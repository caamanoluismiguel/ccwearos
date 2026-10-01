package com.caamano.ccwearos

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.caamano.ccwearos.data.CcwearosForegroundService
import com.caamano.ccwearos.notifications.PermissionNotifier
import com.google.firebase.database.FirebaseDatabase

// Application subclass does two startup-critical things, BEFORE any code
// touches FirebaseDatabase.getInstance() or starts the foreground service:
//
//   1. Enable Firebase Realtime DB disk persistence. setPersistenceEnabled
//      MUST be called exactly once before any other FirebaseDatabase API
//      call in the process — otherwise it throws "Persistence settings
//      cannot be changed after Database is used."
//   2. Create the NotificationChannels (foreground service + "Permisos").
//      A channel must exist BEFORE a notification is posted on it, or
//      startForeground crashes the service with IllegalArgumentException.
//
// Application.onCreate runs before any Activity / Service / Receiver
// onCreate, making it the canonical home for both.
//
// Why disk persistence matters:
//   - On screen wake from ambient, our listeners reconnect via TCP, which
//     takes 500ms-3s. During that gap, StateFlow holds the last cached
//     value the SDK has in memory. With persistence DISABLED, that cache
//     is wiped on app process death — so on cold start the user sees
//     `status=OFFLINE` for the reconnect duration ("wrapper not reachable"
//     flicker).
//   - With persistence ENABLED, Firebase stores the last server snapshot
//     on disk. On cold start the SDK loads disk → emits the cached value
//     immediately → reconnects in the background → updates if changed.
//     The user sees the real state on wake within milliseconds.
class CcwearosApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true)
        } catch (e: Exception) {
            // Idempotency: if hot-reload re-enters this code path the SDK
            // throws "Persistence settings cannot be changed after Database
            // is used." Log + carry on — the original setting is still in
            // effect.
            Log.w("ccwearos", "setPersistenceEnabled skipped: ${e.message}")
        }
        createForegroundChannel()
        PermissionNotifier.createChannel(this)
    }

    private fun createForegroundChannel() {
        // LOW importance = no sound / vibration / popup; the notification
        // shows up only in the panel (and as the Ongoing Activity chip),
        // which is what we want for an "I'm alive in background" indicator.
        // Creating a channel is idempotent.
        val channel = NotificationChannel(
            CcwearosForegroundService.CHANNEL_ID,
            "Conexión en segundo plano",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description =
                "Mantiene viva la conexión con tu Mac. Sin esto, el reloj " +
                    "pierde la conexión cada vez que se apaga la pantalla."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}
