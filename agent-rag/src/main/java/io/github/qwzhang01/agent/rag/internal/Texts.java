package io.github.qwzhang01.agent.rag.internal;

/** Not part of the public API. */
public final class Texts {

    private Texts() {
    }

    /** First {@code maxChars} chars of {@code text}, one fewer when the cut would split a surrogate pair. */
    public static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        if (maxChars <= 0) {
            return "";
        }
        int end = Character.isHighSurrogate(text.charAt(maxChars - 1)) ? maxChars - 1 : maxChars;
        return text.substring(0, end);
    }
}
