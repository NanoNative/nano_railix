package dev.nanonative.railix.stdlib;

import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepDefinition.Input;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;

import java.util.ArrayList;
import java.util.List;

import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ;
import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ_WRITE;
import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.WRITE;

/**
 * Built-in Steps accepted by the current Creator-first checkpoint.
 */
public final class StandardLibrary {
    private StandardLibrary() {
    }

    public static StepCatalog catalog() {
        final List<StepDefinition> definitions = new ArrayList<>(List.of(
                app(),
                cli(),
                fieldManipulation(),
                filter(),
                choice()
        ));
        definitions.addAll(PrimitiveSteps.definitions());
        return StepCatalog.of(definitions.toArray(StepDefinition[]::new));
    }

    private static StepDefinition app() {
        return StepDefinition.named("railix.app", "1")
                .kind(StepDefinition.Kind.APP)
                .define();
    }

    private static StepDefinition cli() {
        return StepDefinition.named("railix.trigger.cli", "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .displayName("CLI")
                .maximumInstances(1)
                .source("application.arguments")
                .receive("arguments", ValueShape.ARRAY)
                .input("target", Input.path(WRITE)
                        .defaultPath("context", "payload", "arguments"))
                .exampleTarget("target")
                .example("no-arguments", RailixValue.array(List.of()))
                .example("one-argument", RailixValue.array(List.of(
                        RailixValue.string("railix")
                )))
                .example("multiple-arguments", RailixValue.array(List.of(
                        RailixValue.string("hello"),
                        RailixValue.string("railix")
                )))
                .result("result", ValueShape.ANY, RailixValue.nullValue())
                .result("exit_code", ValueShape.NUMBER, RailixValue.number(0))
                .response("output", "result")
                .response("status", "exit_code")
                .run(input -> StepResult.outcome(input.primaryOutcome())
                        .write("target", input.value("arguments")));
    }

    private static StepDefinition fieldManipulation() {
        return StepDefinition.named("railix.field-manipulation", "2")
                .input("field", Input.path(READ_WRITE).defaultPath("context", "payload"))
                .input("value", Input.candidates(
                        Input.option("current").fromParent("field"),
                        Input.option("literal")
                                .input("literal", Input.json(ValueShape.ANY)
                                        .defaultValue(RailixValue.nullValue()))
                                .fromOwned("literal"),
                        Input.option("field")
                                .input("source", Input.path(READ)
                                        .defaultPath("context", "payload"))
                                .fromOwned("source")
                ).defaultCandidate("current"))
                .input("steps", Input.steps(StepDefinition.ValueSource.from("value")))
                .run(input -> input.run("steps").writeWhenPresent("field"));
    }

    private static StepDefinition filter() {
        return StepDefinition.named("railix.filter", "1")
                .displayName("Filter")
                .primaryOutcome("match")
                .input("conditions", Input.candidates(fieldOrLiteral()).defaultCandidate("field"))
                .outcome("otherwise")
                .run(input -> StepResult.outcome(
                        input.optionalValue("conditions").isPresent() ? "match" : "otherwise"
                ));
    }

    private static StepDefinition choice() {
        return StepDefinition.named("railix.choice", "1")
                .displayName("Choice")
                .primaryOutcome("match")
                .input("conditions", Input.matcherGroups(fieldOrLiteral()))
                .outcome("otherwise")
                .run(input -> StepResult.outcome(
                        RailixValue.bool(true).equals(input.value("conditions")) ? "match" : "otherwise"
                ));
    }

    private static StepDefinition.Option[] fieldOrLiteral() {
        return new StepDefinition.Option[]{
                Input.option("field")
                        .input("field", Input.path(READ).defaultPath("context", "payload"))
                        .fromOwned("field"),
                Input.option("literal")
                        .input("value", Input.json(ValueShape.ANY))
                        .fromOwned("value")
        };
    }
}
