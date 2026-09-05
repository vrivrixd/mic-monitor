package com.vrivrixd.micmonitor

import android.content.Context
import android.content.SharedPreferences

/** Leitura e gravacao das preferencias do aplicativo. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("mic_monitor", Context.MODE_PRIVATE)

    /** Texto cru digitado pelo usuario. Vazio significa usar a porta padrao. */
    var portText: String
        get() = sp.getString(KEY_PORT, "") ?: ""
        set(value) = sp.edit().putString(KEY_PORT, value.trim()).apply()

    /** Porta efetiva, ja resolvida para a padrao quando o campo esta vazio. */
    val port: Int
        get() = portText.toIntOrNull()?.takeIf { it in MIN_PORT..MAX_PORT } ?: DEFAULT_PORT

    /** Posicao do controle deslizante, de zero a cem. O meio nao mexe no volume. */
    var gainPercent: Int
        get() = sp.getInt(KEY_GAIN, DEFAULT_GAIN_PERCENT).coerceIn(0, 100)
        set(value) = sp.edit().putInt(KEY_GAIN, value.coerceIn(0, 100)).apply()

    /** O mesmo ganho convertido para decibeis, que e o que a captura usa. */
    val gainDb: Float
        get() = percentToDb(gainPercent)

    /** Uma das constantes de [MicSource]. */
    var micSource: String
        get() = sp.getString(KEY_SOURCE, MicSource.DEFAULT) ?: MicSource.DEFAULT
        set(value) = sp.edit().putString(KEY_SOURCE, value).apply()

    var stereo: Boolean
        get() = sp.getBoolean(KEY_STEREO, false)
        set(value) = sp.edit().putBoolean(KEY_STEREO, value).apply()

    /** Quanto som o computador guarda antes de tocar, em milissegundos. */
    var bufferMs: Int
        get() {
            val stored = sp.getInt(KEY_BUFFER, DEFAULT_BUFFER_MS)
            return if (BUFFER_OPTIONS.contains(stored)) stored else DEFAULT_BUFFER_MS
        }
        set(value) {
            val safe = if (BUFFER_OPTIONS.contains(value)) value else DEFAULT_BUFFER_MS
            sp.edit().putInt(KEY_BUFFER, safe).apply()
        }

    companion object {
        const val DEFAULT_PORT = 8080
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        const val DEFAULT_GAIN_PERCENT = 50
        const val DEFAULT_BUFFER_MS = 150

        /** Faixa util de ganho, em decibeis para cada lado do meio. */
        private const val GAIN_RANGE_DB = 20f

        val BUFFER_OPTIONS = listOf(60, 100, 150, 250, 400)

        fun percentToDb(percent: Int): Float =
            (percent.coerceIn(0, 100) - 50) * (GAIN_RANGE_DB / 50f)

        fun bufferLabelRes(ms: Int): Int = when (ms) {
            60 -> R.string.buffer_60
            100 -> R.string.buffer_100
            250 -> R.string.buffer_250
            400 -> R.string.buffer_400
            else -> R.string.buffer_150
        }

        private const val KEY_PORT = "port"
        private const val KEY_GAIN = "gain_percent"
        private const val KEY_SOURCE = "mic_source"
        private const val KEY_STEREO = "stereo"
        private const val KEY_BUFFER = "buffer_ms"
    }
}

/** Identificadores das fontes de audio oferecidas na interface. */
object MicSource {
    const val DEFAULT = "default"
    const val FRONT = "front"
    const val CAMERA = "camera"

    val ALL = listOf(DEFAULT, FRONT, CAMERA)

    fun labelRes(key: String): Int = when (key) {
        FRONT -> R.string.mic_front
        CAMERA -> R.string.mic_camera
        else -> R.string.mic_default
    }
}
