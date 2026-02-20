package com.jellyfinbroadcast.phone

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.jellyfinbroadcast.databinding.FragmentConfigFormBinding
import com.jellyfinbroadcast.server.ConfigPayload
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.launch

class ConfigFormFragment : Fragment() {

    private var _binding: FragmentConfigFormBinding? = null
    private val binding get() = _binding!!

    var tvIp: String? = null
    var tvPort: Int = 8765
    var prefilledHost: String? = null

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentConfigFormBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefilledHost?.let { binding.etHost.setText(it) }
        binding.btnSend.setOnClickListener { sendConfig() }
    }

    private fun sendConfig() {
        val host = binding.etHost.text.toString().trim()
        val port = binding.etPort.text.toString().toIntOrNull() ?: 8096
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()

        val payload = ConfigPayload(host, port, username, password)
        if (!payload.isValid()) {
            binding.tvStatus.apply { text = "Veuillez remplir tous les champs"; visibility = View.VISIBLE }
            return
        }

        val targetIp = tvIp
        if (targetIp == null) {
            // Configure this device locally
            binding.tvStatus.apply { text = "Configuration locale non implémentée"; visibility = View.VISIBLE }
            return
        }

        binding.btnSend.isEnabled = false
        binding.tvStatus.apply { text = "Envoi en cours..."; visibility = View.VISIBLE }

        lifecycleScope.launch {
            try {
                val response: HttpResponse = httpClient.post("http://$targetIp:$tvPort/configure") {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
                if (_binding == null) return@launch
                if (response.status == HttpStatusCode.OK) {
                    binding.tvStatus.text = "Configuration envoyée ✓"
                } else {
                    binding.tvStatus.text = "Erreur : credentials invalides"
                    binding.btnSend.isEnabled = true
                }
            } catch (e: Exception) {
                if (_binding == null) return@launch
                binding.tvStatus.text = "Erreur réseau : ${e.message}"
                binding.btnSend.isEnabled = true
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        httpClient.close()
        _binding = null
    }
}
