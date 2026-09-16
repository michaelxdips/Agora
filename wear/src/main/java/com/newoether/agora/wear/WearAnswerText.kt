package com.newoether.agora.wear

/**
 * Turns whatever a model wrote into text that is actually readable on a watch.
 *
 * Why this exists: `WearChatClient` returned the provider's `content` verbatim, so a model that
 * answers in Markdown or LaTeX put `**`, `$`, `` ``` `` and `\frac` on a 384 px screen — and the
 * watch has no Markdown renderer at all. There was no single place that decided "text from the
 * provider → text fit to show a human"; this is that place.
 *
 * Three constraints shape every rule below:
 *
 *  1. **No dependency.** The watch module is deliberately minimal, so the Markdown/LaTeX subset is
 *     an explicit mapping table, not a library.
 *  2. **Only glyphs that exist on the device.** The Wear OS image ships DroidSans, DroidSansMono,
 *     NotoSansSymbols and NotoColorEmoji and **no math font**. Every character this file can emit is
 *     checked against those cmaps by `WearAnswerTextCoverageTest`, which is what makes "readable"
 *     a measurement instead of a hope.
 *  3. **An honest ceiling.** Past [MAX_CHARS] the text is cut with a marker the user can see, never
 *     silently.
 *
 * Pure Kotlin, no Android: this is the piece the JVM tests exercise hardest, because a wrong render
 * is invisible until a user reads a wrong answer.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
object WearAnswerText {

    /**
     * Ceiling on the rendered answer.
     *
     * A provider can return megabytes (`/__huge` in the mock returns 200 KB): laying that out on a
     * watch is a stall, and speaking it is unusable. The ceiling is generous for prose and short
     * maths and small enough to stay safe; the truncation is announced, not silent.
     */
    const val MAX_CHARS = 2000

    /** Appended when [MAX_CHARS] cuts the answer. Visible on purpose. */
    const val TRUNCATION_MARKER = "… [truncated]"

    /** Characters that exist in no font on the Wear OS image, mapped to something readable. */
    private val NO_GLYPH_FALLBACKS = mapOf(
        // Superscript/subscript codepoints the image's fonts do not carry. A model writing ₙ or ²
        // is fine; these are the holes around them (⁲⁳₏₝₞₟), and a tofu box is what the user sees
        // if they pass through.
        '\u2072' to "[+]",           // ⁲ superscript plus (unassigned-ish; not in any font here)
        '\u2073' to "[-]",           // ⁳ superscript minus
        '\u208F' to "_",             // ₏ subscript plus
        '\u209D' to "=",             // ₝ subscript equals
        '\u209E' to "(",             // ₞ subscript left paren
        '\u209F' to ")",             // ₟ subscript right paren
        '\u23FB' to "[power]",       // ⏻ POWER SYMBOL
        '\u23FC' to "[power-off]",   // ⏼ POWER SYMBOL OFF
        '\u23BE' to "|",            // ⎾ measured-angle / bracket pieces: no glyph on this image
        '\u23BF' to "|",
        '\u23C0' to "o",
        '\u23C1' to "o",
        '\u23C2' to "o",
        '\u23C3' to "o",
        '\u23C4' to "o",
        '\u23C5' to "o",
        '\u23C6' to "o",
        '\u23C7' to "o",
        '\u23C8' to "o",
        '\u23C9' to "o",
        '\u23CA' to "o",
        '\u23CB' to "o",
        '\u23CC' to "o",
        '\u23F4' to "<",
        '\u23F5' to ">",
        '\u23F6' to "^",
        '\u23F7' to "v",
    )

    /** Zero-width and directionality characters: invisible, and they break layout for no gain. */
    private val INVISIBLE = setOf(
        '\u200B', '\u200C', '\u200D', '\u200E', '\u200F', '\u2060', '\uFEFF',
        '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
    )

    /** Superscript digits/operators that really exist in DroidSans. */
    private val SUPERSCRIPTS = buildMap {
        "0123456789".forEachIndexed { i, c -> put(c, '\u2070' + i) }   // ⁰¹²³⁴⁵⁶⁷⁸⁹ (¹²³ below)
        put('1', '\u00B9'); put('2', '\u00B2'); put('3', '\u00B3')
        put('-', '\u207B'); put('+', '\u207A'); put('=', '\u207C')
        put('(', '\u207D'); put(')', '\u207E'); put('n', '\u207F'); put('i', '\u2071')
    }

    /** Subscript digits/letters that really exist in DroidSans (the rest are unassigned). */
    private val SUBSCRIPTS = buildMap {
        "0123456789".forEachIndexed { i, c -> put(c, '\u2080' + i) }   // ₀₁₂₃₄₅₆₇₈₉
        put('+', '\u208A'); put('-', '\u208B'); put('=', '\u208C')
        put('(', '\u208D'); put(')', '\u208E')
        put('a', '\u2090'); put('e', '\u2091'); put('h', '\u2095'); put('i', '\u1D62')
        put('j', '\u2C7C'); put('k', '\u2096'); put('l', '\u2097'); put('m', '\u2098')
        put('n', '\u2099'); put('o', '\u2092'); put('p', '\u209A'); put('r', '\u1D63')
        put('s', '\u209B'); put('t', '\u209C'); put('u', '\u1D64'); put('v', '\u1D65')
        put('x', '\u2093')
    }

    /** LaTeX commands → the single character the watch actually has a glyph for. */
    private val LATEX_SYMBOLS = mapOf(
        "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓",
        "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥",
        "ne" to "≠", "neq" to "≠", "approx" to "≈", "equiv" to "≡",
        "infty" to "∞", "sum" to "∑", "prod" to "∏", "int" to "∫",
        "partial" to "∂", "nabla" to "∇", "in" to "∈", "notin" to "∉",
        "subset" to "⊂", "supset" to "⊃", "cup" to "∪", "cap" to "∩",
        "cdot" to "·", "ldots" to "…", "dots" to "…", "circ" to "∘",
        "to" to "→", "rightarrow" to "→", "leftarrow" to "←",
        "uparrow" to "↑", "downarrow" to "↓", "Rightarrow" to "⇒",
        "degree" to "°", "deg" to "°", "angle" to "∠", "perp" to "⊥",
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
        "Delta" to "Δ", "epsilon" to "ε", "zeta" to "ζ", "eta" to "η",
        "theta" to "θ", "Theta" to "Θ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "Lambda" to "Λ", "mu" to "µ", "nu" to "ν",
        "xi" to "ξ", "pi" to "π", "Pi" to "Π", "rho" to "ρ", "sigma" to "σ",
        "Sigma" to "Σ", "tau" to "τ", "upsilon" to "υ", "phi" to "φ",
        "Phi" to "Φ", "chi" to "χ", "psi" to "ψ", "Psi" to "Ψ",
        "omega" to "ω", "Omega" to "Ω",
    )

    /** Commands that carry no visible meaning on a watch screen. */
    private val LATEX_NOISE = setOf(
        "left", "right", "displaystyle", "textstyle", "limits", "nolimits",
        "quad", "qquad", "mathrm", "mathit", "mathbf", "operatorname", "text", "mbox",
    )

    /**
     * Renders one provider answer for the watch screen.
     *
     * @return the text to show: Markdown stripped, LaTeX reduced to Unicode, whitespace normalised,
     *   capped at [MAX_CHARS].
     */
    fun render(raw: String): String {
        val normalised = normalise(raw)
        val withoutFences = stripFences(normalised)
        val latex = latexToUnicode(withoutFences)
        val markdown = stripMarkdown(latex)
        val tidy = tidyWhitespace(markdown)
        return cap(tidy)
    }

    /** Line endings, tabs, invisibles, zero-width space, BOM. */
    private fun normalise(raw: String): String {
        val out = StringBuilder(raw.length)
        for (ch in raw.replace("\r\n", "\n").replace('\r', '\n')) {
            when {
                ch == '\t' -> out.append(' ')
                ch in INVISIBLE -> Unit
                NO_GLYPH_FALLBACKS.containsKey(ch) -> out.append(NO_GLYPH_FALLBACKS.getValue(ch))
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    /**
     * Drops ``` fences but **keeps the code**.
     *
     * A fenced block is still an answer; the watch has no syntax highlighter, so the fence lines go
     * and the content stays. A model that wraps its arithmetic in a fence is common enough that
     * discarding the body would lose the answer itself.
     */
    private fun stripFences(text: String): String =
        text.lineSequence()
            .filterNot { it.trimStart().startsWith("```") || it.trimStart().startsWith("~~~") }
            .joinToString("\n")

    /**
     * LaTeX → Unicode.
     *
     * Order matters: `\frac` is expanded **before** the `^`/`_` pass so `\frac{1}{2}` becomes `1/2`
     * and not `1` + a stray superscript; and `\sum_{i=1}^{n}` must reach the `_`/`^` pass with its
     * arguments intact so it becomes `∑ᵢ₌₁ⁿ`.
     */
    private fun latexToUnicode(text: String): String {
        var out = text
        // Delimiters first: they only ever wrap maths, and leaving them makes the pass below skip it.
        out = out.replace("$$", "").replace("\\(", "").replace("\\)", "")
            .replace("\\[", "").replace("\\]", "")
        // \frac{a}{b} → a/b  (innermost first, so nested fractions still resolve)
        out = replaceFractions(out)
        // \sqrt[n]{x} → ⁿ√x ; \sqrt{x} → √x
        out = out.replace(Regex("""\\sqrt\s*\[([^\]]*)\]\s*\{([^{}]*)\}""")) { m ->
            superscript(m.groupValues[1]) + "√" + m.groupValues[2]
        }
        out = out.replace(Regex("""\\sqrt\s*\{([^{}]*)\}""")) { m -> "√" + m.groupValues[1] }
        // Bare commands: longest name first so \leq never matches \le.
        out = replaceCommands(out)
        // ^{...} / ^n and _{...} / _n
        out = out.replace(Regex("""\^\s*\{([^{}]*)\}""")) { m -> superscript(m.groupValues[1]) }
        out = out.replace(Regex("""\^\s*(\S)""")) { m -> superscript(m.groupValues[1]) }
        out = out.replace(Regex("""_\s*\{([^{}]*)\}""")) { m -> subscript(m.groupValues[1]) }
        out = replaceBareSubscripts(out)
        // Remaining braces and stray backslashes are punctuation the user must not read.
        out = out.replace("{", "").replace("}", "")
        out = out.replace(Regex("""\\([A-Za-z]+)""")) { m -> m.groupValues[1] }
        out = out.replace("\\", "")
        // Single $ delimiters (a pair that never contained a command) go last.
        return out.replace("$", "")
    }

    /** Innermost `\frac{a}{b}` pairs, repeatedly, so `\frac{\frac{1}{2}}{3}` resolves. */
    private fun replaceFractions(text: String): String {
        val pattern = Regex("""\\frac\s*\{([^{}]*)\}\s*\{([^{}]*)\}""")
        var out = text
        var guard = 0
        while (pattern.containsMatchIn(out) && guard++ < 32) {
            out = out.replace(pattern) { m -> fraction(m.groupValues[1], m.groupValues[2]) }
        }
        return out
    }

    /**
     * `a/b`, with brackets only where the reader needs them.
     *
     * `\frac{1}{2}` is `1/2` — brackets around single tokens are noise on a 24-character-wide
     * screen. `\frac{x+1}{2}` is `(x+1)/2`, because `x+1/2` means something else.
     */
    private fun fraction(numerator: String, denominator: String): String {
        val n = numerator.trim()
        val d = denominator.trim()
        val needsBrackets = { part: String -> part.any { it == ' ' || it in "+-×÷*/^" } }
        val left = if (needsBrackets(n)) "($n)" else n
        val right = if (needsBrackets(d)) "($d)" else d
        return "$left/$right"
    }

    /**
     * Bare `_x` (no braces) → subscript.
     *
     * Deliberately narrow: it fires only when the next character is **not an ASCII letter**, so
     * `x_1` and `H_2O` become `x₁`/`H₂O` while `max_lines` and `a_b_c` stay identifiers. A model
     * writing maths uses `_{n}` (handled above) or `_1`/`_2`; a developer's message is far more
     * likely to contain snake_case, and mangling an identifier is worse than showing one underscore.
     */
    private fun replaceBareSubscripts(text: String): String =
        Regex("""_([^A-Za-z\s_{}])""").replace(text) { m -> subscript(m.groupValues[1]) }

    /** Replaces `\name` with its glyph, drops known noise commands, and unescapes punctuation. */
    private fun replaceCommands(text: String): String {
        val pattern = Regex("""\\([A-Za-z]+)""")
        return pattern.replace(text) { m ->
            val name = m.groupValues[1]
            when {
                LATEX_SYMBOLS.containsKey(name) -> LATEX_SYMBOLS.getValue(name)
                LATEX_NOISE.contains(name) -> ""
                name.length == 1 -> name                       // \%, \&, \_ …
                else -> name                                    // unknown: show the word, not the slash
            }
        }
    }

    private fun superscript(text: String): String =
        text.map { SUPERSCRIPTS[it] ?: it }.joinToString("")

    private fun subscript(text: String): String =
        text.map { SUBSCRIPTS[it] ?: it }.joinToString("")

    /**
     * Markdown → plain text.
     *
     * Line by line, because the line-leading rules (`#`, `-`, `>`) are the ones that matter on a
     * watch and they must not fire mid-sentence.
     */
    private fun stripMarkdown(text: String): String =
        text.lineSequence()
            .filterNot { isTableSeparator(it) }
            .joinToString("\n") { line -> stripLine(line) }

    /** `|---|---|` — a Markdown table's rule row: no information once the pipes are gone. */
    private fun isTableSeparator(line: String): Boolean =
        line.contains('|') && line.contains('-') && line.all { it in "|-: \t" }

    private fun stripLine(line: String): String {
        var out = line

        // A table row becomes its cells, separated by two spaces.
        if (out.trimStart().startsWith("|")) {
            val cells = out.trim().trim('|').split('|').map { it.trim() }
            out = cells.joinToString("  ")
        }

        // Block quote marker.
        out = out.replace(Regex("""^\s*>\s?"""), "")

        // Heading markers at line start (#, ##, …).
        out = out.replace(Regex("""^\s{0,3}#{1,6}\s*"""), "")

        // Bullet lists → a visible bullet the fonts have.
        out = out.replace(Regex("""^(\s*)[-*+]\s+"""), "$1• ")

        // Ordered list "1. " is left alone: the number is information.
        return inline(out)
    }

    /** Inline spans, applied to one line. */
    private fun inline(line: String): String {
        var out = line
        out = out.replace(Regex("""\[([^\]]*)\]\(([^)]*)\)"""), "$1")   // [text](url) → text
        out = out.replace(Regex("""\*\*\*([^*]+)\*\*\*"""), "$1")
        out = out.replace(Regex("""\*\*([^*]+)\*\*"""), "$1")           // **bold**
        out = out.replace(Regex("""__([^_]+)__"""), "$1")
        out = out.replace(Regex("""~~([^~]+)~~"""), "$1")               // ~~strike~~
        out = out.replace(Regex("""`([^`]*)`"""), "$1")                 // `code`
        // Single * or _ emphasis, but never inside a word (snake_case is an identifier, not emphasis).
        out = out.replace(Regex("""(?<![\w*])\*([^*\s][^*]*)\*(?![\w*])"""), "$1")
        out = out.replace(Regex("""(?<![\w_])_([^_\s][^_]*)_(?![\w_])"""), "$1")
        return out
    }
    /** Runs of blank lines, trailing spaces, leading/trailing blank lines. */
    private fun tidyWhitespace(text: String): String =
        text.lineSequence()
            .map { it.trimEnd() }
            .joinToString("\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()

    /** Hard ceiling with a visible marker. */
    private fun cap(text: String): String =
        if (text.length <= MAX_CHARS) text
        else text.take(MAX_CHARS).trimEnd() + "\n" + TRUNCATION_MARKER

    /**
     * Every character the symbol mapping can emit.
     *
     * Exposed so the coverage test can check the **tables**, not just a sample answer: a mapping
     * value with no glyph on the device is a latent tofu box that only shows up when a model happens
     * to use that command.
     */
    fun renderedSymbolsForTest(): Set<Char> = buildSet {
        LATEX_SYMBOLS.values.forEach { addAll(it.toList()) }
    }

    /** Every character the super/subscript mapping can emit. */
    fun renderedScriptsForTest(): Set<Char> = buildSet {
        addAll(SUPERSCRIPTS.values)
        addAll(SUBSCRIPTS.values)
        NO_GLYPH_FALLBACKS.values.forEach { addAll(it.toList()) }
    }
}
