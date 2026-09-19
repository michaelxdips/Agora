package com.newoether.agora.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The renderer must never emit a character the watch cannot draw.
 *
 * [WearAnswerText] maps Markdown and LaTeX onto Unicode, and Unicode has a lot of codepoints that
 * the Wear OS image has **no font for** — `⁲⁳₏₝₞₟`, `⏻⏼`, `⎾⎿…`, all of U+23BE–U+23CC and U+23F4–U+23FF.
 * A model writing maths can emit any of them, and the user sees `□` — the exact failure this
 * mission exists to remove.
 *
 * [WearFontCoverage] is generated from the real cmaps pulled off the API 34 wear emulator, so this
 * test measures the device's fonts rather than assuming them.
 */
class WearAnswerTextCoverageTest {

    /** The Phase-A sample: what a real quick question and its answer actually contain. */
    private val sample = """
        2×(3+4)=14   √144=12   π≈3.14159   x² + y² = r²   5! = 120   ∑ᵢ₌₁ⁿ i   ∫₀¹ x dx
        Rp 25.000 · 15% dari 80 = 12 · ½ + ⅓ = ⅚ · 3⁴ = 81 · H₂O · 25°C · ±0.5 · ≤ ≥ ≠ ≈ ∞
        α β γ Δ θ Ω µ → ← ↑ ↓ • – — … “kutip” ‘tunggal’   👍 🎉 ✅ ❌
        | tabel | **tebal** | `kode` | ~~coret~~ | [tautan](url) | # Heading | - poin |
        é ñ ü ç 1,234.56 1.234,56
    """.trimIndent()

    /** Every LaTeX form the renderer claims to handle, so the mapping itself is coverage-tested. */
    private val latexForms = listOf(
        "\$\\frac{1}{2}\$", "\$\\frac{x+1}{2}\$", "\$x^{2}\$", "\$x^{n}\$", "\$x_{i}\$",
        "\$\\sqrt{9}\$", "\$\\sqrt[3]{8}\$", "\$\\sum_{i=1}^{n} i\$", "\$\\int_{0}^{1} x dx\$",
        "\$\\pi \\approx 3.14159\$", "\$\\times \\div \\pm \\mp\$", "\$\\le \\ge \\ne \\equiv\$",
        "\$\\infty \\partial \\nabla \\in \\notin \\subset \\supset \\cup \\cap\$",
        "\$\\Delta \\theta \\mu \\Omega \\alpha \\beta \\gamma \$",
        "\$\\to \\rightarrow \\leftarrow \\uparrow \\downarrow \\Rightarrow\$",
        "\$\\cdot \\ldots \\circ \\angle \\perp\$", "\$\\prod_{i=1}^{n}\$",
        "\$5! = 120\$", "\$25°C\$", "\$\\pm 0.5\$",
    )

    private fun everyRenderedChar(): List<Pair<Int, String>> {
        val sources = buildList {
            add(sample)
            addAll(latexForms)
            add("```\n# Hasil\n- a\n- b\n```")
            add("| a | b |\n|---|---|\n| 1 | 2 |")
            add("~~coret~~ dan `kode` [x](https://example.com)")
        }
        return sources.flatMap { src ->
            // Code points, not Chars: an emoji is a surrogate pair, and two lone surrogates are not
            // "characters with no glyph" — the pair is one grapheme the emoji font has.
            WearAnswerText.render(src).codePoints().toArray().map { cp -> cp to src }
        }
    }

    @Test
    fun everyCharacterTheRendererEmitsHasAGlyphOnTheWatchImage() {
        val uncovered = everyRenderedChar()
            .filter { (cp, _) -> !WearFontCoverage.hasGlyph(cp) }
            .map { (cp, src) -> "U+%04X %s (from: %s)".format(cp, String(Character.toChars(cp)), src.take(40)) }
            .distinct()
        assertTrue("characters with no glyph reached the screen:\n${uncovered.joinToString("\n")}", uncovered.isEmpty())
    }

    @Test
    fun theCharactersWithNoGlyphAreReplacedRatherThanShown() {
        val noGlyph = listOf(
            '\u2072', '\u2073', '\u208F', '\u209D', '\u209E', '\u209F',
            '\u23BE', '\u23BF', '\u23C0', '\u23CC', '\u23F4', '\u23F7', '\u23FB', '\u23FC',
        )
        for (ch in noGlyph) {
            assertFalse(
                "U+%04X has no glyph on this image and the renderer passed it through".format(ch.code),
                WearAnswerText.render("a${ch}b").contains(ch),
            )
        }
    }

    @Test
    fun theFontCoverageTableIsNotEmptyAndContainsWhatItMust() {
        // Guards the generated file against a bad regeneration: if these stop being covered, the
        // generator read the wrong fonts.
        //
        // The old form of this assertion was `WearFontCoverage.size > 8000`, where `size` is computed
        // from the very range list under test — a tautology that a half-generated table would still
        // pass. The size is now pinned to the union the file's own header documents (8755, read from
        // the fonts' cmaps), and `WearFontCoverageTest` additionally checks the table's structure.
        assertEquals("the coverage table no longer matches the fonts it documents", 8755, WearFontCoverage.size)
        for (ch in "0123456789abcXYZ ×÷√π°±≈≤≥∞∑∫→²³₀₁•—…") {
            assertTrue("U+%04X missing from the coverage table".format(ch.code), WearFontCoverage.hasGlyph(ch.code))
        }
        // Emoji live outside the BMP: check them as code points, not as chars.
        for (cp in listOf(0x1F44D, 0x1F389, 0x2705, 0x274C)) {
            assertTrue("U+%04X missing from the coverage table".format(cp), WearFontCoverage.hasGlyph(cp))
        }
    }

    @Test
    fun theMappingTablesOnlyEmitCoveredCharacters() {
        // The renderer's own mapping values — not just the sample — must be inside the coverage set.
        val emitted = buildSet {
            addAll(WearAnswerText.renderedSymbolsForTest())
            addAll(WearAnswerText.renderedScriptsForTest())
        }
        val uncovered = emitted.filter { !WearFontCoverage.hasGlyph(it.code) }
        assertTrue(
            "mapping tables emit uncovered characters: ${uncovered.map { "U+%04X".format(it.code) }}",
            uncovered.isEmpty(),
        )
    }
}
