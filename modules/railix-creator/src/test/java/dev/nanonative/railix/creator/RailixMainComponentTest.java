package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.stdlib.text.LowercaseStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RailixMainComponentTest {
    private static final String USAGE = "Usage: railix run <app-directory> | railix creator [--port <1-65535>]";
    private static final String APPLICATION_USAGE =
            "Usage: <no arguments> | cli <trigger-id> [argument...] | creator "
                    + "| lock [--check | --write <output>...]";
    private static final StepCatalog APPLICATION_STEPS = StepCatalog.of(LowercaseStep.definition());

    @Test
    void mainEntrypointAcceptsHelp() {
        assertThatCode(() -> RailixMain.main(new String[]{"--help"})).doesNotThrowAnyException();
    }

    @Test
    void exitBoundaryReturnsZeroWithoutStoppingTheJvm() {
        assertThat(RailixMain.exitOnFailure(0)).isZero();
    }

    @Test
    void helpPrintsTheCompletePublicCommandSurface() {
        assertThat(invoke("--help")).isEqualTo(new Invocation(0, USAGE + "\n", ""));
    }

    @Test
    void unknownCommandReturnsUsageError() {
        assertThat(invoke("unknown")).isEqualTo(new Invocation(
                2,
                "",
                "Unknown command: unknown\n" + USAGE + "\n"
        ));
    }

    @Test
    void noArgumentsPrintsHelp() {
        assertThat(invoke()).isEqualTo(new Invocation(0, USAGE + "\n", ""));
    }

    @Test
    void runRequiresExactlyOneDirectory() {
        assertThat(invoke("run")).isEqualTo(new Invocation(
                2,
                "",
                "run requires exactly one app directory.\n" + USAGE + "\n"
        ));
    }

    @Test
    void lowercaseExampleRunsThroughThePublicLauncher() {
        assertThat(invoke("run", example().toString())).isEqualTo(new Invocation(
                0,
                "{\"text\":\"hello railix\"}\n",
                ""
        ));
    }

    @Test
    void missingAppDirectoryReturnsDeterministicLauncherError(@TempDir final Path temporary) {
        assertThat(invoke("run", temporary.resolve("missing").toString())).isEqualTo(new Invocation(
                2,
                "",
                "{\"error\":{\"code\":\"APP_DIRECTORY_MISSING\",\"message\":\"App directory does not exist.\"},"
                        + "\"status\":\"launcher-rejected\"}\n"
        ));
    }

    @Test
    void invalidAppDirectoryPathReturnsDeterministicLauncherError() {
        assertThat(invoke("run", "\0")).isEqualTo(new Invocation(
                2,
                "",
                "{\"error\":{\"code\":\"APP_DIRECTORY_INVALID\","
                        + "\"message\":\"App directory path is invalid.\"},"
                        + "\"status\":\"launcher-rejected\"}\n"
        ));
    }

    @Test
    void missingFlowFileReturnsDeterministicLauncherError(@TempDir final Path temporary) {
        assertThat(invoke("run", temporary.toString())).isEqualTo(new Invocation(
                2,
                "",
                "{\"error\":{\"code\":\"FLOW_FILE_MISSING\",\"message\":\"Missing railix.flow.json.\"},"
                        + "\"status\":\"launcher-rejected\"}\n"
        ));
    }

    @Test
    void missingInputFileReturnsDeterministicLauncherError(@TempDir final Path temporary) throws IOException {
        Files.writeString(temporary.resolve("railix.flow.json"), "{}");

        assertThat(invoke("run", temporary.toString())).isEqualTo(new Invocation(
                2,
                "",
                "{\"error\":{\"code\":\"INPUT_FILE_MISSING\",\"message\":\"Missing input.json.\"},"
                        + "\"status\":\"launcher-rejected\"}\n"
        ));
    }

    @Test
    void malformedFlowReturnsCompileDiagnostic(@TempDir final Path temporary) throws IOException {
        Files.writeString(temporary.resolve("railix.flow.json"), "{");
        Files.writeString(temporary.resolve("input.json"), "{}");

        final Invocation invocation = invoke("run", temporary.toString());

        assertThat(invocation).satisfies(result -> {
            assertThat(result.exitCode()).isEqualTo(2);
            assertThat(result.stdout()).isEmpty();
            assertThat(result.stderr()).contains(
                    "\"status\":\"compile-rejected\"",
                    "\"code\":\"FLOW_JSON_INVALID\""
            );
        });
    }

    @Test
    void authoringFlowRejectsInvalidUtf8(@TempDir final Path temporary) throws IOException {
        Files.write(temporary.resolve("railix.flow.json"), new byte[]{(byte) 0xc3, 0x28});
        Files.writeString(temporary.resolve("input.json"), "{}");

        assertThat(invoke("run", temporary.toString())).isEqualTo(new Invocation(
                2,
                "",
                "{\"diagnostics\":[{\"code\":\"FLOW_SOURCE_UTF8_INVALID\","
                        + "\"column\":0,\"line\":0,\"message\":\"Flow source is not valid UTF-8.\","
                        + "\"path\":\"$\"}],\"status\":\"compile-rejected\"}\n"
        ));
    }

    @Test
    void malformedInputReturnsInputDiagnostic(@TempDir final Path temporary) throws IOException {
        copyFlow(temporary);
        Files.writeString(temporary.resolve("input.json"), "{");

        final Invocation invocation = invoke("run", temporary.toString());

        assertThat(invocation).satisfies(result -> {
            assertThat(result.exitCode()).isEqualTo(2);
            assertThat(result.stdout()).isEmpty();
            assertThat(result.stderr()).contains(
                    "\"status\":\"input-rejected\"",
                    "\"code\":\"INPUT_JSON_INVALID\""
            );
        });
    }

    @Test
    void authoringInputCanFillTheExactByteLimit(@TempDir final Path temporary) throws IOException {
        copyFlow(temporary);
        final String input = "{\"text\":\"RAILIX\"}";
        Files.writeString(
                temporary.resolve("input.json"),
                input + " ".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES - input.length())
        );

        assertThat(invoke("run", temporary.toString()))
                .isEqualTo(new Invocation(0, "{\"text\":\"railix\"}\n", ""));
    }

    @Test
    void authoringInputCannotExceedThePublishedByteLimit(@TempDir final Path temporary) throws IOException {
        copyFlow(temporary);
        final String input = "{\"text\":\"RAILIX\"}";
        Files.writeString(
                temporary.resolve("input.json"),
                input + " ".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES - input.length() + 1)
        );

        assertThat(invoke("run", temporary.toString())).isEqualTo(new Invocation(
                2,
                "",
                "{\"diagnostics\":[{\"code\":\"INPUT_SOURCE_TOO_LARGE\",\"column\":0,\"line\":0,"
                        + "\"message\":\"Input source exceeds the 1048576-byte limit.\",\"path\":\"$\"}],"
                        + "\"status\":\"input-rejected\"}\n"
        ));
    }

    @Test
    void authoringInputRejectsInvalidUtf8(@TempDir final Path temporary) throws IOException {
        copyFlow(temporary);
        Files.write(temporary.resolve("input.json"), new byte[]{(byte) 0xc3, 0x28});

        assertInputRejection(temporary, "INPUT_SOURCE_UTF8_INVALID");
    }

    @Test
    void authoringInputRejectsAByteOrderMark(@TempDir final Path temporary) throws IOException {
        copyFlow(temporary);
        Files.write(
                temporary.resolve("input.json"),
                new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf, '{', '}'}
        );

        assertInputRejection(temporary, "INPUT_BOM_UNSUPPORTED");
    }

    @Test
    void authoringInputRejectsExcessiveContainerDepth(@TempDir final Path temporary) throws IOException {
        copyFlow(temporary);
        Files.writeString(
                temporary.resolve("input.json"),
                "{\"value\":".repeat(RailixData.DEFAULT_MAX_DEPTH + 1)
                        + "0"
                        + "}".repeat(RailixData.DEFAULT_MAX_DEPTH + 1)
        );

        assertInputRejection(temporary, "INPUT_DEPTH_EXCEEDED");
    }

    @Test
    void authoringInputRejectsAnOversizedNumber(@TempDir final Path temporary) throws IOException {
        copyFlow(temporary);
        Files.writeString(
                temporary.resolve("input.json"),
                "{\"text\":" + "1".repeat(RailixData.MAX_CANONICAL_NUMBER_CHARACTERS + 1) + "}"
        );

        assertInputRejection(temporary, "INPUT_NUMBER_LIMIT_EXCEEDED");
    }

    @Test
    void nonObjectInputReturnsAdmissionDiagnostic(@TempDir final Path temporary) throws IOException {
        copyFlow(temporary);
        Files.writeString(temporary.resolve("input.json"), "[]");

        final Invocation invocation = invoke("run", temporary.toString());

        assertThat(invocation).satisfies(result -> {
            assertThat(result.exitCode()).isEqualTo(2);
            assertThat(result.stdout()).isEmpty();
            assertThat(result.stderr()).contains("\"code\":\"FLOW_INPUT_OBJECT_REQUIRED\"");
        });
    }

    @Test
    void publicLauncherRejectsIncompleteHttpTrigger(@TempDir final Path temporary) throws IOException {
        assertThat(invokeUnsupportedTrigger(temporary, "http")).isEqualTo(new Invocation(
                2,
                "",
                "{\"diagnostics\":[{\"code\":\"FLOW_TRIGGER_HTTP_PORT_REQUIRED\","
                        + "\"column\":0,\"line\":0,\"message\":\"HTTP trigger config field port "
                        + "must be an integer.\",\"path\":\"triggers[0].config.port\"}],"
                        + "\"status\":\"compile-rejected\"}\n"
        ));
    }

    @Test
    void publicLauncherRejectsArbitraryTriggerWithoutFallback(@TempDir final Path temporary) throws IOException {
        assertThat(invokeUnsupportedTrigger(temporary, "custom")).isEqualTo(unsupportedTrigger("custom"));
    }

    @Test
    void packagedApplicationPrintsItsDerivedDependencyLock() {
        assertThat(invokeApplicationWithLock(flow(), null, APPLICATION_STEPS, "lock"))
                .isEqualTo(new Invocation(0, lockSource(), ""));
    }

    @Test
    void packagedApplicationPublicBoundaryChecksItsExactDependencyLock() {
        assertThat(RailixMain.executeApplication(
                List.of("lock", "--check"),
                flow(),
                lock(),
                APPLICATION_STEPS
        )).isZero();
    }

    @Test
    void packagedApplicationLockCommandIgnoresAndClosesItsStaleEmbeddedLock(
            @TempDir final Path temporary
    ) throws IOException {
        final InputStream staleLock = Files.newInputStream(Files.writeString(
                temporary.resolve("stale.lock.json"),
                staleLockSource()
        ));

        assertThat(invokeApplicationWithLock(flow(), staleLock, APPLICATION_STEPS, "lock"))
                .isEqualTo(new Invocation(0, lockSource(), ""));
        assertClosed(staleLock);
    }

    @Test
    void packagedApplicationLockCommandIgnoresAnUnreadableEmbeddedLock() throws IOException {
        final InputStream closedLock = flow();
        closedLock.close();

        assertThat(invokeApplicationWithLock(flow(), closedLock, APPLICATION_STEPS, "lock"))
                .isEqualTo(new Invocation(0, lockSource(), ""));
    }

    @Test
    void packagedApplicationLockCommandRejectsAMalformedAuthoredFlow() {
        assertThat(invokeApplicationWithLock(stream("{"), null, APPLICATION_STEPS, "lock"))
                .isEqualTo(new Invocation(
                        2,
                        "",
                        "{\"diagnostics\":[{\"code\":\"FLOW_JSON_INVALID\",\"column\":2,\"line\":1,"
                                + "\"message\":\"Expected an object field name.\",\"path\":\"$\"}],"
                                + "\"status\":\"compile-rejected\"}\n"
                ));
    }

    @Test
    void packagedApplicationAcceptsItsExactDependencyLock() {
        assertThat(invokeApplication(flow(), APPLICATION_STEPS, "lock", "--check"))
                .isEqualTo(new Invocation(0, "", ""));
    }

    @Test
    void packagedApplicationRejectsAMissingDependencyLock() {
        assertThat(invokeApplicationWithLock(flow(), null, APPLICATION_STEPS)).isEqualTo(new Invocation(
                4,
                "",
                "{\"error\":{\"code\":\"PACKAGED_LOCK_MISSING\","
                        + "\"message\":\"Packaged dependency lock resource is missing.\"},"
                        + "\"status\":\"launcher-rejected\"}\n"
        ));
    }

    @Test
    void packagedApplicationRejectsAnUnreadableDependencyLock() throws IOException {
        final InputStream closedLock = flow();
        closedLock.close();

        assertThat(invokeApplicationWithLock(
                flow(),
                closedLock,
                APPLICATION_STEPS
        )).isEqualTo(new Invocation(
                4,
                "",
                "{\"error\":{\"code\":\"PACKAGED_LOCK_READ_FAILED\","
                        + "\"message\":\"Could not read packaged dependency lock.\"},"
                        + "\"status\":\"launcher-rejected\"}\n"
        ));
    }

    @Test
    void packagedFlowReadStopsAtThePublishedByteLimit() {
        final TrackingInputStream packagedFlow = new TrackingInputStream(
                " ".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES + 2)
        );
        final TrackingInputStream packagedLock = new TrackingInputStream(lockSource());

        assertThat(new ResourceObservation(
                invokeApplicationWithLock(packagedFlow, packagedLock, APPLICATION_STEPS, "lock"),
                packagedFlow.available(),
                packagedFlow.closed,
                packagedLock.closed
        )).isEqualTo(new ResourceObservation(
                new Invocation(
                        2,
                        "",
                        "{\"diagnostics\":[{\"code\":\"FLOW_SOURCE_TOO_LARGE\",\"column\":0,\"line\":0,"
                                + "\"message\":\"Flow source exceeds the 1048576-byte limit.\","
                                + "\"path\":\"$\"}],\"status\":\"compile-rejected\"}\n"
                ),
                1,
                true,
                true
        ));
    }

    @Test
    void packagedFlowReadAllowsTheExactPublishedByteLimit() {
        final String source = flowSource();
        final TrackingInputStream packagedFlow = new TrackingInputStream(
                source + " ".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES - source.length())
        );
        final TrackingInputStream packagedLock = new TrackingInputStream(lockSource());

        assertThat(new ResourceObservation(
                invokeApplicationWithLock(packagedFlow, packagedLock, APPLICATION_STEPS, "lock"),
                packagedFlow.available(),
                packagedFlow.closed,
                packagedLock.closed
        )).isEqualTo(new ResourceObservation(
                new Invocation(0, lockSource(), ""),
                0,
                true,
                true
        ));
    }

    @Test
    void packagedFlowRejectsInvalidUtf8() {
        final TrackingInputStream packagedFlow = new TrackingInputStream(
                new byte[]{(byte) 0xc3, 0x28}
        );
        final TrackingInputStream packagedLock = new TrackingInputStream(lockSource());

        assertThat(new ClosureObservation(
                invokeApplicationWithLock(packagedFlow, packagedLock, APPLICATION_STEPS, "lock"),
                packagedFlow.closed,
                packagedLock.closed
        )).isEqualTo(new ClosureObservation(
                new Invocation(
                        2,
                        "",
                        "{\"diagnostics\":[{\"code\":\"FLOW_SOURCE_UTF8_INVALID\","
                                + "\"column\":0,\"line\":0,\"message\":\"Flow source is not valid UTF-8.\","
                                + "\"path\":\"$\"}],\"status\":\"compile-rejected\"}\n"
                ),
                true,
                true
        ));
    }

    @Test
    void invalidUtf8PackagedFlowReportsALockCloseFailure() {
        assertThat(invokeApplicationWithLock(
                new TrackingInputStream(new byte[]{(byte) 0xc3, 0x28}),
                new CloseFailureStream(lockSource()),
                APPLICATION_STEPS,
                "lock"
        )).isEqualTo(resourceCloseFailure());
    }

    @Test
    void packagedDependencyLockReadStopsAtThePublishedByteLimit() {
        final TrackingInputStream packagedFlow = new TrackingInputStream(flowSource());
        final TrackingInputStream packagedLock = new TrackingInputStream(
                " ".repeat(RailixData.MAX_SOURCE_BYTES + 2)
        );

        assertThat(new ResourceObservation(
                invokeApplicationWithLock(packagedFlow, packagedLock, APPLICATION_STEPS),
                packagedLock.available(),
                packagedFlow.closed,
                packagedLock.closed
        )).isEqualTo(new ResourceObservation(
                new Invocation(
                        2,
                        "",
                        "{\"diagnostics\":[{\"code\":\"STEP_LOCK_SOURCE_TOO_LARGE\","
                                + "\"column\":0,\"line\":0,\"message\":\"Step dependency lock exceeds the "
                                + "8388608-byte limit.\",\"path\":\"lock\"}],"
                                + "\"status\":\"compile-rejected\"}\n"
                ),
                1,
                true,
                true
        ));
    }

    @Test
    void packagedDependencyLockReadAllowsTheExactPublishedByteLimit() {
        final String source = lockSource();
        final TrackingInputStream packagedFlow = new TrackingInputStream(flowSource());
        final TrackingInputStream packagedLock = new TrackingInputStream(
                source + " ".repeat(RailixData.MAX_SOURCE_BYTES - source.length())
        );

        assertThat(new ResourceObservation(
                invokeApplicationWithLock(packagedFlow, packagedLock, APPLICATION_STEPS),
                packagedLock.available(),
                packagedFlow.closed,
                packagedLock.closed
        )).isEqualTo(new ResourceObservation(
                new Invocation(
                        2,
                        "",
                        "{\"diagnostics\":[{\"code\":\"STEP_LOCK_NON_CANONICAL\","
                                + "\"column\":0,\"line\":0,\"message\":\"Step dependency lock must use "
                                + "canonical JSON with one final newline.\",\"path\":\"$\"}],"
                                + "\"status\":\"compile-rejected\"}\n"
                ),
                0,
                true,
                true
        ));
    }

    @Test
    void packagedLockRejectsInvalidUtf8() {
        final TrackingInputStream packagedFlow = new TrackingInputStream(flowSource());
        final TrackingInputStream packagedLock = new TrackingInputStream(
                new byte[]{(byte) 0xc3, 0x28}
        );

        assertThat(new ClosureObservation(
                invokeApplicationWithLock(packagedFlow, packagedLock, APPLICATION_STEPS),
                packagedFlow.closed,
                packagedLock.closed
        )).isEqualTo(new ClosureObservation(
                new Invocation(
                        2,
                        "",
                        "{\"diagnostics\":[{\"code\":\"STEP_LOCK_SOURCE_UTF8_INVALID\","
                                + "\"column\":0,\"line\":0,\"message\":\"Step dependency lock is not "
                                + "valid UTF-8.\",\"path\":\"lock\"}],"
                                + "\"status\":\"compile-rejected\"}\n"
                ),
                true,
                true
        ));
    }

    @Test
    void uncheckedPackagedFlowReadFailureClosesBothResources() {
        final RuntimeReadFailureStream packagedFlow = new RuntimeReadFailureStream();
        final TrackingInputStream packagedLock = new TrackingInputStream(lockSource());

        assertThat(new ClosureObservation(
                invokeApplicationWithLock(packagedFlow, packagedLock, APPLICATION_STEPS),
                packagedFlow.closed,
                packagedLock.closed
        )).isEqualTo(new ClosureObservation(
                new Invocation(
                        4,
                        "",
                        "{\"error\":{\"code\":\"PACKAGED_FLOW_READ_FAILED\","
                                + "\"message\":\"Could not read packaged flow.\"},"
                                + "\"status\":\"launcher-rejected\"}\n"
                ),
                true,
                true
        ));
    }

    @Test
    void uncheckedPackagedLockReadFailureClosesTheResource() {
        final TrackingInputStream packagedFlow = new TrackingInputStream(flowSource());
        final RuntimeReadFailureStream packagedLock = new RuntimeReadFailureStream();

        assertThat(new ClosureObservation(
                invokeApplicationWithLock(packagedFlow, packagedLock, APPLICATION_STEPS),
                packagedFlow.closed,
                packagedLock.closed
        )).isEqualTo(new ClosureObservation(
                new Invocation(
                        4,
                        "",
                        "{\"error\":{\"code\":\"PACKAGED_LOCK_READ_FAILED\","
                                + "\"message\":\"Could not read packaged dependency lock.\"},"
                                + "\"status\":\"launcher-rejected\"}\n"
                ),
                true,
                true
        ));
    }

    @Test
    void successfulDependencyCheckClosesBothPackagedResources() {
        final TrackingInputStream packagedFlow = new TrackingInputStream(flowSource());
        final TrackingInputStream packagedLock = new TrackingInputStream(lockSource());

        assertThat(new ClosureObservation(
                invokeApplicationWithLock(
                        packagedFlow,
                        packagedLock,
                        APPLICATION_STEPS,
                        "lock",
                        "--check"
                ),
                packagedFlow.closed,
                packagedLock.closed
        )).isEqualTo(new ClosureObservation(
                new Invocation(0, "", ""),
                true,
                true
        ));
    }

    @Test
    void packagedApplicationStartupRejectsAStaleDependencyLock() {
        assertThat(invokeApplicationWithLock(
                flow(),
                stream(staleLockSource()),
                APPLICATION_STEPS
        )).isEqualTo(lockMismatch());
    }

    @Test
    void packagedApplicationCreatorRejectsAStaleDependencyLock() {
        assertThat(invokeApplicationWithLock(
                flow(),
                stream(staleLockSource()),
                APPLICATION_STEPS,
                "creator"
        )).isEqualTo(lockMismatch());
    }

    @Test
    void packagedApplicationRejectsLockWriteWithoutAnOutputPath() {
        assertThat(invokeApplication(flow(), APPLICATION_STEPS, "lock", "--write"))
                .isEqualTo(new Invocation(2, "", APPLICATION_USAGE + "\n"));
    }

    @Test
    void packagedApplicationWritesItsDerivedDependencyLock(@TempDir final Path temporary) throws IOException {
        final Path output = temporary.resolve("nested/railix.lock.json");

        assertThat(invokeApplication(flow(), APPLICATION_STEPS, "lock", "--write", output.toString()))
                .isEqualTo(new Invocation(0, "", ""));
        assertThat(Files.readString(output)).isEqualTo(lockSource());
    }

    @Test
    void packagedApplicationOverwritesItsDependencyLockRepeatably(@TempDir final Path temporary) throws IOException {
        final Path output = Files.writeString(temporary.resolve("railix.lock.json"), "stale");

        assertThat(invokeApplication(flow(), APPLICATION_STEPS, "lock", "--write", output.toString()))
                .isEqualTo(new Invocation(0, "", ""));
        assertThat(invokeApplication(flow(), APPLICATION_STEPS, "lock", "--write", output.toString()))
                .isEqualTo(new Invocation(0, "", ""));
        assertThat(Files.readString(output)).isEqualTo(lockSource());
    }

    @Test
    void packagedApplicationWritesItsDependencyLockToEveryRequestedOutput(
            @TempDir final Path temporary
    ) throws IOException {
        final Path first = temporary.resolve("source/railix.lock.json");
        final Path second = temporary.resolve("classes/railix.lock.json");

        assertThat(invokeApplication(
                flow(),
                APPLICATION_STEPS,
                "lock", "--write", first.toString(), second.toString()
        )).isEqualTo(new Invocation(0, "", ""));
        assertThat(List.of(Files.readString(first), Files.readString(second)))
                .containsExactly(lockSource(), lockSource());
    }

    @Test
    void packagedApplicationRejectsAnInvalidDependencyLockOutputPath() {
        assertThat(invokeApplication(flow(), APPLICATION_STEPS, "lock", "--write", "\0"))
                .isEqualTo(new Invocation(
                        2,
                        "",
                        "{\"error\":{\"code\":\"STEP_LOCK_PATH_INVALID\","
                                + "\"message\":\"Step dependency lock output path is invalid.\"},"
                                + "\"status\":\"launcher-rejected\"}\n"
                ));
    }

    @Test
    void packagedApplicationReportsADependencyLockWriteFailure(@TempDir final Path temporary) {
        final Path directory = temporary.resolve("railix.lock.json");
        assertThat(directory.toFile().mkdir()).isTrue();

        assertThat(invokeApplication(flow(), APPLICATION_STEPS, "lock", "--write", directory.toString()))
                .isEqualTo(new Invocation(
                        4,
                        "",
                        "{\"error\":{\"code\":\"STEP_LOCK_WRITE_FAILED\","
                                + "\"message\":\"Could not write Step dependency lock.\"},"
                                + "\"status\":\"launcher-rejected\"}\n"
                ));
    }

    @Test
    void packagedApplicationLockWriteRejectsAMalformedAuthoredFlow(@TempDir final Path temporary) {
        assertThat(invokeApplicationWithLock(
                stream("{"),
                null,
                APPLICATION_STEPS,
                "lock",
                "--write",
                temporary.resolve("railix.lock.json").toString()
        )).isEqualTo(new Invocation(
                2,
                "",
                "{\"diagnostics\":[{\"code\":\"FLOW_JSON_INVALID\",\"column\":2,\"line\":1,"
                        + "\"message\":\"Expected an object field name.\",\"path\":\"$\"}],"
                        + "\"status\":\"compile-rejected\"}\n"
        ));
    }

    @Test
    void packagedApplicationRejectsAJavaNullDependencyLockOutputPath() {
        assertThat(invokeApplication(
                flow(),
                APPLICATION_STEPS,
                Arrays.asList("lock", "--write", null)
        )).isEqualTo(new Invocation(
                2,
                "",
                "{\"error\":{\"code\":\"STEP_LOCK_PATH_INVALID\","
                        + "\"message\":\"Step dependency lock output path is invalid.\"},"
                        + "\"status\":\"launcher-rejected\"}\n"
        ));
    }

    @Test
    void packagedApplicationRejectsAFileSystemRootAsALockOutput() {
        final Path root = Path.of("").toAbsolutePath().getRoot();

        assertThat(invokeApplication(flow(), APPLICATION_STEPS, "lock", "--write", root.toString()))
                .isEqualTo(new Invocation(
                        4,
                        "",
                        "{\"error\":{\"code\":\"STEP_LOCK_WRITE_FAILED\","
                                + "\"message\":\"Could not write Step dependency lock.\"},"
                                + "\"status\":\"launcher-rejected\"}\n"
                ));
    }

    @Test
    void packagedApplicationRejectsAMissingFlowResource(@TempDir final Path temporary) throws IOException {
        final InputStream packagedLock = fileLock(temporary);

        assertThat(invokeApplicationWithLock(null, packagedLock, APPLICATION_STEPS, "creator"))
                .isEqualTo(new Invocation(
                        4,
                        "",
                        "{\"error\":{\"code\":\"PACKAGED_FLOW_MISSING\","
                                + "\"message\":\"Packaged flow resource is missing.\"},"
                        + "\"status\":\"launcher-rejected\"}\n"
                ));
        assertClosed(packagedLock);
    }

    @Test
    void packagedApplicationRejectsAnUnreadableFlowResource(@TempDir final Path temporary) throws IOException {
        final InputStream closedFlow = flow();
        closedFlow.close();
        final InputStream packagedLock = fileLock(temporary);

        assertThat(invokeApplicationWithLock(
                closedFlow,
                packagedLock,
                APPLICATION_STEPS,
                "creator"
        ))
                .isEqualTo(new Invocation(
                        4,
                        "",
                        "{\"error\":{\"code\":\"PACKAGED_FLOW_READ_FAILED\","
                                + "\"message\":\"Could not read packaged flow.\"},"
                        + "\"status\":\"launcher-rejected\"}\n"
                ));
        assertClosed(packagedLock);
    }

    @Test
    void packagedApplicationRejectsAMissingStepCatalog(@TempDir final Path temporary) throws IOException {
        final InputStream packagedLock = fileLock(temporary);

        assertThat(invokeApplicationWithLock(flow(), packagedLock, null, "creator")).isEqualTo(new Invocation(
                4,
                "",
                "{\"error\":{\"code\":\"STEP_CATALOG_MISSING\","
                        + "\"message\":\"Step catalog is missing.\"},"
                + "\"status\":\"launcher-rejected\"}\n"
        ));
        assertClosed(packagedLock);
    }

    @Test
    void packagedApplicationRejectsRemovedRunCommand() {
        assertThat(invokeApplication(flow(), APPLICATION_STEPS, "run"))
                .isEqualTo(new Invocation(2, "", APPLICATION_USAGE + "\n"));
    }

    @Test
    void packagedApplicationRejectsJavaNullArguments(@TempDir final Path temporary) throws IOException {
        final InputStream packagedLock = fileLock(temporary);

        assertThat(invokeApplicationWithLock(flow(), packagedLock, APPLICATION_STEPS, (List<String>) null))
                .isEqualTo(new Invocation(2, "", APPLICATION_USAGE + "\n"));
        assertClosed(packagedLock);
    }

    @Test
    void packagedApplicationRejectsAJavaNullArgument() {
        assertThat(invokeApplication(flow(), APPLICATION_STEPS, Arrays.asList("run", null)))
                .isEqualTo(new Invocation(2, "", APPLICATION_USAGE + "\n"));
    }

    @Test
    void packagedApplicationClosesItsResourcesForInvalidArguments(@TempDir final Path temporary) throws IOException {
        final InputStream packagedFlow = flow();
        final InputStream packagedLock = fileLock(temporary);

        assertThat(invokeApplicationWithLock(packagedFlow, packagedLock, APPLICATION_STEPS, "unknown"))
                .isEqualTo(new Invocation(2, "", APPLICATION_USAGE + "\n"));
        assertClosed(packagedFlow);
        assertClosed(packagedLock);
    }

    @Test
    void packagedApplicationReportsAResourceCloseFailure() {
        assertThat(invokeApplicationWithLock(
                flow(),
                new CloseFailureStream(lockSource()),
                APPLICATION_STEPS,
                "creator"
        )).isEqualTo(resourceCloseFailure());
    }

    @Test
    void packagedApplicationReportsAnUncheckedResourceCloseFailure() {
        assertThat(invokeApplicationWithLock(
                flow(),
                new RuntimeCloseFailureStream(lockSource()),
                APPLICATION_STEPS,
                "creator"
        )).isEqualTo(resourceCloseFailure());
    }

    @Test
    void packagedApplicationReportsAFlowResourceCloseFailure() {
        assertThat(invokeApplicationWithLock(
                new CloseFailureStream(flowSource()),
                lock(),
                APPLICATION_STEPS,
                "creator"
        )).isEqualTo(resourceCloseFailure());
    }

    @Test
    void packagedApplicationLockPrintReportsAResourceCloseFailure() {
        assertThat(invokeApplicationWithLock(
                flow(),
                new CloseFailureStream(lockSource()),
                APPLICATION_STEPS,
                "lock"
        )).isEqualTo(resourceCloseFailure());
    }

    @Test
    void packagedApplicationLockWriteReportsAResourceCloseFailure(@TempDir final Path temporary) {
        assertThat(invokeApplicationWithLock(
                flow(),
                new CloseFailureStream(lockSource()),
                APPLICATION_STEPS,
                "lock",
                "--write",
                temporary.resolve("railix.lock.json").toString()
        )).isEqualTo(resourceCloseFailure());
    }

    @Test
    void packagedApplicationMissingFlowReportsALockCloseFailure() {
        assertThat(invokeApplicationWithLock(
                null,
                new CloseFailureStream(lockSource()),
                APPLICATION_STEPS,
                "creator"
        )).isEqualTo(resourceCloseFailure());
    }

    @Test
    void packagedApplicationUnreadableFlowReportsItsOwnCloseFailure() {
        assertThat(invokeApplicationWithLock(
                new ReadAndCloseFailureStream(),
                lock(),
                APPLICATION_STEPS,
                "creator"
        )).isEqualTo(resourceCloseFailure());
    }

    @Test
    void packagedApplicationUnreadableFlowReportsALockCloseFailure() throws IOException {
        final InputStream unreadableFlow = flow();
        unreadableFlow.close();

        assertThat(invokeApplicationWithLock(
                unreadableFlow,
                new CloseFailureStream(lockSource()),
                APPLICATION_STEPS,
                "creator"
        )).isEqualTo(resourceCloseFailure());
    }

    @Test
    void packagedApplicationMissingCatalogReportsALockCloseFailure() {
        assertThat(invokeApplicationWithLock(
                flow(),
                new CloseFailureStream(lockSource()),
                null,
                "creator"
        )).isEqualTo(resourceCloseFailure());
    }

    @Test
    void packagedApplicationInvalidArgumentsReportALockCloseFailure() {
        assertThat(invokeApplicationWithLock(
                flow(),
                new CloseFailureStream(lockSource()),
                APPLICATION_STEPS,
                (List<String>) null
        )).isEqualTo(resourceCloseFailure());
    }

    @Test
    void packagedApplicationUnknownCommandReportsALockCloseFailure() {
        assertThat(invokeApplicationWithLock(
                flow(),
                new CloseFailureStream(lockSource()),
                APPLICATION_STEPS,
                "unknown"
        )).isEqualTo(resourceCloseFailure());
    }

    @Test
    void packagedApplicationUnreadableLockReportsItsCloseFailure() {
        assertThat(invokeApplicationWithLock(
                flow(),
                new ReadAndCloseFailureStream(),
                APPLICATION_STEPS,
                "creator"
        )).isEqualTo(resourceCloseFailure());
    }

    @Test
    void packagedApplicationCreatorUsesAnAssignedPortAndClosesWhenInterrupted() throws Exception {
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        final AtomicInteger exitCode = new AtomicInteger(-1);
        final Thread creator = Thread.ofVirtual().start(() -> {
            try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
                 PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
                exitCode.set(RailixMain.executeApplication(
                        List.of("creator"),
                        flow(),
                        lock(),
                        APPLICATION_STEPS,
                        InputStream.nullInputStream(),
                        out,
                        err
                ));
            }
        });
        try {
            awaitOutput(stdout);
        } finally {
            stopCreator(creator);
        }

        assertThat(new Invocation(
                exitCode.get(),
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8)
        )).satisfies(result -> {
            assertThat(creator.isAlive()).isFalse();
            assertThat(result.exitCode()).isEqualTo(4);
            assertThat(result.stdout()).contains(
                    "\"status\":\"creator-ready\"",
                    "\"creatorUrl\":\"http://127.0.0.1:"
            );
            assertThat(result.stderr()).contains("\"code\":\"CREATOR_INTERRUPTED\"");
        });
    }

    @Test
    void creatorRejectsNonNumericPort() {
        assertThat(invoke("creator", "--port", "nope")).isEqualTo(new Invocation(
                2,
                "",
                "Creator port must be an integer.\n" + USAGE + "\n"
        ));
    }

    @Test
    void creatorRejectsOutOfRangePort() {
        assertThat(invoke("creator", "--port", "0")).isEqualTo(new Invocation(
                2,
                "",
                "Creator port must be between 1 and 65535.\n" + USAGE + "\n"
        ));
    }

    @Test
    void creatorRejectsPortAboveMaximum() {
        assertThat(invoke("creator", "--port", "65536")).isEqualTo(new Invocation(
                2,
                "",
                "Creator port must be between 1 and 65535.\n" + USAGE + "\n"
        ));
    }

    @Test
    void creatorRejectsUnknownOptions() {
        assertThat(invoke("creator", "--unknown")).isEqualTo(new Invocation(
                2,
                "",
                "creator accepts only --port <1-65535>.\n" + USAGE + "\n"
        ));
    }

    @Test
    void creatorRejectsUnknownThreePartOption() {
        assertThat(invoke("creator", "--unknown", "1")).isEqualTo(new Invocation(
                2,
                "",
                "creator accepts only --port <1-65535>.\n" + USAGE + "\n"
        ));
    }

    @Test
    void creatorUsesDocumentedDefaultPort() throws Exception {
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        final AtomicInteger exitCode = new AtomicInteger(-1);
        final Thread creator = Thread.ofVirtual().start(() -> {
            try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
                 PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
                exitCode.set(RailixMain.execute(List.of("creator"), out, err));
            }
        });
        try {
            awaitOutput(stdout);
        } finally {
            stopCreator(creator);
        }

        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .contains("\"creatorUrl\":\"http://127.0.0.1:4173/\"");
    }

    @Test
    void creatorStartsOnExplicitPortAndClosesWhenInterrupted() throws Exception {
        final int port;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        final AtomicInteger exitCode = new AtomicInteger(-1);
        final Thread creator = Thread.ofVirtual().start(() -> {
            try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
                 PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
                exitCode.set(RailixMain.execute(
                        List.of("creator", "--port", Integer.toString(port)),
                        out,
                        err
                ));
            }
        });
        try {
            awaitOutput(stdout);
        } finally {
            stopCreator(creator);
        }

        assertThat(new Invocation(
                exitCode.get(),
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8)
        )).satisfies(result -> {
            assertThat(result.exitCode()).isEqualTo(4);
            assertThat(result.stdout()).contains(
                    "\"status\":\"creator-ready\"",
                    "\"creatorUrl\":\"http://127.0.0.1:" + port + "/\""
            );
            assertThat(result.stderr()).contains("\"code\":\"CREATOR_INTERRUPTED\"");
        });
    }

    @Test
    void creatorReportsBindFailure() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(
                0,
                1,
                java.net.InetAddress.getByAddress(new byte[]{127, 0, 0, 1})
        )) {
            final Invocation invocation = invoke(
                    "creator",
                    "--port",
                    Integer.toString(socket.getLocalPort())
            );

            assertThat(invocation).isEqualTo(new Invocation(
                    4,
                    "",
                    "{\"error\":{\"code\":\"CREATOR_START_FAILED\","
                            + "\"message\":\"Could not start Creator.\"},"
                            + "\"status\":\"launcher-rejected\"}\n"
            ));
        }
    }

    private static void awaitOutput(final ByteArrayOutputStream output) throws InterruptedException {
        for (int attempt = 0; attempt < 200 && output.size() == 0; attempt++) {
            Thread.sleep(10);
        }
        assertThat(output.size()).isPositive();
    }

    private static Thread stopCreator(final Thread creator) throws InterruptedException {
        creator.interrupt();
        creator.join(2_000);
        assertThat(creator.isAlive()).as("Creator thread survived interrupt").isFalse();
        return creator;
    }

    private static Invocation invoke(final String... arguments) {
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        final int exitCode;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            exitCode = RailixMain.execute(List.of(arguments), out, err);
        }
        return new Invocation(
                exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8)
        );
    }

    private static Invocation invokeApplication(
            final InputStream packagedFlow,
            final StepCatalog catalog,
            final String... arguments
    ) {
        return invokeApplication(packagedFlow, catalog, List.of(arguments));
    }

    private static Invocation invokeApplication(
            final InputStream packagedFlow,
            final StepCatalog catalog,
            final List<String> arguments
    ) {
        return invokeApplicationWithLock(packagedFlow, lock(), catalog, arguments);
    }

    private static Invocation invokeApplicationWithLock(
            final InputStream packagedFlow,
            final InputStream packagedLock,
            final StepCatalog catalog,
            final String... arguments
    ) {
        return invokeApplicationWithLock(packagedFlow, packagedLock, catalog, List.of(arguments));
    }

    private static Invocation invokeApplicationWithLock(
            final InputStream packagedFlow,
            final InputStream packagedLock,
            final StepCatalog catalog,
            final List<String> arguments
    ) {
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        final int exitCode;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            exitCode = RailixMain.executeApplication(
                    arguments,
                    packagedFlow,
                    packagedLock,
                    catalog,
                    InputStream.nullInputStream(),
                    out,
                    err
            );
        }
        return new Invocation(
                exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8)
        );
    }

    private static Path example() {
        return Path.of("..", "..", "examples", "lowercase-app").toAbsolutePath().normalize();
    }

    private static InputStream flow() {
        try {
            return Files.newInputStream(example().resolve("railix.flow.json"));
        } catch (final IOException exception) {
            throw new AssertionError("Could not open the committed test flow.", exception);
        }
    }

    private static InputStream lock() {
        return stream(lockSource());
    }

    private static InputStream fileLock(final Path temporary) throws IOException {
        return Files.newInputStream(Files.writeString(temporary.resolve("packaged.lock.json"), lockSource()));
    }

    private static void assertClosed(final InputStream stream) {
        assertThatThrownBy(stream::read).isInstanceOf(IOException.class);
    }

    private static String lockSource() {
        final CompileResult result;
        try (InputStream source = flow()) {
            result = FlowCompiler.compile(
                    new String(source.readAllBytes(), StandardCharsets.UTF_8),
                    APPLICATION_STEPS
            );
        } catch (final IOException exception) {
            throw new AssertionError("Could not derive the committed test lock.", exception);
        }
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        return ((CompileResult.Compiled) result).lock();
    }

    private static String staleLockSource() {
        return lockSource().replaceFirst(
                "sha256:[0-9a-f]{64}",
                "sha256:" + "0".repeat(64)
        );
    }

    private static String flowSource() {
        try (InputStream source = flow()) {
            return new String(source.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException exception) {
            throw new AssertionError("Could not read the committed test flow.", exception);
        }
    }

    private static InputStream stream(final String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Invocation lockMismatch() {
        return new Invocation(
                2,
                "",
                "{\"diagnostics\":[{\"code\":\"STEP_LOCK_FLOW_MISMATCH\",\"column\":0,"
                        + "\"line\":0,\"message\":\"Step dependency lock does not match the authored flow.\","
                        + "\"path\":\"flow\"}],\"status\":\"compile-rejected\"}\n"
        );
    }

    private static Invocation resourceCloseFailure() {
        return new Invocation(
                4,
                "",
                "{\"error\":{\"code\":\"PACKAGED_RESOURCE_CLOSE_FAILED\","
                        + "\"message\":\"Could not close packaged application resources.\"},"
                        + "\"status\":\"launcher-rejected\"}\n"
        );
    }

    private static void copyFlow(final Path target) throws IOException {
        Files.copy(example().resolve("railix.flow.json"), target.resolve("railix.flow.json"));
    }

    private static void assertInputRejection(final Path target, final String code) {
        final Invocation invocation = invoke("run", target.toString());
        assertThat(List.of(
                invocation.exitCode(),
                invocation.stdout().isEmpty(),
                invocation.stderr().contains("\"status\":\"input-rejected\""),
                invocation.stderr().contains("\"code\":\"" + code + "\"")
        )).containsExactly(2, true, true, true);
    }

    private static Invocation invokeUnsupportedTrigger(final Path target, final String type) throws IOException {
        Files.writeString(
                target.resolve("railix.flow.json"),
                Files.readString(example().resolve("railix.flow.json")).replace(
                        "\"triggers\": []",
                        "\"triggers\":[{\"id\":\"source\",\"type\":\"" + type + "\",\"config\":{}}]"
                )
        );
        Files.writeString(target.resolve("input.json"), "{\"text\":\"Railix\"}");
        return invoke("run", target.toString());
    }

    private static Invocation unsupportedTrigger(final String type) {
        return new Invocation(
                2,
                "",
                "{\"diagnostics\":[{\"code\":\"FLOW_TRIGGER_TYPE_UNSUPPORTED\","
                        + "\"column\":0,\"line\":0,\"message\":\"Trigger type is not implemented: "
                        + type + ".\",\"path\":\"triggers[0].type\"}],\"status\":\"compile-rejected\"}\n"
        );
    }

    private record Invocation(int exitCode, String stdout, String stderr) {
    }

    private record ResourceObservation(
            Invocation invocation,
            int remainingBytes,
            boolean flowClosed,
            boolean lockClosed
    ) {
    }

    private record ClosureObservation(
            Invocation invocation,
            boolean flowClosed,
            boolean lockClosed
    ) {
    }

    private static final class CloseFailureStream extends ByteArrayInputStream {
        private CloseFailureStream(final String value) {
            super(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() throws IOException {
            throw new IOException("Expected close failure.");
        }
    }

    private static final class ReadAndCloseFailureStream extends InputStream {
        @Override
        public int read() throws IOException {
            throw new IOException("Expected read failure.");
        }

        @Override
        public void close() throws IOException {
            throw new IOException("Expected close failure.");
        }
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInputStream(final String value) {
            this(value.getBytes(StandardCharsets.UTF_8));
        }

        private TrackingInputStream(final byte[] value) {
            super(value);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class RuntimeReadFailureStream extends InputStream {
        private boolean closed;

        @Override
        public int read() {
            throw new IllegalStateException("Expected unchecked read failure.");
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class RuntimeCloseFailureStream extends ByteArrayInputStream {
        private RuntimeCloseFailureStream(final String source) {
            super(source.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() {
            throw new IllegalStateException("Expected unchecked close failure.");
        }
    }
}
