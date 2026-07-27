package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.CompiledFlow;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepHandler;
import dev.nanonative.railix.core.step.StepResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationScheduledTriggerE2eTest {
    @Test
    void scheduledTriggerDoesNotRunBeforeApplicationReadiness() throws Exception {
        final AtomicInteger runs = new AtomicInteger();
        final Capture capture = new Capture();
        final ApplicationRuntime runtime = ApplicationRuntime.start(
                scheduledFlow(schedule("refresh", 10, 0, 1), counted(runs)),
                capture.stdout(),
                capture.stderr()
        );
        try {
            TimeUnit.MILLISECONDS.sleep(80);

            assertThat(new ScheduleObservation(runs.get(), capture.stdoutText(), capture.stderrText()))
                    .isEqualTo(new ScheduleObservation(0, "", ""));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void zeroInitialDelayRunsOnceAfterReadinessAndPrintsTheCanonicalReply() throws Exception {
        final AtomicInteger runs = new AtomicInteger();
        final Capture capture = new Capture();
        final ApplicationRuntime runtime = start(
                scheduledFlow(schedule("refresh", 60_000, 0, 1), counted(runs)),
                capture
        );
        try {
            assertThat(await(
                    () -> runs.get() == 1 && capture.stdoutText().contains("\"steps\""),
                    Duration.ofSeconds(2)
            )).isTrue();
            assertThat(capture.stdoutText()).isEqualTo(
                    "{\"scheduledTriggers\":[\"refresh\"],\"status\":\"application-ready\"}\n"
                            + "{\"outputs\":{},\"status\":\"succeeded\","
                            + "\"steps\":[{\"outcome\":\"ok\",\"step\":\"action\"}]}\n"
            );
        } finally {
            runtime.stop();
        }
    }

    @Test
    void initialDelayIsMeasuredFromReadinessRatherThanRuntimeConstruction() throws Exception {
        final AtomicInteger runs = new AtomicInteger();
        final Capture capture = new Capture();
        final ApplicationRuntime runtime = ApplicationRuntime.start(
                scheduledFlow(schedule("refresh", 60_000, 150, 1), counted(runs)),
                capture.stdout(),
                capture.stderr()
        );
        try {
            TimeUnit.MILLISECONDS.sleep(180);
            runtime.ready();
            TimeUnit.MILLISECONDS.sleep(60);
            final int beforeDelay = runs.get();
            final boolean eventuallyRan = await(() -> runs.get() == 1, Duration.ofSeconds(1));

            assertThat(new InitialDelayObservation(beforeDelay, eventuallyRan))
                    .isEqualTo(new InitialDelayObservation(0, true));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void scheduledTriggerRunsRepeatedlyAtItsFixedRate() throws Exception {
        final AtomicInteger runs = new AtomicInteger();
        final Capture capture = new Capture();
        final ApplicationRuntime runtime = start(
                scheduledFlow(schedule("refresh", 20, 0, 1), counted(runs)),
                capture
        );
        try {
            assertThat(await(() -> runs.get() >= 3, Duration.ofSeconds(2))).isTrue();
        } finally {
            runtime.stop();
        }
    }

    @Test
    void oneConcurrentRunSkipsAnOverlappingTickWithoutQueueing() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();
        final Capture capture = new Capture();
        final ApplicationRuntime runtime = start(
                scheduledFlow(schedule("refresh", 20, 0, 1), input -> {
                    runs.incrementAndGet();
                    entered.countDown();
                    release.await();
                    return StepResult.outcome("ok");
                }),
                capture
        );
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(await(
                    () -> capture.stderrText().contains("\"code\":\"SCHEDULED_TRIGGER_OVERLAPPED\""),
                    Duration.ofSeconds(2)
            )).isTrue();
            assertThat(runs.get()).isEqualTo(1);
            assertThat(capture.stderrText()).contains(
                    "{\"error\":{\"code\":\"SCHEDULED_TRIGGER_OVERLAPPED\","
                            + "\"message\":\"Scheduled trigger refresh reached "
                            + "maxConcurrentRuns 1; tick was skipped.\"},"
                            + "\"status\":\"schedule-skipped\"}\n"
            );
        } finally {
            release.countDown();
            runtime.stop();
        }
    }

    @Test
    void skippedTicksDoNotBurstAfterTheOccupiedRunCompletes() throws Exception {
        final CountDownLatch firstEntered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();
        final Capture capture = new Capture();
        final ApplicationRuntime runtime = start(
                scheduledFlow(schedule("refresh", 250, 0, 1), input -> {
                    if (runs.incrementAndGet() == 1) {
                        firstEntered.countDown();
                        release.await();
                    }
                    return StepResult.outcome("ok");
                }),
                capture
        );
        try {
            assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(await(
                    () -> occurrences(
                            capture.stderrText(),
                            "\"code\":\"SCHEDULED_TRIGGER_OVERLAPPED\""
                    ) >= 3,
                    Duration.ofSeconds(2)
            )).isTrue();
            release.countDown();
            assertThat(await(() -> runs.get() >= 2, Duration.ofSeconds(2))).isTrue();
            final int shortlyAfterRelease = runs.get();
            TimeUnit.MILLISECONDS.sleep(100);

            assertThat(runs.get()).isEqualTo(shortlyAfterRelease);
        } finally {
            release.countDown();
            runtime.stop();
        }
    }

    @Test
    void configuredConcurrentRunsPermitBoundedOverlap() throws Exception {
        final CountDownLatch entered = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maximum = new AtomicInteger();
        final Capture capture = new Capture();
        final ApplicationRuntime runtime = start(
                scheduledFlow(schedule("refresh", 20, 0, 2), input -> {
                    final int now = active.incrementAndGet();
                    maximum.accumulateAndGet(now, Math::max);
                    entered.countDown();
                    try {
                        release.await();
                        return StepResult.outcome("ok");
                    } finally {
                        active.decrementAndGet();
                    }
                }),
                capture
        );
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(await(
                    () -> capture.stderrText().contains("\"code\":\"SCHEDULED_TRIGGER_OVERLAPPED\""),
                    Duration.ofSeconds(2)
            )).isTrue();
            assertThat(maximum.get()).isEqualTo(2);
        } finally {
            release.countDown();
            runtime.stop();
        }
    }

    @Test
    void fixedRateCanStartTheNextPermittedRunBeforeThePriorRunCompletes() throws Exception {
        final CountDownLatch entered = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        final Capture capture = new Capture();
        final ApplicationRuntime runtime = start(
                scheduledFlow(schedule("refresh", 30, 0, 2), input -> {
                    entered.countDown();
                    release.await();
                    return StepResult.outcome("ok");
                }),
                capture
        );
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            runtime.stop();
        }
    }

    @Test
    void failureIsReportedAndDoesNotCancelLaterTicks() throws Exception {
        final AtomicInteger runs = new AtomicInteger();
        final Capture capture = new Capture();
        final ApplicationRuntime runtime = start(
                scheduledFlow(schedule("refresh", 30, 0, 1), input -> {
                    if (runs.incrementAndGet() == 1) {
                        throw new IllegalStateException("first run fails");
                    }
                    return StepResult.outcome("ok");
                }),
                capture
        );
        try {
            assertThat(await(
                    () -> runs.get() >= 2 && !capture.stdoutText().isEmpty(),
                    Duration.ofSeconds(2)
            )).isTrue();
            assertThat(new FailureContinuation(
                    capture.stderrText().contains("\"code\":\"STEP_IMPLEMENTATION_FAULT\""),
                    capture.stdoutText().contains("\"status\":\"succeeded\"")
            )).isEqualTo(new FailureContinuation(true, true));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void timeSpentBeforeReadinessDoesNotCreateCatchUpRuns() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();
        final Capture capture = new Capture();
        final ApplicationRuntime runtime = ApplicationRuntime.start(
                scheduledFlow(schedule("refresh", 20, 0, 1), input -> {
                    runs.incrementAndGet();
                    release.await();
                    return StepResult.outcome("ok");
                }),
                capture.stdout(),
                capture.stderr()
        );
        try {
            TimeUnit.MILLISECONDS.sleep(120);
            runtime.ready();
            assertThat(await(() -> runs.get() == 1, Duration.ofSeconds(1))).isTrue();
            TimeUnit.MILLISECONDS.sleep(80);

            assertThat(runs.get()).isEqualTo(1);
        } finally {
            release.countDown();
            runtime.stop();
        }
    }

    @Test
    void multipleScheduledDeclarationsOwnIndependentTicks() throws Exception {
        final AtomicInteger runs = new AtomicInteger();
        final Capture capture = new Capture();
        final ApplicationRuntime runtime = start(
                scheduledFlow(
                        schedule("first", 60_000, 0, 1) + ","
                                + schedule("second", 60_000, 0, 1),
                        counted(runs)
                ),
                capture
        );
        try {
            assertThat(await(() -> runs.get() == 2, Duration.ofSeconds(2))).isTrue();
        } finally {
            runtime.stop();
        }
    }

    @Test
    void stopBeforeReadinessPreventsEveryScheduledRun() throws Exception {
        final AtomicInteger runs = new AtomicInteger();
        final Capture capture = new Capture();
        final ApplicationRuntime runtime = ApplicationRuntime.start(
                scheduledFlow(schedule("refresh", 1, 0, 1), counted(runs)),
                capture.stdout(),
                capture.stderr()
        );

        runtime.stop();
        TimeUnit.MILLISECONDS.sleep(40);

        assertThat(runs.get()).isZero();
    }

    @Test
    void stopInterruptsACooperativeScheduledRun() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final Capture capture = new Capture();
        final ApplicationRuntime runtime = start(
                scheduledFlow(schedule("refresh", 60_000, 0, 1), input -> {
                    entered.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (final InterruptedException exception) {
                        interrupted.countDown();
                        throw exception;
                    }
                    return StepResult.outcome("ok");
                }),
                capture
        );
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        runtime.stop();

        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void stopReturnsWhenAnUnsupportedNonCooperativeScheduledStepIgnoresInterruption() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final AtomicBoolean release = new AtomicBoolean();
        final AtomicBoolean observedInterrupt = new AtomicBoolean();
        final Capture capture = new Capture();
        final ApplicationRuntime runtime = start(
                scheduledFlow(schedule("refresh", 1, 0, 1), input -> {
                    entered.countDown();
                    while (!release.get()) {
                        try {
                            TimeUnit.MILLISECONDS.sleep(5);
                        } catch (final InterruptedException exception) {
                            observedInterrupt.set(true);
                        }
                    }
                    return StepResult.outcome("ok");
                }),
                capture
        );
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            final long started = System.nanoTime();

            runtime.stop();

            assertThat(new NonCooperativeStopObservation(
                    Duration.ofNanos(System.nanoTime() - started).toMillis() < 500,
                    await(observedInterrupt::get, Duration.ofSeconds(2))
            )).isEqualTo(new NonCooperativeStopObservation(true, true));
        } finally {
            release.set(true);
        }
    }

    @Test
    void restartResetsTheInitialDelayWithoutReplayingPriorTime() throws Exception {
        final AtomicInteger runs = new AtomicInteger();
        final CompiledFlow flow = scheduledFlow(
                schedule("refresh", 60_000, 120, 1),
                counted(runs)
        );
        final Capture firstCapture = new Capture();
        final ApplicationRuntime first = start(flow, firstCapture);
        TimeUnit.MILLISECONDS.sleep(50);
        first.stop();

        final Capture secondCapture = new Capture();
        final ApplicationRuntime second = start(flow, secondCapture);
        try {
            TimeUnit.MILLISECONDS.sleep(60);
            final int beforeRestartDelay = runs.get();
            final boolean ranAfterRestartDelay = await(() -> runs.get() == 1, Duration.ofSeconds(1));

            assertThat(new InitialDelayObservation(beforeRestartDelay, ranAfterRestartDelay))
                    .isEqualTo(new InitialDelayObservation(0, true));
        } finally {
            second.stop();
        }
    }

    @Test
    void readinessIsIdempotentAndPublishedOnce() throws Exception {
        final Capture capture = new Capture();
        final ApplicationRuntime runtime = ApplicationRuntime.start(
                scheduledFlow(schedule("refresh", 60_000, 60_000, 1), counted(new AtomicInteger())),
                capture.stdout(),
                capture.stderr()
        );
        try {
            assertThat(List.of(runtime.ready(), runtime.ready())).containsOnly(runtime);
            assertThat(capture.stdoutText()).isEqualTo(
                    "{\"scheduledTriggers\":[\"refresh\"],\"status\":\"application-ready\"}\n"
            );
        } finally {
            runtime.stop();
        }
    }

    @Test
    void readinessAfterStopIsRejectedDeterministically() throws Exception {
        final Capture capture = new Capture();
        final ApplicationRuntime runtime = ApplicationRuntime.start(
                scheduledFlow(schedule("refresh", 60_000, 60_000, 1), counted(new AtomicInteger())),
                capture.stdout(),
                capture.stderr()
        );

        runtime.stop();

        assertThatThrownBy(runtime::ready)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Application runtime is not running.");
    }

    @Test
    void awaitStopReturnsTheRuntimeAfterStop() throws Exception {
        final Capture capture = new Capture();
        final ApplicationRuntime runtime = ApplicationRuntime.start(
                scheduledFlow(schedule("refresh", 60_000, 60_000, 1), counted(new AtomicInteger())),
                capture.stdout(),
                capture.stderr()
        );
        final FutureTask<ApplicationRuntime> waiting = new FutureTask<>(runtime::awaitStop);
        Thread.startVirtualThread(waiting);

        assertThat(waiting.isDone()).isFalse();
        runtime.stop();
        assertThat(waiting.get(2, TimeUnit.SECONDS)).isSameAs(runtime);
    }

    @Test
    void stopIsIdempotentForAScheduledOnlyRuntime() throws Exception {
        final Capture capture = new Capture();
        final ApplicationRuntime runtime = start(
                scheduledFlow(schedule("refresh", 60_000, 60_000, 1), counted(new AtomicInteger())),
                capture
        );

        assertThat(List.of(runtime.stop(), runtime.stop())).containsOnly(runtime);
    }

    private static ApplicationRuntime start(
            final CompiledFlow flow,
            final Capture capture
    ) throws IOException {
        return ApplicationRuntime.start(flow, capture.stdout(), capture.stderr()).ready();
    }

    private static StepHandler counted(final AtomicInteger runs) {
        return input -> {
            runs.incrementAndGet();
            return StepResult.outcome("ok");
        };
    }

    private static CompiledFlow scheduledFlow(
            final String triggers,
            final StepHandler handler
    ) {
        final String source = """
                {
                  "id":"scheduled-app",
                  "triggers":[%s],
                  "entry":"action",
                  "inputs":{},
                  "outputs":{},
                  "steps":[{"id":"action","use":"action","config":{},"on":{"ok":"end"}}],
                  "connections":[]
                }
                """.formatted(triggers);
        return ((CompileResult.Compiled) FlowCompiler.compile(source, StepCatalog.of(
                StepDefinition.named("action", "1.0.0")
                        .outcome("ok")
                        .run(handler)
        ))).flow();
    }

    private static String schedule(
            final String id,
            final long intervalMillis,
            final long initialDelayMillis,
            final int maxConcurrentRuns
    ) {
        return """
                {"id":"%s","type":"scheduled","config":{
                  "intervalMillis":%d,
                  "initialDelayMillis":%d,
                  "maxConcurrentRuns":%d
                }}\
                """.formatted(id, intervalMillis, initialDelayMillis, maxConcurrentRuns);
    }

    private static boolean await(
            final BooleanSupplier condition,
            final Duration timeout
    ) throws InterruptedException {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        return condition.getAsBoolean();
    }

    private static int occurrences(final String source, final String value) {
        return source.split(java.util.regex.Pattern.quote(value), -1).length - 1;
    }

    private record ScheduleObservation(int runs, String stdout, String stderr) {
    }

    private record InitialDelayObservation(int beforeDelay, boolean eventuallyRan) {
    }

    private record FailureContinuation(boolean failed, boolean continued) {
    }

    private record NonCooperativeStopObservation(boolean returned, boolean interrupted) {
    }

    private static final class Capture {
        private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        private final PrintStream stdoutStream = new PrintStream(stdout, true, StandardCharsets.UTF_8);
        private final PrintStream stderrStream = new PrintStream(stderr, true, StandardCharsets.UTF_8);

        private PrintStream stdout() {
            return stdoutStream;
        }

        private PrintStream stderr() {
            return stderrStream;
        }

        private String stdoutText() {
            synchronized (stdoutStream) {
                return stdout.toString(StandardCharsets.UTF_8);
            }
        }

        private String stderrText() {
            synchronized (stderrStream) {
                return stderr.toString(StandardCharsets.UTF_8);
            }
        }
    }
}
