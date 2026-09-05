package com.vrivrixd.micmonitor

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.vrivrixd.micmonitor.databinding.ActivitySettingsBinding
import java.util.Locale

/** Tela de configuracoes. Cada alteracao vale na hora, inclusive com a transmissao ligada. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    /** Evita que a atualizacao vinda da pagina dispare os ouvintes da tela. */
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
        setupGain()
        setupMic()
        setupStereo()

        loadFromPrefs()

        // A pagina no computador tambem muda estas opcoes.
        var lastRevision = StreamState.current.configRevision
        StreamState.state.observe(this) { snapshot ->
            if (snapshot.configRevision != lastRevision) {
                lastRevision = snapshot.configRevision
                loadFromPrefs()
            }
        }
    }

    // -------------------------------------------------------------------- porta

    private fun setupPort() {
        binding.portInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                val text = s?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    binding.portLayout.error = null
                    prefs.portText = ""
                    StreamService.applyPreferences(false)
                    return
                }
                val value = text.toIntOrNull()
                if (value == null || value < Prefs.MIN_PORT || value > Prefs.MAX_PORT) {
                    binding.portLayout.error = getString(R.string.error_port_range)
                    return
                }
                binding.portLayout.error = null
                prefs.portText = text
                StreamService.applyPreferences(false)
            }
        })
    }

    // -------------------------------------------------------------------- ganho

    private fun setupGain() {
        binding.gainSlider.valueFrom = Prefs.MIN_GAIN_DB
        binding.gainSlider.valueTo = Prefs.MAX_GAIN_DB
        binding.gainSlider.setLabelFormatter { value -> formatGain(value) }
        binding.gainSlider.addOnChangeListener { _, value, fromUser ->
            showGain(value)
            if (updating || !fromUser) return@addOnChangeListener
            prefs.gainDb = value
            StreamService.applyPreferences(false)
        }
    }

    private fun showGain(value: Float) {
        val text = formatGain(value)
        binding.gainValue.text = text
        binding.gainSlider.contentDescription = getString(R.string.gain_desc, text.removeSuffix(" dB"))
    }

    private fun formatGain(value: Float): String {
        val rounded = Math.round(value)
        return getString(R.string.gain_value, String.format(Locale.getDefault(), "%d", rounded))
    }

    // --------------------------------------------------------------- microfone

    private fun setupMic() {
        val labels = MicSource.ALL.map { getString(MicSource.labelRes(it)) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.micSpinner.adapter = adapter
        binding.micSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updating) return
                val key = MicSource.ALL[position]
                if (key == prefs.micSource) return
                prefs.micSource = key
                StreamService.applyPreferences(true)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    // ----------------------------------------------------------------- estereo

    private fun setupStereo() {
        binding.stereoCheck.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            prefs.stereo = checked
            binding.micSpinner.isEnabled = !checked
            StreamService.applyPreferences(false)
        }
    }

    // ------------------------------------------------------------------ estado

    private fun loadFromPrefs() {
        updating = true
        try {
            val portText = prefs.portText
            if (binding.portInput.text?.toString() != portText) {
                binding.portInput.setText(portText)
            }
            binding.gainSlider.value = prefs.gainDb.coerceIn(Prefs.MIN_GAIN_DB, Prefs.MAX_GAIN_DB)
            showGain(binding.gainSlider.value)
            binding.micSpinner.setSelection(MicSource.ALL.indexOf(prefs.micSource).coerceAtLeast(0))
            binding.stereoCheck.isChecked = prefs.stereo
            binding.micSpinner.isEnabled = !prefs.stereo
        } finally {
            updating = false
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
