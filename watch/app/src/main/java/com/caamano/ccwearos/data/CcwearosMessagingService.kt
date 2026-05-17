package com.caamano.ccwearos.data

import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives wake-up FCM messages from the wrapper. The high-priority data
 * payload alone is enough to wake the app out of doze/ambient — the actual
 * permission state is then read from Firebase RTDB by the existing
 * CcwearosRepository listeners.
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
        // No-op: receipt of a high-priority data message is the wake signal.
        // The status/permissionPrompt listeners on our existing repository will
        // pick up the actual state change from Firebase as the user re-engages.
    }
}
