package com.motoobd.scanner

/** Reads each advertised support page once, retaining attribution and missing-page uncertainty. */
object CapabilityDiscovery {
    data class Result(val supported: Map<String, Set<Int>>, val ranges: Map<String, Set<Int>>, val errors: List<String>)
    fun discover(mode: Int, query: (String) -> String): Result {
        require(mode in listOf(1, 2, 9))
        val supported = linkedMapOf<String, MutableSet<Int>>()
        val ranges = linkedMapOf<String, MutableSet<Int>>()
        val errors = mutableListOf<String>()
        var expected = emptySet<String>()
        for (base in 0..0xE0 step 0x20) {
            val raw = query("%02X%02X".format(mode, base) + if (mode == 2) "00" else "")
            if (ObdParser.hasNegativeReply(raw, mode)) errors.add("Mode %02X support page %02X included a negative ECU reply.".format(mode, base))
            val offset = if (mode == 2) 3 else 2
            val replies = ObdParser.frames(raw).filter { frame ->
                frame.bytes.size >= offset + 4 && frame.bytes[0] == 0x40 + mode && frame.bytes[1] == base && (mode != 2 || frame.bytes[2] == 0)
            }
            if (replies.isEmpty()) errors.add("Mode %02X support page %02X unavailable: %s".format(mode, base, raw.take(100)))
            val responding = replies.map { it.ecu }.toSet()
            (expected - responding).forEach { errors.add("$it did not answer Mode %02X support page %02X".format(mode, base)) }
            val continuation = mutableSetOf<String>()
            for (reply in replies) {
                val advertised = PidCatalogue.supported(base, reply.bytes.drop(offset))
                supported.getOrPut(reply.ecu) { mutableSetOf() }.addAll(advertised)
                ranges.getOrPut(reply.ecu) { mutableSetOf() }.add(base)
                if (base + 32 in advertised) continuation.add(reply.ecu)
            }
            if (continuation.isEmpty()) break
            expected = continuation
        }
        return Result(supported.mapValues { it.value.toSet() }, ranges.mapValues { it.value.toSet() }, errors)
    }
    fun pollOrder(supported: Map<String, Set<Int>>): List<Int> = supported.values.flatten().distinct().filter { it % 32 != 0 }.sorted()
}
