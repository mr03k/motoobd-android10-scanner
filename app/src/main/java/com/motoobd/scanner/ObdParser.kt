package com.motoobd.scanner

/** Parses ELM text without joining unrelated ECU responses or adapter status text. */
object ObdParser {
    private val hexLine = Regex("^[0-9A-F ]+$")
    private val canHeader = Regex("^(?:[0-9A-F]{3}|[0-9A-F]{8})$")

    data class Frame(val ecu: String, val bytes: List<Int>)
    fun payloads(raw: String): List<List<Int>> = frames(raw).map { it.bytes }

    fun frames(raw: String): List<Frame> {
        val result = mutableListOf<Frame>()
        val segmented = linkedMapOf<String, MutableList<Int>>()
        val lengths = mutableMapOf<String, Int>()
        val sequences = mutableMapOf<String, Int>()
        var numbered: MutableList<Int>? = null
        for (source in raw.uppercase().replace('>', '\n').split('\r', '\n')) {
            var line = source.trim()
            if (line.isEmpty()) continue
            val label = Regex("^([0-9A-F]+):\\s*").find(line)
            if (label != null) {
                line = line.substring(label.value.length)
                if (label.groupValues[1] == "0") {
                    numbered = mutableListOf()
                    result.add(Frame("unidentified", numbered))
                }
            }
            if (!hexLine.matches(line)) continue
            val tokens = line.split(Regex(" +"))
            var ecu: String? = null
            var data = line.replace(" ", "")
            if (tokens.size > 1 && canHeader.matches(tokens.first())) {
                ecu = tokens.first()
                var body = tokens.drop(1)
                // Some adapters include a single-digit DLC before the PCI byte.
                if (body.firstOrNull()?.length == 1) body = body.drop(1)
                data = body.joinToString("")
            }
            if (data.length % 2 != 0 || data.length < 2) continue
            val bytes = data.chunked(2).map { it.toInt(16) }
            if (ecu != null) {
                when (bytes[0] shr 4) {
                    0 -> {
                        val length = bytes[0] and 15
                        if (bytes.size >= length + 1) result.add(Frame(ecu, bytes.drop(1).take(length)))
                    }
                    1 -> if (bytes.size >= 2) {
                        lengths[ecu] = ((bytes[0] and 15) shl 8) or bytes[1]
                        segmented[ecu] = bytes.drop(2).toMutableList()
                        sequences[ecu] = 1
                    }
                    2 -> {
                        val frame = segmented[ecu] ?: continue
                        if ((bytes[0] and 15) != sequences[ecu]) {
                            segmented.remove(ecu)
                            continue
                        }
                        frame.addAll(bytes.drop(1))
                        sequences[ecu] = ((sequences[ecu] ?: 1) + 1) and 15
                    }
                    else -> result.add(Frame(ecu, bytes))
                }
            } else if (label != null && numbered != null) {
                numbered.addAll(bytes)
            } else {
                // A 3-digit ELM message length and command echoes are not replies.
                if (bytes.first() in 0x40..0x7F) result.add(Frame("unidentified", bytes))
            }
        }
        for ((ecu, bytes) in segmented) {
            val length = lengths[ecu] ?: continue
            if (bytes.size >= length) result.add(Frame(ecu, bytes.take(length)))
        }
        return result
    }

    fun pid(raw: String, pid: Int, length: Int): List<Int>? =
        payloads(raw).firstOrNull { it.size >= length + 2 && it[0] == 0x41 && it[1] == pid }
            ?.drop(2)?.take(length)

    fun hasReply(raw: String, mode: Int): Boolean = payloads(raw).any { it.firstOrNull() == mode }

    fun hasNegativeReply(raw: String, requestedMode: Int): Boolean = payloads(raw)
        .any { it.size >= 3 && it[0] == 0x7F && it[1] == requestedMode && it[2] != 0x78 }

    fun validDtcReply(raw: String, mode: Int, counted: Boolean): Boolean {
        if (hasNegativeReply(raw, mode - 0x40)) return false
        val replies = payloads(raw).filter { it.firstOrNull() == mode }
        return replies.isNotEmpty() && replies.all {
            val tail = it.drop(1)
            if (counted) tail.isNotEmpty() && tail[0] * 2 <= tail.size - 1 &&
                tail.drop(1 + tail[0] * 2).all { byte -> byte == 0 } &&
                tail.drop(1).take(tail[0] * 2).chunked(2).all { pair -> !pair.all { byte -> byte == 0 } && !pair.all { byte -> byte == 255 } }
            else tail.size % 2 == 0
        }
    }

    fun dtcs(raw: String, mode: Int, counted: Boolean? = null): List<String> = payloads(raw)
        .filter { it.firstOrNull() == mode }
        .flatMap { payload ->
            val tail = payload.drop(1)
            // CAN replies carry a DTC count; legacy replies contain pairs directly.
            val bytes = if (counted == true || (counted == null && tail.size % 2 == 1)) {
                val count = tail.firstOrNull() ?: 0
                if (count * 2 > tail.size - 1) emptyList() else tail.drop(1).take(count * 2)
            } else tail
            bytes.chunked(2).mapNotNull { pair ->
                if (pair.size != 2 || pair.all { it == 0 } || pair.all { it == 255 }) null
                else "PCBU"[pair[0] shr 6].toString() + ((pair[0] shr 4) and 3) +
                    "%01X%02X".format(pair[0] and 15, pair[1])
            }
        }.distinct()

    fun voltage(raw: String): Double? = Regex("(?i)(?:^|[\\r\\n])\\s*([0-9]+(?:\\.[0-9]+)?)\\s*V\\s*(?:$|[\\r\\n>])")
        .find(raw)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 0.0..40.0 }
}
