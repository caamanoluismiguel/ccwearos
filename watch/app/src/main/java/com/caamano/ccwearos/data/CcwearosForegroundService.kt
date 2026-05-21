package com.caamano.ccwearos.data

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.caamano.ccwearos.R
import com.caamano.ccwearos.presentation.MainActivity
import com.google.firebase.database.FirebaseDatabase

// Foreground service whose ONLY job is to keep the app's process alive so
// our Firebase listeners stay subscribed across screen-off / ambient mode.
//
// Without this, Samsung's Freecess (Galaxy Watch background freezer) pauses
// the process ~10s after screen-off, killing our listeners. On wake the
// watch shows "wrapper not reachable" for several seconds while Firebase
// reconnects. Samsung's own dev blog (2026-04-23) confirms that a
// foreground service is the supported way to opt out of that pause.
//
// We use `keepSynced(true)` on the three routing-critical paths so Firebase
// keeps the local cache fresh even when no UI is collecting. Combined with
// the persistent disk cache (CcwearosApplication.setPersistenceEnabled),
// the watch shows the real state instantly on wake.
class CcwearosForegroundService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "ccwearos_foreground"

        // Paths we ask Firebase to keep synced. These mirror the three
        // SharingStarted.Eagerly flows in CcwearosViewModel — the ones that
        // drive screen routing + permission haptic. Keeping their cache hot
        // means the watch shows the real value on wake without waiting for
        // a network round-trip.
        private val KEEP_SYNCED_PATHS = listOf(
            "status",
            "sharedSession",
            "permissionPrompt",
        )
    }

    override fun onCreate() {
        super.onCreate()
        try {
            for (path in KEEP_SYNCED_PATHS) {
                FirebaseDatabase.getInstance().getReference(path).keepSynced(true)
            }
        } catch (e: Exception) {
            // Persistence may not be initialized yet if this service somehow
            // starts before Application.onCreate (shouldn't happen, but be
            // defensive — the worst case is just slightly slower wake state).
            Log.w("ccwearos-fg", "keepSynced failed: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to foreground within 5s of startForegroundService(), else
        // Android kills us with ForegroundServiceDidNotStartInTimeException.
        // Doing it immediately in onStartCommand keeps us well under that.
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // START_STICKY: if the system kills us under memory pressure (rare
        // for foreground services but possible), it'll re-create us with a
        // null intent — our onStartCommand handles that fine.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("CCWEAROS")
            .setContentText("conectado al wrapper")
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }
}
