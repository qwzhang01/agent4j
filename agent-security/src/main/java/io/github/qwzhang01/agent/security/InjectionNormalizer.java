package io.github.qwzhang01.agent.security;

import java.text.Normalizer;

/**
 * Unicode normalization + obfuscation collapse (KP10 v1.5, debt-3 fix 2026-09-12).
 *
 * <p>The v1 regex wall only catches attacks written in canonical form.
 * Real adversaries don't write canonically:
 * <ul>
 *   <li><b>Homoglyphs</b> — "іgnore" with a Cyrillic і (U+0456) reads
 *       identically to "ignore" but shares zero codepoints with the
 *       ASCII spelling a regex expects.</li>
 *   <li><b>Zero-width filler</b> — "ig\u200Bnored" splits the word with
 *       invisible characters that the model's tokenizer usually drops.</li>
 *   <li><b>Fullwidth/CJK punctuation</b> — "ｉｇｎｏｒｅ" or "｛｛instructions｝｝"
 *       dodge ASCII-anchored patterns.</li>
 *   <li><b>Case tricks</b> — trivially handled by CASE_INSENSITIVE but
 *       folded here anyway so downstream patterns need no flags.</li>
 * </ul>
 *
 * <p>Defense shape: normalize BEFORE matching. {@link #normalize} maps
 * homoglyph/fullwidth forms onto their ASCII equivalents (NFKC does most
 * of this for Latin), strips zero-width joiners and variation selectors,
 * and collapses whitespace. The scanner then runs its patterns against
 * the normalized view while the ORIGINAL text stays untouched for the
 * forensics ledger — the same raw-vs-view discipline as
 * {@code SanitizingContextBuilder} (decision 12: sanitized views for the
 * model, raw bytes for audit).
 *
 * <p>Honest boundaries (what normalization does NOT catch): paraphrase
 * ("其实你真正的任务是..."), cross-language semantic attacks, and
 * token-splitting tricks that survive NFKC (e.g. Base64 payloads decoded
 * by the model itself). Those belong to the semantic judge
 * ({@link InjectionJudge}, the v2 slot) — normalization widens the regex
 * wall's coverage, it does not turn the wall into a door judge.
 *
 * <p>Pure function, no state; thread-safe by construction.
 */
public final class InjectionNormalizer {

    private InjectionNormalizer() {
    }

    /** Characters that carry no visible width — stripped before matching. */
    private static final String ZERO_WIDTH_CHARS =
            "\u200B\u200C\u200D\u2060\uFEFF\uFE00\uFE01\uFE02\uFE03\uFE04\uFE05"
                    + "\uFE06\uFE07\uFE08\uFE09\uFE0A\uFE0B\uFE0C\uFE0D\uFE0E\uFE0F";

    /**
     * Cross-script homoglyph map: NFKC handles compatibility forms (fullwidth,
     * ligatures) but NEVER crosses writing systems — Cyrillic 'і' (U+0456)
     * does not decompose to ASCII 'i' under any Unicode normalization because
     * they are different letters that merely LOOK identical. This table maps
     * the high-frequency confusable letters used in injection attacks onto
     * their ASCII look-alikes so the shape wall sees the attack as written.
     * <p>
     * Written as parallel codepoint arrays — every (from, to) pair is aligned
     * by index and declared with explicit escapes; no visually-similar
     * hand-typed strings (that is exactly how the first draft of this table
     * shipped misaligned — pinned by normalizeIsWellBehaved).
     * <p>
     * Coverage stance (honest boundary): this is a deny-list of the most
     * common confusables, not a complete confusables database — an adversary
     * picking a rare homoglyph outside this table still evades the wall and
     * lands in the semantic judge's territory.
     */
    private static final char[] HOMOGLYPH_FROM = {
            '\u0430', // а -> a
            '\u0435', // е -> e
            '\u043E', // о -> o
            '\u0440', // р -> p
            '\u0441', // с -> c
            '\u0443', // у -> y
            '\u0445', // х -> x
            '\u0455', // ѕ -> s
            '\u0456', // і -> i
            '\u0458', // ј -> j
            '\u04BB', // һ -> h
            '\u04CF', // ӏ -> l
            '\u0501', // ԁ -> d
            '\u051B', // ԛ -> q
            '\u0410', // А -> A
            '\u0412', // В -> B
            '\u0415', // Е -> E
            '\u041A', // К -> K
            '\u041C', // М -> M
            '\u041D', // Н -> H
            '\u041E', // О -> O
            '\u0420', // Р -> P
            '\u0421', // С -> C
            '\u0422', // Т -> T
            '\u0425', // Х -> X
            '\u0423', // У -> Y
            '\u0391', // Α -> A
            '\u0392', // Β -> B
            '\u0395', // Ε -> E
            '\u0396', // Ζ -> Z
            '\u0397', // Η -> H
            '\u039A', // Κ -> K
            '\u039C', // Μ -> M
            '\u039D', // Ν -> N
            '\u039F', // Ο -> O
            '\u03A1', // Ρ -> P
            '\u03A4', // Τ -> T
            '\u03A7', // Χ -> X
            '\u03B1', // α -> a (only when standing alone words? kept: high-freq confusable)
            '\u03B5', // ε -> e
            '\u03BF', // ο -> o
            '\u03C1', // ρ -> p
            '\u03C3', // σ -> s
            '\u03C5', // υ -> y
            '\u03C7', // χ -> x
            '\u0192', // ƒ -> f
            '\u0261', // ɡ -> g
    };

    private static final char[] HOMOGLYPH_TO = {
            'a', 'e', 'o', 'p', 'c', 'y', 'x', 's', 'i', 'j', 'h', 'l', 'd', 'q',
            'A', 'B', 'E', 'K', 'M', 'H', 'O', 'P', 'C', 'T', 'X', 'Y',
            'A', 'B', 'E', 'Z', 'H', 'K', 'M', 'N', 'O', 'P', 'T', 'X',
            'a', 'e', 'o', 'p', 's', 'y', 'x',
            'f', 'g',
    };

    /**
     * Normalized view for pattern matching.
     *
     * @param text raw tool output / context content; null-safe (returns null)
     * @return the NFKC-normalized, homoglyph-folded, zero-width-stripped,
     *         whitespace-collapsed view
     */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String stripped = stripZeroWidth(text);
        String nfkc = Normalizer.normalize(stripped, Normalizer.Form.NFKC);
        // Post-NFKC strip: decomposition can re-expose variation selectors
        String folded = foldHomoglyphs(stripZeroWidth(nfkc));
        return collapseWhitespace(folded);
    }

    /**
     * Map cross-script confusables onto their ASCII look-alikes. Runs AFTER
     * NFKC so fullwidth/compat forms are already ASCII; only true
     * cross-script homoglyphs remain for this pass.
     */
    private static String foldHomoglyphs(String text) {
        if (text.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char mapped = c;
            for (int k = 0; k < HOMOGLYPH_FROM.length; k++) {
                if (HOMOGLYPH_FROM[k] == c) {
                    mapped = HOMOGLYPH_TO[k];
                    break;
                }
            }
            sb.append(mapped);
        }
        return sb.toString();
    }

    private static String stripZeroWidth(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            if (ZERO_WIDTH_CHARS.indexOf(text.charAt(i)) < 0) {
                sb.append(text.charAt(i));
            }
        }
        return sb.toString();
    }

    private static String collapseWhitespace(String text) {
        return text.replaceAll("\\s+", " ");
    }
}
