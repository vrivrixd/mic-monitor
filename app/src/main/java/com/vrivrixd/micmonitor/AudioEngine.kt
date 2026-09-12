package com.vrivrixd.micmonitor

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlin.math.pow

/**
 * Captures the microphone as 16-bit PCM and hands out blocks with the gain applied.
 *
 * Opening tries several combinations of source, rate and channel count, because
 * stereo support varies a lot between devices.
 */
class AudioEngine(
    private val onPcm: (ByteArray, Int) -> Unit,
    private val onError: (String) -> Unit
) {

    /** Gain in decibels, changed at any time by the app or by the page. */
    @Volatile
    var gainDb: Float = 0f

    /**
     * Trades the left side for the right one.
     *
     * It takes effect at once, without reopening the microphone, because the swap
     * happens in the same loop that already packs every block. In mono it does
     * nothing.
     */
    @Volatile
    var swapChannels: Boolean = false

    @Volatile
    private var running = false

    private var thread: Thread? = null
    private var record: AudioRecord? = null

    var sampleRate = 48000
        private set

    var channels = 1
        private set

    /** Session identifier, used to tell whether the system has silenced us. */
    var sessionId = 0
        private set

    fun isRunning(): Boolean = running

    /**
     * Opens the microphone and starts producing blocks.
     * Returns false when no combination worked.
     */
    @SuppressLint("MissingPermission")
    fun start(sourceKey: String, wantStereo: Boolean): Boolean {
        stop()

        val opened = openRecord(sourceKey, wantStereo) ?: run {
            onError("mic")
            return false
        }
        record = opened

        val frameBytes = channels * 2
        // Twenty millisecond blocks keep the latency low without flooding the network.
        val chunkFrames = sampleRate / 50
        val shorts = ShortArray(chunkFrames * channels)
        val bytes = ByteArray(shorts.size * 2)

        running = true

        thread = Thread({
            try {
                opened.startRecording()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "startRecording failed", e)
                running = false
                onError("mic")
                return@Thread
            }

            while (running) {
                val read = opened.read(shorts, 0, shorts.size)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) break
                    continue
                }

                applyGainAndPack(shorts, read, bytes)
                onPcm(bytes, read * 2)
            }
        }, "MicMonitor-Audio").also { it.start() }

        Log.i(TAG, "Capture started at $sampleRate Hz, $channels channel(s), $frameBytes byte frames")
        return true
    }

    fun stop() {
        running = false
        thread?.let {
            it.interrupt()
            try {
                it.join(500)
            } catch (_: InterruptedException) {
            }
        }
        thread = null
        record?.let {
            try {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
            } catch (e: Throwable) {
                Log.w(TAG, "Could not stop the capture", e)
            }
            try {
                it.release()
            } catch (e: Throwable) {
                Log.w(TAG, "Could not release the capture", e)
            }
        }
        record = null
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(sourceKey: String, wantStereo: Boolean): AudioRecord? {
        val attempts = mutableListOf<Pair<Int, Int>>()
        if (wantStereo) {
            // In stereo the microphone choice is ignored. The camera source is the
            // one that most often delivers two real channels.
            for (src in STEREO_SOURCES) attempts += src to 2
        }
        attempts += androidSource(sourceKey) to 1
        attempts += MediaRecorder.AudioSource.MIC to 1

        for ((source, ch) in attempts) {
            for (rate in SAMPLE_RATES) {
                val mask = if (ch == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
                val min = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) continue
                val bufferSize = maxOf(min, rate / 50 * ch * 2 * 4)
                val candidate = try {
                    build(source, rate, mask, bufferSize)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not open source $source at $rate Hz", e)
                    null
                }
                if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                    sampleRate = rate
                    channels = ch
                    sessionId = candidate.audioSessionId
                    return candidate
                }
                candidate?.release()
            }
        }
        return null
    }

    /**
     * Opens the capture without reserving the microphone for us.
     *
     * The camera source, the one that most often delivers real stereo, is marked as
     * reserved by default. That mark stops any other app from recording at the same
     * time, and it was why a voice message recorded in a messenger came out silent
     * while the stream was running. Without it Android decides on its own again, and
     * whoever starts recording later gets the sound.
     */
    @SuppressLint("MissingPermission")
    private fun build(source: Int, rate: Int, mask: Int, bufferSize: Int): AudioRecord {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(mask)
            .build()
        val builder = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setPrivacySensitive(false)
        }
        return builder.build()
    }

    private fun androidSource(key: String): Int = when (key) {
        MicSource.FRONT -> MediaRecorder.AudioSource.MIC
        MicSource.CAMERA -> MediaRecorder.AudioSource.CAMCORDER
        else -> MediaRecorder.AudioSource.DEFAULT
    }

    /** Applies the gain with a ceiling and writes it out in little endian. */
    private fun applyGainAndPack(src: ShortArray, count: Int, dst: ByteArray) {
        val factor = 10.0.pow(gainDb / 20.0).toFloat()
        // Swapping sides reads the sample from the neighbouring channel, two by two.
        val swap = swapChannels && channels == 2 && count % 2 == 0
        var j = 0
        for (i in 0 until count) {
            val from = if (swap) (if (i % 2 == 0) i + 1 else i - 1) else i
            var v = if (factor == 1f) src[from].toInt() else (src[from] * factor).toInt()
            if (v > 32767) v = 32767 else if (v < -32768) v = -32768
            dst[j++] = (v and 0xFF).toByte()
            dst[j++] = ((v shr 8) and 0xFF).toByte()
        }
    }

    companion object {
        private const val TAG = "AudioEngine"
        private val SAMPLE_RATES = intArrayOf(48000, 44100)
        private val STEREO_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT
        )
    }
}
