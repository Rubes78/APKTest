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
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * VPN service that acts as the PCL bridge between the Ingenico terminal
 * and the tablet's internet connection.
 *
 * Includes a minimal DHCP server so the terminal can obtain an IP address.
 *
 * Architecture:
 *   Terminal ←USB/Ethernet→ UsbEthernetManager ←→ PCLBridgeService ←→ Internet
 *                                                        ↕
 *                                              TUN interface (VPN)
 */
public class PCLBridgeService extends VpnService {

    public static final String TERMINAL_IP = "192.168.55.2";
    public static final String GATEWAY_IP = "192.168.55.1";
    public static final String SUBNET_MASK = "255.255.255.0";
    public static final String DNS_PRIMARY = "8.8.8.8";
    public static final String DNS_SECONDARY = "8.8.4.4";
    public static final int TERMINAL_SUBNET = 24;
    public static final int DHCP_LEASE_TIME = 86400; // 24 hours

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

    // Frame counters for diagnostics
    private final AtomicInteger framesFromTerminal = new AtomicInteger(0);
    private final AtomicInteger framesToTerminal = new AtomicInteger(0);

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

    public boolean startBridge() {
        if (running) return true;

        try {
            Builder builder = new Builder();
            builder.addAddress(GATEWAY_IP, TERMINAL_SUBNET);
            builder.addRoute("192.168.55.0", TERMINAL_SUBNET);
            builder.setMtu(1500);
            builder.setSession("PCL Bridge");
            builder.addDnsServer(DNS_PRIMARY);

            // CRITICAL: Don't route our own app's traffic through the VPN
            // otherwise WebSocket to terminal would loop
            try {
                builder.addDisallowedApplication(getPackageName());
            } catch (Exception e) {
                log("Warning: could not exclude app from VPN: " + e.getMessage());
            }

            vpnFd = builder.establish();
            if (vpnFd == null) {
                log("VPN establish failed - did you approve the VPN dialog?");
                return false;
            }

            tunIn = new FileInputStream(vpnFd.getFileDescriptor());
            tunOut = new FileOutputStream(vpnFd.getFileDescriptor());

            running = true;

            // Read from TUN (internet responses → terminal)
            executor.submit(this::tunReadLoop);

            log("PCL Bridge VPN started");
            log("  Gateway (us): " + GATEWAY_IP);
            log("  Terminal will get: " + TERMINAL_IP);
            log("  DNS: " + DNS_PRIMARY);
            log("  Waiting for terminal DHCP/ARP...");

            return true;
        } catch (Exception e) {
            log("Bridge start error: " + e.getMessage());
            return false;
        }
    }

    public void stopBridge() {
        running = false;
        try {
            if (vpnFd != null) vpnFd.close();
        } catch (IOException ignored) {}

        for (Socket s : tcpProxyMap.values()) {
            try { s.close(); } catch (Exception ignored) {}
        }
        tcpProxyMap.clear();

        log("PCL Bridge stopped. Frames in:" + framesFromTerminal.get() +
                " out:" + framesToTerminal.get());
    }

    public boolean isRunning() {
        return running;
    }

    public String getStats() {
        return "Frames in:" + framesFromTerminal.get() +
                " out:" + framesToTerminal.get() +
                " MAC:" + (terminalMac != null ? macToString(terminalMac) : "unknown");
    }

    /**
     * Called by UsbEthernetManager when an Ethernet frame arrives from the terminal.
     */
    public void onTerminalFrame(byte[] frame, int length) {
        if (!running) return;

        int count = framesFromTerminal.incrementAndGet();
        if (count <= 5 || count % 100 == 0) {
            log("Frame from terminal #" + count + " (" + length + " bytes)");
        }

        // Parse Ethernet header (14 bytes): dst(6) + src(6) + type(2)
        if (length < 14) {
            log("Frame too short: " + length);
            return;
        }

        // Save terminal MAC from source
        if (terminalMac == null) {
            terminalMac = new byte[6];
            System.arraycopy(frame, 6, terminalMac, 0, 6);
            log("Terminal MAC learned: " + macToString(terminalMac));
        }

        int etherType = ((frame[12] & 0xFF) << 8) | (frame[13] & 0xFF);

        if (etherType == 0x0806) {
            log("ARP frame received");
            handleArp(frame, length);
            return;
        }

        if (etherType == 0x0800 && length > 14) {
            // IPv4 — check for DHCP (UDP port 67/68)
            byte[] ipPacket = new byte[length - 14];
            System.arraycopy(frame, 14, ipPacket, 0, length - 14);

            if (isDhcpPacket(ipPacket)) {
                log("DHCP packet from terminal");
                handleDhcp(frame, ipPacket);
                return;
            }

            // Regular IP traffic — inject into TUN for routing
            if (tunOut != null) {
                try {
                    tunOut.write(ipPacket);
                    tunOut.flush();
                } catch (IOException e) {
                    log("TUN write error: " + e.getMessage());
                }
            }
        } else {
            log("Unknown EtherType: 0x" + String.format("%04X", etherType));
        }
    }

    /**
     * Check if an IP packet is a DHCP packet (UDP src:68 dst:67 or src:67 dst:68).
     */
    private boolean isDhcpPacket(byte[] ip) {
        if (ip.length < 28) return false;
        // IP header: check protocol = UDP (17)
        if ((ip[9] & 0xFF) != 17) return false;
        // IP header length
        int ihl = (ip[0] & 0x0F) * 4;
        if (ip.length < ihl + 8) return false;
        // UDP ports
        int srcPort = ((ip[ihl] & 0xFF) << 8) | (ip[ihl + 1] & 0xFF);
        int dstPort = ((ip[ihl + 2] & 0xFF) << 8) | (ip[ihl + 3] & 0xFF);
        return (srcPort == 68 && dstPort == 67); // Client → Server
    }

    /**
     * Handle DHCP: respond with OFFER/ACK assigning TERMINAL_IP to the terminal.
     */
    private void handleDhcp(byte[] ethFrame, byte[] ipPacket) {
        int ihl = (ipPacket[0] & 0x0F) * 4;
        int udpStart = ihl;
        int dhcpStart = udpStart + 8; // UDP header is 8 bytes

        if (ipPacket.length < dhcpStart + 240) {
            log("DHCP packet too short");
            return;
        }

        // DHCP message type is in options (after 240 byte fixed header)
        byte dhcpMsgType = 0;
        byte[] clientXid = new byte[4];
        byte[] clientMac = new byte[6];

        // Extract XID (bytes 4-7 of DHCP)
        System.arraycopy(ipPacket, dhcpStart + 4, clientXid, 0, 4);
        // Extract client MAC (bytes 28-33 of DHCP)
        System.arraycopy(ipPacket, dhcpStart + 28, clientMac, 0, 6);

        // Parse DHCP options starting at dhcpStart + 240
        // First check magic cookie (99.130.83.99)
        int optStart = dhcpStart + 236;
        if (ipPacket.length > optStart + 4) {
            if ((ipPacket[optStart] & 0xFF) == 99 && (ipPacket[optStart + 1] & 0xFF) == 130 &&
                    (ipPacket[optStart + 2] & 0xFF) == 83 && (ipPacket[optStart + 3] & 0xFF) == 99) {
                int pos = optStart + 4;
                while (pos < ipPacket.length - 1) {
                    int opt = ipPacket[pos] & 0xFF;
                    if (opt == 255) break; // End
                    if (opt == 0) { pos++; continue; } // Pad
                    int len = ipPacket[pos + 1] & 0xFF;
                    if (opt == 53 && len >= 1) { // DHCP Message Type
                        dhcpMsgType = ipPacket[pos + 2];
                    }
                    pos += 2 + len;
                }
            }
        }

        String typeStr;
        byte responseType;
        if (dhcpMsgType == 1) {
            typeStr = "DISCOVER";
            responseType = 2; // OFFER
        } else if (dhcpMsgType == 3) {
            typeStr = "REQUEST";
            responseType = 5; // ACK
        } else {
            log("DHCP: ignoring type " + dhcpMsgType);
            return;
        }
        log("DHCP " + typeStr + " from " + macToString(clientMac));

        // Build DHCP response
        byte[] dhcpReply = buildDhcpResponse(responseType, clientXid, clientMac);
        if (dhcpReply == null) return;

        // Wrap in UDP
        byte[] udp = buildUdpPacket(67, 68, dhcpReply);
        // Wrap in IP (src=gateway, dst=255.255.255.255 for broadcast)
        byte[] ip = buildIpPacket(ipToBytes(GATEWAY_IP), new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255}, 17, udp);
        // Wrap in Ethernet (broadcast)
        byte[] eth = new byte[14 + ip.length];
        // Dst MAC: broadcast
        for (int i = 0; i < 6; i++) eth[i] = (byte) 0xFF;
        System.arraycopy(GATEWAY_MAC, 0, eth, 6, 6);
        eth[12] = 0x08;
        eth[13] = 0x00;
        System.arraycopy(ip, 0, eth, 14, ip.length);

        if (usbManager != null) {
            usbManager.sendFrame(eth, eth.length);
            log("DHCP " + (responseType == 2 ? "OFFER" : "ACK") + " sent: " + TERMINAL_IP);
        }
    }

    private byte[] buildDhcpResponse(byte msgType, byte[] xid, byte[] clientMac) {
        ByteBuffer buf = ByteBuffer.allocate(300).order(ByteOrder.BIG_ENDIAN);

        buf.put((byte) 2);    // op: BOOTREPLY
        buf.put((byte) 1);    // htype: Ethernet
        buf.put((byte) 6);    // hlen: MAC length
        buf.put((byte) 0);    // hops
        buf.put(xid);         // xid
        buf.putShort((short) 0); // secs
        buf.putShort((short) 0); // flags
        buf.put(new byte[4]); // ciaddr (0.0.0.0)
        buf.put(ipToBytes(TERMINAL_IP)); // yiaddr (assigned IP)
        buf.put(ipToBytes(GATEWAY_IP));  // siaddr (server IP)
        buf.put(new byte[4]); // giaddr

        // chaddr (16 bytes, padded)
        buf.put(clientMac);
        buf.put(new byte[10]); // padding

        // sname (64 bytes) + file (128 bytes) = 192 bytes of zeros
        buf.put(new byte[192]);

        // Magic cookie
        buf.put(new byte[]{99, (byte) 130, 83, 99});

        // Option 53: DHCP Message Type
        buf.put((byte) 53);
        buf.put((byte) 1);
        buf.put(msgType);

        // Option 54: Server Identifier
        buf.put((byte) 54);
        buf.put((byte) 4);
        buf.put(ipToBytes(GATEWAY_IP));

        // Option 51: Lease Time
        buf.put((byte) 51);
        buf.put((byte) 4);
        buf.putInt(DHCP_LEASE_TIME);

        // Option 1: Subnet Mask
        buf.put((byte) 1);
        buf.put((byte) 4);
        buf.put(ipToBytes(SUBNET_MASK));

        // Option 3: Router (gateway)
        buf.put((byte) 3);
        buf.put((byte) 4);
        buf.put(ipToBytes(GATEWAY_IP));

        // Option 6: DNS
        buf.put((byte) 6);
        buf.put((byte) 8);
        buf.put(ipToBytes(DNS_PRIMARY));
        buf.put(ipToBytes(DNS_SECONDARY));

        // Option 255: End
        buf.put((byte) 255);

        buf.flip();
        byte[] result = new byte[buf.remaining()];
        buf.get(result);
        return result;
    }

    private byte[] buildUdpPacket(int srcPort, int dstPort, byte[] payload) {
        ByteBuffer buf = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) srcPort);
        buf.putShort((short) dstPort);
        buf.putShort((short) (8 + payload.length)); // length
        buf.putShort((short) 0); // checksum (0 = not computed, valid for UDP)
        buf.put(payload);
        return buf.array();
    }

    private byte[] buildIpPacket(byte[] srcIp, byte[] dstIp, int protocol, byte[] payload) {
        int totalLen = 20 + payload.length;
        ByteBuffer buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 0x45);         // Version 4, IHL 5
        buf.put((byte) 0x00);         // DSCP/ECN
        buf.putShort((short) totalLen);
        buf.putShort((short) 0);      // Identification
        buf.putShort((short) 0x4000); // Flags: Don't Fragment
        buf.put((byte) 64);           // TTL
        buf.put((byte) protocol);
        buf.putShort((short) 0);      // Header checksum (filled below)
        buf.put(srcIp);
        buf.put(dstIp);
        buf.put(payload);

        byte[] packet = buf.array();
        // Compute IP header checksum
        int sum = 0;
        for (int i = 0; i < 20; i += 2) {
            sum += ((packet[i] & 0xFF) << 8) | (packet[i + 1] & 0xFF);
        }
        while (sum > 0xFFFF) sum = (sum & 0xFFFF) + (sum >> 16);
        int checksum = ~sum & 0xFFFF;
        packet[10] = (byte) (checksum >> 8);
        packet[11] = (byte) (checksum & 0xFF);

        return packet;
    }

    /**
     * Handle ARP requests from the terminal.
     */
    private void handleArp(byte[] frame, int length) {
        if (length < 42) return;

        int opcode = ((frame[20] & 0xFF) << 8) | (frame[21] & 0xFF);

        String senderIp = String.format("%d.%d.%d.%d",
                frame[28] & 0xFF, frame[29] & 0xFF, frame[30] & 0xFF, frame[31] & 0xFF);
        String targetIp = String.format("%d.%d.%d.%d",
                frame[38] & 0xFF, frame[39] & 0xFF, frame[40] & 0xFF, frame[41] & 0xFF);

        log("ARP op:" + opcode + " sender:" + senderIp + " target:" + targetIp);

        if (opcode != 1) return; // Only handle ARP requests

        // Respond to any ARP request for our gateway or any IP on our subnet
        // (proxy ARP — needed so terminal can reach the internet through us)
        log("ARP reply: " + targetIp + " → " + macToString(GATEWAY_MAC));

        byte[] reply = new byte[42];
        // Ethernet header
        System.arraycopy(frame, 6, reply, 0, 6);       // dst = sender's MAC
        System.arraycopy(GATEWAY_MAC, 0, reply, 6, 6);  // src = our MAC
        reply[12] = 0x08; reply[13] = 0x06;              // ARP

        // ARP payload
        reply[14] = 0x00; reply[15] = 0x01; // Hardware: Ethernet
        reply[16] = 0x08; reply[17] = 0x00; // Protocol: IPv4
        reply[18] = 6;    // HW addr length
        reply[19] = 4;    // Proto addr length
        reply[20] = 0x00; reply[21] = 0x02; // Opcode: Reply

        // Sender (us): our MAC + requested IP
        System.arraycopy(GATEWAY_MAC, 0, reply, 22, 6);
        System.arraycopy(frame, 38, reply, 28, 4); // target IP becomes sender IP

        // Target: original sender
        System.arraycopy(frame, 6, reply, 32, 6);   // original sender MAC
        System.arraycopy(frame, 28, reply, 38, 4);  // original sender IP

        if (usbManager != null) {
            usbManager.sendFrame(reply, reply.length);
        }
    }

    /**
     * Read IP packets from TUN (responses from internet → terminal).
     */
    private void tunReadLoop() {
        byte[] buffer = new byte[2048];
        log("TUN read loop started");
        while (running) {
            try {
                int len = tunIn.read(buffer);
                if (len > 0 && usbManager != null) {
                    int count = framesToTerminal.incrementAndGet();
                    if (count <= 5 || count % 100 == 0) {
                        log("TUN→USB frame #" + count + " (" + len + " bytes)");
                    }

                    if (terminalMac != null) {
                        byte[] frame = new byte[14 + len];
                        System.arraycopy(terminalMac, 0, frame, 0, 6);
                        System.arraycopy(GATEWAY_MAC, 0, frame, 6, 6);
                        frame[12] = 0x08; frame[13] = 0x00;
                        System.arraycopy(buffer, 0, frame, 14, len);
                        usbManager.sendFrame(frame, frame.length);
                    } else {
                        // Broadcast if we don't know terminal MAC yet
                        byte[] frame = new byte[14 + len];
                        for (int i = 0; i < 6; i++) frame[i] = (byte) 0xFF;
                        System.arraycopy(GATEWAY_MAC, 0, frame, 6, 6);
                        frame[12] = 0x08; frame[13] = 0x00;
                        System.arraycopy(buffer, 0, frame, 14, len);
                        usbManager.sendFrame(frame, frame.length);
                    }
                }
            } catch (IOException e) {
                if (running) log("TUN read error: " + e.getMessage());
                break;
            }
        }
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
