package com.vrivrixd.micmonitor

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.vrivrixd.micmonitor.databinding.DialogDownloadBinding
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Procura uma versao nova no GitHub, baixa e entrega ao instalador do Android.
 *
 * A consulta nao precisa de senha nem de conta. A propria publicacao diz o nome do
 * arquivo e o endereco de onde baixar, entao o aplicativo nunca precisa adivinhar.
 *
 * Nada disso aparece quando o aparelho esta sem rede ou quando o GitHub nao
 * responde: a procura falha calada, porque ela nao e o motivo de abrir o programa.
 */
class UpdateManager(private val activity: AppCompatActivity) {

    /** O que a ultima publicacao diz sobre si mesma. */
    data class Release(
        val version: String,
        val fileName: String,
        val url: String,
        val size: Long
    )

    private val main = Handler(Looper.getMainLooper())

    private var worker: Thread? = null

    /** Conexao do download, para poder ser cortada de fora. */
    @Volatile
    private var live: HttpURLConnection? = null

    @Volatile
    private var canceled = false

    private var dialog: AlertDialog? = null
    private var progress: DialogDownloadBinding? = null

    /** Ultimo aviso falado, para nao repetir a cada pedaco que chega. */
    private var spokenStep = -1

    /** Versao que espera a permissao de instalar voltar da tela do sistema. */
    private var waiting: Release? = null

    private val permissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val release = waiting
        waiting = null
        if (release != null && canInstall()) start(release)
    }

    /**
     * Procura uma versao nova, uma vez por execucao.
     * A consulta acontece fora da thread principal e a resposta volta para ela.
     */
    fun checkOnStart() {
        // Em teste a janela volta toda vez, mesmo sem versao nova.
        if (checkedThisRun && !FORCE_DIALOG) return
        checkedThisRun = true
        Thread({
            val release = try {
                fetch()
            } catch (e: Exception) {
                Log.d(TAG, "Procura por atualizacao falhou", e)
                null
            } ?: return@Thread
            main.post { onRelease(release) }
        }, "MicMonitor-Update").start()
    }

    /** Encerra o que estiver em andamento quando a tela sai. */
    fun release() {
        cancel()
        worker = null
        dismiss()
    }

    // ------------------------------------------------------------------ consulta

    private fun fetch(): Release? {
        val connection = (URL(API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "MicMonitor")
            connectTimeout = 10000
            readTimeout = 10000
        }
        try {
            if (connection.responseCode != 200) return null
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val version = json.optString("tag_name").removePrefix("v").trim()
            if (version.isEmpty()) return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    return Release(
                        version = version,
                        fileName = name,
                        url = asset.optString("browser_download_url"),
                        size = asset.optLong("size")
                    )
                }
            }
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun onRelease(release: Release) {
        if (activity.isFinishing || activity.isDestroyed) return
        val mine = installedVersion()
        if (!FORCE_DIALOG && !isNewer(release.version, mine)) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_title)
            .setMessage(activity.getString(R.string.update_body, mine, release.version))
            .setPositiveButton(R.string.dialog_yes) { _, _ -> start(release) }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }

    /** Compara numero a numero, entao 1.10 e maior que 1.9 e nao menor. */
    private fun isNewer(remote: String, local: String): Boolean {
        val there = numbers(remote)
        val here = numbers(local)
        for (i in 0 until maxOf(there.size, here.size)) {
            val a = there.getOrElse(i) { 0 }
            val b = here.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun numbers(version: String): List<Int> =
        version.split(".").map { part -> part.filter { it.isDigit() }.toIntOrNull() ?: 0 }

    private fun installedVersion(): String = try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    // ------------------------------------------------------------------- download

    private fun start(release: Release) {
        if (!canInstall()) {
            askPermission(release)
            return
        }
        val ready = finished(release)
        if (ready != null) {
            // Ja esta no aparelho inteiro, de uma recusa anterior. Baixar de novo
            // gastaria rede e tempo por nada.
            install(ready)
            return
        }
        download(release)
    }

    /** O arquivo completo daquela versao, se ele ja estiver guardado. */
    private fun finished(release: Release): File? {
        val file = fileFor(release)
        if (!file.isFile) return null
        if (release.size > 0 && file.length() != release.size) {
            file.delete()
            return null
        }
        return file
    }

    private fun folder(): File = File(activity.cacheDir, "atualizacoes").apply { mkdirs() }

    private fun fileFor(release: Release): File = File(folder(), release.fileName)

    private fun download(release: Release) {
        val target = fileFor(release)
        val part = File(target.path + ".part")
        // Versoes antigas que ficaram para tras so ocupam espaco.
        folder().listFiles()?.forEach {
            if (it.name != target.name && it.name != part.name) it.delete()
        }

        canceled = false
        spokenStep = -1
        showProgress(release)

        worker = Thread({
            var done = false
            try {
                done = fetchFile(release, part)
            } catch (e: Exception) {
                Log.w(TAG, "Download da atualizacao falhou", e)
            }
            if (done && !canceled) {
                part.renameTo(target)
                main.post {
                    dismiss()
                    install(target)
                }
            } else {
                // Pedaco solto nao serve para nada e ainda ocupa espaco.
                part.delete()
                val avisar = !canceled
                main.post {
                    dismiss()
                    if (avisar) fail()
                }
            }
        }, "MicMonitor-Download").also { it.start() }
    }

    @Throws(IOException::class)
    private fun fetchFile(release: Release, part: File): Boolean {
        val connection = (URL(release.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MicMonitor")
            connectTimeout = 15000
            readTimeout = 20000
        }
        live = connection
        try {
            if (connection.responseCode != 200) return false
            val total = if (release.size > 0) release.size else connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var got = 0L
                    while (!canceled) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        got += read
                        publish(got, total)
                    }
                    return !canceled && (total <= 0 || got == total)
                }
            }
        } finally {
            live = null
            connection.disconnect()
        }
    }

    private fun publish(got: Long, total: Long) {
        if (total <= 0) return
        val percent = ((got * 100) / total).toInt().coerceIn(0, 100)
        main.post { showPercent(percent) }
    }

    // ------------------------------------------------------------------- janelas

    private fun showProgress(release: Release) {
        val binding = DialogDownloadBinding.inflate(activity.layoutInflater)
        binding.downloadBar.max = 100
        binding.downloadBar.progress = 0
        binding.downloadPercent.text = activity.getString(R.string.update_percent, 0)
        progress = binding

        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_download_title)
            .setView(binding.root)
            .setCancelable(false)
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> cancel() }
            .show()
        Log.i(TAG, "Baixando " + release.fileName)
    }

    private fun showPercent(percent: Int) {
        val binding = progress ?: return
        binding.downloadBar.progress = percent
        binding.downloadPercent.text = activity.getString(R.string.update_percent, percent)
        // O numero muda depressa demais para ser falado inteiro. De dez em dez o
        // leitor de tela acompanha sem atropelar o resto da tela.
        val step = percent / 10
        if (step != spokenStep) {
            spokenStep = step
            binding.downloadPercent.announceForAccessibility(
                activity.getString(R.string.update_percent, percent)
            )
        }
    }

    /**
     * Cancelar precisa cortar a conexao, e nao so avisar a thread. Uma leitura de
     * rede parada nao acorda com interrupcao, e a pessoa ficaria esperando.
     */
    private fun cancel() {
        canceled = true
        val open = live
        worker?.interrupt()
        if (open != null) Thread({ open.disconnect() }, "MicMonitor-Cancel").start()
    }

    private fun dismiss() {
        dialog?.let {
            if (it.isShowing) it.dismiss()
        }
        dialog = null
        progress = null
    }

    private fun fail() {
        val root: View = activity.findViewById(android.R.id.content) ?: return
        Snackbar.make(root, R.string.update_failed, Snackbar.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- instalacao

    /**
     * Do Android 8 em diante cada aplicativo precisa de autorizacao propria para
     * instalar outro. Sem ela o instalador nem abre.
     */
    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            activity.packageManager.canRequestPackageInstalls()

    private fun askPermission(release: Release) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_permission_title)
            .setMessage(R.string.update_permission_body)
            .setPositiveButton(R.string.update_permission_action) { _, _ ->
                waiting = release
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.packageName)
                )
                try {
                    permissionLauncher.launch(intent)
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "Sem tela de permissao de instalacao", e)
                    waiting = null
                    fail()
                }
            }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }

    /**
     * Entrega o arquivo ao instalador do sistema.
     *
     * O caminho vai como endereco de conteudo, e nao como caminho de arquivo, porque
     * desde o Android 7 passar o caminho direto derruba o aplicativo.
     */
    private fun install(file: File) {
        val uri = try {
            FileProvider.getUriForFile(activity, activity.packageName + PROVIDER, file)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Arquivo fora das pastas que o provedor conhece", e)
            fail()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Nenhum instalador atendeu", e)
            fail()
        }
    }

    companion object {
        private const val TAG = "UpdateManager"
        private const val PROVIDER = ".updates"
        private const val API =
            "https://api.github.com/repos/vrivrixd/mic-monitor/releases/latest"

        /**
         * Enquanto estiver ligado, o aviso aparece toda vez que o aplicativo abre,
         * mesmo sem versao nova. Serve so para experimentar a janela e o download.
         * Precisa voltar para falso antes de publicar.
         */
        const val FORCE_DIALOG = true

        /** Uma procura por execucao, senao girar a tela traria a janela de volta. */
        @Volatile
        private var checkedThisRun = false
    }
}
