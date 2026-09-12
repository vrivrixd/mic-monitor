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

    /**
     * Uma das constantes de [ChannelMode].
     *
     * A versao anterior guardava so uma marca de estereo ligado ou desligado. Quem
     * atualiza cai no modo equivalente ao que tinha escolhido antes.
     */
    var channelMode: String
        get() {
            val stored = sp.getString(KEY_CHANNELS, null)
            if (stored != null && stored in ChannelMode.ALL) return stored
            return if (sp.getBoolean(KEY_STEREO, false)) ChannelMode.STEREO else ChannelMode.MONO
        }
        set(value) {
            val safe = if (value in ChannelMode.ALL) value else ChannelMode.MONO
            sp.edit().putString(KEY_CHANNELS, safe).apply()
        }

    /** Verdadeiro quando mais de um computador pode ouvir ao mesmo tempo. */
    var allowMultiple: Boolean
        get() = sp.getBoolean(KEY_MULTIPLE, false)
        set(value) = sp.edit().putBoolean(KEY_MULTIPLE, value).apply()

    /**
     * Quanto som o computador guarda antes de tocar, em milissegundos.
     *
     * O valor e livre dentro da faixa util. Quem manda nesse tempo e o navegador,
     * porque o celular envia blocos de vinte milissegundos de qualquer maneira.
     */
    var bufferMs: Int
        get() = sp.getInt(KEY_BUFFER, DEFAULT_BUFFER_MS).coerceIn(MIN_BUFFER_MS, MAX_BUFFER_MS)
        set(value) =
            sp.edit().putInt(KEY_BUFFER, value.coerceIn(MIN_BUFFER_MS, MAX_BUFFER_MS)).apply()

    companion object {
        const val DEFAULT_PORT = 8080
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        const val DEFAULT_GAIN_PERCENT = 50
        const val DEFAULT_BUFFER_MS = 150
        const val MIN_BUFFER_MS = 30
        const val MAX_BUFFER_MS = 1000

        /** Faixa util de ganho, em decibeis para cada lado do meio. */
        private const val GAIN_RANGE_DB = 20f

        fun percentToDb(percent: Int): Float =
            (percent.coerceIn(0, 100) - 50) * (GAIN_RANGE_DB / 50f)

        private const val KEY_PORT = "port"
        private const val KEY_GAIN = "gain_percent"
        private const val KEY_SOURCE = "mic_source"
        private const val KEY_STEREO = "stereo"
        private const val KEY_CHANNELS = "channel_mode"
        private const val KEY_MULTIPLE = "allow_multiple"
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

/**
 * Como os canais saem na transmissao.
 *
 * O modo invertido troca o lado esquerdo com o direito, para quem quiser o som do
 * microfone de baixo em um lado e o do topo no outro, ao contrario do que o
 * aparelho entrega. Sem estereo de verdade no aparelho os dois modos de estereo
 * caem em um canal so, e inverter deixa de ter efeito.
 */
object ChannelMode {
    const val MONO = "mono"
    const val STEREO = "stereo"
    const val SWAPPED = "swapped"

    val ALL = listOf(MONO, STEREO, SWAPPED)

    fun labelRes(key: String): Int = when (key) {
        STEREO -> R.string.channel_stereo
        SWAPPED -> R.string.channel_swapped
        else -> R.string.channel_mono
    }

    fun wantsStereo(key: String): Boolean = key == STEREO || key == SWAPPED

    fun swaps(key: String): Boolean = key == SWAPPED
}
