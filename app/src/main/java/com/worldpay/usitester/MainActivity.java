package com.worldpay.usitester;

import android.app.AlertDialog;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
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

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements USIClient.Listener {

    private USIClient client;

    private TextInputEditText etHost, etPort;
    private MaterialButton btnConnect, btnDisconnect;
    private MaterialButton btnSoftReset, btnHardReset, btnDeviceInfo, btnTestSale;
    private MaterialButton btnCustomAmount, btnSendRaw, btnClearLog;
    private TextView tvStatus, tvLatency, tvLog;
    private View statusDot;
    private ScrollView scrollLog;

    private long lastSendTime;
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        client = new USIClient();
        client.setListener(this);

        bindViews();
        setupClickListeners();

        // Auto-run diagnostics on launch
        runDiagnostics();
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

    /**
     * Dump all network interfaces, USB devices, and attempt a TCP ping
     * to the default terminal IP. This helps diagnose PCL bridge issues.
     */
    private void runDiagnostics() {
        log("DIAG", "=== Network Interface Scan ===");
        try {
            boolean foundUsb = false;
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp()) continue;
                StringBuilder sb = new StringBuilder();
                sb.append(iface.getName()).append(" (").append(iface.getDisplayName()).append(")");
                for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                    if (addr instanceof Inet4Address) {
                        sb.append(" → ").append(addr.getHostAddress());
                    }
                }
                String name = iface.getName().toLowerCase();
                // USB RNDIS/ECM interfaces are typically usb0, rndis0, or eth1+
                if (name.contains("usb") || name.contains("rndis")) {
                    sb.append(" [USB ETHERNET]");
                    foundUsb = true;
                }
                log("DIAG", "  " + sb.toString());
            }
            if (!foundUsb) {
                log("DIAG", "  ⚠ No USB Ethernet interface detected!");
                log("DIAG", "  PCL bridge requires USB host mode (OTG).");
                log("DIAG", "  Check: Is terminal USB cable connected?");
                log("DIAG", "  Check: Does tablet support USB OTG?");
            }
        } catch (Exception e) {
            log("DIAG", "  Error scanning interfaces: " + e.getMessage());
        }

        // Scan USB devices
        log("DIAG", "=== USB Device Scan ===");
        try {
            UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
            HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
            if (devices.isEmpty()) {
                log("DIAG", "  No USB devices found");
            } else {
                for (UsbDevice device : devices.values()) {
                    String info = String.format(Locale.US,
                            "  VID:%04X PID:%04X — %s %s (class:%d)",
                            device.getVendorId(), device.getProductId(),
                            device.getManufacturerName() != null ? device.getManufacturerName() : "?",
                            device.getProductName() != null ? device.getProductName() : "?",
                            device.getDeviceClass());
                    log("DIAG", info);

                    // Flag Ingenico devices
                    int vid = device.getVendorId();
                    if (vid == 0x1998 || vid == 0x1DD4 || vid == 0x0B00) {
                        log("DIAG", "  ↑ INGENICO DEVICE DETECTED");
                    }
                }
            }
        } catch (Exception e) {
            log("DIAG", "  Error scanning USB: " + e.getMessage());
        }

        // Background TCP reachability test
        String host = etHost.getText().toString().trim();
        String portStr = etPort.getText().toString().trim();
        log("DIAG", "=== TCP Ping " + host + ":" + portStr + " ===");

        new Thread(() -> {
            try {
                int port = Integer.parseInt(portStr);
                long start = System.currentTimeMillis();
                java.net.Socket sock = new java.net.Socket();
                sock.connect(new java.net.InetSocketAddress(host, port), 3000);
                long elapsed = System.currentTimeMillis() - start;
                sock.close();
                runOnUiThread(() -> log("DIAG", "  TCP connection OK! (" + elapsed + "ms)"));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    log("DIAG", "  TCP connection FAILED: " + e.getMessage());
                    log("DIAG", "  Terminal not reachable at this IP/port.");

                    // Try common alternative IPs
                    log("DIAG", "  Will scan common PCL bridge IPs...");
                });
                // Scan a few common USB bridge IPs
                String[] tryIps = {"172.16.0.1", "172.16.0.2", "192.168.225.1", "192.168.1.1", "10.0.0.1"};
                for (String tryIp : tryIps) {
                    try {
                        java.net.Socket s = new java.net.Socket();
                        s.connect(new java.net.InetSocketAddress(tryIp, 50000), 1500);
                        s.close();
                        runOnUiThread(() -> {
                            log("DIAG", "  ✓ FOUND terminal at " + tryIp + ":50000!");
                            log("DIAG", "  Update the IP field and tap Connect.");
                            etHost.setText(tryIp);
                        });
                        return;
                    } catch (Exception ignored) {}
                }
                runOnUiThread(() -> log("DIAG", "  No terminal found on common IPs."));
            }
        }).start();
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
            client.connect(host, port);
            btnConnect.setEnabled(false);
        });

        btnDisconnect.setOnClickListener(v -> {
            client.disconnect();
            setStatus("Disconnected", R.drawable.status_dot_red);
            setCommandsEnabled(false);
            btnConnect.setEnabled(true);
            btnDisconnect.setEnabled(false);
            log("SYS", "Disconnected by user");
        });

        btnSoftReset.setOnClickListener(v -> {
            log("CMD", "Sending soft reset...");
            lastSendTime = System.currentTimeMillis();
            client.sendSoftReset();
        });

        btnHardReset.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Hard Reset")
                    .setMessage("This will restart the terminal. Proceed?")
                    .setPositiveButton("Reset", (d, w) -> {
                        log("CMD", "Sending hard reset...");
                        lastSendTime = System.currentTimeMillis();
                        client.sendHardReset();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        btnDeviceInfo.setOnClickListener(v -> {
            log("CMD", "Requesting device info...");
            lastSendTime = System.currentTimeMillis();
            client.sendDeviceInfo();
        });

        btnTestSale.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Test Sale")
                    .setMessage("Send $0.01 test sale to terminal?\nCard will be prompted.")
                    .setPositiveButton("Send", (d, w) -> {
                        log("CMD", "Sending $0.01 test sale...");
                        lastSendTime = System.currentTimeMillis();
                        client.sendSale(1);  // 1 cent
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
                            client.sendSale(cents);
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
                        client.sendRaw(json);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        btnClearLog.setOnClickListener(v -> {
            tvLog.setText("Log cleared.\n");
            runDiagnostics();
        });
    }

    // ── USIClient.Listener callbacks (already on main thread) ──

    @Override
    public void onConnected() {
        setStatus("Connected", R.drawable.status_dot_green);
        setCommandsEnabled(true);
        btnConnect.setEnabled(false);
        btnDisconnect.setEnabled(true);
        log("SYS", "WebSocket connected to terminal");
    }

    @Override
    public void onDisconnected(String reason) {
        setStatus("Disconnected", R.drawable.status_dot_red);
        setCommandsEnabled(false);
        btnConnect.setEnabled(true);
        btnDisconnect.setEnabled(false);
        log("SYS", "Disconnected: " + reason);
    }

    @Override
    public void onMessageSent(String json) {
        log("SEND", json);
    }

    @Override
    public void onMessageReceived(String json) {
        long latency = System.currentTimeMillis() - lastSendTime;
        tvLatency.setText(latency + "ms");

        // Detect message type from the JSON
        String msgType = detectMessageType(json);
        log("RECV [" + msgType + "]", json);

        // Auto-ack transaction_completed events
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
                        client.sendEventAck(endpoint, flowId);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void log(String tag, String message) {
        String ts = sdf.format(new Date());
        String line = "[" + ts + "] " + tag + ": " + message + "\n";
        tvLog.append(line);

        // Auto-scroll to bottom
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
        client.shutdown();
        super.onDestroy();
    }
}
