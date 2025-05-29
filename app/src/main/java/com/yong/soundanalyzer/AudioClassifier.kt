package com.yong.soundanalyzer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import org.tensorflow.lite.task.core.BaseOptions
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class AudioClassifier {
    companion object {
        // YAMNet Classify가 요구하는 Audio Sample Rate
        private const val CLASSIFY_SAMPLE_RATE = 16000
        // YAMNet Classify 과정에서의 판단 기준 값
        private const val CLASSIFY_THRESHOLD = 0.5f

        private const val LOG_TAG_CLASSIFY = "SoundAnalyzer_Classify"

        private const val TF_YAMNET_MODEL_FILENAME = "yamnet.tflite"

        const val TF_MODE_GPU = "MODE_GPU"
        const val TF_MODE_NNAPI = "MODE_NNAPI"
        const val TF_MODE_NORMAL = "MODE_NORMAL"
    }

    interface Delegate {
        fun addToUI(range: Pair<Long, Long>)
        fun onError()
        fun onFinish()
    }

    // YAMNet Model에서 필터링하기 위한 Sound Label
    private val humanSoundLabels = setOf(
        "Speech", "Shout", "Yell", "Children shouting", "Babbling",
        "Chatter", "Rapping", "Whispering", "Crowd", "Laughter",
        "Giggle", "Snicker", "Belly laugh", "Baby laughter", "Crying, sobbing",
        "Baby cry, infant cry", "Sigh", "Gasp", "Breathing", "Cough",
        "Sneeze", "Hiccup", "Gargling", "Choir", "Humming",
        "Chewing, mastication", "Biting", "Burping, eructation", "Whistling", "Snoring"
    )

    // Tensorflow Audio Classifier 인스턴스
    private var tfAudioClassifier: AudioClassifier? = null
    private val detectedRanges = mutableListOf<Pair<Long, Long>>()

    // Classifier 동작을 위한 Scope
    private var tfDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var isReady = false

    private var delegate: Delegate? = null

    // Classifier 초기화
    fun init(
        context: Context,
        mode: String,
        delegate: Delegate? = null
    ): Boolean {
        Log.d(LOG_TAG_CLASSIFY, "Classifier Initializing (Mode $mode)")
        this.delegate = delegate

        CoroutineScope(tfDispatcher).launch {
            // Model File을 통해 YAMNet Classifier 생성
            try {
                // Tensorflow 기본 Option 구성 (GPU / NNAPI Delegate 설정을 위함)
                val baseOption = BaseOptions.builder()
                // GPU 또는 NNAPI Mode인 경우 활성화
                if(mode == TF_MODE_GPU) baseOption.useGpu()
                if(mode == TF_MODE_NNAPI) baseOption.useNnapi()

                // Classifier Option 구성
                val classifierOption = AudioClassifier.AudioClassifierOptions.builder()
                    .setBaseOptions(baseOption.build())
                    // 정해진 Label에 대해서 Category 필터링
                    .setLabelAllowList(humanSoundLabels.toList())
                    // 정확도가 Threshold 값보다 높은 경우 필터링
                    .setScoreThreshold(CLASSIFY_THRESHOLD)
                    .build()

                tfAudioClassifier = AudioClassifier.createFromFileAndOptions(context, TF_YAMNET_MODEL_FILENAME, classifierOption)
                if(tfAudioClassifier == null) return@launch

                isReady = true
            } catch(e: IOException) {
                Log.e(LOG_TAG_CLASSIFY, "Classifier Initialize Error: [${e.toString()}]")
            }
        }

        Log.d(LOG_TAG_CLASSIFY, "Classifier Initialized")
        return true
    }

    fun destroy() {
        isReady = false
        tfAudioClassifier?.close()
        tfAudioClassifier = null
    }

    // Classifier 처리 시작
    fun startClassifier(
        pcmChannel: Channel<PcmData>
    ) {
        CoroutineScope(tfDispatcher).launch {
            Log.d(LOG_TAG_CLASSIFY, "Classifier Starting")
            while(!isReady) delay(50)

            while(true) {
                // Channel로부터 처리할 데이터 확인
                val curData = pcmChannel.receiveCatching().getOrNull()
                // Channel이 유효하지 않은 경우
                // 즉, Audio가 끝난 경우
                if(curData == null) break

                // 현재 데이터의 Sample Time 및 PCM 데이터
                val currentTimeUs = curData.sampleTime
                val pcmData = curData.pcmArray

                // Classifier 속성으로 Tensor Audio 인스턴스 생성
                val tensorAudio = tfAudioClassifier!!.createInputTensorAudio()
                // Tensor Audio에 PCM 데이터 전달
                tensorAudio.load(pcmData)

                // Tensor Audio 처리 결과 확인
                val classifyResult = tfAudioClassifier!!.classify(tensorAudio)
                val speechCategory = classifyResult.firstOrNull()?.categories

                // SampleRate를 기반으로 현재 PCM 데이터의 Duration 확인
                val segmentDurationUs = (pcmData.size.toLong() * 1_000_000) / CLASSIFY_SAMPLE_RATE
                // 각 Category를 TimeRange와 함께 Detected List에 추가
                speechCategory?.forEach {
                    val startTimeMs = currentTimeUs / 1000
                    val endTimeMs = (currentTimeUs + segmentDurationUs) / 1000
                    detectedRanges.add(Pair(startTimeMs, endTimeMs))

                    Log.d(LOG_TAG_CLASSIFY, "Classified [${it.label}]: ${startTimeMs}ms ~ ${endTimeMs}ms / Score: ${it.score}")
                }
            }

            Log.d(LOG_TAG_CLASSIFY, "Classifier Done: [${detectedRanges.size}]")
            // Classify 결과 후처리
            mergeClassifyResults(detectedRanges)

            destroy()
            withContext(Dispatchers.Main) { delegate?.onFinish() }
        }
    }

    // Classify 결과 후처리
    private suspend fun mergeClassifyResults(detectedRanges: List<Pair<Long, Long>>) {
        // 처리된 결과가 비어있는 경우 종료
        if(detectedRanges.isEmpty()) return

        // 처리된 결과를 TimeRange 기준으로 정렬
        val sortedRanges = detectedRanges.sortedBy { it.first }

        // 시작/종료 시간이 겹치는 TimeRange들을 하나로 합치기 위한 For 구문
        var currentRange = sortedRanges.first()
        for(nextRange in sortedRanges) {
            if(nextRange.first <= currentRange.second) {
                currentRange = Pair(min(currentRange.first, nextRange.first), max(currentRange.second, nextRange.second))
            } else {
                // 하나의 TimeRange가 완성되었다면 UI에 추가
                withContext(Dispatchers.Main) { delegate?.addToUI(currentRange) }
                currentRange = nextRange
            }
        }

        // 마지막 Range를 UI에 추가
        withContext(Dispatchers.Main) { delegate?.addToUI(currentRange) }
    }
}