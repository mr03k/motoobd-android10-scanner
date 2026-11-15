package com.motoobd.scanner

object DiagnosticDecoder {
    data class Monitor(val name: String, val supported: Boolean, val complete: Boolean?)
    data class Readiness(val mil: Boolean, val dtcCount: Int, val ignitionType: String, val monitors: List<Monitor>)
    fun readiness(bytes: List<Int>): Readiness? {
        if (bytes.size < 4) return null
        val compression = bytes[1] and 8 != 0
        val monitors = mutableListOf<Monitor>()
        listOf("Misfire", "Fuel system", "Comprehensive components").forEachIndexed { i, name ->
            val supported = bytes[1] and (1 shl i) != 0
            monitors.add(Monitor(name, supported, if (supported) bytes[1] and (1 shl (i + 4)) == 0 else null))
        }
        val names = if (compression) listOf("NMHC catalyst", "NOx/SCR", "Reserved", "Boost pressure", "Reserved", "Exhaust gas sensor", "Particulate filter", "EGR/VVT")
            else listOf("Catalyst", "Heated catalyst", "Evaporative system", "Secondary air", "Monitor bit 4 (standard-dependent)", "Oxygen sensor", "Oxygen sensor heater", "EGR/VVT")
        names.forEachIndexed { i, name -> if (name != "Reserved") {
            val supported = bytes[2] and (1 shl i) != 0
            monitors.add(Monitor(name, supported, if (supported) bytes[3] and (1 shl i) == 0 else null))
        } }
        return Readiness(bytes[0] and 0x80 != 0, bytes[0] and 0x7F, if (compression) "Compression ignition" else "Spark ignition", monitors)
    }

    fun vehicleInfoName(pid: Int): String = when (pid) {
        1 -> "VIN message count"; 2 -> "Vehicle identification number (VIN)"
        3 -> "Calibration ID message count"; 4 -> "Calibration identification"
        5 -> "Calibration verification message count"; 6 -> "Calibration verification numbers (CVN)"
        7 -> "In-use performance message count"; 8 -> "In-use performance tracking (spark ignition)"
        9 -> "ECU name message count"; 10 -> "ECU name"
        11 -> "In-use performance tracking (compression ignition)"
        else -> "Vehicle information PID %02X".format(pid)
    }

    /** Only decode individually reassembled CAN records, never concatenate unrelated responders. */
    fun vehicleInfo(pid: Int, bytes: List<Int>, identifiedCan: Boolean): String? {
        if (!identifiedCan || bytes.isEmpty()) return null
        val body = bytes.drop(1) // CAN number-of-data-items byte
        if (pid == 2) {
            val text = body.filter { it != 0 }.map { it.toChar() }.joinToString("")
            return text.takeIf { it.length == 17 && it.all { ch -> ch in '0'..'9' || ch in 'A'..'Z' && ch !in "IOQ" } }
        }
        if (pid == 4 || pid == 10) {
            if (body.any { it != 0 && it !in 32..126 }) return null
            return body.map { if (it == 0) '\n' else it.toChar() }.joinToString("").trim().takeIf { it.isNotEmpty() }
        }
        if (pid == 6 && body.size % 4 == 0) return body.chunked(4).joinToString(" / ") { PidCatalogue.rawHex(it) }
        return null
    }
}
