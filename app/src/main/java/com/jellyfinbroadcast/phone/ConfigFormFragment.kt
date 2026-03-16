package com.jellyfinbroadcast.phone

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.jellyfinbroadcast.databinding.FragmentConfigFormBinding
import com.jellyfinbroadcast.discovery.JellyfinDiscovery
import com.jellyfinbroadcast.server.ConfigPayload
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ConfigFormFragment : Fragment() {

    private var _binding: FragmentConfigFormBinding? = null
    private val binding get() = _binding!!

    private val tvIp: String? get() = arguments?.getString(ARG_TV_IP)
    private val tvPort: Int get() = arguments?.getInt(ARG_TV_PORT) ?: 8765
    private val prefilledHost: String? get() = arguments?.getString(ARG_PREFILLED_HOST)
    private val authToken: String get() = arguments?.getString(ARG_AUTH_TOKEN) ?: ""

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
    }

    companion object {
        private const val ARG_TV_IP = "tv_ip"
        private const val ARG_TV_PORT = "tv_port"
        private const val ARG_PREFILLED_HOST = "prefilled_host"
        private const val ARG_AUTH_TOKEN = "auth_token"

        fun newInstance(tvIp: String?, tvPort: Int = 8765, prefilledHost: String? = null, token: String = "") =
            ConfigFormFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TV_IP, tvIp)
                    putInt(ARG_TV_PORT, tvPort)
                    putString(ARG_PREFILLED_HOST, prefilledHost)
                    putString(ARG_AUTH_TOKEN, token)
                }
            }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentConfigFormBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefilledHost?.let { binding.etHost.setText(it) }
        binding.btnSend.setOnClickListener { sendConfig() }

        if (tvIp != null) {
            // Configuring a remote TV — fetch discovered server from TV's config server
            fetchDiscoveredServerFromTv()
        } else {
            // Configuring this device locally — run mDNS discovery directly
            discoverServerLocally()
        }
    }

    private fun fetchDiscoveredServerFromTv() {
        val targetIp = tvIp ?: return
        lifecycleScope.launch {
            try {
                val response: HttpResponse = httpClient.get("http://$targetIp:$tvPort/server-info")
                if (_binding == null) return@launch
                if (response.status == HttpStatusCode.OK) {
                    val body = response.bodyAsText()
                    val json = Json.parseToJsonElement(body).jsonObject
                    val host = json["host"]?.jsonPrimitive?.content
                    val port = json["port"]?.jsonPrimitive?.int
                    if (host != null && binding.etHost.text.isNullOrBlank()) {
                        binding.etHost.setText(host)
                    }
                    if (port != null && port != 8096) {
                        binding.etPort.setText(port.toString())
                    }
                }
            } catch (_: Exception) {
                // Server info not available, that's fine
            }
        }
    }

    private fun discoverServerLocally() {
        lifecycleScope.launch {
            val serverInfo = JellyfinDiscovery(requireContext()).discover()
            if (_binding == null) return@launch
            if (serverInfo != null && binding.etHost.text.isNullOrBlank()) {
                binding.etHost.setText(serverInfo.host)
                if (serverInfo.port != 8096) {
                    binding.etPort.setText(serverInfo.port.toString())
                }
            }
        }
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

        if (tvIp != null) {
            sendConfigToTv(payload)
        } else {
            sendConfigLocally(payload)
        }
    }

    private fun sendConfigToTv(payload: ConfigPayload) {
        val targetIp = tvIp ?: return
        binding.btnSend.isEnabled = false
        binding.tvStatus.apply { text = "Configuration en cours..."; visibility = View.VISIBLE }

        lifecycleScope.launch {
            try {
                val tokenParam = if (authToken.isNotEmpty()) "?token=$authToken" else ""
                val response: HttpResponse = httpClient.post("http://$targetIp:$tvPort/configure$tokenParam") {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
                if (_binding == null) return@launch
                if (response.status == HttpStatusCode.OK) {
                    binding.tvStatus.text = "Équipement distant configuré avec succès !"
                    kotlinx.coroutines.delay(2000)
                    if (_binding == null) return@launch
                    (activity as? PhoneActivity)?.onTvConfigSuccess()
                } else {
                    val body = response.bodyAsText()
                    binding.tvStatus.text = "Erreur : $body"
                    binding.btnSend.isEnabled = true
                }
            } catch (e: Exception) {
                if (_binding == null) return@launch
                binding.tvStatus.text = "Erreur réseau : ${e.message}"
                binding.btnSend.isEnabled = true
            }
        }
    }

    private fun sendConfigLocally(payload: ConfigPayload) {
        binding.btnSend.isEnabled = false
        binding.tvStatus.apply { text = "Connexion au serveur Jellyfin..."; visibility = View.VISIBLE }

        lifecycleScope.launch {
            val session = (requireActivity() as PhoneActivity).jellyfinSession
            val error = session.authenticate(payload)
            if (_binding == null) return@launch
            if (error == null) {
                (activity as? PhoneActivity)?.onLocalConfigSuccess()
                return@launch
            } else {
                binding.tvStatus.text = "Erreur : $error"
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
