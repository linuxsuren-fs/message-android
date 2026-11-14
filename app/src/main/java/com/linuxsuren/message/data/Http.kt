package com.linuxsuren.message.data

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Error classification shared by all message-hub endpoints.
 * Raw transport errors are never surfaced to users; activities map
 * [ErrorKind] to friendly localized strings.
 */
enum class ErrorKind { NETWORK, TIMEOUT, UNAUTHORIZED, FORBIDDEN, SERVER }

/**
 * Classified transport/API error. [serverMessage] carries the human-readable
 * text from the server's `{"error": "..."}` body when present (the API
 * contract guarantees one for every non-2xx response); the exception [detail]
 * is for logs only, never for the UI.
 */
class HttpException(
    val kind: ErrorKind,
    val status: Int,
    val serverMessage: String?,
    detail: String,
) : IOException("$kind/$status: $detail")

/** Successful response payload; body is the raw response text (JSON). */
class HttpResult(val status: Int, val body: String)

/**
 * Minimal HTTP wrapper on top of [java.net.HttpURLConnection], in the style of
 * the reference project's data/Http.kt.
 *
 * - 15s connect / 20s read timeout by default;
 * - the session cookie flows automatically through the process-wide
 *   CookieManager installed by the Application class — no manual header here;
 * - classifies every failure into one of the [ErrorKind]s.
 *
 * Blocking; callers must invoke from a background dispatcher.
 */
object Http {

    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 20_000

    /**
     * Accepts "host:port", "host/path" or a full URL; https:// is assumed when
     * the scheme is missing. Returns the origin without a trailing slash.
     */
    fun normalizeBase(address: String): String {
        var base = address.trim()
        if (!base.startsWith("http://", ignoreCase = true) &&
            !base.startsWith("https://", ignoreCase = true)
        ) {
            base = "https://$base"
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length - 1)
        }
        return base
    }

    fun get(url: String, timeoutMs: Int = READ_TIMEOUT_MS): HttpResult =
        request("GET", url, null, timeoutMs)

    fun post(url: String, body: String, timeoutMs: Int = READ_TIMEOUT_MS): HttpResult =
        request("POST", url, body, timeoutMs)

    /** Extracts the human-readable message from a `{"error": "..."}` body, or null. */
    fun serverErrorMessage(body: String): String? {
        val text = body.trim()
        if (!text.startsWith("{")) {
            return null
        }
        return try {
            JSONObject(text).optString("error").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun request(method: String, url: String, body: String?, timeoutMs: Int): HttpResult {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    doOutput = true
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (status in 200..299) {
                return HttpResult(status, text)
            }
            throw HttpException(classify(status), status, serverErrorMessage(text), text.take(200))
        } catch (e: HttpException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw HttpException(ErrorKind.TIMEOUT, 0, null, e.message ?: "timeout")
        } catch (e: UnknownHostException) {
            throw HttpException(ErrorKind.NETWORK, 0, null, e.message ?: "unknown host")
        } catch (e: Exception) {
            throw HttpException(ErrorKind.NETWORK, 0, null, e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    private fun classify(status: Int): ErrorKind = when (status) {
        401 -> ErrorKind.UNAUTHORIZED
        403 -> ErrorKind.FORBIDDEN
        else -> ErrorKind.SERVER
    }
}
