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

    /**
     * Ganho em decibeis, sempre em passos inteiros.
     * O centro do controle deslizante equivale a zero.
     */
    var gainDb: Float
        get() = clampGain(sp.getFloat(KEY_GAIN, 0f))
        set(value) = sp.edit().putFloat(KEY_GAIN, clampGain(value)).apply()

    /** Uma das constantes de [MicSource]. */
    var micSource: String
        get() = sp.getString(KEY_SOURCE, MicSource.DEFAULT) ?: MicSource.DEFAULT
        set(value) = sp.edit().putString(KEY_SOURCE, value).apply()

    var stereo: Boolean
        get() = sp.getBoolean(KEY_STEREO, false)
        set(value) = sp.edit().putBoolean(KEY_STEREO, value).apply()

    companion object {
        const val DEFAULT_PORT = 8080
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        const val MIN_GAIN_DB = -20f
        const val MAX_GAIN_DB = 20f

        fun clampGain(value: Float): Float =
            Math.round(value.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)).toFloat()

        private const val KEY_PORT = "port"
        private const val KEY_GAIN = "gain_db"
        private const val KEY_SOURCE = "mic_source"
        private const val KEY_STEREO = "stereo"
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
