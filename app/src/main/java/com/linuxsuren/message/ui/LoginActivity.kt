package com.linuxsuren.message.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import com.linuxsuren.message.R
import com.linuxsuren.message.data.Http
import com.linuxsuren.message.data.MessageApi
import com.linuxsuren.message.data.Settings
import com.linuxsuren.message.databinding.ActivityLoginBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Sign-in: server address + email + password. On success the server sets the
 * `mh_session` cookie (kept by the process-wide CookieManager), the server
 * URL and account are persisted, and the session cookie value is stored so
 * the next cold start skips this screen. Errors are shown as friendly
 * localized toasts — the server's `error` field message when present.
 */
class LoginActivity : Activity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var settings: Settings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)

        // Skip the form when a stored session is still around.
        val saved = settings.serverUrl
        if (saved.isNotBlank()) {
            settings.restoreSessionCookie(saved)
            if (settings.hasLiveSession(saved)) {
                startActivity(Intent(this, MessagesActivity::class.java))
                finish()
                return
            }
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.serverEdit.setText(settings.serverUrl.ifBlank { DEFAULT_SERVER_PREFIX })
        binding.emailEdit.setText(settings.email)
        binding.loginButton.setOnClickListener { attemptLogin() }
        binding.passwordEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptLogin()
                true
            } else {
                false
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun attemptLogin() {
        if (busy) {
            return
        }
        val base = Http.normalizeBase(binding.serverEdit.text.toString())
        val email = binding.emailEdit.text.toString().trim()
        val password = binding.passwordEdit.text.toString()
        val fieldError = when {
            binding.serverEdit.text.isBlank() -> R.string.err_server_required
            email.isBlank() -> R.string.err_email_required
            password.isBlank() -> R.string.err_password_required
            else -> 0
        }
        if (fieldError != 0) {
            Toast.makeText(this, fieldError, Toast.LENGTH_SHORT).show()
            return
        }
        if (!isValidUrl(base)) {
            Toast.makeText(this, R.string.err_invalid_server, Toast.LENGTH_SHORT).show()
            return
        }

        busy = true
        binding.loginButton.isEnabled = false
        binding.loginProgress.visibility = View.VISIBLE
        scope.launch {
            try {
                val user = withContext(Dispatchers.IO) { MessageApi(base).login(email, password) }
                settings.serverUrl = base
                settings.email = email
                settings.displayName = user.displayName.ifBlank { email }
                settings.persistSessionCookie(base)
                startActivity(Intent(this@LoginActivity, MessagesActivity::class.java))
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@LoginActivity, ErrorText.friendly(this@LoginActivity, e), Toast.LENGTH_LONG).show()
            } finally {
                busy = false
                binding.loginButton.isEnabled = true
                binding.loginProgress.visibility = View.GONE
            }
        }
    }

    private fun isValidUrl(base: String): Boolean = try {
        val url = URL(base)
        (url.protocol == "http" || url.protocol == "https") && url.host.isNotBlank()
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val DEFAULT_SERVER_PREFIX = "https://"
    }
}
