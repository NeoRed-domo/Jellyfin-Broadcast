package com.jellyfinbroadcast.tv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.jellyfinbroadcast.core.NetworkUtils
import com.jellyfinbroadcast.core.QrCodeGenerator
import com.jellyfinbroadcast.databinding.FragmentTvQrCodeBinding
import com.jellyfinbroadcast.discovery.JellyfinDiscovery
import com.jellyfinbroadcast.server.ConfigServer
import kotlinx.coroutines.launch

class TvQrCodeFragment : Fragment() {

    private var _binding: FragmentTvQrCodeBinding? = null
    private val binding get() = _binding!!
    private var configServer: ConfigServer? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTvQrCodeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        startInitialization()
    }

    private fun startInitialization() {
        // Phase 1: Show splash with spinner
        binding.tvStatus.text = "Initialisation en cours..."
        binding.progressBar.visibility = View.VISIBLE
        binding.ivQrCode.visibility = View.GONE
        binding.tvInstructions.visibility = View.GONE

        // Capture activity reference eagerly (Ktor callback runs on IO thread where fragment.activity can be null)
        val tvActivity = requireActivity() as TvActivity
        val server = ConfigServer { payload ->
            tvActivity.onConfigReceived(payload)
        }
        configServer = server
        server.start()

        // Phase 2: Run discovery, then show QR code
        lifecycleScope.launch {
            val serverInfo = JellyfinDiscovery(requireContext()).discover()

            if (_binding == null) return@launch

            // Store discovered server in ConfigServer so phone can fetch it
            if (serverInfo != null) {
                server.discoveredHost = serverInfo.host
                server.discoveredPort = serverInfo.port
                (activity as? TvActivity)?.onServerDiscovered(serverInfo.host, serverInfo.port)
            }

            // Show QR code
            val localIp = NetworkUtils.getLocalIpAddress()
            showQrCode(localIp, server.port)
        }
    }

    private fun showQrCode(ip: String, port: Int) {
        binding.progressBar.visibility = View.GONE
        binding.tvStatus.text = "Scannez ce QR code pour configurer"
        val bitmap = QrCodeGenerator.generate(ip, port)
        binding.ivQrCode.setImageBitmap(bitmap)
        binding.ivQrCode.visibility = View.VISIBLE
        binding.tvInstructions.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        configServer?.stop()
        configServer = null
        _binding = null
    }
}
