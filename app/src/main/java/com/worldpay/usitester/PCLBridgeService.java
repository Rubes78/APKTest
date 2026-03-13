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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PCL Bridge service.
 *
 * The terminal does NOT have its own IP on the network. It sends raw TCP traffic
 * over USB Ethernet, expecting the bridge to proxy those connections to the internet.
 * The bridge also allows local WebSocket connections to the terminal's USB IP.
 *
 * This service:
 * 1. Reads Ethernet frames from terminal via USB
 * 2. Detects the terminal's link-local IP from frame headers
 * 3. Proxies the terminal's outbound TCP connections through tablet internet
 * 4. Handles ARP so the terminal can resolve the gateway
 * 5. Routes local traffic to/from the terminal via VPN TUN
 */
public class PCLBridgeService extends VpnService {

    public static final String GATEWAY_IP = "192.168.55.1";
    public static final int TERMINAL_SUBNET = 24;

    public interface Listener {
        void onBridgeLog(String message);
        void onTerminalIpDetected(String ip);
    }

    private final IBinder binder = new LocalBinder();
    private ParcelFileDescriptor vpnFd;
    private volatile boolean running = false;
    private UsbEthernetManager usbManager;
    private Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private FileInputStream tunIn;
    private FileOutputStream tunOut;

    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    // Terminal state
    private byte[] terminalMac = null;
    private String terminalIp = null;
    private static final byte[] GATEWAY_MAC = {0x02, 0x00, 0x55, 0x00, 0x00, 0x01};

    // TCP proxy: "srcIp:srcPort→dstIp:dstPort" → ProxyConnection
    private final ConcurrentHashMap<String, TcpProxy> tcpProxies = new ConcurrentHashMap<>();

    // Frame counters
    private final AtomicInteger framesIn = new AtomicInteger(0);
    private final AtomicInteger framesOut = new AtomicInteger(0);

    public class LocalBinder extends Binder {
        PCLBridgeService getService() {
            return PCLBridgeService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setUsbManager(UsbEthernetManager mgr) {
        this.usbManager = mgr;
    }

    public String getTerminalIp() {
        return terminalIp;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean startBridge() {
        if (running) return true;

        try {
            Builder builder = new Builder();
            builder.addAddress(GATEWAY_IP, TERMINAL_SUBNET);
            builder.addRoute("192.168.55.0", TERMINAL_SUBNET);
            builder.setMtu(1500);
            builder.setSession("PCL Bridge");

            vpnFd = builder.establish();
            if (vpnFd == null) {
                log("VPN establish failed");
                return false;
            }

            tunIn = new FileInputStream(vpnFd.getFileDescriptor());
            tunOut = new FileOutputStream(vpnFd.getFileDescriptor());

            running = true;
            executor.submit(this::tunReadLoop);

            log("PCL Bridge started");
            log("  Gateway: " + GATEWAY_IP);
            log("  Waiting for terminal traffic...");
            log("  Will detect terminal IP from its frames.");

            return true;
        } catch (Exception e) {
            log("Bridge start error: " + e.getMessage());
            return false;
        }
    }

    public void stopBridge() {
        running = false;
        try { if (vpnFd != null) vpnFd.close(); } catch (IOException ignored) {}
        for (TcpProxy p : tcpProxies.values()) p.close();
        tcpProxies.clear();
        log("Bridge stopped. Frames: in=" + framesIn.get() + " out=" + framesOut.get());
    }

    /**
     * Process Ethernet frame from terminal (via USB).
     */
    public void onTerminalFrame(byte[] frame, int length) {
        if (!running) return;

        int count = framesIn.incrementAndGet();
        if (length < 14) return;

        // Learn terminal MAC
        if (terminalMac == null) {
            terminalMac = new byte[6];
            System.arraycopy(frame, 6, terminalMac, 0, 6);
            log("Terminal MAC: " + macToString(terminalMac));
        }

        int etherType = ((frame[12] & 0xFF) << 8) | (frame[13] & 0xFF);

        switch (etherType) {
            case 0x0806: // ARP
                handleArp(frame, length);
                break;
            case 0x0800: // IPv4
                handleIpv4(frame, length, count);
                break;
            default:
                if (count <= 10) {
                    log("Frame #" + count + ": EtherType 0x" + String.format("%04X", etherType) + " (" + length + "b)");
                }
                break;
        }
    }

    private void handleIpv4(byte[] frame, int length, int frameNum) {
        if (length < 34) return; // 14 eth + 20 ip minimum

        byte[] ip = new byte[length - 14];
        System.arraycopy(frame, 14, ip, 0, length - 14);

        // Extract source IP
        String srcIp = String.format("%d.%d.%d.%d",
                ip[12] & 0xFF, ip[13] & 0xFF, ip[14] & 0xFF, ip[15] & 0xFF);
        String dstIp = String.format("%d.%d.%d.%d",
                ip[16] & 0xFF, ip[17] & 0xFF, ip[18] & 0xFF, ip[19] & 0xFF);
        int protocol = ip[9] & 0xFF;

        // Detect terminal IP on first IPv4 frame
        if (terminalIp == null && !srcIp.equals("0.0.0.0")) {
            terminalIp = srcIp;
            log("*** Terminal IP detected: " + terminalIp + " ***");
            mainHandler.post(() -> {
                if (listener != null) listener.onTerminalIpDetected(terminalIp);
            });
        }

        int ihl = (ip[0] & 0x0F) * 4;

        if (protocol == 17 && ip.length >= ihl + 8) {
            // UDP
            int srcPort = ((ip[ihl] & 0xFF) << 8) | (ip[ihl + 1] & 0xFF);
            int dstPort = ((ip[ihl + 2] & 0xFF) << 8) | (ip[ihl + 3] & 0xFF);

            if (frameNum <= 20 || frameNum % 50 == 0) {
                log("UDP " + srcIp + ":" + srcPort + " → " + dstIp + ":" + dstPort);
            }

            // DHCP discover/request (client→server)
            if (srcPort == 68 && dstPort == 67) {
                handleDhcp(frame, ip, ihl);
                return;
            }

            // DNS — proxy via TUN
        }

        if (protocol == 6 && ip.length >= ihl + 20) {
            // TCP
            int srcPort = ((ip[ihl] & 0xFF) << 8) | (ip[ihl + 1] & 0xFF);
            int dstPort = ((ip[ihl + 2] & 0xFF) << 8) | (ip[ihl + 3] & 0xFF);
            int flags = ip[ihl + 13] & 0xFF;

            if (frameNum <= 20 || frameNum % 50 == 0) {
                String flagStr = "";
                if ((flags & 0x02) != 0) flagStr += "SYN ";
                if ((flags & 0x10) != 0) flagStr += "ACK ";
                if ((flags & 0x01) != 0) flagStr += "FIN ";
                if ((flags & 0x04) != 0) flagStr += "RST ";
                log("TCP " + srcIp + ":" + srcPort + " → " + dstIp + ":" + dstPort + " [" + flagStr.trim() + "]");
            }
        }

        // Forward ALL IP packets through TUN for routing
        if (tunOut != null) {
            try {
                tunOut.write(ip);
                tunOut.flush();
            } catch (IOException e) {
                if (frameNum <= 5) log("TUN write error: " + e.getMessage());
            }
        }
    }

    /**
     * Handle DHCP from terminal.
     */
    private void handleDhcp(byte[] ethFrame, byte[] ip, int ihl) {
        int dhcpStart = ihl + 8; // past UDP header
        if (ip.length < dhcpStart + 240) return;

        // Find message type in options
        byte msgType = 0;
        byte[] xid = new byte[4];
        byte[] clientMac = new byte[6];
        System.arraycopy(ip, dhcpStart + 4, xid, 0, 4);
        System.arraycopy(ip, dhcpStart + 28, clientMac, 0, 6);

        int optStart = dhcpStart + 236;
        if (ip.length > optStart + 4 &&
                (ip[optStart] & 0xFF) == 99 && (ip[optStart + 1] & 0xFF) == 130 &&
                (ip[optStart + 2] & 0xFF) == 83 && (ip[optStart + 3] & 0xFF) == 99) {
            int pos = optStart + 4;
            while (pos < ip.length - 1) {
                int opt = ip[pos] & 0xFF;
                if (opt == 255) break;
                if (opt == 0) { pos++; continue; }
                int len = ip[pos + 1] & 0xFF;
                if (opt == 53 && len >= 1) msgType = ip[pos + 2];
                pos += 2 + len;
            }
        }

        // Use terminal's source IP from earlier, or assign one
        String assignIp = (terminalIp != null) ? terminalIp : "192.168.55.2";

        byte responseType;
        if (msgType == 1) { log("DHCP DISCOVER"); responseType = 2; }
        else if (msgType == 3) { log("DHCP REQUEST"); responseType = 5; }
        else return;

        sendDhcpResponse(responseType, xid, clientMac, assignIp);
    }

    private void sendDhcpResponse(byte msgType, byte[] xid, byte[] clientMac, String assignIp) {
        ByteBuffer buf = ByteBuffer.allocate(300).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 2); buf.put((byte) 1); buf.put((byte) 6); buf.put((byte) 0);
        buf.put(xid);
        buf.putShort((short) 0); buf.putShort((short) 0);
        buf.put(new byte[4]); // ciaddr
        buf.put(ipToBytes(assignIp)); // yiaddr
        buf.put(ipToBytes(GATEWAY_IP)); // siaddr
        buf.put(new byte[4]); // giaddr
        buf.put(clientMac); buf.put(new byte[10]);
        buf.put(new byte[192]); // sname + file
        buf.put(new byte[]{99, (byte) 130, 83, 99}); // magic cookie

        // Options
        buf.put((byte) 53); buf.put((byte) 1); buf.put(msgType);
        buf.put((byte) 54); buf.put((byte) 4); buf.put(ipToBytes(GATEWAY_IP));
        buf.put((byte) 51); buf.put((byte) 4); buf.putInt(86400);
        buf.put((byte) 1); buf.put((byte) 4); buf.put(ipToBytes("255.255.255.0"));
        buf.put((byte) 3); buf.put((byte) 4); buf.put(ipToBytes(GATEWAY_IP));
        buf.put((byte) 6); buf.put((byte) 8); buf.put(ipToBytes("8.8.8.8")); buf.put(ipToBytes("8.8.4.4"));
        buf.put((byte) 255);

        buf.flip();
        byte[] dhcp = new byte[buf.remaining()];
        buf.get(dhcp);

        // Wrap UDP
        byte[] udp = new byte[8 + dhcp.length];
        udp[0] = 0; udp[1] = 67; // src port 67
        udp[2] = 0; udp[3] = 68; // dst port 68
        int udpLen = 8 + dhcp.length;
        udp[4] = (byte)(udpLen >> 8); udp[5] = (byte)(udpLen & 0xFF);
        udp[6] = 0; udp[7] = 0; // checksum
        System.arraycopy(dhcp, 0, udp, 8, dhcp.length);

        // Wrap IP
        byte[] ipPkt = buildIpPacket(ipToBytes(GATEWAY_IP),
                new byte[]{(byte)255,(byte)255,(byte)255,(byte)255}, 17, udp);

        // Wrap Ethernet (broadcast)
        byte[] eth = new byte[14 + ipPkt.length];
        for (int i = 0; i < 6; i++) eth[i] = (byte) 0xFF;
        System.arraycopy(GATEWAY_MAC, 0, eth, 6, 6);
        eth[12] = 0x08; eth[13] = 0x00;
        System.arraycopy(ipPkt, 0, eth, 14, ipPkt.length);

        if (usbManager != null) {
            usbManager.sendFrame(eth, eth.length);
            log("DHCP " + (msgType == 2 ? "OFFER" : "ACK") + " → " + assignIp);
        }
    }

    /**
     * Handle ARP — respond to all requests with our MAC (proxy ARP).
     */
    private void handleArp(byte[] frame, int length) {
        if (length < 42) return;
        int opcode = ((frame[20] & 0xFF) << 8) | (frame[21] & 0xFF);

        String senderIp = String.format("%d.%d.%d.%d",
                frame[28] & 0xFF, frame[29] & 0xFF, frame[30] & 0xFF, frame[31] & 0xFF);
        String targetIp = String.format("%d.%d.%d.%d",
                frame[38] & 0xFF, frame[39] & 0xFF, frame[40] & 0xFF, frame[41] & 0xFF);

        log("ARP op:" + opcode + " " + senderIp + " → " + targetIp);

        // Learn terminal IP from ARP sender
        if (terminalIp == null && !senderIp.equals("0.0.0.0")) {
            terminalIp = senderIp;
            log("*** Terminal IP from ARP: " + terminalIp + " ***");
            mainHandler.post(() -> {
                if (listener != null) listener.onTerminalIpDetected(terminalIp);
            });
        }

        if (opcode != 1) return; // Only reply to requests

        // Send ARP reply
        byte[] reply = new byte[42];
        System.arraycopy(frame, 6, reply, 0, 6);
        System.arraycopy(GATEWAY_MAC, 0, reply, 6, 6);
        reply[12] = 0x08; reply[13] = 0x06;
        reply[14] = 0x00; reply[15] = 0x01;
        reply[16] = 0x08; reply[17] = 0x00;
        reply[18] = 6; reply[19] = 4;
        reply[20] = 0x00; reply[21] = 0x02; // Reply

        System.arraycopy(GATEWAY_MAC, 0, reply, 22, 6);
        System.arraycopy(frame, 38, reply, 28, 4); // requested IP → sender
        System.arraycopy(frame, 6, reply, 32, 6);
        System.arraycopy(frame, 28, reply, 38, 4);

        if (usbManager != null) usbManager.sendFrame(reply, reply.length);
        log("ARP reply sent for " + targetIp);
    }

    /**
     * TUN read loop: packets from Android network stack → terminal via USB.
     */
    private void tunReadLoop() {
        byte[] buffer = new byte[2048];
        while (running) {
            try {
                int len = tunIn.read(buffer);
                if (len > 0 && usbManager != null) {
                    int count = framesOut.incrementAndGet();

                    byte[] dstMac = (terminalMac != null) ? terminalMac : new byte[]{(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF};
                    byte[] frame = new byte[14 + len];
                    System.arraycopy(dstMac, 0, frame, 0, 6);
                    System.arraycopy(GATEWAY_MAC, 0, frame, 6, 6);
                    frame[12] = 0x08; frame[13] = 0x00;
                    System.arraycopy(buffer, 0, frame, 14, len);
                    usbManager.sendFrame(frame, frame.length);

                    if (count <= 5 || count % 100 == 0) {
                        log("TUN→USB #" + count + " (" + len + "b)");
                    }
                }
            } catch (IOException e) {
                if (running) log("TUN read error: " + e.getMessage());
                break;
            }
        }
    }

    // ── IP packet builder ──

    private byte[] buildIpPacket(byte[] srcIp, byte[] dstIp, int protocol, byte[] payload) {
        int totalLen = 20 + payload.length;
        ByteBuffer buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 0x45); buf.put((byte) 0x00);
        buf.putShort((short) totalLen);
        buf.putShort((short) 0); buf.putShort((short) 0x4000);
        buf.put((byte) 64); buf.put((byte) protocol);
        buf.putShort((short) 0); // checksum placeholder
        buf.put(srcIp); buf.put(dstIp);
        buf.put(payload);

        byte[] pkt = buf.array();
        int sum = 0;
        for (int i = 0; i < 20; i += 2) sum += ((pkt[i] & 0xFF) << 8) | (pkt[i + 1] & 0xFF);
        while (sum > 0xFFFF) sum = (sum & 0xFFFF) + (sum >> 16);
        int cksum = ~sum & 0xFFFF;
        pkt[10] = (byte)(cksum >> 8); pkt[11] = (byte)(cksum & 0xFF);
        return pkt;
    }

    // ── Utility ──

    private byte[] ipToBytes(String ip) {
        String[] parts = ip.split("\\.");
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) b[i] = (byte) Integer.parseInt(parts[i]);
        return b;
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
        mainHandler.post(() -> { if (listener != null) listener.onBridgeLog(msg); });
    }

    @Override
    public void onDestroy() {
        stopBridge();
        executor.shutdownNow();
        super.onDestroy();
    }

    // ── TCP Proxy helper ──
    private static class TcpProxy {
        Socket socket;
        void close() {
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        }
    }
}
