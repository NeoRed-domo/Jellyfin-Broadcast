package com.jellyfinbroadcast.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@Composable
fun QrCodeScreen(configUrl: String) {
    val qrBitmap = remember(configUrl) { generateQrCode(configUrl, 400) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            // App name
            Text(
                text = "Jellyfin Broadcast",
                color = androidx.compose.ui.graphics.Color(0xFF00A4DC),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // QR Code
            qrBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "QR Code de configuration",
                    modifier = Modifier
                        .size(280.dp)
                        .background(androidx.compose.ui.graphics.Color.White)
                        .padding(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Instruction text
            Text(
                text = "Scannez ce QR code depuis l'application\nJellyfin Broadcast sur votre smartphone ou tablette",
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                lineHeight = 26.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "L'application est disponible sur Android",
                color = androidx.compose.ui.graphics.Color(0xFF888888),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun generateQrCode(content: String, size: Int): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}
