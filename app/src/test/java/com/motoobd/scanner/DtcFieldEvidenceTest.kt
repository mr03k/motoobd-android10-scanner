package com.motoobd.scanner

import org.junit.Assert.*
import org.junit.Test

/** Synthetic wire equivalents of the code list in the user's Torque screenshot.
 * These establish parser coverage, not what the vehicle actually transmitted. */
class DtcFieldEvidenceTest {
    private val expected = listOf("P0230", "P0449", "P0645", "P0650", "P1665", "P3001", "P3005", "P3089", "P3190", "P3381")
    private val pairs = "02 30 04 49 06 45 06 50 16 65 30 01 30 05 30 89 31 90 33 81"

    @Test fun legacyPairsPreserveAllScreenshotCodesIncludingManufacturerRanges() {
        val raw = "43 $pairs"
        assertTrue(ObdParser.validDtcReply(raw, 0x43, false))
        assertEquals(expected, ObdParser.dtcs(raw, 0x43, false))
    }
    @Test fun canCountIsSeparateFromFirstDtcByte() {
        val raw = "43 0A $pairs"
        assertTrue(ObdParser.validDtcReply(raw, 0x43, true))
        assertEquals(expected, ObdParser.dtcs(raw, 0x43, true))
        assertFalse(ObdParser.validDtcReply("43 $pairs", 0x43, true))
    }
}
