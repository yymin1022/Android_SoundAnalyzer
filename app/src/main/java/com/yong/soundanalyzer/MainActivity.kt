package com.yong.soundanalyzer

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class MainActivity: AppCompatActivity() {
    companion object {
        private const val CLASSIFY_THRESHOLD = 0.1f

        private const val LOG_TAG_CLASSIFY = "SoundAnalyzer_Classify"
        private const val LOG_TAG_DECODE = "SoundAnalyzer_Decode"
        private const val LOG_TAG_PROCESS = "SoundAnalyzer_Process"
        private const val LOG_TAG_SELECT = "SoundAnalyzer_Select"

        private const val MIMETYPE_AUDIO_PREFIX = "audio/"
        private const val MIMETYPE_AUDIO = "audio/*"
        private const val MIMETYPE_VIDEO = "video/*"
    }

    private lateinit var btnSelect: Button
    private lateinit var layoutResult: LinearLayout
    private lateinit var tvAudioPath: TextView

    private var audioClassifier: AudioClassifier? = null

    private var audioDecoder: MediaCodec? = null
    private var audioFormat: MediaFormat? = null
    private var mediaExtractor: MediaExtractor? = null

    private val detectedRanges = mutableListOf<Pair<Long, Long>>()
    private var pcmChannel: Channel<Pair<Long, ShortArray>>? = null

    private val humanSoundLabels = setOf(
        "speech", "laughter", "cough", "baby_crying", "snoring",
        "gasp", "sneeze", "yell", "screaming", "crying_sobbing"
    )

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
        initEventListener()
    }

    private fun initUI() {
        btnSelect = findViewById(R.id.main_btn_select)
        layoutResult = findViewById(R.id.main_layout_result)
        tvAudioPath = findViewById(R.id.main_tv_audio_path)
    }

    private fun initEventListener() {
        btnSelect.setOnClickListener {
            Log.d(LOG_TAG_SELECT, "Select Started")
            val mimeType = setOf(MIMETYPE_AUDIO, MIMETYPE_VIDEO)
            selectAudioFile.launch(mimeType.joinToString(","))
        }
    }

    private fun updateUI(filepath: String) {
        layoutResult.removeAllViewsInLayout()
        tvAudioPath.text = filepath
    }

    private val selectAudioFile = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            Log.d(LOG_TAG_SELECT, "Select Done: $it")

            updateUI(it.path ?: "Unknown Path")
            startAudioProcessing(it)
        }
    }

    private fun startAudioProcessing(uri: Uri) {
        Log.d(LOG_TAG_PROCESS, "Process Started: $uri")

        // Decoding -> [PCM Data] -> Classify w/ Tensorflow Lite YAMNet -> [Result Data]

        var isInitialized = initClassifier() && initDecoder(uri)
        if(!isInitialized) return

        pcmChannel = Channel<Pair<Long, ShortArray>>()

        startClassifier()
        startDecoder()
    }

    private fun getAudioTrack(uri: Uri): Int {
        if(mediaExtractor != null) {
            mediaExtractor!!.release()
            mediaExtractor = null
        }

        mediaExtractor = MediaExtractor()
        if(mediaExtractor == null) return -1

        mediaExtractor!!.setDataSource(this@MainActivity, uri, null)
        for(i in 0 until mediaExtractor!!.trackCount) {
            val trackFormat = mediaExtractor!!.getTrackFormat(i)
            if(trackFormat.getString(MediaFormat.KEY_MIME)?.startsWith(MIMETYPE_AUDIO_PREFIX) == true) {
                audioFormat = trackFormat
                mediaExtractor!!.selectTrack(i)
                return i
            }
        }

        return -1
    }

    private fun initClassifier(): Boolean {
        Log.d(LOG_TAG_CLASSIFY, "Classifier Initializing")

        audioClassifier = AudioClassifier.createFromFile(this, "yamnet.tflite")
        if(audioClassifier == null) return false

        Log.d(LOG_TAG_CLASSIFY, "Classifier Initialized")
        return true
    }

    private fun initDecoder(uri: Uri): Boolean {
        Log.d(LOG_TAG_DECODE, "Decoder Initializing")

        val trackIdx = getAudioTrack(uri)
        if(trackIdx < 0) return false

        Log.d(LOG_TAG_DECODE, "Track Found : [$trackIdx]")

        if(audioDecoder != null) {
            audioDecoder!!.stop()
            audioDecoder!!.release()
            audioDecoder = null
        }

        audioDecoder = MediaCodec.createDecoderByType(audioFormat!!.getString(MediaFormat.KEY_MIME)!!)
        if(audioDecoder == null) return false

        audioDecoder!!.configure(audioFormat, null, null, 0)
        audioDecoder!!.start()

        Log.d(LOG_TAG_DECODE, "Decoder Initialized")
        return true
    }

    private fun startDecoder() {
        startDecoderInputProcessing()
        startDecoderOutputProcessing()
    }

    private fun startDecoderInputProcessing() {
        CoroutineScope(Dispatchers.Default).launch {
            while(true) {
                val inputIdx = audioDecoder!!.dequeueInputBuffer(0)
                if(inputIdx >= 0) {
                    val inputBuffer = audioDecoder!!.getInputBuffer(inputIdx) ?: continue

                    val sampleSize = mediaExtractor!!.readSampleData(inputBuffer, 0)
                    if(sampleSize < 0) {
                        Log.d(LOG_TAG_DECODE, "Decoder Input Done: EOS")
                        audioDecoder!!.queueInputBuffer(inputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        continue
                    }

                    val sampleTime = mediaExtractor!!.sampleTime
                    audioDecoder!!.queueInputBuffer(inputIdx, 0, sampleSize, sampleTime, 0)

                    mediaExtractor!!.advance()
                }
            }
        }
    }

    private fun startDecoderOutputProcessing() {
        CoroutineScope(Dispatchers.Default).launch {
            val audioChannelCount = audioFormat!!.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val audioFrameSize = audioChannelCount * 2
            val audioSampleRate = audioFormat!!.getInteger(MediaFormat.KEY_SAMPLE_RATE)

            Log.d(LOG_TAG_DECODE, "Decoder Spec: Channel Count - [$audioChannelCount] / Sample Rate - [$audioSampleRate]")

            val bufferInfo = MediaCodec.BufferInfo()
            val bufferSampleCount = audioClassifier!!.requiredInputBufferSize.toInt()
            val bufferSize = bufferSampleCount * audioChannelCount * 2

            val classifierBuffer = ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.nativeOrder())
            val shortBuffer = ShortArray(bufferSize)

            var currentTimeUs = 0L
            var decodedSampleCount = 0L

            while(true) {
                val outputIdx = audioDecoder!!.dequeueOutputBuffer(bufferInfo, 0)
                if(bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    Log.d(LOG_TAG_DECODE, "Decoder Output Done: EOS")
                    pcmChannel!!.close()
                    break
                }

                if(outputIdx >= 0) {
                    val outputBuffer = audioDecoder!!.getOutputBuffer(outputIdx) ?: continue
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                    // TODO: PCM Channel-Count & Sample-Rate 조정 필요
                    // YAMNet은 16kHz 1-ch 오디오를 처리하도록 되어있기에, 리샘플링이 필요함
                    // 일단 원본 PCM을 그대로 넣어도 정확도에 큰 문제가 발생하지는 않는 듯
                    while(outputBuffer.remaining() >= audioFrameSize) {
                        if(classifierBuffer.remaining() < audioFrameSize) {
                            classifierBuffer.flip()
                            classifierBuffer.asShortBuffer().get(shortBuffer, 0, classifierBuffer.limit() / 2)

                            pcmChannel!!.send(Pair(currentTimeUs, shortBuffer.copyOf(classifierBuffer.limit() / 2)))
                            classifierBuffer.clear()

                            decodedSampleCount += bufferSampleCount
                            currentTimeUs = (decodedSampleCount * 1_000_000) / audioSampleRate
                        }

                        repeat(audioChannelCount) {
                            classifierBuffer.putShort(outputBuffer.short)
                        }
                    }

                    audioDecoder!!.releaseOutputBuffer(outputIdx, false)
                }
            }
        }
    }

    private fun startClassifier() {
        CoroutineScope(Dispatchers.Default).launch {
            val audioSampleRate = audioFormat!!.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            while(true) {
                val curData = pcmChannel!!.receiveCatching().getOrNull()
                if(curData == null) break

                val currentTimeUs = curData.first
                val pcmData = curData.second

                val tensorAudio = audioClassifier!!.createInputTensorAudio()
                tensorAudio.load(pcmData)

                val classifyResult = audioClassifier!!.classify(tensorAudio)
                val speechCategory = classifyResult.firstOrNull()?.categories?.filter { humanSoundLabels.contains(it.label.lowercase()) }
                val segmentDurationUs = (pcmData.size.toLong() * 1_000_000) / audioSampleRate
                speechCategory?.forEach {
                    if(it.score >= CLASSIFY_THRESHOLD) {
                        val startTimeMs = currentTimeUs / 1000
                        val endTimeMs = (currentTimeUs + segmentDurationUs) / 1000
                        detectedRanges.add(Pair(startTimeMs, endTimeMs))

                        Log.d(LOG_TAG_CLASSIFY, "Classified [${it.displayName}(${it.label})]: ${startTimeMs}ms ~ ${endTimeMs}ms / Score: ${it.score}")
                    }
                }
            }

            onClassifyDone()
        }
    }

    private suspend fun onClassifyDone() {
        if(detectedRanges.isEmpty()) return

        val sortedRanges = detectedRanges.sortedBy { it.first }

        var currentRange = sortedRanges.first()
        for(nextRange in sortedRanges) {
            if(nextRange.first <= currentRange.second) {
                currentRange = Pair(min(currentRange.first, nextRange.first), max(currentRange.second, nextRange.second))
            } else {
                Log.d(LOG_TAG_CLASSIFY, "Classify Merged: ${currentRange.first}ms ~ ${currentRange.second}ms")

                withContext(Dispatchers.Main) {
                    layoutResult.addView(
                        TextView(this@MainActivity).apply {
                            text = String.format(Locale.getDefault(), "%.2fs - %.2fs", currentRange.first / 1000.0, currentRange.second / 1000.0)
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                        }
                    )
                }
                currentRange = nextRange
            }
        }

        Log.d(LOG_TAG_CLASSIFY, "Classify Done")
    }
}