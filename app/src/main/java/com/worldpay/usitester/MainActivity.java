package com.worldpay.usitester;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.provider.Settings;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
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

    @Override
    protected void onResume() {
        super.onResume();
        // Re-scan when returning from tethering settings
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
     * Full diagnostics: network interfaces, USB devices, tethering state,
     * and terminal scan on USB subnets.
     */
    private void runDiagnostics() {
        log("DIAG", "========================================");
        log("DIAG", "  PCL Bridge Diagnostics");
        log("DIAG", "========================================");

        // ── Step 1: Network interfaces ──
        log("DIAG", "");
        log("DIAG", "[1] Network Interfaces:");
        boolean foundUsbEth = false;
        String usbGatewayIp = null;
        String usbSubnet = null;

        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp()) continue;

                String name = iface.getName().toLowerCase();
                StringBuilder sb = new StringBuilder();
                sb.append("  ").append(iface.getName());

                String ipv4 = null;
                for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                    if (addr instanceof Inet4Address) {
                        ipv4 = addr.getHostAddress();
                        sb.append(" → ").append(ipv4);
                    }
                }

                // Detect USB tethering interfaces
                // Android USB tethering: rndis0, usb0, or sometimes eth1
                // Common tethering subnets: 192.168.42.x, 192.168.48.x
                boolean isUsbIface = name.contains("usb") || name.contains("rndis")
                        || (ipv4 != null && (ipv4.startsWith("192.168.42.") || ipv4.startsWith("192.168.48.")));

                if (isUsbIface) {
                    sb.append(" [USB TETHERING]");
                    foundUsbEth = true;
                    if (ipv4 != null) {
                        usbGatewayIp = ipv4;
                        // Derive subnet (e.g., 192.168.42)
                        int lastDot = ipv4.lastIndexOf('.');
                        usbSubnet = ipv4.substring(0, lastDot);
                    }
                } else if (name.contains("wlan") || name.contains("wifi")) {
                    sb.append(" [WIFI]");
                } else if (name.equals("lo")) {
                    continue; // skip loopback
                }

                log("DIAG", sb.toString());
            }
        } catch (Exception e) {
            log("DIAG", "  Error: " + e.getMessage());
        }

        // ── Step 2: USB devices ──
        log("DIAG", "");
        log("DIAG", "[2] USB Devices:");
        boolean foundIngenico = false;
        try {
            UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
            HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
            if (devices.isEmpty()) {
                log("DIAG", "  (none)");
            } else {
                for (UsbDevice device : devices.values()) {
                    String info = String.format(Locale.US,
                            "  VID:%04X PID:%04X %s %s (class:%d)",
                            device.getVendorId(), device.getProductId(),
                            device.getManufacturerName() != null ? device.getManufacturerName() : "",
                            device.getProductName() != null ? device.getProductName() : "",
                            device.getDeviceClass());
                    log("DIAG", info);

                    int vid = device.getVendorId();
                    if (vid == 0x1998 || vid == 0x1DD4 || vid == 0x0B00) {
                        log("DIAG", "    ^ INGENICO TERMINAL");
                        foundIngenico = true;
                    }
                }
            }
        } catch (Exception e) {
            log("DIAG", "  Error: " + e.getMessage());
        }

        // ── Step 3: Status assessment ──
        log("DIAG", "");
        log("DIAG", "[3] Status:");

        if (!foundIngenico) {
            log("DIAG", "  ✗ No Ingenico USB device detected.");
            log("DIAG", "    → Check USB cable connection.");
            log("DIAG", "    → Tablet must be USB HOST (use OTG adapter if needed).");
            log("DIAG", "");
            return;
        }

        log("DIAG", "  ✓ Ingenico terminal detected on USB.");

        if (!foundUsbEth) {
            log("DIAG", "  ✗ No USB Ethernet/tethering interface active.");
            log("DIAG", "");
            log("DIAG", "  ══════════════════════════════════════");
            log("DIAG", "  ACTION REQUIRED: Enable USB Tethering");
            log("DIAG", "  ══════════════════════════════════════");
            log("DIAG", "  The Lane 7000 needs the tablet to share");
            log("DIAG", "  its internet via USB. This creates the");
            log("DIAG", "  PCL bridge network link.");
            log("DIAG", "");
            log("DIAG", "  Go to: Settings → Network/Connections");
            log("DIAG", "       → Hotspot & Tethering");
            log("DIAG", "       → USB Tethering → ON");
            log("DIAG", "");
            log("DIAG", "  Then tap Clear to re-scan.");
            log("DIAG", "");

            // Offer to open tethering settings
            new AlertDialog.Builder(this)
                    .setTitle("Enable USB Tethering")
                    .setMessage("Ingenico terminal detected but USB tethering is off.\n\n"
                            + "USB tethering creates the PCL bridge — it shares the tablet's "
                            + "internet with the terminal via USB.\n\n"
                            + "Open tethering settings now?")
                    .setPositiveButton("Open Settings", (d, w) -> {
                        try {
                            // Try tethering settings directly
                            Intent intent = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
                            startActivity(intent);
                        } catch (Exception e) {
                            // Fallback to general settings
                            startActivity(new Intent(Settings.ACTION_SETTINGS));
                        }
                    })
                    .setNegativeButton("Later", null)
                    .show();
            return;
        }

        log("DIAG", "  ✓ USB tethering active: " + usbGatewayIp);
        log("DIAG", "    Tablet is gateway on " + usbSubnet + ".x subnet.");

        // ── Step 4: Scan USB subnet for terminal's WebSocket port ──
        log("DIAG", "");
        log("DIAG", "[4] Scanning " + usbSubnet + ".0/24 for port 50000...");

        final String scanSubnet = usbSubnet;
        new Thread(() -> {
            String foundIp = null;

            // Also try the standard PCL bridge IPs
            List<String> scanIps = new ArrayList<>();
            // Common terminal IPs on tethering subnets
            for (int i = 1; i <= 254; i++) {
                scanIps.add(scanSubnet + "." + i);
            }
            // Also try standard PCL IPs in case
            scanIps.add("172.16.0.1");
            scanIps.add("172.16.0.2");

            for (String ip : scanIps) {
                try {
                    java.net.Socket s = new java.net.Socket();
                    s.connect(new java.net.InetSocketAddress(ip, 50000), 300);
                    s.close();
                    foundIp = ip;
                    break;
                } catch (Exception ignored) {}
            }

            final String terminalIp = foundIp;
            runOnUiThread(() -> {
                if (terminalIp != null) {
                    log("DIAG", "  ✓ TERMINAL FOUND at " + terminalIp + ":50000");
                    log("DIAG", "");
                    log("DIAG", "  Ready to connect! Tap Connect button.");
                    etHost.setText(terminalIp);
                    setStatus("Terminal found", R.drawable.status_dot_yellow);
                } else {
                    log("DIAG", "  ✗ No terminal responding on port 50000.");
                    log("DIAG", "    The terminal may need a moment after");
                    log("DIAG", "    tethering is enabled. Tap Clear to retry.");
                    log("DIAG", "");
                    log("DIAG", "  If this persists, verify in the terminal's");
                    log("DIAG", "  iConnect-Ws config that:");
                    log("DIAG", "    mode: 0 (server)");
                    log("DIAG", "    usb_pcl: 1");
                    log("DIAG", "    ws_server port: 50000");
                }
            });
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
            tvLog.setText("");
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
