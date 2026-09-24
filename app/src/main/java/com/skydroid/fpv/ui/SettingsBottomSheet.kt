package com.skydroid.fpv.ui

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.skydroid.fpv.R
import com.skydroid.fpv.config.AppConfig
import com.skydroid.fpv.telemetry.BluetoothTelemetryManager

/**
 * Modern configuration bottom-sheet dialog for SkyFPV.
 * Allows user to configure Bluetooth telemetry, hardware receiver types,
 * USB UART baud rates, latency profiles, map layers, and DVR options without hardcoding.
 */
class SettingsBottomSheet(
    private val bluetoothManager: BluetoothTelemetryManager,
    private val onConfigChanged: () -> Unit
) : BottomSheetDialogFragment() {

    private lateinit var config: AppConfig
    private var pairedDevices: List<BluetoothDevice> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        config = AppConfig.getInstance(requireContext())

        setupHeader(view)
        setupBluetoothSection(view)
        setupReceiverSection(view)
        setupMapSection(view)
        setupDvrSection(view)
    }

    private fun setupHeader(view: View) {
        view.findViewById<ImageButton>(R.id.btn_close_settings).setOnClickListener {
            dismiss()
        }

        view.findViewById<MaterialButton>(R.id.btn_reset_defaults).setOnClickListener {
            config.resetToDefaults()
            Toast.makeText(requireContext(), "Settings reset to defaults", Toast.LENGTH_SHORT).show()
            onConfigChanged()
            dismiss()
        }
    }

    private fun setupBluetoothSection(view: View) {
        val tvStatus = view.findViewById<TextView>(R.id.tv_bt_status_badge)
        val btnConnect = view.findViewById<MaterialButton>(R.id.btn_bt_connect_toggle)
        val spinnerDevices = view.findViewById<Spinner>(R.id.spinner_bt_devices)
        val spinnerTelemSource = view.findViewById<Spinner>(R.id.spinner_telemetry_source)
        val spinnerBaud = view.findViewById<Spinner>(R.id.spinner_telemetry_baud)
        val switchUdp = view.findViewById<SwitchMaterial>(R.id.switch_forward_udp)

        // 1. Update Connection State UI
        fun updateBtUi() {
            when (bluetoothManager.currentState) {
                BluetoothTelemetryManager.State.CONNECTED -> {
                    tvStatus.text = "Status: Connected (${bluetoothManager.connectedDeviceName})"
                    tvStatus.setTextColor(Color.parseColor("#00FF66"))
                    btnConnect.text = "Disconnect"
                    btnConnect.setBackgroundColor(Color.parseColor("#44FF3333"))
                    btnConnect.strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF3333"))
                }
                BluetoothTelemetryManager.State.CONNECTING -> {
                    tvStatus.text = "Status: Connecting..."
                    tvStatus.setTextColor(Color.parseColor("#FFAA00"))
                    btnConnect.text = "Cancel"
                }
                BluetoothTelemetryManager.State.ERROR -> {
                    tvStatus.text = "Status: Connection Error"
                    tvStatus.setTextColor(Color.parseColor("#FF4444"))
                    btnConnect.text = "Retry"
                }
                BluetoothTelemetryManager.State.DISCONNECTED -> {
                    tvStatus.text = "Status: Disconnected"
                    tvStatus.setTextColor(Color.parseColor("#888888"))
                    btnConnect.text = "Connect"
                    btnConnect.setBackgroundColor(Color.parseColor("#1A00E5FF"))
                    btnConnect.strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5FF"))
                }
            }
        }

        updateBtUi()
        bluetoothManager.onStateChanged = { _, _ ->
            activity?.runOnUiThread {
                updateBtUi()
            }
        }

        // 2. Load Paired Bluetooth Devices
        pairedDevices = bluetoothManager.getPairedDevices()
        val deviceNames = if (pairedDevices.isNotEmpty()) {
            pairedDevices.map { dev ->
                val name = try { dev.name ?: "Unknown" } catch (_: SecurityException) { "Unknown" }
                "$name (${dev.address})"
            }.toMutableList()
        } else {
            mutableListOf("No paired devices found (Pair in Android Settings)")
        }

        val deviceAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, deviceNames)
        spinnerDevices.adapter = deviceAdapter

        // Pre-select saved device
        val savedMac = config.bluetoothDeviceAddress
        val selectedIdx = pairedDevices.indexOfFirst { it.address.equals(savedMac, ignoreCase = true) }
        if (selectedIdx >= 0) {
            spinnerDevices.setSelection(selectedIdx)
        }

        spinnerDevices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (position in pairedDevices.indices) {
                    val dev = pairedDevices[position]
                    config.bluetoothDeviceAddress = dev.address
                    config.bluetoothDeviceName = try { dev.name ?: dev.address } catch (_: SecurityException) { dev.address }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Connect button handler
        btnConnect.setOnClickListener {
            if (bluetoothManager.currentState == BluetoothTelemetryManager.State.CONNECTED) {
                bluetoothManager.disconnect()
            } else {
                val pos = spinnerDevices.selectedItemPosition
                if (pos in pairedDevices.indices) {
                    bluetoothManager.connect(pairedDevices[pos])
                } else {
                    // Open Android Bluetooth settings to pair
                    try {
                        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Please pair Skydroid T12 in Bluetooth Settings first", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // 3. Telemetry Source
        val sources = listOf("Bluetooth SPP (Skydroid T12 / Radio)", "USB Demuxed Stream", "UDP Network (Port 14550)", "Disabled")
        val sourceAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, sources)
        spinnerTelemSource.adapter = sourceAdapter
        spinnerTelemSource.setSelection(config.telemetrySource.coerceIn(0, sources.size - 1))
        spinnerTelemSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                config.telemetrySource = position
                onConfigChanged()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 4. MAVLink Baud Rate
        val bauds = listOf(57600, 115200, 38400, 19200, 9600)
        val baudLabels = bauds.map { if (it == 57600) "$it Baud (Pixhawk / ArduPilot Default)" else "$it Baud" }
        val baudAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, baudLabels)
        spinnerBaud.adapter = baudAdapter
        val baudIdx = bauds.indexOf(config.telemetryBaudRate).let { if (it >= 0) it else 0 }
        spinnerBaud.setSelection(baudIdx)
        spinnerBaud.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                config.telemetryBaudRate = bauds[position]
                onConfigChanged()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 5. Forward to UDP Switch
        switchUdp.isChecked = config.forwardTelemetryToUdp
        switchUdp.setOnCheckedChangeListener { _, isChecked ->
            config.forwardTelemetryToUdp = isChecked
        }
    }

    private fun setupReceiverSection(view: View) {
        val spinnerReceiver = view.findViewById<Spinner>(R.id.spinner_receiver_type)
        val spinnerUsbBaud = view.findViewById<Spinner>(R.id.spinner_usb_baud)
        val spinnerLatency = view.findViewById<Spinner>(R.id.spinner_latency_profile)

        // Receiver Hardware Types
        val receiverTypes = listOf(
            "Auto-Detect Receiver Hardware",
            "Skydroid T12 Digital (CP2102N UART)",
            "Standard 5.8G UVC (Eachine ROTG01/02, FUAV)",
            "Custom UART Video Bridge"
        )
        val receiverAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, receiverTypes)
        spinnerReceiver.adapter = receiverAdapter
        spinnerReceiver.setSelection(config.receiverMode.coerceIn(0, receiverTypes.size - 1))
        spinnerReceiver.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                config.receiverMode = position
                onConfigChanged()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // USB Baud Rates
        val usbBauds = listOf(4000000, 2000000, 1000000, 921600, 500000, 115200, 57600)
        val usbBaudLabels = usbBauds.map {
            when (it) {
                4000000 -> "4,000,000 Baud (Skydroid T12 Default)"
                115200 -> "115,200 Baud (Standard Serial)"
                else -> String.format("%,d Baud", it)
            }
        }
        val usbBaudAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, usbBaudLabels)
        spinnerUsbBaud.adapter = usbBaudAdapter
        val usbBaudIdx = usbBauds.indexOf(config.usbBaudRate).let { if (it >= 0) it else 0 }
        spinnerUsbBaud.setSelection(usbBaudIdx)
        spinnerUsbBaud.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                config.usbBaudRate = usbBauds[position]
                onConfigChanged()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Latency Buffer Profile
        val latencyProfiles = listOf(
            "Ultra-Low Latency (Immediate Frame Push - FPV Recommended)",
            "Balanced (1-Frame Jitter Buffer)",
            "Smooth Playback (2-Frames Buffer)"
        )
        val latencyAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, latencyProfiles)
        spinnerLatency.adapter = latencyAdapter
        spinnerLatency.setSelection(config.latencyProfile.coerceIn(0, latencyProfiles.size - 1))
        spinnerLatency.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                config.latencyProfile = position
                onConfigChanged()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupMapSection(view: View) {
        val spinnerMapLayer = view.findViewById<Spinner>(R.id.spinner_map_layer)
        val switchFollow = view.findViewById<SwitchMaterial>(R.id.switch_map_auto_follow)
        val switchTrail = view.findViewById<SwitchMaterial>(R.id.switch_map_trail)
        val switchHome = view.findViewById<SwitchMaterial>(R.id.switch_map_home_bearing)

        val mapLayers = listOf(
            "🛰️ ESRI World Imagery (High-Res Satellite)",
            "🗺️ OpenStreetMap Mapnik (Standard Street)",
            "⛰️ OpenTopoMap (Terrain & Elevation)"
        )
        val mapAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, mapLayers)
        spinnerMapLayer.adapter = mapAdapter
        spinnerMapLayer.setSelection(config.mapDefaultLayer.coerceIn(0, mapLayers.size - 1))
        spinnerMapLayer.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                config.mapDefaultLayer = position
                onConfigChanged()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        switchFollow.isChecked = config.mapAutoFollow
        switchFollow.setOnCheckedChangeListener { _, isChecked ->
            config.mapAutoFollow = isChecked
            onConfigChanged()
        }

        switchTrail.isChecked = config.showFlightTrail
        switchTrail.setOnCheckedChangeListener { _, isChecked ->
            config.showFlightTrail = isChecked
            onConfigChanged()
        }

        switchHome.isChecked = config.showBearingLine
        switchHome.setOnCheckedChangeListener { _, isChecked ->
            config.showBearingLine = isChecked
            config.showHomePoint = isChecked
            onConfigChanged()
        }
    }

    private fun setupDvrSection(view: View) {
        val switchAudio = view.findViewById<SwitchMaterial>(R.id.switch_dvr_audio)
        switchAudio.isChecked = config.recordAudio
        switchAudio.setOnCheckedChangeListener { _, isChecked ->
            config.recordAudio = isChecked
            onConfigChanged()
        }
    }
}
