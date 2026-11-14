package dev.nanonative.railix.stdlib;

import dev.nanonative.railix.core.step.StepBundle;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepDefinition.Input;
import dev.nanonative.railix.core.runtime.MetricDefinition;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ;
import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ_WRITE;
import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.WRITE;

/**
 * Built-in Steps accepted by the current Creator-first checkpoint.
 */
public final class StandardLibrary {
    private StandardLibrary() {
    }

    /**
     * Exports this provider during its own build using the public bundle writer.
     *
     * @param arguments compiled classes directory and destination provider JAR
     * @throws IllegalArgumentException when exactly two paths are not supplied
     * @throws java.io.IOException when export fails
     */
    public static void main(final String[] arguments) throws java.io.IOException {
        if (arguments.length != 2) throw new IllegalArgumentException("Expected compiled classes and destination bundle paths.");
        StepBundle.write(java.nio.file.Path.of(arguments[0]), java.nio.file.Path.of(arguments[1]),
                StandardLibrary.class, definitions());
    }

    /** Returns the built-in authoring contracts, also used by the isolated configuration validator. */
    public static List<StepDefinition> definitions() {
        final List<StepDefinition> definitions = new ArrayList<>(List.of(
                app(),
                cli(),
                http(),
                httpClient(),
                database(),
                fieldManipulation(),
                branch()
        ));
        definitions.addAll(PrimitiveSteps.definitions());
        return List.copyOf(definitions);
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
                .implementation(StandardStepHandlers.Cli.class);
    }

    private static StepDefinition http() {
        return StepDefinition.named("railix.trigger.http", "2")
                .kind(StepDefinition.Kind.TRIGGER)
                .displayName("HTTP")
                .maximumInstances(1)
                .source("application.http")
                .metric(MetricDefinition.gauge("preferred_port", "Preferred port", "port"))
                .metric(MetricDefinition.gauge("active_port", "Active port", "port"))
                .receive("request", ValueShape.OBJECT)
                .input("host", Input.json(ValueShape.STRING).defaultValue(RailixValue.string("127.0.0.1")))
                .input("port", Input.json(ValueShape.NUMBER).between(RailixValue.number(0), RailixValue.number(65535))
                        .defaultValue(RailixValue.number(8080))
                        .validation(Integer.class, port -> java.util.Optional.empty()))
                .input("next_free_port", Input.json(ValueShape.BOOLEAN).defaultValue(RailixValue.bool(true)))
                .input("target", Input.path(WRITE)
                        .defaultPath("context", "payload", "request"))
                .exampleTarget("target")
                .example("get-root", RailixValue.object(java.util.Map.of(
                        "method", RailixValue.string("GET"),
                        "path", RailixValue.string("/"),
                        "query", RailixValue.string(""),
                        "headers", RailixValue.object(java.util.Map.of()),
                        "body", RailixValue.nullValue()
                )))
                .result("body", ValueShape.ANY, RailixValue.nullValue())
                .result("headers", ValueShape.OBJECT, RailixValue.object(java.util.Map.of()))
                .result("status", ValueShape.NUMBER, RailixValue.number(200))
                .response("body", "body")
                .response("headers", "headers")
                .response("status", "status")
                .implementation(HttpStepHandlers.Trigger.class, "jdk.httpserver");
    }

    private static StepDefinition httpClient() {
        return StepDefinition.named("railix.http.client", "1")
                .displayName("HTTP Client")
                .searchTerms("fetch", "request", "api")
                .receive("body", ValueShape.ANY)
                .input("url", Input.json(ValueShape.STRING))
                .input("method", Input.json(ValueShape.STRING)
                        .defaultValue(RailixValue.string("GET")))
                .input("headers", Input.json(ValueShape.OBJECT)
                        .defaultValue(RailixValue.object(java.util.Map.of())))
                .input("connection", Input.options(Input.option("private"), Input.option("shared"))
                        .defaultOption("private"))
                .returns("response", ValueShape.OBJECT)
                .implementation(HttpStepHandlers.Client.class, "java.net.http");
    }

    private static StepDefinition database() {
        return StepDefinition.named("railix.database", "1")
                .displayName("Database")
                .searchTerms("store", "persist", "collection", "json", "crud", "upsert", "mongodb")
                .primaryOutcome("ok")
                .input("collection", Input.json(ValueShape.STRING)
                        .defaultValue(RailixValue.string("default"))
                        .validation(String.class, value -> DatabaseStepHandlers.validCollectionName(value)
                                ? Optional.empty()
                                : Optional.of("Collection must start and end with a lowercase ASCII letter or digit, "
                                        + "contain 1 to 64 lowercase ASCII letters, digits, '.', '_', or '-', and "
                                        + "avoid OS-reserved device names.")))
                .input("directory", Input.json(ValueShape.STRING)
                        .defaultValue(RailixValue.string(".railix/data"))
                        .validation(String.class, value -> DatabaseStepHandlers.validStorageDirectory(value)
                                ? Optional.empty()
                                : Optional.of("Directory must be a valid non-blank path up to 512 characters without "
                                        + "'..' parent traversal.")))
                .input("key_field", Input.json(ValueShape.STRING)
                        .defaultValue(RailixValue.string("_id"))
                        .validation(String.class, value -> DatabaseStepHandlers.validKeyField(value)
                                ? Optional.empty()
                                : Optional.of("Key field must be a non-blank UTF-8 string up to 128 bytes.")))
                .input("unique_fields", databaseFieldListInput())
                .input("index_fields", databaseFieldListInput())
                .input("operation", Input.options(
                        Input.option("upsert")
                                .input("document", Input.path(READ).defaultPath("context", "payload"))
                                .input("auto_key", Input.json(ValueShape.BOOLEAN)
                                        .defaultValue(RailixValue.bool(true)))
                                .input("fields", databaseFieldListInput()),
                        Input.option("get")
                                .input("key", databaseKeyPathInput())
                                .input("fields", databaseFieldListInput())
                                .input("defaults", databaseDefaultsInput()),
                        Input.option("find")
                                .input("filter", Input.path(READ).defaultPath("context", "payload"))
                                .input("after", Input.path(READ).optional())
                                .input("fields", databaseFieldListInput())
                                .input("defaults", databaseDefaultsInput())
                                .input("limit", Input.json(ValueShape.NUMBER)
                                        .defaultValue(RailixValue.number(DatabaseStepHandlers.DEFAULT_FIND_LIMIT))
                                        .between(RailixValue.number(1),
                                                RailixValue.number(DatabaseStepHandlers.MAX_FIND_LIMIT))
                                        .validation(Integer.class, limit ->
                                                limit >= 1 && limit <= DatabaseStepHandlers.MAX_FIND_LIMIT
                                                        ? Optional.empty()
                                                        : Optional.of("Limit must be between 1 and "
                                                                + DatabaseStepHandlers.MAX_FIND_LIMIT + "."))),
                        Input.option("delete")
                                .input("key", databaseKeyPathInput())
                ).defaultOption("upsert"))
                .input("target", Input.path(WRITE).defaultPath("context", "result"))
                .outcome("not_found")
                .outcome("invalid")
                .implementation(DatabaseStepHandlers.class);
    }

    private static StepDefinition.JsonInput databaseFieldListInput() {
        return Input.json(ValueShape.ARRAY)
                .defaultValue(RailixValue.array(List.of()))
                .validation(RailixValue.ArrayValue.class, DatabaseStepHandlers::fieldListRejection);
    }

    private static StepDefinition.JsonInput databaseDefaultsInput() {
        return Input.json(ValueShape.OBJECT)
                .defaultValue(RailixValue.object(Map.of()))
                .validation(RailixValue.ObjectValue.class, DatabaseStepHandlers::defaultsRejection);
    }

    private static StepDefinition.PathInput databaseKeyPathInput() {
        return Input.path(READ).defaultPath("context", "payload", "_id");
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
                .implementation(StandardStepHandlers.FieldManipulation.class);
    }

    private static StepDefinition branch() {
        return StepDefinition.named("railix.branch", "1")
                .displayName("Branch")
                .searchTerms("choice", "if", "filter", "switch", "split", "route", "async", "sync")
                .primaryOutcome("otherwise")
                .input("routes", Input.matcherGroups(fieldOrLiteral()).withAuthoredOutcomes()
                        .routeInput("own_context", Input.json(ValueShape.BOOLEAN).defaultValue(RailixValue.bool(false))))
                .input("selection", Input.options(Input.option("first"), Input.option("matching"), Input.option("all"))
                        .defaultOption("first"))
                .input("execution", Input.options(Input.option("sequential"), Input.option("parallel"))
                        .defaultOption("sequential"))
                .withContinuations()
                .implementation(StandardStepHandlers.Branch.class);
    }

    private static StepDefinition.Option[] fieldOrLiteral() {
        return new StepDefinition.Option[]{
                Input.option("field")
                        .input("field", Input.path(READ).defaultPath("context", "payload"))
                        .fromOwned("field"),
                Input.option("literal")
                        .input("value", Input.json(ValueShape.ANY).defaultValue(RailixValue.nullValue()))
                        .fromOwned("value")
        };
    }
}
