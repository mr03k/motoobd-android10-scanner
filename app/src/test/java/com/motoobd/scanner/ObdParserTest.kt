package com.motoobd.scanner

import org.junit.Assert.*
import org.junit.Test

class ObdParserTest {
    @Test fun echoedPidAndAdapterStatusAreNotData() {
        assertEquals(listOf(0x1A, 0xF8), ObdParser.pid("010C\rSEARCHING...\r41 0C 1A F8\r>", 0x0C, 2))
        assertNull(ObdParser.pid("010C\rNO DATA\r>", 0x0C, 2))
        assertNull(ObdParser.pid("ERROR 410CFFFF", 0x0C, 2))
    }
    @Test fun shortPidDoesNotBorrowBytesFromAnotherEcu() {
        assertNull(ObdParser.pid("41 0C 1A\r41 0D 20", 0x0C, 2))
    }
    @Test fun parsesAllDtcCategoriesAndHexDigits() {
        assertEquals(listOf("P0171", "C1234", "B2ABC", "U3DEF"),
            ObdParser.dtcs("43 01 71 52 34 AA BC FD EF 00 00", 0x43, false))
    }
    @Test fun preservesCodeBeginningWithAnotherReplyMode() {
        assertEquals(listOf("C0300", "C0701", "C0A02"), ObdParser.dtcs("43 43 00 47 01 4A 02", 0x43, false))
    }
    @Test fun handlesCanCountAndPaddingWithoutInventingCodes() {
        assertEquals(listOf("P0171", "P0300"), ObdParser.dtcs("43 02 01 71 03 00 00 00", 0x43, true))
        assertEquals(emptyList<String>(), ObdParser.dtcs("43 00 00 00 00 00 00 00", 0x43, true))
        assertTrue(ObdParser.validDtcReply("43 00", 0x43, true))
        assertFalse(ObdParser.validDtcReply("43 FF", 0x43, true))
        assertFalse(ObdParser.validDtcReply("43 01 00 00", 0x43, true))
        assertFalse(ObdParser.validDtcReply("43 01 01 71 12 34", 0x43, true))
        assertFalse(ObdParser.validDtcReply("NO DATA", 0x43, true))
    }
    @Test fun legacyLeadingBytesAreNotMistakenForCanCount() {
        assertEquals(listOf("P0101"), ObdParser.dtcs("43 01 01 00 00 00 00", 0x43, false))
    }
    @Test fun separatesMultipleSingleFrameEcus() {
        assertEquals(listOf("P0171", "U0100"), ObdParser.dtcs("7E8 06 43 01 01 71 00 00\r7E9 06 43 01 C1 00 00 00", 0x43, true))
    }
    @Test fun reassemblesInterleavedIsoTpWithoutHeadersAsCodes() {
        val response = "7E8 10 0A 43 04 01 71 03 00\r7E9 04 43 01 C1 00\r7E8 21 04 20 01 18 00 00 00"
        assertEquals(listOf("U0100", "P0171", "P0300", "P0420", "P0118"), ObdParser.dtcs(response, 0x43, true))
    }
    @Test fun incompleteAndOutOfOrderIsoTpAreRejected() {
        assertFalse(ObdParser.hasReply("7E8 10 0A 43 04 01 71 03 00", 0x43))
        assertFalse(ObdParser.hasReply("7E8 10 0A 43 04 01 71 03 00\r7E8 22 04 20 01 18 00 00 00", 0x43))
    }
    @Test fun handlesElmNumberedMultilineResponse() {
        assertEquals(listOf("P0171", "P0300", "P0420", "P0118"),
            ObdParser.dtcs("00A\r0: 43 04 01 71 03 00\r1: 04 20 01 18", 0x43, true))
    }
    @Test fun clearRequiresPositiveService44() {
        assertTrue(ObdParser.hasReply("44", 0x44))
        assertFalse(ObdParser.hasReply("04\rOK", 0x44))
        assertFalse(ObdParser.hasReply("7F 04 22", 0x44))
        assertTrue(ObdParser.hasNegativeReply("44\r7F 04 22", 0x04))
        assertFalse(ObdParser.validDtcReply("43 00\r7F 03 22", 0x43, true))
    }
    @Test fun voltageRequiresUnitsAndRejectsEchoesAndOutOfRange() {
        assertEquals(12.7, ObdParser.voltage("ATRV\r12.7V\r>")!!, 0.001)
        assertNull(ObdParser.voltage("ELM327 v1.5"))
        assertNull(ObdParser.voltage("99.0V"))
    }
}
