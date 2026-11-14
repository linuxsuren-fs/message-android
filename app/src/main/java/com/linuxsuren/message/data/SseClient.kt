package com.linuxsuren.message.data

import kotlinx.coroutines.delay
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Server-Sent Events client over [HttpURLConnection], sharing the session
 * cookie with the REST calls through the process-wide CookieManager.
 *
 * Wire-format parsing follows the SSE spec:
 * - a blank line dispatches the accumulated event;
 * - `event:` / `data:` fields, with multi-line data joined by "\n";
 * - lines starting with ':' are comments;
 * - `retry:` is consumed but ignored — the reconnect policy stays
 *   exponential (1s, 2s, 4s … capped at 60s), resetting to 1s after every
 *   connection that had actually been established.
 *
 * Lifecycle: [run] blocks inside the caller's coroutine (Dispatchers.IO) and
 * returns once [stop] is called or the coroutine is cancelled. [stop] also
 * disconnects the socket so a blocked read wakes up immediately.
 * A 401/403 from the stream stops all retries via [Listener.onSseAuthError]
 * — the session is gone and re-authentication is required.
 */
class SseClient(
    private val url: String,
    private val listener: Listener,
) {

    interface Listener {
        /** Dispatched for every complete event (e.g. "message", "heartbeat"). */
        fun onSseEvent(name: String, data: String)

        fun onSseConnected()

        fun onSseRetryIn(seconds: Int)

        fun onSseAuthError()
    }

    private var currentConn: HttpURLConnection? = null

    @Volatile
    private var stopped = false

    fun stop() {
        stopped = true
        currentConn?.disconnect()
    }

    /** Blocking connect/read/reconnect loop; call from Dispatchers.IO. */
    suspend fun run() {
        var backoffMs = INITIAL_BACKOFF_MS
        while (!stopped) {
            var connectedOnce = false
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("Accept", "text/event-stream")
                    setRequestProperty("Cache-Control", "no-cache")
                }
                currentConn = conn
                when (val status = conn.responseCode) {
                    401, 403 -> {
                        // Session expired/invalid: retrying cannot help.
                        conn.disconnect()
                        currentConn = null
                        listener.onSseAuthError()
                        return
                    }

                    !in 200..299 -> throw IOException("HTTP $status")
                }
                connectedOnce = true
                listener.onSseConnected()
                readEvents(conn)
                // Graceful stream end (server closed): fall through to reconnect.
            } catch (_: IOException) {
                // Expected on stop() or network loss; fall through to backoff.
            }
            currentConn = null
            if (stopped) {
                return
            }
            // A connection that stayed up resets the backoff: transient drop
            // reconnects fast, a server that is down backs off exponentially.
            backoffMs = if (connectedOnce) INITIAL_BACKOFF_MS else backoffMs
            listener.onSseRetryIn((backoffMs / 1000).toInt())
            // Sleep in slices so stop() stays responsive.
            var waited = 0L
            while (!stopped && waited < backoffMs) {
                val step = minOf(SLEEP_SLICE_MS, backoffMs - waited)
                delay(step)
                waited += step
            }
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private fun readEvents(conn: HttpURLConnection) {
        val reader = conn.inputStream.bufferedReader()
        var eventName = ""
        val dataLines = mutableListOf<String>()
        while (!stopped) {
            val line = reader.readLine() ?: break // end of stream
            when {
                line.isEmpty() -> {
                    if (dataLines.isNotEmpty()) {
                        listener.onSseEvent(eventName.ifBlank { DEFAULT_EVENT }, dataLines.joinToString("\n"))
                    }
                    eventName = ""
                    dataLines.clear()
                }

                line.startsWith(":") -> Unit // comment

                else -> {
                    val idx = line.indexOf(':')
                    val field = if (idx == -1) line else line.substring(0, idx)
                    var value = if (idx == -1) "" else line.substring(idx + 1)
                    if (value.startsWith(" ")) {
                        value = value.substring(1)
                    }
                    when (field) {
                        "event" -> eventName = value
                        "data" -> dataLines.add(value)
                        // "retry" / "id" are valid fields; intentionally unused.
                    }
                }
            }
        }
    }

    companion object {
        const val DEFAULT_EVENT = "message"
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val SLEEP_SLICE_MS = 500L
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 75_000 // > 3x the ~20s heartbeat
    }
}
