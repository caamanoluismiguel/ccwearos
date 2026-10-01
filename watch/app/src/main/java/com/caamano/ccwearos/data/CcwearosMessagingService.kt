package com.caamano.ccwearos.data

import android.util.Log
import com.caamano.ccwearos.notifications.PermissionNotifier
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking

/**
 * Receives wake-up FCM messages from the wrapper (`data.type = "permission"`).
 *
 * The payload carries no prompt text, so on a permission wake we read
 * /status + /permissionPrompt + /permissionPromptId once and hand them to
 * [PermissionNotifier], which posts the heads-up notification (deduped by
 * prompt id against the foreground service's live listener). We also try to
 * restart the foreground service, which may have been stopped by the
 * Android 15 dataSync time limit; a high-priority FCM message is one of the
 * allowed background-start exemptions, and the start is a no-op if it runs.
 *
 * onMessageReceived runs on a background thread and has ~10s, so blocking
 * here for a bounded read is the documented pattern.
 *
 * Registered in AndroidManifest.xml under the standard
 * com.google.firebase.MESSAGING_EVENT intent filter.
 */
class CcwearosMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Push the new token to /fcmToken so the wrapper can target this watch.
        runCatching {
            FirebaseDatabase.getInstance().getReference("fcmToken").setValue(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        if (message.data["type"] != "permission") return

        CcwearosForegroundService.start(applicationContext)

        val snapshot = runBlocking { CcwearosRepository().fetchPermissionSnapshot() }
        if (snapshot == null) {
            Log.w(TAG, "permission wake: state read failed")
            return
        }
        PermissionNotifier.update(applicationContext, snapshot, AppVisibility.foreground.value)
    }

    private companion object {
        const val TAG = "ccwearos-fcm"
    }
}
