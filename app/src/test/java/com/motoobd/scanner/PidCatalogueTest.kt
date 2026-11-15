package com.motoobd.scanner

import org.junit.Assert.*
import org.junit.Test

class PidCatalogueTest {
    @Test fun zeroIsAValidScalarAndShortPayloadIsNot() {
        assertEquals(0, PidCatalogue.decode(0x0D, listOf(0)))
        assertNull(PidCatalogue.decode(0x0C, listOf(0xFF)))
    }
    @Test fun commonSensorsUseCorrectScalingAndOffsets() {
        assertEquals(1726.0, PidCatalogue.decode(0x0C, listOf(0x1A, 0xF8)))
        assertEquals(-40, PidCatalogue.decode(0x5C, listOf(0)))
        assertEquals(0.0, PidCatalogue.decode(0x06, listOf(128)))
        assertEquals(-64.0, PidCatalogue.decode(0x0E, listOf(0)))
        assertEquals(3000, PidCatalogue.decode(0x23, listOf(1, 44)))
        assertEquals(12.8, PidCatalogue.decode(0x42, listOf(0x32, 0)))
        assertEquals(10.0, PidCatalogue.decode(0x5E, listOf(0, 200)))
        assertEquals(-0.25, PidCatalogue.decode(0x32, listOf(255, 255)))
    }
    @Test fun unknownAndEncodedRemainRawWithoutGuesses() {
        assertNull(PidCatalogue.decode(0x61, listOf(1, 2)))
        assertNull(PidCatalogue.decode(0x01, listOf(0x80, 7, 0xFF, 0)))
        assertEquals("01 FF", PidCatalogue.rawHex(listOf(1, 255)))
    }
    @Test fun readinessDistinguishesUnsupportedIncompleteAndDieselMonitors() {
        val ready = DiagnosticDecoder.readiness(listOf(0x82, 0x1F, 0x81, 1))!!
        assertTrue(ready.mil)
        assertEquals(2, ready.dtcCount)
        assertEquals("Compression ignition", ready.ignitionType)
        assertFalse(ready.monitors.first { it.name == "Misfire" }.complete!!)
        assertFalse(ready.monitors.first { it.name == "NMHC catalyst" }.complete!!)
        assertNull(ready.monitors.first { it.name == "NOx/SCR" }.complete)
        assertTrue(ready.monitors.first { it.name == "EGR/VVT" }.complete!!)
        assertNull(DiagnosticDecoder.readiness(listOf(1, 2)))
    }
    @Test fun canVinOnlyDecodesAnUnambiguousCompleteRecord() {
        val bytes = listOf(1) + "WVWZZZ1JZXW000001".map { it.code }
        assertEquals("WVWZZZ1JZXW000001", DiagnosticDecoder.vehicleInfo(2, bytes, true))
        assertNull(DiagnosticDecoder.vehicleInfo(2, bytes, false))
        assertNull(DiagnosticDecoder.vehicleInfo(2, bytes.dropLast(1), true))
    }
    @Test fun framesKeepSeparateCanSourceIdentifiers() {
        assertEquals(listOf("7E8", "7E9"), ObdParser.frames("7E8 03 41 0D 00\r7E9 03 41 0D FF").map { it.ecu })
        assertFalse(ObdParser.hasNegativeReply("7F 09 78\r49 00 40 00 00 00", 9))
    }
}
