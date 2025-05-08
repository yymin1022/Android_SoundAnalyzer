package com.yong.soundanalyzer

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    companion object {
        private const val LOG_TAG_PROCESS = "SoundAnalyzer_Process"
        private const val LOG_TAG_SELECT = "SoundAnalyzer_Select"

        private const val MIMETYPE_AUDIO = "audio/*"
    }

    private lateinit var btnSelect: Button
    private lateinit var layoutResult: LinearLayout
    private lateinit var tvAudioPath: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initUI()
    }

    private fun initUI() {
        btnSelect = findViewById(R.id.main_btn_select)
        btnSelect.setOnClickListener {
            Log.d(LOG_TAG_SELECT, "Select Started")
            selectAudioFile.launch(MIMETYPE_AUDIO)
        }

        layoutResult = findViewById(R.id.main_layout_result)
        tvAudioPath = findViewById(R.id.main_tv_audio_path)
    }

    private fun updateUI(filepath: String) {
        tvAudioPath.text = filepath
    }

    private val selectAudioFile = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            Log.d(LOG_TAG_SELECT, "Select Done: $it")
            updateUI(it.path ?: "Unknown Path")
            processAudioFile(it)
        }
    }

    private fun processAudioFile(uri: Uri) {
        Log.d(LOG_TAG_PROCESS, "Process Started: $uri")

        // TODO: Audio Processing Implementation
        // Decoding -> [PCM Data] -> Analyze -> [Result Data]

        Log.d(LOG_TAG_PROCESS, "Process Done: $uri")
    }
}