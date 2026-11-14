package dev.nanonative.railix.core;

import dev.nanonative.railix.core.util.Sha256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class Sha256Test {
    private static final String EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @TempDir
    Path directory;

    @Test
    void textAndBytesMatchKnownDigestsWithUtf8Encoding() {
        assertThat(Sha256.hexDigest("")).isEqualTo(EMPTY);
        assertThat(Sha256.hexDigest("abc")).isEqualTo(ABC);
        final String cafe = "850f7dc43910ff890f8879c0ed26fe697c93a067ad93a7d50f466a7028a9bf4e";
        assertThat(Sha256.hexDigest("café")).isEqualTo(cafe);
        assertThat(Sha256.hexDigest(new byte[]{'c', 'a', 'f', (byte) 0xc3, (byte) 0xa9})).isEqualTo(cafe);
    }

    @Test
    void incrementalDigestsHaveIndependentState() {
        final var first = Sha256.newDigest();
        first.update((byte) 'a');
        final var second = Sha256.newDigest();
        assertThat(Sha256.finishHex(second)).isEqualTo(EMPTY);
        first.update("bc".getBytes(StandardCharsets.US_ASCII));
        assertThat(Sha256.finishHex(first)).isEqualTo(ABC);
    }

    @Test
    void filesAreHashedCompletelyWithoutModificationAndReadFailuresPropagate() throws Exception {
        final byte[] bytes = new byte[65_537];
        Arrays.fill(bytes, (byte) 'a');
        final Path file = Files.write(directory.resolve("content.bin"), bytes);
        assertThat(Sha256.hexDigest(file)).isEqualTo("008ffc88d3c96a9f307524eb361e47c5222a887fc45fa0c1fb8d429c5c23b430");
        assertThat(Files.readAllBytes(file)).isEqualTo(bytes);
        final Path missing = directory.resolve("missing.bin");
        assertThatThrownBy(() -> Sha256.hexDigest(missing)).isInstanceOf(IOException.class);
        assertThat(missing).doesNotExist();
    }
}
