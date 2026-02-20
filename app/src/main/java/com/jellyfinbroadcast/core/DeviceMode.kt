package com.jellyfinbroadcast.core

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

enum class DeviceMode {
    TV, PHONE;

    companion object {
        /**
         * Returns [DeviceMode] based on the given UI mode type.
         *
         * @param uiModeType The value of [UiModeManager.currentModeType] — already extracted.
         *   Do NOT pass [Configuration.uiMode] directly (it's a bitmask and requires masking first).
         */
        fun from(uiModeType: Int): DeviceMode =
            if (uiModeType == Configuration.UI_MODE_TYPE_TELEVISION) TV else PHONE

        /**
         * Detects the current [DeviceMode] from [UiModeManager].
         * Falls back to [PHONE] if the service is unavailable.
         */
        fun detect(context: Context): DeviceMode {
            val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE)
                as? UiModeManager ?: return PHONE
            return from(uiModeManager.currentModeType)
        }
    }
}
