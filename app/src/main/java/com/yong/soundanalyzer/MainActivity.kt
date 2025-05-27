package com.yong.soundanalyzer

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity: AppCompatActivity() {
    companion object {
        private const val LOG_TAG_PROCESS = "SoundAnalyzer_Process"
        private const val LOG_TAG_SELECT = "SoundAnalyzer_Select"

        private const val MIMETYPE_AUDIO = "audio/*"
        private const val MIMETYPE_VIDEO = "video/*"
    }

    // UI Elements
    private lateinit var btnSelect: Button
    private lateinit var btnStartNormal: Button
    private lateinit var btnStartQualcomm: Button
    private lateinit var layoutResult: LinearLayout
    private lateinit var tvAudioPath: TextView

    // Tensorflow Lite Classifier 인스턴스
    private var audioClassifier: AudioClassifier? = null

    // Audio Decoder 인스턴스
    private var audioDecoder: AudioDecoder? = null

    // Decoding 결과 데이터를 처리하기 위한 Channel 및 List
    private var pcmChannel: Channel<PcmData>? = null

    // 선택된 File 결과를 담기 위한 Uri
    private var fileUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // UI Initialization
        initUI()
        initEventListener()
    }

    // UI View Initialization
    private fun initUI() {
        btnSelect = findViewById(R.id.main_btn_select)
        btnStartNormal = findViewById(R.id.main_btn_start_normal)
        btnStartQualcomm = findViewById(R.id.main_btn_start_qualcomm)
        layoutResult = findViewById(R.id.main_layout_result)
        tvAudioPath = findViewById(R.id.main_tv_audio_path)
    }

    // UI Event Listener Initialization
    private fun initEventListener() {
        btnSelect.setOnClickListener {
            Log.d(LOG_TAG_SELECT, "Select Started")
            val mimeType = arrayOf(MIMETYPE_AUDIO, MIMETYPE_VIDEO)
            selectMediaFile.launch(mimeType)
        }

        btnStartNormal.setOnClickListener { startAudioProcessing(AudioClassifier.YAMNET_MODE_NORMAL) }
        btnStartQualcomm.setOnClickListener { startAudioProcessing(AudioClassifier.YAMNET_MODE_QUALCOMM) }
    }

    // UI Update
    private fun updateUI(filepath: String) {
        layoutResult.removeAllViewsInLayout()
        tvAudioPath.text = filepath
    }

    // Media File 선택 이후 결과 처리
    private val selectMediaFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            Log.d(LOG_TAG_SELECT, "Select Done: $it")

            // UI Update
            updateUI(it.path ?: "Unknown Path")
            // 선택된 File 업데이트
            fileUri = uri
        }
    }

    // Audio 처리 과정 시작
    private fun startAudioProcessing(mode: String) {
        if(fileUri == null) return
        Log.d(LOG_TAG_PROCESS, "Process Started: $fileUri")

        // 전체 Flow는 다음과 같음
        // Decoding -> [PCM Data] -> Classify w/ Tensorflow Lite YAMNet -> [Result Data]

        // Classifier 및 Decoder 초기화
        var isInitialized = initClassifier(mode) && initDecoder(fileUri!!)
        if(!isInitialized) return

        // 데이터 전달을 위한 Channel 초기화
        pcmChannel = Channel<PcmData>()
        if(pcmChannel == null) return

        // Classifier 및 Decoder 시작
        audioClassifier!!.startClassifier(pcmChannel!!)
        audioDecoder!!.startDecoder(pcmChannel!!)
    }

    // Decoder 초기화
    private fun initDecoder(uri: Uri): Boolean {
        audioDecoder = AudioDecoder()
        return audioDecoder?.init(applicationContext, uri) ?: false
    }

    // Classifier 초기화
    private fun initClassifier(mode: String): Boolean {
        val delegate = object: AudioClassifier.Delegate {
            override suspend fun onRangeComposed(range: Pair<Long, Long>)
                = addMergedRangeToUI(range)
        }
        audioClassifier = AudioClassifier()
        return audioClassifier?.init(applicationContext, mode, delegate) ?: false
    }

    private suspend fun addMergedRangeToUI(range: Pair<Long, Long>) {
        withContext(Dispatchers.Main) {
            layoutResult.addView(
                TextView(this@MainActivity).apply {
                    text = String.format(Locale.getDefault(), "%.2fs - %.2fs", range.first / 1000.0, range.second / 1000.0)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
            )
        }
    }
}