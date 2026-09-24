package com.skydroid.fpv.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Central configuration repository for SkyFPV.
 * Allows customizing camera receivers, USB baud rates, Bluetooth telemetry,
 * MAVLink parameters, map options, and DVR preferences without hardcoded values.
 */
class AppConfig(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "skyfpv_config"

        // Receiver hardware modes
        const val RECEIVER_MODE_AUTO = 0
        const val RECEIVER_MODE_SKYDROID_T12 = 1
        const val RECEIVER_MODE_UVC_STANDARD = 2
        const val RECEIVER_MODE_CUSTOM_UART = 3

        // Telemetry Sources
        const val TELEMETRY_SOURCE_BLUETOOTH = 0
        const val TELEMETRY_SOURCE_USB = 1
        const val TELEMETRY_SOURCE_UDP = 2
        const val TELEMETRY_SOURCE_DISABLED = 3

        // Map Tile Layers
        const val MAP_LAYER_SATELLITE = 0
        const val MAP_LAYER_STREET = 1
        const val MAP_LAYER_TOPO = 2

        // Latency Profiles
        const val LATENCY_ULTRA_LOW = 0  // Drops lagged frames immediately (lowest delay)
        const val LATENCY_BALANCED = 1   // 1 frame buffer
        const val LATENCY_SMOOTH = 2     // 2 frames buffer (smooth, slight buffer)

        // Aspect Ratios
        const val ASPECT_RATIO_16_9 = 0
        const val ASPECT_RATIO_4_3 = 1
        const val ASPECT_RATIO_STRETCH = 2

        @Volatile
        private var instance: AppConfig? = null

        fun getInstance(context: Context): AppConfig {
            return instance ?: synchronized(this) {
                instance ?: AppConfig(context.applicationContext).also { instance = it }
            }
        }
    }

    // --- Video & Camera Receiver Settings ---

    var receiverMode: Int
        get() = prefs.getInt("receiver_mode", RECEIVER_MODE_AUTO)
        set(value) = prefs.edit().putInt("receiver_mode", value).apply()

    var usbBaudRate: Int
        get() = prefs.getInt("usb_baud_rate", 4000000)
        set(value) = prefs.edit().putInt("usb_baud_rate", value).apply()

    var customResolutionWidth: Int
        get() = prefs.getInt("custom_res_width", 0) // 0 = Auto-detect from SPS / UVC header
        set(value) = prefs.edit().putInt("custom_res_width", value).apply()

    var customResolutionHeight: Int
        get() = prefs.getInt("custom_res_height", 0)
        set(value) = prefs.edit().putInt("custom_res_height", value).apply()

    var defaultAspectRatio: Int
        get() = prefs.getInt("default_aspect_ratio", ASPECT_RATIO_16_9)
        set(value) = prefs.edit().putInt("default_aspect_ratio", value).apply()

    var latencyProfile: Int
        get() = prefs.getInt("latency_profile", LATENCY_ULTRA_LOW)
        set(value) = prefs.edit().putInt("latency_profile", value).apply()

    // --- Telemetry & Bluetooth Settings ---

    var telemetrySource: Int
        get() = prefs.getInt("telemetry_source", TELEMETRY_SOURCE_BLUETOOTH)
        set(value) = prefs.edit().putInt("telemetry_source", value).apply()

    var bluetoothDeviceAddress: String
        get() = prefs.getString("bt_device_address", "") ?: ""
        set(value) = prefs.edit().putString("bt_device_address", value).apply()

    var bluetoothDeviceName: String
        get() = prefs.getString("bt_device_name", "") ?: ""
        set(value) = prefs.edit().putString("bt_device_name", value).apply()

    var autoConnectBluetooth: Boolean
        get() = prefs.getBoolean("bt_auto_connect", true)
        set(value) = prefs.edit().putBoolean("bt_auto_connect", value).apply()

    var telemetryBaudRate: Int
        get() = prefs.getInt("telemetry_baud_rate", 57600) // Standard Pixhawk / ArduPilot TELEM baud
        set(value) = prefs.edit().putInt("telemetry_baud_rate", value).apply()

    var udpTelemetryPort: Int
        get() = prefs.getInt("udp_telemetry_port", 14550)
        set(value) = prefs.edit().putInt("udp_telemetry_port", value).apply()

    /**
     * When true, re-broadcasts all incoming Bluetooth MAVLink telemetry
     * over local UDP (127.0.0.1:14550) so QGroundControl / Mission Planner can connect simultaneously.
     */
    var forwardTelemetryToUdp: Boolean
        get() = prefs.getBoolean("forward_telemetry_udp", true)
        set(value) = prefs.edit().putBoolean("forward_telemetry_udp", value).apply()

    var forwardUdpTargetPort: Int
        get() = prefs.getInt("forward_udp_port", 14550)
        set(value) = prefs.edit().putInt("forward_udp_port", value).apply()

    // --- Map & Navigation Display Settings ---

    var mapDefaultLayer: Int
        get() = prefs.getInt("map_default_layer", MAP_LAYER_SATELLITE)
        set(value) = prefs.edit().putInt("map_default_layer", value).apply()

    var mapAutoFollow: Boolean
        get() = prefs.getBoolean("map_auto_follow", true)
        set(value) = prefs.edit().putBoolean("map_auto_follow", value).apply()

    var showFlightTrail: Boolean
        get() = prefs.getBoolean("show_flight_trail", true)
        set(value) = prefs.edit().putBoolean("show_flight_trail", value).apply()

    var maxTrailPoints: Int
        get() = prefs.getInt("max_trail_points", 200)
        set(value) = prefs.edit().putInt("max_trail_points", value).apply()

    var showHomePoint: Boolean
        get() = prefs.getBoolean("show_home_point", true)
        set(value) = prefs.edit().putBoolean("show_home_point", value).apply()

    var showBearingLine: Boolean
        get() = prefs.getBoolean("show_bearing_line", true)
        set(value) = prefs.edit().putBoolean("show_bearing_line", value).apply()

    // --- DVR & Recording Settings ---

    var recordAudio: Boolean
        get() = prefs.getBoolean("dvr_record_audio", true)
        set(value) = prefs.edit().putBoolean("dvr_record_audio", value).apply()

    // Reset helper
    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }
}
