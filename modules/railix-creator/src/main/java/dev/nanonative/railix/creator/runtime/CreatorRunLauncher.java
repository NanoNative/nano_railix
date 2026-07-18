package dev.nanonative.railix.creator.runtime;

import dev.nanonative.railix.kernel.model.KernelContractCodec;
import dev.nanonative.railix.kernel.runtime.BuiltRailixAppMain;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Launches one real built-app run from persisted launcher inputs for the Creator shell.
 */
public final class CreatorRunLauncher {

    static final int MAX_CAPTURE_BYTES = 64 * 1024;
    private static final byte[] TRUNCATED_MARKER = "\n...[truncated]".getBytes(StandardCharsets.UTF_8);

    private CreatorRunLauncher() {}

    /**
     * Executes a built-app run request using the public launcher boundary.
     *
     * @param requestBody stable-JSON request body
     * @param runsRoot run artifact root
     * @return primitive response model suitable for stable JSON
     */
    public static Map<String, Object> launch(final String requestBody, final Path runsRoot) {
        Objects.requireNonNull(requestBody, "requestBody");
        Objects.requireNonNull(runsRoot, "runsRoot");
        final RunLaunchRequest request = decodeRequest(requestBody);
        return launch(request, runsRoot);
    }

    static Map<String, Object> launch(final RunLaunchRequest request, final Path runsRoot) {
        return launch(request, runsRoot, BuiltRailixAppMain::launch);
    }

    static Map<String, Object> launch(
            final RunLaunchRequest request,
            final Path runsRoot,
            final LaunchExecutor launchExecutor
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(runsRoot, "runsRoot");
        Objects.requireNonNull(launchExecutor, "launchExecutor");
        final List<String> args = new ArrayList<>();
        args.add("--plan");
        args.add(request.planLocation());
        args.add("--envelope");
        args.add(request.envelopeLocation());
        args.add("--runs-root");
        args.add(runsRoot.toAbsolutePath().normalize().toString());
        if (!request.profileName().isEmpty()) {
            args.add("--profile");
            args.add(request.profileName());
        }
        if (!request.settingsLocation().isEmpty()) {
            args.add("--settings");
            args.add(request.settingsLocation());
        }
        if (!request.runId().isEmpty()) {
            args.add("--run-id");
            args.add(request.runId());
        }

        final BoundedCaptureOutputStream stdout = new BoundedCaptureOutputStream(MAX_CAPTURE_BYTES);
        final BoundedCaptureOutputStream stderr = new BoundedCaptureOutputStream(MAX_CAPTURE_BYTES);
        final BuiltRailixAppMain.LaunchResult result = launchExecutor.launch(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        final CapturedOutput capturedStdout = stdout.finish();
        final CapturedOutput capturedStderr = stderr.finish();
        return orderedMap(
                "exitCode", result.exitCode(),
                "succeeded", result.succeeded(),
                "message", result.message(),
                "runId", result.runId(),
                "outcome", result.outcome(),
                "runFolder", result.runFolder(),
                "stdout", capturedStdout.value(),
                "stderr", capturedStderr.value(),
                "stdoutSize", capturedStdout.totalBytes(),
                "stderrSize", capturedStderr.totalBytes(),
                "stdoutTruncated", capturedStdout.truncated(),
                "stderrTruncated", capturedStderr.truncated()
        );
    }

    static RunLaunchRequest decodeRequest(final String requestBody) {
        final Map<String, Object> raw = KernelContractCodec.parseStableJsonObject(requestBody);
        return new RunLaunchRequest(
                requiredString(raw, "planLocation"),
                requiredString(raw, "envelopeLocation"),
                optionalString(raw, "settingsLocation"),
                optionalString(raw, "profileName"),
                optionalString(raw, "runId")
        );
    }

    private static String requiredString(final Map<String, Object> values, final String key) {
        final String value = optionalString(values, key);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(key + " must be a non-blank string");
        }
        return value;
    }

    private static String optionalString(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        if (value == null) {
            return "";
        }
        if (value instanceof String stringValue) {
            return stringValue.trim();
        }
        throw new IllegalArgumentException(key + " must be a string");
    }

    private static Map<String, Object> orderedMap(final Object... keyValues) {
        final LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            values.put((String) keyValues[index], keyValues[index + 1]);
        }
        return values;
    }

    record RunLaunchRequest(
            String planLocation,
            String envelopeLocation,
            String settingsLocation,
            String profileName,
            String runId
    ) {
        RunLaunchRequest {
            planLocation = Objects.requireNonNull(planLocation, "planLocation");
            envelopeLocation = Objects.requireNonNull(envelopeLocation, "envelopeLocation");
            settingsLocation = Objects.requireNonNull(settingsLocation, "settingsLocation");
            profileName = Objects.requireNonNull(profileName, "profileName");
            runId = Objects.requireNonNull(runId, "runId");
        }

        RunLaunchRequest withRunId(final String normalizedRunId) {
            return new RunLaunchRequest(
                    planLocation,
                    envelopeLocation,
                    settingsLocation,
                    profileName,
                    Objects.requireNonNull(normalizedRunId, "normalizedRunId")
            );
        }
    }

    @FunctionalInterface
    interface LaunchExecutor {
        BuiltRailixAppMain.LaunchResult launch(List<String> args, PrintStream stdout, PrintStream stderr);
    }

    private static final class BoundedCaptureOutputStream extends OutputStream {
        private final java.io.ByteArrayOutputStream preview;
        private final int maxBytes;
        private int totalBytes;
        private boolean truncated;

        private BoundedCaptureOutputStream(final int maxBytes) {
            if (maxBytes < TRUNCATED_MARKER.length) {
                throw new IllegalArgumentException("maxBytes must be >= truncated marker length");
            }
            this.preview = new java.io.ByteArrayOutputStream(maxBytes);
            this.maxBytes = maxBytes;
            this.totalBytes = 0;
            this.truncated = false;
        }

        @Override
        public void write(final int value) {
            totalBytes += 1;
            if (preview.size() < maxBytes) {
                preview.write(value);
            } else {
                truncated = true;
            }
        }

        @Override
        public void write(final byte[] buffer, final int offset, final int length) {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return;
            }
            totalBytes += length;
            final int remaining = maxBytes - preview.size();
            if (remaining > 0) {
                preview.write(buffer, offset, Math.min(remaining, length));
            }
            if (length > remaining) {
                truncated = true;
            }
        }

        private CapturedOutput finish() {
            if (!truncated) {
                return new CapturedOutput(preview.toString(StandardCharsets.UTF_8), totalBytes, false);
            }
            final byte[] capturedBytes = preview.toByteArray();
            final int previewLength = Math.max(0, maxBytes - TRUNCATED_MARKER.length);
            return new CapturedOutput(
                    new String(capturedBytes, 0, previewLength, StandardCharsets.UTF_8)
                            + new String(TRUNCATED_MARKER, StandardCharsets.UTF_8),
                    totalBytes,
                    true
            );
        }
    }

    private record CapturedOutput(
            String value,
            int totalBytes,
            boolean truncated
    ) {
        private CapturedOutput {
            value = Objects.requireNonNull(value, "value");
            if (totalBytes < 0) {
                throw new IllegalArgumentException("totalBytes must be >= 0");
            }
        }
    }
}
