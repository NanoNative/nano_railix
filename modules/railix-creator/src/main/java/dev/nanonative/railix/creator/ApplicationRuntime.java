package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.flow.CompiledFlow;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one compiled application's long-running ingress lifecycle. */
final class ApplicationRuntime {
    private final AtomicBoolean ready;
    private final List<ApplicationHttpServer> http;
    private final List<ApplicationSocketIngress> sockets;
    private final CompiledFlow flow;
    private final PrintStream stdout;
    private final PrintStream stderr;
    private final List<Schedule> schedules;
    private final List<ScheduledExecutorService> clocks;
    private final Set<Thread> scheduledRuns = new HashSet<>();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private boolean open = true;

    private ApplicationRuntime(
            final AtomicBoolean ready,
            final List<ApplicationHttpServer> http,
            final List<ApplicationSocketIngress> sockets,
            final CompiledFlow flow,
            final PrintStream stdout,
            final PrintStream stderr
    ) {
        this.ready = ready;
        this.http = List.copyOf(http);
        this.sockets = List.copyOf(sockets);
        this.flow = flow;
        this.stdout = stdout;
        this.stderr = stderr;
        schedules = flow.triggers().stream()
                .filter(trigger -> "scheduled".equals(trigger.type()))
                .map(Schedule::new)
                .toList();
        clocks = schedules.isEmpty()
                ? List.of()
                : List.of(Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform()
                                .daemon(true)
                                .name("railix-schedule-clock")
                                .factory()
                ));
    }

    static ApplicationRuntime start(
            final CompiledFlow flow,
            final PrintStream stdout,
            final PrintStream stderr
    ) throws StartFailure {
        if (flow == null) {
            throw new IllegalArgumentException("Compiled flow cannot be Java null.");
        }
        if (stdout == null || stderr == null) {
            throw new IllegalArgumentException("Application output streams cannot be Java null.");
        }
        final AtomicBoolean ready = new AtomicBoolean();
        final List<ApplicationHttpServer> http = new ArrayList<>(1);
        final List<ApplicationSocketIngress> sockets = new ArrayList<>(1);
        try {
            if (flow.triggers().stream().anyMatch(trigger -> "http".equals(trigger.type()))) {
                try {
                    http.add(ApplicationHttpServer.start(flow, ready));
                } catch (final IOException exception) {
                    throw new StartFailure(
                            "HTTP_BIND_FAILED",
                            "Application HTTP listener could not bind.",
                            exception
                    );
                }
            }
            for (final CompiledFlow.Trigger trigger : flow.triggers()) {
                if ("socket".equals(trigger.type())) {
                    try {
                        sockets.add(ApplicationSocketIngress.start(flow, trigger, ready));
                    } catch (final IOException exception) {
                        throw new StartFailure(
                                "SOCKET_BIND_FAILED",
                                "Application socket listener could not bind.",
                                exception
                        );
                    }
                    break;
                }
            }
            return new ApplicationRuntime(ready, http, sockets, flow, stdout, stderr);
        } catch (final RuntimeException | StartFailure exception) {
            sockets.forEach(ApplicationSocketIngress::stop);
            http.forEach(ApplicationHttpServer::stop);
            throw exception;
        }
    }

    synchronized ApplicationRuntime ready() {
        if (!open) {
            throw new IllegalStateException("Application runtime is not running.");
        }
        if (ready.get()) {
            return this;
        }
        if (!schedules.isEmpty()) {
            final ScheduledExecutorService clock = clocks.getFirst();
            for (final Schedule schedule : schedules) {
                clock.scheduleAtFixedRate(
                        () -> tick(schedule),
                        schedule.initialDelayMillis(),
                        schedule.intervalMillis(),
                        TimeUnit.MILLISECONDS
                );
            }
        }
        ready.set(true);
        stdout.println(RailixJson.write(readiness()));
        return this;
    }

    ApplicationRuntime awaitStop() throws InterruptedException {
        stopped.await();
        return this;
    }

    synchronized ApplicationRuntime stop() {
        if (open) {
            open = false;
            ready.set(false);
            clocks.forEach(ScheduledExecutorService::shutdownNow);
            scheduledRuns.forEach(Thread::interrupt);
            sockets.forEach(ApplicationSocketIngress::stop);
            http.forEach(ApplicationHttpServer::stop);
            stopped.countDown();
        }
        return this;
    }

    private synchronized void tick(final Schedule schedule) {
        if (!open || !ready.get()) {
            return;
        }
        if (!schedule.permits().tryAcquire()) {
            stderr.println(RailixJson.write(RailixProtocol.error(
                    "schedule-skipped",
                    "SCHEDULED_TRIGGER_OVERLAPPED",
                    "Scheduled trigger " + schedule.id() + " reached maxConcurrentRuns "
                            + schedule.maxConcurrentRuns() + "; tick was skipped."
            )));
            return;
        }
        final Thread worker = Thread.ofVirtual()
                .name("railix-schedule-" + schedule.id())
                .unstarted(() -> run(schedule));
        scheduledRuns.add(worker);
        try {
            worker.start();
        } catch (final RuntimeException exception) {
            scheduledRuns.remove(worker);
            schedule.permits().release();
            stderr.println(RailixJson.write(RailixProtocol.error(
                    "schedule-rejected",
                    "SCHEDULED_TRIGGER_START_FAILED",
                    "Scheduled trigger " + schedule.id() + " could not start."
            )));
        }
    }

    private void run(final Schedule schedule) {
        final Thread worker = Thread.currentThread();
        try {
            final RailixRunner.Outcome outcome = RailixRunner.run(
                    flow,
                    RailixValue.object(Map.of())
            );
            synchronized (this) {
                if (open) {
                    final String source = RailixJson.write(
                            outcome.payload(),
                            RailixData.DEFAULT_MAX_SOURCE_BYTES
                    ).orElseGet(() -> RailixJson.write(RailixProtocol.error(
                            "schedule-rejected",
                            "SCHEDULED_REPLY_TOO_LARGE",
                            "Scheduled reply exceeds the "
                                    + RailixData.DEFAULT_MAX_SOURCE_BYTES + "-byte limit."
                    )));
                    if (outcome instanceof RailixRunner.Completed) {
                        stdout.println(source);
                    } else {
                        stderr.println(source);
                    }
                }
            }
        } finally {
            synchronized (this) {
                scheduledRuns.remove(worker);
                schedule.permits().release();
            }
        }
    }

    private RailixValue.ObjectValue readiness() {
        final Map<String, RailixValue> fields = new LinkedHashMap<>();
        if (!http.isEmpty()) {
            fields.put("httpUrl", RailixValue.string(http.getFirst().baseUri().toString()));
        }
        if (!sockets.isEmpty()) {
            fields.put(
                    "socketAddress",
                    RailixValue.string("127.0.0.1:" + sockets.getFirst().port())
            );
        }
        if (!schedules.isEmpty()) {
            fields.put("scheduledTriggers", RailixValue.array(schedules.stream()
                    .map(Schedule::id)
                    .map(RailixValue::string)
                    .map(RailixValue.class::cast)
                    .toList()));
        }
        fields.put("status", RailixValue.string("application-ready"));
        return RailixValue.object(fields);
    }

    static final class StartFailure extends IOException {
        private final String code;
        private final String detail;

        private StartFailure(
                final String code,
                final String detail,
                final IOException cause
        ) {
            super(detail, cause);
            this.code = code;
            this.detail = detail;
        }

        String code() {
            return code;
        }

        String detail() {
            return detail;
        }
    }

    private record Schedule(
            String id,
            long intervalMillis,
            long initialDelayMillis,
            int maxConcurrentRuns,
            Semaphore permits
    ) {
        private Schedule(final CompiledFlow.Trigger trigger) {
            this(
                    trigger.id(),
                    number(trigger, "intervalMillis"),
                    number(trigger, "initialDelayMillis"),
                    Math.toIntExact(number(trigger, "maxConcurrentRuns")),
                    new Semaphore(Math.toIntExact(number(trigger, "maxConcurrentRuns")))
            );
        }

        private static long number(final CompiledFlow.Trigger trigger, final String field) {
            return ((RailixValue.NumberValue) trigger.config().values().get(field))
                    .value()
                    .longValueExact();
        }
    }
}
