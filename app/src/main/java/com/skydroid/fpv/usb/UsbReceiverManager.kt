package com.skydroid.fpv.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.skydroid.fpv.config.AppConfig

/**
 * Manages USB-OTG connection, permissions, and interface claiming for
 * 5.8GHz FPV receivers and UVC video capture devices.
 */
class UsbReceiverManager(
    private val context: Context,
    private val listener: UsbConnectionListener
) {
    private val TAG = "UsbReceiverManager"
    private val ACTION_USB_PERMISSION = "com.skydroid.fpv.USB_PERMISSION"

    interface UsbConnectionListener {
        fun onDeviceAttached(device: UsbDevice)
        fun onDeviceDetached(device: UsbDevice)
        fun onPermissionGranted(device: UsbDevice)
        fun onPermissionDenied(device: UsbDevice)
        fun onStreamReady(
            connection: UsbDeviceConnection,
            usbInterface: UsbInterface,
            inEndpoint: UsbEndpoint,
            outEndpoint: UsbEndpoint?,
            isCp210x: Boolean
        )
        fun onError(message: String)
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var activeDevice: UsbDevice? = null
    private var activeConnection: UsbDeviceConnection? = null
    private var activeInterface: UsbInterface? = null
    private var isReceiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device != null) {
                        if (granted) {
                            Log.d(TAG, "USB Permission granted for: ${device.deviceName}")
                            listener.onPermissionGranted(device)
                            openDeviceStream(device)
                        } else {
                            Log.w(TAG, "USB Permission denied for: ${device.deviceName}")
                            listener.onPermissionDenied(device)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null && isUvcDevice(device)) {
                        Log.d(TAG, "USB Device Attached: ${device.deviceName}")
                        activeDevice = device
                        listener.onDeviceAttached(device)
                        if (usbManager.hasPermission(device)) {
                            listener.onPermissionGranted(device)
                            openDeviceStream(device)
                        } else {
                            requestPermission(device)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null && device == activeDevice) {
                        Log.d(TAG, "USB Device Detached: ${device.deviceName}")
                        closeConnection()
                        listener.onDeviceDetached(device)
                    }
                }
            }
        }
    }

    fun register() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        isReceiverRegistered = true
        scanConnectedDevices()
    }

    fun unregister() {
        if (!isReceiverRegistered) return
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering USB receiver", e)
        }
        isReceiverRegistered = false
        closeConnection()
    }

    fun scanConnectedDevices(): Boolean {
        val deviceList = usbManager.deviceList
        for (device in deviceList.values) {
            if (isUvcDevice(device)) {
                Log.d(TAG, "Found candidate UVC device: ${device.deviceName} (Vendor: 0x${Integer.toHexString(device.vendorId)})")
                activeDevice = device
                listener.onDeviceAttached(device)
                if (usbManager.hasPermission(device)) {
                    listener.onPermissionGranted(device)
                    openDeviceStream(device)
                } else {
                    requestPermission(device)
                }
                return true
            }
        }
        return false
    }

    private fun requestPermission(device: UsbDevice) {
        try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val intent = Intent(ACTION_USB_PERMISSION).apply {
                setPackage(context.packageName)
            }
            val permissionIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                flags
            )
            usbManager.requestPermission(device, permissionIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting USB permission for device ${device.deviceName}", e)
            listener.onError("USB permission request failed: ${e.message}")
        }
    }

    private fun isUvcDevice(device: UsbDevice): Boolean {
        // Class 14 = USB Video Class, Class 239 = Misc (frequently used for composite webcams)
        if (device.deviceClass == 14 || device.deviceClass == 239) return true

        // Check interfaces
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == 14) { // UVC Video Class
                return true
            }
        }
        // Also accept generic devices with bulk IN endpoints typical of Skydroid/ROTG dongles
        return device.interfaceCount > 0
    }

    private fun openDeviceStream(device: UsbDevice) {
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            listener.onError("Failed to open USB connection. Check OTG cable.")
            return
        }

        var streamingInterface: UsbInterface? = null
        var inEndpoint: UsbEndpoint? = null
        var outEndpoint: UsbEndpoint? = null

        // Search for interface with IN and OUT endpoints
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.direction == UsbConstants.USB_DIR_IN && inEndpoint == null) {
                    streamingInterface = iface
                    inEndpoint = ep
                } else if (ep.direction == UsbConstants.USB_DIR_OUT && outEndpoint == null) {
                    outEndpoint = ep
                }
            }
            if (inEndpoint != null) break
        }

        if (streamingInterface == null || inEndpoint == null) {
            // Fallback: claim interface 0
            if (device.interfaceCount > 0) {
                val fallbackIface = device.getInterface(0)
                for (e in 0 until fallbackIface.endpointCount) {
                    val ep = fallbackIface.getEndpoint(e)
                    if (ep.direction == UsbConstants.USB_DIR_IN && inEndpoint == null) {
                        streamingInterface = fallbackIface
                        inEndpoint = ep
                    } else if (ep.direction == UsbConstants.USB_DIR_OUT && outEndpoint == null) {
                        outEndpoint = ep
                    }
                }
            }
        }

        if (streamingInterface != null && inEndpoint != null) {
            try {
                val claimed = connection.claimInterface(streamingInterface, true)
                if (!claimed) {
                    Log.w(TAG, "claimInterface returned false, trying without force")
                    connection.claimInterface(streamingInterface, false)
                }

                val config = AppConfig.getInstance(context)
                val isCp210x = (device.vendorId == 0x10C4 || device.vendorId == 4292)
                if (isCp210x || config.receiverMode == AppConfig.RECEIVER_MODE_CUSTOM_UART || config.receiverMode == AppConfig.RECEIVER_MODE_SKYDROID_T12) {
                    val baudRate = config.usbBaudRate
                    Log.i(TAG, "Configuring UART bridge with baud rate: $baudRate")
                    configureCp210x(connection, baudRate)
                }

                this.activeDevice = device
                this.activeConnection = connection
                this.activeInterface = streamingInterface

                Log.i(TAG, "Streaming endpoint claimed: ${inEndpoint.address}, packetSize=${inEndpoint.maxPacketSize}, isCp210x=$isCp210x")
                listener.onStreamReady(connection, streamingInterface, inEndpoint, outEndpoint, isCp210x)
            } catch (e: Exception) {
                Log.e(TAG, "Exception claiming USB interface", e)
                connection.close()
                listener.onError("Could not claim USB interface: ${e.message}")
            }
        } else {
            connection.close()
            listener.onError("No suitable video streaming endpoint found on receiver")
        }
    }

    fun configureCp210x(connection: UsbDeviceConnection, baudRate: Int) {
        try {
            val reqType = 0x41 // UsbConstants.USB_TYPE_VENDOR | UsbConstants.USB_DIR_OUT
            // 1. IFC_ENABLE: 0x00, value: 0x0001 (enable)
            val ifcRes = connection.controlTransfer(reqType, 0x00, 0x0001, 0, null, 0, 1000)
            Log.i(TAG, "CP210x IFC_ENABLE result: $ifcRes")

            // 2. SET_MHS: 0x07, value: 0x0303 (DTR=1, RTS=1)
            val mhsRes = connection.controlTransfer(reqType, 0x07, 0x0303, 0, null, 0, 1000)
            Log.i(TAG, "CP210x SET_MHS result: $mhsRes")

            // 2b. PURGE FIFOs: 0x12, value: 0x0180
            val purgeRes = connection.controlTransfer(reqType, 0x12, 0x0180, 0, null, 0, 1000)
            Log.i(TAG, "CP210x PURGE result: $purgeRes")

            // 3. SET_LINE_CTL: 0x03, value: 0x0800 (8 data bits, 1 stop bit, no parity)
            val lineRes = connection.controlTransfer(reqType, 0x03, 0x0800, 0, null, 0, 1000)
            Log.i(TAG, "CP210x SET_LINE_CTL result: $lineRes")

            // 4. SET_BAUDRATE: 0x1E
            val baudBytes = byteArrayOf(
                (baudRate and 0xFF).toByte(),
                ((baudRate shr 8) and 0xFF).toByte(),
                ((baudRate shr 16) and 0xFF).toByte(),
                ((baudRate shr 24) and 0xFF).toByte()
            )
            val baudRes = connection.controlTransfer(reqType, 0x1E, 0, 0, baudBytes, 4, 1000)
            Log.i(TAG, "CP210x SET_BAUDRATE ($baudRate) result: $baudRes")
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring CP210x UART bridge", e)
        }
    }

    fun closeConnection() {
        try {
            activeInterface?.let { activeConnection?.releaseInterface(it) }
            activeConnection?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing USB connection", e)
        }
        activeConnection = null
        activeInterface = null
        activeDevice = null
    }

    fun isConnected(): Boolean = activeConnection != null
}
