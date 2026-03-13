package com.worldpay.usitester;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * WebSocket client for Ingenico USI v1 protocol over iConnect-Ws.
 * Matches the message format from your terminal logs exactly.
 */
public class USIClient {

    public interface Listener {
        void onConnected();
        void onDisconnected(String reason);
        void onMessageSent(String json);
        void onMessageReceived(String json);
        void onError(String error);
    }

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final Handler mainHandler;
    private final Random random = new Random();

    private WebSocket webSocket;
    private Listener listener;
    private boolean connected = false;

    public USIClient() {
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)    // no read timeout for long-lived WS
                .writeTimeout(10, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)   // keep-alive
                .build();
        gson = new GsonBuilder().setPrettyPrinting().create();
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isConnected() {
        return connected;
    }

    /**
     * Connect to the terminal's iConnect-Ws WebSocket server.
     * PCL bridge: terminal at USB-Ethernet gateway (typically 172.16.0.1)
     * WiFi/Ethernet: use terminal's network IP
     */
    public void connect(String host, int port) {
        if (connected) return;

        String url = "ws://" + host + ":" + port;
        Request request = new Request.Builder()
                .url(url)
                .build();

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                connected = true;
                postToMain(() -> {
                    if (listener != null) listener.onConnected();
                });
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                String pretty = prettify(text);
                postToMain(() -> {
                    if (listener != null) listener.onMessageReceived(pretty);
                });
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
                connected = false;
                postToMain(() -> {
                    if (listener != null) listener.onDisconnected("Closed: " + code + " " + reason);
                });
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                connected = false;
                postToMain(() -> {
                    if (listener != null) listener.onDisconnected("Closed: " + code + " " + reason);
                });
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                connected = false;
                String msg = t.getMessage();
                if (response != null) {
                    msg += " (HTTP " + response.code() + ")";
                }
                String finalMsg = msg;
                postToMain(() -> {
                    if (listener != null) {
                        listener.onError(finalMsg);
                        listener.onDisconnected("Connection failed");
                    }
                });
            }
        });
    }

    public void disconnect() {
        if (webSocket != null) {
            webSocket.close(1000, "User disconnect");
            webSocket = null;
        }
        connected = false;
    }

    private String generateFlowId() {
        return String.valueOf(100000 + random.nextInt(900000));
    }

    /**
     * Send a raw JSON string to the terminal.
     */
    public void sendRaw(String json) {
        if (!connected || webSocket == null) return;
        webSocket.send(json);
        postToMain(() -> {
            if (listener != null) listener.onMessageSent(prettify(json));
        });
    }

    // ── USI v1 Commands (matching your terminal log format exactly) ──

    /**
     * Device soft reset — clears current form, returns to idle.
     */
    public void sendSoftReset() {
        JsonObject resource = new JsonObject();
        resource.addProperty("type", "reset");
        resource.addProperty("keep_form", false);
        JsonObject params = new JsonObject();
        params.addProperty("reset_type", "soft");
        resource.add("parameters", params);

        sendRequest("/usi/v1/device", resource);
    }

    /**
     * Device hard reset — full terminal restart.
     */
    public void sendHardReset() {
        JsonObject resource = new JsonObject();
        resource.addProperty("type", "reset");
        resource.addProperty("keep_form", false);
        JsonObject params = new JsonObject();
        params.addProperty("reset_type", "hard");
        resource.add("parameters", params);

        sendRequest("/usi/v1/device", resource);
    }

    /**
     * Device info request — get terminal status/info.
     */
    public void sendDeviceInfo() {
        JsonObject resource = new JsonObject();
        resource.addProperty("type", "info");

        sendRequest("/usi/v1/device", resource);
    }

    /**
     * Sale transaction — amount is in cents (e.g. 1 = $0.01, 100 = $1.00).
     * Uses the same format as your production logs.
     */
    public void sendSale(int amountCents) {
        JsonObject amount = new JsonObject();
        amount.addProperty("cashback", 0);
        amount.addProperty("surcharge", 0);
        amount.addProperty("tax", 0);
        amount.addProperty("vat", 0);
        amount.addProperty("tip", 0);
        amount.addProperty("total", amountCents);

        JsonObject resource = new JsonObject();
        resource.addProperty("txn_type", "sale");
        resource.add("amount", amount);
        resource.addProperty("txn_status_events", false);
        resource.addProperty("display_txn_result", true);
        resource.addProperty("confirm_amount", true);
        resource.addProperty("manual_entry", false);
        resource.addProperty("form", "CESWIPE.K3Z");

        sendRequest("/usi/v1/transaction", resource);
    }

    /**
     * Acknowledge an event from the terminal (required after transaction_completed, etc.)
     */
    public void sendEventAck(String endpoint, String flowId) {
        JsonObject status = new JsonObject();
        status.addProperty("status", "ok");

        JsonObject ack = new JsonObject();
        ack.addProperty("endpoint", endpoint);
        ack.addProperty("flow_id", flowId);
        ack.add("resource", status);

        JsonObject wrapper = new JsonObject();
        wrapper.add("event_ack", ack);

        String json = gson.toJson(wrapper);
        sendRaw(json);
    }

    /**
     * Build and send a USI request envelope matching log format:
     * { "request": { "flow_id": "...", "endpoint": "...", "resource": {...} } }
     */
    private void sendRequest(String endpoint, JsonObject resource) {
        JsonObject request = new JsonObject();
        request.addProperty("flow_id", generateFlowId());
        request.addProperty("endpoint", endpoint);
        request.add("resource", resource);

        JsonObject wrapper = new JsonObject();
        wrapper.add("request", request);

        String json = gson.toJson(wrapper);
        sendRaw(json);
    }

    private String prettify(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            return gson.toJson(obj);
        } catch (Exception e) {
            return json;
        }
    }

    private void postToMain(Runnable r) {
        mainHandler.post(r);
    }

    public void shutdown() {
        disconnect();
        httpClient.dispatcher().executorService().shutdown();
    }
}
