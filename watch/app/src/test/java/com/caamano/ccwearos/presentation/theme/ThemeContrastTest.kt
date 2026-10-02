package com.caamano.ccwearos.presentation.theme

import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    private fun assertAtLeast(name: String, ratio: Float, min: Float) =
        assertTrue("$name contrast $ratio < $min", ratio >= min)

    @Test fun text_tokens_meet_aa_on_black() {
        assertAtLeast("coral", contrastRatio(CcPalette.Coral, CcPalette.Black), 4.5f)
        assertAtLeast("onPrimary", contrastRatio(CcColorScheme.onPrimary, CcColorScheme.primary), 4.5f)
        assertAtLeast("textSecondary", contrastRatio(CcPalette.TextSecondary, CcPalette.Black), 4.5f)
        assertAtLeast("textSecondary on surfaceHigh", contrastRatio(CcPalette.TextSecondary, CcPalette.SurfaceHigh), 4.5f)
        listOf(
            "running" to StatusColors.running,
            "waiting" to StatusColors.waiting,
            "error" to StatusColors.error,
            "idle" to StatusColors.idle,
        ).forEach { (n, c) -> assertAtLeast(n, contrastRatio(c, CcPalette.Black), 4.5f) }
    }

    @Test fun non_text_tokens_meet_3_to_1() {
        assertAtLeast("offline", contrastRatio(StatusColors.offline, CcPalette.Black), 3f)
    }
}
