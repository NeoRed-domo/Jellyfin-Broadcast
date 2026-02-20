package com.jellyfinbroadcast.core

import android.content.res.Configuration

enum class DeviceMode {
    TV, PHONE;

    companion object {
        fun from(uiModeType: Int): DeviceMode =
            if (uiModeType == Configuration.UI_MODE_TYPE_TELEVISION) TV else PHONE

        fun detect(context: android.content.Context): DeviceMode {
            val uiModeManager = context.getSystemService(android.content.Context.UI_MODE_SERVICE)
                    as android.app.UiModeManager
            return from(uiModeManager.currentModeType)
        }
    }
}
