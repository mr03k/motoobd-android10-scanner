package com.motoobd.scanner

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class FieldProtocolRegressionTest {
    @Test fun legacyVehicleInformationBitmapSkipsSequenceByte() {
        val requests = mutableListOf<String>()
        val result = CapabilityDiscovery.discover(9, legacy = true) { request ->
            requests.add(request)
            "49 00 01 FC 00 00 00"
        }
        assertEquals(listOf("0900"), requests)
        assertEquals((1..6).toSet(), result.supported["unidentified"])
        assertTrue(result.errors.isEmpty())
    }
    @Test fun legacyBitmapRejectsMissingDuplicateAndInvalidSequence() {
        for (raw in listOf("49 00 FC 00 00 00", "49 00 02 FC 00 00 00", "49 00 01 FC 00 00 00 00")) {
            val result = CapabilityDiscovery.discover(9, legacy = true) { raw }
            assertTrue(result.supported.isEmpty())
            assertTrue(result.errors.isNotEmpty())
        }
    }
    @Test fun legacyCalibrationStrictlyAssemblesFourByteMessages() {
        val text = "MXGITP50----".padEnd(16, '\u0000').map { it.code }
        val records = text.chunked(4).mapIndexed { i, b -> listOf(i + 1) + b }
        assertEquals("MXGITP50----", DiagnosticDecoder.legacyVehicleInfo(4, records.reversed(), 4).value)
        assertNull(DiagnosticDecoder.legacyVehicleInfo(4, records + records, 8).value)
        assertNull(DiagnosticDecoder.legacyVehicleInfo(4, records.take(3), 4).value)
        assertNull(DiagnosticDecoder.legacyVehicleInfo(4, records, null).value)
        assertEquals(4, DiagnosticDecoder.legacyMessageCount(ObdParser.frames("49 03 04").map { it.bytes.drop(2) }))
        assertNull(DiagnosticDecoder.legacyMessageCount(listOf(listOf(4), listOf(4))))
        assertNull(DiagnosticDecoder.legacyMessageCount(listOf(listOf(1, 4))))
    }
    @Test fun legacyVinRequiresTwentyBytePaddingAndUniqueSequence() {
        val vin = "1G1JC5444R7252367"
        val records = (listOf(0, 0, 0) + vin.map { it.code }).chunked(4).mapIndexed { i, b -> listOf(i + 1) + b }
        assertEquals(vin, DiagnosticDecoder.legacyVehicleInfo(2, records, 5).value)
        assertNull(DiagnosticDecoder.legacyVehicleInfo(2, records.drop(1) + listOf(records.last()), 5).value)
    }
    @Test fun legacyMonitorLimitBitIsNotPartOfCidAndRecordsRemainDistinct() {
        val raw = "46 05 09 80 00 51 20\r46 05 8A 80 00 79 C0\r46 05 10 80 00 81 90"
        val result = MonitorTestDecoder.parse(raw, 5, false)
        assertEquals(3, result.tests.size)
        assertEquals(listOf(9, 10, 16), result.tests.map { it.cid })
        assertEquals(32768, result.tests[0].value)
        assertEquals(20768, result.tests[0].maximum)
        assertNull(result.tests[0].minimum)
        assertEquals(31168, result.tests[1].minimum)
        assertEquals(33168, result.tests[2].maximum)
        assertTrue(result.errors.isEmpty())
    }
    @Test fun legacyRepeatedRecordsAndOppositeLimitsAreNotLost() {
        val raw = "46 0D 01 00 01 FF FF\r46 0D 01 00 01 FF FF\r46 0D 81 00 01 00 00"
        val result = MonitorTestDecoder.parse(raw, 0x0D, false)
        assertEquals(3, result.tests.size)
        assertEquals(65535, result.tests[0].maximum)
        assertEquals(0, result.tests[2].minimum)
    }
    @Test fun legacyMonitorBitmapRequiresFfFiller() {
        val support = CapabilityDiscovery.discover(6, legacy = true) { "46 00 FF 08 08 00 00" }
        assertEquals(setOf(5, 13), support.supported["unidentified"])
        assertTrue(CapabilityDiscovery.discover(6, legacy = true) { "46 00 01 08 08 00 00" }.supported.isEmpty())
    }
    @Test fun canMonitorUsesRepeatedMidAndScalingId() {
        val result = MonitorTestDecoder.parse("46 01 01 0A 00 64 00 00 00 C8 01 02 80 FF FF 80 00 7F FF", 1, true)
        assertEquals(2, result.tests.size)
        assertEquals(listOf(1, 2), result.tests.map { it.tid })
        assertEquals(0x80, result.tests[1].scalingId)
        assertEquals(65535, result.tests[1].value) // preserved bits; signed interpretation intentionally absent
        assertTrue(result.errors.isEmpty())
        assertTrue(MonitorTestDecoder.parse("46 01 01 00 00 00 00", 1, true).tests.isEmpty())
    }
    @Test fun completedNoDataRetriesOnceButTimeoutAndWritesNeverRetry() {
        var calls = 0
        val result = ReadOnlyRetry.execute("010C") { if (calls++ == 0) "010C\rNO DATA" else "41 0C 00 00" }
        assertEquals(2, calls)
        assertEquals(2, result.attempts.size)
        calls = 0
        ReadOnlyRetry.execute("04") { calls++; "NO DATA" }
        assertEquals(1, calls)
        calls = 0
        try { ReadOnlyRetry.execute("03") { calls++; throw IOException("No prompt") }; fail("Expected failure") } catch (_: IOException) { }
        assertEquals(1, calls)
        calls = 0
        ReadOnlyRetry.execute("03") { calls++; "43 00 00 00 00 00 00" }
        assertEquals(1, calls)
    }
    @Test fun readingHistorySeparatesSuccessFromLatestFailure() {
        val previous = ParameterReading(0x0D, "unidentified", listOf(0), 100)
        val failed = ReadingHistory.failed(previous, 0x0D, "unidentified", "NO DATA", 200, "NO DATA")
        assertEquals(listOf(0), failed.bytes)
        assertEquals(100, failed.at)
        assertEquals(200, failed.attemptedAt)
        assertEquals("NO DATA", failed.failureRaw)
        val firstFailure = ReadingHistory.failed(null, 0x0D, "unidentified", "NO DATA", 300, "NO DATA")
        assertEquals(0, firstFailure.at)
        assertTrue(firstFailure.bytes.isEmpty())
    }
}
