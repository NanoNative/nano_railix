package dev.nanonative.railix.kernel.runtime;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.Patch;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.Reply;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeRecordsTest {

    @Test
    void shouldCopyContextDiffChangedPaths() {
        final List<RailixPath> changedPaths = new ArrayList<>(List.of(RailixPath.parse("ctx.customer.email")));

        final ContextDoc.ContextDiff contextDiff = new ContextDoc.ContextDiff(changedPaths);

        changedPaths.clear();

        assertThat(contextDiff.changedPaths()).containsExactly(RailixPath.parse("ctx.customer.email"));
        assertThatThrownBy(() -> contextDiff.changedPaths().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldCopyStepResultPatchesAndExposeExecutionInput() {
        final List<Patch> patches = new ArrayList<>(List.of(
                new Patch.Set(
                        RailixPath.parse("ctx.customer.email"),
                        new Patch.LiteralSource(new RailixValue.StringValue("user@example.com"))
                )
        ));
        final ContextDoc context = new ContextDoc() {
            @Override
            public RailixValue get(final RailixPath path) {
                return RailixValue.NULL;
            }

            @Override
            public List<RailixValue> select(final dev.nanonative.railix.kernel.model.Selector selector) {
                return List.of();
            }

            @Override
            public ContextDoc apply(final Patch patch) {
                return this;
            }

            @Override
            public ContextDoc applyAll(final List<Patch> patchesInput) {
                return this;
            }

            @Override
            public ContextDiff diff(final ContextDoc other) {
                return new ContextDiff(List.of());
            }
        };

        final Envelope envelope = new Envelope(
                "manual-ui",
                "manual",
                new RailixValue.ObjectValue(Map.of()),
                new RailixValue.ObjectValue(Map.of()),
                Map.of(),
                new Envelope.ReplyChannel(true, List.of(Reply.Mode.IMMEDIATE))
        );
        final Step.ExecutionInput executionInput = new Step.ExecutionInput(envelope, context);
        final Step.Result result = new Step.Result("ok", patches);

        patches.clear();

        assertThat(executionInput.envelope()).isSameAs(envelope);
        assertThat(executionInput.context()).isSameAs(context);
        assertThat(executionInput.settings().read(RailixPath.parse("settings.missing"))).isEmpty();
        assertThatThrownBy(() -> executionInput.resources().register(
                "temp.file",
                new RailixValue.ObjectValue(Map.of()),
                () -> {
                }
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Resource registration is not available");
        assertThatThrownBy(() -> executionInput.metrics().emit(
                "custom.metric",
                new RailixValue.NumberValue(java.math.BigDecimal.ONE),
                Map.of()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Metric emission is not available");
        assertThatThrownBy(() -> executionInput.audits().emit(
                "custom.audit",
                new RailixValue.ObjectValue(Map.of())
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Audit emission is not available");
        assertThatThrownBy(() -> executionInput.permissions().require("resource.read", "file/demo.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Permission checks are not available");
        assertThat(result.outcome()).isEqualTo("ok");
        assertThat(result.patches()).hasSize(1);
        assertThat(result.reply()).isEqualTo(Reply.none());
        assertThatThrownBy(() -> result.patches().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldExposeThreeArgumentExecutionInputAndValidateRegisteredResources() {
        final ContextDoc context = new ContextDoc() {
            @Override
            public RailixValue get(final RailixPath path) {
                return RailixValue.NULL;
            }

            @Override
            public List<RailixValue> select(final dev.nanonative.railix.kernel.model.Selector selector) {
                return List.of();
            }

            @Override
            public ContextDoc apply(final Patch patch) {
                return this;
            }

            @Override
            public ContextDoc applyAll(final List<Patch> patchesInput) {
                return this;
            }

            @Override
            public ContextDiff diff(final ContextDoc other) {
                return new ContextDiff(List.of());
            }
        };
        final Envelope envelope = new Envelope(
                "manual-ui",
                "manual",
                new RailixValue.ObjectValue(Map.of()),
                new RailixValue.ObjectValue(Map.of()),
                Map.of(),
                new Envelope.ReplyChannel(true, List.of(Reply.Mode.IMMEDIATE))
        );

        final Step.ExecutionInput executionInput = new Step.ExecutionInput(envelope, context, Step.SettingsView.none());

        assertThat(executionInput.resources()).isNotNull();
        assertThat(executionInput.metrics()).isNotNull();
        assertThat(executionInput.permissions()).isNotNull();
        assertThatThrownBy(() -> executionInput.resources().registerMetadata(
                "temp.file",
                new RailixValue.ObjectValue(Map.of())
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Resource registration is not available");
        assertThatThrownBy(() -> new Step.RegisteredResource(
                " ",
                "temp.file",
                new RailixValue.ObjectValue(Map.of())
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refId");
        assertThatThrownBy(() -> new Step.RegisteredResource(
                "ref-1",
                " ",
                new RailixValue.ObjectValue(Map.of())
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refType");
    }
}
