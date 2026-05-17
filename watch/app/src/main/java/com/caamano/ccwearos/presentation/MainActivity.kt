package com.caamano.ccwearos.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.caamano.ccwearos.presentation.theme.CCWEAROSTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            registerFcmToken()
            renderApp()
        } else {
            // First launch (or after pm clear) — sign in anonymously THEN render.
            // Otherwise Firebase Realtime DB rules reject reads with "permission
            // denied" before the anonymous user is established, which crashes
            // the Flow listeners.
            auth.signInAnonymously().addOnCompleteListener {
                registerFcmToken()
                renderApp()
            }
        }
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
}
