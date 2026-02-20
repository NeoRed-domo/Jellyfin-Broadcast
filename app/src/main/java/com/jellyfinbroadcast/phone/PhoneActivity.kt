package com.jellyfinbroadcast.phone

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.jellyfinbroadcast.R
import com.jellyfinbroadcast.server.ConfigPayload

class PhoneActivity : AppCompatActivity() {

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { url ->
            val regex = Regex("http://([^:]+):(\\d+)")
            val match = regex.find(url)
            if (match != null) {
                val tvIp = match.groupValues[1]
                val tvPort = match.groupValues[2].toIntOrNull() ?: 8765
                showConfigForm(tvIp, tvPort)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone)
        showQrCodeScreen()
    }

    private fun showQrCodeScreen() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, PhoneQrCodeFragment())
            .commit()
    }

    fun showConfigForm(tvIp: String?, tvPort: Int = 8765, prefilledHost: String? = null) {
        val fragment = ConfigFormFragment.newInstance(tvIp, tvPort, prefilledHost)
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .addToBackStack(null)
            .commit()
    }

    fun startQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Cadrez le QR code de la TV")
            setBeepEnabled(true)
        }
        qrScanLauncher.launch(options)
    }

    suspend fun onConfigReceived(payload: ConfigPayload): Boolean = false
}
