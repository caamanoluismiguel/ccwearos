package com.caamano.ccwearos.presentation

import android.Manifest
import android.app.LocaleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import android.os.LocaleList
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.caamano.ccwearos.R
import com.caamano.ccwearos.data.AppVisibility
import com.caamano.ccwearos.data.CcwearosForegroundService
import com.caamano.ccwearos.notifications.PermissionNotifier
import com.caamano.ccwearos.presentation.theme.CCWEAROSTheme
import com.caamano.ccwearos.presentation.ui.MascotState
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.caamano.ccwearos.notifications.DeepLinks
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeepLinks.capture(intent)
        pinSpanishLocale()
        signInThenRender()
        maybeRequestNotificationPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLinks.capture(intent)
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.foreground.value = true
        // The PermissionScreen owns any pending prompt while we're visible.
        PermissionNotifier.cancel(this)

        // Start (or re-start) the foreground service that keeps our Firebase
        // listener alive across ambient / screen-off. Samsung's Freecess
        // freezes background processes within 60s of the screen turning off
        // (often sooner — we observed ~10s on real watch). A
        // foregroundServiceType="dataSync" service is the ONLY supported way
        // on Wear OS to opt out of that freezing.
        //
        // In onStart (not onCreate) so the service comes back every time the
        // app is foregrounded, e.g. after the Android 15 dataSync timeout
        // stopped it. Starting an already-running service is a no-op.
        CcwearosForegroundService.start(this)
    }

    override fun onStop() {
        AppVisibility.foreground.value = false
        super.onStop()
    }

    private fun signInThenRender() {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            registerFcmToken()
            renderApp()
            return
        }
        // First launch (or after pm clear) — sign in anonymously THEN render.
        // Otherwise Firebase Realtime DB rules reject reads with "permission
        // denied" before the anonymous user is established. On failure show a
        // retry screen instead of rendering an app that can't read anything.
        auth.signInAnonymously().addOnCompleteListener { task ->
            if (task.isSuccessful && auth.currentUser != null) {
                registerFcmToken()
                renderApp()
            } else {
                Log.w(TAG, "anonymous sign-in failed: ${task.exception?.message}")
                setContent {
                    CCWEAROSTheme {
                        SignInErrorScreen(onRetry = ::signInThenRender)
                    }
                }
            }
        }
    }

    // The owner speaks Spanish but the watch OS runs in English. values/ is
    // Spanish-only already; pinning es-CO as the per-app locale (API 33+) also
    // gives Spanish dates/numbers and keeps it if a translation is ever added.
    // Only when unset, so a choice made in system per-app language settings
    // wins. Setting it recreates the activity once.
    private fun pinSpanishLocale() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        runCatching {
            val manager = getSystemService(LocaleManager::class.java) ?: return
            if (manager.applicationLocales.isEmpty) {
                manager.applicationLocales = LocaleList.forLanguageTags(APP_LOCALE)
            }
        }.onFailure { Log.w(TAG, "could not pin app locale: ${it.message}") }
    }

    // Wear OS 4+ (API 33+) needs runtime consent for notifications, or the
    // "Claude necesita permiso" heads-up never shows. Asked once; after that
    // the user decides in system settings.
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return
        prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
        // ActivityCompat rather than registerForActivityResult: no result
        // handling is needed, and the latter trips lintVital's
        // InvalidFragmentVersionForActivityResult on the transitive
        // fragment 1.0 pulled in by play-services.
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
    }

    // Push the current FCM token to /fcmToken so the wrapper can target this
    // device for wake-ups. onNewToken in CcwearosMessagingService fires when
    // the token rotates; this call covers the steady-state launch case.
    private fun registerFcmToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                if (!token.isNullOrBlank()) {
                    runCatching {
                        FirebaseDatabase.getInstance()
                            .getReference("fcmToken")
                            .setValue(token)
                    }
                }
            }
        }
    }

    private fun renderApp() {
        setContent {
            CCWEAROSTheme {
                WearApp()
            }
        }
    }

    private companion object {
        const val TAG = "ccwearos-main"
        const val PREFS = "ccwearos"
        const val KEY_ASKED_NOTIFICATIONS = "asked_post_notifications"
        const val REQ_NOTIFICATIONS = 1
        const val APP_LOCALE = "es-CO"
    }
}

// Same designed state as BlockedScreen: still X-eyed mascot, one title, one
// line, one coral "Reintentar".
@Composable
private fun SignInErrorScreen(onRetry: () -> Unit) {
    BlockedLayout(
        mascot = MascotState.Error,
        title = stringResource(R.string.signin_error_title),
        body = stringResource(R.string.signin_error_body),
        primaryLabel = stringResource(R.string.signin_error_action),
        onPrimary = onRetry,
        haptic = BlockedHaptic.ERROR,
    )
}
