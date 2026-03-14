package com.jellyfinbroadcast.phone

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.jellyfinbroadcast.core.SessionStore
import com.jellyfinbroadcast.databinding.FragmentPhoneHomeBinding

class PhoneHomeFragment : Fragment() {

    private var _binding: FragmentPhoneHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPhoneHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val store = SessionStore(requireContext())
        val host = store.getHost()
        if (host != null) {
            binding.tvStatus.text = "Connecté à $host"
        }

        binding.btnScanTv.setOnClickListener {
            (activity as? PhoneActivity)?.startQrScanner()
        }

        binding.btnReconfigure.setOnClickListener {
            SessionStore(requireContext()).clear()
            (activity as? PhoneActivity)?.showConfigForm(tvIp = null)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
