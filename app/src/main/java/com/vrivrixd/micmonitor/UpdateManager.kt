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
 * Looks for a new version on GitHub, downloads it and hands it to the Android
 * installer.
 *
 * The lookup needs no password and no account. The release itself names the file
 * and the address to fetch it from, so the app never has to guess.
 *
 * None of this shows up when the phone has no network or when GitHub does not
 * answer: the lookup fails quietly, because it is not the reason to open the app.
 */
class UpdateManager(private val activity: AppCompatActivity) {

    /** What the latest release says about itself. */
    data class Release(
        val version: String,
        val fileName: String,
        val url: String,
        val size: Long
    )

    private val main = Handler(Looper.getMainLooper())

    private var worker: Thread? = null

    /** The download connection, so that it can be cut from the outside. */
    @Volatile
    private var live: HttpURLConnection? = null

    @Volatile
    private var canceled = false

    private var dialog: AlertDialog? = null
    private var progress: DialogDownloadBinding? = null

    /** Last percentage shown, so every incoming block does not repeat it. */
    private var shownPercent = -1

    /** The version waiting for the install permission to come back. */
    private var waiting: Release? = null

    private val permissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val release = waiting
        waiting = null
        if (release != null && canInstall()) start(release)
    }

    /**
     * Looks for a new version, once per run.
     * The lookup happens off the main thread and the answer comes back to it.
     */
    fun checkOnStart() {
        // Under test the dialog comes back every time, even without a new version.
        if (checkedThisRun && !FORCE_DIALOG) return
        checkedThisRun = true
        Thread({
            val release = try {
                fetch()
            } catch (e: Exception) {
                Log.d(TAG, "Update lookup failed", e)
                null
            } ?: return@Thread
            main.post { onRelease(release) }
        }, "MicMonitor-Update").start()
    }

    /** Ends whatever is in flight when the screen goes away. */
    fun release() {
        cancel()
        worker = null
        dismiss()
    }

    // -------------------------------------------------------------------- lookup

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

    /** Compares number by number, so 1.10 is greater than 1.9 instead of smaller. */
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

    // ------------------------------------------------------------------ download

    private fun start(release: Release) {
        if (!canInstall()) {
            askPermission(release)
            return
        }
        val ready = finished(release)
        if (ready != null) {
            // The whole thing is already on the phone, from an earlier refusal.
            // Fetching it again would spend network and time for nothing.
            install(ready)
            return
        }
        download(release)
    }

    /** The complete file of that version, if it is already stored. */
    private fun finished(release: Release): File? {
        val file = fileFor(release)
        if (!file.isFile) return null
        if (release.size > 0 && file.length() != release.size) {
            file.delete()
            return null
        }
        return file
    }

    private fun folder(): File = File(activity.cacheDir, "updates").apply { mkdirs() }

    private fun fileFor(release: Release): File = File(folder(), release.fileName)

    private fun download(release: Release) {
        val target = fileFor(release)
        val part = File(target.path + ".part")
        // Older versions left behind only take up room.
        folder().listFiles()?.forEach {
            if (it.name != target.name && it.name != part.name) it.delete()
        }

        canceled = false
        shownPercent = -1
        showProgress(release)

        worker = Thread({
            var done = false
            try {
                done = fetchFile(release, part)
            } catch (e: Exception) {
                Log.w(TAG, "The update download failed", e)
            }
            if (done && !canceled) {
                part.renameTo(target)
                main.post {
                    dismiss()
                    install(target)
                }
            } else {
                // A loose piece is good for nothing and still takes up room.
                part.delete()
                val warn = !canceled
                main.post {
                    dismiss()
                    if (warn) fail()
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

    // ------------------------------------------------------------------ windows

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
        Log.i(TAG, "Downloading " + release.fileName)
    }

    private fun showPercent(percent: Int) {
        val binding = progress ?: return
        if (percent == shownPercent) return
        shownPercent = percent
        binding.downloadBar.progress = percent
        val text = activity.getString(R.string.update_percent, percent)
        binding.downloadPercent.text = text
        // One by one: whoever listens to the screen needs to know exactly where the
        // download stands, not roughly how far along it has gone.
        binding.downloadPercent.announceForAccessibility(text)
    }

    /**
     * Cancelling has to cut the connection, and not merely tell the thread. A
     * network read that is parked does not wake up on an interrupt, and the person
     * would be left waiting.
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

    // ---------------------------------------------------------------- installing

    /**
     * From Android 8 on, every app needs its own authorisation to install another
     * one. Without it the installer does not even open.
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
                    Log.w(TAG, "No install permission screen on this phone", e)
                    waiting = null
                    fail()
                }
            }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }

    /**
     * Hands the file to the system installer.
     *
     * The path travels as a content address and not as a file path, because since
     * Android 7 passing the path directly brings the app down.
     */
    private fun install(file: File) {
        val uri = try {
            FileProvider.getUriForFile(activity, activity.packageName + PROVIDER, file)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "File outside the folders the provider knows about", e)
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
            Log.e(TAG, "No installer answered", e)
            fail()
        }
    }

    companion object {
        private const val TAG = "UpdateManager"
        private const val PROVIDER = ".updates"
        private const val API =
            "https://api.github.com/repos/vrivrixd/mic-monitor/releases/latest"

        /**
         * While this is on, the notice shows up every time the app opens, even
         * without a new version. It exists only to try the window and the download
         * out, and has to be off in a published build.
         */
        const val FORCE_DIALOG = false

        /** One lookup per run, otherwise rotating the screen would bring it back. */
        @Volatile
        private var checkedThisRun = false
    }
}
