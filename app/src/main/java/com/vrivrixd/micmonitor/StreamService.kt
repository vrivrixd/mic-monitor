package com.vrivrixd.micmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

    /** Verdadeiro quando outro aplicativo pediu o microfone e nos soltamos ele. */
    private var pausedByFocus = false
    private var focusRequest: AudioFocusRequest? = null
    private var holdsFocus = false

    /** Tudo que mexe no servico passa por aqui, para nao concorrer entre threads. */
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

        acquireLocks()

        // O foco vem antes da captura. Se outro aplicativo ja estiver com o
        // microfone, comecamos pausados em vez de tomar o lugar dele.
        pausedByFocus = !requestFocus()

        if (!pausedByFocus && !startEngine()) {
            releaseEverything()
            StreamState.reset(getString(R.string.error_mic_busy))
            stopSelf()
            return
        }

        StreamState.update {
            it.copy(
                running = true,
                address = address,
                clientConnected = false,
                paused = pausedByFocus,
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

    // -------------------------------------------------------------- foco de audio

    /*
     * Quando outro aplicativo do celular vai gravar, uma chamada ou um audio de
     * mensageiro, ele pede o foco de audio ao sistema. Nos soltamos o microfone de
     * verdade nesse momento, e voltamos assim que o foco retorna. Sem isso o outro
     * aplicativo grava mudo, porque o microfone continua preso aqui.
     */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> main.post { resumeAfterFocus() }
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> main.post { pauseForFocus() }
        }
    }

    private fun requestFocus(): Boolean {
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusListener, main)
                // Abaixar o volume nao adianta para quem grava, entao queremos o aviso.
                .setWillPauseWhenDucked(true)
                .build()
            focusRequest = request
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
        holdsFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return holdsFocus
    }

    private fun abandonFocus() {
        if (!holdsFocus) return
        holdsFocus = false
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(focusListener)
        }
        focusRequest = null
    }

    /** Solta o microfone sem derrubar o servidor nem a pagina do computador. */
    private fun pauseForFocus() {
        if (server == null || pausedByFocus) return
        pausedByFocus = true
        engine?.stop()
        engine = null
        StreamState.update { it.copy(paused = true) }
        server?.sendConfig()
        updateNotification()
        Log.i(TAG, "Microfone solto para outro aplicativo")
    }

    private fun resumeAfterFocus() {
        if (server == null || !pausedByFocus) return
        pausedByFocus = false
        if (!startEngine()) {
            pausedByFocus = true
            StreamState.update { it.copy(paused = true) }
            return
        }
        StreamState.update {
            it.copy(paused = false, stereoReal = false, stereoVerified = false)
        }
        server?.sendConfig()
        updateNotification()
        Log.i(TAG, "Microfone retomado")
    }

    /**
     * Tentativa a pedido da pessoa, quando ela volta para a tela do aplicativo.
     * Nao ha nova tentativa automatica, senao roubariamos o microfone de volta de
     * quem ainda estivesse gravando.
     */
    fun retryAfterFocusLoss() {
        if (server == null || !pausedByFocus) return
        if (requestFocus()) resumeAfterFocus()
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
        pausedByFocus = false
        try {
            abandonFocus()
        } catch (e: Throwable) {
            Log.w(TAG, "Falha ao soltar o foco de audio", e)
        }
        try {
            engine?.stop()
        } catch (e: Throwable) {
            Log.w(TAG, "Falha ao encerrar a captura", e)
        }
        engine = null
        try {
            server?.stop()
        } catch (e: Throwable) {
            Log.w(TAG, "Falha ao encerrar o servidor", e)
        }
        server = null
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Throwable) {
            Log.w(TAG, "Falha ao soltar o bloqueio de energia", e)
        }
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (e: Throwable) {
            Log.w(TAG, "Falha ao soltar o bloqueio de Wi-Fi", e)
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

    // -------------------------------------------------- mudancas de preferencia

    /**
     * Reaplica as preferencias enquanto a transmissao roda.
     * O ganho vale na hora. Trocar microfone, estereo ou porta refaz o que for preciso.
     */
    fun reconfigure() {
        val current = server ?: return
        engine?.gainDb = prefs.gainDb

        if (pausedByFocus) {
            // O microfone esta com outro aplicativo. Guardamos a escolha para depois.
            sourceChanged = false
            current.sendConfig()
            return
        }

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
            .put("gainPercent", prefs.gainPercent)
            .put("bufferMs", prefs.bufferMs)
            .put("source", prefs.micSource)
            .put("stereo", prefs.stereo)
            .put("stereoKnown", stereoKnown)
            .put("stereoReal", running?.stereoReal ?: false)
            .put("paused", pausedByFocus)
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
                when {
                    pausedByFocus -> getString(R.string.notif_paused)
                    address != null -> getString(R.string.notif_text, address)
                    else -> getString(R.string.address_unavailable)
                }
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

        /** Nova tentativa de pegar o microfone, a pedido da pessoa. */
        fun retryMicrophone() {
            instance?.retryAfterFocusLoss()
        }

        /** Reaplica as preferencias se o servico estiver ativo. */
        fun applyPreferences(sourceChanged: Boolean) {
            val service = instance ?: return
            if (sourceChanged) service.markSourceChanged()
            service.reconfigure()
        }
    }
}
