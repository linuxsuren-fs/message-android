package com.linuxsuren.message.data

import android.content.Context
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpCookie
import java.net.URI

/**
 * Persists the server address and the last signed-in account (email + display
 * name for the header) in SharedPreferences. The password is never stored —
 * only the 12h session cookie value, so a cold start can skip sign-in until
 * the cookie expires; when it does, the user simply signs in again.
 */
class Settings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("message_hub", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, "")?.trim().orEmpty()
        set(value) {
            prefs.edit().putString(KEY_SERVER, value.trim()).apply()
        }

    var email: String
        get() = prefs.getString(KEY_EMAIL, "")?.trim().orEmpty()
        set(value) {
            prefs.edit().putString(KEY_EMAIL, value.trim()).apply()
        }

    var displayName: String
        get() = prefs.getString(KEY_DISPLAY_NAME, "")?.trim().orEmpty()
        set(value) {
            prefs.edit().putString(KEY_DISPLAY_NAME, value.trim()).apply()
        }

    /** Raw value of the `mh_session` cookie from the last successful sign-in. */
    private var sessionValue: String
        get() = prefs.getString(KEY_SESSION, "")?.trim().orEmpty()
        set(value) {
            prefs.edit().putString(KEY_SESSION, value.trim()).apply()
        }

    /** Drops the session everywhere: stored value + live CookieManager. */
    fun clearSession() {
        sessionValue = ""
        (CookieHandler.getDefault() as? CookieManager)?.cookieStore?.removeAll()
    }

    /** "Sign out": forgets the account and the session. */
    fun forgetAccount() {
        email = ""
        displayName = ""
        clearSession()
    }

    /** Saves the live `mh_session` cookie so the next cold start can skip sign-in. */
    fun persistSessionCookie(base: String) {
        val cookie = findLiveCookie(base) ?: return
        sessionValue = cookie.value
    }

    /** Re-injects the stored session cookie into the CookieManager (no-op without one). */
    fun restoreSessionCookie(base: String) {
        if (base.isBlank() || sessionValue.isBlank()) {
            return
        }
        val manager = CookieHandler.getDefault() as? CookieManager ?: return
        if (findLiveCookie(base) != null) {
            return
        }
        val host = baseHost(base) ?: return
        try {
            val cookie = HttpCookie(SESSION_COOKIE_NAME, sessionValue).apply {
                domain = host
                path = "/"
                version = 0
            }
            // Register under both schemes so the cookie is found whichever
            // form the base URL uses.
            cookieUris(base).forEach { manager.cookieStore.add(it, cookie) }
        } catch (_: Exception) {
            // Corrupt stored state: fall through, the user just signs in again.
        }
    }

    /** True when the CookieManager currently holds a usable session cookie for [base]. */
    fun hasLiveSession(base: String): Boolean = findLiveCookie(base) != null

    private fun findLiveCookie(base: String): HttpCookie? {
        val manager = CookieHandler.getDefault() as? CookieManager ?: return null
        return cookieUris(base).asSequence()
            .mapNotNull { uri ->
                manager.cookieStore.get(uri).firstOrNull {
                    it.name == SESSION_COOKIE_NAME && it.value.isNotBlank()
                }
            }
            .firstOrNull()
    }

    private fun cookieUris(base: String): List<URI> {
        val host = baseHost(base) ?: return emptyList()
        return listOf("https", "http").mapNotNull { scheme ->
            runCatching { URI("$scheme://$host") }.getOrNull()
        }
    }

    private fun baseHost(base: String): String? =
        runCatching { URI(base).host }.getOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        const val SESSION_COOKIE_NAME = "mh_session"
        private const val KEY_SERVER = "server"
        private const val KEY_EMAIL = "email"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_SESSION = "session"
    }
}
