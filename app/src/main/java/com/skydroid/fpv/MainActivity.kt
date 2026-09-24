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
import com.skydroid.fpv.usb.SkydroidT12Engine
import com.skydroid.fpv.usb.UvcProtocolParser
import com.skydroid.fpv.usb.UsbReceiverManager

class MainActivity : AppCompatActivity(), UsbReceiverManager.UsbConnectionListener {

    private lateinit var binding: ActivityFpvBinding

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

    // Permissions Request
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (cameraGranted) {
            usbManager.scanConnectedDevices()
        } else {
            Toast.makeText(this, "Camera permission needed for external USB video feed", Toast.LENGTH_LONG).show()
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

        // Retry USB Scan button
        binding.btnRetryUsb.setOnClickListener {
            vibrate(20)
            val found = usbManager.scanConnectedDevices()
            if (!found) {
                Toast.makeText(this, "Skydroid T12 not detected. Please ensure OTG cable is connected.", Toast.LENGTH_SHORT).show()
            }
        }
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
            val newRecorder = DvrVideoRecorder(this, width = 640, height = 360, frameRate = 30)

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
            Manifest.permission.RECORD_AUDIO
        )
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
    }

    override fun onDestroy() {
        super.onDestroy()
        skydroidEngine?.setNalListener(null)
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
