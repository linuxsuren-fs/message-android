package com.linuxsuren.message

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.linuxsuren.message.notification.Notifier
import java.net.CookieHandler
import java.net.CookieManager

/**
 * Process-wide setup:
 * - a [CookieManager] as the default [CookieHandler], so the session cookie
 *   (`mh_session`) is shared by every REST call and the SSE stream;
 * - the notification channel for background messages;
 * - foreground/background tracking, so a live message can decide between an
 *   in-app beep (foreground) and a system notification (background).
 */
class MessageApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CookieHandler.setDefault(CookieManager())
        Notifier.ensureChannel(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }

            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) {
                startedActivities--
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    companion object {
        @Volatile
        private var startedActivities = 0

        /** True while any activity of this process is visible to the user. */
        val isForeground: Boolean
            get() = startedActivities > 0
    }
}
