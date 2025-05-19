package com.yong.soundanalyzer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioDecoder {
    companion object {
        // YAMNet Classify가 요구하는 Audio Sample Rate
        private const val CLASSIFY_BUFFER_COUNT = 15600
        private const val LOG_TAG_DECODE = "SoundAnalyzer_Decode"

        private const val MIMETYPE_AUDIO_PREFIX = "audio/"
    }

    // Decoder 관련 인스턴스
    private var mediaCodec: MediaCodec? = null
    private var mediaFormat: MediaFormat? = null
    private var mediaExtractor: MediaExtractor? = null

    // Decoder 초기화
    fun init(context: Context, uri: Uri): Boolean {
        Log.d(LOG_TAG_DECODE, "Decoder Initializing")

        // Audio Track 확인 및 유효성 검증
        val trackIdx = getAudioTrack(context, uri)
        if(trackIdx < 0) return false

        Log.d(LOG_TAG_DECODE, "Track Found : [$trackIdx]")

        // 기존에 해제되지 않은 Decoder가 있다면 종료
        if(mediaCodec != null) {
            mediaCodec!!.stop()
            mediaCodec!!.release()
            mediaCodec = null
        }

        // Audio Track에 맞춰 Decoder 생성
        mediaCodec = MediaCodec.createDecoderByType(mediaFormat!!.getString(MediaFormat.KEY_MIME)!!)
        if(mediaCodec == null) return false

        // Decoder 설정 및 시작
        mediaCodec!!.configure(mediaFormat, null, null, 0)
        mediaCodec!!.start()

        Log.d(LOG_TAG_DECODE, "Decoder Initialized")
        return true
    }

    // Audio Track 확인
    private fun getAudioTrack(context: Context, uri: Uri): Int {
        if(mediaExtractor != null) {
            mediaExtractor!!.release()
            mediaExtractor = null
        }

        // Extractor 생성
        mediaExtractor = MediaExtractor()
        if(mediaExtractor == null) return -1

        // Extractor에 URI로 Source 지정
        mediaExtractor!!.setDataSource(context, uri, null)
        for(i in 0 until mediaExtractor!!.trackCount) {
            // 현재 Track의 Format 확인
            val trackFormat = mediaExtractor!!.getTrackFormat(i)
            if(trackFormat.getString(MediaFormat.KEY_MIME)?.startsWith(MIMETYPE_AUDIO_PREFIX) == true) {
                // Audio Track인 경우 해당 Track 선택
                mediaFormat = trackFormat
                mediaExtractor!!.selectTrack(i)
                return i
            }
        }

        // Audio Track을 찾지 못한 경우
        return -1
    }

    // Decoder 시작
    fun startDecoder(pcmChannel: Channel<PcmData>) {
        // Decoder의 Input과 Output 처리를 각각 분리해 시작
        startDecoderInputProcessing()
        startDecoderOutputProcessing(pcmChannel)
    }

    // Decoder Input 처리 시작
    private fun startDecoderInputProcessing() {
        CoroutineScope(Dispatchers.Default).launch {
            Log.d(LOG_TAG_DECODE, "Decoder Input Starting")

            while(true) {
                // Input Buffer 확인
                val inputIdx = mediaCodec!!.dequeueInputBuffer(0)

                // Input Buffer가 유효한 경우에만 동작
                if(inputIdx >= 0) {
                    val inputBuffer = mediaCodec!!.getInputBuffer(inputIdx) ?: continue

                    // Audio Sample 확인
                    val sampleSize = mediaExtractor!!.readSampleData(inputBuffer, 0)
                    if(sampleSize < 0) {
                        // Audio Sample이 유효하지 않은 경우
                        // 즉. Audio가 끝난 경우 EOS Flag 전달 후 Decoder Input 처리 종료
                        Log.d(LOG_TAG_DECODE, "Decoder Input Done: EOS")
                        mediaCodec!!.queueInputBuffer(inputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        break
                    }

                    // Audio Sample Time 확인
                    val sampleTime = mediaExtractor!!.sampleTime
                    // Decoder에 Buffer 전달
                    mediaCodec!!.queueInputBuffer(inputIdx, 0, sampleSize, sampleTime, 0)

                    // Extractor를 다음 Sample로 이동
                    mediaExtractor!!.advance()
                }
            }
        }
    }

    // Decoder Output 처리 시작
    private fun startDecoderOutputProcessing(pcmChannel: Channel<PcmData>) {
        CoroutineScope(Dispatchers.Default).launch {
            Log.d(LOG_TAG_DECODE, "Decoder Output Starting")

            // Buffer 크기 계산을 위해 Audio 속성 확인
            val audioChannelCount = mediaFormat!!.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val audioFrameSize = audioChannelCount * 2
            val audioSampleRate = mediaFormat!!.getInteger(MediaFormat.KEY_SAMPLE_RATE)

            Log.d(LOG_TAG_DECODE, "Decoder Spec: Channel Count - [$audioChannelCount] / Sample Rate - [$audioSampleRate]")

            // Buffer 정보 확인 및 크기 계신
            val bufferInfo = MediaCodec.BufferInfo()
            val bufferSize = CLASSIFY_BUFFER_COUNT * audioFrameSize

            // Classifier에 전달하기 위한 Buffer
            val classifierBuffer = ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.nativeOrder())
            val shortBuffer = ShortArray(bufferSize)

            // 현재 SampleTime 계산을 위한 값
            var startSampleTimeUs = -1L
            var decodedSampleCount = 0L

            while(true) {
                // Output Buffer 확인
                val outputIdx = mediaCodec!!.dequeueOutputBuffer(bufferInfo, 0)

                // EOS Flag가 전달된 경우, Decoder Output 처리 종료
                if(bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    Log.d(LOG_TAG_DECODE, "Decoder Output Done: EOS")
                    pcmChannel.close()
                    break
                }

                // Output Buffer가 유효한 경우에만 동작
                if(outputIdx >= 0) {
                    val outputBuffer = mediaCodec!!.getOutputBuffer(outputIdx) ?: continue
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                    val curSampleTime = bufferInfo.presentationTimeUs
                    if(startSampleTimeUs == -1L && curSampleTime >= 0L) {
                        startSampleTimeUs = curSampleTime
                    }

                    // TODO: PCM Channel-Count & Sample-Rate 조정 필요
                    // YAMNet은 16kHz 1-ch 오디오를 처리하도록 되어있기에, 리샘플링이 필요함
                    // 일단 원본 PCM을 그대로 넣어도 정확도에 큰 문제가 발생하지는 않는 듯

                    // Classifier가 처리 가능한 Buffer 크기에 맞춰 Decoding 완료된 PCM 데이터를 나누어 전달
                    while(outputBuffer.remaining() >= audioFrameSize) {
                        // Classifier에 전달하기 위한 Buffer가 꽉 채워진 경우
                        if(classifierBuffer.remaining() < audioFrameSize) {
                            classifierBuffer.flip()
                            classifierBuffer.asShortBuffer().get(shortBuffer, 0, classifierBuffer.limit() / 2)

                            // Sample Time 계산
                            val currentSampleTimeUs = startSampleTimeUs + (decodedSampleCount * 1_000_000L) / audioSampleRate

                            // Channel을 통해 전달
                            val pcmArray = shortBuffer.copyOf(classifierBuffer.limit() / 2)
                            pcmChannel.send(PcmData(currentSampleTimeUs, pcmArray))
                            classifierBuffer.clear()

                            // 처리한 Sample 데이터 수를 기반으로 Sample Time 업데이트
                            decodedSampleCount += CLASSIFY_BUFFER_COUNT
                        }

                        // Channel 수만큼 PCM 데이터 연속 전달
                        repeat(audioChannelCount) {
                            classifierBuffer.putShort(outputBuffer.short)
                        }
                    }

                    // 처리 완료한 Buffer 해제
                    mediaCodec!!.releaseOutputBuffer(outputIdx, false)
                }
            }
        }
    }
}