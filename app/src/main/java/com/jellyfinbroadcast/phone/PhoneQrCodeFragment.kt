package com.jellyfinbroadcast.phone

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.jellyfinbroadcast.core.NetworkUtils
import com.jellyfinbroadcast.core.QrCodeGenerator
import com.jellyfinbroadcast.databinding.FragmentPhoneQrCodeBinding
import com.jellyfinbroadcast.server.ConfigServer

class PhoneQrCodeFragment : Fragment() {

    private var _binding: FragmentPhoneQrCodeBinding? = null
    private val binding get() = _binding!!
    private var configServer: ConfigServer? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPhoneQrCodeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val server = ConfigServer { payload ->
            (activity as? PhoneActivity)?.onConfigReceived(payload) ?: false
        }
        configServer = server
        server.start()
        showQrCode(server.port)
        setupLongPress()
        setupMenuButtons()
    }

    private fun showQrCode(port: Int) {
        val ip = NetworkUtils.getLocalIpAddress()
        val bitmap = QrCodeGenerator.generate(ip, port)
        binding.ivQrCode.setImageBitmap(bitmap)
    }

    private fun setupLongPress() {
        binding.ivQrCode.setOnLongClickListener {
            binding.menuOverlay.visibility = View.VISIBLE
            true
        }
        binding.menuOverlay.setOnClickListener {
            binding.menuOverlay.visibility = View.GONE
        }
    }

    private fun setupMenuButtons() {
        binding.btnConfigureThis.setOnClickListener {
            binding.menuOverlay.visibility = View.GONE
            (activity as? PhoneActivity)?.showConfigForm(tvIp = null)
        }
        binding.btnScanQr.setOnClickListener {
            binding.menuOverlay.visibility = View.GONE
            (activity as? PhoneActivity)?.startQrScanner()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        configServer?.stop()
        configServer = null
        _binding = null
    }
}
