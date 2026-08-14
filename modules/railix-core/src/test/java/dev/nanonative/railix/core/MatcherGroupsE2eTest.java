package dev.nanonative.railix.core;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;

import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ;
import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

final class MatcherGroupsE2eTest {
    @Test
    void canonicalMatcherGroupsCompileAsOrderedOrGroupsOfAndMatchers() {
        final CompileResult.Compiled compiled = compiled("""
                [[{"option":"literal","inputs":{"literal":"one"},
                   "when":{"transforms":[],"all":[]}}],
                 [{"option":"literal","inputs":{"literal":"two"},
                   "when":{"transforms":[],"all":[]}}]]
                """);

        assertThat(compiled.source()).contains(
                "\"matches\":[[{\"inputs\":{\"literal\":\"one\"},\"option\":\"literal\","
                        + "\"when\":{\"all\":[],\"transforms\":[]}}],[{\"inputs\":{\"literal\":\"two\"},"
                        + "\"option\":\"literal\",\"when\":{\"all\":[],\"transforms\":[]}}]]"
        );
    }

    @Test
    void canonicalMatcherPreservesAnExplicitAdditionalPredicate() {
        final CompileResult.Compiled compiled = compiled("""
                [[{"option":"literal","inputs":{"literal":"railix"},"when":{
                  "transforms":[{"use":"text.length","inputs":{}}],
                  "all":[
                    [{"use":"number.greater-than","inputs":{"than":1}}],
                    [{"use":"number.less-than","inputs":{"than":10}}]
                  ]
                }}]]
                """);

        assertThat(compiled.source()).contains(
                "\"when\":{\"all\":[[{\"inputs\":{\"than\":1},\"use\":\"number.greater-than\"}],"
                        + "[{\"inputs\":{\"than\":10},\"use\":\"number.less-than\"}]],"
                        + "\"transforms\":[{\"inputs\":{},\"use\":\"text.length\"}]}"
        );
    }

    @Test
    void matcherConditionArrayIsRejectedInsteadOfSilentlyMigrated() {
        assertRejected("""
                [[{"option":"literal","inputs":{"literal":"railix"},"when":[
                  {"use":"text.length","inputs":{}},
                  {"use":"number.greater-than","inputs":{"than":1}}
                ]}]]
                """, "PROJECT_MATCHER_CONDITION_OBJECT_REQUIRED", "nodes[2].inputs.matches[0][0].when");
    }

    @Test
    void matcherGroupsMustBeAnOuterArray() {
        assertRejected("{}", "PROJECT_MATCHER_GROUPS_ARRAY_REQUIRED", "nodes[2].inputs.matches");
    }

    @Test
    void matcherGroupMustBeAnArray() {
        assertRejected("[{}]", "PROJECT_MATCHER_GROUP_ARRAY_REQUIRED", "nodes[2].inputs.matches[0]");
    }

    @Test
    void matcherMustBeAnExistingCandidateObjectShape() {
        assertRejected("[[true]]", "PROJECT_MATCHER_OBJECT_REQUIRED", "nodes[2].inputs.matches[0][0]");
    }

    @Test
    void unknownMatcherFieldsAreRejected() {
        assertRejected(
                "[[{\"option\":\"current\",\"inputs\":{},\"unknown\":true}]]",
                "PROJECT_MATCHER_FIELD_UNKNOWN",
                "nodes[2].inputs.matches[0][0].unknown"
        );
    }

    @Test
    void matcherOptionIsRequired() {
        assertRejected(
                "[[{\"inputs\":{}}]]",
                "PROJECT_MATCHER_REQUIRED",
                "nodes[2].inputs.matches[0][0].option"
        );
    }

    @Test
    void matcherGroupCannotBeEmpty() {
        assertRejected("[[]]", "PROJECT_MATCHER_GROUP_EMPTY", "nodes[2].inputs.matches[0]");
    }

    @Test
    void matcherOptionMustBeDeclared() {
        assertRejected(
                "[[{\"option\":\"unknown\",\"inputs\":{}}]]",
                "PROJECT_MATCHER_UNKNOWN",
                "nodes[2].inputs.matches[0][0].option"
        );
    }

    @Test
    void matcherOwnedInputRemainsRequired() {
        assertRejected(
                "[[{\"option\":\"literal\",\"inputs\":{}}]]",
                "PROJECT_INPUT_REQUIRED",
                "nodes[2].inputs.matches[0][0].inputs.literal"
        );
    }

    @Test
    void matcherInputsMustBeAnObject() {
        assertRejected(
                "[[{\"option\":\"current\",\"inputs\":[]}]]",
                "PROJECT_MATCHER_INPUTS_OBJECT_REQUIRED",
                "nodes[2].inputs.matches[0][0].inputs"
        );
    }

    @Test
    void matcherConditionMustBeAnObject() {
        assertRejected(
                "[[{\"option\":\"current\",\"inputs\":{},\"when\":true}]]",
                "PROJECT_MATCHER_CONDITION_OBJECT_REQUIRED",
                "nodes[2].inputs.matches[0][0].when"
        );
    }

    @Test
    void matcherTransformsMustBeAnArray() {
        assertRejected(
                "[[{\"option\":\"current\",\"inputs\":{},\"when\":{\"transforms\":true,\"all\":[["
                        + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":true}}]]}}]]",
                "PROJECT_MATCHER_TRANSFORMS_ARRAY_REQUIRED",
                "nodes[2].inputs.matches[0][0].when.transforms"
        );
    }

    @Test
    void matcherPredicatesMustBeAnArray() {
        assertRejected(
                "[[{\"option\":\"current\",\"inputs\":{},\"when\":{\"transforms\":[],\"all\":true}}]]",
                "PROJECT_MATCHER_PREDICATES_ARRAY_REQUIRED",
                "nodes[2].inputs.matches[0][0].when.all"
        );
    }

    @Test
    void matcherConditionRejectsUnknownFields() {
        assertRejected(
                "[[{\"option\":\"current\",\"inputs\":{},\"when\":{\"transforms\":[],\"all\":[["
                        + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":true}}]],\"extra\":true}}]]",
                "PROJECT_MATCHER_PREDICATE_FIELD_UNKNOWN",
                "nodes[2].inputs.matches[0][0].when.extra"
        );
    }

    @Test
    void matcherTransformsRequireAtLeastOnePredicate() {
        assertRejected(
                "[[{\"option\":\"current\",\"inputs\":{},\"when\":{\"transforms\":["
                        + "{\"use\":\"value.non-boolean\",\"inputs\":{}}],\"all\":[]}}]]",
                "PROJECT_MATCHER_PREDICATE_EMPTY",
                "nodes[2].inputs.matches[0][0].when.all"
        );
    }

    @Test
    void matcherPredicateMustBeAnArray() {
        assertRejected(
                "[[{\"option\":\"current\",\"inputs\":{},\"when\":{\"transforms\":[],\"all\":[true]}}]]",
                "PROJECT_MATCHER_PREDICATE_ARRAY_REQUIRED",
                "nodes[2].inputs.matches[0][0].when.all[0]"
        );
    }

    @Test
    void matcherPredicateCannotBeEmpty() {
        assertRejected(
                "[[{\"option\":\"current\",\"inputs\":{},\"when\":{\"transforms\":[],\"all\":[[]]}}]]",
                "PROJECT_MATCHER_PREDICATE_EMPTY",
                "nodes[2].inputs.matches[0][0].when.all[0]"
        );
    }

    @Test
    void matcherPredicateStepsCannotBranch() {
        assertRejected(
                "[[{\"option\":\"current\",\"inputs\":{},\"when\":{\"transforms\":[],\"all\":[["
                        + "{\"use\":\"value.branching-predicate\",\"inputs\":{}}]]}}]]",
                "PROJECT_MATCHER_PREDICATE_OUTCOME_INVALID",
                "nodes[2].inputs.matches[0][0].when.all[0][0].use"
        );
    }

    @Test
    void matcherTransformStepsCannotBranch() {
        assertRejected(
                "[[{\"option\":\"current\",\"inputs\":{},\"when\":{\"transforms\":["
                        + "{\"use\":\"value.branching-predicate\",\"inputs\":{}}],\"all\":[["
                        + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":true}}]]}}]]",
                "PROJECT_MATCHER_PREDICATE_OUTCOME_INVALID",
                "nodes[2].inputs.matches[0][0].when.transforms[0].use"
        );
    }

    @Test
    void matcherFinalPredicateMustReturnBoolean() {
        assertRejected(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"value\"},\"when\":{"
                        + "\"transforms\":[],\"all\":[[{\"use\":\"value.non-boolean\",\"inputs\":{}}]]}}]]",
                "PROJECT_MATCHER_PREDICATE_RESULT_INVALID",
                "nodes[2].inputs.matches[0][0].when.all[0][0].use"
        );
    }

    @Test
    void matcherAdditionalPredicateMustReturnBoolean() {
        assertRejected(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"value\"},\"when\":{"
                        + "\"transforms\":[],\"all\":[["
                        + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":\"value\"}}],["
                        + "{\"use\":\"value.non-boolean\",\"inputs\":{}}]]}}]]",
                "PROJECT_MATCHER_PREDICATE_RESULT_INVALID",
                "nodes[2].inputs.matches[0][0].when.all[1][0].use"
        );
    }

    @Test
    void incompatibleMatcherSourceIsRejectedBeforeExecution() {
        assertRejected(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"text\"},\"when\":{"
                        + "\"transforms\":[],\"all\":[[{\"use\":\"boolean.not\",\"inputs\":{}}]]}}]]",
                "PROJECT_NESTED_INPUT_INCOMPATIBLE",
                "nodes[2].inputs.matches[0][0].when.all[0][0].use"
        );
    }

    @Test
    void incompatibleMatcherPredicateChainIsRejectedBeforeExecution() {
        assertRejected(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"text\"},\"when\":{"
                        + "\"transforms\":[{\"use\":\"text.length\",\"inputs\":{}}],\"all\":[["
                        + "{\"use\":\"boolean.not\",\"inputs\":{}}]]}}]]",
                "PROJECT_NESTED_INPUT_INCOMPATIBLE",
                "nodes[2].inputs.matches[0][0].when.all[0][0].use"
        );
    }

    @Test
    void incompatibleMatcherTransformSourceIsRejectedBeforeExecution() {
        assertRejected(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"text\"},\"when\":{"
                        + "\"transforms\":[{\"use\":\"boolean.not\",\"inputs\":{}}],\"all\":[["
                        + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":true}}]]}}]]",
                "PROJECT_NESTED_INPUT_INCOMPATIBLE",
                "nodes[2].inputs.matches[0][0].when.transforms[0].use"
        );
    }

    @Test
    void incompatibleAdditionalMatcherPredicateIsRejectedBeforeExecution() {
        assertRejected(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"text\"},\"when\":{"
                        + "\"transforms\":[{\"use\":\"text.length\",\"inputs\":{}}],\"all\":[["
                        + "{\"use\":\"number.greater-than\",\"inputs\":{\"than\":0}}],["
                        + "{\"use\":\"value.text-boolean\",\"inputs\":{}}]]}}]]",
                "PROJECT_NESTED_INPUT_INCOMPATIBLE",
                "nodes[2].inputs.matches[0][0].when.all[1][0].use"
        );
    }

    private static void assertRejected(final String groups, final String code, final String path) {
        final CompileResult result = ProjectCompiler.compileApplication(project(groups), catalog());

        assertThat(result).isInstanceOf(CompileResult.Rejected.class);
        assertThat(((CompileResult.Rejected) result).diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(code);
            assertThat(diagnostic.path()).isEqualTo(path);
        });
    }

    private static CompileResult.Compiled compiled(final String groups) {
        final CompileResult result = ProjectCompiler.compileApplication(project(groups), catalog());
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        return (CompileResult.Compiled) result;
    }

    private static StepCatalog catalog() {
        final StepDefinition app = StepDefinition.named("railix.app", "1")
                .kind(StepDefinition.Kind.APP)
                .define();
        final StepDefinition trigger = StepDefinition.named("test.trigger", "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source("test.source")
                .result("result", ValueShape.ANY, RailixValue.nullValue())
                .run(TestStepHandlers.PrimaryOutcome.class);
        final StepDefinition equals = predicate("value.equals")
                .input("expected", StepDefinition.Input.json(ValueShape.ANY))
                .run(TestStepHandlers.EqualsExpected.class);
        final StepDefinition nonBoolean = StepDefinition.named("value.non-boolean", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.ANY)
                .returns("value", ValueShape.ANY)
                .run(TestStepHandlers.PrimaryIdentity.class);
        final StepDefinition textBoolean = StepDefinition.named("value.text-boolean", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.STRING)
                .returns("value", ValueShape.BOOLEAN)
                .run(TestStepHandlers.True.class);
        final StepDefinition not = StepDefinition.named("boolean.not", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.BOOLEAN)
                .returns("value", ValueShape.BOOLEAN)
                .run(TestStepHandlers.BooleanNot.class);
        final StepDefinition length = StepDefinition.named("text.length", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.STRING)
                .returns("value", ValueShape.NUMBER)
                .run(TestStepHandlers.TextLength.class);
        final StepDefinition greaterThan = StepDefinition.named("number.greater-than", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.NUMBER)
                .returns("value", ValueShape.BOOLEAN)
                .input("than", StepDefinition.Input.json(ValueShape.NUMBER))
                .run(TestStepHandlers.GreaterThan.class);
        final StepDefinition lessThan = StepDefinition.named("number.less-than", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.NUMBER)
                .returns("value", ValueShape.BOOLEAN)
                .input("than", StepDefinition.Input.json(ValueShape.NUMBER))
                .run(TestStepHandlers.LessThan.class);
        final StepDefinition branchingPredicate = predicate("value.branching-predicate")
                .outcome("rejected")
                .run(TestStepHandlers.True.class);
        final StepDefinition fault = predicate("value.fault").run(TestStepHandlers.AlwaysFault.class);
        final StepDefinition interrupt = predicate("value.interrupt")
                .run(TestStepHandlers.InterruptAndTrue.class);
        final StepDefinition invalid = predicate("value.invalid")
                .run(TestStepHandlers.InvalidString.class);
        final StepDefinition probe = StepDefinition.named("test.probe", "1")
                .input("source", StepDefinition.Input.path(READ).defaultPath("context", "payload", "source"))
                .input("matches", StepDefinition.Input.matcherGroups(
                        StepDefinition.Input.option("current").fromParent("source"),
                        StepDefinition.Input.option("literal")
                                .input("literal", StepDefinition.Input.json(ValueShape.ANY))
                                .fromOwned("literal")
                ))
                .input("target", StepDefinition.Input.path(WRITE).defaultPath("context", "payload", "match"))
                .run(TestStepHandlers.WriteMatches.class);
        return StepCatalog.of(
                app,
                trigger,
                equals,
                nonBoolean,
                textBoolean,
                not,
                length,
                greaterThan,
                lessThan,
                branchingPredicate,
                fault,
                interrupt,
                invalid,
                probe
        );
    }

    private static StepDefinition.Builder predicate(final String id) {
        return StepDefinition.named(id, "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.ANY)
                .returns("value", ValueShape.BOOLEAN);
    }

    private static String project(final String groups) {
        return """
                {"format":1,"id":"matcher-groups","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"test.trigger","inputs":{},"examples":[
                    {"name":"example","payload":{},"context":{"payload":{}}}
                  ]},
                  {"id":"probe","use":"test.probe","inputs":{"matches":%s}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"probe"},
                  {"from":"probe.next","to":"end"}
                ]}
                """.formatted(groups);
    }
}
