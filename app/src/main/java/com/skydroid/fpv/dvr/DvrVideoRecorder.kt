package com.skydroid.fpv.dvr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

/**
 * High-performance DVR Recorder for FPV flight videos.
 *
 * Supports two recording modes:
 * 1. DIRECT H.264 BITSTREAM RECORDING (Skydroid T12):
 *    Directly multiplexes the native H.264 NAL units into MP4 container with zero
 *    re-encoding, zero CPU/GPU overhead, and pristine camera quality.
 *
 * 2. HARDWARE ENCODER RECORDING (Analog UVC / Simulator):
 *    Encodes incoming Bitmaps to H.264 via MediaCodec hardware surface encoder.
 *
 * Files are recorded to a local temp file and published atomically to MediaStore
 * (Movies/SkyFPV) upon completion to guarantee 100% reliability across Android versions.
 */
class DvrVideoRecorder(
    private val context: Context,
    private val width: Int = 640,
    private val height: Int = 360,
    private val frameRate: Int = 30,
    private val bitRate: Int = 4_000_000,
    private val enableAudio: Boolean = false
) {
    private val TAG = "DvrVideoRecorder"

    @Volatile
    var isRecording: Boolean = false
        private set

    // Recording mode
    private var isDirectH264: Boolean = false
    private var tempFile: File? = null
    private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var isMuxerStarted = false

    // Direct H.264 state
    private var cachedSps: ByteArray? = null
    private var cachedPps: ByteArray? = null
    private var currentWidth = width
    private var currentHeight = height
    private var hasWrittenKeyframe = false
    private var recordingStartNs = 0L
    private var lastPtsUs = 0L
    private var framesWritten = 0

    // Bitmap encoder state (for UVC/Simulator)
    private var videoEncoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var audioCaptureEngine: AudioCaptureEngine? = null

    private var startTimeNs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var durationCallback: ((Long) -> Unit)? = null

    private val tickerRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsedMs = (System.nanoTime() - startTimeNs) / 1_000_000
                durationCallback?.invoke(elapsedMs)
                mainHandler.postDelayed(this, 500)
            }
        }
    }

    /**
     * Start Direct H.264 Bitstream DVR recording (for Skydroid T12).
     */
    @Synchronized
    fun startDirectH264(
        sps: ByteArray?,
        pps: ByteArray?,
        videoWidth: Int,
        videoHeight: Int,
        onDurationUpdate: (Long) -> Unit
    ): Boolean {
        if (isRecording) return true
        this.durationCallback = onDurationUpdate
        this.isDirectH264 = true
        this.cachedSps = sps
        this.cachedPps = pps
        this.currentWidth = if (videoWidth > 0) videoWidth else width
        this.currentHeight = if (videoHeight > 0) videoHeight else height
        this.hasWrittenKeyframe = false
        this.recordingStartNs = 0L
        this.lastPtsUs = 0L
        this.framesWritten = 0

        try {
            val file = File(context.cacheDir, "flight_dvr_${System.currentTimeMillis()}.mp4")
            this.tempFile = file
            mediaMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // If SPS and PPS are already cached from video stream, add track immediately
            if (sps != null && pps != null) {
                initDirectH264Track(sps, pps, currentWidth, currentHeight)
            }

            isRecording = true
            startTimeNs = System.nanoTime()
            mainHandler.post(tickerRunnable)
            Log.d(TAG, "Direct H.264 DVR recording started (${currentWidth}x${currentHeight})")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start direct H.264 DVR recording", e)
            stop()
            return false
        }
    }

    private fun initDirectH264Track(sps: ByteArray, pps: ByteArray, w: Int, h: Int) {
        val muxer = mediaMuxer ?: return
        if (isMuxerStarted) return

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            }
            videoTrackIndex = muxer.addTrack(format)
            muxer.start()
            isMuxerStarted = true
            Log.i(TAG, "MediaMuxer started with direct H.264 track ($videoTrackIndex, ${w}x${h})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaMuxer with direct H.264 track", e)
        }
    }

    /**
     * Feed raw H.264 NAL unit into DVR multiplexer.
     */
    @Synchronized
    fun feedH264Nal(nal: ByteArray, isKeyFrame: Boolean) {
        if (!isRecording || !isDirectH264) return
        if (nal.size < 5) return

        val nalType = nal[4].toInt() and 0x1F

        // Capture SPS/PPS if not yet configured
        if (nalType == 7) {
            cachedSps = nal.clone()
            if (!isMuxerStarted && cachedPps != null) {
                initDirectH264Track(cachedSps!!, cachedPps!!, currentWidth, currentHeight)
            }
            return
        }
        if (nalType == 8) {
            cachedPps = nal.clone()
            if (!isMuxerStarted && cachedSps != null) {
                initDirectH264Track(cachedSps!!, cachedPps!!, currentWidth, currentHeight)
            }
            return
        }

        // Only write video slices (type 5: IDR, type 1: non-IDR)
        if (nalType != 5 && nalType != 1) {
            return
        }

        if (!isMuxerStarted) return
        val muxer = mediaMuxer ?: return

        // Must start MP4 stream with an IDR keyframe
        if (!hasWrittenKeyframe) {
            if (nalType != 5 && !isKeyFrame) {
                return // Wait for next keyframe
            }
            hasWrittenKeyframe = true
            recordingStartNs = System.nanoTime()
            Log.i(TAG, "First DVR keyframe received. Writing video samples to MP4...")
        }

        val nowNs = System.nanoTime()
        var ptsUs = (nowNs - recordingStartNs) / 1000L
        if (ptsUs <= lastPtsUs) {
            ptsUs = lastPtsUs + 1000L
        }
        lastPtsUs = ptsUs

        val buffer = ByteBuffer.wrap(nal)
        val info = MediaCodec.BufferInfo().apply {
            set(0, nal.size, ptsUs, if (nalType == 5 || isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }

        try {
            muxer.writeSampleData(videoTrackIndex, buffer, info)
            framesWritten++
        } catch (e: Exception) {
            Log.w(TAG, "Error writing H.264 sample to muxer: ${e.message}")
        }
    }

    /**
     * Start Bitmap hardware encoding DVR recording (for UVC/Simulator).
     */
    @Synchronized
    fun start(onDurationUpdate: (Long) -> Unit): Boolean {
        if (isRecording) return true
        this.durationCallback = onDurationUpdate
        this.isDirectH264 = false
        this.framesWritten = 0

        try {
            val file = File(context.cacheDir, "flight_dvr_${System.currentTimeMillis()}.mp4")
            this.tempFile = file
            mediaMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Configure H.264 Video Encoder
            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_LATENCY, 0)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }

            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }

            // Optional Audio Capture
            if (enableAudio) {
                audioCaptureEngine = AudioCaptureEngine(
                    onAudioFormatAvailable = { audioFormat ->
                        synchronized(this@DvrVideoRecorder) {
                            if (!isMuxerStarted && mediaMuxer != null) {
                                audioTrackIndex = mediaMuxer!!.addTrack(audioFormat)
                                checkStartMuxer()
                            }
                        }
                    },
                    onAudioSampleAvailable = { buffer, bufferInfo ->
                        writeAudioSample(buffer, bufferInfo)
                    }
                )
                audioCaptureEngine?.start()
            }

            isRecording = true
            startTimeNs = System.nanoTime()
            mainHandler.post(tickerRunnable)

            Thread({ drainEncoderLoop() }, "SkyFPV-DvrDrainer").start()
            Log.d(TAG, "Bitmap DVR recording started ($width x $height @ $frameRate fps)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start bitmap DVR recording", e)
            stop()
            return false
        }
    }

    /**
     * Submit an incoming video frame to the hardware encoder (UVC/Simulator).
     */
    fun encodeFrame(bitmap: Bitmap) {
        if (!isRecording || isDirectH264 || inputSurface == null) return

        try {
            val canvas: Canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                inputSurface!!.lockHardwareCanvas()
            } else {
                inputSurface!!.lockCanvas(null)
            }

            canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, width, height), null)
            inputSurface!!.unlockCanvasAndPost(canvas)
        } catch (e: Exception) {
            Log.w(TAG, "Error rendering frame to encoder surface: ${e.message}")
        }
    }

    private fun checkStartMuxer() {
        val muxer = mediaMuxer ?: return
        if (isMuxerStarted) return

        if (videoTrackIndex >= 0 && (!enableAudio || audioTrackIndex >= 0)) {
            muxer.start()
            isMuxerStarted = true
            Log.d(TAG, "MediaMuxer started with videoTrack=$videoTrackIndex, audioTrack=$audioTrackIndex")
        }
    }

    private fun writeAudioSample(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        synchronized(this) {
            if (isMuxerStarted && audioTrackIndex >= 0 && mediaMuxer != null) {
                try {
                    mediaMuxer?.writeSampleData(audioTrackIndex, buffer, bufferInfo)
                } catch (e: Exception) {
                    Log.w(TAG, "Error writing audio sample to muxer", e)
                }
            }
        }
    }

    private fun drainEncoderLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        val encoder = videoEncoder ?: return

        while (isRecording && !isDirectH264) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(this) {
                    if (!isMuxerStarted && mediaMuxer != null) {
                        videoTrackIndex = mediaMuxer!!.addTrack(encoder.outputFormat)
                        checkStartMuxer()
                    }
                }
            } else if (outputIndex >= 0) {
                val outputBuffer = encoder.getOutputBuffer(outputIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    synchronized(this) {
                        if (isMuxerStarted && videoTrackIndex >= 0 && mediaMuxer != null) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                mediaMuxer?.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                                framesWritten++
                            }
                        }
                    }
                }
                encoder.releaseOutputBuffer(outputIndex, false)
            }
        }
    }

    /**
     * Stop DVR recording and publish the MP4 file to MediaStore.
     * Returns true if a valid recording was saved.
     */
    @Synchronized
    fun stop(): Boolean {
        if (!isRecording) return false
        isRecording = false
        mainHandler.removeCallbacks(tickerRunnable)

        try {
            audioCaptureEngine?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio capture", e)
        }
        audioCaptureEngine = null

        if (!isDirectH264) {
            try {
                videoEncoder?.signalEndOfInputStream()
            } catch (_: Exception) {}

            try {
                videoEncoder?.stop()
                videoEncoder?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping video encoder", e)
            }
            videoEncoder = null

            inputSurface?.release()
            inputSurface = null
        }

        var savedSuccessfully = false
        if (isMuxerStarted && framesWritten > 0) {
            try {
                mediaMuxer?.stop()
                Log.i(TAG, "MediaMuxer stopped successfully with $framesWritten frames")
                savedSuccessfully = true
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping MediaMuxer", e)
            }
        } else {
            Log.w(TAG, "Muxer was not started or 0 frames written (started=$isMuxerStarted, frames=$framesWritten)")
        }

        try {
            mediaMuxer?.release()
        } catch (_: Exception) {}
        mediaMuxer = null
        isMuxerStarted = false

        // Publish to MediaStore Scoped Storage
        val file = tempFile
        var published = false
        if (savedSuccessfully && file != null && file.exists() && file.length() > 0) {
            val uri = MediaStoreHelper.saveVideoFile(context, file)
            if (uri != null) {
                Log.i(TAG, "Published flight recording to MediaStore: $uri (${file.length()} bytes)")
                published = true
            }
        }
        file?.delete()
        tempFile = null

        Log.d(TAG, "DVR Video recording ended. Success: $published")
        return published
    }
}
