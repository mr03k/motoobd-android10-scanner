package com.motoobd.scanner

/** Layout only. OEM identities, physical scaling and diagnostic conclusions are not inferred. */
object MonitorTestDecoder {
    data class Test(val ecu: String, val tid: Int, val cid: Int?, val mid: Int?, val scalingId: Int?,
                    val value: Int, val minimum: Int?, val maximum: Int?, val raw: List<Int>, val limitType: String)
    data class Result(val tests: List<Test>, val errors: List<String>)
    private fun word(bytes: List<Int>, offset: Int) = bytes[offset] * 256 + bytes[offset + 1]
    fun parse(raw: String, requestedId: Int, can: Boolean): Result {
        val tests = mutableListOf<Test>()
        val errors = mutableListOf<String>()
        val replies = ObdParser.frames(raw).filter { it.bytes.firstOrNull() == 0x46 }
        if (replies.isEmpty()) errors.add("No positive Mode 06 reply for %02X: %s".format(requestedId, raw.take(160)))
        if (ObdParser.hasNegativeReply(raw, 6)) errors.add("An ECU rejected Mode 06 request %02X.".format(requestedId))
        for (frame in replies) {
            val body = frame.bytes.drop(1)
            val width = if (can) 9 else 6
            if (body.isEmpty() || body.size % width != 0) {
                errors.add("${frame.ecu}: malformed ${if (can) "CAN" else "legacy"} monitor result; raw reply retained.")
                continue
            }
            for (record in body.chunked(width)) {
                if (record[0] != requestedId) { errors.add("Monitor reply identifier differs from request; raw reply retained."); continue }
                if (can) tests.add(Test(frame.ecu, record[1], null, record[0], record[2], word(record, 3), word(record, 5), word(record, 7), record, "both"))
                else {
                    val minimum = record[1] and 0x80 != 0
                    tests.add(Test(frame.ecu, record[0], record[1] and 0x7F, null, null, word(record, 2),
                        if (minimum) word(record, 4) else null, if (minimum) null else word(record, 4), record, if (minimum) "minimum" else "maximum"))
                }
            }
        }
        return Result(tests, errors)
    }
}
