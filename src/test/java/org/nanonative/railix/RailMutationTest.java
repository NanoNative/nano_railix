package org.nanonative.railix;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

final class RailMutationTest {

    @Test
    void set_withPathAndValue_shouldWritePayload() {
        final Rail rail = Rail.of()
            .set("user", new HashMap<>())
            .put("user", "email", "first@example.com")
            .set("user", "email", "user@example.com");
        final Result result = rail.fire();

        assertThat(result.payload().asString("user", "email")).isEqualTo("user@example.com");
    }

    @Test
    void set_withSupplier_shouldEvaluatePerFire() {
        final AtomicInteger counter = new AtomicInteger(0);
        final RailDef rail = Rail.of().set(counter::incrementAndGet, "trace_id").seal();

        final Result first = rail.fire();
        final Result second = rail.fire();

        assertThat(first.payload().asInt("trace_id")).isEqualTo(1);
        assertThat(second.payload().asInt("trace_id")).isEqualTo(2);
    }

    @Test
    void putAndAdd_withSupplierVariants_shouldEvaluateAndWritePayload() {
        final AtomicInteger counter = new AtomicInteger();

        final Rail rail = Rail.of()
            .set("meta", new HashMap<>())
            .set("tags", new ArrayList<>())
            .put(() -> "source-" + counter.incrementAndGet(), "meta", "source")
            .add(() -> "tag-" + counter.incrementAndGet(), "tags");
        final Result result = rail.fire();

        assertThat(result.payload().asString("meta", "source")).isEqualTo("source-1");
        assertThat(result.payload().asList(String.class, "tags")).isEqualTo(List.of("tag-2"));
    }

    @Test
    void put_withMapPath_shouldInsertEntry() {
        final Rail rail = Rail.of()
            .set("meta", new HashMap<>())
            .put("meta", "source", "http");
        final Result result = rail.fire();

        assertThat(result.payload().asString("meta", "source")).isEqualTo("http");
    }

    @Test
    void put_withEntryValue_shouldInsertMapEntry() {
        final Rail rail = Rail.of()
            .set("meta", new HashMap<>())
            .put("meta", Map.entry("tenant", "acme"));
        final Result result = rail.fire();

        assertThat(result.payload().asString("meta", "tenant")).isEqualTo("acme");
    }

    @Test
    void add_withListPath_shouldAppendItem() {
        final Rail rail = Rail.of()
            .set("tags", new ArrayList<>(List.of("alpha")))
            .add("tags", "beta");
        final Result result = rail.fire();

        assertThat(result.payload().asList(String.class, "tags")).isEqualTo(List.of("alpha", "beta"));
    }

    @Test
    void add_withMapPath_shouldBehaveLikePutForCurrentTypeMapSemantics() {
        final Rail rail = Rail.of()
            .set("meta", new HashMap<>())
            .add("meta", "source", "http");
        final Result result = rail.fire();

        assertThat(result.payload().asString("meta", "source")).isEqualTo("http");
    }

    @Test
    void defaults_whenPresent_shouldKeepExistingValue() {
        final Rail rail = Rail.of()
            .defaults("lang", "de")
            .defaults("lang", "en");
        final Result result = rail.fire();

        assertThat(result.payload().asString("lang")).isEqualTo("de");
    }

    @Test
    void defaults_withSupplier_shouldEvaluateEagerlyForCurrentSemantics() {
        final AtomicInteger counter = new AtomicInteger();

        final Rail rail = Rail.of()
            .defaults(counter::incrementAndGet, "trace_id")
            .defaults(counter::incrementAndGet, "trace_id");
        final Result result = rail.fire();

        assertThat(result.payload().asInt("trace_id")).isEqualTo(1);
        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    void defaults_withInvalidInput_shouldSkipWithoutFailure() {
        final Rail rail = Rail.of()
            .defaults("only_key")
            .defaults((Object[]) null);
        final Result result = rail.fire();

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.payload()).isEmpty();
    }

    @Test
    void supplierMutations_withNullSupplier_shouldWriteExplicitNullsPredictably() {
        final Rail rail = Rail.of()
            .set("meta", new HashMap<>())
            .set("tags", new ArrayList<>())
            .ctxPut("ctx_meta", new HashMap<>())
            .ctxAdd("ctx_tags", new ArrayList<>())
            .set((java.util.function.Supplier<?>) null, "nullable")
            .put((java.util.function.Supplier<?>) null, "meta", "source")
            .add((java.util.function.Supplier<?>) null, "tags")
            .defaults((java.util.function.Supplier<?>) null, "defaulted")
            .ctxSet((java.util.function.Supplier<?>) null, "ctx_nullable")
            .ctxPut((java.util.function.Supplier<?>) null, "ctx_meta", "source")
            .ctxAdd((java.util.function.Supplier<?>) null, "ctx_tags")
            .ctxDefaults((java.util.function.Supplier<?>) null, "ctx_defaulted");
        final Result result = rail.fire();

        assertThat(result.payload()).containsKeys("nullable", "defaulted");
        assertThat(result.payload().get("nullable")).isNull();
        assertThat(result.payload().asList(Object.class, "tags")).isEmpty();
        assertThat(result.payload().asString("meta", "source")).isNull();
        assertThat(result.ctx()).containsKeys("ctx_nullable", "ctx_defaulted");
        assertThat(result.ctx().get("ctx_nullable")).isNull();
        assertThat(result.ctx().asList(Object.class, "ctx_tags")).isEmpty();
        assertThat(result.ctx().asString("ctx_meta", "source")).isNull();
    }

    @Test
    void remove_whenPresent_shouldDeletePayloadValue() {
        final Rail rail = Rail.of()
            .set("debug", true)
            .remove("debug");
        final Result result = rail.fire();

        assertThat(result.payload().asBoolean("debug")).isNull();
    }

    @Test
    void remove_withNestedMapAndListPaths_shouldDeleteEntries() {
        final Rail sourceRail = Rail.of();
        final Result result = sourceRail.fire(Map.of(
                "user", new HashMap<>(Map.of("email", "user@example.com", "debug", true)),
                "items", new ArrayList<>(List.of("a", "b", "c"))));

        final Rail mutateRail = Rail.of()
            .remove("user", "debug")
            .remove("items", 1);
        final Result mutated = mutateRail.fire(new HashMap<>(result.payload()));

        assertThat(mutated.payload().asBoolean("user", "debug")).isNull();
        assertThat(mutated.payload().asList(String.class, "items")).isEqualTo(List.of("a", "c"));
    }

    @Test
    void remove_withListValue_shouldDeleteMatchingElement() {
        final Rail rail = Rail.of().remove("items", "b");
        final Result result = rail.fire(Map.of("items", new ArrayList<>(List.of("a", "b", "c"))));

        assertThat(result.payload().asList(String.class, "items")).isEqualTo(List.of("a", "c"));
    }

    @Test
    void remove_withNullOrEmptyPath_shouldSkipWithoutFailure() {
        final Rail nullPathRail = Rail.of().remove((Object[]) null);
        final Result nullPathResult = nullPathRail.fire(Map.of("debug", true));

        final Rail emptyPathRail = Rail.of().remove();
        final Result emptyPathResult = emptyPathRail.fire(Map.of("debug", true));

        assertThat(nullPathResult.payload().asBoolean("debug")).isTrue();
        assertThat(emptyPathResult.payload().asBoolean("debug")).isTrue();
    }

    @Test
    void remove_withScalarParentOrMissingElement_shouldSkipWithoutFailure() {
        final Rail rail = Rail.of()
            .remove("email", "debug")
            .remove("items", 99)
            .remove("items", "missing");
        final Result result = rail.fire(Map.of(
            "email", "user@example.com",
            "items", new ArrayList<>(List.of("a", "b"))));

        assertThat(result.payload().asString("email")).isEqualTo("user@example.com");
        assertThat(result.payload().asList(String.class, "items")).isEqualTo(List.of("a", "b"));
    }

    @Test
    void ctxMutations_shouldStayInsideCtx() {
        final Rail rail = Rail.of()
            .ctxSet("trace_id", "trace-1")
            .ctxPut("meta", new HashMap<>())
            .ctxPut("meta", "source", "http")
            .ctxAdd("tags", new ArrayList<>())
            .ctxAdd("tags", "beta")
            .ctxDefaults("lang", "de")
            .ctxDefaults("lang", "en")
            .ctxRemove("meta", "source");
        final Result result = rail.fire();

        assertThat(result.ctx().asString("trace_id")).isEqualTo("trace-1");
        assertThat(result.ctx().asList(String.class, "tags")).isEqualTo(List.of("beta"));
        assertThat(result.ctx().asString("lang")).isEqualTo("de");
        assertThat(result.ctx().asString("meta", "source")).isNull();
        assertThat(result.payload().asString("trace_id")).isNull();
    }

    @Test
    void ctxSupplierMutations_shouldWriteCtxOnly() {
        final AtomicInteger counter = new AtomicInteger();

        final Rail rail = Rail.of()
            .ctxSet(() -> "trace-" + counter.incrementAndGet(), "trace_id")
            .ctxPut("meta", new HashMap<>())
            .ctxPut(() -> "source-" + counter.incrementAndGet(), "meta", "source")
            .ctxAdd("tags", new ArrayList<>())
            .ctxAdd(() -> "tag-" + counter.incrementAndGet(), "tags")
            .ctxDefaults(() -> "tenant-" + counter.incrementAndGet(), "tenant")
            .ctxDefaults(() -> "tenant-" + counter.incrementAndGet(), "tenant");
        final Result result = rail.fire();

        assertThat(result.ctx().asString("trace_id")).isEqualTo("trace-1");
        assertThat(result.ctx().asString("meta", "source")).isEqualTo("source-2");
        assertThat(result.ctx().asList(String.class, "tags")).isEqualTo(List.of("tag-3"));
        assertThat(result.ctx().asString("tenant")).isEqualTo("tenant-4");
        assertThat(result.payload()).isEmpty();
    }
}
