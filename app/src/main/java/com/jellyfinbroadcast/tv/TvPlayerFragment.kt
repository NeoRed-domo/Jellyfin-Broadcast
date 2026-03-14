package com.jellyfinbroadcast.tv

import android.os.Bundle
import android.view.KeyEvent
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

    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                val mp = mediaPlayer ?: return false
                if (mp.isPlaying()) mp.pause() else mp.resume()
                true
            }
            else -> false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.playerView.player = null
        mediaPlayer?.release()
        mediaPlayer = null
        _binding = null
    }
}
