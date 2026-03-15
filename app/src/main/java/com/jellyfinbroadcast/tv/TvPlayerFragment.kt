package com.jellyfinbroadcast.tv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.jellyfinbroadcast.core.MediaPlayer
import com.jellyfinbroadcast.databinding.FragmentTvPlayerBinding

class TvPlayerFragment : Fragment() {

    private var _binding: FragmentTvPlayerBinding? = null
    private val binding get() = _binding!!
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTvPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val player = MediaPlayer(requireContext())
        player.initialize(enablePassthrough = true)
        mediaPlayer = player
        // Connect ExoPlayer instance to PlayerView
        binding.playerView.player = player.getExoPlayer()
    }

    fun getMediaPlayer(): MediaPlayer? = mediaPlayer

    fun rebindPlayer(mediaPlayer: MediaPlayer) {
        _binding?.playerView?.player = mediaPlayer.getExoPlayer()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.playerView.player = null
        mediaPlayer?.release()
        mediaPlayer = null
        _binding = null
    }
}
