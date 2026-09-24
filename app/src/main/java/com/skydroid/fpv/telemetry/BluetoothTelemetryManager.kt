package com.skydroid.fpv.telemetry

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.skydroid.fpv.config.AppConfig
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID

/**
 * Manages Bluetooth SPP (Serial Port Profile) connection for Skydroid T12,
 * Pixhawk telemetry bridges, HC-05/06, Crossfire, ELRS, and other MAVLink radio links.
 *
 * Reads raw MAVLink stream and feeds it directly into [MavlinkTelemetryEngine].
 * Optionally re-broadcasts the stream via local UDP (127.0.0.1:14550) so external GCS apps
 * (like QGroundControl or Mission Planner) can run simultaneously on the same Android device.
 */
class BluetoothTelemetryManager(
    private val context: Context,
    private val telemetryEngine: MavlinkTelemetryEngine
) {

    companion object {
        private const val TAG = "BluetoothTelemMgr"
        // Standard Serial Port Profile (SPP) UUID
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    enum class State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    @Volatile
    var currentState: State = State.DISCONNECTED
        private set

    var connectedDeviceName: String = ""
        private set

    var connectedDeviceAddress: String = ""
        private set

    var onStateChanged: ((State, String?) -> Unit)? = null

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothManager?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
    }

    private var activeSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    @Volatile
    private var isWorkerRunning = false
    private var workerThread: Thread? = null

    // Local UDP forwarding socket
    private var udpSocket: DatagramSocket? = null
    private var udpTargetAddress: InetAddress? = null

    fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    fun getPairedDevices(): List<BluetoothDevice> {
        if (!hasBluetoothPermission() || bluetoothAdapter == null) {
            return emptyList()
        }
        return try {
            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException getting bonded devices", e)
            emptyList()
        }
    }

    @Synchronized
    fun connectByAddress(macAddress: String) {
        if (macAddress.isBlank() || bluetoothAdapter == null) {
            updateState(State.ERROR, "Invalid Bluetooth MAC address")
            return
        }

        try {
            val device = bluetoothAdapter?.getRemoteDevice(macAddress)
            if (device != null) {
                connect(device)
            } else {
                updateState(State.ERROR, "Bluetooth device not found: $macAddress")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving device by address $macAddress", e)
            updateState(State.ERROR, e.message)
        }
    }

    @Synchronized
    fun connect(device: BluetoothDevice) {
        if (!hasBluetoothPermission()) {
            updateState(State.ERROR, "Bluetooth permission not granted")
            return
        }

        disconnect()

        val devName = try { device.name ?: device.address } catch (_: SecurityException) { device.address }
        connectedDeviceName = devName
        connectedDeviceAddress = device.address

        updateState(State.CONNECTING, "Connecting to $devName...")

        workerThread = Thread({
            var socket: BluetoothSocket? = null
            try {
                // Cancel discovery before connecting as it slows down connection
                try {
                    bluetoothAdapter?.cancelDiscovery()
                } catch (_: SecurityException) {}

                // Attempt standard SPP RFCOMM connection
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                try {
                    socket.connect()
                } catch (connectException: Exception) {
                    Log.w(TAG, "Standard SPP connection failed, attempting fallback reflection socket: ${connectException.message}")
                    socket.close()
                    // Fallback to channel 1 reflection (standard for non-standard BT chips like HC-05/T12)
                    val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    socket = method.invoke(device, 1) as BluetoothSocket
                    socket.connect()
                }

                activeSocket = socket
                inputStream = socket.inputStream
                outputStream = socket.outputStream

                // Save last successfully connected device in config
                val config = AppConfig.getInstance(context)
                config.bluetoothDeviceAddress = device.address
                config.bluetoothDeviceName = devName

                updateState(State.CONNECTED, "Connected: $devName")

                // Start reader loop
                readTelemetryLoop(socket.inputStream)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to Bluetooth device $devName", e)
                try { socket?.close() } catch (_: Exception) {}
                disconnect()
                updateState(State.ERROR, "Connection failed: ${e.message}")
            }
        }, "BT-Telemetry-Thread")

        workerThread?.start()
    }

    @Synchronized
    fun disconnect() {
        isWorkerRunning = false
        try {
            inputStream?.close()
        } catch (_: Exception) {}
        inputStream = null

        try {
            outputStream?.close()
        } catch (_: Exception) {}
        outputStream = null

        try {
            activeSocket?.close()
        } catch (_: Exception) {}
        activeSocket = null

        try {
            workerThread?.interrupt()
        } catch (_: Exception) {}
        workerThread = null

        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        udpSocket = null

        if (currentState != State.DISCONNECTED) {
            updateState(State.DISCONNECTED, "Disconnected")
        }
    }

    private fun readTelemetryLoop(stream: InputStream) {
        isWorkerRunning = true
        val buffer = ByteArray(2048)
        val config = AppConfig.getInstance(context)

        // Setup local UDP forwarder if enabled
        if (config.forwardTelemetryToUdp) {
            try {
                udpSocket = DatagramSocket()
                udpTargetAddress = InetAddress.getByName("127.0.0.1")
            } catch (e: Exception) {
                Log.w(TAG, "Could not initialize local UDP forwarding socket", e)
            }
        }

        Log.i(TAG, "Bluetooth telemetry reader loop started for $connectedDeviceName")

        while (isWorkerRunning) {
            try {
                val bytesRead = stream.read(buffer)
                if (bytesRead > 0) {
                    // Feed MAVLink packet directly into engine
                    telemetryEngine.feedBytes(buffer, 0, bytesRead)

                    // Re-broadcast to local UDP for QGroundControl/Mission Planner
                    val socket = udpSocket
                    val targetAddr = udpTargetAddress
                    if (socket != null && targetAddr != null && config.forwardTelemetryToUdp) {
                        try {
                            val packet = DatagramPacket(buffer, bytesRead, targetAddr, config.forwardUdpTargetPort)
                            socket.send(packet)
                        } catch (_: Exception) {}
                    }
                } else if (bytesRead == -1) {
                    Log.w(TAG, "Bluetooth EOF reached (device closed connection)")
                    break
                }
            } catch (e: Exception) {
                if (isWorkerRunning) {
                    Log.e(TAG, "Bluetooth read error: ${e.message}")
                }
                break
            }
        }

        if (isWorkerRunning) {
            disconnect()
            updateState(State.DISCONNECTED, "Connection lost")
        }
    }

    /**
     * Send MAVLink command or heartbeat packet back over Bluetooth to the drone/Pixhawk.
     */
    fun sendData(data: ByteArray): Boolean {
        return try {
            val out = outputStream ?: return false
            out.write(data)
            out.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error writing data to Bluetooth stream", e)
            false
        }
    }

    private fun updateState(newState: State, message: String?) {
        currentState = newState
        onStateChanged?.invoke(newState, message)
    }
}
