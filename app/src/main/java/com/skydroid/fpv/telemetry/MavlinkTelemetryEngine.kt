package com.skydroid.fpv.telemetry

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real-time MAVLink 1 & 2 telemetry parser for Pixhawk 2.4.8 (ArduPilot / PX4)
 * and Skydroid T12 transmitter.
 *
 * Listens on:
 * 1. Skydroid T12 USB stream (interleaved telemetry data frames)
 * 2. UDP Socket on port 14550 (standard GCS telemetry broadcast)
 */
class MavlinkTelemetryEngine {

    companion object {
        private const val TAG = "MavlinkEngine"
        private const val MAVLINK_1_STX = 0xFE.toByte()
        private const val MAVLINK_2_STX = 0xFD.toByte()
        const val DEFAULT_UDP_PORT = 14550
    }

    data class TelemetryData(
        val droneLat: Double = 0.0,
        val droneLon: Double = 0.0,
        val relativeAltMeters: Float = 0f,
        val altitudeMslMeters: Float = 0f,
        val headingDeg: Float = 0f,
        val groundSpeedMps: Float = 0f,
        val satellites: Int = 0,
        val gpsFixType: Int = 0, // 0-1: No Fix, 2: 2D, 3: 3D Fix, 4: DGPS, 5: RTK
        val isArmed: Boolean = false,
        val flightMode: String = "DISARMED",
        val batteryVoltage: Float = 0f,
        val batteryCurrentAmps: Float = 0f,
        val batteryRemainingPct: Int = -1,
        val hasGpsLock: Boolean = false,
        val lastUpdateTimestamp: Long = 0L
    )

    @Volatile
    var currentData = TelemetryData()
        private set

    var onTelemetryUpdated: ((TelemetryData) -> Unit)? = null

    // Accumulator buffer for USB streaming fragments
    private val buffer = ByteArray(4096)
    private var bufferLength = 0

    // UDP Listener thread
    @Volatile
    private var isUdpRunning = false
    private var udpSocket: DatagramSocket? = null
    private var udpThread: Thread? = null

    fun startUdpListener(port: Int = DEFAULT_UDP_PORT) {
        stopUdpListener()
        isUdpRunning = true
        udpThread = Thread({
            try {
                val socket = DatagramSocket(port)
                udpSocket = socket
                val packetBuf = ByteArray(2048)
                val packet = DatagramPacket(packetBuf, packetBuf.size)
                Log.i(TAG, "MAVLink UDP listener started on port $port")

                while (isUdpRunning && !socket.isClosed) {
                    try {
                        socket.receive(packet)
                        feedBytes(packet.data, packet.offset, packet.length)
                    } catch (_: Exception) {
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot start MAVLink UDP socket on port $port: ${e.message}")
            }
        }, "SkyFPV-MavlinkUdp").apply {
            isDaemon = true
            start()
        }
    }

    fun stopUdpListener() {
        isUdpRunning = false
        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        udpSocket = null
        try {
            udpThread?.interrupt()
            udpThread?.join(300)
        } catch (_: Exception) {}
        udpThread = null
    }

    /**
     * Feed raw bytes from USB or network stream into MAVLink packet parser.
     */
    @Synchronized
    fun feedBytes(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return

        // Safety: Prevent overflow
        if (bufferLength + length > buffer.size) {
            bufferLength = 0
        }

        System.arraycopy(data, offset, buffer, bufferLength, length)
        bufferLength += length

        var cursor = 0
        while (cursor < bufferLength) {
            val stx = buffer[cursor]
            if (stx == MAVLINK_1_STX) {
                // MAVLink 1: Header is 6 bytes (STX, LEN, SEQ, SYS, COMP, MSGID) + LEN + 2 CRC
                if (cursor + 6 > bufferLength) break
                val payloadLen = buffer[cursor + 1].toInt() and 0xFF
                val totalPacketLen = 6 + payloadLen + 2
                if (cursor + totalPacketLen > bufferLength) break

                val msgId = buffer[cursor + 5].toInt() and 0xFF
                parseMavlinkPayload(msgId, buffer, cursor + 6, payloadLen)
                cursor += totalPacketLen
            } else if (stx == MAVLINK_2_STX) {
                // MAVLink 2: Header is 10 bytes (STX, LEN, INC, CMP, SEQ, SYS, COMP, MSGID[3]) + LEN + 2 CRC
                if (cursor + 10 > bufferLength) break
                val payloadLen = buffer[cursor + 1].toInt() and 0xFF
                val incompatFlags = buffer[cursor + 2].toInt() and 0xFF
                val signatureLen = if ((incompatFlags and 0x01) != 0) 13 else 0
                val totalPacketLen = 10 + payloadLen + 2 + signatureLen
                if (cursor + totalPacketLen > bufferLength) break

                val b0 = buffer[cursor + 7].toInt() and 0xFF
                val b1 = buffer[cursor + 8].toInt() and 0xFF
                val b2 = buffer[cursor + 9].toInt() and 0xFF
                val msgId = b0 or (b1 shl 8) or (b2 shl 16)

                parseMavlinkPayload(msgId, buffer, cursor + 10, payloadLen)
                cursor += totalPacketLen
            } else {
                cursor++
            }
        }

        // Shift remaining unparsed bytes to front of buffer
        if (cursor < bufferLength) {
            val remaining = bufferLength - cursor
            System.arraycopy(buffer, cursor, buffer, 0, remaining)
            bufferLength = remaining
        } else {
            bufferLength = 0
        }
    }

    private fun parseMavlinkPayload(msgId: Int, payload: ByteArray, offset: Int, length: Int) {
        if (offset + length > payload.size) return
        val bb = ByteBuffer.wrap(payload, offset, length).order(ByteOrder.LITTLE_ENDIAN)

        try {
            when (msgId) {
                // HEARTBEAT (Msg #0)
                0 -> {
                    if (length >= 9) {
                        val customMode = bb.getInt(0)
                        val baseMode = bb.get(6).toInt() and 0xFF
                        val isArmed = (baseMode and 128) != 0
                        val flightModeName = getArduCopterFlightMode(customMode, isArmed)
                        updateState { it.copy(isArmed = isArmed, flightMode = flightModeName) }
                    }
                }

                // SYS_STATUS (Msg #1)
                1 -> {
                    if (length >= 31) {
                        val voltageMv = bb.getShort(14).toInt() and 0xFFFF
                        val currentCa = bb.getShort(16).toInt() // 10*mA (centi-amps) or -1
                        val batteryPct = bb.get(18).toInt()
                        val voltageVolts = voltageMv / 1000.0f
                        val currentAmps = if (currentCa > 0) currentCa / 100.0f else 0f
                        updateState {
                            it.copy(
                                batteryVoltage = voltageVolts,
                                batteryCurrentAmps = currentAmps,
                                batteryRemainingPct = if (batteryPct in 0..100) batteryPct else it.batteryRemainingPct
                            )
                        }
                    }
                }

                // GPS_RAW_INT (Msg #24)
                24 -> {
                    if (length >= 30) {
                        val fixType = bb.get(8).toInt() and 0xFF
                        val rawLat = bb.getInt(9)
                        val rawLon = bb.getInt(13)
                        val rawAlt = bb.getInt(17)
                        val sats = bb.get(29).toInt() and 0xFF

                        val hasFix = fixType >= 2 && rawLat != 0 && rawLon != 0
                        val lat = if (hasFix) rawLat / 1e7 else currentData.droneLat
                        val lon = if (hasFix) rawLon / 1e7 else currentData.droneLon

                        updateState {
                            it.copy(
                                droneLat = lat,
                                droneLon = lon,
                                altitudeMslMeters = rawAlt / 1000.0f,
                                gpsFixType = fixType,
                                satellites = sats,
                                hasGpsLock = hasFix
                            )
                        }
                    }
                }

                // ATTITUDE (Msg #30)
                30 -> {
                    if (length >= 28) {
                        val yawRad = bb.getFloat(12)
                        var yawDeg = Math.toDegrees(yawRad.toDouble()).toFloat()
                        if (yawDeg < 0) yawDeg += 360f
                        updateState { it.copy(headingDeg = yawDeg) }
                    }
                }

                // GLOBAL_POSITION_INT (Msg #33)
                33 -> {
                    if (length >= 28) {
                        val rawLat = bb.getInt(4)
                        val rawLon = bb.getInt(8)
                        val altMsl = bb.getInt(12)
                        val relativeAlt = bb.getInt(16)
                        val rawHdg = bb.getShort(26).toInt() and 0xFFFF

                        if (rawLat != 0 && rawLon != 0) {
                            val lat = rawLat / 1e7
                            val lon = rawLon / 1e7
                            val heading = if (rawHdg != 65535) rawHdg / 100.0f else currentData.headingDeg
                            updateState {
                                it.copy(
                                    droneLat = lat,
                                    droneLon = lon,
                                    relativeAltMeters = relativeAlt / 1000.0f,
                                    altitudeMslMeters = altMsl / 1000.0f,
                                    headingDeg = heading,
                                    hasGpsLock = true
                                )
                            }
                        }
                    }
                }

                // VFR_HUD (Msg #74)
                74 -> {
                    if (length >= 20) {
                        val groundSpeed = bb.getFloat(4)
                        val heading = bb.getShort(8).toInt()
                        val alt = bb.getFloat(10)
                        val hdg = if (heading in 0..360) heading.toFloat() else currentData.headingDeg
                        updateState {
                            it.copy(
                                groundSpeedMps = groundSpeed,
                                headingDeg = hdg,
                                relativeAltMeters = alt
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing MAVLink payload msgId $msgId: ${e.message}")
        }
    }

    private fun updateState(transform: (TelemetryData) -> TelemetryData) {
        val updated = transform(currentData).copy(lastUpdateTimestamp = System.currentTimeMillis())
        currentData = updated
        onTelemetryUpdated?.invoke(updated)
    }

    private fun getArduCopterFlightMode(customMode: Int, isArmed: Boolean): String {
        val mode = when (customMode) {
            0 -> "STABILIZE"
            1 -> "ACRO"
            2 -> "ALT_HOLD"
            3 -> "AUTO"
            4 -> "GUIDED"
            5 -> "LOITER"
            6 -> "RTL"
            7 -> "CIRCLE"
            9 -> "LAND"
            11 -> "DRIFT"
            13 -> "SPORT"
            14 -> "FLIP"
            15 -> "AUTOTUNE"
            16 -> "POSHOLD"
            17 -> "BRAKE"
            18 -> "THROW"
            19 -> "AVOID_ADSB"
            20 -> "GUIDED_NOGPS"
            21 -> "SMART_RTL"
            22 -> "FLOWHOLD"
            23 -> "FOLLOW"
            24 -> "ZIGZAG"
            else -> "MODE-$customMode"
        }
        return if (isArmed) mode else "DISARMED ($mode)"
    }
}
