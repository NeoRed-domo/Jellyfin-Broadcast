package com.jellyfinbroadcast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView

@Composable
fun PlayerScreen(
    player: Player?,
    isConfigured: Boolean,
    onConfigureClick: () -> Unit,
    onLongPress: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                if (onLongPress != null && isConfigured) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = { onLongPress() }
                        )
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        if (!isConfigured) {
            Button(
                onClick = onConfigureClick,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00A4DC)
                ),
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Configurer",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }
        } else if (player != null) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        this.player = player
                        useController = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        setKeepScreenOn(true)
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { playerView ->
                    playerView.player = player
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
