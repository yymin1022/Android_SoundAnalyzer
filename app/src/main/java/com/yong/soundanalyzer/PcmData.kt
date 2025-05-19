package com.yong.soundanalyzer

data class PcmData(
    val sampleTime: Long,
    val pcmArray: ShortArray
) {
    override fun equals(other: Any?): Boolean {
        if(this === other) return true
        if(javaClass != other?.javaClass) return false

        other as PcmData

        if(sampleTime != other.sampleTime) return false
        if(!pcmArray.contentEquals(other.pcmArray)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = sampleTime.hashCode()
        result = 31 * result + pcmArray.contentHashCode()
        return result
    }
}
