# USI Terminal Tester

Android app for testing communication with Ingenico WorldPay USI terminals via the iConnect-Ws WebSocket interface (PCL bridge over USB or WiFi/Ethernet).

## What it does

- Connects to the terminal's WebSocket server (port 50000 by default)
- Sends USI v1 JSON commands matching your production message format
- Available commands:
  - **Soft Reset** — clear terminal form, return to idle
  - **Hard Reset** — full terminal restart
  - **Device Info** — query terminal status
  - **Test Sale ($0.01)** — minimal sale to confirm end-to-end flow
  - **Custom Sale Amount** — enter any dollar amount
  - **Send Raw JSON** — paste any USI JSON payload
- Displays all sent/received messages in a live log with timestamps
- Auto-acknowledges `transaction_completed` events
- Shows connection status and response latency

## PCL Bridge (USB)

When `usb_pcl: 1` in `iConnect-Ws.PBT`, the terminal acts as a USB Ethernet gadget. Connect Android to the terminal via USB and the terminal is typically reachable at `172.16.0.1:50000`.

## Build

Requires Android Studio or command-line Android SDK (API 34).

```bash
# Android Studio
# Open this folder as a project, Build > Build Bundle(s) / APK(s) > Build APK(s)

# Command line (with ANDROID_HOME set)
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

## Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or transfer the APK to the device and sideload it.

## Configuration from PBT files

This app is built to match:
- `local.iConnect-Ws.PBT` — WebSocket server mode, port 50000, PCL over USB
- `local.hostmodule.config.PBT` — WorldPay host config (BID 5429, MID 030000000839)
- Terminal logs show USI v1 JSON over WebSocket with endpoints `/usi/v1/device` and `/usi/v1/transaction`
