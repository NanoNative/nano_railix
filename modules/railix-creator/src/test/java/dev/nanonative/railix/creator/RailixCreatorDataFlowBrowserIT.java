package dev.nanonative.railix.creator;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thirdparty.conformance.MatcherConformanceSteps;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

final class RailixCreatorDataFlowBrowserIT extends RailixCreatorBrowserSupport {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Tag("responsive")
    @Tag("browser-diagnostics")
    @Timeout(120)
    void nestedRoutesCanInsertMapAndRunAValueStepWithoutCountingOtherRoutes(final boolean external) throws Exception {
        if (external) {
            creator.close();
            GeneratedApplicationFixture.installedCatalog(directory, List.of(
                    StepDefinition.named("acme.string", "1")
                            .displayName("External string")
                            .receive("value", ValueShape.STRING)
                            .returns("value", ValueShape.STRING)
                            .implementation(MatcherConformanceSteps.Identity.class)
            ), MatcherConformanceSteps.Identity.class);
            creator = CreatorServer.start(0, directory.resolve("project.json"), directory.resolve("railix-home"));
            page.navigate(creator.baseUri().toString());
        }
        openProject(routedRequests("{\"email\":\"SECOND@EXAMPLE.COM\"}"));
        selectWorldNode("method");
        chooseVisibleExample("http:0");
        clickOverview("[data-add-outcome='post']");
        page.locator("#step-search").fill(external ? "external string" : "lowercase");
        page.locator("[data-add-step='" + (external ? "acme.string" : "text.lowercase") + "']").click();
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        final String inserted = page.locator("#inspector").getAttribute("data-selection");
        choosePath("source", "request", "body", "email");
        waitForText("#build-state", "Built");
        chooseCustomPathFor("target", "body");
        waitForText("#build-state", "Built");

        final String first = external ? "FIRST@EXAMPLE.COM" : "first@example.com";
        final String second = external ? "SECOND@EXAMPLE.COM" : "second@example.com";
        final var firstContext = caseContext(0);
        org.assertj.core.api.Assertions.assertThat(firstContext.values().get("body")).isEqualTo(RailixValue.string(first));
        final var request = (RailixValue.ObjectValue) firstContext.values().get("request");
        org.assertj.core.api.Assertions.assertThat(((RailixValue.ObjectValue) request.values().get("body")).values().get("email"))
                .isEqualTo(RailixValue.string("FIRST@EXAMPLE.COM"));
        org.assertj.core.api.Assertions.assertThat(caseContext(1).values().get("body")).isEqualTo(RailixValue.string(second));
        for (int index = 2; index < 8; index++) {
            org.assertj.core.api.Assertions.assertThat(caseContext(index).values().get("body"))
                    .as("Example %s must not run the Step on the POST route", index).isEqualTo(RailixValue.nullValue());
        }
        page.reload();
        waitForText("#build-state", "Built");
        selectWorldNode(inserted);
        assertThat(page.locator("[data-input-name='source'] .path-button")).containsText("email");
        assertThat(page.locator("[data-input-name='target'] .path-button")).containsText("body");
        chooseVisibleExample("http:1");
        assertThat(page.locator("[data-preview-input-value='value']")).hasText("\"SECOND@EXAMPLE.COM\"");
        assertThat(page.locator("[data-preview-output='value']")).hasText("\"" + second + "\"");
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target", "screenshots"))
                .resolve("routed-value-step-" + external + "-" + page.viewportSize().width + ".png")));
        deleteAcceptedStep();
        waitForText("#build-state", "Built");
        org.assertj.core.api.Assertions.assertThat(caseContext(0).values().get("body")).isEqualTo(RailixValue.nullValue());
        page.locator("#editor-undo").click();
        waitForText("#build-state", "Built");
        org.assertj.core.api.Assertions.assertThat(caseContext(0).values().get("body")).isEqualTo(RailixValue.string(first));
        org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "Mixed CASE", "A\nB\"C"})
    @Tag("browser-diagnostics")
    void emptyWhitespaceAndEscapedStringsRemainUsableOnTheirOwnRoute(final String email) {
        openProject(routedRequests(RailixJson.write(RailixValue.object(Map.of("email", RailixValue.string(email))))));
        selectWorldNode("post-entry");
        chooseVisibleExample("http:0");
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("lowercase");
        page.locator("[data-add-step='text.lowercase']").click();
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        choosePath("source", "request", "body", "email");
        waitForText("#build-state", "Built");
        chooseCustomPathFor("target", "body");
        waitForText("#build-state", "Built");
        org.assertj.core.api.Assertions.assertThat(caseContext(1).values().get("body"))
                .isEqualTo(RailixValue.string(email.toLowerCase(Locale.ROOT)));
        org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"email\":null}", "{\"email\":7}", "{\"email\":[]}"})
    @Tag("browser-diagnostics")
    void typedFieldChoicesStillRejectMissingOrIncompatibleDataAmongActualArrivals(final String secondBody) {
        openProject(routedRequests(secondBody));
        selectWorldNode("post-entry");
        chooseVisibleExample("http:0");
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("lowercase");
        page.locator("[data-add-step='text.lowercase']").click();
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        page.locator("[data-input-name='source'] .path-button").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='request']").click();
        assertThat(page.locator("[data-path-part='method']")).isVisible();
        assertThat(page.locator("[data-path-part='body']")).hasCount(0);
        assertThat(page.locator("#apply-path")).isDisabled();
        org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("browser-diagnostics")
    void aConvergingStepMustConsiderAllOfItsIncomingRoutes() {
        openProject(routedRequests("{\"email\":\"SECOND@EXAMPLE.COM\"}"));
        selectWorldNode("finished");
        chooseVisibleExample("http:0");
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("lowercase");
        page.locator("[data-add-step='text.lowercase']").click();
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        page.locator("[data-input-name='source'] .path-button").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='request']").click();
        assertThat(page.locator("[data-path-part='method']")).isVisible();
        assertThat(page.locator("[data-path-part='body']")).hasCount(0);
        page.locator("[data-path-part='method']").click();
        page.locator("#apply-path").click();
        waitForText("#build-state", "Built");
        chooseCustomPathFor("target", "body");
        waitForText("#build-state", "Built");
        org.assertj.core.api.Assertions.assertThat(caseContext(0).values().get("body")).isEqualTo(RailixValue.string("post"));
        org.assertj.core.api.Assertions.assertThat(caseContext(2).values().get("body")).isEqualTo(RailixValue.string("get"));
        org.assertj.core.api.Assertions.assertThat(caseContext(5).values().get("body")).isEqualTo(RailixValue.string("delete"));
        org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Tag("browser-diagnostics")
    void aRepeatedBranchVisitCannotHideAnEarlierArrivalToAnotherExit(final boolean fieldCreatedOnLastVisit) {
        final String source = """
                {"format":1,"id":"repeated-branch","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"different-exits","payload":[],"context":{"payload":{"left":true,"right":false}}},
                    {"name":"both-matched","payload":[],"context":{"payload":{"left":true,"right":true,"g":"UPPERCASE"}}}]},
                  {"id":"dispatch","use":"railix.branch","inputs":{"selection":{"option":"all","inputs":{}},
                    "routes":[{"outcome":"left","groups":[],"inputs":{}},{"outcome":"right","groups":[],"inputs":{}}]}},
                  {"id":"left","use":"railix.field-manipulation","inputs":{"field":["context","selected"],
                    "value":[{"option":"field","inputs":{"source":["context","payload","left"]}}]}},
                  {"id":"right","use":"railix.field-manipulation","inputs":{"field":["context","selected"],
                    "value":[{"option":"field","inputs":{"source":["context","payload","right"]}}]}},
                  {"id":"gate","use":"railix.branch","inputs":{"routes":[{"outcome":"matched","inputs":{},"groups":[[{
                    "option":"field","inputs":{"field":["context","selected"]},
                    "when":{"transforms":[],"all":[[{"use":"value.equals","inputs":{"expected":true}}]]}
                  }]]}]}}
                ],"links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"dispatch"},
                  {"from":"dispatch.left","to":"left"},{"from":"dispatch.right","to":"right"},
                  {"from":"dispatch.otherwise","to":"end"},{"from":"left.next","to":"gate"},
                  {"from":"right.next","to":"gate"},{"from":"gate.matched","to":"end"},
                  {"from":"gate.otherwise","to":"end"}]}
                """;
        openProject(fieldCreatedOnLastVisit ? source
                .replace("{\"id\":\"gate\"", "{\"id\":\"late\",\"use\":\"railix.field-manipulation\",\"inputs\":{"
                        + "\"field\":[\"context\",\"g\"],\"value\":[{\"option\":\"literal\",\"inputs\":{\"literal\":\"LATE\"}}]}},{\"id\":\"gate\"")
                .replace("{\"from\":\"right.next\",\"to\":\"gate\"}",
                        "{\"from\":\"right.next\",\"to\":\"late\"},{\"from\":\"late.next\",\"to\":\"gate\"}") : source);
        selectWorldNode("gate");
        chooseVisibleExample("command:1");
        clickOverview("[data-add-outcome='matched']");
        page.locator("#step-search").fill("lowercase");
        assertThat(page.locator("#step-options")).hasText("No Step matches.");
        assertThat(page.locator("[data-add-step='text.lowercase']")).hasCount(0);
        org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Tag("browser-diagnostics")
    void aPendingObservationCannotReuseAnotherStepsCompatibleFields(final boolean throughBelt) {
        openProject("""
                {"format":1,"id":"delayed-step-evidence","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{"target":["context","arguments"]},"examples":[
                    {"name":"text","payload":[],"context":{"approved":true}},
                    {"name":"number","payload":[],"context":{"approved":false}}]},
                  {"id":"gate","use":"railix.branch","inputs":{"routes":[{"outcome":"text","inputs":{},"groups":[[{
                    "option":"field","inputs":{"field":["context","approved"]},
                    "when":{"transforms":[],"all":[[{"use":"value.equals","inputs":{"expected":true}}]]}
                  }]]}]}},
                  {"id":"text","use":"railix.field-manipulation","inputs":{"field":["context","payload"],
                    "value":[{"option":"literal","inputs":{"literal":"UPPERCASE"}}]}},
                  {"id":"number","use":"railix.field-manipulation","inputs":{"field":["context","payload"],
                    "value":[{"option":"literal","inputs":{"literal":7}}]}}
                ],"links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"gate"},
                  {"from":"gate.text","to":"text"},{"from":"gate.otherwise","to":"number"},
                  {"from":"text.next","to":"end"},{"from":"number.next","to":"end"}]}
                """);
        selectWorldNode("text");
        chooseVisibleExample("command:0");
        assertThat(page.locator("#preview-source")).hasText("\"UPPERCASE\"");
        final var held = new ArrayList<Route>();
        page.route("**/api/examples/steps/*", held::add);
        try {
            if (throughBelt) {
                page.evaluate("() => void state.world.focus('number', 1)");
                awaitScene();
                final var point = (List<?>) page.evaluate("""
                        () => {
                          const world=state.world,link=world.scene.links.find(item=>item.from==='number' && item.outcome==='next');
                          const stations=new Map(world.scene.nodes.map(node=>[node.id,{node,look:world._appearance(node),box:world._planeBounds(node)}]));
                          const points=world._linkPoints(link,stations);
                          const runs=points.slice(1).map((b,i)=>({a:points[i],b,length:Math.hypot(b[0]-points[i][0],b[1]-points[i][1])}));
                          const {a,b}=runs.sort((a,b)=>b.length-a.length)[0];
                          return world._view((a[0]+b[0])/2,(a[1]+b[1])/2,6*world._camera.scale*link.station_scale);
                        }
                        """);
                final var graph = page.locator("#graph").boundingBox();
                page.mouse().click(graph.x + ((Number) point.get(0)).doubleValue(),
                        graph.y + ((Number) point.get(1)).doubleValue());
                page.locator(".step-picker").waitFor();
            } else {
                selectWorldNode("number");
                chooseVisibleExample("command:1");
                clickOverview("#add-next-step");
            }
            page.locator("#step-search").fill("lowercase");
            assertThat(page.locator("#step-options")).hasText("No Step matches.");
            page.waitForCondition(() -> !held.isEmpty());
        } finally {
            page.unroute("**/api/examples/steps/*");
            held.forEach(Route::resume);
        }
        page.locator("[data-close-picker]").click();
        chooseVisibleExample("command:1");
        openInspectorTab("inspect");
        assertThat(page.locator("#preview-source")).hasText("7");
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("floor");
        page.locator("[data-add-step='number.floor']").click();
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        assertThat(page.locator("[data-input-name='source'] .path-button")).containsText("payload");
        chooseCustomPathFor("target", "result");
        waitForText("#build-state", "Built");
        org.assertj.core.api.Assertions.assertThat(runResult(0)).isEqualTo(RailixValue.nullValue());
        org.assertj.core.api.Assertions.assertThat(runResult(1)).isEqualTo(RailixValue.number(7));
        org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("browser-diagnostics")
    @Timeout(120)
    void anAnyPortCannotAutomaticallyChooseAFieldBeforeOtherExamplesFinish() throws Exception {
        final Path gate = directory.resolve("pending-example");
        creator.close();
        GeneratedApplicationFixture.installedCatalog(directory, List.of(
                StepDefinition.named("test.gate", "1")
                        .input("root", StepDefinition.Input.json(ValueShape.STRING).defaultValue(RailixValue.string(gate.toString())))
                        .input("case", StepDefinition.Input.json(ValueShape.STRING).defaultValue(RailixValue.string("work")))
                        .input("ignore_interrupt", StepDefinition.Input.json(ValueShape.BOOLEAN).defaultValue(RailixValue.bool(false)))
                        .implementation(thirdparty.conformance.ChunkGateStepHandler.class),
                StepDefinition.named("acme.consume", "1").displayName("Consume any")
                        .receive("value", ValueShape.ANY).implementation(MatcherConformanceSteps.Trigger.class)
        ), thirdparty.conformance.ChunkGateStepHandler.class, MatcherConformanceSteps.Trigger.class);
        creator = CreatorServer.start(0, directory.resolve("project.json"), directory.resolve("railix-home"));
        page.navigate(creator.baseUri().toString());
        try {
            openProject("""
                    {"format":1,"id":"pending-field-coverage","nodes":[
                      {"id":"app","use":"railix.app","inputs":{}},
                      {"id":"command","use":"railix.trigger.cli","inputs":{"target":["context","arguments"]},"examples":[
                        {"name":"fast","payload":[],"context":{"slow":false,"payload":"EARLY"}},
                        {"name":"slow","payload":[],"context":{"slow":true}}]},
                      {"id":"route","use":"railix.branch","inputs":{"routes":[{"outcome":"slow","inputs":{},"groups":[[{
                        "option":"field","inputs":{"field":["context","slow"]},
                        "when":{"transforms":[],"all":[[{"use":"value.equals","inputs":{"expected":true}}]]}
                      }]]}]}},
                      {"id":"work","use":"test.gate","inputs":{}},
                      {"id":"probe","use":"railix.field-manipulation","inputs":{"field":["context","result"]}}
                    ],"links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"route"},
                      {"from":"route.slow","to":"work"},{"from":"route.otherwise","to":"probe"},
                      {"from":"work.next","to":"probe"},{"from":"probe.next","to":"end"}]}
                    """);
            chooseVisibleExample("command:0");
            page.locator("#edit-example").click();
            openInspectorTab("inspect");
            assertThat(page.locator("#visible-example option[value='command:0']")).hasAttribute("data-status", "succeeded");
            assertThat(page.locator("#visible-example option[value='command:1']")).hasAttribute("data-status", "running");
            page.locator("#inspector-content [data-navigate-step='route']").click();
            assertThat(page.locator("#inspector")).hasAttribute("data-selection", "route");
            openInspectorTab("inspect");
            page.locator("#inspector-content [data-navigate-step='probe']").click();
            assertThat(page.locator("#inspector")).hasAttribute("data-selection", "probe");
            openInspectorTab("inspect");
            assertThat(page.locator("#preview-source")).hasText("null");
            clickOverview("#add-next-step");
            page.locator("#step-search").fill("consume any");
            page.locator("[data-add-step='acme.consume']").click();
            openInspectorTab("inspect");
            assertThat(page.locator("[data-input-name='source'] .path-button")).containsText("context");
            assertThat(page.locator("[data-input-name='source'] .path-button")).not().containsText("payload");
        } finally {
            Files.createDirectories(gate);
            Files.writeString(gate.resolve("release"), "release");
        }
        waitForText("#build-state", "Built");
        page.locator("#edit-example").click();
        openInspectorTab("inspect");
        assertThat(page.locator(".run-result")).containsText("\"status\": \"succeeded\"");
        chooseVisibleExample("command:1");
        assertThat(page.locator(".run-result")).containsText("\"example\": \"slow\"");
        assertThat(page.locator(".run-result")).containsText("\"status\": \"succeeded\"");
        org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("browser-diagnostics")
    void anUnreachedStepMustNotBorrowCompatibleFieldsFromOtherRoutes() {
        openProject(routedRequests("{\"email\":\"SECOND@EXAMPLE.COM\"}")
                .replace("\"expected\":\"/auth\"", "\"expected\":\"/unreached\""));
        selectWorldNode("http");
        chooseVisibleExample("http:0");
        selectWorldNode("method");
        assertThat(page.locator("#preview-observation [role='img']"))
                .hasAttribute("aria-label", "Not reached by this Example");
        clickOverview("[data-add-outcome='post']");
        page.locator("#step-search").fill("lowercase");
        assertThat(page.locator("#step-options")).hasText("No Step matches.");
        assertThat(page.locator("[data-add-step='text.lowercase']")).hasCount(0);
        org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("browser-diagnostics")
    void anEarlierFailedExampleDoesNotHideFieldsOfHealthyArrivals() {
        openProject(routedRequests("{\"email\":null}", true));
        selectWorldNode("method");
        chooseVisibleExample("http:0");
        clickOverview("[data-add-outcome='post']");
        page.locator("#step-search").fill("lowercase");
        page.locator("[data-add-step='text.lowercase']").click();
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        choosePath("source", "request", "body", "email");
        waitForText("#build-state", "Built");
        chooseCustomPathFor("target", "body");
        waitForText("#build-state", "Built");
        org.assertj.core.api.Assertions.assertThat(caseContext(0).values().get("body"))
                .isEqualTo(RailixValue.string("first@example.com"));
        selectWorldNode("http");
        chooseVisibleExample("http:1");
        openInspectorTab("inspect");
        assertThat(page.locator(".run-result")).containsText("\"status\": \"rejected\"");
        assertThat(page.locator(".run-result")).containsText("RUN_STEP_RECEIVE_INCOMPATIBLE");
        assertThat(page.locator(".run-result")).containsText("\"node\": \"preflight\"");
        assertThat(page.locator("#build-state")).hasText("Running");
        org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("browser-diagnostics")
    void aSharedStepsChoicesFollowTheSelectedIngressWithoutLeakingTheOtherSourcesShape() {
        openProject("""
                {"format":1,"id":"shared-types","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"cli","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"text","payload":[],"context":{"shared":"UPPERCASE"}}]},
                  {"id":"http","use":"railix.trigger.http","inputs":{"port":0},"examples":[
                    {"name":"number","payload":{},"context":{"shared":7}}]},
                  {"id":"shared","use":"railix.field-manipulation","inputs":{"field":["context","shared"]}}
                ],"links":[{"from":"app.start","to":"cli"},{"from":"app.start","to":"http"},
                  {"from":"cli.next","to":"shared"},{"from":"http.next","to":"shared"},
                  {"from":"shared.next","to":"end"}]}
                """);
        selectWorldNode("shared");
        chooseVisibleExample("cli:0");
        assertThat(page.locator("#preview-source")).hasText("\"UPPERCASE\"");
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("lowercase");
        assertThat(page.locator("[data-add-step='text.lowercase']")).isVisible();
        page.locator("[data-close-picker]").click();

        chooseVisibleExample("http:0");
        openInspectorTab("inspect");
        assertThat(page.locator("#preview-source")).hasText("7");
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("floor");
        assertThat(page.locator("[data-add-step='number.floor']")).isVisible();
        page.locator("#step-search").fill("lowercase");
        assertThat(page.locator("#step-options")).hasText("No Step matches.");
        page.locator("[data-close-picker]").click();

        chooseVisibleExample("cli:0");
        openInspectorTab("inspect");
        assertThat(page.locator("#preview-source")).hasText("\"UPPERCASE\"");
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("lowercase");
        page.locator("[data-add-step='text.lowercase']").click();
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        assertThat(page.locator("[data-input-name='source'] .path-button")).containsText("shared");
        assertThat(page.locator("[data-preview-output='value']")).hasText("\"uppercase\"");
        org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("browser-diagnostics")
    void eachBranchExitUsesItsOwnExamplesWhenThePayloadTypesDiffer() {
        openProject("""
                {"format":1,"id":"branch-payload-types","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"text","payload":[],"context":{"payload":{"approved":true,"value":"UPPERCASE"}}},
                    {"name":"number","payload":[],"context":{"payload":{"approved":false,"value":7}}}]},
                  {"id":"gate","use":"railix.branch","inputs":{"routes":[{"outcome":"text","inputs":{},"groups":[[{
                    "option":"field","inputs":{"field":["context","payload","approved"]},
                    "when":{"transforms":[],"all":[[{"use":"value.equals","inputs":{"expected":true}}]]}
                  }]]}]}}
                ],"links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"gate"},
                  {"from":"gate.text","to":"end"},{"from":"gate.otherwise","to":"end"}]}
                """);
        selectWorldNode("gate");
        chooseVisibleExample("command:0");
        clickOverview("[data-add-outcome='otherwise']");
        page.locator("#step-search").fill("floor");
        assertThat(page.locator("[data-add-step='number.floor']")).isVisible();
        page.locator("#step-search").fill("lowercase");
        assertThat(page.locator("#step-options")).hasText("No Step matches.");
        page.locator("[data-close-picker]").click();

        clickOverview("[data-add-outcome='text']");
        page.locator("#step-search").fill("lowercase");
        page.locator("[data-add-step='text.lowercase']").click();
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        assertThat(page.locator("[data-input-name='source'] .path-button")).containsText("value");
        chooseCustomPathFor("target", "result");
        waitForText("#build-state", "Built");
        org.assertj.core.api.Assertions.assertThat(runResult(0)).isEqualTo(RailixValue.string("uppercase"));
        org.assertj.core.api.Assertions.assertThat(runResult(1)).isEqualTo(RailixValue.nullValue());
        selectWorldNode("gate");
        clickOverview("[data-add-outcome='otherwise']");
        page.locator("#step-search").fill("floor");
        page.locator("[data-add-step='number.floor']").click();
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        assertThat(page.locator("[data-input-name='source'] .path-button")).containsText("value");
        chooseCustomPathFor("target", "result");
        waitForText("#build-state", "Built");
        org.assertj.core.api.Assertions.assertThat(runResult(0)).isEqualTo(RailixValue.string("uppercase"));
        org.assertj.core.api.Assertions.assertThat(runResult(1)).isEqualTo(RailixValue.number(7));
        org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("browser-diagnostics")
    @Timeout(120)
    void sixtyFourRequestsKeepNestedRouteEvidenceSeparateDuringInsertionAndReload() {
        final var source = (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(
                routedRequests("{\"email\":\"SECOND@EXAMPLE.COM\"}"))).value();
        final var nodes = new ArrayList<>(((RailixValue.ArrayValue) source.values().get("nodes")).values());
        final var trigger = (RailixValue.ObjectValue) nodes.get(1);
        final var examples = new ArrayList<RailixValue>();
        for (int repetition = 0; repetition < 8; repetition++) {
            for (final var example : ((RailixValue.ArrayValue) trigger.values().get("examples")).values()) {
                final var fields = new LinkedHashMap<>(((RailixValue.ObjectValue) example).values());
                fields.put("name", RailixValue.string(((RailixValue.StringValue) fields.get("name")).value() + "-" + repetition));
                examples.add(RailixValue.object(fields));
            }
        }
        final var triggerFields = new LinkedHashMap<>(trigger.values());
        triggerFields.put("examples", RailixValue.array(examples));
        nodes.set(1, RailixValue.object(triggerFields));
        final var project = new LinkedHashMap<>(source.values());
        project.put("nodes", RailixValue.array(nodes));
        openProject(RailixJson.write(RailixValue.object(project)));
        selectWorldNode("post-entry");
        chooseVisibleExample("http:0");
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("lowercase");
        page.locator("[data-add-step='text.lowercase']").click();
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        final String inserted = page.locator("#inspector").getAttribute("data-selection");
        choosePath("source", "request", "body", "email");
        waitForText("#build-state", "Built");
        chooseCustomPathFor("target", "body");
        waitForText("#build-state", "Built");
        page.reload();
        waitForText("#build-state", "Built");
        selectWorldNode(inserted);
        chooseVisibleExample("http:57");
        assertThat(page.locator("[data-preview-output='value']")).hasText("\"second@example.com\"");
        org.assertj.core.api.Assertions.assertThat(caseContext(0).values().get("body"))
                .isEqualTo(RailixValue.string("first@example.com"));
        org.assertj.core.api.Assertions.assertThat(caseContext(57).values().get("body"))
                .isEqualTo(RailixValue.string("second@example.com"));
        org.assertj.core.api.Assertions.assertThat(caseContext(55).values().get("body")).isEqualTo(RailixValue.nullValue());
        org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sequential", "parallel"})
    @Tag("browser-diagnostics")
    @Timeout(120)
    void aLargeMultiPathApplicationCanInsertAValueStepAfterAContinuationOwner(final String execution) throws Exception {
        openProject(RailixCreatorBranchBrowserIT.project()
                .replace("\"execution\":{\"option\":\"parallel\",\"inputs\":{}}",
                        "\"execution\":{\"option\":\"" + execution + "\",\"inputs\":{}}")
                .replace("{\"name\":\"order\",\"payload\":[]}",
                        "{\"name\":\"order\",\"payload\":[],\"context\":{\"payload\":{\"label\":\"UPPERCASE\"}}}"));
        selectWorldNode("dispatch");
        chooseVisibleExample("command:0");
        clickOverview("[data-add-outcome='stock']");
        page.locator("#step-search").fill("lowercase");
        page.locator("[data-add-step='text.lowercase']").click();
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        assertThat(page.locator("[data-input-name='source'] .path-button")).containsText("label");
        chooseCustomPathFor("target", "result");
        waitForText("#build-state", "Built");
        assertThat(page.locator("[data-preview-output='value']")).hasText("\"uppercase\"");
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target", "screenshots"))
                .resolve("large-branch-insertion-" + execution + ".png")));
        org.assertj.core.api.Assertions.assertThat(runResult(0)).isEqualTo(RailixValue.string("uppercase"));
        org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("browser-diagnostics")
    void changingARouteRebuildsFieldEvidenceWithoutReusingThePreviousSubset() {
        openProject(routedRequests("{\"email\":\"SECOND@EXAMPLE.COM\"}"));
        selectWorldNode("post-entry");
        chooseVisibleExample("http:0");
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("lowercase");
        page.locator("[data-add-step='text.lowercase']").click();
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        final String inserted = page.locator("#inspector").getAttribute("data-selection");
        choosePath("source", "request", "body", "email");
        waitForText("#build-state", "Built");
        chooseCustomPathFor("target", "body");
        waitForText("#build-state", "Built");

        selectWorldNode("area");
        final var expected = visibleControl("[data-input-name='expected'] [data-input-json]");
        expected.fill("\"/shop\"");
        expected.press("Tab");
        waitForText("#build-state", "Built");
        selectWorldNode(inserted);
        chooseVisibleExample("http:4");
        page.locator("[data-input-name='source'] .path-button").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='request']").click();
        assertThat(page.locator("[data-path-part='method']")).isVisible();
        assertThat(page.locator("[data-path-part='body']")).hasCount(0);
        page.locator("#cancel-path").click();
        org.assertj.core.api.Assertions.assertThat(caseContext(0).values().get("body")).isEqualTo(RailixValue.nullValue());
        org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
    }

    RailixValue.ObjectValue caseContext(final int index) {
        selectWorldNode("http");
        chooseVisibleExample("http:" + index);
        final String name = page.locator("#visible-example option:checked").textContent().substring(2);
        openInspectorTab("inspect");
        assertThat(page.locator(".run-result")).containsText("\"example\": \"" + name + "\"");
        assertThat(page.locator(".run-result")).containsText("\"context\"");
        final var result = (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(
                page.locator(".run-result").textContent())).value();
        org.assertj.core.api.Assertions.assertThat(result.values().get("status")).isEqualTo(RailixValue.string("succeeded"));
        return (RailixValue.ObjectValue) result.values().get("context");
    }

    static String routedRequests(final String secondBody) {
        return routedRequests(secondBody, false);
    }

    static String routedRequests(final String secondBody, final boolean preflight) {
        return """
                {"format":1,"id":"routed-http-requests","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"http","use":"railix.trigger.http","inputs":{"port":0,"target":["context","request"]},"examples":[
                    {"name":"auth-post-first","payload":{"method":"POST","path":"/auth","body":{"email":"FIRST@EXAMPLE.COM"}}},
                    {"name":"auth-post-second","payload":{"method":"POST","path":"/auth","body":%s}},
                    {"name":"auth-get-first","payload":{"method":"GET","path":"/auth","body":{"email":"GET@EXAMPLE.COM"}}},
                    {"name":"auth-get-second","payload":{"method":"GET","path":"/auth","body":{"email":"OTHER@EXAMPLE.COM"}}},
                    {"name":"shop-post-number","payload":{"method":"POST","path":"/shop","body":{"email":42}}},
                    {"name":"shop-delete","payload":{"method":"DELETE","path":"/shop","body":{}}},
                    {"name":"shop-get","payload":{"method":"GET","path":"/shop","body":{"email":null}}},
                    {"name":"shop-post-list","payload":{"method":"POST","path":"/shop","body":{"email":[]}}}
                  ]},
                  {"id":"area","use":"railix.branch","inputs":{"routes":[{"outcome":"auth","inputs":{},"groups":[[{
                    "option":"field","inputs":{"field":["context","request","path"]},
                    "when":{"transforms":[],"all":[[{"use":"value.equals","inputs":{"expected":"/auth"}}]]}
                  }]]}]}},
                  {"id":"auth-entry","use":"railix.field-manipulation","inputs":{"field":["context","auth"],
                    "value":[{"option":"literal","inputs":{"literal":true}}],"steps":[]}},
                  %s
                  {"id":"method","use":"railix.branch","inputs":{"routes":[{"outcome":"post","inputs":{},"groups":[[{
                    "option":"field","inputs":{"field":["context","request","method"]},
                    "when":{"transforms":[],"all":[[{"use":"value.equals","inputs":{"expected":"POST"}}]]}
                  }]]}]}},
                  {"id":"post-entry","use":"railix.field-manipulation","inputs":{"field":["context","post"],
                    "value":[{"option":"literal","inputs":{"literal":true}}],"steps":[]}},
                  {"id":"get-entry","use":"railix.field-manipulation","inputs":{"field":["context","get"],
                    "value":[{"option":"literal","inputs":{"literal":true}}],"steps":[]}},
                  {"id":"shop-entry","use":"railix.field-manipulation","inputs":{"field":["context","shop"],
                    "value":[{"option":"literal","inputs":{"literal":true}}],"steps":[]}},
                  {"id":"finished","use":"railix.field-manipulation","inputs":{"field":["context","finished"],
                    "value":[{"option":"literal","inputs":{"literal":true}}],"steps":[]}}
                ],"links":[
                  {"from":"app.start","to":"http"},{"from":"http.next","to":"area"},
                  {"from":"area.auth","to":"auth-entry"},{"from":"area.otherwise","to":"shop-entry"},
                  {"from":"auth-entry.next","to":"%s"},%s{"from":"method.post","to":"post-entry"},
                  {"from":"method.otherwise","to":"get-entry"},{"from":"post-entry.next","to":"finished"},
                  {"from":"get-entry.next","to":"finished"},{"from":"shop-entry.next","to":"finished"},
                  {"from":"finished.next","to":"end"}
                ]}
                """.formatted(secondBody, preflight ? """
                        {"id":"preflight","use":"text.lowercase","inputs":{},
                         "receives":{"value":["context","request","body","email"]},
                         "returns":{"value":["context","request","body","email"]}},
                        """ : "", preflight ? "preflight" : "method",
                        preflight ? "{\"from\":\"preflight.ok\",\"to\":\"method\"}," : "");
    }
}
