package dev.nanonative.railix.core.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Build-time SHA-256 mechanics shared by authoring tools; absent from generated application runtimes. */
public final class Sha256 {
    private Sha256() { }

    /**
     * Creates an independent digest for caller-defined incremental inputs.
     * @return a new caller-owned SHA-256 digest
     * @throws IllegalStateException if the Java runtime lacks the required algorithm
     */
    public static MessageDigest newDigest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by the Java platform.", unavailable);
        }
    }

    /**
     * Hashes UTF-8 text without normalization or an identity prefix.
     * @param text exact text to encode
     * @return the lowercase hexadecimal digest
     * @throws IllegalArgumentException if text is Java null
     */
    public static String hexDigest(final String text) {
        if (text == null) throw new IllegalArgumentException("Hash text cannot be Java null.");
        return hexDigest(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Hashes bytes without modifying them.
     * @param bytes exact bytes to hash
     * @return the lowercase hexadecimal digest
     * @throws IllegalArgumentException if bytes are Java null
     */
    public static String hexDigest(final byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("Hash bytes cannot be Java null.");
        final var digest = newDigest();
        digest.update(bytes);
        return finishHex(digest);
    }

    /**
     * Hashes a file through a stream owned and closed by this call.
     * @param file file whose bytes determine the digest
     * @return the lowercase hexadecimal digest
     * @throws IllegalArgumentException if the file is Java null
     * @throws IOException if the file cannot be opened or read
     */
    public static String hexDigest(final Path file) throws IOException {
        if (file == null) throw new IllegalArgumentException("Hash file cannot be Java null.");
        final var digest = newDigest();
        try (var input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return finishHex(digest);
    }

    /**
     * Completes an incremental digest and resets it, as {@link MessageDigest#digest()} does.
     * @param digest caller-owned digest created by {@link #newDigest()}
     * @return the lowercase hexadecimal digest
     * @throws IllegalArgumentException if the digest is Java null
     */
    public static String finishHex(final MessageDigest digest) {
        if (digest == null) throw new IllegalArgumentException("Digest cannot be Java null.");
        return HexFormat.of().formatHex(digest.digest());
    }
}
