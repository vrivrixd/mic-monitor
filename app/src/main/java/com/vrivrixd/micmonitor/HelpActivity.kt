package com.vrivrixd.micmonitor

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vrivrixd.micmonitor.databinding.ActivityHelpBinding

/**
 * Help as a screen, holding the list of topics.
 *
 * A screen and not a dialog, so that navigate up is the first element, like
 * everywhere else in the app. Each topic opens in a dialog on top, and its OK
 * button gives the list back.
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
        /** Title and body of each topic, in the order they are listed. */
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
