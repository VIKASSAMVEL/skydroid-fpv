package com.skydroid.fpv.dvr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer

/**
 * Captures microphone audio, encodes it to AAC using MediaCodec,
 * and passes the encoded samples to the MediaMuxer.
 */
class AudioCaptureEngine(
    private val onAudioFormatAvailable: (MediaFormat) -> Unit,
    private val onAudioSampleAvailable: (ByteBuffer, MediaCodec.BufferInfo) -> Unit
) {
    private val TAG = "AudioCaptureEngine"
    private val SAMPLE_RATE = 44100
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BIT_RATE = 64000

    private var audioRecord: AudioRecord? = null
    private var audioEncoder: MediaCodec? = null
    @Volatile
    private var isRecording = false
    private var recordingThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid audio buffer size")
            return false
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return false
            }

            // Setup AAC MediaCodec encoder
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            isRecording = true
            audioRecord?.startRecording()

            recordingThread = Thread({ recordLoop() }, "SkyFPV-AudioRecorder")
            recordingThread?.start()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio engine", e)
            stop()
            return false
        }
    }

    private fun recordLoop() {
        val buffer = ByteArray(2048)
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRecording) {
            val record = audioRecord ?: break
            val readBytes = record.read(buffer, 0, buffer.size)
            if (readBytes > 0) {
                val encoder = audioEncoder ?: break
                val inputIndex = encoder.dequeueInputBuffer(10000)
                if (inputIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inputIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(buffer, 0, readBytes)
                    val pts = System.nanoTime() / 1000
                    encoder.queueInputBuffer(inputIndex, 0, readBytes, pts, 0)
                }

                // Drain output from encoder
                var outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                while (outputIndex >= 0) {
                    val outputBuffer = encoder.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        onAudioSampleAvailable(outputBuffer, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                    outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                }
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    onAudioFormatAvailable(encoder.outputFormat)
                }
            }
        }
    }

    fun stop() {
        isRecording = false
        try {
            recordingThread?.join(500)
        } catch (_: InterruptedException) {}
        recordingThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null

        try {
            audioEncoder?.stop()
            audioEncoder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio encoder", e)
        }
        audioEncoder = null
    }
}
