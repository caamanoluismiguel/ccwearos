package com.caamano.ccwearos.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import com.caamano.ccwearos.data.AppVisibility
import com.caamano.ccwearos.data.CcwearosForegroundService
import com.caamano.ccwearos.notifications.PermissionNotifier
import com.caamano.ccwearos.presentation.theme.CCWEAROSTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        signInThenRender()
        maybeRequestNotificationPermission()
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
    }
}

@Composable
private fun SignInErrorScreen(onRetry: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "No pude conectarme",
                color = Color.White,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Revisa la conexión del reloj e intenta de nuevo.",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRetry) {
                Text("Reintentar")
            }
        }
    }
}
