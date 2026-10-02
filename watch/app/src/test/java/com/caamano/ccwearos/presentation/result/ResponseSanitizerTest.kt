package com.caamano.ccwearos.presentation.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseSanitizerTest {

    private fun clean(raw: String): String {
        val r = ResponseSanitizer.sanitize(raw)
        assertTrue("expected Clean, got $r", r is SanitizeResult.Clean)
        return (r as SanitizeResult.Clean).text
    }

    @Test
    fun `real leaked trust dialog is blocked`() {
        val leaked = "Accessingworkspace:/Users/luis/projects/x\n❯No,exit\nYes,Itrustthisfolder\n" +
            "Entertoconfirm·Esctocancel\u001b(B\u000f"
        assertEquals(SanitizeResult.Blocked, ResponseSanitizer.sanitize(leaked))
    }

    @Test
    fun `spaced trust dialog with box drawing is blocked`() {
        val tui = """
            ╭──────────────────────────────╮
            │ Do you trust the files in this folder? │
            │ ❯ 1. Yes, proceed              │
            │   2. No, exit                  │
            ╰──────────────────────────────╯
            Enter to confirm · Esc to cancel
        """.trimIndent()
        assertEquals(SanitizeResult.Blocked, ResponseSanitizer.sanitize(tui))
    }

    @Test
    fun `menu plus press enter is blocked`() {
        val tui = "Select an option\n1. Yes\n2. No\nPress Enter to continue"
        assertEquals(SanitizeResult.Blocked, ResponseSanitizer.sanitize(tui))
    }

    @Test
    fun `single signal is not enough`() {
        val text = "Para guardar, presiona Enter.\n\nEl árbol:\n├── src\n└── test"
        assertEquals(text, clean(text))
        assertEquals(setOf("box"), ResponseSanitizer.junkSignals("├── src"))
    }

    @Test
    fun `normal markdown answer is clean and unchanged`() {
        val md = "**TL;DR:** Todo bien.\n\n- uno\n- dos\n\n1. Instala\n2. Nota final"
        assertEquals(md, clean(md))
    }

    @Test
    fun `strips CSI SGR and OSC title sequences`() {
        val raw = "\u001b]0;claude\u0007\u001b[1mHola\u001b[0m mundo\u001b[K"
        assertEquals("Hola mundo", clean(raw))
    }

    @Test
    fun `cursor forward becomes a space`() {
        assertEquals("Accessing workspace", clean("Accessing\u001b[1Cworkspace"))
    }

    @Test
    fun `charset escapes and C0 controls are removed, newlines kept`() {
        assertEquals("a\nb\tc", clean("a\u001b(B\u000f\r\nb\tc\u0007\u0000"))
    }

    @Test
    fun `OSC terminated by ST is removed`() {
        assertEquals("ok", clean("\u001b]8;;http://x\u001b\\ok"))
    }

    @Test
    fun `null and blank are clean empty`() {
        assertEquals(SanitizeResult.Clean(""), ResponseSanitizer.sanitize(null))
        assertEquals(SanitizeResult.Clean(""), ResponseSanitizer.sanitize("   "))
    }

    @Test
    fun `collapses blank line runs and trailing spaces`() {
        assertEquals("a\n\nb", clean("a   \n\n\n\n b".replace("\n b", "\nb")))
    }

    @Test
    fun `select only counts at line start`() {
        assertEquals(emptySet<String>(), ResponseSanitizer.junkSignals("Puedes seleccionar o Select all"))
        assertTrue("select" in ResponseSanitizer.junkSignals("Select a model"))
    }
}
