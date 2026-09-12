package com.vrivrixd.micmonitor

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vrivrixd.micmonitor.databinding.ActivityHelpBinding

/**
 * Ajuda em forma de tela, com a lista de assuntos.
 *
 * E uma tela de verdade, e nao um dialogo, para que o navegar para cima seja o
 * primeiro elemento, igual as outras telas do aplicativo. Cada assunto abre em um
 * dialogo por cima, e o botao OK devolve a lista.
 */
class HelpActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHelpBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.topicList.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            TOPICS.map { getString(it.first) }
        )
        binding.topicList.setOnItemClickListener { _, _, position, _ -> showTopic(position) }
    }

    private fun showTopic(index: Int) {
        val topic = TOPICS.getOrNull(index) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(topic.first)
            .setMessage(topic.second)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        /** Titulo e texto de cada assunto, na ordem em que aparecem. */
        private val TOPICS = listOf(
            R.string.help_topic_about to R.string.help_body_about,
            R.string.help_topic_start to R.string.help_body_start,
            R.string.help_topic_port to R.string.help_body_port,
            R.string.help_topic_controls to R.string.help_body_controls,
            R.string.help_topic_multi to R.string.help_body_multi,
            R.string.help_topic_buffer to R.string.help_body_buffer,
            R.string.help_topic_output to R.string.help_body_output,
            R.string.help_topic_battery to R.string.help_body_battery
        )
    }
}
