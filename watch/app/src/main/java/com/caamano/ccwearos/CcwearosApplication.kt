package com.caamano.ccwearos

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.caamano.ccwearos.data.CcwearosForegroundService
import com.google.firebase.database.FirebaseDatabase

// Application subclass does two startup-critical things, BEFORE any code
// touches FirebaseDatabase.getInstance() or starts the foreground service:
//
//   1. Enable Firebase Realtime DB disk persistence. setPersistenceEnabled
//      MUST be called exactly once before any other FirebaseDatabase API
//      call in the process — otherwise it throws "Persistence settings
//      cannot be changed after Database is used."
//   2. Create the NotificationChannel that CcwearosForegroundService uses
//      for its ongoing notification. On Android 8+ a channel must exist
//      BEFORE the notification is posted, or startForeground crashes the
//      service with IllegalArgumentException.
//
// Application.onCreate runs before any Activity / Service onCreate, making
// it the canonical home for both.
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
    }

    private fun createForegroundChannel() {
        // NotificationChannel is required on Android 8+ (API 26). LOW
        // importance = no sound / vibration / popup; the notification
        // shows up only in the panel, which is what we want for a "I'm
        // alive in background" indicator. Creating a channel is
        // idempotent — re-creating with the same id is a no-op.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CcwearosForegroundService.CHANNEL_ID,
            "Service en background",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description =
                "Mantiene viva la conexión al wrapper en el Mac. " +
                    "Sin esto, el reloj muestra 'wrapper not reachable' " +
                    "después de cada vez que dormís la pantalla."
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(channel)
    }
}
