package dev.nanonative.railix.kernel.runtime;

import dev.nanonative.railix.kernel.model.KernelContractCodec;
import dev.nanonative.railix.kernel.model.RailixValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunSignalLogTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldAppendSignalsAndWriteNdjson() throws Exception {
        final RunSignalLog signalLog = new RunSignalLog(tempDir.resolve("signals.ndjson"));

        final RunSignalLog appended = signalLog.append(new RunSignal.RunStarted(
                "run-1-sig-1",
                Instant.parse("2026-06-27T14:00:00Z"),
                "railix-app",
                "order-flow",
                "run-1",
                new RailixValue.ObjectValue(Map.of("orderId", new RailixValue.StringValue("42")))
        )).append(new RunSignal.ReplyProduced(
                "run-1-sig-2",
                Instant.parse("2026-06-27T14:00:01Z"),
                "railix-app",
                "order-flow",
                "run-1",
                "immediate",
                new RailixValue.NumberValue(BigDecimal.valueOf(200)),
                new RailixValue.ObjectValue(Map.of("contentType", new RailixValue.StringValue("application/json")))
        ));

        final String content = Files.readString(signalLog.file());

        assertThat(appended).isSameAs(signalLog);
        assertThat(signalLog.signalCount()).isEqualTo(2);
        assertThat(signalLog.signals().stream().map(RunSignal::type)).containsExactly("run.started", "reply.produced");
        assertThat(signalLog.readSignals(10)).hasSize(2);
        assertThat(signalLog.readSignals(10).stream().map(RunSignal::type)).containsExactly("run.started", "reply.produced");
        assertThat(signalLog.readSignals(1)).containsExactly(signalLog.readSignals(10).getFirst());
        assertThat(signalLog.tailSignals(1).stream().map(RunSignal::type)).containsExactly("reply.produced");
        assertThat(content).contains("\"type\":\"run.started\"");
        assertThat(content).contains("\"type\":\"reply.produced\"");
        assertThatThrownBy(() -> signalLog.readSignals(10).clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> signalLog.tailSignals(1).clear()).isInstanceOf(UnsupportedOperationException.class);
        Files.delete(signalLog.file());
        assertThatThrownBy(signalLog::signalCount).isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(() -> signalLog.readSignals(1)).isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(() -> signalLog.tailSignals(1)).isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(() -> signalLog.forEachSignal(signal -> {
        })).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void shouldRoundTripPersistedSignalsWithNestedValuesAndEscapedStrings() throws Exception {
        final RunSignalLog signalLog = new RunSignalLog(tempDir.resolve("signals.ndjson"));
        final RunSignal.ResourceCreated resourceCreated = new RunSignal.ResourceCreated(
                "run-2-sig-1",
                Instant.parse("2026-06-27T14:00:00Z"),
                "railix-app",
                "order-flow",
                "run-2",
                "create-resource",
                7,
                "ref-1",
                "temp.file",
                new RailixValue.ObjectValue(Map.of(
                        "enabled", new RailixValue.BoolValue(true),
                        "message", new RailixValue.StringValue("line1\nline2\t\"quoted\" \\ path/slash\u0001"),
                        "nested", new RailixValue.ObjectValue(Map.of(
                                "emptyList", new RailixValue.ListValue(List.of()),
                                "items", new RailixValue.ListValue(List.of(
                                        new RailixValue.BoolValue(false),
                                        RailixValue.NULL,
                                        new RailixValue.ObjectValue(Map.of("kind", new RailixValue.StringValue("leaf")))
                                ))
                        ))
                ))
        );
        final RunSignal.RunFinished runFinished = new RunSignal.RunFinished(
                "run-2-sig-2",
                Instant.parse("2026-06-27T14:00:01Z"),
                "railix-app",
                "order-flow",
                "run-2",
                "ok",
                4_000_000_000L
        );

        signalLog.append(resourceCreated).append(runFinished);
        Files.writeString(
                signalLog.file(),
                Files.readString(signalLog.file()).replace("path/slash", "path\\/slash")
        );

        assertThat(collectSignals(signalLog)).containsExactly(resourceCreated, runFinished);
    }

    @Test
    void shouldIgnoreBlankLinesInPersistedSignalArtifacts() throws Exception {
        final RunSignalLog signalLog = new RunSignalLog(tempDir.resolve("signals.ndjson"));
        final String first = signalLine(new RunSignal.RunFinished(
                "run-blank-1",
                Instant.parse("2026-06-27T14:00:00Z"),
                "railix-app",
                "order-flow",
                "run-blank",
                "ok",
                1L
        ));
        final String second = signalLine(new RunSignal.RunFinished(
                "run-blank-2",
                Instant.parse("2026-06-27T14:00:01Z"),
                "railix-app",
                "order-flow",
                "run-blank",
                "failed",
                2L
        ));

        Files.writeString(signalLog.file(), "\n" + first + "\n\n" + second + "\n\n");

        assertThat(signalLog.signalCount()).isEqualTo(2);
        assertThat(signalLog.readSignals(0, 1).stream().map(RunSignal::signalId))
                .containsExactly("run-blank-1");
        assertThat(signalLog.readSignals(1, 1).stream().map(RunSignal::signalId))
                .containsExactly("run-blank-2");
        assertThat(signalLog.tailSignals(2).stream().map(RunSignal::signalId))
                .containsExactly("run-blank-1", "run-blank-2");
    }

    @Test
    void shouldRoundTripReplyProducedSignalsWithDecimalStatus() {
        final RunSignalLog signalLog = new RunSignalLog(tempDir.resolve("signals.ndjson"));
        final RunSignal.ReplyProduced replyProduced = new RunSignal.ReplyProduced(
                "run-3-sig-1",
                Instant.parse("2026-06-27T14:00:00Z"),
                "railix-app",
                "order-flow",
                "run-3",
                "immediate",
                new RailixValue.NumberValue(new BigDecimal("200.5")),
                new RailixValue.ObjectValue(Map.of(
                        "contentType", new RailixValue.StringValue("application/json"),
                        "traceId", new RailixValue.StringValue("trace-1")
                ))
        );

        signalLog.append(replyProduced);

        assertThat(signalLog.readSignals(1)).containsExactly(replyProduced);
    }

    @Test
    void shouldStreamSignalsWithoutMaterializingWholeLogByDefault() {
        final RunSignalLog signalLog = new RunSignalLog(tempDir.resolve("signals.ndjson"));
        final RunSignal.RunFinished first = new RunSignal.RunFinished(
                "run-stream-1",
                Instant.parse("2026-06-27T14:00:00Z"),
                "railix-app",
                "order-flow",
                "run-stream",
                "ok",
                1L
        );
        final RunSignal.RunFinished second = new RunSignal.RunFinished(
                "run-stream-2",
                Instant.parse("2026-06-27T14:00:01Z"),
                "railix-app",
                "order-flow",
                "run-stream",
                "failed",
                2L
        );

        signalLog.append(first).append(second);

        final List<String> signalIds = new ArrayList<>();
        signalLog.forEachSignal(signal -> signalIds.add(signal.signalId()));

        assertThat(signalIds).containsExactly("run-stream-1", "run-stream-2");
    }

    @Test
    void shouldRejectNegativeReadArgumentsAndNullConsumers() {
        final RunSignalLog signalLog = new RunSignalLog(tempDir.resolve("signals.ndjson"));

        assertThatThrownBy(() -> signalLog.readSignals(-1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset");
        assertThatThrownBy(() -> signalLog.readSignals(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> signalLog.readSignals(0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> signalLog.tailSignals(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> signalLog.forEachSignal(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("consumer");
    }

    @Test
    void shouldReturnEmptyCollectionsForZeroSizedReads() {
        final RunSignalLog signalLog = new RunSignalLog(tempDir.resolve("signals.ndjson"));
        signalLog.append(new RunSignal.RunFinished(
                "run-zero",
                Instant.parse("2026-06-27T14:00:00Z"),
                "railix-app",
                "order-flow",
                "run-zero",
                "ok",
                1L
        ));

        assertThat(signalLog.readSignals(0)).isEmpty();
        assertThat(signalLog.readSignals(1, 0)).isEmpty();
        assertThat(signalLog.tailSignals(0)).isEmpty();
    }

    @Test
    void shouldRejectSignalLogsWhenParentDirectoryCannotBeCreated() throws Exception {
        final Path blockedParent = tempDir.resolve("blocked-parent");
        Files.writeString(blockedParent, "not-a-directory");

        assertThatThrownBy(() -> new RunSignalLog(blockedParent.resolve("signals.ndjson")))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void shouldRejectAppendWhenSignalArtifactCannotBeWritten() throws Exception {
        final RunSignalLog signalLog = new RunSignalLog(tempDir.resolve("signals.ndjson"));
        Files.delete(signalLog.file());
        Files.createDirectory(signalLog.file());

        assertThatThrownBy(() -> signalLog.append(new RunSignal.RunFinished(
                "run-write-fail",
                Instant.parse("2026-06-27T14:00:00Z"),
                "railix-app",
                "order-flow",
                "run-write-fail",
                "ok",
                1L
        ))).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void shouldRejectSignalLinesWithTrailingJsonContent() {
        assertInvalidSignalLine(
                signalLine(new RunSignal.RunFinished(
                        "run-4-sig-1",
                        Instant.parse("2026-06-27T14:00:00Z"),
                        "railix-app",
                        "order-flow",
                        "run-4",
                        "ok",
                        1L
                )) + " trailing",
                "Unexpected trailing JSON content"
        );
    }

    @Test
    void shouldRejectSignalLinesWithUnsupportedJsonEscapes() {
        assertInvalidSignalLine(
                signalLine(new RunSignal.RunFinished(
                        "escape-target",
                        Instant.parse("2026-06-27T14:00:00Z"),
                        "railix-app",
                        "order-flow",
                        "run-5",
                        "ok",
                        1L
                )).replace("escape-target", "bad\\x"),
                "Unsupported JSON escape"
        );
    }

    @Test
    void shouldRejectSignalLinesWithInvalidUnicodeEscapes() {
        assertInvalidSignalLine(
                signalLine(new RunSignal.RunFinished(
                        "unicode-target",
                        Instant.parse("2026-06-27T14:00:00Z"),
                        "railix-app",
                        "order-flow",
                        "run-6",
                        "ok",
                        1L
                )).replace("unicode-target", "bad\\u00ZZ"),
                "Invalid unicode escape"
        );
    }

    @Test
    void shouldRejectSignalLinesWithIncompleteUnicodeEscapes() {
        assertInvalidSignalLine("\"bad\\u12", "Incomplete unicode escape");
    }

    @Test
    void shouldRejectSignalLinesWithIncompleteEscapeSequences() {
        assertInvalidSignalLine("\"bad\\", "Incomplete JSON escape sequence");
    }

    @Test
    void shouldRejectSignalLinesWithUnterminatedStrings() {
        assertInvalidSignalLine("\"unterminated", "Unterminated JSON string");
    }

    @Test
    void shouldRejectSignalLinesWithInvalidNumbers() {
        assertInvalidSignalLine(
                signalLine(new RunSignal.RunFinished(
                        "run-7-sig-1",
                        Instant.parse("2026-06-27T14:00:00Z"),
                        "railix-app",
                        "order-flow",
                        "run-7",
                        "ok",
                        1L
                )).replace("\"durationMs\":1", "\"durationMs\":-"),
                "Invalid JSON number"
        );
    }

    @Test
    void shouldRejectSignalLinesWithIncompleteDecimalNumbers() {
        assertInvalidSignalLine("{\"type\":1.}", "Invalid JSON number");
    }

    @Test
    void shouldRejectSignalLinesWithIncompleteExponentNumbers() {
        assertInvalidSignalLine("{\"type\":1e}", "Invalid JSON number");
    }

    @Test
    void shouldRejectSignalLinesThatAreNotObjectsAfterArrayParsing() {
        assertInvalidSignalLine("[]", "Signal line must decode to an object");
    }

    @Test
    void shouldRejectSignalLinesThatAreNotObjectsAfterBooleanParsing() {
        assertInvalidSignalLine("true", "Signal line must decode to an object");
        assertInvalidSignalLine("false", "Signal line must decode to an object");
        assertInvalidSignalLine("null", "Signal line must decode to an object");
    }

    @Test
    void shouldRejectSignalLinesThatAreNotObjectsAfterNumberParsing() {
        assertInvalidSignalLine("-1", "Signal line must decode to an object");
        assertInvalidSignalLine("1.5", "Signal line must decode to an object");
        assertInvalidSignalLine("1e3", "Signal line must decode to an object");
        assertInvalidSignalLine("9223372036854775808", "Signal line must decode to an object");
    }

    @Test
    void shouldRejectSignalLinesWithEmptyObjectsAndMissingSeparators() {
        assertInvalidSignalLine("{}", "type must be a string");
        assertInvalidSignalLine("{\"type\" \"run.finished\"}", "Expected JSON character: :");
        assertInvalidSignalLine("[1 2]", "Expected JSON character: ,");
        assertInvalidSignalLine("truth", "Expected JSON token: true");
    }

    private static String signalLine(final RunSignal signal) {
        return KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(signal));
    }

    private static void writeSignalLine(final RunSignalLog signalLog, final String line) {
        try {
            Files.writeString(signalLog.file(), line + "\n");
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void assertInvalidSignalLine(final String line, final String message) {
        final RunSignalLog signalLog = new RunSignalLog(tempDir.resolve("signals.ndjson"));
        writeSignalLine(signalLog, line);
        assertThatThrownBy(() -> signalLog.readSignals(1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    private static List<RunSignal> collectSignals(final RunSignalLog signalLog) {
        final List<RunSignal> signals = new ArrayList<>();
        signalLog.forEachSignal(signals::add);
        return List.copyOf(signals);
    }
}
