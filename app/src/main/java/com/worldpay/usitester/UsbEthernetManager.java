package com.worldpay.usitester;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Locale;

/**
 * Manages USB CDC-ECM/RNDIS communication with the Ingenico Lane 7000.
 *
 * When usb_pcl=1, the terminal exposes a USB Ethernet gadget.
 * This class claims the USB device, configures the CDC-ECM or RNDIS interface,
 * and provides read/write access to raw Ethernet frames.
 */
public class UsbEthernetManager {

    private static final String ACTION_USB_PERMISSION = "com.worldpay.usitester.USB_PERMISSION";

    // Ingenico vendor IDs
    private static final int VID_INGENICO_1 = 0x1998;
    private static final int VID_INGENICO_2 = 0x1DD4;
    private static final int VID_INGENICO_3 = 0x0B00;

    // USB class codes
    private static final int USB_CLASS_CDC = 0x02;         // Communications
    private static final int USB_CLASS_CDC_DATA = 0x0A;    // CDC Data
    private static final int USB_CLASS_WIRELESS = 0xE0;    // Wireless/RNDIS
    private static final int USB_CLASS_VENDOR = 0xFF;      // Vendor specific

    // CDC ECM subclass
    private static final int CDC_SUBCLASS_ECM = 0x06;
    // RNDIS uses wireless class with specific subclass
    private static final int RNDIS_SUBCLASS = 0x01;
    private static final int RNDIS_PROTOCOL = 0x03;

    public interface Listener {
        void onLog(String message);
        void onUsbReady(String terminalInfo);
        void onUsbError(String error);
        void onUsbDisconnected();
        void onEthernetFrame(byte[] frame, int length);
    }

    private final Context context;
    private final UsbManager usbManager;
    private final Handler mainHandler;
    private Listener listener;

    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface dataInterface;
    private UsbInterface controlInterface;
    private UsbEndpoint endpointIn;
    private UsbEndpoint endpointOut;
    private UsbEndpoint endpointNotify;

    private volatile boolean running = false;
    private Thread readThread;

    private boolean isRndis = false;
    private boolean rndisInitialized = false;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    if (dev != null) {
                        postLog("USB permission granted");
                        openDevice(dev);
                    }
                } else {
                    postError("USB permission denied by user");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (dev != null && device != null && dev.getDeviceId() == device.getDeviceId()) {
                    postLog("USB device detached");
                    stop();
                    mainHandler.post(() -> { if (listener != null) listener.onUsbDisconnected(); });
                }
            }
        }
    };

    public UsbEthernetManager(Context context) {
        this.context = context;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Scan for Ingenico terminal and request permission.
     */
    public void start() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(usbReceiver, filter);
        }

        UsbDevice terminal = findIngenico();
        if (terminal == null) {
            postError("No Ingenico terminal found on USB");
            postLog("Ensure the Lane 7000 is connected via USB cable");
            return;
        }

        device = terminal;
        postLog("Found: " + describeDevice(terminal));
        listInterfaces(terminal);

        if (usbManager.hasPermission(terminal)) {
            openDevice(terminal);
        } else {
            postLog("Requesting USB permission...");
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(context, 0,
                    new Intent(ACTION_USB_PERMISSION), flags);
            usbManager.requestPermission(terminal, pi);
        }
    }

    public void stop() {
        running = false;
        if (readThread != null) {
            readThread.interrupt();
            readThread = null;
        }
        if (connection != null) {
            if (dataInterface != null) connection.releaseInterface(dataInterface);
            if (controlInterface != null) connection.releaseInterface(controlInterface);
            connection.close();
            connection = null;
        }
        try {
            context.unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {}
    }

    /**
     * Send a raw Ethernet frame to the terminal.
     */
    public boolean sendFrame(byte[] frame, int length) {
        if (connection == null || endpointOut == null || !running) return false;

        int sent;
        if (isRndis) {
            // Wrap in RNDIS packet message
            byte[] rndisPacket = wrapRndisPacket(frame, length);
            sent = connection.bulkTransfer(endpointOut, rndisPacket, rndisPacket.length, 1000);
        } else {
            sent = connection.bulkTransfer(endpointOut, frame, length, 1000);
        }
        return sent >= 0;
    }

    public boolean isRunning() {
        return running;
    }

    // ── Private methods ──

    private UsbDevice findIngenico() {
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        for (UsbDevice dev : devices.values()) {
            int vid = dev.getVendorId();
            if (vid == VID_INGENICO_1 || vid == VID_INGENICO_2 || vid == VID_INGENICO_3) {
                return dev;
            }
        }
        // Fallback: look for CDC ECM or RNDIS class devices
        for (UsbDevice dev : devices.values()) {
            for (int i = 0; i < dev.getInterfaceCount(); i++) {
                UsbInterface iface = dev.getInterface(i);
                int cls = iface.getInterfaceClass();
                if (cls == USB_CLASS_CDC || cls == USB_CLASS_CDC_DATA || cls == USB_CLASS_WIRELESS) {
                    postLog("Found potential CDC/RNDIS device: VID:" +
                            String.format("%04X", dev.getVendorId()));
                    return dev;
                }
            }
        }
        return null;
    }

    private void listInterfaces(UsbDevice dev) {
        postLog("USB interfaces (" + dev.getInterfaceCount() + "):");
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface iface = dev.getInterface(i);
            postLog(String.format("  [%d] class:%d sub:%d proto:%d eps:%d",
                    i, iface.getInterfaceClass(), iface.getInterfaceSubclass(),
                    iface.getInterfaceProtocol(), iface.getEndpointCount()));
            for (int e = 0; e < iface.getEndpointCount(); e++) {
                UsbEndpoint ep = iface.getEndpoint(e);
                String dir = ep.getDirection() == UsbConstants.USB_DIR_IN ? "IN" : "OUT";
                String type;
                switch (ep.getType()) {
                    case UsbConstants.USB_ENDPOINT_XFER_BULK: type = "BULK"; break;
                    case UsbConstants.USB_ENDPOINT_XFER_INT: type = "INT"; break;
                    case UsbConstants.USB_ENDPOINT_XFER_ISOC: type = "ISOC"; break;
                    default: type = "CTRL"; break;
                }
                postLog(String.format("    EP%d: %s %s maxPkt:%d",
                        e, dir, type, ep.getMaxPacketSize()));
            }
        }
    }

    private void openDevice(UsbDevice dev) {
        connection = usbManager.openDevice(dev);
        if (connection == null) {
            postError("Failed to open USB device");
            return;
        }

        postLog("USB device opened, serial: " + connection.getSerial());

        // Find the right interfaces
        if (!findEndpoints(dev)) {
            postError("Could not find suitable USB Ethernet endpoints");
            postLog("The terminal may not be in PCL bridge mode.");
            postLog("Check iConnect-Ws.PBT: usb_pcl must be 1");
            return;
        }

        // Claim interfaces
        if (controlInterface != null) {
            if (!connection.claimInterface(controlInterface, true)) {
                postError("Failed to claim control interface");
                return;
            }
            postLog("Claimed control interface");
        }
        if (!connection.claimInterface(dataInterface, true)) {
            postError("Failed to claim data interface");
            return;
        }
        postLog("Claimed data interface");

        // Initialize RNDIS if needed
        if (isRndis) {
            if (!initializeRndis()) {
                postError("RNDIS initialization failed");
                return;
            }
        }

        running = true;
        startReadThread();

        String info = String.format("VID:%04X PID:%04X %s",
                dev.getVendorId(), dev.getProductId(),
                isRndis ? "(RNDIS)" : "(CDC-ECM)");
        mainHandler.post(() -> { if (listener != null) listener.onUsbReady(info); });
    }

    private boolean findEndpoints(UsbDevice dev) {
        // Strategy 1: Look for CDC ECM (class 2, subclass 6)
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface iface = dev.getInterface(i);
            if (iface.getInterfaceClass() == USB_CLASS_CDC &&
                    iface.getInterfaceSubclass() == CDC_SUBCLASS_ECM) {
                postLog("Found CDC-ECM control interface [" + i + "]");
                controlInterface = iface;
                isRndis = false;

                // Find the paired data interface (class 0x0A)
                for (int j = 0; j < dev.getInterfaceCount(); j++) {
                    UsbInterface dataIface = dev.getInterface(j);
                    if (dataIface.getInterfaceClass() == USB_CLASS_CDC_DATA &&
                            dataIface.getEndpointCount() >= 2) {
                        postLog("Found CDC-ECM data interface [" + j + "]");
                        dataInterface = dataIface;
                        extractBulkEndpoints(dataIface);
                        // Get interrupt endpoint from control interface
                        extractNotifyEndpoint(iface);
                        return endpointIn != null && endpointOut != null;
                    }
                }
            }
        }

        // Strategy 2: Look for RNDIS (class 0xE0 or class 0x02 with subclass 0x02)
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface iface = dev.getInterface(i);
            boolean isRndisControl =
                    (iface.getInterfaceClass() == USB_CLASS_WIRELESS &&
                            iface.getInterfaceSubclass() == RNDIS_SUBCLASS &&
                            iface.getInterfaceProtocol() == RNDIS_PROTOCOL) ||
                    (iface.getInterfaceClass() == USB_CLASS_CDC &&
                            iface.getInterfaceSubclass() == 0x02 &&
                            iface.getInterfaceProtocol() == 0xFF);

            if (isRndisControl) {
                postLog("Found RNDIS control interface [" + i + "]");
                controlInterface = iface;
                isRndis = true;
                extractNotifyEndpoint(iface);

                // Data interface is usually the next one
                for (int j = 0; j < dev.getInterfaceCount(); j++) {
                    UsbInterface dataIface = dev.getInterface(j);
                    if (j != i && dataIface.getInterfaceClass() == USB_CLASS_CDC_DATA &&
                            dataIface.getEndpointCount() >= 2) {
                        postLog("Found RNDIS data interface [" + j + "]");
                        dataInterface = dataIface;
                        extractBulkEndpoints(dataIface);
                        return endpointIn != null && endpointOut != null;
                    }
                }
            }
        }

        // Strategy 3: Fallback - find any interface with bulk IN + OUT endpoints
        postLog("No standard CDC/RNDIS found, trying bulk endpoint scan...");
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface iface = dev.getInterface(i);
            if (iface.getEndpointCount() >= 2) {
                UsbEndpoint bulkIn = null, bulkOut = null;
                for (int e = 0; e < iface.getEndpointCount(); e++) {
                    UsbEndpoint ep = iface.getEndpoint(e);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.getDirection() == UsbConstants.USB_DIR_IN) bulkIn = ep;
                        else bulkOut = ep;
                    }
                }
                if (bulkIn != null && bulkOut != null) {
                    postLog("Using fallback bulk interface [" + i + "]");
                    dataInterface = iface;
                    endpointIn = bulkIn;
                    endpointOut = bulkOut;
                    isRndis = false;
                    return true;
                }
            }
        }

        return false;
    }

    private void extractBulkEndpoints(UsbInterface iface) {
        for (int e = 0; e < iface.getEndpointCount(); e++) {
            UsbEndpoint ep = iface.getEndpoint(e);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                    endpointIn = ep;
                    postLog("  Bulk IN endpoint: maxPkt=" + ep.getMaxPacketSize());
                } else {
                    endpointOut = ep;
                    postLog("  Bulk OUT endpoint: maxPkt=" + ep.getMaxPacketSize());
                }
            }
        }
    }

    private void extractNotifyEndpoint(UsbInterface iface) {
        for (int e = 0; e < iface.getEndpointCount(); e++) {
            UsbEndpoint ep = iface.getEndpoint(e);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT &&
                    ep.getDirection() == UsbConstants.USB_DIR_IN) {
                endpointNotify = ep;
                postLog("  Notify endpoint found");
            }
        }
    }

    // ── RNDIS Protocol ──

    private static final int RNDIS_MSG_INIT = 0x00000002;
    private static final int RNDIS_MSG_INIT_C = 0x80000002;
    private static final int RNDIS_MSG_SET = 0x00000005;
    private static final int RNDIS_MSG_SET_C = 0x80000005;
    private static final int RNDIS_MSG_PACKET = 0x00000001;

    private static final int RNDIS_OID_802_3_CURRENT_ADDRESS = 0x01010102;
    private static final int RNDIS_OID_GEN_CURRENT_PACKET_FILTER = 0x0001010E;

    private int rndisRequestId = 1;

    private boolean initializeRndis() {
        postLog("Initializing RNDIS...");

        // RNDIS_INITIALIZE_MSG
        ByteBuffer init = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        init.putInt(RNDIS_MSG_INIT);   // MessageType
        init.putInt(24);                // MessageLength
        init.putInt(rndisRequestId++);  // RequestId
        init.putInt(1);                 // MajorVersion
        init.putInt(0);                 // MinorVersion
        init.putInt(1518);              // MaxTransferSize

        byte[] response = sendRndisControl(init.array());
        if (response == null || response.length < 16) {
            postLog("RNDIS init: no response");
            return false;
        }

        ByteBuffer resp = ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN);
        int msgType = resp.getInt();
        if (msgType != RNDIS_MSG_INIT_C) {
            postLog("RNDIS init: unexpected response type 0x" + Integer.toHexString(msgType));
            return false;
        }
        postLog("RNDIS initialized successfully");

        // Set packet filter to receive all packets
        ByteBuffer filter = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        filter.putInt(RNDIS_MSG_SET);              // MessageType
        filter.putInt(32);                          // MessageLength
        filter.putInt(rndisRequestId++);            // RequestId
        filter.putInt(RNDIS_OID_GEN_CURRENT_PACKET_FILTER); // Oid
        filter.putInt(4);                           // InformationBufferLength
        filter.putInt(20);                          // InformationBufferOffset (from RequestId)
        filter.putInt(0);                           // DeviceVcHandle
        filter.putInt(0x0000002F);                  // Packet filter: directed|multicast|broadcast|all_multicast|promiscuous

        response = sendRndisControl(filter.array());
        if (response != null) {
            ByteBuffer r = ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN);
            int type = r.getInt();
            if (type == RNDIS_MSG_SET_C) {
                postLog("RNDIS packet filter set");
                rndisInitialized = true;
                return true;
            }
        }

        // Some devices work without the filter set
        postLog("RNDIS filter set may have failed, continuing anyway");
        rndisInitialized = true;
        return true;
    }

    private byte[] sendRndisControl(byte[] data) {
        if (connection == null || controlInterface == null) return null;

        // Send via control transfer (class interface request)
        int sent = connection.controlTransfer(
                0x21, // bmRequestType: host-to-device, class, interface
                0x00, // bRequest: SEND_ENCAPSULATED_COMMAND
                0,    // wValue
                controlInterface.getId(), // wIndex: interface number
                data, data.length, 1000);

        if (sent < 0) {
            postLog("RNDIS control send failed");
            return null;
        }

        // Read response
        byte[] response = new byte[1024];
        int received = connection.controlTransfer(
                0xA1, // bmRequestType: device-to-host, class, interface
                0x01, // bRequest: GET_ENCAPSULATED_RESPONSE
                0, controlInterface.getId(),
                response, response.length, 1000);

        if (received > 0) {
            byte[] trimmed = new byte[received];
            System.arraycopy(response, 0, trimmed, 0, received);
            return trimmed;
        }
        return null;
    }

    private byte[] wrapRndisPacket(byte[] ethFrame, int length) {
        // RNDIS_PACKET_MSG header is 44 bytes
        int totalLen = 44 + length;
        ByteBuffer pkt = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN);
        pkt.putInt(RNDIS_MSG_PACKET);  // MessageType
        pkt.putInt(totalLen);           // MessageLength
        pkt.putInt(44);                 // DataOffset (from start of DataOffset field = byte 8)
        pkt.putInt(length);             // DataLength
        pkt.putInt(0);                  // OOBDataOffset
        pkt.putInt(0);                  // OOBDataLength
        pkt.putInt(0);                  // NumOOBDataElements
        pkt.putInt(0);                  // PerPacketInfoOffset
        pkt.putInt(0);                  // PerPacketInfoLength
        pkt.putInt(0);                  // VcHandle
        pkt.putInt(0);                  // Reserved
        pkt.put(ethFrame, 0, length);
        return pkt.array();
    }

    private byte[] unwrapRndisPacket(byte[] data, int length) {
        if (length < 44) return null;
        ByteBuffer pkt = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN);
        int msgType = pkt.getInt();
        if (msgType != RNDIS_MSG_PACKET) return null;
        int msgLen = pkt.getInt();
        int dataOffset = pkt.getInt(); // offset from byte 8
        int dataLen = pkt.getInt();

        int frameStart = 8 + dataOffset;
        if (frameStart + dataLen > length) return null;

        byte[] frame = new byte[dataLen];
        System.arraycopy(data, frameStart, frame, 0, dataLen);
        return frame;
    }

    // ── Read thread ──

    private void startReadThread() {
        readThread = new Thread(() -> {
            byte[] buffer = new byte[2048];
            postLog("USB read thread started");

            while (running && !Thread.interrupted()) {
                int received = connection.bulkTransfer(endpointIn, buffer, buffer.length, 500);
                if (received > 0) {
                    byte[] frame;
                    if (isRndis) {
                        frame = unwrapRndisPacket(buffer, received);
                    } else {
                        frame = new byte[received];
                        System.arraycopy(buffer, 0, frame, 0, received);
                    }

                    if (frame != null && frame.length > 0 && listener != null) {
                        listener.onEthernetFrame(frame, frame.length);
                    }
                }
            }
            postLog("USB read thread stopped");
        }, "USB-Read");
        readThread.setDaemon(true);
        readThread.start();
    }

    // ── Helpers ──

    private String describeDevice(UsbDevice dev) {
        return String.format(Locale.US, "VID:%04X PID:%04X %s %s",
                dev.getVendorId(), dev.getProductId(),
                dev.getManufacturerName() != null ? dev.getManufacturerName() : "",
                dev.getProductName() != null ? dev.getProductName() : "");
    }

    private void postLog(String msg) {
        mainHandler.post(() -> { if (listener != null) listener.onLog(msg); });
    }

    private void postError(String msg) {
        mainHandler.post(() -> { if (listener != null) listener.onUsbError(msg); });
    }
}
