package com.caamano.ccwearos.data

import com.caamano.ccwearos.complication.ComplicationUpdater
import com.caamano.ccwearos.tile.TileUpdater

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.caamano.ccwearos.R
import com.caamano.ccwearos.notifications.OngoingStatus
import com.caamano.ccwearos.notifications.OngoingStatusMapper
import com.caamano.ccwearos.notifications.OutcomeGate
import com.caamano.ccwearos.notifications.PermissionNotifier
import com.caamano.ccwearos.notifications.RunNotifier
import com.caamano.ccwearos.presentation.MainActivity
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// Foreground service that keeps the app's process alive so our Firebase
// listeners stay subscribed across screen-off / ambient mode, and that now
// also watches for permission prompts to raise the heads-up notification.
//
// Without this, Samsung's Freecess (Galaxy Watch background freezer) pauses
// the process ~10s after screen-off, killing our listeners. On wake the
// watch shows "wrapper not reachable" for several seconds while Firebase
// reconnects. Samsung's own dev blog (2026-04-23) confirms that a
// foreground service is the supported way to opt out of that pause.
//
// The notification is an Ongoing Activity, so its status line ("Conectado a
// tu Mac" / "Trabajando…" / "Esperando permiso" / "Claude necesita tu Mac")
// shows on the watch face and
// in the recents surface.
//
// Android 15+: dataSync FGS get ~6h per 24h. When the budget runs out the
// system calls onTimeout(); we stop cleanly and MainActivity.onStart (or the
// next FCM wake) starts us again.
class CcwearosForegroundService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "ccwearos_foreground"
        private const val TAG = "ccwearos-fg"
        private const val HEADLINE_SETTLE_MS = 1_500L

        // Paths we ask Firebase to keep synced. These mirror the
        // SharingStarted.Eagerly flows in CcwearosViewModel that drive screen
        // routing and the allow/deny guard.
        private val KEEP_SYNCED_PATHS = listOf(
            "status",
            "sharedSession",
            "permissionPrompt",
            "permissionPromptId",
            "blocker",
            "outcome",
            "headline",
        )

        /**
         * Starts (or re-promotes) the service. Never throws: on Android 12+
         * a background start can be refused, and after an Android 15 dataSync
         * timeout a new start is refused until the app is foregrounded.
         */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CcwearosForegroundService::class.java),
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "start refused: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Resources aren't reachable before onCreate, so the initial text is resolved lazily.
    private var currentText: String? = null
    private var watching = false

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
            Log.w(TAG, "keepSynced failed: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to foreground within 5s of startForegroundService(), else
        // Android kills us with ForegroundServiceDidNotStartInTimeException.
        // Doing it immediately in onStartCommand keeps us well under that.
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(currentText ?: getString(R.string.ongoing_no_connection)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (e.g. dataSync budget
            // exhausted). Nothing to keep alive without foreground status.
            Log.w(TAG, "startForeground refused: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!watching) {
            watching = true
            watchState()
        }

        // START_STICKY: if the system kills us under memory pressure (rare
        // for foreground services but possible), it'll re-create us with a
        // null intent — our onStartCommand handles that fine.
        return START_STICKY
    }

    // Android 15+ (API 35): the dataSync time budget ran out. We must stop
    // within a few seconds or the system raises a crash. The service comes
    // back from MainActivity.onStart (foreground start is always allowed) or
    // an FCM wake once the budget refills.
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "FGS timeout (type=$fgsType); stopping until next foreground")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun watchState() {
        val repo = CcwearosRepository()
        // Ongoing Activity status line.
        scope.launch {
            combine(repo.status, repo.connected, repo.blocker) { s, c, b -> statusText(s, c, b != null) }
                .distinctUntilChanged()
                .collect { text ->
                    currentText = text
                    // Tiles and complications can't listen; nudge them on
                    // every visible state change (system throttles these).
                    TileUpdater.requestUpdate(this@CcwearosForegroundService)
                    ComplicationUpdater.requestUpdate(this@CcwearosForegroundService)
                    // Re-post the same id: refreshes both the content text and
                    // the Ongoing Activity status. Silent (channel is LOW).
                    val ctx = this@CcwearosForegroundService
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        runCatching {
                            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, buildNotification(text))
                        }.onFailure { Log.w(TAG, "status refresh failed: ${it.message}") }
                    }
                }
        }
        // Permission prompt notification.
        scope.launch {
            combine(
                repo.status,
                repo.permissionPrompt,
                repo.permissionPromptId,
                AppVisibility.foreground,
            ) { s, prompt, id, visible -> PermissionSnapshot(s, prompt, id) to visible }
                .distinctUntilChanged()
                .collect { (snapshot, visible) ->
                    PermissionNotifier.update(this@CcwearosForegroundService, snapshot, visible)
                }
        }
        // Tile + complication show the blocker and the last outcome too.
        scope.launch {
            combine(repo.blocker, repo.outcome, repo.headline) { b, o, h -> Triple(b, o, h) }
                .distinctUntilChanged()
                .collect {
                    TileUpdater.requestUpdate(this@CcwearosForegroundService)
                    ComplicationUpdater.requestUpdate(this@CcwearosForegroundService)
                }
        }
        // "Claude necesita tu Mac" notification (only while the app is hidden).
        scope.launch {
            combine(repo.blocker, AppVisibility.foreground) { b, visible -> b to visible }
                .distinctUntilChanged()
                .collect { (blocker, visible) ->
                    RunNotifier.updateBlocker(this@CcwearosForegroundService, blocker, visible)
                }
        }
        // Opening the app clears "Claude terminó": the app shows the result itself.
        scope.launch {
            AppVisibility.foreground.collect { visible ->
                if (visible) RunNotifier.cancelAll(this@CcwearosForegroundService)
            }
        }
        // "Claude terminó" notification, once per finished run.
        val headline = repo.headline.stateIn(scope, SharingStarted.Eagerly, null)
        val outcomeGate = OutcomeGate()
        scope.launch {
            repo.outcome.distinctUntilChanged().collect { outcome ->
                if (!outcomeGate.shouldNotify(outcome) || outcome == null) return@collect
                // The wrapper may write /headline right after /outcome.
                delay(HEADLINE_SETTLE_MS)
                RunNotifier.runFinished(
                    this@CcwearosForegroundService,
                    outcome,
                    headline.value,
                    AppVisibility.foreground.value,
                )
            }
        }
    }

    private fun statusText(status: WrapperStatus, connected: Boolean, blocked: Boolean): String =
        getString(
            when (OngoingStatusMapper.map(status, connected, blocked)) {
                OngoingStatus.NO_CONNECTION -> R.string.ongoing_no_connection
                OngoingStatus.AWAITING -> R.string.ongoing_awaiting
                OngoingStatus.BLOCKED -> R.string.ongoing_blocked
                OngoingStatus.RUNNING -> R.string.ongoing_running
                OngoingStatus.MAC_OFFLINE -> R.string.ongoing_mac_offline
                OngoingStatus.CONNECTED -> R.string.ongoing_connected
            },
        )

    private fun buildNotification(text: String): Notification {
        val tapIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(R.mipmap.ic_launcher)
            .setTouchIntent(pendingIntent)
            .setStatus(Status.forPart(Status.TextPart(text)))
            .build()
            .apply(applicationContext)
        return builder.build()
    }
}
