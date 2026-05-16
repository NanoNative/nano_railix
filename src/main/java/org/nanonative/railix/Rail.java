package org.nanonative.railix;

import berlin.yuna.typemap.model.ConcurrentTypeMap;
import berlin.yuna.typemap.model.TypeMap;
import berlin.yuna.typemap.model.TypeMapI;
import org.nanonative.railix.fn.LoopStep;
import org.nanonative.railix.fn.Step;
import org.nanonative.railix.fn.ThrowingPredicate;
import org.nanonative.railix.metrics.Metrics;
import org.nanonative.railix.name.Names;

import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public class Rail {
    public static final String KEY_CURRENT_STEP_NAME = "_current_step_name";

    private final Object source;
    private final TypeMap railConfig;
    private final List<FlowUnit> units;
    private final boolean concurrent;
    private final boolean sealed;

    private TypeMapI<?> payload;
    private TypeMapI<?> ctxRaw;
    private Result result;
    private boolean firing;
    private Deque<FlowUnit> queue;
    private final ArrayList<FlowUnit> scheduled;

    public Rail() {
        this(null, Map.of(), false);
    }

    public Rail(final boolean concurrent) {
        this(null, Map.of(), concurrent);
    }

    public Rail(final Map<?, ?> railConfig) {
        this(null, railConfig, false);
    }

    public Rail(final Object source, final Map<?, ?> railConfig) {
        this(source, railConfig, false);
    }

    public Rail(final Object source, final Map<?, ?> railConfig, final boolean concurrent) {
        this(source, railConfig == null ? new TypeMap() : new TypeMap(railConfig), new ArrayList<>(), concurrent, false,
            stateMap(concurrent, null), stateMap(concurrent, null), null, false, null, new ArrayList<>());
    }

    private Rail(final Object source, final TypeMap railConfig, final List<FlowUnit> units, final boolean concurrent,
        final boolean sealed, final TypeMapI<?> payload, final TypeMapI<?> ctxRaw, final Result result,
        final boolean firing, final Deque<FlowUnit> queue, final ArrayList<FlowUnit> scheduled) {
        this.source = source;
        this.railConfig = railConfig;
        this.units = units;
        this.concurrent = concurrent;
        this.sealed = sealed;
        this.payload = payload;
        this.ctxRaw = ctxRaw;
        this.result = result;
        this.firing = firing;
        this.queue = queue;
        this.scheduled = scheduled;
    }

    public static Rail of() {
        return new Rail();
    }

    public static Rail of(final boolean concurrent) {
        return new Rail(concurrent);
    }

    public static Rail of(final Object source) {
        return new Rail(source, Map.of());
    }

    public static Rail of(final Map<?, ?> railConfig) {
        return new Rail(railConfig);
    }

    public static Rail of(final Object source, final Map<?, ?> railConfig) {
        return new Rail(source, railConfig);
    }

    public static Rail of(final Consumer<RailConfig.Builder> configurer) {
        final RailConfig.Builder builder = RailConfig.builder();
        if (configurer != null) {
            configurer.accept(builder);
        }
        return of(builder.build());
    }

    public Object source() {
        return source;
    }

    public TypeMapI<?> payload() {
        return payload;
    }

    public TypeMapI<?> ctxMap() {
        return ctxRaw;
    }

    public RailixRuntime runtime() {
        return RailixRuntime.global();
    }

    public ConcurrentTypeMap config() {
        return RailixRuntime.global().config();
    }

    public static ConcurrentTypeMap globalConfig() {
        return RailixRuntime.global().config();
    }

    public TypeMapI<?> railConfig() {
        return railConfig;
    }

    public Executor executor() {
        final Executor override = railConfig.asOpt(Executor.class, RailConfig.KEY_EXECUTOR).orElse(null);
        return override != null ? override : RailixRuntime.global().executor();
    }

    public Metrics metrics() {
        return RailixRuntime.global().metrics();
    }

    public Actors actors() {
        return RailixRuntime.global().actors();
    }

    public boolean isConcurrent() {
        return concurrent;
    }

    public boolean sealed() {
        return sealed;
    }

    public boolean done() {
        return result != null;
    }

    public Optional<Result> resultOpt() {
        return Optional.ofNullable(result);
    }

    public RailDef seal() {
        return new RailDef(sealedBlueprint());
    }

    public Result fire() {
        return fire(null, null);
    }

    public Result fire(final Object payload) {
        return fire(payload, null);
    }

    public Result fire(final Object payload, final Object ctx) {
        final Rail execution = sealedBlueprint().executionCopy(payload, ctx);
        return execution.execute();
    }

    public Rail set(final Object... pathAndValue) {
        return append("set", null, r -> r.payload.setPath(pathAndValue));
    }

    public Rail set(final Supplier<?> value, final Object... path) {
        return append("set", null, r -> r.payload.setPath(mergePathValue(path, value == null ? null : value.get())));
    }

    public Rail put(final Object... pathAndValue) {
        return append("put", null, r -> r.payload.putPath(pathAndValue));
    }

    public Rail put(final Supplier<?> value, final Object... path) {
        return append("put", null, r -> r.payload.putPath(mergePathValue(path, value == null ? null : value.get())));
    }

    public Rail add(final Object... pathAndValue) {
        return append("add", null, r -> r.payload.addPath(pathAndValue));
    }

    public Rail add(final Supplier<?> value, final Object... path) {
        return append("add", null, r -> r.payload.addPath(mergePathValue(path, value == null ? null : value.get())));
    }

    public Rail defaults(final Object... pathAndValue) {
        return append("defaults", null, r -> defaultsPath(r.payload, pathAndValue));
    }

    public Rail defaults(final Supplier<?> value, final Object... path) {
        return append("defaults", null,
            r -> defaultsPath(r.payload, mergePathValue(path, value == null ? null : value.get())));
    }

    public Rail remove(final Object... path) {
        return append("remove", null, r -> removePath(r.payload, path));
    }

    public Rail ctxSet(final Object... pathAndValue) {
        return append("ctx_set", null, r -> r.ctxRaw.setPath(pathAndValue));
    }

    public Rail ctxSet(final Supplier<?> value, final Object... path) {
        return append("ctx_set", null, r -> r.ctxRaw.setPath(mergePathValue(path, value == null ? null : value.get())));
    }

    public Rail ctxPut(final Object... pathAndValue) {
        return append("ctx_put", null, r -> r.ctxRaw.putPath(pathAndValue));
    }

    public Rail ctxPut(final Supplier<?> value, final Object... path) {
        return append("ctx_put", null, r -> r.ctxRaw.putPath(mergePathValue(path, value == null ? null : value.get())));
    }

    public Rail ctxAdd(final Object... pathAndValue) {
        return append("ctx_add", null, r -> r.ctxRaw.addPath(pathAndValue));
    }

    public Rail ctxAdd(final Supplier<?> value, final Object... path) {
        return append("ctx_add", null, r -> r.ctxRaw.addPath(mergePathValue(path, value == null ? null : value.get())));
    }

    public Rail ctxDefaults(final Object... pathAndValue) {
        return append("ctx_defaults", null, r -> defaultsPath(r.ctxRaw, pathAndValue));
    }

    public Rail ctxDefaults(final Supplier<?> value, final Object... path) {
        return append("ctx_defaults", null,
            r -> defaultsPath(r.ctxRaw, mergePathValue(path, value == null ? null : value.get())));
    }

    public Rail ctxRemove(final Object... path) {
        return append("ctx_remove", null, r -> removePath(r.ctxRaw, path));
    }

    public Rail step(final Step<Rail> step) {
        return step(null, step);
    }

    @SafeVarargs
    public final Rail step(final Step<Rail>... steps) {
        Rail target = this;
        if (steps != null) {
            for (final Step<Rail> step : steps) {
                target = target.step(step);
            }
        }
        return target;
    }

    public Rail step(final String name, final Step<Rail> step) {
        if (step == null) {
            return this;
        }
        return append("step", name, step::accept);
    }

    public Rail step(final Rail rail) {
        return step(null, rail);
    }

    public Rail step(final String name, final Rail rail) {
        if (rail == null || rail.units.isEmpty()) {
            return this;
        }
        return append("step", name, r -> r.scheduleIncluded(rail.units));
    }

    public Rail step(final RailDef rail) {
        return step(null, rail);
    }

    public Rail step(final String name, final RailDef rail) {
        return rail == null ? this : step(name, rail.blueprint());
    }

    public Rail verify(final ThrowingPredicate<Rail> condition) {
        return verify(null, condition, "verification_failed", Result.CODE_ABSENT);
    }

    public Rail verify(final String name, final ThrowingPredicate<Rail> condition) {
        return verify(name, condition, "verification_failed", Result.CODE_ABSENT);
    }

    public Rail verify(final ThrowingPredicate<Rail> condition, final String message, final int code) {
        return verify(null, condition, message, code);
    }

    public Rail verify(final String name, final ThrowingPredicate<Rail> condition, final String message, final int code) {
        return append("verify", name, r -> {
            if (condition != null && !condition.test(r)) {
                r.respond(Outcome.ERROR, message == null ? "verification_failed" : message, code, null);
            }
        });
    }

    public Rail map(final UnaryOperator<Object> mapper, final Object... path) {
        return map(null, mapper, path);
    }

    public Rail map(final String name, final UnaryOperator<Object> mapper, final Object... path) {
        return append("map", name, r -> {
            if (mapper == null || path == null || path.length == 0) {
                return;
            }
            final Object value = TypeMap.treeGet(r.payload, path);
            if (value != null) {
                r.payload.setPath(mergePathValue(path, mapper.apply(value)));
            }
        });
    }

    public Rail choose(final ThrowingPredicate<Rail> condition, final Step<Rail> thenStep) {
        return choose(null, condition, thenStep, null);
    }

    public Rail choose(final String name, final ThrowingPredicate<Rail> condition, final Step<Rail> thenStep) {
        return choose(name, condition, thenStep, null);
    }

    public Rail choose(final ThrowingPredicate<Rail> condition, final Step<Rail> thenStep, final Step<Rail> elseStep) {
        return choose(null, condition, thenStep, elseStep);
    }

    public Rail choose(final String name, final ThrowingPredicate<Rail> condition, final Step<Rail> thenStep,
        final Step<Rail> elseStep) {
        return append("choose", name, r -> {
            if (condition == null) {
                return;
            }
            if (condition.test(r)) {
                if (thenStep != null) {
                    thenStep.accept(r);
                }
            } else if (elseStep != null) {
                elseStep.accept(r);
            }
        });
    }

    public Rail each(final LoopStep<Rail> logic, final Object... path) {
        return each(null, logic, path);
    }

    public Rail each(final String name, final LoopStep<Rail> logic, final Object... path) {
        return append("each", name, r -> {
            if (logic == null || path == null) {
                return;
            }
            for (final LoopItem item : loopItems(r.payload, path)) {
                logic.accept(r, item.index(), item.key(), item.value());
            }
        });
    }

    public final Rail parallelEach(final LoopStep<Rail> logic, final Object... path) {
        return parallelEach(null, logic, path);
    }

    public Rail parallelEach(final String name, final LoopStep<Rail> logic, final Object... path) {
        return append("parallel_each", name, r -> executeParallelItems(r, path, logic));
    }

    public <R> Rail reduce(final R identity, final BiFunction<R, Object, R> reducer, final Object... path) {
        return reduce(null, identity, reducer, path);
    }

    public <R> Rail reduce(final String name, final R identity, final BiFunction<R, Object, R> reducer,
        final Object... path) {
        return append("reduce", name, r -> {
            if (path == null || path.length == 0 || reducer == null) {
                return;
            }
            R value = identity;
            for (final LoopItem item : loopItems(r.payload, path)) {
                value = reducer.apply(value, item.value());
            }
            r.payload.setPath(mergePathValue(path, value));
        });
    }

    @SafeVarargs
    public final Rail parallel(final Step<Rail>... steps) {
        return parallel(null, steps);
    }

    @SafeVarargs
    public final Rail parallel(final String name, final Step<Rail>... steps) {
        return append("parallel", name, r -> {
            if (steps == null || steps.length == 0) {
                return;
            }
            final List<CompletableFuture<Rail>> futures = new ArrayList<>();
            for (final Step<Rail> step : steps) {
                if (step == null) {
                    continue;
                }
                futures.add(CompletableFuture.supplyAsync(() -> {
                    final Rail child = r.parallelChild().step(step);
                    child.execute();
                    return child;
                }, r.executor()));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            Result failure = null;
            for (final CompletableFuture<Rail> future : futures) {
                final Rail child = future.getNow(null);
                if (child == null) {
                    continue;
                }
                mergeState(r.payload, child.payload, false);
                mergeState(r.ctxRaw, child.ctxRaw, true);
                failure = selectParallelFailure(failure, child.result);
            }
            if (failure != null) {
                r.respond(failure.outcome(), failure.message(), failure.code(), failure.cause());
            }
        });
    }

    public Rail ok() {
        return ok(null);
    }

    public Rail ok(final String name) {
        return append("ok", name, r -> r.respond(Outcome.SUCCESS, "", Result.CODE_ABSENT, null));
    }

    public Rail fail(final String message, final int code) {
        return fail(null, message, code);
    }

    public Rail fail(final String name, final String message, final int code) {
        return append("fail", name, r -> r.respond(Outcome.ERROR, message, code, null));
    }

    private Rail append(final String kind, final String name, final UnitLogic logic) {
        final Rail target = firing ? this : mutableBuilder();
        final FlowUnit unit = new FlowUnit(kind, unitName(kind, name, target.nextUnitIndex()), logic);
        if (target.firing) {
            target.scheduled.add(unit);
        } else {
            target.units.add(unit);
        }
        return target;
    }

    private Rail mutableBuilder() {
        if (!sealed) {
            return this;
        }
        return new Rail(source, new TypeMap(railConfig), new ArrayList<>(units), concurrent, false, stateMap(concurrent, null),
            stateMap(concurrent, null), null, false, null, new ArrayList<>());
    }

    private Rail sealedBlueprint() {
        if (sealed) {
            return this;
        }
        return new Rail(source, new TypeMap(railConfig), new ArrayList<>(units), concurrent, true, stateMap(concurrent, null),
            stateMap(concurrent, null), null, false, null, new ArrayList<>());
    }

    private int nextUnitIndex() {
        return firing ? units.size() + queue.size() + scheduled.size() + 1 : units.size() + 1;
    }

    private Rail executionCopy(final Object payloadSeed, final Object ctxSeed) {
        return new Rail(source, new TypeMap(railConfig), new ArrayList<>(units), concurrent, true,
            stateMap(concurrent, payloadSeed), stateMap(concurrent, ctxSeed), null, false, new ArrayDeque<>(units),
            new ArrayList<>());
    }

    private Rail parallelChild() {
        return new Rail(source, new TypeMap(railConfig), new ArrayList<>(), concurrent, true,
            stateMap(concurrent, payload), stateMap(concurrent, ctxRaw), null, false, new ArrayDeque<>(), new ArrayList<>());
    }

    private void scheduleIncluded(final List<FlowUnit> defs) {
        if (defs.isEmpty()) {
            return;
        }
        scheduled.addAll(defs);
    }

    private Result execute() {
        if (queue == null) {
            queue = new ArrayDeque<>(units);
        }
        if (firing) {
            return result;
        }
        firing = true;
        try {
            while (!done() && queue != null && !queue.isEmpty()) {
                final FlowUnit unit = queue.pollFirst();
                if (unit == null) {
                    continue;
                }
                scheduled.clear();
                ctxRaw.putR(KEY_CURRENT_STEP_NAME, unit.name());
                try {
                    unit.logic().apply(this);
                } catch (final Exception ex) {
                    respond(Outcome.UNEXPECTED, unit.kind() + "_failed", Result.CODE_ABSENT, ex);
                } finally {
                    prependScheduled();
                }
            }
            if (!done()) {
                respond(Outcome.SUCCESS, "", Result.CODE_ABSENT, null);
            }
            complete();
            return result;
        } finally {
            ctxRaw.remove(KEY_CURRENT_STEP_NAME);
            firing = false;
        }
    }

    private void prependScheduled() {
        if (scheduled.isEmpty() || queue == null) {
            return;
        }
        for (int i = scheduled.size() - 1; i >= 0; i--) {
            queue.addFirst(scheduled.get(i));
        }
        scheduled.clear();
    }

    private void complete() {
        final String name = configValue(RailConfig.KEY_NAME);
        if (metricsEnabled() && name != null && result != null) {
            metrics().incrementCounter("rail_executions_total",
                Map.of("rail", name, "outcome", result.outcome().name().toLowerCase()));
        }
        final Consumer<Result> onComplete = onComplete();
        if (onComplete != null && result != null) {
            try {
                onComplete.accept(result);
            } catch (final Exception ignored) {
                // callback failures must not break result delivery
            }
        }
    }

    private void respond(final Outcome outcome, final String message, final int code, final Throwable cause) {
        if (done()) {
            return;
        }
        result = new Result(outcome, message, code, cause, Result.snapshot(payload), snapshotVisibleCtx(ctxRaw));
    }

    private boolean metricsEnabled() {
        final Object value = firstNonNull(railConfig.asOpt(RailConfig.KEY_METRICS_ENABLED).orElse(null),
            config().asOpt(RailConfig.KEY_METRICS_ENABLED).orElse(null));
        return asBoolean(value, true);
    }

    @SuppressWarnings("unchecked")
    private Consumer<Result> onComplete() {
        final Object value = firstNonNull(railConfig.asOpt(RailConfig.KEY_ON_COMPLETE).orElse(null),
            config().asOpt(RailConfig.KEY_ON_COMPLETE).orElse(null));
        return value instanceof Consumer<?> consumer ? (Consumer<Result>) consumer : null;
    }

    private String configValue(final String key) {
        final Object value = firstNonNull(railConfig.asOpt(key).orElse(null), config().asOpt(key).orElse(null));
        return value == null ? null : String.valueOf(value);
    }

    private static Object firstNonNull(final Object first, final Object second) {
        return first != null ? first : second;
    }

    private static boolean asBoolean(final Object value, final boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof final Boolean bool) {
            return bool;
        }
        if (value instanceof final Number number) {
            return number.intValue() != 0;
        }
        final String raw = String.valueOf(value).trim();
        return raw.isEmpty() ? fallback : "true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw);
    }

    private static TypeMapI<?> stateMap(final boolean concurrent, final Object seed) {
        if (seed instanceof final ConcurrentTypeMap map) {
            return new ConcurrentTypeMap(map);
        }
        if (seed instanceof final TypeMapI<?> map) {
            return concurrent ? new ConcurrentTypeMap((Map<?, ?>) map) : new TypeMap((Map<?, ?>) map);
        }
        if (seed instanceof final Map<?, ?> map) {
            return concurrent ? new ConcurrentTypeMap(map) : new TypeMap(map);
        }
        final TypeMapI<?> result = concurrent ? new ConcurrentTypeMap() : new TypeMap();
        if (seed != null) {
            result.putR("", seed);
        }
        return result;
    }

    private static Object[] mergePathValue(final Object[] path, final Object value) {
        final Object[] result = Arrays.copyOf(path == null ? new Object[0] : path, (path == null ? 0 : path.length) + 1);
        result[result.length - 1] = value;
        return result;
    }

    private static boolean defaultsPath(final TypeMapI<?> map, final Object... pathAndValue) {
        if (map == null || pathAndValue == null || pathAndValue.length < 2) {
            return false;
        }
        final Object[] path = Arrays.copyOf(pathAndValue, pathAndValue.length - 1);
        if (!map.isPresent(path)) {
            return map.setPath(pathAndValue);
        }
        return false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean removePath(final TypeMapI<?> map, final Object... path) {
        if (map == null || path == null || path.length == 0) {
            return false;
        }
        if (path.length == 1) {
            final Object key = path[0];
            final boolean present = map.containsKey(key);
            map.remove(key);
            return present;
        }
        final Object parent = TypeMap.treeGet(map, Arrays.copyOf(path, path.length - 1));
        final Object key = path[path.length - 1];
        if (parent instanceof final Map rawMap) {
            final boolean present = rawMap.containsKey(key);
            rawMap.remove(key);
            return present;
        }
        if (parent instanceof final List list) {
            final int index = key instanceof Number number ? number.intValue() : list.indexOf(key);
            if (index >= 0 && index < list.size()) {
                list.remove(index);
                return true;
            }
        }
        return false;
    }

    private static void mergeState(final TypeMapI<?> target, final TypeMapI<?> source, final boolean skipInternalKeys) {
        if (target == null || source == null) {
            return;
        }
        source.forEach((key, value) -> {
            if (!skipInternalKeys || !(key instanceof String s) || !s.startsWith("_")) {
                target.putR(key, value);
            }
        });
    }

    private static berlin.yuna.typemap.model.LinkedTypeMap snapshotVisibleCtx(final TypeMapI<?> source) {
        final var snapshot = Result.snapshot(source);
        snapshot.entrySet().removeIf(entry -> entry.getKey() instanceof String key && key.startsWith("_"));
        return snapshot;
    }

    private static void executeParallelItems(final Rail rail, final Object[] path, final LoopStep<Rail> logic) {
        if (rail == null || path == null || logic == null) {
            return;
        }
        final List<LoopItem> items = loopItems(rail.payload, path);
        if (items.isEmpty()) {
            return;
        }
        final List<CompletableFuture<Rail>> futures = new ArrayList<>();
        for (final LoopItem item : items) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                Rail child = rail.parallelChild();
                child = child.step(stepRail -> logic.accept(stepRail, item.index(), item.key(), item.value()));
                child.execute();
                return child;
            }, rail.executor()));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        Result failure = null;
        for (final CompletableFuture<Rail> future : futures) {
            final Rail child = future.getNow(null);
            mergeChildState(rail, child);
            failure = selectParallelFailure(failure, child == null ? null : child.result);
        }
        if (failure != null) {
            rail.respond(failure.outcome(), failure.message(), failure.code(), failure.cause());
        }
    }

    private static void mergeChildState(final Rail rail, final Rail child) {
        if (rail == null || child == null) {
            return;
        }
        mergeState(rail.payload, child.payload, false);
        mergeState(rail.ctxRaw, child.ctxRaw, true);
    }

    private static Result selectParallelFailure(final Result current, final Result candidate) {
        if (candidate == null || candidate.outcome() == Outcome.SUCCESS) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        if (current.outcome() == Outcome.UNEXPECTED) {
            return current;
        }
        return candidate.outcome() == Outcome.UNEXPECTED ? candidate : current;
    }

    private static List<LoopItem> loopItems(final TypeMapI<?> payload, final Object... path) {
        return loopItems(TypeMap.treeGet(payload, path));
    }

    private static List<LoopItem> loopItems(final Object target) {
        if (target == null) {
            return List.of();
        }
        final ArrayList<LoopItem> items = new ArrayList<>();
        if (target instanceof final Map<?, ?> map) {
            int index = 0;
            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                items.add(new LoopItem(index++, entry.getKey(), entry.getValue()));
            }
            return items;
        }
        if (target instanceof final Iterable<?> iterable) {
            int index = 0;
            for (final Object value : iterable) {
                items.add(new LoopItem(index++, null, value));
            }
            return items;
        }
        if (target.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(target); index++) {
                items.add(new LoopItem(index, null, Array.get(target, index)));
            }
            return items;
        }
        items.add(new LoopItem(0, null, target));
        return items;
    }

    private static String unitName(final String kind, final String explicitName, final int index) {
        return Names.sanitize(explicitName, kind + "_" + index);
    }

    private record LoopItem(int index, Object key, Object value) {
    }

    private record FlowUnit(String kind, String name, UnitLogic logic) {
    }

    @FunctionalInterface
    private interface UnitLogic {
        void apply(Rail rail) throws Exception;
    }
}
