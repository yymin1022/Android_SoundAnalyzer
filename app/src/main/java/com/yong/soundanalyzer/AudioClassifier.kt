package com.yong.soundanalyzer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import kotlin.math.max
import kotlin.math.min

class AudioClassifier {
    companion object {
        // YAMNet Mode
        const val YAMNET_MODE_NORMAL = "YAMNET_MODE_NORMAL"
        const val YAMNET_MODE_QUALCOMM = "YAMNET_MODE_QUALCOMM"

        // YAMNet Classify가 요구하는 Audio Sample Rate
        private const val CLASSIFY_SAMPLE_RATE = 16000
        // YAMNet Classify 과정에서의 판단 기준 값
        private const val CLASSIFY_THRESHOLD = 0.5f

        private const val LOG_TAG_CLASSIFY = "SoundAnalyzer_Classify"

        // YAMNet Model File
        private const val TF_YAMNET_MODEL_FILENAME = "yamnet.tflite"
        private const val TF_YAMNET_QC_MODEL_FILENAME = "yamnet_qc.tflite"
    }

    interface Delegate {
        suspend fun onRangeComposed(range: Pair<Long, Long>)
    }

    // YAMNet Model에서 필터링하기 위한 Sound Label
    private val humanSoundLabels = setOf(
        "speech", "laughter", "cough", "baby_crying", "snoring",
        "gasp", "sneeze", "yell", "screaming", "crying_sobbing"
    )

    // Tensorflow Audio Classifier 인스턴스
    private var tfAudioClassifier: AudioClassifier? = null
    private val detectedRanges = mutableListOf<Pair<Long, Long>>()

    private var delegate: Delegate? = null

    // Classifier 초기화
    fun init(
        context: Context,
        mode: String,
        delegate: Delegate? = null
    ): Boolean {
        Log.d(LOG_TAG_CLASSIFY, "Classifier Initializing")
        this.delegate = delegate

        val yamnetModel = if(mode == YAMNET_MODE_QUALCOMM) TF_YAMNET_QC_MODEL_FILENAME
                                else TF_YAMNET_MODEL_FILENAME

        // Model File을 통해 YAMNet Classifier 생성
        tfAudioClassifier = AudioClassifier.createFromFile(context, yamnetModel)
        if(tfAudioClassifier == null) return false

        Log.d(LOG_TAG_CLASSIFY, "Classifier Initialized")
        return true
    }

    // Classifier 처리 시작
    fun startClassifier(
        pcmChannel: Channel<PcmData>
    ) {
        CoroutineScope(Dispatchers.Default).launch {
            Log.d(LOG_TAG_CLASSIFY, "Classifier Starting")

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
                    // 정해진 Label에 대해서 Category 필터링
                    ?.filter { humanSoundLabels.contains(it.label.lowercase()) }
                    // 정확도가 Threshold 값보다 높은 경우 필터링
                    ?.filter { it.score > CLASSIFY_THRESHOLD }

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
                delegate?.onRangeComposed(currentRange)

                currentRange = nextRange
            }
        }

        // 마지막 Range를 UI에 추가
        delegate?.onRangeComposed(currentRange)
    }
}