package com.worldpay.usitester;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.VpnService;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements USIClient.Listener {

    private static final int VPN_REQUEST_CODE = 100;

    private USIClient usiClient;
    private UsbEthernetManager usbManager;
    private PCLBridgeService bridgeService;
    private boolean bridgeBound = false;

    private TextInputEditText etHost, etPort;
    private MaterialButton btnConnect, btnDisconnect;
    private MaterialButton btnSoftReset, btnHardReset, btnDeviceInfo, btnTestSale;
    private MaterialButton btnCustomAmount, btnSendRaw, btnClearLog;
    private TextView tvStatus, tvLatency, tvLog;
    private View statusDot;
    private ScrollView scrollLog;

    private long lastSendTime;
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    // Bridge state
    private boolean usbReady = false;
    private boolean vpnReady = false;

    private final ServiceConnection bridgeConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            PCLBridgeService.LocalBinder binder = (PCLBridgeService.LocalBinder) service;
            bridgeService = binder.getService();
            bridgeBound = true;

            bridgeService.setLogListener(msg -> log("BRIDGE", msg));
            bridgeService.setUsbManager(usbManager);

            log("SYS", "Bridge service connected");

            // If USB is already ready, start the bridge
            if (usbReady) {
                startVpnBridge();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bridgeService = null;
            bridgeBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        usiClient = new USIClient();
        usiClient.setListener(this);

        bindViews();
        setupClickListeners();

        // Default to the PCL bridge gateway IP
        etHost.setText(PCLBridgeService.TERMINAL_IP);

        log("SYS", "========================================");
        log("SYS", "  USI Terminal Tester + PCL Bridge");
        log("SYS", "========================================");
        log("SYS", "");
        log("SYS", "This app acts as the PCL bridge between");
        log("SYS", "the Lane 7000 and the internet.");
        log("SYS", "");
        log("SYS", "1. USB link to terminal (Ethernet over USB)");
        log("SYS", "2. Internet bridge (terminal → tablet WiFi)");
        log("SYS", "3. WebSocket for USI commands");
        log("SYS", "");

        // Start USB detection
        initUsb();
    }

    private void bindViews() {
        etHost = findViewById(R.id.etHost);
        etPort = findViewById(R.id.etPort);
        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        btnSoftReset = findViewById(R.id.btnSoftReset);
        btnHardReset = findViewById(R.id.btnHardReset);
        btnDeviceInfo = findViewById(R.id.btnDeviceInfo);
        btnTestSale = findViewById(R.id.btnTestSale);
        btnCustomAmount = findViewById(R.id.btnCustomAmount);
        btnSendRaw = findViewById(R.id.btnSendRaw);
        btnClearLog = findViewById(R.id.btnClearLog);
        tvStatus = findViewById(R.id.tvStatus);
        tvLatency = findViewById(R.id.tvLatency);
        tvLog = findViewById(R.id.tvLog);
        statusDot = findViewById(R.id.statusDot);
        scrollLog = findViewById(R.id.scrollLog);
    }

    private void initUsb() {
        log("USB", "Scanning for Ingenico terminal...");
        setStatus("Scanning USB...", R.drawable.status_dot_yellow);

        usbManager = new UsbEthernetManager(this);
        usbManager.setListener(new UsbEthernetManager.Listener() {
            @Override
            public void onLog(String message) {
                log("USB", message);
            }

            @Override
            public void onUsbReady(String terminalInfo) {
                log("USB", "Terminal ready: " + terminalInfo);
                usbReady = true;
                setStatus("USB ready, starting bridge...", R.drawable.status_dot_yellow);

                // Start the VPN bridge service
                bindBridgeService();
            }

            @Override
            public void onUsbError(String error) {
                log("USB", "ERROR: " + error);
                setStatus("USB Error", R.drawable.status_dot_red);
            }

            @Override
            public void onUsbDisconnected() {
                log("USB", "Terminal disconnected");
                usbReady = false;
                vpnReady = false;
                setStatus("Disconnected", R.drawable.status_dot_red);
                setCommandsEnabled(false);
                if (usiClient.isConnected()) {
                    usiClient.disconnect();
                }
            }

            @Override
            public void onEthernetFrame(byte[] frame, int length) {
                // Forward frames from terminal to the bridge
                if (bridgeService != null && bridgeService.isRunning()) {
                    bridgeService.onTerminalFrame(frame, length);
                }
            }
        });

        usbManager.start();
    }

    private void bindBridgeService() {
        Intent intent = new Intent(this, PCLBridgeService.class);
        startService(intent);
        bindService(intent, bridgeConnection, Context.BIND_AUTO_CREATE);
    }

    private void startVpnBridge() {
        // Check if VPN permission is granted
        Intent vpnIntent = VpnService.prepare(this);
        if (vpnIntent != null) {
            log("SYS", "VPN permission needed — approve the dialog");
            startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
        } else {
            // Already have VPN permission
            activateBridge();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                log("SYS", "VPN permission granted");
                activateBridge();
            } else {
                log("SYS", "VPN permission denied — bridge cannot function");
                setStatus("VPN denied", R.drawable.status_dot_red);
            }
        }
    }

    private void activateBridge() {
        if (bridgeService == null) {
            log("SYS", "Waiting for bridge service...");
            return;
        }

        if (bridgeService.startBridge()) {
            vpnReady = true;
            setStatus("Bridge active — connecting...", R.drawable.status_dot_yellow);
            log("SYS", "");
            log("SYS", "PCL bridge is active!");
            log("SYS", "Terminal should get IP: " + PCLBridgeService.TERMINAL_IP);
            log("SYS", "Internet is bridged through tablet WiFi.");
            log("SYS", "");

            log("SYS", "Waiting for terminal to DHCP and come online...");
            log("SYS", "Watch for DHCP/ARP activity in the log.");
            log("SYS", "When ready, tap Connect to open WebSocket.");
            log("SYS", "");

            // Try auto-connecting after giving terminal time for DHCP
            scrollLog.postDelayed(this::autoConnectWebSocket, 8000);
        } else {
            setStatus("Bridge failed", R.drawable.status_dot_red);
        }
    }

    private void autoConnectWebSocket() {
        if (!vpnReady || !usbReady) return;

        String host = etHost.getText().toString().trim();
        String portStr = etPort.getText().toString().trim();
        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            return;
        }

        log("SYS", "Auto-connecting WebSocket to " + host + ":" + port + "...");
        setStatus("Connecting WebSocket...", R.drawable.status_dot_yellow);
        usiClient.connect(host, port);
        btnConnect.setEnabled(false);
    }

    private void setupClickListeners() {
        btnConnect.setOnClickListener(v -> {
            String host = etHost.getText().toString().trim();
            String portStr = etPort.getText().toString().trim();
            if (host.isEmpty() || portStr.isEmpty()) {
                log("ERROR", "Enter host and port");
                return;
            }
            int port;
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                log("ERROR", "Invalid port");
                return;
            }

            setStatus("Connecting...", R.drawable.status_dot_yellow);
            log("SYS", "Connecting to ws://" + host + ":" + port + " ...");
            usiClient.connect(host, port);
            btnConnect.setEnabled(false);
        });

        btnDisconnect.setOnClickListener(v -> {
            usiClient.disconnect();
            setStatus("Disconnected", R.drawable.status_dot_red);
            setCommandsEnabled(false);
            btnConnect.setEnabled(true);
            btnDisconnect.setEnabled(false);
            log("SYS", "Disconnected by user");
        });

        btnSoftReset.setOnClickListener(v -> {
            log("CMD", "Sending soft reset...");
            lastSendTime = System.currentTimeMillis();
            usiClient.sendSoftReset();
        });

        btnHardReset.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Hard Reset")
                    .setMessage("This will restart the terminal. Proceed?")
                    .setPositiveButton("Reset", (d, w) -> {
                        log("CMD", "Sending hard reset...");
                        lastSendTime = System.currentTimeMillis();
                        usiClient.sendHardReset();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        btnDeviceInfo.setOnClickListener(v -> {
            log("CMD", "Requesting device info...");
            lastSendTime = System.currentTimeMillis();
            usiClient.sendDeviceInfo();
        });

        btnTestSale.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Test Sale")
                    .setMessage("Send $0.01 test sale to terminal?\nCard will be prompted.")
                    .setPositiveButton("Send", (d, w) -> {
                        log("CMD", "Sending $0.01 test sale...");
                        lastSendTime = System.currentTimeMillis();
                        usiClient.sendSale(1);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        btnCustomAmount.setOnClickListener(v -> {
            EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            input.setHint("e.g. 1.50");
            new AlertDialog.Builder(this)
                    .setTitle("Sale Amount (dollars)")
                    .setView(input)
                    .setPositiveButton("Send", (d, w) -> {
                        try {
                            double dollars = Double.parseDouble(input.getText().toString());
                            int cents = (int) Math.round(dollars * 100);
                            if (cents <= 0) {
                                log("ERROR", "Amount must be > 0");
                                return;
                            }
                            log("CMD", "Sending sale for $" + String.format(Locale.US, "%.2f", dollars) + " (" + cents + " cents)...");
                            lastSendTime = System.currentTimeMillis();
                            usiClient.sendSale(cents);
                        } catch (NumberFormatException e) {
                            log("ERROR", "Invalid amount");
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        btnSendRaw.setOnClickListener(v -> {
            EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            input.setMinLines(5);
            input.setHint("Paste USI JSON here...");
            input.setTextSize(12);
            new AlertDialog.Builder(this)
                    .setTitle("Send Raw JSON")
                    .setView(input)
                    .setPositiveButton("Send", (d, w) -> {
                        String json = input.getText().toString().trim();
                        if (json.isEmpty()) return;
                        log("CMD", "Sending raw JSON...");
                        lastSendTime = System.currentTimeMillis();
                        usiClient.sendRaw(json);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        btnClearLog.setOnClickListener(v -> {
            tvLog.setText("");
            log("SYS", "Log cleared. Current state:");
            log("SYS", "  USB: " + (usbReady ? "ready" : "not connected"));
            log("SYS", "  Bridge: " + (vpnReady ? "active" : "inactive"));
            log("SYS", "  WebSocket: " + (usiClient.isConnected() ? "connected" : "disconnected"));
        });
    }

    // ── USIClient.Listener callbacks ──

    @Override
    public void onConnected() {
        setStatus("Connected", R.drawable.status_dot_green);
        setCommandsEnabled(true);
        btnConnect.setEnabled(false);
        btnDisconnect.setEnabled(true);
        log("SYS", "WebSocket connected to terminal!");
        log("SYS", "Ready to send USI commands.");
    }

    @Override
    public void onDisconnected(String reason) {
        setStatus(vpnReady ? "Bridge active" : "Disconnected",
                vpnReady ? R.drawable.status_dot_yellow : R.drawable.status_dot_red);
        setCommandsEnabled(false);
        btnConnect.setEnabled(true);
        btnDisconnect.setEnabled(false);
        log("SYS", "WebSocket disconnected: " + reason);
    }

    @Override
    public void onMessageSent(String json) {
        log("SEND", json);
    }

    @Override
    public void onMessageReceived(String json) {
        long latency = System.currentTimeMillis() - lastSendTime;
        tvLatency.setText(latency + "ms");

        String msgType = detectMessageType(json);
        log("RECV [" + msgType + "]", json);

        autoAckIfNeeded(json);
    }

    @Override
    public void onError(String error) {
        log("ERROR", error);
    }

    // ── Helpers ──

    private String detectMessageType(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (obj.has("response")) {
                JsonObject resp = obj.getAsJsonObject("response");
                String status = "";
                if (resp.has("resource")) {
                    JsonObject res = resp.getAsJsonObject("resource");
                    if (res.has("status")) status = res.get("status").getAsString();
                    if (res.has("error_info")) status = "error";
                }
                return "response:" + status;
            } else if (obj.has("event")) {
                JsonObject event = obj.getAsJsonObject("event");
                if (event.has("resource")) {
                    JsonObject res = event.getAsJsonObject("resource");
                    if (res.has("type")) return "event:" + res.get("type").getAsString();
                    if (res.has("status")) return "event:" + res.get("status").getAsString();
                }
                return "event";
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    private void autoAckIfNeeded(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (obj.has("event")) {
                JsonObject event = obj.getAsJsonObject("event");
                String endpoint = event.has("endpoint") ? event.get("endpoint").getAsString() : "";
                String flowId = event.has("flow_id") ? event.get("flow_id").getAsString() : "";
                if (event.has("resource")) {
                    JsonObject res = event.getAsJsonObject("resource");
                    String type = res.has("type") ? res.get("type").getAsString() : "";
                    if ("transaction_completed".equals(type) && !flowId.isEmpty()) {
                        log("SYS", "Auto-sending event_ack for flow " + flowId);
                        usiClient.sendEventAck(endpoint, flowId);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void log(String tag, String message) {
        String ts = sdf.format(new Date());
        String line = "[" + ts + "] " + tag + ": " + message + "\n";
        tvLog.append(line);
        scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
    }

    private void setStatus(String text, int dotDrawable) {
        tvStatus.setText(text);
        statusDot.setBackgroundResource(dotDrawable);
    }

    private void setCommandsEnabled(boolean enabled) {
        btnSoftReset.setEnabled(enabled);
        btnHardReset.setEnabled(enabled);
        btnDeviceInfo.setEnabled(enabled);
        btnTestSale.setEnabled(enabled);
        btnCustomAmount.setEnabled(enabled);
        btnSendRaw.setEnabled(enabled);
    }

    @Override
    protected void onDestroy() {
        usiClient.shutdown();
        if (usbManager != null) usbManager.stop();
        if (bridgeBound) {
            if (bridgeService != null) bridgeService.stopBridge();
            unbindService(bridgeConnection);
        }
        super.onDestroy();
    }
}
