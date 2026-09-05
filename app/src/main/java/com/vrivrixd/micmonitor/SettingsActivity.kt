package com.vrivrixd.micmonitor

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import com.vrivrixd.micmonitor.databinding.ActivitySettingsBinding

/**
 * Tela de configuracoes. Cada alteracao vale na hora, inclusive com a transmissao ligada.
 *
 * Cada opcao e um unico ponto de parada para o leitor de tela. Os rotulos visiveis
 * ficam fora da arvore de acessibilidade e o nome vai na propria opcao.
 */
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
        setupBuffer()

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

    // -------------------------------------------------------------------- ganho

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

    // --------------------------------------------------------------- microfone

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

    // ----------------------------------------------------------------- estereo

    private fun setupStereo() {
        binding.stereoCheck.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            prefs.stereo = checked
            binding.micSpinner.isEnabled = !checked
            StreamService.applyPreferences(false)
        }
    }

    // ------------------------------------------------------------------ buffer

    private fun setupBuffer() {
        binding.bufferSpinner.adapter =
            adapterOf(Prefs.BUFFER_OPTIONS.map { getString(Prefs.bufferLabelRes(it)) })
        binding.bufferSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                describe(binding.bufferSpinner, R.string.label_buffer)
                if (updating) return
                val ms = Prefs.BUFFER_OPTIONS[position]
                if (ms == prefs.bufferMs) return
                prefs.bufferMs = ms
                StreamService.applyPreferences(false)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    // ------------------------------------------------------------------ estado

    private fun adapterOf(labels: List<String>): ArrayAdapter<String> {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return adapter
    }

    /** O nome da opcao e o valor escolhido saem em um anuncio so. */
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
            binding.gainSeek.progress = prefs.gainPercent
            binding.micSpinner.setSelection(MicSource.ALL.indexOf(prefs.micSource).coerceAtLeast(0))
            binding.stereoCheck.isChecked = prefs.stereo
            binding.micSpinner.isEnabled = !prefs.stereo
            binding.bufferSpinner.setSelection(
                Prefs.BUFFER_OPTIONS.indexOf(prefs.bufferMs).coerceAtLeast(0)
            )
            describe(binding.micSpinner, R.string.label_mic)
            describe(binding.bufferSpinner, R.string.label_buffer)
        } finally {
            updating = false
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
