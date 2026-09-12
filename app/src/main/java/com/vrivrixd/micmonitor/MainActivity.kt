package com.vrivrixd.micmonitor

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.vrivrixd.micmonitor.databinding.ActivityMainBinding

/** Main screen: turns the stream on and off and shows the listening address. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** The start and stop button sits in the bar, next to the overflow menu. */
    private var toggleItem: MenuItem? = null

    /** Stopping and starting hides and shows text, which would lose the reader's place. */
    private var restoreToggleFocus = false

    /** Finds, downloads and hands the new version to the Android installer. */
    private lateinit var updates: UpdateManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            StreamService.start(this)
        } else {
            Snackbar.make(binding.root, R.string.perm_mic_needed, Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        ViewCompat.setAccessibilityHeading(binding.statusText, true)

        StreamState.state.observe(this) { render(it) }

        // It has to be born here, because the return from the permission screen can
        // only be registered before the screen shows up.
        updates = UpdateManager(this)
        updates.checkOnStart()
    }

    override fun onDestroy() {
        updates.release()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        toggleItem = menu.findItem(R.id.menu_toggle)
        updateToggleTitle(StreamState.current.running)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_toggle -> {
            onToggle()
            true
        }
        R.id.menu_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        R.id.menu_help -> {
            startActivity(Intent(this, HelpActivity::class.java))
            true
        }
        R.id.menu_about -> {
            showAbout()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun onToggle() {
        restoreToggleFocus = true
        if (StreamState.current.running) {
            StreamService.stop(this)
            return
        }
        val needed = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isEmpty()) {
            StreamService.start(this)
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun updateToggleTitle(running: Boolean) {
        toggleItem?.setTitle(if (running) R.string.action_stop else R.string.action_start)
    }

    private fun render(snapshot: StreamState.Snapshot) {
        updateToggleTitle(snapshot.running)

        binding.statusText.setText(
            when {
                snapshot.running && snapshot.paused -> R.string.status_paused
                snapshot.running -> R.string.status_running
                else -> R.string.status_stopped
            }
        )

        val showAddress = snapshot.running
        binding.addressIntro.visibility = if (showAddress) View.VISIBLE else View.GONE
        binding.addressText.visibility = if (showAddress) View.VISIBLE else View.GONE
        binding.clientText.visibility = if (showAddress) View.VISIBLE else View.GONE

        if (showAddress) {
            val address = snapshot.address
            if (address != null) {
                binding.addressText.text = address
                binding.addressText.contentDescription = address
            } else {
                binding.addressText.setText(R.string.address_unavailable)
                binding.addressIntro.visibility = View.GONE
            }
            // The count only shows up past one listener, so no language has to make
            // a number agree with the sentence around it.
            binding.clientText.text = when {
                snapshot.clientCount <= 0 -> getString(R.string.client_none)
                snapshot.clientCount == 1 -> getString(R.string.client_connected)
                else -> getString(R.string.client_count, snapshot.clientCount)
            }
        }

        val warning = when {
            snapshot.error != null -> snapshot.error
            snapshot.running && snapshot.paused -> getString(R.string.paused_reason)
            else -> null
        }
        if (warning != null) {
            binding.warningText.text = warning
            binding.warningText.visibility = View.VISIBLE
        } else {
            binding.warningText.visibility = View.GONE
        }

        if (restoreToggleFocus) {
            restoreToggleFocus = false
            binding.toolbar.post { focusToggle() }
        }
    }

    /** Gives the reader's focus back to the button the person has just used. */
    private fun focusToggle() {
        val view = binding.toolbar.findViewById<View>(R.id.menu_toggle) ?: return
        view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
    }

    /** Version, author and the way to the source, which opens in the browser. */
    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_body, versionName()))
            .setPositiveButton(R.string.dialog_ok, null)
            .setNeutralButton(R.string.about_source) { _, _ -> openSource() }
            .show()
    }

    private fun openSource() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)))
        } catch (e: ActivityNotFoundException) {
            Log.w("MainActivity", "No browser to open the source code", e)
        }
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.1"
    } catch (e: PackageManager.NameNotFoundException) {
        "1.1"
    }

    companion object {
        private const val SOURCE_URL = "https://github.com/vrivrixd/mic-monitor"
    }
}
