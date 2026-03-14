package com.jellyfinbroadcast.phone

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.jellyfinbroadcast.core.MediaPlayer
import com.jellyfinbroadcast.core.NetworkUtils
import com.jellyfinbroadcast.core.QrCodeGenerator
import com.jellyfinbroadcast.databinding.FragmentPhoneQrCodeBinding
import com.jellyfinbroadcast.server.ConfigServer

class PhoneQrCodeFragment : Fragment() {

    private var _binding: FragmentPhoneQrCodeBinding? = null
    private val binding get() = _binding!!
    private var configServer: ConfigServer? = null
    private var mediaPlayer: MediaPlayer? = null

    private val isConfigured: Boolean get() = arguments?.getBoolean(ARG_CONFIGURED, false) ?: false
    private val showMenu: Boolean get() = arguments?.getBoolean(ARG_SHOW_MENU, false) ?: false

    companion object {
        private const val ARG_CONFIGURED = "configured"
        private const val ARG_SHOW_MENU = "show_menu"

        fun newInstance(configured: Boolean = false, showMenu: Boolean = false) = PhoneQrCodeFragment().apply {
            arguments = Bundle().apply {
                putBoolean(ARG_CONFIGURED, configured)
                putBoolean(ARG_SHOW_MENU, showMenu)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPhoneQrCodeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize media player
        val player = MediaPlayer(requireContext())
        player.initialize(enablePassthrough = false)
        mediaPlayer = player
        binding.playerView.player = player.getExoPlayer()

        // Capture activity reference eagerly (Ktor callback runs on IO thread)
        val phoneActivity = requireActivity() as PhoneActivity
        val server = ConfigServer { payload ->
            phoneActivity.onConfigReceived(payload)
        }
        configServer = server
        server.start()
        showQrCode(server.port)
        setupButtons()

        if (isConfigured) {
            // "Mode diffusion" button visible in configured mode
            binding.btnBroadcastMode.visibility = View.VISIBLE

            if (showMenu) {
                // Returning from TV config: show overlay immediately for chain configuration
                binding.contentOverlay.visibility = View.VISIBLE
            } else {
                // Normal configured mode: black screen, long-press to reveal overlay
                binding.contentOverlay.visibility = View.GONE
            }

            binding.root.setOnLongClickListener {
                binding.contentOverlay.visibility = View.VISIBLE
                true
            }
        } else {
            // First launch: overlay always visible, no broadcast mode button
            binding.contentOverlay.visibility = View.VISIBLE
            binding.btnBroadcastMode.visibility = View.GONE
        }
    }

    private fun showQrCode(port: Int) {
        val ip = NetworkUtils.getLocalIpAddress()
        val bitmap = QrCodeGenerator.generate(ip, port)
        binding.ivQrCode.setImageBitmap(bitmap)
    }

    private fun setupButtons() {
        binding.btnConfigureThis.setOnClickListener {
            binding.contentOverlay.visibility = View.GONE
            (activity as? PhoneActivity)?.showConfigForm(tvIp = null)
        }
        binding.btnScanQr.setOnClickListener {
            binding.contentOverlay.visibility = View.GONE
            (activity as? PhoneActivity)?.startQrScanner()
        }
        binding.btnBroadcastMode.setOnClickListener {
            binding.contentOverlay.visibility = View.GONE
        }
    }

    fun getMediaPlayer(): MediaPlayer? = mediaPlayer

    /** Hide overlay when playback starts */
    fun onPlaybackStarted() {
        _binding?.contentOverlay?.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.playerView.player = null
        mediaPlayer?.release()
        mediaPlayer = null
        configServer?.stop()
        configServer = null
        _binding = null
    }
}
