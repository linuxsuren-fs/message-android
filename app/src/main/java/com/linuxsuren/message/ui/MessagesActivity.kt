package com.linuxsuren.message.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.linuxsuren.message.MessageApp
import com.linuxsuren.message.R
import com.linuxsuren.message.data.ErrorKind
import com.linuxsuren.message.data.HttpException
import com.linuxsuren.message.data.MessageApi
import com.linuxsuren.message.data.Settings
import com.linuxsuren.message.data.SseClient
import com.linuxsuren.message.databinding.ActivityMessagesBinding
import com.linuxsuren.message.notification.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Message list: scope filter (all / own tenant / public), manual refresh and
 * a live SSE stream. Incoming `message` events are inserted at the top of
 * the list; in the foreground a light beep plays, in the background a system
 * notification is posted (Android 13+ asks for POST_NOTIFICATIONS once).
 *
 * The stream job lives in an activity-local scope and is cancelled in
 * onDestroy, per the "no work after leave" rule.
 */
class MessagesActivity : Activity() {

    private lateinit var binding: ActivityMessagesBinding
    private lateinit var settings: Settings
    private lateinit var api: MessageApi
    private lateinit var adapter: MessageAdapter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null
    private var sse: SseClient? = null
    private var tone: ToneGenerator? = null
    private var currentScope: String = MessageApi.SCOPE_ALL
    private var authHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = Settings(this)
        val base = settings.serverUrl
        if (base.isBlank()) {
            goLogin()
            return
        }
        settings.restoreSessionCookie(base)
        api = MessageApi(base)

        binding = ActivityMessagesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.headerAccount.text =
            getString(R.string.signed_in_as, settings.displayName.ifBlank { settings.email })

        adapter = MessageAdapter()
        binding.messageList.layoutManager = LinearLayoutManager(this)
        binding.messageList.adapter = adapter

        binding.scopeGroup.setOnCheckedChangeListener { _, checkedId ->
            currentScope = when (checkedId) {
                R.id.scope_own -> MessageApi.SCOPE_OWN
                R.id.scope_public -> MessageApi.SCOPE_PUBLIC
                else -> MessageApi.SCOPE_ALL
            }
            reload()
        }
        binding.refreshButton.setOnClickListener { reload() }
        binding.menuButton.setOnClickListener { showOverflowMenu() }

        binding.streamStatus.text = getString(R.string.sse_connecting)

        requestNotificationPermissionIfNeeded()
        reload()
        startStream()
    }

    override fun onDestroy() {
        sse?.stop()
        tone?.release()
        tone = null
        scope.cancel()
        super.onDestroy()
    }

    private fun reload() {
        loadJob?.cancel()
        binding.emptyView.visibility = View.GONE
        binding.listProgress.visibility = View.VISIBLE
        loadJob = scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { api.listMessages(scope = currentScope) }
                adapter.submit(page.items)
                binding.listProgress.visibility = View.GONE
                binding.emptyView.visibility =
                    if (page.items.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.listProgress.visibility = View.GONE
                if (e is HttpException && e.kind == ErrorKind.UNAUTHORIZED) {
                    onSessionExpired()
                } else {
                    Toast.makeText(this@MessagesActivity, ErrorText.friendly(this@MessagesActivity, e), Toast.LENGTH_LONG).show()
                }
                if (adapter.itemCount == 0) {
                    binding.emptyView.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun startStream() {
        val client = SseClient(api.streamUrl(), object : SseClient.Listener {
            override fun onSseEvent(name: String, data: String) {
                // "heartbeat" (and anything unknown) is ignored for the list.
                runOnUiThread { if (name == SseClient.DEFAULT_EVENT) onLiveMessage(data) }
            }

            override fun onSseConnected() {
                runOnUiThread { binding.streamStatus.text = getString(R.string.sse_connected) }
            }

            override fun onSseRetryIn(seconds: Int) {
                runOnUiThread { binding.streamStatus.text = getString(R.string.sse_reconnecting, seconds) }
            }

            override fun onSseAuthError() {
                runOnUiThread { onSessionExpired() }
            }
        })
        sse = client
        scope.launch { withContext(Dispatchers.IO) { client.run() } }
    }

    /** A live `message` event arrived: top-insert + beep (foreground) or notify (background). */
    private fun onLiveMessage(data: String) {
        if (isFinishing || isDestroyed) {
            return
        }
        val item = MessageApi.parseMessageEvent(data) ?: return
        val inserted = adapter.prepend(item)
        binding.emptyView.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
        if (inserted) {
            if (MessageApp.isForeground) {
                beep()
            } else {
                Notifier.notifyMessage(this, item)
            }
        }
    }

    private fun beep() {
        try {
            val generator = tone ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, BEEP_VOLUME)
                .also { tone = it }
            generator.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
        } catch (_: RuntimeException) {
            // Tone generator resources are occasionally busy; a missed beep is fine.
        }
    }

    private fun onSessionExpired() {
        if (authHandled) {
            return
        }
        authHandled = true
        settings.clearSession()
        Toast.makeText(this, R.string.err_session_expired, Toast.LENGTH_LONG).show()
        goLogin()
    }

    private fun showOverflowMenu() {
        val popup = PopupMenu(this, binding.menuButton)
        popup.menuInflater.inflate(R.menu.menu_messages, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                // Both return to the sign-in screen with the session cleared;
                // switching servers keeps the remembered account, signing out
                // forgets it.
                R.id.menu_switch_server -> {
                    settings.clearSession()
                    goLogin()
                    true
                }

                R.id.menu_logout -> {
                    settings.forgetAccount()
                    goLogin()
                    true
                }

                else -> false
            }
        }
        popup.show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
        }
    }

    private fun goLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 1001
        private const val BEEP_VOLUME = 80
        private const val BEEP_MS = 120
    }
}
