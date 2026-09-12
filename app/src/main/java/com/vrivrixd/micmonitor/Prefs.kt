package com.vrivrixd.micmonitor

import android.content.Context
import android.content.SharedPreferences

/** Reads and writes the app preferences. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("mic_monitor", Context.MODE_PRIVATE)

    /** Raw text typed by the user. Empty means use the default port. */
    var portText: String
        get() = sp.getString(KEY_PORT, "") ?: ""
        set(value) = sp.edit().putString(KEY_PORT, value.trim()).apply()

    /** The port in use, already resolved to the default when the field is empty. */
    val port: Int
        get() = portText.toIntOrNull()?.takeIf { it in MIN_PORT..MAX_PORT } ?: DEFAULT_PORT

    /** Slider position, from zero to one hundred. The middle leaves the volume alone. */
    var gainPercent: Int
        get() = sp.getInt(KEY_GAIN, DEFAULT_GAIN_PERCENT).coerceIn(0, 100)
        set(value) = sp.edit().putInt(KEY_GAIN, value.coerceIn(0, 100)).apply()

    /** The same gain in decibels, which is what the capture works with. */
    val gainDb: Float
        get() = percentToDb(gainPercent)

    /** One of the [MicSource] constants. */
    var micSource: String
        get() = sp.getString(KEY_SOURCE, MicSource.DEFAULT) ?: MicSource.DEFAULT
        set(value) = sp.edit().putString(KEY_SOURCE, value).apply()

    /**
     * One of the [ChannelMode] constants.
     *
     * The previous version stored only a stereo flag, on or off. Anyone updating
     * lands on the mode matching what they had chosen before.
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

    /** True when more than one computer may listen at the same time. */
    var allowMultiple: Boolean
        get() = sp.getBoolean(KEY_MULTIPLE, false)
        set(value) = sp.edit().putBoolean(KEY_MULTIPLE, value).apply()

    /**
     * How much sound the computer holds before playing it, in milliseconds.
     *
     * The value is free inside the useful range. What this time really controls is
     * the browser, because the phone sends twenty millisecond blocks either way.
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

        /** Useful gain range, in decibels on each side of the middle. */
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

/** Identifiers of the audio sources offered in the interface. */
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
 * How the channels leave in the stream.
 *
 * The swapped mode trades the left side for the right one, for anyone who wants the
 * bottom microphone on one side and the top one on the other, the opposite of what
 * the device delivers. Without real stereo on the device both stereo modes fall back
 * to a single channel, and swapping stops having any effect.
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
