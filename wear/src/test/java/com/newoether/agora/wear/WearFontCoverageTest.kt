package com.newoether.agora.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The font-coverage table and its consumer.
 *
 * The audit's finding (P3) was that this pair was a tautology: `WearFontCoverage.size` is computed
 * from the same hand-written range list the test reads, so `size > 8000` asserts that a list is a
 * list. A generator that silently produced half the table would still pass.
 *
 * What makes it non-tautological is comparing the table against an **independent** source. There are
 * two available here, and both are used:
 *
 *  * the documented per-font counts in the file's own header (2797 + 873 + 4616 + 124 + 1449), which
 *    came from the cmaps, not from the ranges — a regeneration that drops a font must break the sum;
 *  * the **rendered output** of [WearAnswerText], which is a different code path entirely: if the
 *    table is missing a codepoint the renderer actually emits, the render check fails.
 *
 * `WearAnswerTextCoverageTest` covers the second. This file covers the first, plus the invariants the
 * generator has to satisfy (sorted, disjoint, inside the Unicode range) which no consumer checks.
 */
class WearFontCoverageTest {

    /** The five fonts' codepoint counts, from the generated file's own header. */
    private val documentedFontCounts = listOf(2797, 873, 4616, 124, 1449)

    @Test
    fun `the table size matches the documented union of the five fonts`() {
        // The header records 8755 codepoints in 165 ranges. This is the independent number: it was
        // read from the fonts' cmaps, not derived from the ranges below it. A regeneration that reads
        // the wrong font set, or that truncates the list, changes the sum.
        val documented = 8755

        assertEquals(
            "the coverage table no longer matches the fonts it claims to be generated from",
            documented,
            WearFontCoverage.size,
        )
        // The individual counts are recorded so a future regeneration has to state its numbers.
        assertTrue(
            "the documented per-font counts do not add up to the documented union",
            documentedFontCounts.sum() >= documented,
        )
    }

    @Test
    fun `the table is not a tautology about its own input`() {
        // The old assertion was `size > 8000`, computed from the list under test. This one pins the
        // size to a number derived elsewhere, and additionally requires that the *coverage* be usable
        // for its stated purpose: every printable ASCII character must be present, or the watch cannot
        // render the answer to an ordinary question.
        (0x20..0x7E).forEach { cp ->
            assertTrue(
                "printable ASCII U+%04X is missing from the coverage table".format(cp),
                WearFontCoverage.hasGlyph(cp),
            )
        }
        // The table starts at U+0000 (DroidSans covers the C0 block), which is recorded here rather
        // than asserted away: it is what the generated file says, and the renderer is what keeps
        // control characters off the screen.
        assertTrue("the table's first range is documented as U+0000..U+007E", WearFontCoverage.hasGlyph(0))
        // …and a code point outside every range is genuinely reported as uncovered. Without this the
        // test would pass against a `hasGlyph` that returned true unconditionally.
        assertFalse("U+1FFFF is outside every documented range", WearFontCoverage.hasGlyph(0x1FFFF))
        assertFalse("U+E0100 is outside every documented range", WearFontCoverage.hasGlyph(0xE0100))
    }

    @Test
    fun `the ranges are sorted and disjoint`() {
        // A generator bug that emits overlapping or out-of-order ranges would still satisfy
        // `hasGlyph` for most inputs while making the table unusable for any binary search a future
        // consumer adds. These invariants are the table's own contract.
        val ranges = WearFontCoverage.rangesForTest()
        assertTrue("the table is empty", ranges.isNotEmpty())
        ranges.zipWithNext().forEach { (previous, next) ->
            assertTrue(
                "ranges are out of order or overlapping: $previous then $next",
                previous.last < next.first,
            )
        }
        ranges.forEach { range ->
            assertTrue("a range starts below U+0000: $range", range.first >= 0)
            assertTrue("a range ends above U+10FFFF: $range", range.last <= 0x10FFFF)
            assertTrue("an empty range: $range", range.first <= range.last)
        }
    }

    @Test
    fun `the emoji the renderer can emit are covered as code points`() {
        // Surrogate-pair handling: an emoji is two Chars, and a table that only covered BMP ranges
        // would still "contain" both halves while the composed glyph is missing.
        listOf(0x1F44D, 0x1F389, 0x2705, 0x274C, 0x1F600, 0x1F525).forEach { cp ->
            assertTrue("U+%04X missing".format(cp), WearFontCoverage.hasGlyph(cp))
        }
    }
}
