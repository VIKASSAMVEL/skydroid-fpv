package com.skydroid.fpv.usb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Parses high-throughput UVC video data from the USB streaming endpoint.
 * Detects MJPEG frame boundaries (SOI 0xFFD8, EOI 0xFFD9), extracts frames,
 * decodes them to Bitmap with sub-frame latency, and tracks live FPS.
 */
class UvcProtocolParser(
    private val frameListener: (Bitmap, Int) -> Unit
) {
    private val TAG = "UvcProtocolParser"

    @Volatile
    private var isStreaming = false
    private var workerThread: Thread? = null

    // Real-time FPS Tracking
    private var frameCount = 0
    private var lastFpsTimestamp = System.currentTimeMillis()
    private var currentFps = 0

    // Bitmap reuse options for zero garbage collection jitter
    private val bitmapOptions = BitmapFactory.Options().apply {
        inMutable = true
        inPreferredConfig = Bitmap.Config.RGB_565 // Faster decoding & lower memory footprint for FPV
    }

    fun startStreaming(connection: UsbDeviceConnection, endpoint: UsbEndpoint) {
        stopStreaming()
        isStreaming = true
        workerThread = Thread({
            streamLoop(connection, endpoint)
        }, "SkyFPV-UvcParser")
        workerThread?.priority = Thread.MAX_PRIORITY
        workerThread?.start()
    }

    var byteRateListener: ((Int) -> Unit)? = null

    private fun streamLoop(connection: UsbDeviceConnection, endpoint: UsbEndpoint) {
        val packetSize = endpoint.maxPacketSize.coerceAtLeast(4096)
        val readBuffer = ByteArray(packetSize)
        val frameBuffer = ByteArrayOutputStream(256 * 1024)

        var insideFrame = false
        var prevByte = 0.toByte()

        var totalBytesInSec = 0
        var lastRateTimestamp = System.currentTimeMillis()
        var loggedPackets = 0

        Log.i(TAG, "USB Streaming loop started. Endpoint: ${endpoint.address}, PacketSize: $packetSize")

        while (isStreaming) {
            val bytesRead = connection.bulkTransfer(endpoint, readBuffer, readBuffer.size, 50)
            if (bytesRead > 0) {
                totalBytesInSec += bytesRead

                if (loggedPackets < 5) {
                    loggedPackets++
                    val preview = readBuffer.take(Math.min(bytesRead, 32)).joinToString(" ") { "%02X".format(it) }
                    Log.i(TAG, "USB RX #$loggedPackets ($bytesRead bytes): $preview")
                }

                var offset = 0

                // Check for UVC Payload Header (Header Length byte at start of packet)
                if (bytesRead > 2) {
                    val headerLength = readBuffer[0].toInt() and 0xFF
                    if (headerLength in 2..12 && headerLength < bytesRead) {
                        offset = headerLength
                    }
                }

                for (i in offset until bytesRead) {
                    val b = readBuffer[i]

                    // Detect JPEG SOI (0xFF 0xD8)
                    if (!insideFrame && prevByte == 0xFF.toByte() && b == 0xD8.toByte()) {
                        insideFrame = true
                        frameBuffer.reset()
                        frameBuffer.write(0xFF)
                        frameBuffer.write(0xD8)
                    } else if (insideFrame) {
                        frameBuffer.write(b.toInt())
                        // Detect JPEG EOI (0xFF 0xD9)
                        if (prevByte == 0xFF.toByte() && b == 0xD9.toByte()) {
                            insideFrame = false
                            val jpegBytes = frameBuffer.toByteArray()
                            processJpegFrame(jpegBytes)
                        }
                    }
                    prevByte = b
                }
            }

            val now = System.currentTimeMillis()
            if (now - lastRateTimestamp >= 1000) {
                val bps = totalBytesInSec
                totalBytesInSec = 0
                lastRateTimestamp = now
                byteRateListener?.invoke(bps)
                if (bps > 0) {
                    Log.i(TAG, "Stream throughput: ${bps / 1024} KB/s, FPS: $currentFps")
                }
            }
        }
    }

    private fun processJpegFrame(jpegBytes: ByteArray) {
        try {
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bitmapOptions)
            if (bitmap != null) {
                frameCount++
                val now = System.currentTimeMillis()
                if (now - lastFpsTimestamp >= 1000) {
                    currentFps = frameCount
                    frameCount = 0
                    lastFpsTimestamp = now
                }
                frameListener(bitmap, currentFps)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error decoding MJPEG frame", e)
        }
    }



    fun stopStreaming() {
        isStreaming = false
        try {
            workerThread?.join(300)
        } catch (_: InterruptedException) {}
        workerThread = null
    }
}
