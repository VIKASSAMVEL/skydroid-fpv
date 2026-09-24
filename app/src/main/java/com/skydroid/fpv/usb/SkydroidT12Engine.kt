package com.skydroid.fpv.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.Process
import android.util.Log
import android.view.Surface
import com.skydroid.fpv.video.H264DecoderEngine
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Manages USB communication and video streaming protocol for Skydroid T12 transmitter
 * and CP2102N USB-to-UART bridge running at 4,000,000 BAUD.
 */
class SkydroidT12Engine(
    private val connection: UsbDeviceConnection,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
    surface: Surface,
    private val onFpsUpdate: (Int) -> Unit,
    private val onThroughputUpdate: (Int) -> Unit
) {
    private val TAG = "SkydroidT12Engine"

    @Volatile
    private var isRunning = false
    private var workerThread: Thread? = null

    private val decoder = H264DecoderEngine(surface) { fps ->
        onFpsUpdate(fps)
    }

    // Outbound command queue
    private val commandQueue = ConcurrentLinkedQueue<ByteArray>()

    // Standard video frame poll packet: [0xFF, 0x02, 0x00, 0x55, 0x00, 0xA5]
    private val pollPacket = byteArrayOf(
        0xFF.toByte(),
        0x02,
        0x00,
        0x55,
        0x00,
        0xA5.toByte()
    )

    fun start() {
        stop()
        isRunning = true

        // Queue initial activation AT commands
        queueAtCommand("AT+SWITCH -e1\r\n")
        queueAtCommand("AT+LED -e1\r\n")
        queueAtCommand("AT+VIDEO -m0 -p1 -f15 -b300 -e1 -g8\r\n")

        workerThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            streamLoop()
        }, "Skydroid-T12-Loop")
        workerThread?.priority = Thread.MAX_PRIORITY
        workerThread?.start()
    }

    fun queueAtCommand(cmd: String) {
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
        commandQueue.offer(packet)
    }

    private fun streamLoop() {
        val readBuffer = ByteArray(16384)
        var totalBytesSec = 0
        var lastRateCheck = System.currentTimeMillis()
        var lastHeartbeat = System.currentTimeMillis()
        var pollCounter = 0

        Log.i(TAG, "Skydroid T12 streaming loop started on EP IN: ${inEndpoint.address}, OUT: ${outEndpoint.address}")

        while (isRunning) {
            val now = System.currentTimeMillis()

            // Heartbeat every 1.5 seconds to keep Skydroid video transmission active
            if (now - lastHeartbeat >= 1500) {
                lastHeartbeat = now
                queueAtCommand("AT+VIDEO -x0 -y0\r\n")
            }

            // Determine packet to send: queued command or standard poll
            val packetToSend = commandQueue.poll() ?: pollPacket
            val written = connection.bulkTransfer(outEndpoint, packetToSend, packetToSend.size, 100)
            if (written < 0) {
                // USB bus glitch or stall, brief yield
                try {
                    Thread.sleep(2)
                } catch (_: InterruptedException) {
                    break
                }
            }

            // Read response from transmitter (512-byte bulk response)
            val bytesRead = connection.bulkTransfer(inEndpoint, readBuffer, readBuffer.size, 30)
            if (bytesRead > 0) {
                totalBytesSec += bytesRead

                if (pollCounter < 10) {
                    pollCounter++
                    val preview = readBuffer.take(Math.min(bytesRead, 16)).joinToString(" ") { "%02X".format(it) }
                    Log.i(TAG, "Skydroid RX #$pollCounter ($bytesRead bytes): $preview")
                }

                // Check Skydroid framing: starts with 0xFF, 4th byte is packet type
                if (readBuffer[0] == 0xFF.toByte() && bytesRead >= 4) {
                    val lenHigh = readBuffer[1].toInt() and 0xFF
                    val lenLow = readBuffer[2].toInt() and 0xFF
                    val packetType = readBuffer[3].toInt() and 0xFF
                    val payloadLen = ((lenHigh shl 8) or lenLow).coerceAtMost(bytesRead - 4)

                    if (packetType == 0xA5 && payloadLen > 0) {
                        // Offset 4 is the start of H.264 elementary stream data
                        decoder.feedData(readBuffer, 4, payloadLen)
                    }
                }
            }

            // Throughput tracking
            if (now - lastRateCheck >= 1000) {
                val bps = totalBytesSec
                totalBytesSec = 0
                lastRateCheck = now
                onThroughputUpdate(bps)
            }
        }

        Log.i(TAG, "Skydroid T12 streaming loop ended")
    }

    fun getSps(): ByteArray? = decoder.getSps()
    fun getPps(): ByteArray? = decoder.getPps()
    fun getVideoWidth(): Int = decoder.videoWidth
    fun getVideoHeight(): Int = decoder.videoHeight

    fun setNalListener(listener: ((ByteArray, Boolean) -> Unit)?) {
        decoder.nalListener = listener
    }

    fun stop() {
        isRunning = false
        decoder.nalListener = null
        try {
            workerThread?.interrupt()
            workerThread?.join(300)
        } catch (_: Exception) {}
        workerThread = null
        decoder.release()
    }
}
