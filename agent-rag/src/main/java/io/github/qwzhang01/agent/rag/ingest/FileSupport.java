package io.github.qwzhang01.agent.rag.ingest;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

final class FileSupport {

    private FileSupport() {
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static boolean hasExtension(Path file, String... extensions) {
        Path name = file.getFileName();
        if (name == null) {
            return false;
        }
        String lower = name.toString().toLowerCase(Locale.ROOT);
        for (String ext : extensions) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    static String baseName(Path file) {
        Path name = file.getFileName();
        if (name == null) {
            return "";
        }
        String s = name.toString();
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }
}
