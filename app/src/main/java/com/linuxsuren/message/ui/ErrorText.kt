package com.linuxsuren.message.ui

import android.content.Context
import com.linuxsuren.message.R
import com.linuxsuren.message.data.ErrorKind
import com.linuxsuren.message.data.HttpException

/**
 * Maps transport/API failures to friendly localized toast texts. The
 * server's own human-readable message (the `error` JSON field) wins when
 * present; raw technical errors are never surfaced to the user.
 */
object ErrorText {

    fun friendly(context: Context, e: Exception): String = when (e) {
        is HttpException -> when {
            e.serverMessage != null -> e.serverMessage
            e.kind == ErrorKind.UNAUTHORIZED -> context.getString(R.string.err_bad_credentials)
            e.kind == ErrorKind.TIMEOUT -> context.getString(R.string.err_timeout)
            e.kind == ErrorKind.NETWORK -> context.getString(R.string.err_network)
            e.kind == ErrorKind.FORBIDDEN -> context.getString(R.string.err_forbidden)
            else -> context.getString(R.string.err_server, e.status)
        }

        else -> context.getString(R.string.err_network)
    }
}
