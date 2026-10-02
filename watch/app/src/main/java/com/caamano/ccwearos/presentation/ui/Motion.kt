package com.caamano.ccwearos.presentation.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

// One motion vocabulary for the whole app. Every animation picks from these
// tokens so the watch feels like one instrument, not a pile of effects.
//
// Feedback contract — every user action answers four questions, each with a
// visual AND a haptic (Haptics.kt) so they work without looking:
//   1. ¿Qué hice?        Instant press response (<100ms): scale to 0.96 +
//                         Haptics.tick. The control changes label/state.
//   2. ¿Qué está pasando? A living state, never a frozen screen: the mascot
//                         animates, a progress/elapsed line updates, the
//                         spoken text stays visible while sending.
//   3. ¿Qué puedo hacer?  Exactly one primary action per screen, visibly
//                         primary (coral). Secondary actions are outlined.
//   4. ¿Salió bien?       A distinct ending: success = Haptics.done + mascot
//                         hop + ring sweep; failure = Haptics.error + X eyes +
//                         one-line reason + one recovery action.
//
// Respect reduced motion: when animations are disabled system-wide
// (ANIMATOR_DURATION_SCALE == 0) skip decorative motion, keep state changes.
object Motion {
    /** Press feedback, chip/button state flips. */
    const val FAST = 120

    /** Most enter/exit transitions, card expand. */
    const val MEDIUM = 240

    /** Screen-level transitions, result reveal. */
    const val SLOW = 380

    /** Celebration (done ring sweep, mascot hop). */
    const val CELEBRATE = 700

    /** Material "emphasized decelerate": things arriving. */
    val EnterEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Material "emphasized accelerate": things leaving. */
    val ExitEasing: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Standard for in-place changes. */
    val StandardEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Scale a pressed control animates to. */
    const val PRESSED_SCALE = 0.96f

    fun <T> enter(durationMs: Int = MEDIUM) = tween<T>(durationMs, easing = EnterEasing)

    fun <T> exit(durationMs: Int = FAST) = tween<T>(durationMs, easing = ExitEasing)

    fun <T> standard(durationMs: Int = MEDIUM) = tween<T>(durationMs, easing = StandardEasing)

    /** Gentle physical settle for cards/chips arriving. */
    fun <T> settle() = spring<T>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)

    /** Stagger between list items revealing in sequence. */
    const val STAGGER = 40
}
