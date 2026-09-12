package com.vrivrixd.micmonitor

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.material.snackbar.Snackbar
import com.vrivrixd.micmonitor.databinding.ActivitySettingsBinding

/**
 * Settings screen. Every change takes effect at once, stream running or not.
 *
 * Each option is a single stop for the screen reader. The visible labels stay out
 * of the accessibility tree and the name goes on the control itself.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    /** Keeps an update coming from the page from firing the listeners here. */
    private var updating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupPort()
        setupMultiple()
        setupGain()
        setupMic()
        setupChannels()
        setupBuffer()
        setupBattery()

        loadFromPrefs()

        // The page on the computer changes these options too.
        var lastRevision = StreamState.current.configRevision
        StreamState.state.observe(this) { snapshot ->
            if (snapshot.configRevision != lastRevision) {
                lastRevision = snapshot.configRevision
                loadFromPrefs()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryButton()
    }

    // --------------------------------------------------------------------- port

    private fun setupPort() {
        labelField(binding.portInput, R.string.label_port)

        binding.portInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                val text = s?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    binding.portInput.error = null
                    prefs.portText = ""
                    StreamService.applyPreferences(false)
                    return
                }
                val value = text.toIntOrNull()
                if (value == null || value < Prefs.MIN_PORT || value > Prefs.MAX_PORT) {
                    binding.portInput.error = getString(R.string.error_port_range)
                    return
                }
                binding.portInput.error = null
                prefs.portText = text
                StreamService.applyPreferences(false)
            }
        })
    }

    // ---------------------------------------------------- several connections

    private fun setupMultiple() {
        binding.multiCheck.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            prefs.allowMultiple = checked
            StreamService.applyPreferences(false)
        }
    }

    // --------------------------------------------------------------------- gain

    private fun setupGain() {
        binding.gainSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (updating || !fromUser) return
                prefs.gainPercent = progress
                StreamService.applyPreferences(false)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
    }

    // --------------------------------------------------------------- microphone

    private fun setupMic() {
        binding.micSpinner.adapter = adapterOf(MicSource.ALL.map { getString(MicSource.labelRes(it)) })
        binding.micSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                describe(binding.micSpinner, R.string.label_mic)
                if (updating) return
                val key = MicSource.ALL[position]
                if (key == prefs.micSource) return
                prefs.micSource = key
                StreamService.applyPreferences(true)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    // ----------------------------------------------------------------- channels

    private fun setupChannels() {
        binding.channelSpinner.adapter =
            adapterOf(ChannelMode.ALL.map { getString(ChannelMode.labelRes(it)) })
        binding.channelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                describe(binding.channelSpinner, R.string.label_channels)
                if (updating) return
                val mode = ChannelMode.ALL[position]
                if (mode == prefs.channelMode) return
                prefs.channelMode = mode
                // In stereo the device picks the microphone on its own.
                binding.micSpinner.isEnabled = !ChannelMode.wantsStereo(mode)
                StreamService.applyPreferences(false)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    // ------------------------------------------------------------------- buffer

    private fun setupBuffer() {
        labelField(binding.bufferInput, R.string.label_buffer)

        binding.bufferInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                val value = s?.toString()?.trim()?.toIntOrNull()
                if (value == null || value < Prefs.MIN_BUFFER_MS || value > Prefs.MAX_BUFFER_MS) {
                    binding.bufferInput.error = getString(
                        R.string.error_buffer_range, Prefs.MIN_BUFFER_MS, Prefs.MAX_BUFFER_MS
                    )
                    return
                }
                binding.bufferInput.error = null
                if (value == prefs.bufferMs) return
                prefs.bufferMs = value
                StreamService.applyPreferences(false)
            }
        })
    }

    // ------------------------------------------------------------------ battery

    private fun setupBattery() {
        binding.batteryButton.setOnClickListener { requestIgnoreBattery() }
    }

    private fun isIgnoringBattery(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        return power.isIgnoringBatteryOptimizations(packageName)
    }

    private fun refreshBatteryButton() {
        binding.batteryButton.setText(
            if (isIgnoringBattery()) R.string.battery_done else R.string.battery_action
        )
    }

    /** Opens the system request. Without it Android may end the stream in the background. */
    private fun requestIgnoreBattery() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:" + packageName))
        try {
            startActivity(direct)
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(binding.root, R.string.battery_unavailable, Snackbar.LENGTH_LONG).show()
        }
    }

    // -------------------------------------------------------------------- state

    private fun adapterOf(labels: List<String>): ArrayAdapter<String> {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return adapter
    }

    /**
     * Names a text field for the screen reader.
     *
     * The reader stops announcing contentDescription as soon as the field holds
     * text. The label then travels as the hint, which keeps being announced.
     */
    private fun labelField(field: EditText, labelRes: Int) {
        ViewCompat.setAccessibilityDelegate(field, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: AccessibilityNodeInfoCompat
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.hintText = getString(labelRes)
                info.isShowingHintText = field.text.isNullOrEmpty()
            }
        })
    }

    /** The name of the option and the chosen value come out in a single announcement. */
    private fun describe(spinner: Spinner, labelRes: Int) {
        val value = spinner.selectedItem as? String ?: return
        spinner.contentDescription = getString(labelRes) + ", " + value
    }

    private fun loadFromPrefs() {
        updating = true
        try {
            val portText = prefs.portText
            if (binding.portInput.text?.toString() != portText) {
                binding.portInput.setText(portText)
            }
            binding.multiCheck.isChecked = prefs.allowMultiple
            binding.gainSeek.progress = prefs.gainPercent
            binding.micSpinner.setSelection(MicSource.ALL.indexOf(prefs.micSource).coerceAtLeast(0))
            binding.channelSpinner.setSelection(
                ChannelMode.ALL.indexOf(prefs.channelMode).coerceAtLeast(0)
            )
            binding.micSpinner.isEnabled = !ChannelMode.wantsStereo(prefs.channelMode)
            val bufferText = prefs.bufferMs.toString()
            if (binding.bufferInput.text?.toString() != bufferText) {
                binding.bufferInput.setText(bufferText)
            }
            describe(binding.micSpinner, R.string.label_mic)
            describe(binding.channelSpinner, R.string.label_channels)
        } finally {
            updating = false
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
