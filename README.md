# 🛸 SkyFPV - Open Source Skydroid T12 & UVC FPV Ground Station for Android

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026--35)-brightgreen.svg)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Hardware](https://img.shields.io/badge/Hardware-Skydroid%20T12%20%7C%205.8G%20UVC-orange.svg)](https://github.com)

**SkyFPV** is a high-performance, open-source Android ground station app engineered specifically for the **Skydroid T12 digital FPV transmitter**, as well as standard **5.8GHz OTG UVC receivers** (Eachine ROTG01/ROTG02, FUAV).

It completely replaces the outdated, abandoned proprietary Skydroid app (`FUAV / com.shenyaocn.android.fuavg`) with a modern, ultra-low latency video pipeline, hardware-accelerated H.264 decoding, pristine direct-bitstream DVR recording, and full compatibility with **Android 10 through Android 15 (Scoped Storage)**.

---

## ⚡ Highlights & Features

- 🎯 **Native Skydroid T12 Digital Link:**
  - High-speed USB communication over CP2102N UART bridge running at **4,000,000 Baud**.
  - Automated AT command handshake (`AT+SWITCH`, `AT+VIDEO`, `AT+LED`) and keep-alive polling.
  - Automatic parsing of Skydroid proprietary packet framing (`0xFF ... 0xA5`).
- 📺 **Sub-Frame Low Latency Display:**
  - Direct zero-copy hardware decoding (`MediaCodec`) rendered straight to `SurfaceView`.
  - Automatic SPS parameter parsing for native camera resolution (`640x360` / `720p` / `1080p`).
- 🔴 **Lossless Direct-Bitstream DVR Recording:**
  - Multiplexes the drone's incoming digital H.264 NAL units directly into an **MP4** container (`MediaMuxer`).
  - **Zero re-encoding overhead**: 0% CPU spike, zero battery drain, and 100% bit-for-bit pristine camera quality.
  - Recorded flights are immediately available in the Android **Gallery** and **Google Photos** (`Movies/SkyFPV`).
- 📸 **One-Tap Flight Snapshots:**
  - High-resolution screen captures saved directly to `Pictures/SkyFPV` with `PixelCopy`.
- 🥽 **VR Goggles Mode:**
  - Instant split-screen stereoscopic view with center divider for smartphone FPV goggles and headsets.
- 📐 **Aspect Ratio Switching:**
  - Seamless toggle between **4:3 Classic FPV**, **16:9 Widescreen**, and **Fullscreen Stretch**.
- 📂 **In-App Flight Gallery:**
  - Integrated bottom-sheet gallery to review recorded flight videos and photos immediately after landing.
- 🛡️ **Modern Android 14/15 Support:**
  - Full Scoped Storage compliance (no dangerous `MANAGE_EXTERNAL_STORAGE` permissions required).
  - Modern USB intent dispatching and foreground service protection.

---

## 🔬 How Skydroid T12 Video Transmission Works

The Skydroid T12 handles video via a digital video link rather than an analog broadcast:

```
[Drone FPV Camera]
       │ (Digital H.264)
       ▼
[R12 Receiver on Drone]
       │ (2.4GHz Digital Telemetry & Video Link)
       ▼
[T12 Ground Transmitter]
       │ (Internal CP2102N USB-to-UART Bridge @ 4,000,000 Baud)
       ▼
[USB-OTG Cable to Android Phone]
       │
       ▼
[SkyFPV App]
  ├── SkydroidT12Engine: Sends AT commands, demuxes 0xFF frames
  ├── H264DecoderEngine: Extracts SPS/PPS/IDR, hardware decodes to SurfaceView
  └── DvrVideoRecorder: Direct MP4 muxing into Movies/SkyFPV
```

### The Protocol Breakdown
1. **Baud Rate:** The CP2102N bridge inside the T12 controller operates at `4,000,000 baud` (8 data bits, 1 stop bit, no parity).
2. **Initial Handshake:** The app sends the following AT commands upon connection:
   - `AT+SWITCH -e1\r\n`
   - `AT+LED -e1\r\n`
   - `AT+VIDEO -m0 -p1 -f15 -b300 -e1 -g8\r\n`
3. **Heartbeat Polling:** The app sends `AT+VIDEO -x0 -y0\r\n` every 1.5 seconds to keep the video stream active.
4. **Data Packets:** The receiver returns packets starting with `0xFF`:
   - `Byte 0`: `0xFF` (Start delimiter)
   - `Byte 1-2`: Payload length (Big-endian)
   - `Byte 3`: Packet type (`0xA5` indicates H.264 video payload)
   - `Byte 4+`: H.264 elementary NAL units (`00 00 00 01 ...`)

---

## 📲 Download & Install

A pre-built, ready-to-install APK is available directly in the repository:
- **[Download SkyFPV_v1.0_Skydroid_FPV.apk](SkyFPV_v1.0_Skydroid_FPV.apk)**

### Installation on Android:
1. Download the APK onto your phone.
2. Tap the file in your downloads folder.
3. If prompted, allow "Install from unknown sources" in settings.
4. Connect your Skydroid T12 via USB-OTG cable, turn on the controller, and tap **OK** on the USB permission prompt.

---

## 🛠️ Building from Source

### Requirements
- **Android Studio** (Koala / Ladybug or newer recommended)
- **JDK 17**
- **Android SDK API 34+**

### Steps
```bash
# Clone the repository
git clone https://github.com/your-username/skydroid-fpv.git
cd skydroid-fpv

# Build the debug APK
./gradlew assembleDebug

# Install directly to a connected phone via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📁 Project Structure

```
skydroid_fpv/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/
│       │   ├── xml/usb_device_filter.xml     # USB vendor/product ID auto-detection
│       │   ├── layout/activity_fpv.xml        # HUD & SurfaceView layout
│       │   ├── layout/bottom_sheet_gallery.xml
│       │   └── values/
│       └── java/com/skydroid/fpv/
│           ├── MainActivity.kt                # Main activity & HUD lifecycle
│           ├── usb/
│           │   ├── SkydroidT12Engine.kt       # CP2102 4MBaud streaming & AT controller
│           │   ├── UsbReceiverManager.kt      # OTG device attachment & permissions
│           │   └── UvcProtocolParser.kt       # Fallback analog UVC MJPEG parser
│           ├── video/
│           │   └── H264DecoderEngine.kt       # SPS parser & zero-latency MediaCodec decoder
│           ├── dvr/
│           │   ├── DvrVideoRecorder.kt        # Direct H.264 bitstream & MediaMuxer DVR
│           │   ├── MediaStoreHelper.kt        # Android 10-15 Scoped Storage publisher
│           │   └── AudioCaptureEngine.kt      # Optional microphone audio capture
│           ├── render/
│           │   ├── FpvSurfaceRenderer.kt      # Aspect-ratio aware preview renderer
│           │   └── VrSplitViewRenderer.kt     # Stereoscopic split-screen VR goggles mode
│           └── ui/
│               └── GalleryBottomSheet.kt      # Flight recordings & snapshots browser
├── build.gradle.kts
├── settings.gradle.kts
├── LICENSE                                    # MIT License
└── README.md
```

---

## 🤝 Contributing

Contributions, issues, and feature requests are welcome!
Feel free to open an issue or submit a pull request:
1. Fork the Project.
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`).
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`).
4. Push to the Branch (`git push origin feature/AmazingFeature`).
5. Open a Pull Request.

---

## 📄 License

Distributed under the **MIT License**. See [`LICENSE`](LICENSE) for more details.
