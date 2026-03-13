package com.worldpay.usitester;

import android.app.AlertDialog;
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

import java.text.SimpleDateFormat;
import java.util.Date;
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

    /**
     * Auto-send event_ack for transaction_completed events,
     * matching the pattern from your production logs.
     */
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
