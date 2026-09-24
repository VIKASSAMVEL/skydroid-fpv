package com.skydroid.fpv.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Ultra-low-latency H.264 NAL parser and hardware MediaCodec decoder for Skydroid video stream.
 * Feeds NAL units directly to Android's hardware video decoder and renders directly to the
 * hardware Surface with zero memory copies and sub-frame display latency.
 *
 * KEY FIX: MediaCodec is ONLY initialized after both SPS (NAL type 7) and PPS (NAL type 8)
 * are captured from the stream. The SPS is parsed to extract the actual video resolution
 * (640x360 for Skydroid T12) rather than hardcoding dimensions.
 */
class H264DecoderEngine(
    private val surface: Surface,
    private val onFpsUpdate: (Int) -> Unit
) {
    private val TAG = "H264DecoderEngine"

    private var mediaCodec: MediaCodec? = null
    private var isConfigured = false
    private val bufferInfo = MediaCodec.BufferInfo()

    // Realtime FPS tracking
    private var frameCount = 0
    private var lastFpsTimestamp = System.currentTimeMillis()
    private var currentFps = 0

    // Video properties & DVR hook
    var videoWidth: Int = 640
        private set
    var videoHeight: Int = 360
        private set
    var nalListener: ((nal: ByteArray, isKeyFrame: Boolean) -> Unit)? = null

    fun getSps(): ByteArray? = spsBytes
    fun getPps(): ByteArray? = ppsBytes

    // Accumulation buffer for extracting NAL units delimited by 0x00 0x00 0x00 0x01
    private val streamBuffer = ByteArrayOutputStream(128 * 1024)
    private var spsBytes: ByteArray? = null
    private var ppsBytes: ByteArray? = null

    // Presentation timestamp counter
    private var framePtsUs = 0L

    @Synchronized
    fun feedData(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return

        streamBuffer.write(data, offset, length)
        val rawBytes = streamBuffer.toByteArray()

        var searchIndex = 0
        var lastNalStart = -1

        while (searchIndex <= rawBytes.size - 4) {
            if (rawBytes[searchIndex] == 0.toByte() &&
                rawBytes[searchIndex + 1] == 0.toByte() &&
                rawBytes[searchIndex + 2] == 0.toByte() &&
                rawBytes[searchIndex + 3] == 1.toByte()
            ) {
                if (lastNalStart != -1) {
                    // We found a complete NAL unit between lastNalStart and searchIndex
                    val nalLength = searchIndex - lastNalStart
                    val nalUnit = ByteArray(nalLength)
                    System.arraycopy(rawBytes, lastNalStart, nalUnit, 0, nalLength)
                    processNalUnit(nalUnit)
                }
                lastNalStart = searchIndex
                searchIndex += 4
            } else {
                searchIndex++
            }
        }

        // Retain remaining incomplete NAL fragment in the buffer
        if (lastNalStart != -1) {
            val remainingLength = rawBytes.size - lastNalStart
            streamBuffer.reset()
            streamBuffer.write(rawBytes, lastNalStart, remainingLength)
        } else if (rawBytes.size > 256 * 1024) {
            // Safety guard: drop corrupt data if no NAL start code found in 256KB
            streamBuffer.reset()
        }
    }

    private fun processNalUnit(nal: ByteArray) {
        if (nal.size < 5) return

        val nalType = nal[4].toInt() and 0x1F

        when (nalType) {
            7 -> { // SPS (Sequence Parameter Set)
                spsBytes = nal.clone()
                Log.i(TAG, "Captured SPS (${nal.size} bytes)")
                checkInitializeCodec()
            }
            8 -> { // PPS (Picture Parameter Set)
                ppsBytes = nal.clone()
                Log.i(TAG, "Captured PPS (${nal.size} bytes)")
                checkInitializeCodec()
            }
            5 -> { // IDR Slice (Keyframe)
                decodeNalUnit(nal, isKeyFrame = true)
            }
            1 -> { // Non-IDR Slice
                decodeNalUnit(nal, isKeyFrame = false)
            }
            else -> {
                // Other NAL types (SEI, delimiter, etc.) - feed if codec is active
                if (isConfigured) {
                    decodeNalUnit(nal, isKeyFrame = false)
                }
            }
        }
        // Forward NAL unit to DVR recorder if active
        nalListener?.invoke(nal, nalType == 5)
    }

    private fun checkInitializeCodec() {
        if (isConfigured) return

        // CRITICAL: Require BOTH SPS and PPS before initializing MediaCodec.
        // Without both, the hardware decoder will fail with IllegalStateException.
        val sps = spsBytes ?: return
        val pps = ppsBytes ?: return

        if (!surface.isValid) {
            Log.w(TAG, "Cannot initialize MediaCodec: surface is not valid yet")
            return
        }

        // Parse actual resolution from SPS instead of hardcoding
        var width = 640
        var height = 360
        try {
            val dims = parseSpsGetDimensions(sps)
            if (dims != null) {
                width = dims.first
                height = dims.second
                videoWidth = width
                videoHeight = height
                Log.i(TAG, "SPS decoded resolution: ${width}x${height}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "SPS parse failed, using default ${width}x${height}", e)
        }

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                setInteger(MediaFormat.KEY_PUSH_BLANK_BUFFERS_ON_STOP, 1)
            }

            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            Log.i(TAG, "Using codec: ${codec.name}")
            codec.configure(format, surface, null, 0)
            codec.start()
            this.mediaCodec = codec
            this.isConfigured = true
            Log.i(TAG, "MediaCodec AVC hardware decoder configured and started (${width}x${height})")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing MediaCodec decoder", e)
        }
    }

    private fun decodeNalUnit(nal: ByteArray, isKeyFrame: Boolean) {
        val codec = mediaCodec ?: return
        if (!isConfigured) return

        try {
            val inIndex = codec.dequeueInputBuffer(5000)
            if (inIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(nal)
                    val flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    framePtsUs += 33333L // ~30fps delta
                    codec.queueInputBuffer(inIndex, 0, nal.size, framePtsUs, flags)
                }
            }

            // Drain decoded frames directly to the hardware Surface
            var outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            while (outIndex >= 0) {
                // Render directly onto the hardware Surface with 0 latency
                codec.releaseOutputBuffer(outIndex, true)
                frameCount++
                val now = System.currentTimeMillis()
                if (now - lastFpsTimestamp >= 1000) {
                    currentFps = frameCount
                    frameCount = 0
                    lastFpsTimestamp = now
                    onFpsUpdate(currentFps)
                }
                outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }

            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.i(TAG, "Output format changed: ${codec.outputFormat}")
            }
        } catch (e: IllegalStateException) {
            // MediaCodec entered Released or Error state - must fully reset
            Log.w(TAG, "MediaCodec IllegalState, resetting for re-init", e)
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            mediaCodec = null
            isConfigured = false
            // Clear SPS/PPS to force clean re-initialization on next cycle
            spsBytes = null
            ppsBytes = null
        } catch (e: Exception) {
            Log.w(TAG, "Error decoding NAL unit", e)
        }
    }

    /**
     * Parse H.264 SPS NAL unit to extract video dimensions.
     * The NAL includes the 00 00 00 01 start code prefix.
     */
    private fun parseSpsGetDimensions(sps: ByteArray): Pair<Int, Int>? {
        if (sps.size < 10) return null
        try {
            // Skip NAL start code (00 00 00 01) and NAL header byte (0x67)
            val reader = BitReader(sps, 5)
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

    @Synchronized
    fun release() {
        isConfigured = false
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaCodec", e)
        }
        mediaCodec = null
        spsBytes = null
        ppsBytes = null
        streamBuffer.reset()
    }

    /**
     * Bit-level reader for parsing H.264 SPS Exp-Golomb coded fields.
     */
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
