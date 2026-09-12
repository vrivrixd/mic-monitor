package com.vrivrixd.micmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.IOException

/**
 * Foreground service that keeps the microphone capture and the page server alive.
 */
class StreamService : Service(), MicServer.Listener {

    private lateinit var prefs: Prefs
    private var server: MicServer? = null
    private var engine: AudioEngine? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var address: String? = null
    private var streamId = 0

    /** True when another app is recording and the system has silenced us. */
    private var micSilenced = false

    /** Everything that touches the service goes through here, to avoid races. */
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            else -> startStreaming()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseEverything()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // -------------------------------------------------------------------- start

    private fun startStreaming() {
        if (server != null) return

        val port = prefs.port
        address = NetUtils.addressFor(port)
        startForeground(NOTIFICATION_ID, buildNotification())

        val newServer = MicServer(assets, port, prefs.allowMultiple, this)
        try {
            newServer.start()
        } catch (e: IOException) {
            Log.e(TAG, "Could not open port " + port, e)
            StreamState.reset(getString(R.string.error_port_busy, port))
            stopSelf()
            return
        }
        server = newServer

        acquireLocks()

        if (!startEngine()) {
            releaseEverything()
            StreamState.reset(getString(R.string.error_mic_busy))
            stopSelf()
            return
        }

        micSilenced = false
        watchRecording()

        StreamState.update {
            it.copy(
                running = true,
                address = address,
                clientCount = 0,
                paused = false,
                error = null
            )
        }
    }

    /** Opens the capture with the current preferences. */
    private fun startEngine(): Boolean {
        engine?.stop()
        streamId++

        val created = AudioEngine(
            onPcm = { data, length -> server?.sendPcm(data, length) },
            onError = {
                StreamState.update { it.copy(error = getString(R.string.error_mic_busy)) }
            }
        )
        created.gainDb = prefs.gainDb
        created.swapChannels = ChannelMode.swaps(prefs.channelMode)
        if (!created.start(prefs.micSource, ChannelMode.wantsStereo(prefs.channelMode))) return false
        engine = created
        return true
    }

    // ------------------------------------------------ fight over the microphone

    /*
     * Android decides on its own who keeps the microphone when two apps record at
     * the same time, and whoever loses gets silence. The camera capture is marked as
     * reserved by default, which stopped the other app from recording at all. That
     * mark is turned off in AudioEngine, so now the newcomer wins.
     *
     * Here we only follow the outcome, to say on the screen and on the page that the
     * stream is silent because another app is recording. None of this involves media
     * playback.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private inner class SilenceWatcher : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            val session = engine?.sessionId ?: return
            val silenced = configs.any {
                it.clientAudioSessionId == session && it.isClientSilenced
            }
            main.post { setSilenced(silenced) }
        }
    }

    private val recordingCallback: AudioManager.AudioRecordingCallback? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) SilenceWatcher() else null

    private fun watchRecording() {
        val callback = recordingCallback ?: return
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        manager.registerAudioRecordingCallback(callback, main)
    }

    private fun unwatchRecording() {
        val callback = recordingCallback ?: return
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        manager.unregisterAudioRecordingCallback(callback)
    }

    private fun setSilenced(silenced: Boolean) {
        if (silenced == micSilenced || server == null) return
        micSilenced = silenced
        StreamState.update { it.copy(paused = silenced) }
        server?.sendConfig()
        updateNotification()
        Log.i(TAG, if (silenced) "Microphone handed to another app" else "Microphone back")
    }


    private fun acquireLocks() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MicMonitor::stream").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifi.createWifiLock(mode, "MicMonitor::wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseEverything() {
        micSilenced = false
        try {
            unwatchRecording()
        } catch (e: Throwable) {
            Log.w(TAG, "Could not stop watching the recordings", e)
        }
        try {
            engine?.stop()
        } catch (e: Throwable) {
            Log.w(TAG, "Could not end the capture", e)
        }
        engine = null
        try {
            server?.stop()
        } catch (e: Throwable) {
            Log.w(TAG, "Could not end the server", e)
        }
        server = null
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Throwable) {
            Log.w(TAG, "Could not release the wake lock", e)
        }
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (e: Throwable) {
            Log.w(TAG, "Could not release the Wi-Fi lock", e)
        }
        wakeLock = null
        wifiLock = null
    }

    private fun shutdown() {
        val wasRunning = server != null
        releaseEverything()
        if (wasRunning) StreamState.reset()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    // ------------------------------------------------------ preference changes

    /**
     * Applies the preferences again while the stream runs.
     *
     * Gain, side swapping and the permission for several connections take effect at
     * once. Changing the channel count, the microphone or the port redoes whatever
     * is needed.
     */
    fun reconfigure() {
        val current = server ?: return
        engine?.gainDb = prefs.gainDb
        engine?.swapChannels = ChannelMode.swaps(prefs.channelMode)
        current.allowMultiple = prefs.allowMultiple

        if (current.port != prefs.port) {
            // The port changed, so the address changes and the listeners have to
            // connect again.
            releaseEverything()
            startStreaming()
            return
        }

        val running = engine
        val wantStereo = ChannelMode.wantsStereo(prefs.channelMode)
        val needsRestart = running == null ||
            (wantStereo && running.channels != 2) ||
            (!wantStereo && running.channels != 1) ||
            sourceChanged

        if (needsRestart) {
            sourceChanged = false
            if (!startEngine()) {
                StreamState.update { it.copy(error = getString(R.string.error_mic_busy)) }
                return
            }
        }
        current.sendConfig()
        updateNotification()
    }

    private var sourceChanged = false

    fun markSourceChanged() {
        sourceChanged = true
    }

    // ------------------------------------------------- commands from the page

    override fun onCommand(command: JSONObject) {
        main.post { handleCommand(command) }
    }

    private fun handleCommand(command: JSONObject) {
        when (command.optString("type")) {
            "setGain" -> {
                prefs.gainPercent = command.optInt("gainPercent", Prefs.DEFAULT_GAIN_PERCENT)
                engine?.gainDb = prefs.gainDb
                StreamState.update { it.copy(configRevision = it.configRevision + 1) }
                server?.sendConfig()
            }
            "setBuffer" -> {
                val ms = command.optInt("bufferMs", Prefs.DEFAULT_BUFFER_MS)
                if (ms != prefs.bufferMs) {
                    prefs.bufferMs = ms
                    StreamState.update { it.copy(configRevision = it.configRevision + 1) }
                    server?.sendConfig()
                }
            }
            "setSource" -> {
                val key = command.optString("source", MicSource.DEFAULT)
                if (key in MicSource.ALL && key != prefs.micSource) {
                    prefs.micSource = key
                    markSourceChanged()
                    StreamState.update { it.copy(configRevision = it.configRevision + 1) }
                    reconfigure()
                }
            }
            "setChannels" -> {
                val mode = command.optString("mode", ChannelMode.MONO)
                if (mode in ChannelMode.ALL && mode != prefs.channelMode) {
                    prefs.channelMode = mode
                    StreamState.update { it.copy(configRevision = it.configRevision + 1) }
                    reconfigure()
                }
            }
        }
    }

    override fun onClientCountChanged(count: Int) {
        StreamState.update { it.copy(clientCount = count) }
    }

    override fun configJson(): JSONObject {
        val running = engine
        return JSONObject()
            .put("type", "config")
            .put("streamId", streamId)
            .put("sampleRate", running?.sampleRate ?: 48000)
            .put("channels", running?.channels ?: 1)
            .put("gainPercent", prefs.gainPercent)
            .put("bufferMs", prefs.bufferMs)
            .put("source", prefs.micSource)
            .put("channelMode", prefs.channelMode)
            .put("paused", micSilenced)
    }

    // ------------------------------------------------------------- notification

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.notif_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP), flags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            // The state and nothing else. The address belongs on the app screen, not
            // in the notification.
            .setContentText(
                if (micSilenced) getString(R.string.notif_paused)
                else getString(R.string.notif_text)
            )
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    private fun updateNotification() {
        address = NetUtils.addressFor(prefs.port)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
        StreamState.update { it.copy(address = address) }
    }

    companion object {
        private const val TAG = "StreamService"
        private const val CHANNEL_ID = "mic_monitor_stream"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_STOP = "com.vrivrixd.micmonitor.STOP"

        @Volatile
        private var instance: StreamService? = null

        fun start(context: Context) {
            val intent = Intent(context, StreamService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, StreamService::class.java).setAction(ACTION_STOP)
            )
        }

        /** Applies the preferences again if the service is alive. */
        fun applyPreferences(sourceChanged: Boolean) {
            val service = instance ?: return
            if (sourceChanged) service.markSourceChanged()
            service.reconfigure()
        }
    }
}
