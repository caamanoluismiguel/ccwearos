package com.caamano.ccwearos.presentation.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstScreenTest {

    private fun first(md: String, headline: String? = null, tools: Int = 0): FirstScreen {
        val p = prepareResult(headline, md)
        return firstScreenful(p.text, p.blocks, tools)
    }

    @Test
    fun `tldr plus the first three top-level list items`() {
        val f = first(
            """
            **TL;DR:** Arreglé el parser.

            - Uno
            - Dos
              - anidado
            - Tres
            - Cuatro
            """.trimIndent(),
        )
        assertEquals("Arreglé el parser.", f.tldr)
        assertEquals(listOf("Uno", "Dos", "Tres"), f.bullets)
        assertTrue(f.hasDetail)
    }

    @Test
    fun `no list means no bullets`() {
        val f = first("**TL;DR:** Hecho.\n\nUn párrafo largo con el porqué.")
        assertEquals(emptyList<String>(), f.bullets)
        assertTrue(f.hasDetail)
    }

    @Test
    fun `without a tldr the first paragraph leads`() {
        val long = "Primera idea con bastante texto para que no sea una respuesta corta de una línea. ".repeat(3).trim()
        val f = first("$long\n\nSegundo párrafo.")
        assertEquals(long, f.tldr)
    }

    @Test
    fun `short answer is only a tldr, detail only for tools`() {
        val f = first("Listo: tests en verde.")
        assertEquals("Listo: tests en verde.", f.tldr)
        assertFalse(f.hasDetail)
        assertTrue(first("Listo: tests en verde.", tools = 2).hasDetail)
    }

    @Test
    fun `chips are capped at two and ellipsized to 24 chars, full text kept`() {
        val chips = chipLabels(listOf("Escribe el test", "Muestra el diff completo del parser", "Tercera", " "))
        assertEquals(2, chips.size)
        assertEquals("Escribe el test" to "Escribe el test", chips[0])
        assertTrue(chips[1].first.length <= CHIP_LABEL_MAX)
        assertTrue(chips[1].first.endsWith("…"))
        assertEquals("Muestra el diff completo del parser", chips[1].second)
    }

    // ─── defense in depth ────────────────────────────────────────────────────

    @Test
    fun `tui chrome lines are dropped even when the sanitizer says clean`() {
        val raw = """
            ✻ Churned for 12s
            Los tests pasan.
            ▔▔▔▔▔▔▔▔▔▔
            ────────────
            Don't show again
            Teach auto mode what to allow
            Siguiente paso: subir el cambio.
        """.trimIndent()
        assertTrue(ResponseSanitizer.sanitize(raw) is SanitizeResult.Clean)
        assertEquals("Los tests pasan.\nSiguiente paso: subir el cambio.", cleanAnswer(raw))
    }

    @Test
    fun `a response repeated twice keeps one copy`() {
        val once = "**TL;DR:** Hecho.\n\n- a\n- b"
        assertEquals(once, cleanAnswer("$once\n\n$once"))
        // Not a repeat: untouched.
        assertEquals("a\nb\na", cleanAnswer("a\nb\na"))
        assertEquals("a\nb", cleanAnswer("a\nb"))
    }

    @Test
    fun `ordinary markdown is not chrome`() {
        assertFalse(isTuiChrome("- un ítem"))
        assertFalse(isTuiChrome("No es un spinner · solo texto"))
        assertTrue(isTuiChrome("✽ Pondering…"))
    }

    @Test
    fun `done tldr for Inicio comes through the same pipeline, junk gives none`() {
        assertEquals("Hecho.", resultTldr(null, "**TL;DR:** Hecho.\n\nDetalle."))
        assertNull(resultTldr(null, "╭────╮ Do you trust the files in this folder? ❯ 1. Yes"))
    }
}
