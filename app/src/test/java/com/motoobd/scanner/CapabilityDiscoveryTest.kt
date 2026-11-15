package com.motoobd.scanner

import org.junit.Assert.*
import org.junit.Test

class CapabilityDiscoveryTest {
    @Test fun bitmapUsesMsbFirstAndContinuationLsb() {
        assertEquals(setOf(1, 32), PidCatalogue.supported(0, listOf(0x80, 0, 0, 1)))
        assertEquals(setOf(0xE1, 0xFF), PidCatalogue.supported(0xE0, listOf(0x80, 0, 0, 2)))
    }
    @Test fun followsChainedPagesAndPollsAllAdvertisedNonBitmapPids() {
        val requests = mutableListOf<String>()
        val result = CapabilityDiscovery.discover(1) { cmd ->
            requests.add(cmd)
            when(cmd) {
                "0100" -> "7E8 06 41 00 80 18 00 01\r7E9 06 41 00 40 00 00 00"
                "0120" -> "7E8 06 41 20 80 00 00 01"
                "0140" -> "7E8 06 41 40 00 00 00 01"
                "0160" -> "7E8 06 41 60 80 00 00 00"
                else -> error("Unexpected command: $cmd")
            }
        }
        assertEquals(listOf("0100", "0120", "0140", "0160"), requests)
        assertEquals(listOf(1, 2, 0x0C, 0x0D, 0x21, 0x61), CapabilityDiscovery.pollOrder(result.supported))
        assertEquals(setOf(2), result.supported["7E9"])
        assertTrue(result.errors.isEmpty())
    }
    @Test fun missingContinuationPageRemainsUnknown() {
        val result = CapabilityDiscovery.discover(1) { cmd -> if (cmd == "0100") "41 00 80 00 00 01" else "NO DATA" }
        assertEquals(setOf(0), result.ranges["unidentified"])
        assertFalse(0x20 in result.ranges["unidentified"].orEmpty())
        assertTrue(result.errors.isNotEmpty())
    }
    @Test fun stopsAtE0AndDoesNotPollSupportPids() {
        val requests = mutableListOf<String>()
        val result = CapabilityDiscovery.discover(1) { cmd -> requests.add(cmd); "41 ${cmd.takeLast(2)} FF FF FF FF" }
        assertEquals(8, requests.size)
        assertEquals("01E0", requests.last())
        assertEquals(248, CapabilityDiscovery.pollOrder(result.supported).size)
        assertFalse(CapabilityDiscovery.pollOrder(result.supported).any { it % 32 == 0 })
    }
    @Test fun freezeFrameSupportCommandsIncludeFrameNumber() {
        val requests = mutableListOf<String>()
        val result = CapabilityDiscovery.discover(2) { cmd -> requests.add(cmd); "42 00 00 80 00 00 00" }
        assertEquals(listOf("020000"), requests)
        assertEquals(setOf(1), result.supported["unidentified"])
    }
    @Test fun modeNineDiscoveryIsIndependent() {
        val result = CapabilityDiscovery.discover(9) { cmd -> assertEquals("0900", cmd); "49 00 40 00 00 00" }
        assertEquals(setOf(2), result.supported["unidentified"])
    }
}
