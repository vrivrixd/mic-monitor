package com.vrivrixd.micmonitor

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.abs
import kotlin.math.pow

/**
 * Captura do microfone em PCM de 16 bits e entrega dos blocos ja com ganho aplicado.
 *
 * A abertura tenta varias combinacoes de fonte, taxa e numero de canais, porque o
 * suporte a estereo varia muito entre aparelhos.
 */
class AudioEngine(
    private val onPcm: (ByteArray, Int) -> Unit,
    private val onStereoVerified: (Boolean) -> Unit,
    private val onError: (String) -> Unit
) {

    /** Ganho em decibeis, alterado a qualquer momento pelo aplicativo ou pela pagina. */
    @Volatile
    var gainDb: Float = 0f

    @Volatile
    private var running = false

    private var thread: Thread? = null
    private var record: AudioRecord? = null

    var sampleRate = 48000
        private set

    var channels = 1
        private set

    /** Falso quando o aparelho abriu em estereo mas duplicou o mesmo sinal nos dois canais. */
    @Volatile
    var stereoReal = false
        private set

    fun isRunning(): Boolean = running

    /**
     * Abre o microfone e comeca a produzir blocos.
     * Retorna falso quando nenhuma combinacao funcionou.
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
        // Blocos de vinte milissegundos mantem a latencia baixa sem inundar a rede.
        val chunkFrames = sampleRate / 50
        val shorts = ShortArray(chunkFrames * channels)
        val bytes = ByteArray(shorts.size * 2)

        running = true
        stereoReal = false
        var verifyFramesLeft = if (channels == 2) sampleRate * 2 else 0
        var sawDifference = false

        thread = Thread({
            try {
                opened.startRecording()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "startRecording falhou", e)
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

                if (verifyFramesLeft > 0) {
                    if (!sawDifference && channelsDiffer(shorts, read)) sawDifference = true
                    verifyFramesLeft -= read / channels
                    if (verifyFramesLeft <= 0) {
                        stereoReal = sawDifference
                        onStereoVerified(sawDifference)
                    }
                }

                applyGainAndPack(shorts, read, bytes)
                onPcm(bytes, read * 2)
            }
        }, "MicMonitor-Audio").also { it.start() }

        Log.i(TAG, "Captura iniciada em $sampleRate Hz, $channels canal(is), bloco de $frameBytes bytes")
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
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        record = null
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(sourceKey: String, wantStereo: Boolean): AudioRecord? {
        val attempts = mutableListOf<Pair<Int, Int>>()
        if (wantStereo) {
            // Em estereo a escolha de microfone e ignorada. A fonte de camera e a
            // que mais costuma entregar dois canais de verdade.
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
                    AudioRecord(source, rate, mask, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao abrir fonte $source em $rate Hz", e)
                    null
                }
                if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                    sampleRate = rate
                    channels = ch
                    return candidate
                }
                candidate?.release()
            }
        }
        return null
    }

    private fun androidSource(key: String): Int = when (key) {
        MicSource.FRONT -> MediaRecorder.AudioSource.MIC
        MicSource.CAMERA -> MediaRecorder.AudioSource.CAMCORDER
        else -> MediaRecorder.AudioSource.DEFAULT
    }

    /** Procura qualquer diferenca entre os canais esquerdo e direito. */
    private fun channelsDiffer(buf: ShortArray, count: Int): Boolean {
        var i = 0
        while (i + 1 < count) {
            if (abs(buf[i].toInt() - buf[i + 1].toInt()) > 8) return true
            i += 2
        }
        return false
    }

    /** Aplica o ganho com limite e escreve em little endian. */
    private fun applyGainAndPack(src: ShortArray, count: Int, dst: ByteArray) {
        val factor = 10.0.pow(gainDb / 20.0).toFloat()
        var j = 0
        if (factor == 1f) {
            for (i in 0 until count) {
                val v = src[i].toInt()
                dst[j++] = (v and 0xFF).toByte()
                dst[j++] = ((v shr 8) and 0xFF).toByte()
            }
            return
        }
        for (i in 0 until count) {
            var v = (src[i] * factor).toInt()
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
