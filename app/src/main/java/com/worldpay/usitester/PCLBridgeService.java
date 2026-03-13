package com.worldpay.usitester;

import android.content.Intent;
import android.net.VpnService;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * VPN service that acts as the PCL bridge between the Ingenico terminal
 * and the tablet's internet connection.
 *
 * Architecture:
 *   Terminal ←USB/Ethernet→ UsbEthernetManager ←→ PCLBridgeService ←→ Internet
 *                                                        ↕
 *                                              TUN interface (VPN)
 *
 * The terminal gets IP 192.168.55.2, we are 192.168.55.1.
 * Terminal traffic goes through the TUN, we read it, and proxy TCP/UDP
 * connections through the tablet's real network interface.
 */
public class PCLBridgeService extends VpnService {

    public static final String TERMINAL_IP = "192.168.55.2";
    public static final String GATEWAY_IP = "192.168.55.1";
    public static final int TERMINAL_SUBNET = 24;

    public interface LogListener {
        void onBridgeLog(String message);
    }

    private final IBinder binder = new LocalBinder();
    private ParcelFileDescriptor vpnFd;
    private volatile boolean running = false;
    private UsbEthernetManager usbManager;
    private LogListener logListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private FileInputStream tunIn;
    private FileOutputStream tunOut;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final ConcurrentHashMap<String, Socket> tcpProxyMap = new ConcurrentHashMap<>();

    // Simple ARP table: IP → MAC
    private byte[] terminalMac = null;
    // Our fake MAC for the bridge
    private static final byte[] GATEWAY_MAC = {0x02, 0x00, 0x55, 0x00, 0x00, 0x01};

    public class LocalBinder extends Binder {
        PCLBridgeService getService() {
            return PCLBridgeService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void setLogListener(LogListener listener) {
        this.logListener = listener;
    }

    public void setUsbManager(UsbEthernetManager mgr) {
        this.usbManager = mgr;
    }

    /**
     * Start the VPN tunnel and bridge.
     */
    public boolean startBridge() {
        if (running) return true;

        try {
            Builder builder = new Builder();
            builder.addAddress(GATEWAY_IP, TERMINAL_SUBNET);
            builder.addRoute(TERMINAL_IP, 32);
            builder.setMtu(1500);
            builder.setSession("PCL Bridge");

            // Don't route the tablet's own traffic through the VPN
            // Only the terminal subnet goes through us
            try {
                builder.addDisallowedApplication(getPackageName());
            } catch (Exception ignored) {}

            vpnFd = builder.establish();
            if (vpnFd == null) {
                log("VPN establish failed - did you approve the VPN dialog?");
                return false;
            }

            tunIn = new FileInputStream(vpnFd.getFileDescriptor());
            tunOut = new FileOutputStream(vpnFd.getFileDescriptor());

            running = true;

            // Read from TUN (terminal → internet): proxy outbound traffic
            executor.submit(this::tunReadLoop);

            log("PCL Bridge started");
            log("  Gateway: " + GATEWAY_IP);
            log("  Terminal will be: " + TERMINAL_IP);
            log("  Proxying terminal traffic to internet");

            return true;
        } catch (Exception e) {
            log("Bridge start error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Stop the bridge.
     */
    public void stopBridge() {
        running = false;
        try {
            if (vpnFd != null) vpnFd.close();
        } catch (IOException ignored) {}

        for (Socket s : tcpProxyMap.values()) {
            try { s.close(); } catch (Exception ignored) {}
        }
        tcpProxyMap.clear();

        log("PCL Bridge stopped");
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Called by UsbEthernetManager when an Ethernet frame arrives from the terminal.
     * We extract the IP packet and inject it into the TUN interface.
     */
    public void onTerminalFrame(byte[] frame, int length) {
        if (!running || tunOut == null) return;

        // Parse Ethernet header (14 bytes): dst(6) + src(6) + type(2)
        if (length < 14) return;

        // Save terminal MAC from source
        if (terminalMac == null) {
            terminalMac = new byte[6];
            System.arraycopy(frame, 6, terminalMac, 0, 6);
            log("Terminal MAC: " + macToString(terminalMac));
        }

        int etherType = ((frame[12] & 0xFF) << 8) | (frame[13] & 0xFF);

        if (etherType == 0x0806) {
            // ARP - handle locally
            handleArp(frame, length);
            return;
        }

        if (etherType == 0x0800) {
            // IPv4 - inject into TUN (strip Ethernet header)
            try {
                tunOut.write(frame, 14, length - 14);
                tunOut.flush();
            } catch (IOException e) {
                // TUN write failed
            }
        }
    }

    /**
     * Handle ARP requests from the terminal.
     * We respond pretending to be the gateway.
     */
    private void handleArp(byte[] frame, int length) {
        if (length < 42) return;

        int opcode = ((frame[20] & 0xFF) << 8) | (frame[21] & 0xFF);
        if (opcode != 1) return; // Only handle ARP requests

        // Target IP (bytes 38-41)
        String targetIp = String.format("%d.%d.%d.%d",
                frame[38] & 0xFF, frame[39] & 0xFF, frame[40] & 0xFF, frame[41] & 0xFF);

        if (GATEWAY_IP.equals(targetIp)) {
            log("ARP: Terminal asking for gateway MAC");

            // Build ARP reply
            byte[] reply = new byte[42];
            // Ethernet header
            System.arraycopy(frame, 6, reply, 0, 6);  // dst = sender's MAC
            System.arraycopy(GATEWAY_MAC, 0, reply, 6, 6); // src = our MAC
            reply[12] = 0x08; reply[13] = 0x06; // ARP

            // ARP payload
            reply[14] = 0x00; reply[15] = 0x01; // Hardware: Ethernet
            reply[16] = 0x08; reply[17] = 0x00; // Protocol: IPv4
            reply[18] = 6;    // HW addr length
            reply[19] = 4;    // Proto addr length
            reply[20] = 0x00; reply[21] = 0x02; // Opcode: Reply

            // Sender (us): our MAC + gateway IP
            System.arraycopy(GATEWAY_MAC, 0, reply, 22, 6);
            byte[] gwIpBytes = ipToBytes(GATEWAY_IP);
            System.arraycopy(gwIpBytes, 0, reply, 28, 4);

            // Target: terminal MAC + terminal IP
            System.arraycopy(frame, 6, reply, 32, 6);
            System.arraycopy(frame, 28, reply, 38, 4);

            // Send back to terminal via USB
            if (usbManager != null) {
                usbManager.sendFrame(reply, reply.length);
            }
        }
    }

    /**
     * Read IP packets from TUN (responses from internet → terminal).
     * Wrap them in Ethernet frames and send to terminal via USB.
     */
    private void tunReadLoop() {
        byte[] buffer = new byte[2048];
        while (running) {
            try {
                int len = tunIn.read(buffer);
                if (len > 0 && usbManager != null && terminalMac != null) {
                    // Wrap in Ethernet frame
                    byte[] frame = new byte[14 + len];
                    System.arraycopy(terminalMac, 0, frame, 0, 6);   // dst: terminal
                    System.arraycopy(GATEWAY_MAC, 0, frame, 6, 6);   // src: us
                    frame[12] = 0x08; frame[13] = 0x00;               // IPv4

                    System.arraycopy(buffer, 0, frame, 14, len);
                    usbManager.sendFrame(frame, frame.length);
                }
            } catch (IOException e) {
                if (running) log("TUN read error: " + e.getMessage());
                break;
            }
        }
    }

    // ── DHCP Server (minimal) ──

    /**
     * Handle DHCP from terminal. We provide it with a static IP config.
     * Called when we detect a DHCP request in an IP packet from the terminal.
     */
    public void handleDhcp(byte[] ipPacket, int length) {
        // DHCP handling is complex; for now the terminal should be configured
        // with a static IP or we rely on the USB stack's built-in addressing.
        // Most Ingenico PCL implementations use link-local or static.
        log("DHCP request detected from terminal");
    }

    // ── Utility ──

    private byte[] ipToBytes(String ip) {
        String[] parts = ip.split("\\.");
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) bytes[i] = (byte) Integer.parseInt(parts[i]);
        return bytes;
    }

    private String macToString(byte[] mac) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mac.length; i++) {
            if (i > 0) sb.append(":");
            sb.append(String.format("%02X", mac[i] & 0xFF));
        }
        return sb.toString();
    }

    private void log(String msg) {
        mainHandler.post(() -> { if (logListener != null) logListener.onBridgeLog(msg); });
    }

    @Override
    public void onDestroy() {
        stopBridge();
        executor.shutdownNow();
        super.onDestroy();
    }
}
