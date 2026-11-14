package com.linuxsuren.message.data

import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder
import java.time.OffsetDateTime

data class TenantInfo(val id: Long, val name: String)

data class UserInfo(
    val id: Long,
    val email: String,
    val displayName: String,
    val role: String,
    val tenant: TenantInfo?,
)

data class MessageItem(
    val id: Long,
    val tenantId: Long,
    val topic: String,
    val title: String,
    val body: String,
    val level: String,
    val isPublic: Boolean,
    val createdAtMs: Long,
) {
    companion object {
        fun fromJson(o: JSONObject): MessageItem {
            val level = o.optString("level", MessageApi.LEVEL_INFO)
            return MessageItem(
                id = o.optLong("id", 0),
                tenantId = o.optLong("tenant_id", 0),
                topic = o.optString("topic"),
                title = o.optString("title"),
                body = o.optString("body"),
                level = if (level in MessageApi.LEVELS) level else MessageApi.LEVEL_INFO,
                isPublic = o.optBoolean("public", false),
                createdAtMs = parseIso8601(o.optString("created_at")),
            )
        }

        /** "2026-11-14T10:00:00Z" (or any offset form) → epoch millis; 0 when unparsable. */
        fun parseIso8601(text: String): Long = try {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}

data class MessagePage(val items: List<MessageItem>, val nextCursor: String?)

/**
 * Typed client for the message-hub REST API (frozen contract; JSON via the
 * platform org.json). Session state lives in the process-wide CookieManager:
 * [login] stores the 12h `mh_session` cookie there, and every later request —
 * including the SSE stream — sends it automatically.
 *
 * All methods are blocking and must run on Dispatchers.IO.
 */
class MessageApi(private val baseUrl: String) {

    /** Signs in with email + password; returns the signed-in user. */
    fun login(email: String, password: String): UserInfo {
        val payload = JSONObject()
            .put("email", email)
            .put("password", password)
            .toString()
        return parseUser(Http.post("$baseUrl/api/v1/auth/login", payload).body)
    }

    /**
     * Lists messages, newest first. [scope]: all (own tenant + public),
     * own (own tenant only) or public.
     */
    fun listMessages(limit: Int = 50, cursor: String? = null, scope: String = SCOPE_ALL): MessagePage {
        val url = buildString {
            append(baseUrl)
            append("/api/v1/messages?limit=").append(limit.coerceIn(1, 200))
            append("&scope=").append(scope)
            if (!cursor.isNullOrBlank()) {
                append("&cursor=").append(URLEncoder.encode(cursor, "UTF-8"))
            }
        }
        val root = json(Http.get(url).body)
        val array = root.optJSONArray("items")
        val items = if (array == null) {
            emptyList()
        } else {
            (0 until array.length())
                .mapNotNull { i -> array.optJSONObject(i)?.let(MessageItem::fromJson) }
        }
        val next = if (root.isNull("next_cursor")) {
            null
        } else {
            root.optString("next_cursor").takeIf { it.isNotBlank() }
        }
        return MessagePage(items, next)
    }

    /** URL of the SSE live stream (shares the session cookie). */
    fun streamUrl(): String = "$baseUrl/api/v1/stream"

    private fun parseUser(body: String): UserInfo {
        val o = json(body)
        val tenant = o.optJSONObject("tenant")
            ?.let { TenantInfo(it.optLong("id", 0), it.optString("name")) }
        return UserInfo(
            id = o.optLong("id", 0),
            email = o.optString("email"),
            displayName = o.optString("display_name"),
            role = o.optString("role"),
            tenant = tenant,
        )
    }

    private fun json(body: String): JSONObject = try {
        JSONObject(body)
    } catch (e: JSONException) {
        throw HttpException(ErrorKind.SERVER, 0, null, "malformed JSON: ${e.message}")
    }

    companion object {
        const val SCOPE_ALL = "all"
        const val SCOPE_OWN = "own"
        const val SCOPE_PUBLIC = "public"

        const val LEVEL_INFO = "info"
        const val LEVEL_WARNING = "warning"
        const val LEVEL_ERROR = "error"
        val LEVELS = setOf(LEVEL_INFO, LEVEL_WARNING, LEVEL_ERROR)

        /** Parses the data payload of an SSE `message` event; null when malformed. */
        fun parseMessageEvent(data: String): MessageItem? = try {
            MessageItem.fromJson(JSONObject(data))
        } catch (_: Exception) {
            null
        }
    }
}
