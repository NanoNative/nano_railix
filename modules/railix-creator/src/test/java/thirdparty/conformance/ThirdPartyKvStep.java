package thirdparty.conformance;

import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** External third-party persistence Step proving public lifecycle and resource parity. */
public final class ThirdPartyKvStep {
    private final Path file;
    private final Map<String, RailixValue> values = new ConcurrentHashMap<>();

    private ThirdPartyKvStep(final Path file) {
        this.file = file;
    }

    public static StepResult pre(final StepInput configuration, final StepInput context) {
        final Path path = Path.of(configuration.string("file")).toAbsolutePath().normalize();
        context.resource("kv", ThirdPartyKvStep.class, path, () -> open(path), ThirdPartyKvStep::flush);
        return StepResult.outcome(context.primaryOutcome());
    }

    public static StepResult process(final StepInput configuration, final StepInput input) {
        final ThirdPartyKvStep store = input.resource("kv", ThirdPartyKvStep.class);
        final StepInput selected = input.selected("mode");
        final String key = selected.string("key");
        if ("put".equals(input.option("mode"))) {
            final RailixValue value = selected.value("value");
            store.values.put(key, value);
            store.flush();
            return StepResult.outcome("ok").write("target", value);
        }
        final RailixValue found = store.values.get(key);
        return found == null
                ? StepResult.outcome("not_found")
                : StepResult.outcome("ok").write("target", found);
    }

    private static ThirdPartyKvStep open(final Path path) {
        final ThirdPartyKvStep store = new ThirdPartyKvStep(path);
        if (Files.isRegularFile(path)) {
            try {
                final RailixJson.Result parsed = RailixJson.parse(Files.readString(path, StandardCharsets.UTF_8));
                if (parsed instanceof RailixJson.Parsed(RailixValue.ObjectValue obj)) {
                    store.values.putAll(obj.values());
                }
            } catch (final IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        return store;
    }

    private void flush() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, RailixJson.write(RailixValue.object(values)), StandardCharsets.UTF_8);
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
