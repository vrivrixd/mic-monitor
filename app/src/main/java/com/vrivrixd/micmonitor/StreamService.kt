package com.vrivrixd.micmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.IOException

/**
 * Servico em primeiro plano que mantem a captura do microfone e o servidor da pagina.
 */
class StreamService : Service(), MicServer.Listener {

    private lateinit var prefs: Prefs
    private var server: MicServer? = null
    private var engine: AudioEngine? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var address: String? = null
    private var streamId = 0
    private var stereoKnown = false

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
        instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ inicio

    private fun startStreaming() {
        if (server != null) return

        val port = prefs.port
        address = NetUtils.addressFor(port)
        startForeground(NOTIFICATION_ID, buildNotification(address))

        val newServer = MicServer(assets, port, this)
        try {
            newServer.start()
        } catch (e: IOException) {
            Log.e(TAG, "Nao foi possivel abrir a porta " + port, e)
            StreamState.reset(getString(R.string.error_port_busy, port))
            stopSelf()
            return
        }
        server = newServer

        if (!startEngine()) {
            newServer.stop()
            server = null
            StreamState.reset(getString(R.string.error_mic_busy))
            stopSelf()
            return
        }

        acquireLocks()
        StreamState.update {
            it.copy(
                running = true,
                address = address,
                clientConnected = false,
                stereoRequested = prefs.stereo,
                stereoReal = false,
                stereoVerified = false,
                error = null
            )
        }
    }

    /** Abre a captura com as preferencias atuais. */
    private fun startEngine(): Boolean {
        engine?.stop()
        stereoKnown = false
        streamId++

        val created = AudioEngine(
            onPcm = { data, length -> server?.sendPcm(data, length) },
            onStereoVerified = { real ->
                stereoKnown = true
                StreamState.update { it.copy(stereoReal = real, stereoVerified = true) }
                server?.sendConfig()
            },
            onError = {
                StreamState.update { it.copy(error = getString(R.string.error_mic_busy)) }
            }
        )
        created.gainDb = prefs.gainDb
        if (!created.start(prefs.micSource, prefs.stereo)) return false
        engine = created
        return true
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
        engine?.stop()
        engine = null
        server?.stop()
        server = null
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: RuntimeException) {
        }
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (_: RuntimeException) {
        }
        wakeLock = null
        wifiLock = null
    }

    private fun shutdown() {
        releaseEverything()
        StreamState.reset()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    // -------------------------------------------------- mudancas de preferencia

    /**
     * Reaplica as preferencias enquanto a transmissao roda.
     * O ganho vale na hora. Trocar microfone, estereo ou porta refaz o que for preciso.
     */
    fun reconfigure() {
        val current = server ?: return
        engine?.gainDb = prefs.gainDb

        if (current.port != prefs.port) {
            // A porta mudou, entao o endereco muda e o ouvinte precisa reconectar.
            releaseEverything()
            startStreaming()
            return
        }

        val running = engine
        val needsRestart = running == null ||
            (prefs.stereo && running.channels != 2) ||
            (!prefs.stereo && running.channels != 1) ||
            sourceChanged

        if (needsRestart) {
            sourceChanged = false
            if (!startEngine()) {
                StreamState.update { it.copy(error = getString(R.string.error_mic_busy)) }
                return
            }
            StreamState.update {
                it.copy(stereoRequested = prefs.stereo, stereoReal = false, stereoVerified = false)
            }
        }
        current.sendConfig()
        updateNotification()
    }

    private var sourceChanged = false

    fun markSourceChanged() {
        sourceChanged = true
    }

    // ----------------------------------------------------- comandos da pagina

    override fun onCommand(command: JSONObject) {
        when (command.optString("type")) {
            "setGain" -> {
                val db = command.optDouble("gainDb", 0.0).toFloat()
                prefs.gainDb = db
                engine?.gainDb = prefs.gainDb
                StreamState.update { it.copy(configRevision = it.configRevision + 1) }
                server?.sendConfig()
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
            "setStereo" -> {
                val want = command.optBoolean("stereo", false)
                if (want != prefs.stereo) {
                    prefs.stereo = want
                    StreamState.update { it.copy(configRevision = it.configRevision + 1) }
                    reconfigure()
                }
            }
            "shutdown" -> shutdown()
        }
    }

    override fun onClientChanged(connected: Boolean) {
        StreamState.update { it.copy(clientConnected = connected) }
    }

    override fun configJson(): JSONObject {
        val running = engine
        return JSONObject()
            .put("type", "config")
            .put("streamId", streamId)
            .put("sampleRate", running?.sampleRate ?: 48000)
            .put("channels", running?.channels ?: 1)
            .put("gainDb", prefs.gainDb.toDouble())
            .put("minGainDb", Prefs.MIN_GAIN_DB.toDouble())
            .put("maxGainDb", Prefs.MAX_GAIN_DB.toDouble())
            .put("source", prefs.micSource)
            .put("stereo", prefs.stereo)
            .put("stereoKnown", stereoKnown)
            .put("stereoReal", running?.stereoReal ?: false)
    }

    // ------------------------------------------------------------- notificacao

    private fun buildNotification(address: String?): Notification {
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
            .setContentText(
                if (address != null) getString(R.string.notif_text, address)
                else getString(R.string.address_unavailable)
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
        manager.notify(NOTIFICATION_ID, buildNotification(address))
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

        /** Reaplica as preferencias se o servico estiver ativo. */
        fun applyPreferences(sourceChanged: Boolean) {
            val service = instance ?: return
            if (sourceChanged) service.markSourceChanged()
            service.reconfigure()
        }
    }
}
