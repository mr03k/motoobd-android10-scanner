package com.motoobd.scanner

/** Conservative SAE J1979 Mode 01 decoders; unimplemented/encoded PIDs retain their bytes. */
object PidCatalogue {
    data class Definition(val pid: Int, val name: String, val unit: String = "", val size: Int = 0,
                          val decode: ((List<Int>) -> Any)? = null)
    private fun word(b: List<Int>, i: Int = 0) = b[i] * 256 + b[i + 1]
    private fun hex(b: List<Int>) = b.joinToString(" ") { "%02X".format(it) }
    private val definitions = linkedMapOf<Int, Definition>()
    private fun scalar(pid: Int, name: String, unit: String, size: Int = 1, decoder: (List<Int>) -> Any) {
        definitions[pid] = Definition(pid, name, unit, size, decoder)
    }
    private fun raw(pid: Int, name: String) { definitions[pid] = Definition(pid, name) }
    init {
        raw(1, "Monitor status since codes cleared")
        scalar(2, "Freeze-frame trigger code", "", 2) { b ->
            if (b.all { it == 0 }) "None" else "PCBU"[b[0] shr 6].toString() + ((b[0] shr 4) and 3) + "%01X%02X".format(b[0] and 15, b[1])
        }
        scalar(3, "Fuel system status", "", 2) { b -> b.mapIndexed { i, value ->
            "System ${i + 1}: " + when (value) { 0 -> "Not available"; 1 -> "Open loop (insufficient temperature)"; 2 -> "Closed loop"; 4 -> "Open loop (load/deceleration)"; 8 -> "Open loop (fault)"; 16 -> "Closed loop (feedback fault)"; else -> "Raw 0x%02X".format(value) }
        }.joinToString("; ") }
        scalar(4, "Calculated engine load", "%") { it[0] * 100.0 / 255 }
        scalar(5, "Coolant temperature", "°C") { it[0] - 40 }
        for ((pid, name) in listOf(6 to "Short-term fuel trim bank 1", 7 to "Long-term fuel trim bank 1", 8 to "Short-term fuel trim bank 2", 9 to "Long-term fuel trim bank 2")) scalar(pid, name, "%") { it[0] * 100.0 / 128 - 100 }
        scalar(0x0A, "Fuel pressure (gauge)", "kPa") { it[0] * 3 }
        scalar(0x0B, "Intake manifold absolute pressure", "kPa") { it[0] }
        scalar(0x0C, "Engine speed", "rpm", 2) { word(it) / 4.0 }
        scalar(0x0D, "Vehicle speed", "km/h") { it[0] }
        scalar(0x0E, "Timing advance", "°") { it[0] / 2.0 - 64 }
        scalar(0x0F, "Intake air temperature", "°C") { it[0] - 40 }
        scalar(0x10, "Mass air flow", "g/s", 2) { word(it) / 100.0 }
        scalar(0x11, "Throttle position", "%") { it[0] * 100.0 / 255 }
        raw(0x12, "Commanded secondary air status")
        raw(0x13, "Oxygen sensors present (two banks)")
        for (pid in 0x14..0x1B) scalar(pid, "Oxygen sensor ${pid - 0x13} voltage / trim", "", 2) {
            "${it[0] / 200.0} V; " + if (it[1] == 255) "trim unavailable" else "${it[1] * 100.0 / 128 - 100} % trim"
        }
        raw(0x1C, "OBD standards compliance")
        raw(0x1D, "Oxygen sensors present (four banks)")
        scalar(0x1E, "Auxiliary input status", "") { if (it[0] and 1 != 0) "PTO active" else "PTO inactive" }
        scalar(0x1F, "Engine run time", "s", 2) { word(it) }
        scalar(0x21, "Distance with MIL on", "km", 2) { word(it) }
        scalar(0x22, "Fuel rail pressure relative to manifold", "kPa", 2) { word(it) * 0.079 }
        scalar(0x23, "Fuel rail gauge pressure", "kPa", 2) { word(it) * 10 }
        for (pid in 0x24..0x2B) scalar(pid, "Wideband oxygen sensor ${pid - 0x23} ratio / voltage", "", 4) { "${word(it) / 32768.0} λ; ${word(it, 2) / 8192.0} V" }
        scalar(0x2C, "Commanded EGR", "%") { it[0] * 100.0 / 255 }
        scalar(0x2D, "EGR error", "%") { it[0] * 100.0 / 128 - 100 }
        scalar(0x2E, "Commanded evaporative purge", "%") { it[0] * 100.0 / 255 }
        scalar(0x2F, "Fuel tank level", "%") { it[0] * 100.0 / 255 }
        scalar(0x30, "Warm-ups since codes cleared", "count") { it[0] }
        scalar(0x31, "Distance since codes cleared", "km", 2) { word(it) }
        scalar(0x32, "Evaporative system vapor pressure", "Pa", 2) { val n = word(it); (if (n >= 32768) n - 65536 else n) / 4.0 }
        scalar(0x33, "Absolute barometric pressure", "kPa") { it[0] }
        for (pid in 0x34..0x3B) scalar(pid, "Wideband oxygen sensor ${pid - 0x33} ratio / current", "", 4) { "${word(it) / 32768.0} λ; ${word(it, 2) / 256.0 - 128} mA" }
        for ((pid, label) in listOf(0x3C to "bank 1 sensor 1", 0x3D to "bank 2 sensor 1", 0x3E to "bank 1 sensor 2", 0x3F to "bank 2 sensor 2")) scalar(pid, "Catalyst temperature $label", "°C", 2) { word(it) / 10.0 - 40 }
        raw(0x41, "Monitor status this drive cycle")
        scalar(0x42, "Control module voltage", "V", 2) { word(it) / 1000.0 }
        scalar(0x43, "Absolute engine load", "%", 2) { word(it) * 100.0 / 255 }
        scalar(0x44, "Commanded air-fuel equivalence ratio", "λ", 2) { word(it) / 32768.0 }
        val percentages = mapOf(0x45 to "Relative throttle position", 0x47 to "Absolute throttle position B", 0x48 to "Absolute throttle position C", 0x49 to "Accelerator pedal D", 0x4A to "Accelerator pedal E", 0x4B to "Accelerator pedal F", 0x4C to "Commanded throttle actuator", 0x52 to "Ethanol fuel percentage", 0x5A to "Relative accelerator pedal", 0x5B to "Hybrid battery remaining life")
        percentages.forEach { (pid, name) -> scalar(pid, name, "%") { it[0] * 100.0 / 255 } }
        scalar(0x46, "Ambient air temperature", "°C") { it[0] - 40 }
        scalar(0x4D, "Time with MIL on", "min", 2) { word(it) }
        scalar(0x4E, "Time since codes cleared", "min", 2) { word(it) }
        raw(0x4F, "Maximum sensor values")
        scalar(0x50, "Maximum mass air flow", "g/s", 4) { it[0] * 10 }
        raw(0x51, "Fuel type")
        scalar(0x53, "Absolute evaporative vapor pressure", "kPa", 2) { word(it) / 200.0 }
        scalar(0x54, "Evaporative vapor pressure", "Pa", 2) { word(it) - 32767 }
        for (pid in 0x55..0x58) raw(pid, "Secondary oxygen sensor fuel trim %02X".format(pid))
        scalar(0x59, "Absolute fuel rail pressure", "kPa", 2) { word(it) * 10 }
        scalar(0x5C, "Engine oil temperature", "°C") { it[0] - 40 }
        scalar(0x5D, "Fuel injection timing", "°", 2) { word(it) / 128.0 - 210 }
        scalar(0x5E, "Engine fuel rate", "L/h", 2) { word(it) / 20.0 }
        raw(0x5F, "Emission requirements")
    }
    fun definition(pid: Int): Definition = definitions[pid] ?: Definition(pid, "PID %02X (raw data)".format(pid))
    fun knownPids(): Set<Int> = definitions.keys.toSet()
    fun decode(pid: Int, bytes: List<Int>): Any? {
        val d = definition(pid)
        return if (d.decode != null && bytes.size >= d.size) d.decode.invoke(bytes) else null
    }
    fun rawHex(bytes: List<Int>): String = hex(bytes)
    fun supported(base: Int, bitmap: List<Int>): Set<Int> {
        require(base in 0..0xE0 && base % 0x20 == 0 && bitmap.size >= 4)
        return (1..32).filter { offset -> bitmap[(offset - 1) / 8] and (1 shl (7 - (offset - 1) % 8)) != 0 }
            .map { base + it }.filter { it <= 255 }.toSet()
    }
}
