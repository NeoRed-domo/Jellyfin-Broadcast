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
        startDiscoveryAndServer()
    }

    private fun startDiscoveryAndServer() {
        val server = ConfigServer { payload ->
            (activity as? TvActivity)?.onConfigReceived(payload) ?: false
        }
        configServer = server
        server.start()

        val localIp = NetworkUtils.getLocalIpAddress()
        showQrCode(localIp, server.port)

        binding.tvStatus.text = "Recherche du serveur Jellyfin..."
        lifecycleScope.launch {
            val serverInfo = JellyfinDiscovery(requireContext()).discover()
            if (serverInfo != null) {
                (activity as? TvActivity)?.onServerDiscovered(serverInfo.host, serverInfo.port)
            }
            if (_binding != null) {
                binding.tvStatus.text = "Scanner ce QR code pour configurer"
            }
        }
    }

    private fun showQrCode(ip: String, port: Int) {
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
