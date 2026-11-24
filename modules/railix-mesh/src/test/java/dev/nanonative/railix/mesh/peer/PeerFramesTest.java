package dev.nanonative.railix.mesh.peer;

import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerFramesTest {
    @Test
    void roundTripsConsecutiveMessagesUsingBigEndianUtf8ByteLengths() throws IOException {
        final byte[] first = json("é".repeat(125));
        final byte[] second = json("ready");
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final DataOutputStream frames = new DataOutputStream(output);
        final int limit = first.length;

        assertTrue(PeerFrames.write(frames, first, limit));
        assertTrue(PeerFrames.write(frames, second, limit));
        final byte[] wire = output.toByteArray();
        assertArrayEquals(new byte[]{0, 0, 1, 2}, Arrays.copyOf(wire, Integer.BYTES));

        final int headerSplit = 2;
        final int bodySplit = Integer.BYTES + 3;
        try (SequenceInputStream input = new SequenceInputStream(Collections.enumeration(List.of(
                new ByteArrayInputStream(wire, 0, headerSplit),
                new ByteArrayInputStream(wire, headerSplit, bodySplit - headerSplit),
                new ByteArrayInputStream(wire, bodySplit, wire.length - bodySplit)
        )))) {
            final FrameRead firstRead = PeerFrames.read(input, limit, size -> Optional.of(new byte[size]));
            assertArrayEquals(first, assertInstanceOf(FrameBody.class, firstRead).bytes());
            final FrameRead secondRead = PeerFrames.read(input, limit, size -> Optional.of(new byte[size]));
            assertArrayEquals(second, assertInstanceOf(FrameBody.class, secondRead).bytes());
        }
    }

    @Test
    void refusesToWriteAnEmptyOrOversizedBody() throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final DataOutputStream frames = new DataOutputStream(output);
        final int limit = PeerFrames.MAX_CONTROL_BODY_BYTES;
        assertFalse(PeerFrames.write(frames, new byte[0], limit));
        assertFalse(PeerFrames.write(frames, new byte[limit + 1], limit));
        assertEquals(0, output.size());
    }

    private static byte[] json(final String value) {
        return RailixJson.write(RailixValue.object(Map.of("v", RailixValue.string(value))))
                .getBytes(StandardCharsets.UTF_8);
    }
}
