package com.jellyfinbroadcast.core

import org.junit.Assert.*
import org.junit.Test

class QrCodeGeneratorTest {

    @Test
    fun `buildUrl formats correctly`() {
        val url = QrCodeGenerator.buildUrl("192.168.1.10", 8765)
        assertEquals("http://192.168.1.10:8765", url)
    }

    @Test
    fun `buildUrl with IPv6 wraps in brackets`() {
        val url = QrCodeGenerator.buildUrl("fe80::1", 8765)
        assertEquals("http://[fe80::1]:8765", url)
    }
}
