package com.skydroid.fpv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Comprehensive USB Video Diagnostic Activity
 *
 * Tests ALL possible approaches to get video from Skydroid T12:
 * 1. Full USB interface/endpoint enumeration
 * 2. UVC (USB Video Class) interface detection
 * 3. Camera2 API external camera detection
 * 4. CP2102N serial bridge with raw H.264 parsing
 * 5. Multiple MediaCodec initialization strategies
 * 6. Raw data hex dump analysis
 */
class UsbDiagnosticActivity : AppCompatActivity() {

    private val TAG = "UsbDiag"
    private val ACTION_USB_PERMISSION = "com.skydroid.fpv.DIAG_USB_PERMISSION"

    private lateinit var logView: TextView
    private lateinit var surfaceView: SurfaceView
    private lateinit var usbManager: UsbManager
    private val handler = Handler(Looper.getMainLooper())
    private val logBuffer = StringBuilder()

    // Test state
    private var activeConnection: UsbDeviceConnection? = null
    @Volatile private var testRunning = false
    private var testThread: Thread? = null

    // H264 decode state
    private var mediaCodec: MediaCodec? = null
    private var spsBytes: ByteArray? = null
    private var ppsBytes: ByteArray? = null
    private var codecConfigured = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (device != null && granted) {
                    log("✅ Permission GRANTED for ${device.deviceName}")
                    runFullDiagnostic(device)
                } else {
                    log("❌ Permission DENIED")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        // Build UI programmatically
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // Surface for video rendering tests
        surfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(surfaceView)

        // Semi-transparent log overlay
        val scrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.argb(200, 0, 0, 0))
        }

        logView = TextView(this).apply {
            setTextColor(Color.GREEN)
            textSize = 10f
            setPadding(16, 16, 16, 200)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scrollView.addView(logView)
        rootLayout.addView(scrollView)

        // Control buttons at bottom
        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            setBackgroundColor(Color.argb(220, 30, 30, 30))
            setPadding(8, 8, 8, 8)
        }

        fun makeButton(text: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                this.text = text
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(180, 0, 120, 200))
                setPadding(12, 8, 12, 8)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 4
                }
                setOnClickListener { onClick() }
            }
        }

        buttonBar.addView(makeButton("SCAN USB") { scanAndDiagnose() })
        buttonBar.addView(makeButton("CAMERA2") { testCamera2Api() })
        buttonBar.addView(makeButton("TEST #1\nCP2102 H264") { testApproach1Cp2102() })
        buttonBar.addView(makeButton("TEST #2\nUVC RAW") { testApproach2UvcRaw() })
        buttonBar.addView(makeButton("TEST #3\nRAW DUMP") { testApproach3RawDump() })
        buttonBar.addView(makeButton("STOP") { stopAllTests() })

        rootLayout.addView(buttonBar)
        setContentView(rootLayout)

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        log("═══════════════════════════════════════════")
        log("  SKYDROID T12 USB VIDEO DIAGNOSTIC TOOL")
        log("═══════════════════════════════════════════")
        log("Press SCAN USB to begin enumeration")
        log("")

        // Auto-scan on launch
        handler.postDelayed({ scanAndDiagnose() }, 500)
    }

    // ═══════════════════════════════════════════════
    //  SECTION 1: USB DEVICE ENUMERATION
    // ═══════════════════════════════════════════════

    private fun scanAndDiagnose() {
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        log("PHASE 1: USB DEVICE ENUMERATION")
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        val deviceList = usbManager.deviceList
        if (deviceList.isEmpty()) {
            log("❌ NO USB devices detected!")
            log("   → Check OTG cable connection")
            log("   → Enable OTG in phone settings")
            log("   → Ensure T12 is powered on")
            return
        }

        log("Found ${deviceList.size} USB device(s):")
        log("")

        for ((path, device) in deviceList) {
            logDeviceDetails(device)
            log("")
        }

        // Request permission for all devices
        for ((_, device) in deviceList) {
            if (!usbManager.hasPermission(device)) {
                log("→ Requesting permission for ${device.deviceName}...")
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val intent = Intent(ACTION_USB_PERMISSION).apply {
                    setPackage(packageName)
                }
                usbManager.requestPermission(device, PendingIntent.getBroadcast(this, 0, intent, flags))
            } else {
                log("✅ Already have permission for ${device.deviceName}")
                runFullDiagnostic(device)
            }
        }
    }

    private fun logDeviceDetails(device: UsbDevice) {
        log("╔══ USB DEVICE ════════════════════════════")
        log("║ Name:     ${device.deviceName}")
        log("║ VID:      0x${"%04X".format(device.vendorId)} (${device.vendorId})")
        log("║ PID:      0x${"%04X".format(device.productId)} (${device.productId})")
        log("║ Class:    ${device.deviceClass} (${usbClassToString(device.deviceClass)})")
        log("║ SubClass: ${device.deviceSubclass}")
        log("║ Protocol: ${device.deviceProtocol}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try { log("║ Product:  ${device.productName ?: "N/A"}") } catch (_: Exception) {}
            try { log("║ Mfg:      ${device.manufacturerName ?: "N/A"}") } catch (_: Exception) {}
            try { log("║ Serial:   ${device.serialNumber ?: "N/A"}") } catch (_: Exception) {}
        }
        log("║ Interfaces: ${device.interfaceCount}")

        // Check device type
        val isCp210x = device.vendorId == 0x10C4
        val isUvc = device.deviceClass == 14 || device.deviceClass == 239
        log("║")
        log("║ ► CP2102N UART Bridge: ${if (isCp210x) "YES ✓" else "NO"}")
        log("║ ► UVC Video Class:     ${if (isUvc) "YES ✓" else "CHECKING INTERFACES..."}")

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val ifaceIsUvc = iface.interfaceClass == 14
            val ifaceIsVendor = iface.interfaceClass == 255
            val ifaceIsCdc = iface.interfaceClass == 10
            log("║")
            log("║ ┌─ Interface $i ──────────────────────────")
            log("║ │ ID:       ${iface.id}")
            log("║ │ Class:    ${iface.interfaceClass} (${usbClassToString(iface.interfaceClass)})")
            log("║ │ SubClass: ${iface.interfaceSubclass}")
            log("║ │ Protocol: ${iface.interfaceProtocol}")
            log("║ │ Endpoints: ${iface.endpointCount}")

            if (ifaceIsUvc) {
                log("║ │ ★★★ THIS IS A UVC VIDEO INTERFACE ★★★")
                when (iface.interfaceSubclass) {
                    1 -> log("║ │ SubClass 1 = Video Control")
                    2 -> log("║ │ SubClass 2 = Video Streaming")
                    3 -> log("║ │ SubClass 3 = Video Interface Collection")
                }
            }

            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                val type = when (ep.type) {
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                    UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
                    UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
                    UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
                    else -> "UNKNOWN(${ep.type})"
                }
                log("║ │  EP ${ep.address} (0x${"%02X".format(ep.address)}): $dir $type maxPkt=${ep.maxPacketSize} interval=${ep.interval}")

                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && dir == "IN") {
                    log("║ │  ★ ISOCHRONOUS IN = typical UVC video data endpoint!")
                }
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && dir == "IN") {
                    log("║ │  ★ BULK IN = serial data or bulk-mode UVC")
                }
            }
            log("║ └──────────────────────────────────────")
        }
        log("╚══════════════════════════════════════════")
    }

    // ═══════════════════════════════════════════════
    //  SECTION 2: FULL DIAGNOSTIC ON PERMITTED DEVICE
    // ═══════════════════════════════════════════════

    private fun runFullDiagnostic(device: UsbDevice) {
        log("")
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        log("PHASE 2: DEEP DIAGNOSTIC ON ${device.deviceName}")
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            log("❌ FAILED to open device connection!")
            return
        }
        log("✅ Device connection opened")

        // Get raw USB descriptors
        try {
            val rawDesc = connection.rawDescriptors
            if (rawDesc != null) {
                log("")
                log("RAW USB DESCRIPTORS (${rawDesc.size} bytes):")
                logHexDump(rawDesc, maxBytes = 256)
                parseUsbDescriptors(rawDesc)
            }
        } catch (e: Exception) {
            log("⚠ Could not read raw descriptors: ${e.message}")
        }

        // Try claiming each interface and reading data
        log("")
        log("INTERFACE CLAIMING TESTS:")
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            try {
                val claimed = connection.claimInterface(iface, true)
                log("  Interface $i (class=${iface.interfaceClass}): ${if (claimed) "✅ CLAIMED" else "❌ CLAIM FAILED"}")

                // If this is a UVC streaming interface, try to negotiate
                if (iface.interfaceClass == 14 && iface.interfaceSubclass == 2) {
                    log("  → UVC Streaming interface! Trying VS_PROBE_CONTROL...")
                    tryUvcNegotiation(connection, iface)
                }
            } catch (e: Exception) {
                log("  Interface $i: ❌ Exception: ${e.message}")
            }
        }

        activeConnection = connection
        log("")
        log("Device ready for testing. Use buttons below.")
    }

    // ═══════════════════════════════════════════════
    //  SECTION 3: CAMERA2 API EXTERNAL CAMERA CHECK
    // ═══════════════════════════════════════════════

    private fun testCamera2Api() {
        log("")
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        log("CAMERA2 API - External Camera Detection")
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraIds = cameraManager.cameraIdList
        log("Total cameras: ${cameraIds.size}")

        for (id in cameraIds) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            val facingStr = when (facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "★ EXTERNAL (USB/UVC)"
                else -> "UNKNOWN($facing)"
            }
            log("")
            log("Camera ID $id: $facingStr")

            if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                log("  ★★★ EXTERNAL USB CAMERA FOUND! ★★★")
                log("  This means Android recognizes T12 as UVC webcam!")

                val configs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                if (configs != null) {
                    val sizes = configs.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
                    log("  Supported YUV sizes:")
                    sizes?.forEach { log("    ${it.width}x${it.height}") }

                    val jpegSizes = configs.getOutputSizes(android.graphics.ImageFormat.JPEG)
                    log("  Supported JPEG sizes:")
                    jpegSizes?.forEach { log("    ${it.width}x${it.height}") }
                }
            }

            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            log("  Capabilities: ${capabilities?.joinToString(", ") { capabilityToString(it) }}")
        }

        if (cameraIds.none {
            val chars = cameraManager.getCameraCharacteristics(it)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_EXTERNAL
        }) {
            log("")
            log("⚠ No external camera detected via Camera2 API")
            log("  → T12 may NOT expose a standard UVC interface")
            log("  → OR Android needs UVC class driver support")
            log("  → Try the CP2102N serial approach instead")
        }
    }

    // ═══════════════════════════════════════════════
    //  TEST #1: CP2102N Serial Bridge + H.264
    // ═══════════════════════════════════════════════

    private fun testApproach1Cp2102() {
        stopAllTests()

        log("")
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        log("TEST #1: CP2102N Serial + H.264 Decode")
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        val device = findDevice() ?: return
        val connection = ensureConnection(device) ?: return

        // Find CP2102N interface (VID 10C4)
        val isCp210x = device.vendorId == 0x10C4
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        var targetIface: UsbInterface? = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.direction == UsbConstants.USB_DIR_IN && ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    inEp = ep
                    targetIface = iface
                }
                if (ep.direction == UsbConstants.USB_DIR_OUT && ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    outEp = ep
                }
            }
            if (inEp != null) break
        }

        if (inEp == null) {
            log("❌ No BULK IN endpoint found")
            return
        }

        try {
            connection.claimInterface(targetIface, true)
        } catch (e: Exception) {
            log("❌ Failed to claim interface: ${e.message}")
            return
        }

        if (isCp210x) {
            log("Configuring CP2102N at 4,000,000 baud...")
            configureCp210x(connection, 4000000)
        }

        log("IN endpoint: 0x${"%02X".format(inEp.address)}")
        log("OUT endpoint: ${if (outEp != null) "0x${"%02X".format(outEp.address)}" else "NONE"}")

        // Start streaming with ALL recovery mechanisms
        testRunning = true
        spsBytes = null
        ppsBytes = null
        codecConfigured = false

        testThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            runCp2102StreamTest(connection, inEp, outEp)
        }
        testThread?.name = "DiagTest-CP2102"
        testThread?.start()
    }

    private fun runCp2102StreamTest(conn: UsbDeviceConnection, inEp: UsbEndpoint, outEp: UsbEndpoint?) {
        val readBuf = ByteArray(16384) // Try larger read buffer
        var totalBytes = 0L
        var packetCount = 0
        val startTime = System.currentTimeMillis()
        val streamBuf = ByteArrayOutputStream(256 * 1024)

        // Send AT commands
        if (outEp != null) {
            val cmds = listOf(
                "AT+SWITCH -e1\r\n",
                "AT+LED -e1\r\n",
                "AT+VIDEO -m0 -p1 -f15 -b300 -e1 -g8\r\n"
            )
            for (cmd in cmds) {
                val packet = buildSkydroidAtPacket(cmd)
                val w = conn.bulkTransfer(outEp, packet, packet.size, 200)
                postLog("AT cmd sent (${cmd.trim()}): wrote=$w")
            }
        }

        // Poll packet
        val poll = byteArrayOf(0xFF.toByte(), 0x02, 0x00, 0x55, 0x00, 0xA5.toByte())
        var lastLog = System.currentTimeMillis()
        var lastHeartbeat = System.currentTimeMillis()
        var nalCount = 0
        var spsCount = 0
        var ppsCount = 0
        var idrCount = 0
        var codecInitAttempts = 0
        var decodedFrames = 0

        while (testRunning) {
            val now = System.currentTimeMillis()

            // Heartbeat
            if (outEp != null && now - lastHeartbeat >= 1500) {
                lastHeartbeat = now
                val hb = buildSkydroidAtPacket("AT+VIDEO -x0 -y0\r\n")
                conn.bulkTransfer(outEp, hb, hb.size, 50)
            }

            // Send poll
            if (outEp != null) {
                conn.bulkTransfer(outEp, poll, poll.size, 50)
            }

            // Read with various buffer sizes
            val bytesRead = conn.bulkTransfer(inEp, readBuf, readBuf.size, 30)
            if (bytesRead > 0) {
                totalBytes += bytesRead
                packetCount++

                // Log first 20 packets in detail
                if (packetCount <= 20) {
                    val hex = readBuf.take(minOf(bytesRead, 32)).joinToString(" ") { "%02X".format(it) }
                    postLog("PKT #$packetCount ($bytesRead bytes): $hex")
                }

                // Extract Skydroid payload
                if (readBuf[0] == 0xFF.toByte() && bytesRead >= 4) {
                    val lenHigh = readBuf[1].toInt() and 0xFF
                    val lenLow = readBuf[2].toInt() and 0xFF
                    val marker = readBuf[3].toInt() and 0xFF

                    if (marker == 0xA5) {
                        val payloadLen = ((lenHigh shl 8) or lenLow).coerceAtMost(bytesRead - 4)
                        if (payloadLen > 0) {
                            streamBuf.write(readBuf, 4, payloadLen)

                            // Parse NAL units from accumulated stream
                            val raw = streamBuf.toByteArray()
                            var searchIdx = 0
                            var lastNalStart = -1
                            val nals = mutableListOf<ByteArray>()

                            while (searchIdx <= raw.size - 4) {
                                if (raw[searchIdx] == 0.toByte() && raw[searchIdx + 1] == 0.toByte() &&
                                    raw[searchIdx + 2] == 0.toByte() && raw[searchIdx + 3] == 1.toByte()) {
                                    if (lastNalStart != -1) {
                                        val nal = ByteArray(searchIdx - lastNalStart)
                                        System.arraycopy(raw, lastNalStart, nal, 0, nal.size)
                                        nals.add(nal)
                                    }
                                    lastNalStart = searchIdx
                                    searchIdx += 4
                                } else {
                                    searchIdx++
                                }
                            }

                            // Keep leftover
                            if (lastNalStart != -1) {
                                streamBuf.reset()
                                streamBuf.write(raw, lastNalStart, raw.size - lastNalStart)
                            } else if (raw.size > 512 * 1024) {
                                streamBuf.reset()
                            }

                            // Process NALs
                            for (nal in nals) {
                                if (nal.size < 5) continue
                                nalCount++
                                val nalType = nal[4].toInt() and 0x1F

                                when (nalType) {
                                    7 -> { // SPS
                                        spsCount++
                                        spsBytes = nal.clone()
                                        if (spsCount <= 3) {
                                            val hex = nal.take(minOf(nal.size, 48)).joinToString(" ") { "%02X".format(it) }
                                            postLog("★ SPS #$spsCount (${nal.size}b): $hex")
                                            parseSpsInfo(nal)
                                        }
                                        tryInitCodec()
                                    }
                                    8 -> { // PPS
                                        ppsCount++
                                        ppsBytes = nal.clone()
                                        if (ppsCount <= 3) {
                                            val hex = nal.joinToString(" ") { "%02X".format(it) }
                                            postLog("★ PPS #$ppsCount (${nal.size}b): $hex")
                                        }
                                        tryInitCodec()
                                    }
                                    5 -> { // IDR
                                        idrCount++
                                        if (idrCount <= 3) postLog("★ IDR #$idrCount (${nal.size}b)")
                                        feedToCodec(nal, isKeyFrame = true)
                                    }
                                    1 -> { // P-slice
                                        feedToCodec(nal, isKeyFrame = false)
                                    }
                                    6 -> { // SEI
                                        feedToCodec(nal, isKeyFrame = false)
                                    }
                                    else -> {
                                        if (nalCount <= 50) postLog("NAL type=$nalType (${nal.size}b)")
                                        feedToCodec(nal, isKeyFrame = false)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Periodic status
            if (now - lastLog >= 2000) {
                val elapsed = (now - startTime) / 1000
                val kbps = if (elapsed > 0) totalBytes / 1024 / elapsed else 0
                postLog("── ${elapsed}s │ ${kbps}KB/s │ pkts=$packetCount │ NALs=$nalCount │ SPS=$spsCount PPS=$ppsCount IDR=$idrCount │ codec=${if (codecConfigured) "OK" else "NO"} │ frames=$decodedFrames")
                lastLog = now
            }
        }

        postLog("Stream test stopped. Total: ${totalBytes / 1024} KB in $packetCount packets")
    }

    private fun tryInitCodec() {
        if (codecConfigured) return
        val sps = spsBytes ?: return
        val pps = ppsBytes ?: return

        if (!surfaceView.holder.surface.isValid) {
            postLog("⚠ Surface not ready yet, deferring codec init")
            return
        }

        postLog("═══ INITIALIZING MediaCodec with SPS(${sps.size}b) + PPS(${pps.size}b) ═══")

        // Parse SPS to get actual dimensions
        var width = 640
        var height = 480
        try {
            val dims = parseSpsGetDimensions(sps)
            if (dims != null) {
                width = dims.first
                height = dims.second
                postLog("SPS decoded resolution: ${width}x${height}")
            }
        } catch (e: Exception) {
            postLog("⚠ SPS parse failed, using default ${width}x${height}")
        }

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }

            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            postLog("Codec: ${codec.name}")
            codec.configure(format, surfaceView.holder.surface, null, 0)
            codec.start()
            mediaCodec = codec
            codecConfigured = true
            postLog("✅ MediaCodec STARTED successfully!")
        } catch (e: Exception) {
            postLog("❌ MediaCodec init FAILED: ${e.message}")
            postLog("   Trying to list available codecs...")
            listAvailableCodecs()
        }
    }

    private fun feedToCodec(nal: ByteArray, isKeyFrame: Boolean) {
        val codec = mediaCodec ?: return
        if (!codecConfigured) return

        try {
            val inIdx = codec.dequeueInputBuffer(5000)
            if (inIdx >= 0) {
                val buf = codec.getInputBuffer(inIdx) ?: return
                buf.clear()
                buf.put(nal)
                val flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                codec.queueInputBuffer(inIdx, 0, nal.size, System.nanoTime() / 1000, flags)
            }

            val info = MediaCodec.BufferInfo()
            var outIdx = codec.dequeueOutputBuffer(info, 0)
            while (outIdx >= 0) {
                codec.releaseOutputBuffer(outIdx, true)
                outIdx = codec.dequeueOutputBuffer(info, 0)
            }

            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val outFmt = codec.outputFormat
                postLog("✅ Output format changed: ${outFmt}")
            }
        } catch (e: IllegalStateException) {
            postLog("❌ Codec IllegalState: ${e.message}")
            postLog("   → Releasing codec, will re-init on next SPS/PPS")
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            mediaCodec = null
            codecConfigured = false
            spsBytes = null
            ppsBytes = null
        } catch (e: Exception) {
            postLog("⚠ Codec error: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════
    //  TEST #2: UVC Raw Interface Access
    // ═══════════════════════════════════════════════

    private fun testApproach2UvcRaw() {
        stopAllTests()
        log("")
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        log("TEST #2: UVC Raw Interface Access")
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        val device = findDevice() ?: return
        val connection = ensureConnection(device) ?: return

        // Look for UVC interfaces (class 14) or isochronous endpoints
        var uvcStreamIface: UsbInterface? = null
        var isoEp: UsbEndpoint? = null
        var bulkInEp: UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            log("Checking interface $i: class=${iface.interfaceClass} sub=${iface.interfaceSubclass}")

            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.direction == UsbConstants.USB_DIR_IN) {
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC) {
                        log("  ★ Found ISOCHRONOUS IN endpoint: 0x${"%02X".format(ep.address)} maxPkt=${ep.maxPacketSize}")
                        uvcStreamIface = iface
                        isoEp = ep
                    }
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        log("  Found BULK IN endpoint: 0x${"%02X".format(ep.address)} maxPkt=${ep.maxPacketSize}")
                        if (bulkInEp == null) bulkInEp = ep
                        if (uvcStreamIface == null) uvcStreamIface = iface
                    }
                }
            }
        }

        if (isoEp != null) {
            log("UVC isochronous endpoint found! Trying ISO transfer...")
            try {
                connection.claimInterface(uvcStreamIface, true)
                // Try UVC probe/commit
                tryUvcNegotiation(connection, uvcStreamIface!!)
            } catch (e: Exception) {
                log("❌ Error: ${e.message}")
            }
        } else if (bulkInEp != null) {
            log("No ISO endpoint. Using BULK IN for raw data read...")
            try {
                connection.claimInterface(uvcStreamIface, true)
            } catch (e: Exception) {
                log("⚠ Claim warning: ${e.message}")
            }

            testRunning = true
            testThread = Thread {
                val buf = ByteArray(16384)
                var count = 0
                while (testRunning && count < 100) {
                    val n = connection.bulkTransfer(bulkInEp, buf, buf.size, 100)
                    if (n > 0) {
                        count++
                        val hex = buf.take(minOf(n, 32)).joinToString(" ") { "%02X".format(it) }
                        postLog("UVC-BULK #$count ($n bytes): $hex")
                    }
                }
                postLog("UVC bulk read test done: $count packets")
            }
            testThread?.name = "DiagTest-UVC"
            testThread?.start()
        } else {
            log("❌ No UVC streaming endpoint found on this device")
            log("   The T12 may only expose CP2102N serial interface")
        }
    }

    // ═══════════════════════════════════════════════
    //  TEST #3: Raw Data Dump (All Endpoints)
    // ═══════════════════════════════════════════════

    private fun testApproach3RawDump() {
        stopAllTests()
        log("")
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        log("TEST #3: Raw Data Dump (ALL Endpoints)")
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        val device = findDevice() ?: return
        val connection = ensureConnection(device) ?: return

        testRunning = true
        testThread = Thread {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                try {
                    connection.claimInterface(iface, true)
                    postLog("Claimed interface $i")
                } catch (e: Exception) {
                    postLog("⚠ Can't claim interface $i: ${e.message}")
                }

                for (e in 0 until iface.endpointCount) {
                    if (!testRunning) return@Thread
                    val ep = iface.getEndpoint(e)
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        postLog("Reading from EP 0x${"%02X".format(ep.address)} (iface $i)...")
                        val buf = ByteArray(ep.maxPacketSize.coerceAtLeast(512))
                        for (attempt in 1..10) {
                            if (!testRunning) break
                            val n = connection.bulkTransfer(ep, buf, buf.size, 200)
                            if (n > 0) {
                                val hex = buf.take(minOf(n, 48)).joinToString(" ") { "%02X".format(it) }
                                postLog("  EP 0x${"%02X".format(ep.address)} #$attempt ($n bytes): $hex")
                            } else {
                                postLog("  EP 0x${"%02X".format(ep.address)} #$attempt: no data (result=$n)")
                            }
                        }
                    }
                }
            }
            postLog("Raw dump complete")
        }
        testThread?.name = "DiagTest-RawDump"
        testThread?.start()
    }

    // ═══════════════════════════════════════════════
    //  UVC NEGOTIATION
    // ═══════════════════════════════════════════════

    private fun tryUvcNegotiation(conn: UsbDeviceConnection, iface: UsbInterface) {
        log("Attempting UVC VS_PROBE_CONTROL...")
        // VS_PROBE_CONTROL SET_CUR (bRequest=1, wValue=0x0100, wIndex=ifaceNum)
        val probeData = ByteArray(34) // UVC 1.1 probe control
        // Set hint = 1 (dwFrameInterval), formatIndex=1, frameIndex=1
        probeData[0] = 0x01 // bmHint = 1 (frame interval)
        probeData[2] = 0x01 // bFormatIndex
        probeData[3] = 0x01 // bFrameIndex
        // dwFrameInterval = 333333 (30fps) = 0x00051615
        probeData[4] = 0x15
        probeData[5] = 0x16
        probeData[6] = 0x05
        probeData[7] = 0x00

        val reqType = 0x21 // USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_INTERFACE
        val result = conn.controlTransfer(
            reqType,
            0x01, // SET_CUR
            0x0100, // VS_PROBE_CONTROL
            iface.id,
            probeData,
            probeData.size,
            1000
        )
        log("  VS_PROBE_CONTROL SET_CUR result: $result")

        // GET_CUR to read back negotiated parameters
        val getReqType = 0xA1 // USB_DIR_IN | USB_TYPE_CLASS | USB_RECIP_INTERFACE
        val response = ByteArray(34)
        val getResult = conn.controlTransfer(
            getReqType,
            0x81, // GET_CUR
            0x0100, // VS_PROBE_CONTROL
            iface.id,
            response,
            response.size,
            1000
        )
        log("  VS_PROBE_CONTROL GET_CUR result: $getResult")
        if (getResult > 0) {
            val hex = response.take(getResult).joinToString(" ") { "%02X".format(it) }
            log("  Negotiated: $hex")
            val formatIdx = response[2].toInt() and 0xFF
            val frameIdx = response[3].toInt() and 0xFF
            val interval = (response[4].toInt() and 0xFF) or
                    ((response[5].toInt() and 0xFF) shl 8) or
                    ((response[6].toInt() and 0xFF) shl 16) or
                    ((response[7].toInt() and 0xFF) shl 24)
            val fps = if (interval > 0) 10000000 / interval else 0
            log("  Format=$formatIdx Frame=$frameIdx Interval=$interval (${fps}fps)")
        }
    }

    // ═══════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════

    private fun findDevice(): UsbDevice? {
        val devices = usbManager.deviceList
        if (devices.isEmpty()) {
            log("❌ No USB devices connected")
            return null
        }
        return devices.values.first()
    }

    private fun ensureConnection(device: UsbDevice): UsbDeviceConnection? {
        activeConnection?.let { return it }
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            log("❌ Cannot open device")
            return null
        }
        activeConnection = conn
        return conn
    }

    private fun configureCp210x(connection: UsbDeviceConnection, baudRate: Int) {
        val reqType = 0x41
        connection.controlTransfer(reqType, 0x00, 0x0001, 0, null, 0, 1000) // IFC_ENABLE
        connection.controlTransfer(reqType, 0x07, 0x0303, 0, null, 0, 1000) // SET_MHS
        connection.controlTransfer(reqType, 0x12, 0x0180, 0, null, 0, 1000) // PURGE
        connection.controlTransfer(reqType, 0x03, 0x0800, 0, null, 0, 1000) // SET_LINE_CTL
        val baudBytes = byteArrayOf(
            (baudRate and 0xFF).toByte(),
            ((baudRate shr 8) and 0xFF).toByte(),
            ((baudRate shr 16) and 0xFF).toByte(),
            ((baudRate shr 24) and 0xFF).toByte()
        )
        connection.controlTransfer(reqType, 0x1E, 0, 0, baudBytes, 4, 1000) // SET_BAUDRATE
        log("CP2102N configured: 4Mbaud 8N1")
    }

    private fun buildSkydroidAtPacket(cmd: String): ByteArray {
        val ascii = cmd.toByteArray(Charsets.US_ASCII)
        val len = ascii.size
        val packet = ByteArray(6 + len)
        packet[0] = 0xFF.toByte()
        packet[1] = 0x02
        packet[2] = 0x00
        packet[3] = 0x55
        packet[4] = len.toByte()
        packet[5] = 0xA5.toByte()
        System.arraycopy(ascii, 0, packet, 6, len)
        return packet
    }

    private fun parseSpsInfo(sps: ByteArray) {
        if (sps.size < 8) return
        // NAL header: 00 00 00 01 [type] ...
        val profileIdc = sps[5].toInt() and 0xFF
        val levelIdc = sps[7].toInt() and 0xFF
        postLog("  SPS profile_idc=$profileIdc level_idc=$levelIdc")
        val dims = parseSpsGetDimensions(sps)
        if (dims != null) {
            postLog("  SPS resolution: ${dims.first}x${dims.second}")
        }
    }

    private fun parseSpsGetDimensions(sps: ByteArray): Pair<Int, Int>? {
        if (sps.size < 10) return null
        try {
            // Skip NAL start code (00 00 00 01) and NAL header byte
            val reader = BitReader(sps, 5) // start after 00 00 00 01 67
            val profileIdc = reader.readBits(8)
            reader.readBits(8) // constraint flags
            reader.readBits(8) // level_idc
            reader.readUE() // seq_parameter_set_id

            if (profileIdc in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134)) {
                val chromaFormatIdc = reader.readUE()
                if (chromaFormatIdc == 3) reader.readBit()
                reader.readUE() // bit_depth_luma_minus8
                reader.readUE() // bit_depth_chroma_minus8
                reader.readBit() // qpprime_y_zero_transform_bypass
                if (reader.readBit() == 1) { // seq_scaling_matrix_present
                    val limit = if (chromaFormatIdc != 3) 8 else 12
                    for (i in 0 until limit) {
                        if (reader.readBit() == 1) {
                            skipScalingList(reader, if (i < 6) 16 else 64)
                        }
                    }
                }
            }

            reader.readUE() // log2_max_frame_num_minus4
            val picOrderCntType = reader.readUE()
            if (picOrderCntType == 0) {
                reader.readUE()
            } else if (picOrderCntType == 1) {
                reader.readBit()
                reader.readSE()
                reader.readSE()
                val n = reader.readUE()
                for (i in 0 until n) reader.readSE()
            }

            reader.readUE() // max_num_ref_frames
            reader.readBit() // gaps_in_frame_num
            val picWidthInMbsMinus1 = reader.readUE()
            val picHeightInMapUnitsMinus1 = reader.readUE()
            val frameMbsOnlyFlag = reader.readBit()

            if (frameMbsOnlyFlag == 0) reader.readBit() // mb_adaptive_frame_field

            reader.readBit() // direct_8x8_inference_flag

            var cropLeft = 0; var cropRight = 0; var cropTop = 0; var cropBottom = 0
            if (reader.readBit() == 1) { // frame_cropping_flag
                cropLeft = reader.readUE()
                cropRight = reader.readUE()
                cropTop = reader.readUE()
                cropBottom = reader.readUE()
            }

            val width = (picWidthInMbsMinus1 + 1) * 16 - (cropLeft + cropRight) * 2
            val height = ((2 - frameMbsOnlyFlag) * (picHeightInMapUnitsMinus1 + 1) * 16) - (cropTop + cropBottom) * 2

            return Pair(width, height)
        } catch (e: Exception) {
            return null
        }
    }

    private fun skipScalingList(reader: BitReader, sizeOfScalingList: Int) {
        var lastScale = 8
        var nextScale = 8
        for (j in 0 until sizeOfScalingList) {
            if (nextScale != 0) {
                val deltaScale = reader.readSE()
                nextScale = (lastScale + deltaScale + 256) % 256
            }
            lastScale = if (nextScale == 0) lastScale else nextScale
        }
    }

    private fun listAvailableCodecs() {
        val codecList = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
        for (info in codecList.codecInfos) {
            if (!info.isEncoder && info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) }) {
                log("  Available AVC decoder: ${info.name}")
            }
        }
    }

    private fun parseUsbDescriptors(desc: ByteArray) {
        var offset = 0
        while (offset < desc.size - 1) {
            val bLength = desc[offset].toInt() and 0xFF
            if (bLength == 0) break
            val bDescriptorType = desc[offset + 1].toInt() and 0xFF

            when (bDescriptorType) {
                0x01 -> log("  Descriptor: DEVICE (type=0x01)")
                0x02 -> log("  Descriptor: CONFIGURATION (type=0x02)")
                0x04 -> {
                    if (offset + 8 < desc.size) {
                        val ifNum = desc[offset + 2].toInt() and 0xFF
                        val ifClass = desc[offset + 5].toInt() and 0xFF
                        val ifSubClass = desc[offset + 6].toInt() and 0xFF
                        log("  Descriptor: INTERFACE #$ifNum class=$ifClass(${usbClassToString(ifClass)}) sub=$ifSubClass")
                    }
                }
                0x05 -> log("  Descriptor: ENDPOINT (type=0x05)")
                0x24 -> { // CS_INTERFACE (UVC specific)
                    if (offset + 2 < desc.size) {
                        val subtype = desc[offset + 2].toInt() and 0xFF
                        log("  Descriptor: CS_INTERFACE subtype=0x${"%02X".format(subtype)} (UVC class-specific)")
                    }
                }
                0x25 -> log("  Descriptor: CS_ENDPOINT (type=0x25)")
                0x0B -> log("  Descriptor: INTERFACE_ASSOCIATION (type=0x0B)")
            }
            offset += bLength
        }
    }

    private fun stopAllTests() {
        testRunning = false
        testThread?.join(1000)
        testThread = null
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null
        codecConfigured = false
        spsBytes = null
        ppsBytes = null
        log("All tests stopped")
    }

    // ═══════════════════════════════════════════════
    //  LOGGING
    // ═══════════════════════════════════════════════

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logBuffer.append(msg).append('\n')
        logView.text = logBuffer.toString()
        (logView.parent as? ScrollView)?.post {
            (logView.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun postLog(msg: String) {
        Log.i(TAG, msg)
        handler.post {
            logBuffer.append(msg).append('\n')
            logView.text = logBuffer.toString()
            (logView.parent as? ScrollView)?.post {
                (logView.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun logHexDump(data: ByteArray, maxBytes: Int = 128) {
        val limit = minOf(data.size, maxBytes)
        val sb = StringBuilder()
        for (i in 0 until limit step 16) {
            sb.append("  ${"%04X".format(i)}: ")
            for (j in 0 until 16) {
                if (i + j < limit) sb.append("${"%02X".format(data[i + j])} ")
                else sb.append("   ")
            }
            sb.append(" ")
            for (j in 0 until 16) {
                if (i + j < limit) {
                    val c = data[i + j].toInt() and 0xFF
                    sb.append(if (c in 32..126) c.toChar() else '.')
                }
            }
            log(sb.toString())
            sb.clear()
        }
        if (data.size > maxBytes) log("  ... (${data.size - maxBytes} more bytes)")
    }

    private fun usbClassToString(cls: Int) = when (cls) {
        0 -> "Per-Interface"
        1 -> "Audio"
        2 -> "CDC-Control"
        3 -> "HID"
        6 -> "Still-Image"
        7 -> "Printer"
        8 -> "Mass-Storage"
        9 -> "Hub"
        10 -> "CDC-Data"
        14 -> "VIDEO (UVC)"
        239 -> "Miscellaneous"
        255 -> "Vendor-Specific"
        else -> "Class-$cls"
    }

    private fun capabilityToString(cap: Int) = when (cap) {
        0 -> "BACKWARD_COMPATIBLE"
        1 -> "MANUAL_SENSOR"
        2 -> "MANUAL_POST_PROCESSING"
        3 -> "RAW"
        4 -> "PRIVATE_REPROCESSING"
        5 -> "READ_SENSOR_SETTINGS"
        6 -> "BURST_CAPTURE"
        7 -> "YUV_REPROCESSING"
        8 -> "DEPTH_OUTPUT"
        9 -> "CONSTRAINED_HIGH_SPEED"
        10 -> "MOTION_TRACKING"
        11 -> "LOGICAL_MULTI_CAMERA"
        12 -> "MONOCHROME"
        13 -> "SECURE_IMAGE_DATA"
        14 -> "SYSTEM_CAMERA"
        15 -> "ULTRA_HIGH_RESOLUTION_SENSOR"
        else -> "CAPABILITY_$cap"
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAllTests()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        activeConnection?.close()
    }

    // ═══════════════════════════════════════════════
    //  BIT READER for SPS parsing
    // ═══════════════════════════════════════════════

    private class BitReader(private val data: ByteArray, private var byteOffset: Int) {
        private var bitOffset = 7

        fun readBit(): Int {
            if (byteOffset >= data.size) return 0
            val bit = (data[byteOffset].toInt() shr bitOffset) and 1
            bitOffset--
            if (bitOffset < 0) { bitOffset = 7; byteOffset++ }
            return bit
        }

        fun readBits(n: Int): Int {
            var result = 0
            for (i in 0 until n) result = (result shl 1) or readBit()
            return result
        }

        fun readUE(): Int {
            var zeros = 0
            while (readBit() == 0 && zeros < 32) zeros++
            if (zeros == 0) return 0
            return (1 shl zeros) - 1 + readBits(zeros)
        }

        fun readSE(): Int {
            val v = readUE()
            return if (v % 2 == 1) (v + 1) / 2 else -(v / 2)
        }
    }
}
