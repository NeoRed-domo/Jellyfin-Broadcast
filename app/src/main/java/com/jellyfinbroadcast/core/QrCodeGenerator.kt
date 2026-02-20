package com.jellyfinbroadcast.core

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter

object QrCodeGenerator {

    /**
     * Builds the HTTP URL encoded in the QR code.
     * IPv6 addresses are wrapped in brackets per RFC 2732.
     * Already-bracketed addresses (starting with '[') are not double-wrapped.
     */
    fun buildUrl(host: String, port: Int): String {
        val formattedHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
        return "http://$formattedHost:$port"
    }

    /**
     * Generates a black-and-white QR code bitmap for the given host/port.
     *
     * @throws WriterException if ZXing cannot encode the URL (e.g., data too large)
     */
    @Throws(WriterException::class)
    fun generate(host: String, port: Int, size: Int = 512): Bitmap {
        val url = buildUrl(host, port)
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val bits = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size, hints)
        // RGB_565 bitmap (no alpha). Black = 0x0000, White = 0xFFFF in 16-bit.
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bits[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        return bitmap
    }
}
