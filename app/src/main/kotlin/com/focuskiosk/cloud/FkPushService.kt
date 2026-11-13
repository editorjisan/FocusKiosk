package com.focuskiosk.cloud

import android.os.UserManager
import android.util.Log
import com.focuskiosk.alarm.AlarmStore
import com.focuskiosk.alarm.ExtremeAlarmManager
import com.focuskiosk.policy.WorkWindowScheduler
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * The cloud timer pushes at the exact minute of every upload window open/close and wake-up alarm.
 * The push thaws the frozen app; here we do what the held-back alarm should have done.
 * Everything is idempotent, so a duplicate push (the timer also re-sends a minute later) is harmless.
 */
class FkPushService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val app = applicationContext
        val um = getSystemService(UserManager::class.java)
        if (um != null && !um.isUserUnlocked) return
        Log.i(TAG, "cloud wake-up ${message.data}")

        // The push can land a few seconds early: wait for the moment (this runs off the main thread).
        val now = System.currentTimeMillis()
        val soon = FkCloud.upcomingTimes(app, now).firstOrNull { it in now..(now + 15_000L) }
        if (soon != null) runCatching { Thread.sleep(soon - now + 400L) }

        // Upload window: opens / closes / re-arms exactly as the alarm would have.
        runCatching { WorkWindowScheduler.checkAndSyncWindow(app) }
            .onFailure { Log.e(TAG, "window sync failed: ${it.message}") }

        // Wake-up alarm: ring it now if its minute has come and it has not rung yet.
        runCatching {
            val due = AlarmStore.nextAlarmMs(app)
            val t = System.currentTimeMillis()
            if (due > 0L && due <= t + 1_000L && t - due < 5 * 60_000L && !AlarmStore.isRinging(app)) {
                Log.i(TAG, "wake-up alarm was held back by the system — ringing now")
                ExtremeAlarmManager.onTriggered(app, AlarmStore.nextAlarmId(app))
            }
        }.onFailure { Log.e(TAG, "alarm check failed: ${it.message}") }

        FkCloud.report(app)
    }

    override fun onNewToken(token: String) {
        FkCloud.saveToken(applicationContext, token)
    }

    private companion object {
        const val TAG = "FkPush"
    }
}
