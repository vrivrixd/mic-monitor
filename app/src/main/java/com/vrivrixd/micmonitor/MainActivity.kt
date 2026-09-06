package com.vrivrixd.micmonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.vrivrixd.micmonitor.databinding.ActivityMainBinding

/** Tela principal: liga e desliga a transmissao e mostra o endereco de escuta. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** O botao iniciar e parar fica na barra, ao lado do menu de mais opcoes. */
    private var toggleItem: MenuItem? = null

    /** Parar e iniciar esconde e mostra textos, o que faria o leitor de tela perder o lugar. */
    private var restoreToggleFocus = false

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
            showHelp()
            true
        }
        R.id.menu_about -> {
            showDialog(R.string.about_title, getString(R.string.about_body, versionName()))
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
            binding.clientText.setText(
                if (snapshot.clientConnected) R.string.client_connected else R.string.client_none
            )
        }

        val warning = when {
            snapshot.error != null -> snapshot.error
            snapshot.running && snapshot.paused -> getString(R.string.paused_reason)
            snapshot.running && snapshot.stereoRequested &&
                snapshot.stereoVerified && !snapshot.stereoReal ->
                getString(R.string.warn_stereo_fake)
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

    /** Devolve o foco do leitor de tela ao botao que a pessoa acabou de acionar. */
    private fun focusToggle() {
        val view = binding.toolbar.findViewById<View>(R.id.menu_toggle) ?: return
        view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
    }

    /** A ajuda abre como lista de assuntos. Cada assunto abre em cima dela. */
    private fun showHelp() {
        val titles = HELP_TOPICS.map { getString(it.first) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.help_title)
            .setItems(titles) { _, which -> showHelpTopic(which) }
            .setNegativeButton(R.string.navigate_up, null)
            .show()
    }

    private fun showHelpTopic(index: Int) {
        val topic = HELP_TOPICS.getOrNull(index) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(topic.first)
            .setMessage(topic.second)
            // Fechar o assunto, pelo botao ou pelo voltar, devolve a lista.
            .setPositiveButton(R.string.dialog_ok) { _, _ -> showHelp() }
            .setOnCancelListener { showHelp() }
            .show()
    }

    private fun showDialog(titleRes: Int, message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    } catch (e: PackageManager.NameNotFoundException) {
        "1.0"
    }

    companion object {
        /** Titulo e texto de cada assunto da ajuda, na ordem em que aparecem. */
        private val HELP_TOPICS = listOf(
            R.string.help_topic_about to R.string.help_body_about,
            R.string.help_topic_start to R.string.help_body_start,
            R.string.help_topic_port to R.string.help_body_port,
            R.string.help_topic_controls to R.string.help_body_controls,
            R.string.help_topic_buffer to R.string.help_body_buffer,
            R.string.help_topic_output to R.string.help_body_output,
            R.string.help_topic_battery to R.string.help_body_battery
        )
    }
}
