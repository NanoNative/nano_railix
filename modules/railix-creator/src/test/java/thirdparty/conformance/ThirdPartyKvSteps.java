package thirdparty.conformance;

import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepDefinition.Input;
import dev.nanonative.railix.core.value.ValueShape;

import java.util.List;
import java.util.Optional;

import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ;
import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.WRITE;

/** External third-party persistence bundle definitions provider. */
public final class ThirdPartyKvSteps {
    private ThirdPartyKvSteps() {
    }

    public static List<StepDefinition> definitions() {
        return List.of(StepDefinition.named("acme.kv.store", "1")
                .displayName("Acme KV Store")
                .primaryOutcome("ok")
                .input("file", Input.json(ValueShape.STRING)
                        .validation(String.class, value -> value.isBlank()
                                ? Optional.of("Store file path must be non-blank.")
                                : Optional.empty()))
                .input("mode", Input.options(
                        Input.option("put")
                                .input("key", Input.path(READ).defaultPath("context", "payload", "key"))
                                .input("value", Input.path(READ).defaultPath("context", "payload", "value")),
                        Input.option("get")
                                .input("key", Input.path(READ).defaultPath("context", "payload", "key"))
                ).defaultOption("get"))
                .input("target", Input.path(WRITE).defaultPath("context", "result"))
                .outcome("not_found")
                .implementation(ThirdPartyKvStep.class));
    }
}

