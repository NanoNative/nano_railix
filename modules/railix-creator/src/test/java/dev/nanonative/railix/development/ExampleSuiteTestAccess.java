package dev.nanonative.railix.development;

import dev.nanonative.railix.core.value.RailixJson;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Test access for corruption cases that cannot be produced through a validated compiled manifest. */
public final class ExampleSuiteTestAccess {
    public static final int CHUNK_SIZE = ExampleSuite.CHUNK_SIZE;
    public static final long MAX_EXAMPLE_BYTES = ExampleSuite.MAX_EXAMPLE_BYTES;
    public static final long MAX_SUITE_BYTES = ExampleSuite.MAX_SUITE_BYTES;

    private ExampleSuiteTestAccess() {
    }

    public static void project(
            final InputStream source,
            final OutputStream target,
            final int selectedNode,
            final boolean summary
    ) throws IOException {
        final var projection = ExampleSuite.project(source, selectedNode, summary);
        if (projection.isPresent()) {
            target.write(RailixJson.write(projection.orElseThrow()).getBytes(StandardCharsets.UTF_8));
        }
    }
}
