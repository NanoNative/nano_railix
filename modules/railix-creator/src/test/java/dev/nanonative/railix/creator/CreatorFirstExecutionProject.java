package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueRefinement;
import dev.nanonative.railix.core.value.ValueShape;
import thirdparty.conformance.CreatorFirstExecutionSteps;

import java.util.ArrayList;
import java.util.List;

/** Deterministic project and contracts for Creator-first generated execution conformance. */
final class CreatorFirstExecutionProject {
    private static final List<String> TRIGGERS = List.of(
            "graph", "context", "fallback", "literal", "copy", "containers", "conflict", "command",
            "defaults", "fallible", "canonical-input", "depth-input", "bytes-input", "canonical-output",
            "depth-output", "bytes-output", "unrefined-input", "unrefined-output",
            "valid-refined", "null-result", "undeclared-outcome", "missing-output", "number-output",
            "implementation-exception"
    );

    private CreatorFirstExecutionProject() {
    }

    static List<StepDefinition> definitions() {
        final List<StepDefinition> definitions = new ArrayList<>();
        TRIGGERS.forEach(id -> definitions.add(trigger(id)));
        definitions.add(probe(
                "test.probe.canonical-input",
                ValueShape.ANY,
                ValueRefinement.canonical().withMaxDepth(64),
                ValueShape.ANY,
                ValueRefinement.canonical().withMaxDepth(64)
        ));
        definitions.add(probe(
                "test.probe.depth-input",
                ValueShape.ANY,
                ValueRefinement.canonical().withMaxDepth(1),
                ValueShape.ANY,
                ValueRefinement.canonical().withMaxDepth(1)
        ));
        definitions.add(probe(
                "test.probe.bytes-input",
                ValueShape.STRING,
                ValueRefinement.canonical().withMaxJsonBytes(6),
                ValueShape.STRING,
                ValueRefinement.canonical().withMaxJsonBytes(6)
        ));
        definitions.add(probe(
                "test.probe.canonical-output",
                ValueShape.STRING,
                ValueRefinement.none(),
                ValueShape.ANY,
                ValueRefinement.canonical().withMaxDepth(64)
        ));
        definitions.add(probe(
                "test.probe.depth-output",
                ValueShape.STRING,
                ValueRefinement.none(),
                ValueShape.ANY,
                ValueRefinement.canonical().withMaxDepth(1)
        ));
        definitions.add(probe(
                "test.probe.bytes-output",
                ValueShape.STRING,
                ValueRefinement.none(),
                ValueShape.STRING,
                ValueRefinement.canonical().withMaxJsonBytes(6)
        ));
        definitions.add(probe(
                "test.probe.unrefined",
                ValueShape.ANY,
                ValueRefinement.none(),
                ValueShape.ANY,
                ValueRefinement.none()
        ));
        definitions.add(probe(
                "test.probe.valid-refined",
                ValueShape.ANY,
                ValueRefinement.canonical().withMaxDepth(2),
                ValueShape.ANY,
                ValueRefinement.canonical().withMaxDepth(2)
        ));
        definitions.add(probe(
                "test.probe.fault",
                ValueShape.STRING,
                ValueRefinement.none(),
                ValueShape.STRING,
                ValueRefinement.none()
        ));
        return List.copyOf(definitions);
    }

    static String source() {
        final StringBuilder nodes = new StringBuilder("{\"format\":1,\"id\":\"creator-first-execution\",\"nodes\":[")
                .append("{\"id\":\"app\",\"use\":\"railix.app\",\"inputs\":{}}");
        final StringBuilder links = new StringBuilder("],\"links\":[");

        addProbe(nodes, links, "canonical-output", "test.probe.canonical-output", "canonical-output");

        addTrigger(nodes, links, "graph", "lowercase");
        addNode(nodes, "lowercase", "text.lowercase", "{}",
                ",\"receives\":{\"value\":[\"context\",\"payload\",\"name\"]}"
                        + ",\"returns\":{\"value\":[\"context\",\"payload\",\"name\"]}");
        link(links, "lowercase.ok", "end");

        addTrigger(nodes, links, "context", "normalise-name");
        addManipulation(nodes, "normalise-name", "[\"context\",\"payload\",\"name\"]",
                "[{\"option\":\"current\",\"inputs\":{}}]",
                "[{\"use\":\"text.lowercase\",\"inputs\":{}}]");
        addManipulation(nodes, "return-name", "[\"context\",\"result\"]",
                "[{\"option\":\"field\",\"inputs\":{\"source\":[\"context\",\"payload\",\"name\"]}}]",
                "[]");
        link(links, "normalise-name.next", "return-name");
        link(links, "return-name.next", "end");

        addTrigger(nodes, links, "fallback", "fallback-step");
        addManipulation(nodes, "fallback-step", "[\"context\",\"payload\",\"created\"]",
                "[{\"option\":\"current\",\"inputs\":{}},{\"option\":\"literal\",\"inputs\":{\"literal\":\"fallback\"}}]",
                "[]");
        link(links, "fallback-step.next", "end");

        addTrigger(nodes, links, "literal", "literal-step");
        addManipulation(nodes, "literal-step", "[\"context\",\"payload\",\"name\"]",
                "[{\"option\":\"literal\",\"inputs\":{\"literal\":\"fixed\"}}]", "[]");
        link(links, "literal-step.next", "end");

        addTrigger(nodes, links, "copy", "copy-step");
        addManipulation(nodes, "copy-step", "[\"context\",\"payload\",\"copy\"]",
                "[{\"option\":\"field\",\"inputs\":{\"source\":[\"context\",\"payload\",\"name\"]}}]",
                "[]");
        link(links, "copy-step.next", "end");

        addTrigger(nodes, links, "containers", "containers-step");
        addManipulation(nodes, "containers-step", "[\"context\",\"payload\",\"groups\",0,\"name\"]",
                "[{\"option\":\"literal\",\"inputs\":{\"literal\":\"created\"}}]", "[]");
        link(links, "containers-step.next", "end");

        addTrigger(nodes, links, "conflict", "conflict-step");
        addManipulation(nodes, "conflict-step", "[\"context\",\"payload\",\"parent\",\"child\"]",
                "[{\"option\":\"literal\",\"inputs\":{\"literal\":\"created\"}}]", "[]");
        link(links, "conflict-step.next", "end");

        addTrigger(nodes, links, "command", "runtime-step");
        addManipulation(nodes, "runtime-step", "[\"context\",\"result\"]",
                "[{\"option\":\"field\",\"inputs\":{\"source\":[\"context\",\"runtime\",\"trigger\"]}}]",
                "[]");
        link(links, "runtime-step.next", "end");

        addTerminalTrigger(nodes, links, "defaults");

        addTrigger(nodes, links, "fallible", "convert");
        addManipulation(nodes, "convert", "[\"context\",\"payload\",\"value\"]",
                "[{\"option\":\"current\",\"inputs\":{}}]",
                "[{\"use\":\"text.to-number\",\"inputs\":{}}]");
        addManipulation(nodes, "number-result", "[\"context\",\"result\"]",
                "[{\"option\":\"field\",\"inputs\":{\"source\":[\"context\",\"payload\",\"value\"]}}]",
                "[]");
        link(links, "convert.next", "number-result");
        link(links, "number-result.next", "end");

        addProbe(nodes, links, "canonical-input", "test.probe.canonical-input", "reject-noncanonical-input");
        addProbe(nodes, links, "depth-input", "test.probe.depth-input", "identity");
        addProbe(nodes, links, "bytes-input", "test.probe.bytes-input", "identity");
        addProbe(nodes, links, "depth-output", "test.probe.depth-output", "nested-depth");
        addProbe(nodes, links, "bytes-output", "test.probe.bytes-output", "long-string");
        addProbe(nodes, links, "unrefined-input", "test.probe.unrefined", "echo-mixed");
        addProbe(nodes, links, "unrefined-output", "test.probe.unrefined", "surrogate");
        addProbe(nodes, links, "valid-refined", "test.probe.valid-refined", "identity");
        addProbe(nodes, links, "null-result", "test.probe.fault", "java-null");
        addProbe(nodes, links, "undeclared-outcome", "test.probe.fault", "undeclared-outcome");
        addProbe(nodes, links, "missing-output", "test.probe.fault", "missing-output");
        addProbe(nodes, links, "number-output", "test.probe.fault", "number-output");
        addProbe(nodes, links, "implementation-exception", "test.probe.fault", "exception");

        return nodes.append(links).append("]}").toString();
    }

    static String orderedCliSource() {
        return """
                {"format":1,"id":"ordered-cli","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"arguments","payload":["first","second"]}
                  ]},
                  {"id":"result","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],
                    "value":[{"option":"field","inputs":{"source":["context","payload","arguments"]}}],
                    "steps":[]
                  }}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"result"},
                  {"from":"result.next","to":"end"}
                ]}
                """;
    }

    static String triggerOnlyCliSource() {
        return """
                {"format":1,"id":"trigger-only","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"empty","payload":[]}
                  ]}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"end"}
                ]}
                """;
    }

    private static StepDefinition trigger(final String id) {
        return StepDefinition.named("test.trigger." + id, "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source("test.source." + id)
                .result("result", ValueShape.ANY, RailixValue.nullValue())
                .run(CreatorFirstExecutionSteps.Trigger.class);
    }

    private static StepDefinition probe(
            final String id,
            final ValueShape receiveShape,
            final ValueRefinement receiveRefinement,
            final ValueShape returnShape,
            final ValueRefinement returnRefinement
    ) {
        return StepDefinition.named(id, "1")
                .primaryOutcome("ok")
                .receive("value", receiveShape, receiveRefinement)
                .input("behavior", StepDefinition.Input.json(ValueShape.STRING))
                .returns("value", returnShape, returnRefinement)
                .run(CreatorFirstExecutionSteps.Probe.class);
    }

    private static void addTerminalTrigger(
            final StringBuilder nodes,
            final StringBuilder links,
            final String id
    ) {
        addTrigger(nodes, links, id, "end");
    }

    private static void addTrigger(
            final StringBuilder nodes,
            final StringBuilder links,
            final String id,
            final String target
    ) {
        nodes.append(",{\"id\":\"").append(id).append("\",\"use\":\"test.trigger.")
                .append(id).append("\",\"inputs\":{},\"examples\":[{\"name\":\"example\",\"payload\":{}}]}");
        link(links, "app.start", id);
        link(links, id + ".next", target);
    }

    private static void addProbe(
            final StringBuilder nodes,
            final StringBuilder links,
            final String id,
            final String use,
            final String behavior
    ) {
        addTrigger(nodes, links, id, id + "-step");
        addManipulation(
                nodes,
                id + "-step",
                "[\"context\",\"payload\",\"value\"]",
                "[{\"option\":\"current\",\"inputs\":{}}]",
                "[{\"use\":\"" + use + "\",\"inputs\":{\"behavior\":\"" + behavior + "\"}}]"
        );
        link(links, id + "-step.next", "end");
    }

    private static void addManipulation(
            final StringBuilder nodes,
            final String id,
            final String field,
            final String value,
            final String steps
    ) {
        addNode(nodes, id, "railix.field-manipulation",
                "{\"field\":" + field + ",\"value\":" + value + ",\"steps\":" + steps + "}", "");
    }

    private static void addNode(
            final StringBuilder nodes,
            final String id,
            final String use,
            final String inputs,
            final String suffix
    ) {
        nodes.append(",{\"id\":\"").append(id).append("\",\"use\":\"").append(use)
                .append("\",\"inputs\":").append(inputs).append(suffix).append('}');
    }

    private static void link(final StringBuilder links, final String from, final String to) {
        if (links.length() > "],\"links\":[".length()) {
            links.append(',');
        }
        links.append("{\"from\":\"").append(from).append("\",\"to\":\"").append(to).append("\"}");
    }
}
