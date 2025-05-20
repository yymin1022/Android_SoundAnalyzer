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
        // YAMNet Classify 과정에서의 판단 기준 값
        private const val CLASSIFY_THRESHOLD = 0.1f

        private const val LOG_TAG_CLASSIFY = "SoundAnalyzer_Classify"
        private const val LOG_TAG_DECODE = "SoundAnalyzer_Decode"
        private const val LOG_TAG_PROCESS = "SoundAnalyzer_Process"
        private const val LOG_TAG_SELECT = "SoundAnalyzer_Select"

        private const val MIMETYPE_AUDIO_PREFIX = "audio/"
        private const val MIMETYPE_AUDIO = "audio/*"
        private const val MIMETYPE_VIDEO = "video/*"
    }

    // UI Elements
    private lateinit var btnSelect: Button
    private lateinit var layoutResult: LinearLayout
    private lateinit var tvAudioPath: TextView

    // Tensorflow Lite Classifier 인스턴스
    private var audioClassifier: AudioClassifier? = null

    // Decoder 관련 인스턴스
    private var audioDecoder: MediaCodec? = null
    private var audioFormat: MediaFormat? = null
    private var mediaExtractor: MediaExtractor? = null

    // Decoding 결과 데이터를 처리하기 위한 Channel 및 List
    private var pcmChannel: Channel<Pair<Long, ShortArray>>? = null
    private val detectedRanges = mutableListOf<Pair<Long, Long>>()

    // YAMNet Model에서 필터링하기 위한 Sound Label
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

        // UI Initialization
        initUI()
        initEventListener()
    }

    // UI View Initialization
    private fun initUI() {
        btnSelect = findViewById(R.id.main_btn_select)
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
            // Audio 처리 과정 시작
            startAudioProcessing(it)
        }
    }

    // Audio 처리 과정 시작
    private fun startAudioProcessing(uri: Uri) {
        Log.d(LOG_TAG_PROCESS, "Process Started: $uri")

        // 전체 Flow는 다음과 같음
        // Decoding -> [PCM Data] -> Classify w/ Tensorflow Lite YAMNet -> [Result Data]

        // Classifier 및 Decoder 초기화
        var isInitialized = initClassifier() && initDecoder(uri)
        if(!isInitialized) return

        // 데이터 전달을 위한 Channel 초기화
        pcmChannel = Channel<Pair<Long, ShortArray>>()

        // Classifier 및 Decoder 시작
        startClassifier()
        startDecoder()
    }

    // Classifier 초기화
    private fun initClassifier(): Boolean {
        Log.d(LOG_TAG_CLASSIFY, "Classifier Initializing")

        // Model File을 통해 YAMNet Classifier 생성
        audioClassifier = AudioClassifier.createFromFile(this, "yamnet.tflite")
        if(audioClassifier == null) return false

        Log.d(LOG_TAG_CLASSIFY, "Classifier Initialized")
        return true
    }

    // Decoder 초기화
    private fun initDecoder(uri: Uri): Boolean {
        Log.d(LOG_TAG_DECODE, "Decoder Initializing")

        // Audio Track 확인 및 유효성 검증
        val trackIdx = getAudioTrack(uri)
        if(trackIdx < 0) return false

        Log.d(LOG_TAG_DECODE, "Track Found : [$trackIdx]")

        // 기존에 해제되지 않은 Decoder가 있다면 종료
        if(audioDecoder != null) {
            audioDecoder!!.stop()
            audioDecoder!!.release()
            audioDecoder = null
        }

        // Audio Track에 맞춰 Decoder 생성 
        audioDecoder = MediaCodec.createDecoderByType(audioFormat!!.getString(MediaFormat.KEY_MIME)!!)
        if(audioDecoder == null) return false

        // Decoder 설정 및 시작
        audioDecoder!!.configure(audioFormat, null, null, 0)
        audioDecoder!!.start()

        Log.d(LOG_TAG_DECODE, "Decoder Initialized")
        return true
    }

    // Audio Track 확인
    private fun getAudioTrack(uri: Uri): Int {
        if(mediaExtractor != null) {
            mediaExtractor!!.release()
            mediaExtractor = null
        }

        // Extractor 생성
        mediaExtractor = MediaExtractor()
        if(mediaExtractor == null) return -1

        // Extractor에 URI로 Source 지정
        mediaExtractor!!.setDataSource(this@MainActivity, uri, null)
        for(i in 0 until mediaExtractor!!.trackCount) {
            // 현재 Track의 Format 확인
            val trackFormat = mediaExtractor!!.getTrackFormat(i)
            if(trackFormat.getString(MediaFormat.KEY_MIME)?.startsWith(MIMETYPE_AUDIO_PREFIX) == true) {
                // Audio Track인 경우 해당 Track 선택
                audioFormat = trackFormat
                mediaExtractor!!.selectTrack(i)
                return i
            }
        }

        // Audio Track을 찾지 못한 경우
        return -1
    }

    // Decoder 시작
    private fun startDecoder() {
        // Decoder의 Input과 Output 처리를 각각 분리해 시작
        startDecoderInputProcessing()
        startDecoderOutputProcessing()
    }

    // Decoder Input 처리 시작
    private fun startDecoderInputProcessing() {
        CoroutineScope(Dispatchers.Default).launch {
            Log.d(LOG_TAG_CLASSIFY, "Decoder Input Starting")

            while(true) {
                // Input Buffer 확인
                val inputIdx = audioDecoder!!.dequeueInputBuffer(0)

                // Input Buffer가 유효한 경우에만 동작
                if(inputIdx >= 0) {
                    val inputBuffer = audioDecoder!!.getInputBuffer(inputIdx) ?: continue

                    // Audio Sample 확인
                    val sampleSize = mediaExtractor!!.readSampleData(inputBuffer, 0)
                    if(sampleSize < 0) {
                        // Audio Sample이 유효하지 않은 경우
                        // 즉. Audio가 끝난 경우 EOS Flag 전달 후 Decoder Input 처리 종료
                        Log.d(LOG_TAG_DECODE, "Decoder Input Done: EOS")
                        audioDecoder!!.queueInputBuffer(inputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        break
                    }

                    // Audio Sample Time 확인
                    val sampleTime = mediaExtractor!!.sampleTime
                    // Decoder에 Buffer 전달
                    audioDecoder!!.queueInputBuffer(inputIdx, 0, sampleSize, sampleTime, 0)

                    // Extractor를 다음 Sample로 이동
                    mediaExtractor!!.advance()
                }
            }
        }
    }

    // Decoder Output 처리 시작
    private fun startDecoderOutputProcessing() {
        CoroutineScope(Dispatchers.Default).launch {
            Log.d(LOG_TAG_CLASSIFY, "Decoder Output Starting")

            // Buffer 크기 계산을 위해 Audio 속성 확인
            val audioChannelCount = audioFormat!!.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val audioFrameSize = audioChannelCount * 2
            val audioSampleRate = audioFormat!!.getInteger(MediaFormat.KEY_SAMPLE_RATE)

            Log.d(LOG_TAG_DECODE, "Decoder Spec: Channel Count - [$audioChannelCount] / Sample Rate - [$audioSampleRate]")

            // Buffer 정보 확인 및 크기 계신
            val bufferInfo = MediaCodec.BufferInfo()
            val bufferSampleCount = audioClassifier!!.requiredInputBufferSize.toInt()
            val bufferSize = bufferSampleCount * audioChannelCount * 2

            // Classifier에 전달하기 위한 Buffer
            val classifierBuffer = ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.nativeOrder())
            val shortBuffer = ShortArray(bufferSize)

            // 현재 SampleTime 계산을 위한 값
            var currentTimeUs = 0L
            var decodedSampleCount = 0L

            while(true) {
                // Output Buffer 확인
                val outputIdx = audioDecoder!!.dequeueOutputBuffer(bufferInfo, 0)
                
                // EOS Flag가 전달된 경우, Decoder Output 처리 종료
                if(bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    Log.d(LOG_TAG_DECODE, "Decoder Output Done: EOS")
                    pcmChannel!!.close()
                    break
                }

                // Output Buffer가 유효한 경우에만 동작
                if(outputIdx >= 0) {
                    val outputBuffer = audioDecoder!!.getOutputBuffer(outputIdx) ?: continue
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                    // TODO: PCM Channel-Count & Sample-Rate 조정 필요
                    // YAMNet은 16kHz 1-ch 오디오를 처리하도록 되어있기에, 리샘플링이 필요함
                    // 일단 원본 PCM을 그대로 넣어도 정확도에 큰 문제가 발생하지는 않는 듯
                    
                    // Classifier가 처리 가능한 Buffer 크기에 맞춰 Decoding 완료된 PCM 데이터를 나누어 전달
                    while(outputBuffer.remaining() >= audioFrameSize) {
                        // Classifier에 전달하기 위한 Buffer가 꽉 채워진 경우
                        if(classifierBuffer.remaining() < audioFrameSize) {
                            classifierBuffer.flip()
                            classifierBuffer.asShortBuffer().get(shortBuffer, 0, classifierBuffer.limit() / 2)

                            // Channel을 통해 전달
                            pcmChannel!!.send(Pair(currentTimeUs, shortBuffer.copyOf(classifierBuffer.limit() / 2)))
                            classifierBuffer.clear()

                            // 처리한 Sample 데이터 수를 기반으로 Sample Time 업데이트
                            decodedSampleCount += bufferSampleCount
                            currentTimeUs = (decodedSampleCount * 1_000_000) / audioSampleRate
                        }

                        // Channel 수만큼 PCM 데이터 연속 전달
                        repeat(audioChannelCount) {
                            classifierBuffer.putShort(outputBuffer.short)
                        }
                    }

                    // 처리 완료한 Buffer 해제
                    audioDecoder!!.releaseOutputBuffer(outputIdx, false)
                }
            }
        }
    }

    // Classifier 처리 시작
    private fun startClassifier() {
        CoroutineScope(Dispatchers.Default).launch {
            Log.d(LOG_TAG_CLASSIFY, "Classifier Starting")

            // Audio SampleRate 속성 확인
            val audioSampleRate = audioFormat!!.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            
            while(true) {
                Log.d(LOG_TAG_CLASSIFY, "Classifier Waiting for data")
                // Channel로부터 처리할 데이터 확인
                val curData = pcmChannel!!.receiveCatching().getOrNull()
                // Channel이 유효하지 않은 경우
                // 즉, Audio가 끝난 경우
                if(curData == null) break

                // 현재 데이터의 Sample Time 및 PCM 데이터
                val currentTimeUs = curData.first
                val pcmData = curData.second
                Log.d(LOG_TAG_CLASSIFY, "Classifier got data: $currentTimeUs / (${pcmData.size})${pcmData.toList().subList(0, 10)}...")

                // Classifier 속성으로 Tensor Audio 인스턴스 생성
                val tensorAudio = audioClassifier!!.createInputTensorAudio()
                // Tensor Audio에 PCM 데이터 전달
                tensorAudio.load(pcmData)

                // Tensor Audio 처리 결과 확인
                val classifyResult = audioClassifier!!.classify(tensorAudio)
                val speechCategory = classifyResult.firstOrNull()?.categories
                    // 정해진 Label에 대해서 Category 필터링
                    ?.filter { humanSoundLabels.contains(it.label.lowercase()) }
                    // 정확도가 Threshold 값보다 높은 경우 필터링
                    ?.filter { it.score >= CLASSIFY_THRESHOLD }

                // SampleRate를 기반으로 현재 PCM 데이터의 Duration 확인
                val segmentDurationUs = (pcmData.size.toLong() * 1_000_000) / audioSampleRate
                // 각 Category를 TimeRange와 함께 Detected List에 추가
                speechCategory?.forEach {
                    val startTimeMs = currentTimeUs / 1000
                    val endTimeMs = (currentTimeUs + segmentDurationUs) / 1000
                    detectedRanges.add(Pair(startTimeMs, endTimeMs))

                    Log.d(LOG_TAG_CLASSIFY, "Classified [${it.displayName}(${it.label})]: ${startTimeMs}ms ~ ${endTimeMs}ms / Score: ${it.score}")
                }
            }

            Log.d(LOG_TAG_CLASSIFY, "Classifier Done")
            // Classify 결과 후처리
            onClassifyDone()
        }
    }

    // Classify 결과 후처리
    private suspend fun onClassifyDone() {
        // 처리된 결과가 비어있는 경우 종료
        if(detectedRanges.isEmpty()) return

        Log.d(LOG_TAG_CLASSIFY, "Classify Merge Starting")

        // 처리된 결과를 TimeRange 기준으로 정렬
        val sortedRanges = detectedRanges.sortedBy { it.first }

        // 시작/종료 시간이 겹치는 TimeRange들을 하나로 합치기 위한 For 구문
        var currentRange = sortedRanges.first()
        for(nextRange in sortedRanges) {
            if(nextRange.first <= currentRange.second) {
                currentRange = Pair(min(currentRange.first, nextRange.first), max(currentRange.second, nextRange.second))
            } else {
                // 하나의 TimeRange가 완성되었다면 UI에 추가
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

        Log.d(LOG_TAG_CLASSIFY, "Classify Merge Done")
    }
}