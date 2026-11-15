package com.motoobd.scanner

data class ParameterReading(val pid: Int, val ecu: String, val bytes: List<Int>, val at: Long,
                            val error: String = "", val attemptedAt: Long = at, val failureRaw: String = "")

object ReadingHistory {
    fun failed(previous: ParameterReading?, pid: Int, ecu: String, raw: String, now: Long, error: String): ParameterReading =
        previous?.copy(error = error, attemptedAt = now, failureRaw = raw)
            ?: ParameterReading(pid, ecu, emptyList(), 0, error, now, raw)
}
