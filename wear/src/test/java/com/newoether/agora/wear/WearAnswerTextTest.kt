package com.newoether.agora.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a provider answer looks like after [WearAnswerText.render].
 *
 * These are the cases a real model actually produces (bold labels, `$…$` maths, fenced arithmetic,
 * bullets, links) plus the boundaries that bite on a watch: a 200 KB answer, CRLF, zero-width
 * characters, and snake_case that must not be mistaken for emphasis.
 */
class WearAnswerTextTest {

    @Test
    fun boldMarkersAreRemovedButTheTextStays() {
        assertEquals("Jawaban: 14", WearAnswerText.render("**Jawaban:** 14"))
    }

    @Test
    fun aLatexFractionBecomesReadableArithmetic() {
        assertEquals("1/2 dari 80 = 40", WearAnswerText.render("${'$'}\\frac{1}{2}${'$'} dari 80 = 40"))
        // A compound numerator needs its brackets: `x+1/2` is a different expression.
        assertEquals("(x+1)/2", WearAnswerText.render("${'$'}\\frac{x+1}{2}${'$'}"))
        assertEquals("(1/2)/3", WearAnswerText.render("${'$'}\\frac{\\frac{1}{2}}{3}${'$'}"))
    }

    @Test
    fun latexSuperscriptAndSqrtBecomeRealCharacters() {
        assertEquals("x² + y² = r²", WearAnswerText.render("${'$'}x^{2} + y^{2} = r^{2}${'$'}"))
        assertEquals("√9 = 3", WearAnswerText.render("${'$'}\\sqrt{9} = 3${'$'}"))
    }

    @Test
    fun aLatexSumBecomesSigmaWithRealSubAndSuperscripts() {
        assertEquals("∑ᵢ₌₁ⁿ i", WearAnswerText.render("""\sum_{i=1}^{n} i"""))
    }

    @Test
    fun headingsBulletsAndFencesAreGone() {
        val rendered = WearAnswerText.render("```\n# Hasil\n- poin satu\n- poin dua\n```")
        assertEquals("Hasil\n• poin satu\n• poin dua", rendered)
        assertFalse("a heading marker survived: $rendered", rendered.contains("#"))
        assertFalse("a fence survived: $rendered", rendered.contains("```"))
    }

    @Test
    fun aMarkdownLinkKeepsItsTextAndDropsTheUrl() {
        assertEquals(
            "lihat dokumentasi sekarang",
            WearAnswerText.render("lihat [dokumentasi](https://example.com/very/long/path) sekarang"),
        )
    }

    @Test
    fun codeSpansAndStrikethroughLoseTheirMarkers() {
        assertEquals("coret dan kode", WearAnswerText.render("~~coret~~ dan `kode`"))
    }

    @Test
    fun aTableRowBecomesItsCells() {
        val rendered = WearAnswerText.render("| a | b |\n|---|---|\n| 1 | 2 |")
        assertEquals("a  b\n1  2", rendered)
    }

    @Test
    fun crlfTabsAndZeroWidthCharactersAreNormalised() {
        val rendered = WearAnswerText.render("a\r\n\r\n\r\nb\tc\u200Bd\uFEFFe")
        assertEquals("a\n\nb cde", rendered)
    }

    @Test
    fun snakeCaseIsNotEmphasis() {
        assertEquals("max_lines and a_b_c", WearAnswerText.render("max_lines and a_b_c"))
    }

    @Test
    fun aVeryLongAnswerIsCutWithAVisibleMarker() {
        val rendered = WearAnswerText.render("z".repeat(50_000))
        assertTrue("not capped: ${rendered.length}", rendered.length <= WearAnswerText.MAX_CHARS + 64)
        assertTrue("no truncation marker", rendered.endsWith(WearAnswerText.TRUNCATION_MARKER))
    }

    @Test
    fun everyCharacterItCanEmitHasAGlyphOnTheWatchImage() {
        // The device-side half of this assertion is WearAnswerTextCoverageTest, which reads the real
        // cmaps; here we only guarantee the mapping never emits a character outside the fonts' reach.
        val rendered = WearAnswerText.render(
            "2×(3+4)=14 √144=12 π≈3.14159 x²+y²=r² 5!=120 ∑ᵢ₌₁ⁿ i ∫₀¹ x dx Rp 25.000 " +
                "½ ⅓ ⅚ 3⁴ H₂O 25°C ±0.5 ≤ ≥ ≠ ≈ ∞ α β γ Δ θ Ω µ → ← ↑ ↓ • – — … “kutip” 👍 🎉 ✅ ❌"
        )
        val forbidden = listOf('\u2072', '\u2073', '\u208F', '\u209D', '\u209E', '\u209F', '\u23FB', '\u23FC')
        for (ch in forbidden) {
            assertFalse(
                "renderer emitted U+%04X, which has no glyph on this image".format(ch.code),
                rendered.contains(ch),
            )
        }
    }

    @Test
    fun plainTextIsUnchanged() {
        val plain = "Pemalang, 3 + 4 = 7."
        assertEquals(plain, WearAnswerText.render(plain))
    }
}
