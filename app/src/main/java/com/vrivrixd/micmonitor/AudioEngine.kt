package com.vrivrixd.micmonitor

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlin.math.pow

/**
 * Captura do microfone em PCM de 16 bits e entrega dos blocos ja com ganho aplicado.
 *
 * A abertura tenta varias combinacoes de fonte, taxa e numero de canais, porque o
 * suporte a estereo varia muito entre aparelhos.
 */
class AudioEngine(
    private val onPcm: (ByteArray, Int) -> Unit,
    private val onError: (String) -> Unit
) {

    /** Ganho em decibeis, alterado a qualquer momento pelo aplicativo ou pela pagina. */
    @Volatile
    var gainDb: Float = 0f

    /**
     * Troca o lado esquerdo com o direito.
     *
     * Vale na hora, sem reabrir o microfone, porque a troca acontece no mesmo laco
     * que ja empacota cada bloco. Em mono nao faz nada.
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

    /** Identificador da sessao, usado para saber se o sistema nos deu silencio. */
    var sessionId = 0
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
            } catch (e: Throwable) {
                Log.w(TAG, "Falha ao parar a captura", e)
            }
            try {
                it.release()
            } catch (e: Throwable) {
                Log.w(TAG, "Falha ao liberar a captura", e)
            }
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
                    build(source, rate, mask, bufferSize)
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao abrir fonte $source em $rate Hz", e)
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
     * Abre a captura sem reservar o microfone para nos.
     *
     * A fonte de camera, que e a que mais entrega estereo de verdade, vem marcada
     * como reservada por padrao. Essa marca impede que qualquer outro aplicativo
     * grave ao mesmo tempo, e era por isso que um audio gravado no mensageiro saia
     * mudo enquanto a transmissao estava ligada. Sem ela o Android volta a decidir
     * sozinho, e quem comeca a gravar depois recebe o som.
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

    /** Aplica o ganho com limite e escreve em little endian. */
    private fun applyGainAndPack(src: ShortArray, count: Int, dst: ByteArray) {
        val factor = 10.0.pow(gainDb / 20.0).toFloat()
        // A troca de lados le a amostra do canal vizinho, de dois em dois.
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
