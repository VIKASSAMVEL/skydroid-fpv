package com.skydroid.fpv

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.skydroid.fpv.databinding.ActivityFpvBinding
import com.skydroid.fpv.dvr.DvrVideoRecorder
import com.skydroid.fpv.dvr.MediaStoreHelper
import com.skydroid.fpv.render.FpvSurfaceRenderer
import com.skydroid.fpv.render.VrSplitViewRenderer
import com.skydroid.fpv.service.DvrRecordingService
import com.skydroid.fpv.ui.GalleryBottomSheet
import android.util.Log
import android.view.PixelCopy
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.transition.TransitionManager
import com.skydroid.fpv.config.AppConfig
import com.skydroid.fpv.telemetry.BluetoothTelemetryManager
import com.skydroid.fpv.telemetry.MavlinkTelemetryEngine
import com.skydroid.fpv.ui.DroneMapController
import com.skydroid.fpv.ui.SettingsBottomSheet
import com.skydroid.fpv.usb.SkydroidT12Engine
import com.skydroid.fpv.usb.UvcProtocolParser
import com.skydroid.fpv.usb.UsbReceiverManager
import java.util.Locale

class MainActivity : AppCompatActivity(), UsbReceiverManager.UsbConnectionListener {

    private lateinit var binding: ActivityFpvBinding

    // App Configuration & Hardware Profile
    private lateinit var config: AppConfig

    // USB & UVC Pipeline
    private lateinit var usbManager: UsbReceiverManager
    private lateinit var uvcParser: UvcProtocolParser
    private var skydroidEngine: SkydroidT12Engine? = null

    // Renderers
    private lateinit var singleRenderer: FpvSurfaceRenderer
    private lateinit var vrRenderer: VrSplitViewRenderer
    private var isVrModeEnabled = false

    // DVR Recording
    private var dvrRecorder: DvrVideoRecorder? = null
    private var latestFrame: Bitmap? = null

    // Drone Map & MAVLink Telemetry
    private lateinit var mapController: DroneMapController
    private val telemetryEngine = MavlinkTelemetryEngine()
    private lateinit var bluetoothManager: BluetoothTelemetryManager
    private var isMapFullscreen = false

    // Permissions Request
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (cameraGranted) {
            usbManager.scanConnectedDevices()
        }
        val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions[Manifest.permission.BLUETOOTH_CONNECT] ?: false
        } else {
            true
        }
        if (btGranted && config.autoConnectBluetooth && config.bluetoothDeviceAddress.isNotBlank()) {
            bluetoothManager.connectByAddress(config.bluetoothDeviceAddress)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUI()

        binding = ActivityFpvBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep screen on during flight
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        initRenderers()
        initUsbEngine()
        initControls()
        initMapAndTelemetry()
        checkPermissions()
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun initRenderers() {
        singleRenderer = FpvSurfaceRenderer(binding.fpvSurfaceView)
        vrRenderer = VrSplitViewRenderer(binding.vrSurfaceLeft, binding.vrSurfaceRight)
    }

    private fun initUsbEngine() {
        uvcParser = UvcProtocolParser { bitmap, fps ->
            runOnUiThread {
                latestFrame = bitmap
                binding.tvOsdFps.text = getString(R.string.osd_fps_format, fps)

                if (isVrModeEnabled) {
                    vrRenderer.renderVrFrame(bitmap)
                } else {
                    singleRenderer.renderFrame(bitmap)
                }

                // Feed frame to DVR recorder if active
                dvrRecorder?.encodeFrame(bitmap)
            }
        }

        uvcParser.byteRateListener = { bytesPerSec ->
            runOnUiThread {
                val kbps = bytesPerSec / 1024
                if (bytesPerSec > 0) {
                    binding.tvOsdStatus.text = "STREAMING (${kbps} KB/s)"
                    binding.tvOsdStatus.setTextColor(getColor(R.color.fpv_green))
                } else if (usbManager.isConnected()) {
                    binding.tvOsdStatus.text = "TRANSMITTER LINK OK (NO VIDEO)"
                    binding.tvOsdStatus.setTextColor(getColor(R.color.fpv_cyan))
                }
            }
        }

        usbManager = UsbReceiverManager(this, this)
        usbManager.register()
    }

    private fun initControls() {
        // Main DVR Record Button
        binding.btnRecord.setOnClickListener {
            vibrate(50)
            toggleRecording()
        }

        // Snapshot Button
        binding.btnSnapshot.setOnClickListener {
            vibrate(30)
            takeSnapshot()
        }

        // VR Split-Screen Mode Toggle
        binding.btnToggleVr.setOnClickListener {
            vibrate(20)
            isVrModeEnabled = !isVrModeEnabled
            if (isVrModeEnabled) {
                binding.fpvSurfaceView.visibility = View.GONE
                binding.vrContainer.visibility = View.VISIBLE
                binding.btnToggleVr.setColorFilter(getColor(R.color.fpv_cyan))
                Toast.makeText(this, "VR Goggles Split Mode Active", Toast.LENGTH_SHORT).show()
            } else {
                binding.vrContainer.visibility = View.GONE
                binding.fpvSurfaceView.visibility = View.VISIBLE
                binding.btnToggleVr.setColorFilter(getColor(R.color.fpv_text_primary))
            }
        }

        // Aspect Ratio Toggle (4:3 -> 16:9 -> Full Stretch)
        binding.btnToggleAspect.setOnClickListener {
            vibrate(20)
            val nextMode = when (singleRenderer.getAspectRatioMode()) {
                FpvSurfaceRenderer.AspectRatioMode.NATIVE_4_3 -> {
                    binding.tvOsdAspect.text = "16:9 WIDE"
                    FpvSurfaceRenderer.AspectRatioMode.WIDESCREEN_16_9
                }
                FpvSurfaceRenderer.AspectRatioMode.WIDESCREEN_16_9 -> {
                    binding.tvOsdAspect.text = "FULL STRETCH"
                    FpvSurfaceRenderer.AspectRatioMode.FILL_STRETCH
                }
                FpvSurfaceRenderer.AspectRatioMode.FILL_STRETCH -> {
                    binding.tvOsdAspect.text = "4:3 FPV"
                    FpvSurfaceRenderer.AspectRatioMode.NATIVE_4_3
                }
            }
            singleRenderer.setAspectRatioMode(nextMode)
        }

        // Flight Gallery Button
        binding.btnGallery.setOnClickListener {
            vibrate(20)
            val gallerySheet = GalleryBottomSheet()
            gallerySheet.show(supportFragmentManager, "GalleryBottomSheet")
        }

        // Settings / Configuration Button
        binding.btnSettings.setOnClickListener {
            vibrate(20)
            showSettingsDialog()
        }

        // Top OSD Bluetooth Badge tap
        binding.tvOsdBluetooth.setOnClickListener {
            vibrate(20)
            showSettingsDialog()
        }

        // Retry USB Scan button
        binding.btnRetryUsb.setOnClickListener {
            vibrate(20)
            val found = usbManager.scanConnectedDevices()
            if (!found) {
                Toast.makeText(this, "Skydroid T12 not detected. Please ensure OTG cable is connected.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSettingsDialog() {
        val sheet = SettingsBottomSheet(bluetoothManager) {
            applyConfiguration()
        }
        sheet.show(supportFragmentManager, "SettingsBottomSheet")
    }

    private fun applyConfiguration() {
        mapController.applyConfig(config)
        if (config.telemetrySource == AppConfig.TELEMETRY_SOURCE_BLUETOOTH) {
            if (bluetoothManager.currentState == BluetoothTelemetryManager.State.DISCONNECTED &&
                config.bluetoothDeviceAddress.isNotBlank() &&
                bluetoothManager.hasBluetoothPermission()
            ) {
                bluetoothManager.connectByAddress(config.bluetoothDeviceAddress)
            }
        } else if (config.telemetrySource == AppConfig.TELEMETRY_SOURCE_DISABLED) {
            bluetoothManager.disconnect()
        }
    }

    private fun initMapAndTelemetry() {
        config = AppConfig.getInstance(this)
        bluetoothManager = BluetoothTelemetryManager(this, telemetryEngine)
        bluetoothManager.onStateChanged = { state, _ ->
            runOnUiThread {
                when (state) {
                    BluetoothTelemetryManager.State.CONNECTED -> {
                        val name = bluetoothManager.connectedDeviceName.ifBlank { "CONNECTED" }
                        binding.tvOsdBluetooth.text = "📡 BT: $name"
                        binding.tvOsdBluetooth.setTextColor(getColor(R.color.fpv_green))
                    }
                    BluetoothTelemetryManager.State.CONNECTING -> {
                        binding.tvOsdBluetooth.text = "📡 BT: CONNECTING..."
                        binding.tvOsdBluetooth.setTextColor(getColor(R.color.fpv_yellow))
                    }
                    BluetoothTelemetryManager.State.ERROR -> {
                        binding.tvOsdBluetooth.text = "📡 BT: ERROR"
                        binding.tvOsdBluetooth.setTextColor(getColor(R.color.fpv_record_red))
                    }
                    BluetoothTelemetryManager.State.DISCONNECTED -> {
                        binding.tvOsdBluetooth.text = "📡 BT: OFF"
                        binding.tvOsdBluetooth.setTextColor(getColor(R.color.fpv_text_secondary))
                    }
                }
            }
        }

        mapController = DroneMapController(this, binding.osmMapView)
        mapController.applyConfig(config)

        // Auto-connect to saved Bluetooth device if enabled
        if (config.telemetrySource == AppConfig.TELEMETRY_SOURCE_BLUETOOTH &&
            config.autoConnectBluetooth &&
            config.bluetoothDeviceAddress.isNotBlank() &&
            bluetoothManager.hasBluetoothPermission()
        ) {
            bluetoothManager.connectByAddress(config.bluetoothDeviceAddress)
        }

        // Telemetry listener from MAVLink engine
        telemetryEngine.onTelemetryUpdated = { data ->
            runOnUiThread {
                mapController.updateTelemetry(data)
                updateTelemetryHud(data)
            }
        }
        telemetryEngine.startUdpListener(config.udpTelemetryPort)

        // Mini-Map tap -> Expand to Fullscreen (DJI Fly style)
        binding.cardMapContainer.setOnClickListener {
            if (!isMapFullscreen) {
                vibrate(20)
                setMapFullscreen(true)
            }
        }

        // When Map is fullscreen, tapping the video PiP thumbnail swaps back to FPV video
        binding.viewportContainer.setOnClickListener {
            if (isMapFullscreen) {
                vibrate(20)
                setMapFullscreen(false)
            }
        }

        // Fullscreen map action buttons
        binding.btnCollapseMap.setOnClickListener {
            vibrate(20)
            setMapFullscreen(false)
        }

        binding.btnMapLayer.setOnClickListener {
            vibrate(20)
            val isSat = mapController.toggleMapLayer()
            binding.btnMapLayer.text = if (isSat) "SAT VIEW" else "STREET VIEW"
        }

        binding.btnCenterDrone.setOnClickListener {
            vibrate(20)
            mapController.centerOnDrone()
        }

        binding.btnMapZoomIn.setOnClickListener {
            mapController.zoomIn()
        }

        binding.btnMapZoomOut.setOnClickListener {
            mapController.zoomOut()
        }
    }

    private fun updateTelemetryHud(data: MavlinkTelemetryEngine.TelemetryData) {
        // Mini map badge
        val fixLabel = if (data.hasGpsLock) "SATS: ${data.satellites} (3D)" else "GPS: WAITING"
        binding.tvMiniMapSats.text = fixLabel
        binding.tvMiniMapSats.setTextColor(if (data.hasGpsLock) getColor(R.color.fpv_green) else getColor(R.color.fpv_yellow))

        // Fullscreen map telemetry ribbon
        binding.tvMapFlightMode.text = data.flightMode
        binding.tvMapFlightMode.setTextColor(if (data.isArmed) getColor(R.color.fpv_green) else getColor(R.color.fpv_yellow))

        if (data.hasGpsLock) {
            binding.tvMapCoords.text = String.format(Locale.US, "%.5f, %.5f", data.droneLat, data.droneLon)
        } else {
            binding.tvMapCoords.text = "NO GPS FIX"
        }

        binding.tvMapAlt.text = String.format(Locale.US, "ALT: %.1fm", data.relativeAltMeters)
        binding.tvMapSpeed.text = String.format(Locale.US, "SPD: %.1fm/s", data.groundSpeedMps)
    }

    private fun setMapFullscreen(fullscreen: Boolean) {
        TransitionManager.beginDelayedTransition(binding.rootLayout)
        isMapFullscreen = fullscreen

        val mapParams = binding.cardMapContainer.layoutParams as ConstraintLayout.LayoutParams
        val videoParams = binding.viewportContainer.layoutParams as ConstraintLayout.LayoutParams

        if (fullscreen) {
            // Map expands to fill 100% of the screen
            mapParams.width = ConstraintLayout.LayoutParams.MATCH_PARENT
            mapParams.height = ConstraintLayout.LayoutParams.MATCH_PARENT
            mapParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            mapParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            mapParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            mapParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            mapParams.setMargins(0, 0, 0, 0)
            binding.cardMapContainer.radius = 0f
            binding.cardMapContainer.strokeWidth = 0

            // Show Map Controls & Telemetry
            binding.layoutMapControls.visibility = View.VISIBLE
            binding.layoutMiniMapBadge.visibility = View.GONE

            // Hide standard FPV HUD & floating buttons
            binding.layoutControlsBar.visibility = View.GONE
            binding.osdTopLeft.visibility = View.GONE
            binding.osdBottomLeft.visibility = View.GONE

            // Shrink Video to PiP in bottom-left corner
            videoParams.width = dpToPx(190f)
            videoParams.height = dpToPx(125f)
            videoParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            videoParams.endToEnd = ConstraintLayout.LayoutParams.UNSET
            videoParams.topToTop = ConstraintLayout.LayoutParams.UNSET
            videoParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            videoParams.setMargins(dpToPx(16f), 0, 0, dpToPx(16f))
            binding.viewportContainer.elevation = dpToPx(12f).toFloat()
        } else {
            // Restore Video to Fullscreen
            videoParams.width = ConstraintLayout.LayoutParams.MATCH_PARENT
            videoParams.height = ConstraintLayout.LayoutParams.MATCH_PARENT
            videoParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            videoParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            videoParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            videoParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            videoParams.setMargins(0, 0, 0, 0)
            binding.viewportContainer.elevation = 0f

            // Return Map to floating mini-card at bottom center
            mapParams.width = dpToPx(190f)
            mapParams.height = dpToPx(125f)
            mapParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            mapParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            mapParams.topToTop = ConstraintLayout.LayoutParams.UNSET
            mapParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            mapParams.setMargins(0, 0, 0, dpToPx(16f))
            binding.cardMapContainer.radius = dpToPx(12f).toFloat()
            binding.cardMapContainer.strokeWidth = dpToPx(1.5f)

            // Hide Map Controls, show mini badge
            binding.layoutMapControls.visibility = View.GONE
            binding.layoutMiniMapBadge.visibility = View.VISIBLE

            // Restore standard FPV HUD & controls
            binding.layoutControlsBar.visibility = View.VISIBLE
            binding.osdTopLeft.visibility = View.VISIBLE
            binding.osdBottomLeft.visibility = View.VISIBLE
        }

        binding.cardMapContainer.layoutParams = mapParams
        binding.viewportContainer.layoutParams = videoParams
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun toggleRecording() {
        val recorder = dvrRecorder
        if (recorder != null && recorder.isRecording) {
            // Stop recording
            skydroidEngine?.setNalListener(null)
            val saved = recorder.stop()
            dvrRecorder = null
            DvrRecordingService.stopService(this)

            binding.btnRecord.isSelected = false
            binding.layoutRecIndicator.visibility = View.GONE
            if (saved) {
                Toast.makeText(this, "Flight recording saved to Gallery!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Recording stopped (no video frames captured)", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Start recording
            val engine = skydroidEngine
            val newRecorder = DvrVideoRecorder(
                context = this,
                width = 640,
                height = 360,
                frameRate = 30,
                enableAudio = config.recordAudio
            )

            val started = if (engine != null) {
                // Direct H.264 bitstream recording for Skydroid T12
                val sps = engine.getSps()
                val pps = engine.getPps()
                val w = engine.getVideoWidth()
                val h = engine.getVideoHeight()
                engine.setNalListener { nal, isKeyFrame ->
                    newRecorder.feedH264Nal(nal, isKeyFrame)
                }
                newRecorder.startDirectH264(sps, pps, w, h) { elapsedMs ->
                    runOnUiThread {
                        val sec = (elapsedMs / 1000) % 60
                        val min = (elapsedMs / (1000 * 60)) % 60
                        val hr = (elapsedMs / (1000 * 60 * 60))
                        binding.tvRecTimer.text = String.format("%02d:%02d:%02d", hr, min, sec)
                    }
                }
            } else {
                // Bitmap frame recording fallback for UVC/Simulator
                newRecorder.start { elapsedMs ->
                    runOnUiThread {
                        val sec = (elapsedMs / 1000) % 60
                        val min = (elapsedMs / (1000 * 60)) % 60
                        val hr = (elapsedMs / (1000 * 60 * 60))
                        binding.tvRecTimer.text = String.format("%02d:%02d:%02d", hr, min, sec)
                    }
                }
            }

            if (started) {
                dvrRecorder = newRecorder
                DvrRecordingService.startService(this)
                binding.btnRecord.isSelected = true
                binding.layoutRecIndicator.visibility = View.VISIBLE
            } else {
                engine?.setNalListener(null)
                Toast.makeText(this, "Failed to start DVR recording", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun takeSnapshot() {
        val frame = latestFrame
        if (frame != null) {
            val uri = MediaStoreHelper.saveSnapshot(this, frame)
            if (uri != null) {
                Toast.makeText(this, "Snapshot saved to Photos!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to save snapshot", Toast.LENGTH_SHORT).show()
            }
        } else if (skydroidEngine != null) {
            val surfaceView = binding.fpvSurfaceView
            if (surfaceView.width > 0 && surfaceView.height > 0) {
                val bitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
                PixelCopy.request(surfaceView, bitmap, { result ->
                    if (result == PixelCopy.SUCCESS) {
                        val uri = MediaStoreHelper.saveSnapshot(this, bitmap)
                        runOnUiThread {
                            if (uri != null) {
                                Toast.makeText(this, "Snapshot saved to Photos!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "Failed to save snapshot", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            }
        } else {
            Toast.makeText(this, "No video frame available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun vibrate(durationMs: Long) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    // ==================== UsbConnectionListener ====================

    override fun onDeviceAttached(device: UsbDevice) {
        runOnUiThread {
            binding.layoutDisconnectedPrompt.visibility = View.GONE
            binding.tvOsdStatus.text = getString(R.string.status_connected)
            binding.tvOsdStatus.setTextColor(getColor(R.color.fpv_green))
        }
    }

    override fun onDeviceDetached(device: UsbDevice) {
        runOnUiThread {
            skydroidEngine?.stop()
            skydroidEngine = null
            uvcParser.stopStreaming()
            if (dvrRecorder?.isRecording == true) {
                toggleRecording()
            }
            binding.layoutDisconnectedPrompt.visibility = View.VISIBLE
            binding.tvOsdStatus.text = getString(R.string.status_disconnected)
            binding.tvOsdStatus.setTextColor(getColor(R.color.fpv_record_red))
        }
    }

    override fun onPermissionGranted(device: UsbDevice) {
        runOnUiThread {
            binding.tvOsdStatus.text = "PERMISSION GRANTED"
        }
    }

    override fun onPermissionDenied(device: UsbDevice) {
        runOnUiThread {
            Toast.makeText(this, "USB Permission denied for ${device.deviceName}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onStreamReady(
        connection: UsbDeviceConnection,
        usbInterface: UsbInterface,
        inEndpoint: UsbEndpoint,
        outEndpoint: UsbEndpoint?,
        isCp210x: Boolean
    ) {
        runOnUiThread {
            binding.layoutDisconnectedPrompt.visibility = View.GONE
            binding.tvOsdStatus.text = getString(R.string.status_streaming)
            binding.tvOsdStatus.setTextColor(getColor(R.color.fpv_cyan))

            if (isCp210x && outEndpoint != null) {
                Log.i("MainActivity", "Starting Skydroid T12 CP2102 4MBaud H.264 engine...")
                skydroidEngine?.stop()
                val surface = binding.fpvSurfaceView.holder.surface
                val engine = SkydroidT12Engine(
                    connection = connection,
                    inEndpoint = inEndpoint,
                    outEndpoint = outEndpoint,
                    surface = surface,
                    onFpsUpdate = { fps ->
                        runOnUiThread {
                            binding.tvOsdFps.text = getString(R.string.osd_fps_format, fps)
                        }
                    },
                    onThroughputUpdate = { bytesPerSec ->
                        runOnUiThread {
                            val kbps = bytesPerSec / 1024
                            if (bytesPerSec > 0) {
                                binding.tvOsdStatus.text = "SKYDROID T12 (${kbps} KB/s)"
                                binding.tvOsdStatus.setTextColor(getColor(R.color.fpv_green))
                            } else {
                                binding.tvOsdStatus.text = "TRANSMITTER LINK OK (NO VIDEO)"
                                binding.tvOsdStatus.setTextColor(getColor(R.color.fpv_cyan))
                            }
                        }
                    }
                )

                // Hook MAVLink telemetry stream from non-video USB packets
                engine.telemetryListener = { bytes, offset, length ->
                    telemetryEngine.feedBytes(bytes, offset, length)
                }

                skydroidEngine = engine
                dvrRecorder?.let { recorder ->
                    if (recorder.isRecording) {
                        engine.setNalListener { nal, isKeyFrame ->
                            recorder.feedH264Nal(nal, isKeyFrame)
                        }
                    }
                }
                engine.start()
            } else {
                uvcParser.startStreaming(connection, inEndpoint)
            }
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        mapController.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapController.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothManager.disconnect()
        telemetryEngine.stopUdpListener()
        skydroidEngine?.setNalListener(null)
        skydroidEngine?.telemetryListener = null
        skydroidEngine?.stop()
        skydroidEngine = null
        uvcParser.stopStreaming()
        if (dvrRecorder?.isRecording == true) {
            dvrRecorder?.stop()
            dvrRecorder = null
        }
        usbManager.unregister()
    }
}
