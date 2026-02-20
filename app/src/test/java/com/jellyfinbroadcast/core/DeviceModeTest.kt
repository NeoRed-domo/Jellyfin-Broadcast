package com.jellyfinbroadcast.core

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceModeTest {

    @Test
    fun `returns TV when uiMode is TYPE_TELEVISION`() {
        val mode = DeviceMode.from(Configuration.UI_MODE_TYPE_TELEVISION)
        assertEquals(DeviceMode.TV, mode)
    }

    @Test
    fun `returns PHONE when uiMode is TYPE_NORMAL`() {
        val mode = DeviceMode.from(Configuration.UI_MODE_TYPE_NORMAL)
        assertEquals(DeviceMode.PHONE, mode)
    }

    @Test
    fun `returns PHONE for any non-TV uiMode`() {
        val mode = DeviceMode.from(Configuration.UI_MODE_TYPE_DESK)
        assertEquals(DeviceMode.PHONE, mode)
    }
}
