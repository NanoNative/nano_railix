package dev.nanonative.railix.creator;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.WaitUntilState;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import thirdparty.conformance.GenericContractSteps;

import static org.assertj.core.api.Assertions.assertThat;

final class RailixCreatorConfigurationBrowserIT extends RailixCreatorBrowserSupport {
    // Only journeys affected by layout, focus or navigation also run at 320px.
    @ParameterizedTest
    @ValueSource(strings = {"same-input", "other-input", "other-node"})
    @Tag("responsive")
    void changingAConfigurationSourceOnlyDiscardsThatInputsJsonDraft(final String scope) throws Exception {
        openProject("""
                {"format":1,"id":"source-drafts","configuration":{"shared":"VALUE"},"nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"empty","payload":[]}]},
                  {"id":"result","use":"railix.field-manipulation","inputs":{"field":["context","result"],
                    "value":[{"option":"literal","inputs":{"literal":"RAILIX"}},
                      {"option":"literal","inputs":{"literal":"SECOND"}}],"steps":[]}},
                  {"id":"other","use":"railix.field-manipulation","inputs":{"field":["context","other"],
                    "value":[{"option":"literal","inputs":{"literal":"THIRD"}}],"steps":[]}}
                ],"links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"result"},
                  {"from":"result.next","to":"other"},{"from":"other.next","to":"end"}]}
                """);
        selectWorldNode("result");
        final String unfinished = "{\"unfinished\":123,";
        page.locator("[data-input-json]").first().fill(unfinished);
        page.locator("[data-input-json]").first().press("Tab");
        assertThat(page.locator("[data-input-json]").first().inputValue()).isEqualTo(unfinished);
        if (scope.equals("other-node")) selectWorldNode("other");
        final var source = page.locator("[data-input-source]").nth(scope.equals("other-input") ? 1 : 0);
        final var response = page.waitForResponse(reply -> reply.url().endsWith("/api/editor")
                        && reply.request().method().equals("POST"), () -> source.selectOption("shared"));
        assertThat(response.status()).as(response.text()).isEqualTo(200);
        page.waitForFunction("() => !state.writeActive && !state.editorController");
        if (scope.equals("same-input")) {
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(source).hasValue("shared");
            assertThat(page.locator(".configuration-reference-value").textContent()).contains("VALUE");
        } else {
            if (scope.equals("other-node")) selectWorldNode("result");
            assertThat(page.locator("[data-input-json]").first().inputValue()).isEqualTo(unfinished);
            assertThat(Files.readString(directory.resolve("project.json"))).contains("\"literal\":\"RAILIX\"");
        }
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    @Tag("browser-diagnostics")
    void configurationIsReadOnlyInTheLiveWorkspaceAndDraftsBlockSwitching() throws Exception {
        creator.close();
        final String source = CreatorConfigurationE2eTest.configuredProject("\"DRAFT\"")
                .replace("],\"links\":[", """
                        ,{"id":"api","use":"railix.trigger.http","inputs":{"port":0},
                          "examples":[{"name":"request","payload":{}}]}],"links":[
                        {"from":"app.start","to":"api"},{"from":"api.next","to":"end"},
                        """);
        final Path project = directory.resolve("project.json"), userHome = directory.resolve("observed-home"),
                home = userHome.resolve(".railix");
        Files.writeString(project, source);
        Files.deleteIfExists(directory.resolve("railix.examples.json"));
        final var bundle = ProjectFiles.build(project, home, true);
        final Path artifact = Files.copy(ApplicationBuilder.buildProduction(project, bundle).jar(), directory.resolve("running.jar"));
        final Process app = LiveApplicationE2eTest.start(artifact, userHome, directory.resolve("live.log"));
        try {
            LiveApplicationE2eTest.await(() -> assertThat(
                    dev.nanonative.railix.core.runtime.ObservationServer.connections(home)).hasSize(1));
            creator = CreatorServer.start(0, project, home);
            page.navigate(creator.baseUri().toString());
            awaitScene();
            page.locator("#project-menu").click();
            page.locator("#open-live-connections").click();
            page.locator("[data-live-instance]").click();
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("body"))
                    .hasAttribute("data-workspace-source", "live");
            openConfiguration();
            final String before = Files.readString(project);
            for (final String id : List.of("configuration-add", "configuration-apply", "configuration-reset"))
                com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#" + id)).isDisabled();
            for (final var field : page.locator("[data-configuration-field], [data-configuration-remove]").all())
                com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(field).isDisabled();
            assertThat(page.locator("[data-configuration-field=value]").inputValue()).isEqualTo("DRAFT");
            page.locator("[aria-label='Close Settings']").click();
            page.locator("button[data-workspace-source='draft']").click();
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("body"))
                    .hasAttribute("data-workspace-source", "draft");
            openConfiguration();
            page.locator("[data-configuration-field=value]").fill("UNSAVED");
            page.locator("[aria-label='Close Settings']").click();
            page.locator("button[data-workspace-source='live']").click();
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#live-message"))
                    .containsText("Finish or discard");
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("body"))
                    .hasAttribute("data-workspace-source", "draft");
            page.locator("[data-close-live]").click();
            openConfiguration();
            assertThat(page.locator("[data-configuration-field=value]").inputValue()).isEqualTo("UNSAVED");
            assertThat(Files.readString(project)).isEqualTo(before);
            assertThat(app.isAlive()).isTrue();
            assertThat(pageErrors).isEmpty();
        } finally {
            ProjectSourceArtifactsE2eTest.stop(app);
        }
    }

    @Test
    @Tag("responsive")
    void branchRouteBooleanDefaultsCanBindConfigurationAndReturnToLiterals() throws Exception {
        openProject(nestedSwitchProject().replace("\"format\":1", "\"format\":1,\"configuration\":{\"isolated\":true}"));
        selectWorldNode("choice");
        final var source = page.getByLabel("Own context source", new Page.GetByLabelOptions().setExact(true));
        source.selectOption("isolated");
        waitForText("#build-state", "Running");
        assertThat(Files.readString(directory.resolve("project.json")))
                .contains("\"input_sources\":{\"own_context\":{\"configuration\":\"isolated\"}}")
                .doesNotContain("\"own_context\":false");
        page.reload();
        waitForText("#build-state", "Running");
        selectWorldNode("choice");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(source).hasValue("isolated");
        source.selectOption("");
        waitForText("#build-state", "Running");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("[data-input-name='own_context'] input[type=checkbox]"))
                .isChecked();
        assertThat(Files.readString(directory.resolve("project.json"))).contains("\"own_context\":true")
                .doesNotContain("input_sources");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void configurationEditingWaitsForAProjectAndRecoversWithoutLosingExistingKeys() throws Exception {
        openProject(CreatorConfigurationE2eTest.literalProject().replace(
                "\"format\":1", "\"format\":1,\"configuration\":{\"kept\":\"KEPT\"}"));
        creator.close();
        creator = CreatorServer.start(0, directory.resolve("project.json"), directory.resolve("railix-home"));
        assertThat(CreatorServerE2eSupport.object(CreatorServerE2eSupport.request(
                creator.baseUri(), "GET", "/api/editor", "").body()).values().get("revision"))
                .isEqualTo(RailixValue.number(0));
        final String before = Files.readString(directory.resolve("project.json"));
        final var held = new ArrayList<Route>();
        final var first = new AtomicBoolean(true);
        page.route("**/api/editor", route -> {
            if (route.request().method().equals("GET") && first.compareAndSet(true, false)) held.add(route);
            else route.resume();
        });
        try {
            page.navigate(creator.baseUri().toString(),
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.waitForCondition(() -> !held.isEmpty());
            openConfiguration();
            for (final String id : List.of("configuration-add", "configuration-apply", "configuration-reset")) {
                com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#" + id)).isDisabled();
                page.locator("#" + id).dispatchEvent("click");
            }
            assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(before);
            assertThat(pageErrors).isEmpty();
            held.removeFirst().fulfill(new Route.FulfillOptions().setStatus(503)
                    .setContentType("application/json").setBody("{}"));
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#creator-startup")).isVisible();
            for (final String id : List.of("configuration-add", "configuration-apply", "configuration-reset"))
                com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#" + id)).isDisabled();
            page.locator("[aria-label='Close Settings']").click();
            page.locator("#creator-retry").click();
            waitForText("#build-state", "Running");
            openConfiguration();
            for (final String id : List.of("configuration-add", "configuration-apply", "configuration-reset"))
                com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#" + id)).isEnabled();
            assertThat(page.locator("[data-configuration-field=key]").inputValue()).isEqualTo("kept");
            addValue("added", "string", "ADDED");
            page.locator("#configuration-apply").click();
            waitForText("#build-state", "Running");
            assertThat(savedConfiguration().values()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "kept", RailixValue.string("KEPT"), "added", RailixValue.string("ADDED")));
            assertThat(pageErrors).isEmpty();
        } finally {
            page.unroute("**/api/editor");
            held.forEach(Route::abort);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"options", "candidates", "explicit-options", "explicit-candidates"})
    void configurationSourcesMaterializeOnlyTheEditedDefaultScope(final String kind, final TestInfo info) throws Exception {
        final boolean candidates = kind.endsWith("candidates");
        final var literal = StepDefinition.Input.option("literal")
                .input("value", StepDefinition.Input.json(ValueShape.STRING).defaultValue(RailixValue.string("DEFAULT")))
                .input("untouched", StepDefinition.Input.json(ValueShape.STRING).defaultValue(RailixValue.string("SIBLING")))
                .fromOwned("value");
        final var definition = candidates
                ? StepDefinition.named("thirdparty.defaults", "1")
                    .input("field", StepDefinition.Input.path(StepDefinition.PathAccess.WRITE).defaultPath("context", "result"))
                    .input("value", StepDefinition.Input.candidates(literal).defaultCandidate("literal"))
                    .input("operations", StepDefinition.Input.steps(StepDefinition.ValueSource.from("value").onMissing("missing")))
                    .implementation(GenericContractSteps.Operation.class)
                : StepDefinition.named("thirdparty.defaults", "1")
                    .input("target", StepDefinition.Input.path(StepDefinition.PathAccess.WRITE).defaultPath("context", "result"))
                    .input("choice", StepDefinition.Input.options(literal).defaultOption("literal"))
                    .implementation(GenericContractSteps.Option.class);
        closeCreator();
        directory = Files.createDirectory(directory.resolve("installed-defaults"));
        GeneratedApplicationFixture.installedCatalog(directory, List.of(definition), GenericContractSteps.class,
                GenericContractSteps.Operation.class, GenericContractSteps.Option.class);
        openCreator(info);
        final String explicit = kind.startsWith("explicit-")
                ? candidates ? ",\"value\":[{\"option\":\"literal\",\"inputs\":{}}]"
                    : "\"choice\":{\"option\":\"literal\",\"inputs\":{}}"
                : "";
        openProject("""
                {"format":1,"id":"default-sources","configuration":{"configured":"CONFIG"},"nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"empty","payload":[]}]},
                  {"id":"external","use":"thirdparty.defaults","inputs":{%s%s}}],
                 "links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"external"},
                   {"from":"external.next","to":"end"}%s]}
                """.formatted(candidates ? "\"operations\":[]" : "", explicit,
                candidates ? ",{\"from\":\"external.missing\",\"to\":\"end\"}" : ""));
        selectWorldNode("external");
        page.getByLabel("Value source", new Page.GetByLabelOptions().setExact(true)).selectOption("configured");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator(".configuration-reference-value"))
                .containsText("CONFIG");
        waitForText("#build-state", "Running");
        final String configured = Files.readString(directory.resolve("project.json"));
        assertThat(configured).contains("\"input_sources\":{\"value\":{\"configuration\":\"configured\"}}")
                .doesNotContain("untouched", "SIBLING", "DEFAULT", "\"target\":", "\"field\":");
        chooseVisibleExample("command:0");
        openInspectorTab("overview");
        page.waitForFunction("() => document.querySelector('#dock-observation')?.textContent.includes('CONFIG')");
        page.reload();
        waitForText("#build-state", "Running");
        selectWorldNode("external");
        assertThat(page.getByLabel("Value source", new Page.GetByLabelOptions().setExact(true)).inputValue()).isEqualTo("configured");
        page.getByLabel("Value source", new Page.GetByLabelOptions().setExact(true)).selectOption("");
        waitForText("#build-state", "Running");
        assertThat(Files.readString(directory.resolve("project.json"))).contains("\"value\":\"CONFIG\"")
                .doesNotContain("input_sources", "untouched", "SIBLING", "\"target\":", "\"field\":");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"typing", "focused", "focused-apply", "queued", "reversal", "reset", "queued-rejection"})
    void newerConfigurationEditsSurviveTheirOwnSaveAcknowledgments(final String scenario) throws Exception {
        openProject(CreatorConfigurationE2eTest.configuredProject("\"A\""));
        openConfiguration();
        final var held = holdNextConfigurationSave();
        try {
            if (scenario.equals("focused-apply")) addValue("kept", "number", "9007199254740993");
            page.locator("[data-configuration-field=value]").first().fill("B");
            if (scenario.equals("focused-apply")) page.locator("#configuration-apply").dispatchEvent("click");
            else page.locator("#configuration-apply").click();
            page.waitForCondition(() -> !held.isEmpty());
            assertThat(held.getFirst().response().status()).isEqualTo(200);
            assertThat(savedConfiguration().values()).containsEntry("value", RailixValue.string("B"));
            final boolean queued = List.of("queued", "reversal", "reset", "queued-rejection").contains(scenario);
            final String expected = queued ? "D" : "C";
            if (scenario.equals("focused")) page.locator("[data-configuration-field=value]").first().focus();
            else if (scenario.equals("reset")) page.locator("#configuration-reset").click();
            else if (scenario.equals("queued-rejection")) {
                page.locator("[data-configuration-remove]").click();
                page.locator("#configuration-apply").click();
            }
            else {
                page.locator("[data-configuration-field=value]").first().fill(scenario.equals("reversal") ? "A" : "C");
                if (queued) page.locator("#configuration-apply").click();
            }
            if (queued) {
                page.waitForFunction("() => Boolean(state.pendingWrite)");
                if (scenario.equals("queued-rejection")) addValue("value", "string", expected);
                else page.locator("[data-configuration-field=value]").first().fill(expected);
            }
            page.locator("[data-configuration-field=value]").first().evaluate("field => window.configurationFieldBeforeAck = field");
            releaseConfigurationSave(held);
            page.waitForFunction("() => !state.writeActive && !state.pendingWrite");
            assertThat(page.locator("[data-configuration-field=value]").first()
                    .evaluate("field => field === window.configurationFieldBeforeAck")).isEqualTo(true);
            if (scenario.equals("focused")) page.locator("[data-configuration-field=value]").first().fill(expected);
            assertThat(page.locator("[data-configuration-field=value]").first().inputValue()).isEqualTo(expected);
            if (queued) assertThat(savedConfiguration().values()).containsEntry("value",
                    RailixValue.string(scenario.equals("queued") ? "C" : scenario.equals("queued-rejection") ? "B" : "A"));
            page.locator("#configuration-apply").click();
            assertThat(page.locator("#configuration-status").textContent()).doesNotContain("project changed");
            waitForText("#build-state", "Running");
            assertThat(savedConfiguration().values()).containsEntry("value", RailixValue.string(expected));
            if (scenario.equals("focused-apply")) assertThat(savedConfiguration().values())
                    .containsEntry("kept", RailixValue.number(new java.math.BigDecimal("9007199254740993")));
            assertThat(pageErrors).isEmpty();
        } finally { finishConfigurationSaves(held); }
    }

    @Test
    void rejectedConfigurationSavesKeepNewerDraftsCorrectable() throws Exception {
        openProject(CreatorConfigurationE2eTest.configuredProject("\"A\""));
        openConfiguration();
        final var held = holdNextConfigurationSave();
        try {
            page.locator("[data-configuration-remove]").click();
            page.locator("#configuration-apply").click();
            page.waitForCondition(() -> !held.isEmpty());
            assertThat(held.getFirst().response().status()).isEqualTo(422);
            addValue("value", "string", "CORRECTED");
            releaseConfigurationSave(held);
            page.waitForFunction("() => !state.writeActive");
            assertThat(savedConfiguration().values()).containsEntry("value", RailixValue.string("A"));
            page.locator("#configuration-apply").click();
            waitForText("#build-state", "Running");
            assertThat(savedConfiguration().values()).containsEntry("value", RailixValue.string("CORRECTED"));
            assertThat(pageErrors).isEmpty();
        } finally { finishConfigurationSaves(held); }
    }

    @Test
    void anUnconfirmedConfigurationSaveKeepsTheDraftAndRequiresReload() throws Exception {
        openProject(CreatorConfigurationE2eTest.configuredProject("\"A\""));
        selectWorldNode("result");
        openConfiguration();
        final var held = holdNextConfigurationSave();
        final var posts = new ArrayList<String>();
        page.onRequest(request -> { if (request.method().equals("POST") && request.url().endsWith("/api/editor")) posts.add(request.postData()); });
        try {
            page.locator("[data-configuration-field=value]").fill("B");
            page.locator("#configuration-apply").click();
            page.waitForCondition(() -> !held.isEmpty());
            assertThat(held.getFirst().response().status()).isEqualTo(200);
            page.locator("[data-configuration-field=value]").fill("C");
            held.removeFirst().route().abort();
            page.waitForFunction("() => !state.writeActive && state.saveFailure?.reload");
            page.locator("#configuration-apply").click();
            page.locator("[aria-label='Close Settings']").click();
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Reload project").setExact(true))).isVisible();
            assertThat(posts).hasSize(1);
            assertThat(savedConfiguration().values()).containsEntry("value", RailixValue.string("B"));
            openConfiguration();
            assertThat(page.locator("[data-configuration-field=value]").inputValue()).isEqualTo("C");
            assertThat(pageErrors).isEmpty();
        } finally { finishConfigurationSaves(held); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"before-save", "after-save", "queued-after-save"})
    void externalChangesStillConflictAcrossConfigurationAcknowledgments(final String scenario) throws Exception {
        openProject(CreatorConfigurationE2eTest.configuredProject("\"A\""));
        openConfiguration();
        final var held = new ArrayList<Route>();
        final var accepted = new LinkedHashMap<Route, APIResponse>();
        final boolean afterSave = scenario.endsWith("after-save");
        final var first = new AtomicBoolean(true);
        page.route("**/api/editor", route -> {
            if (!route.request().method().equals("POST") || !first.compareAndSet(true, false)) { route.resume(); return; }
            if (afterSave) accepted.put(route, route.fetch());
            held.add(route);
        });
        try {
            page.locator("[data-configuration-field=value]").fill("B");
            page.locator("#configuration-apply").click();
            page.waitForCondition(() -> !held.isEmpty());
            final String snapshot = CreatorServerE2eSupport.request(creator.baseUri(), "GET", "/api/editor", "").body();
            final var revision = CreatorServerE2eSupport.object(snapshot).values().get("revision");
            final var external = CreatorServerE2eSupport.request(creator.baseUri(), "PATCH", "/api/project",
                    "{\"revision\":" + RailixJson.write(revision) + ",\"changes\":{\"configuration\":{\"value\":\"EXTERNAL\"}}}");
            assertThat(external.statusCode()).as(external.body()).isEqualTo(200);
            page.locator("[data-configuration-field=value]").fill("C");
            if (scenario.equals("queued-after-save")) {
                page.locator("#configuration-apply").click();
                page.waitForFunction("() => Boolean(state.pendingWrite)");
                page.locator("[data-configuration-field=value]").fill("D");
            }
            final var pending = held.removeFirst();
            final var response = accepted.containsKey(pending) ? accepted.get(pending) : pending.fetch();
            assertThat(response.status()).isEqualTo(afterSave ? 200 : 409);
            pending.fulfill(new Route.FulfillOptions().setResponse(response));
            page.waitForFunction("() => !state.writeActive");
            page.locator("#configuration-apply").click();
            page.waitForFunction("() => state.configurationMessage.includes('project changed') || !state.writeActive"
                    + " && state.diagnostics.some(issue => issue.code === 'CREATOR_EDIT_CONFLICT')");
            assertThat(page.locator("[data-configuration-field=value]").inputValue())
                    .isEqualTo(scenario.equals("queued-after-save") ? "D" : "C");
            assertThat(savedConfiguration().values()).containsEntry("value", RailixValue.string("EXTERNAL"));
            assertThat(pageErrors).isEmpty();
        } finally {
            page.unroute("**/api/editor");
            held.forEach(Route::abort);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"undo", "external"})
    void configurationDraftsCannotOverwriteAnUndoOrAnExternalRefresh(final String scenario) throws Exception {
        openProject(CreatorConfigurationE2eTest.configuredProject("\"A\""));
        openConfiguration();
        page.locator("[data-configuration-field=value]").fill("B");
        page.locator("#configuration-apply").click();
        waitForText("#build-state", "Running");
        page.waitForFunction("() => !state.writeActive && !state.editorController");
        page.locator("[data-configuration-field=value]").fill("C");
        if (scenario.equals("undo")) {
            final boolean reopen = !page.locator("#editor-undo").isVisible();
            if (reopen) page.locator("[aria-label='Close Settings']").click();
            page.locator("#editor-undo").click();
            page.waitForFunction("() => !state.actionPending && !state.editorController && state.project.configuration.value === 'A'");
            if (reopen) openConfiguration();
        } else {
            final var revision = CreatorServerE2eSupport.object(CreatorServerE2eSupport.request(creator.baseUri(), "GET", "/api/editor", "").body())
                    .values().get("revision");
            final var external = CreatorServerE2eSupport.request(creator.baseUri(), "PATCH", "/api/project",
                    "{\"revision\":" + RailixJson.write(revision) + ",\"changes\":{\"configuration\":{\"value\":\"EXTERNAL\"}}}");
            assertThat(external.statusCode()).as(external.body()).isEqualTo(200);
            page.locator("[aria-label='Close Settings']").click();
            selectWorldNode("result");
            openConfiguration();
        }
        assertThat(page.locator("[data-configuration-field=value]").inputValue()).isEqualTo("C");
        page.locator("#configuration-apply").click();
        assertThat(page.locator("#configuration-status").textContent()).contains("project changed");
        assertThat(savedConfiguration().values()).containsEntry("value", RailixValue.string(scenario.equals("undo") ? "A" : "EXTERNAL"));
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    @Tag("browser-diagnostics")
    void configurationSurvivesLinkedReuseAndReopeningInABranchingProject() throws Exception {
        final String source = nestedSwitchProject()
                .replace("\"id\":\"nested-switch\"", "\"id\":\"configured-branches\",\"configuration\":{\"title\":\"BRANCHED\"}")
                .replace("\"examples\":[{", "\"examples\":[{\"name\":\"deny\",\"payload\":[],\"context\":{\"payload\":{\"value\":\"deny\"}}},{")
                .replace("{\"from\":\"switch.one\",\"to\":\"end\"}", "{\"from\":\"switch.one\",\"to\":\"result\"}")
                .replace("],\"links\":[", """
                        ,{"id":"result","use":"railix.field-manipulation","inputs":{
                          "field":["context","result"],"value":[{"option":"literal","inputs":{},
                            "input_sources":{"literal":{"configuration":"title"}}}],"steps":[]}}
                        ],"links":[{"from":"result.next","to":"end"},
                        """);
        openProject(source);
        selectWorldNode("result");
        assertThat(page.locator("[data-input-source]").inputValue()).isEqualTo("title");
        openInspectorTab("overview");
        page.locator("#add-next-step").click();
        page.locator("#step-search").fill("result");
        page.locator("[data-reuse-item=result][data-reuse-type=step]").click();
        page.locator("[data-apply-reuse=reuse]").click();
        page.waitForFunction("() => !state.actionPending && state.editor.reuse?.count === 2");
        openInspectorTab("inspect");
        assertThat(page.locator("[data-input-source]").inputValue()).isEqualTo("title");
        page.reload();
        waitForText("#build-state", "Running");
        selectWorldNode("result");
        assertThat(page.locator("[data-input-source]").inputValue()).isEqualTo("title");
        openConfiguration();
        page.locator("[data-configuration-field=value]").fill("UPDATED");
        page.locator("#configuration-apply").click();
        waitForText("#build-state", "Running");
        page.locator("[aria-label='Close Settings']").click();
        selectWorldNode("command");
        chooseVisibleExample("command:0");
        selectWorldNode("choice");
        openInspectorTab("overview");
        page.waitForFunction("() => document.querySelector('#dock-observation')?.textContent.includes('deny')");
        chooseVisibleExample("command:1");
        selectWorldNode("result");
        openInspectorTab("overview");
        page.waitForFunction("() => document.querySelector('#dock-observation')?.textContent.includes('UPDATED')");
        page.locator("#close-inspector").click();
        page.locator("#scope-overview").click();
        awaitScene();
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target", "screenshots"))
                .resolve("configuration-branches-" + page.viewportSize().width + ".png")));
        assertThat(executeSavedProject("branch-allow", "{\"payload\":{\"value\":\"allow\"}}")
                .values()).containsEntry("result", RailixValue.string("UPDATED"));
        assertThat(executeSavedProject("branch-deny", "{\"payload\":{\"value\":\"deny\"}}")
                .values()).containsEntry("result", RailixValue.nullValue());
        assertThat(Files.readString(directory.resolve("railix.creator.json"))).contains("\"reuse\"");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "type", "range"})
    void unboundInputsHideConfigurationChoicesWhenNoneAreCompatible(final String kind) {
        final String configuration = switch (kind) {
            case "absent" -> "";
            case "type" -> ",\"configuration\":{\"unusable\":false}";
            case "range" -> ",\"configuration\":{\"unusable\":70000}";
            default -> throw new AssertionError(kind);
        };
        openProject("""
                {"format":1,"id":"configuration-choices"%s,"nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"request","use":"railix.trigger.http","inputs":{"port":0},
                   "examples":[{"name":"get","payload":{"method":"GET","path":"/"}}]}],
                 "links":[{"from":"app.start","to":"request"},{"from":"request.next","to":"end"}]}
                """.formatted(configuration));
        selectWorldNode("request");
        assertThat(page.getByLabel("Port source", new Page.GetByLabelOptions().setExact(true)).count()).isZero();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#port-value")).isVisible();
        assertThat(page.locator("#port-value").inputValue()).isEqualTo("0");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"remove", "type", "range"})
    void unavailableConfigurationReferencesKeepTheirSourceControl(final String edit) throws Exception {
        openProject("""
                {"format":1,"id":"configuration-reference","configuration":{"port":0},"nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"request","use":"railix.trigger.http","inputs":{},
                   "input_sources":{"port":{"configuration":"port"}},
                   "examples":[{"name":"get","payload":{"method":"GET","path":"/"}}]}],
                 "links":[{"from":"app.start","to":"request"},{"from":"request.next","to":"end"}]}
                """);
        selectWorldNode("request");
        final String before = Files.readString(directory.resolve("project.json"));
        final String pid = applicationPid();
        openConfiguration();
        if (edit.equals("remove")) page.locator("[data-configuration-remove]").click();
        else if (edit.equals("type")) page.locator("[data-configuration-field=type]").selectOption("boolean");
        else page.locator("[data-configuration-field=value]").fill("70000");
        page.locator("#configuration-apply").click();
        waitForText("#build-state", "Not built");
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(before);
        assertThat(applicationPid()).isEqualTo(pid);
        if (edit.equals("remove")) {
            assertThat(page.locator("#configuration-status").textContent()).contains("Configuration key does not exist");
            assertThat(page.locator(".configuration-row").count()).isZero();
        }
        page.locator("[aria-label='Close Settings']").click();
        selectWorldNode("request");
        final var source = page.getByLabel("Port source", new Page.GetByLabelOptions().setExact(true));
        assertThat(source.inputValue()).isEqualTo("port");
        assertThat(source.locator("option:checked").textContent()).contains("(unavailable)");
        assertThat(source.locator("option[value='']").count()).isEqualTo(1);
        openConfiguration();
        page.locator("#configuration-reset").click();
        waitForText("#build-state", "Running");
        assertThat(page.locator(".configuration-row").count()).isEqualTo(1);
        page.locator("[aria-label='Close Settings']").click();
        selectWorldNode("request");
        assertThat(source.inputValue()).isEqualTo("port");
        assertThat(source.locator("option:checked").textContent()).isEqualTo("port");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void configuredInputsKeepMainValidationFeedbackAndCanBeCorrected() throws Exception {
        openProject("""
                {"format":1,"id":"configured-port","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"request","use":"railix.trigger.http","inputs":{"port":0},
                   "examples":[{"name":"get","payload":{"method":"GET","path":"/"}}]}],
                 "links":[{"from":"app.start","to":"request"},{"from":"request.next","to":"end"}]}
                """);
        openConfiguration();
        addValue("fraction", "number", "0.5");
        addValue("zero", "number", "0");
        page.locator("#configuration-apply").click();
        waitForText("#build-state", "Running");
        page.locator("[aria-label='Close Settings']").click();
        selectWorldNode("request");
        final String before = Files.readString(directory.resolve("project.json"));
        final var source = page.getByLabel("Port source", new Page.GetByLabelOptions().setExact(true));
        source.selectOption("fraction");
        waitForText("#build-state", "Not built");
        assertThat(page.locator("#port-error").textContent()).contains("Configuration \"fraction\"", "whole number");
        assertThat(source.getAttribute("aria-invalid")).isEqualTo("true");
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(before);
        source.selectOption("zero");
        waitForText("#build-state", "Running");
        assertThat(page.locator("#port-error").count()).isZero();
        assertThat(source.getAttribute("aria-invalid")).isNull();
        assertThat(Files.readString(directory.resolve("project.json"))).contains("\"port\":{\"configuration\":\"zero\"}");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void configurationRowsFollowToolbarUndoAndRedo() throws Exception {
        openProject(CreatorConfigurationE2eTest.literalProject());
        openConfiguration();
        addValue("title", "string", "HELLO_CONFIG");
        page.locator("#configuration-apply").click();
        waitForText("#build-state", "Running");
        page.waitForFunction("() => !state.writeActive && state.history.undo");
        final boolean reopen = !page.locator("#editor-undo").isVisible();
        if (reopen) page.locator("[aria-label='Close Settings']").click();
        page.locator("#editor-undo").click();
        page.waitForFunction("() => !state.actionPending && !state.editorController && !state.project.configuration");
        if (reopen) openConfiguration();
        assertThat(page.locator(".configuration-row").count()).isZero();
        if (reopen) page.locator("[aria-label='Close Settings']").click();
        page.locator("#editor-redo").click();
        page.waitForFunction("() => !state.actionPending && !state.editorController && state.project.configuration?.title === 'HELLO_CONFIG'");
        if (reopen) openConfiguration();
        assertThat(page.locator("[data-configuration-field=value]").inputValue()).isEqualTo("HELLO_CONFIG");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void unsavedConfigurationWarnsBeforeReloadAndKeepsTheDraftWhenCancelled() throws Exception {
        openProject(CreatorConfigurationE2eTest.literalProject());
        final String before = Files.readString(directory.resolve("project.json"));
        openConfiguration();
        addValue("title", "string", "UNSAVED");
        final var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        page.onDialog(dialog -> {
            assertThat(dialog.type()).isEqualTo("beforeunload");
            dialog.dismiss();
            cancelled.set(true);
        });
        page.evaluate("() => location.reload()");
        page.waitForCondition(cancelled::get);
        assertThat(page.locator("[data-configuration-field=value]").inputValue()).isEqualTo("UNSAVED");
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(before);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"canvas", "character"})
    @Tag("responsive")
    @Tag("browser-diagnostics")
    void configurationCanBeAuthoredBoundExecutedAndReopened(final String renderer) throws Exception {
        openProject(CreatorConfigurationE2eTest.literalProject().replace(
                "\"option\":\"literal\",\"inputs\":{\"literal\":\"RAILIX\"}",
                "\"option\":\"current\",\"inputs\":{}"));
        page.locator("#open-settings").click();
        if (renderer.equals("character")) page.locator("#theme-select").selectOption("signal/theme.css");
        page.waitForFunction("renderer => document.querySelector('#graph').dataset.renderer === renderer", renderer);
        page.waitForFunction("() => !state.settingsWriting && !state.settingsTimer");
        page.locator("#settings-configuration-tab").click();
        addValue("title", "string", "HELLO_CONFIG");
        addValue("account_code", "string", "00123");
        addValue("precise", "number", "9007199254740993");
        addValue("off", "boolean", "false");
        addValue("zero", "number", "0");
        addValue("empty", "string", "");
        page.locator("#configuration-apply").click();
        waitForText("#build-state", "Running");
        page.locator("#settings-configuration-tab").scrollIntoViewIfNeeded();
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target", "screenshots"))
                .resolve("configuration-" + renderer + "-" + page.viewportSize().width + ".png")));
        page.locator("[aria-label='Close Settings']").click();
        selectWorldNode("result");
        assertThat(page.locator("#value-0-option").inputValue()).isEqualTo("current");
        page.locator("#value-0-option").selectOption(
                new com.microsoft.playwright.options.SelectOption().setLabel("Enter a fixed value"));
        waitForText("#build-state", "Running");
        page.locator("[data-input-source]").selectOption("precise");
        waitForText("#build-state", "Running");
        chooseVisibleExample("command:0");
        openInspectorTab("overview");
        page.waitForFunction("() => document.querySelector('#dock-observation')?.textContent.includes('9007199254740993')");
        openInspectorTab("inspect");
        page.locator("[data-input-source]").selectOption("title");
        waitForText("#build-state", "Running");
        openInspectorTab("overview");
        page.waitForFunction("() => document.querySelector('#dock-observation')?.textContent.includes('HELLO_CONFIG')");
        final String source = Files.readString(directory.resolve("project.json"));
        assertThat(source).contains("9007199254740993", "\"account_code\":\"00123\"", "\"off\":false", "\"zero\":0", "\"empty\":\"\"",
                "\"input_sources\":{\"literal\":{\"configuration\":\"title\"}}");
        page.reload();
        waitForText("#build-state", "Running");
        selectWorldNode("result");
        assertThat(page.locator("[data-input-source]").inputValue()).isEqualTo("title");
        assertThat(page.locator(".configuration-reference-value").textContent()).contains("HELLO_CONFIG");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"key", "value"})
    @Tag("responsive")
    void oversizedConfigurationFieldsKeepTheirFullDraftAndCanBeCorrected(final String field) throws Exception {
        openProject(CreatorConfigurationE2eTest.literalProject());
        final String before = Files.readString(directory.resolve("project.json"));
        openConfiguration();
        addValue("key", "string", "VALID");
        final var control = page.locator("[data-configuration-field=" + field + "]");
        final String oversized = field.equals("key") ? "k".repeat(65) : "\\\"".repeat(2_048) + "x";
        control.fill(oversized);
        page.locator("#configuration-apply").click();
        assertThat(page.locator("#configuration-status").textContent())
                .contains(field.equals("key") ? "1 to 64" : "at most 4096");
        assertThat(control.inputValue()).isEqualTo(oversized);
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(before);
        final String accepted = oversized.substring(0, field.equals("key") ? 64 : 4_096);
        control.fill(accepted);
        page.locator("#configuration-apply").click();
        waitForText("#build-state", "Running");
        assertThat(savedConfiguration().values()).containsExactlyEntriesOf(Map.of(
                field.equals("key") ? accepted : "key",
                RailixValue.string(field.equals("value") ? accepted : "VALID")));
        page.reload();
        waitForText("#build-state", "Running");
        openConfiguration();
        assertThat(control.inputValue()).isEqualTo(accepted);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void incompleteAndDuplicateDraftRowsNeverOverwriteTheProject() throws Exception {
        openProject(CreatorConfigurationE2eTest.literalProject());
        final String before = Files.readString(directory.resolve("project.json"));
        openConfiguration();
        addValue("amount", "number", "-");
        page.locator("#configuration-apply").click();
        assertThat(page.locator("#configuration-status").textContent()).contains("Enter a supported Number");
        assertThat(page.locator("[data-configuration-field=value]").inputValue()).isEqualTo("-");
        page.locator("[data-configuration-field=value]").fill("0");
        addValue("amount", "boolean", "false");
        page.locator("#configuration-apply").click();
        assertThat(page.locator("#configuration-status").textContent()).contains("Duplicate configuration key");
        assertThat(page.locator(".configuration-row").count()).isEqualTo(2);
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(before);
        page.locator("#configuration-reset").click();
        assertThat(page.locator(".configuration-row").count()).isZero();
        assertThat(page.locator("#configuration-status").textContent()).isEmpty();
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(before);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void characterRulesRejectInvalidDraftsAndPreserveEscapedValues() throws Exception {
        openProject(CreatorConfigurationE2eTest.literalProject());
        final String before = Files.readString(directory.resolve("project.json"));
        openConfiguration();
        addValue("valid", "string", "RAILIX");
        final var row = page.locator(".configuration-row").first();
        for (final String key : List.of("bad name", "bad\nname")) {
            row.locator("[data-configuration-field=key]").fill(key);
            page.locator("#configuration-apply").click();
            assertThat(page.locator("#configuration-status").textContent()).contains("Configuration names must");
            assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(before);
        }
        row.locator("[data-configuration-field=key]").fill("09CONFIG_name-test");
        for (final String value : List.of("has space", "line\nvalue")) {
            row.locator("[data-configuration-field=value]").fill(value);
            page.locator("#configuration-apply").click();
            assertThat(page.locator("#configuration-status").textContent()).contains("String values must");
            assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(before);
        }
        row.locator("[data-configuration-field=value]").fill("\ninvalid");
        page.locator("#configuration-add").click();
        assertThat(row.locator("[data-configuration-field=value]").inputValue()).isEqualTo("\ninvalid");
        page.locator("[data-configuration-remove]").last().click();
        page.locator("#configuration-apply").click();
        assertThat(page.locator("#configuration-status").textContent()).contains("String values must");

        final String value = "https://Aa09-_.@:/\\\"'!?%&+#=[]{}()<>|*^$~,;`";
        row.locator("[data-configuration-field=value]").fill(value);
        page.locator("#configuration-apply").click();
        waitForText("#build-state", "Running");
        page.locator("[aria-label='Close Settings']").click();
        selectWorldNode("result");
        page.locator("[data-input-source]").selectOption("09CONFIG_name-test");
        waitForText("#build-state", "Running");
        chooseVisibleExample("command:0");
        openInspectorTab("overview");
        page.waitForFunction("() => document.querySelector('#dock-observation')?.textContent.includes('https://Aa09-_.@:/')");
        assertThat(Files.readString(directory.resolve("project.json"))).contains(RailixJson.write(RailixValue.string(value)));
        page.reload();
        waitForText("#build-state", "Running");
        openConfiguration();
        assertThat(page.locator("[data-configuration-field=value]").inputValue()).isEqualTo(value);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void changingComparisonKeepsItsConfiguredOperand() throws Exception {
        openProject(configuredPredicateProject("value.equals", "expected", "\"RAILIX\"", "\"RAILIX\""));
        selectWorldNode("result");
        for (final String use : List.of("value.not-equals", "value.equals")) {
            final var response = page.waitForResponse(reply -> reply.url().endsWith("/api/editor")
                            && reply.request().method().equals("POST"),
                    () -> page.locator("[data-replace-comparison]").selectOption(use));
            assertThat(response.status()).as(response.text()).isEqualTo(200);
            waitForText("#build-state", "Running");
            assertThat(page.locator(".condition-predicate [data-input-source]").inputValue()).isEqualTo("operand");
            assertThat(RailixJson.write(savedCandidate())).contains("\"use\":\"" + use + "\"",
                            "\"input_sources\":{\"expected\":{\"configuration\":\"operand\"}}")
                    .doesNotContain("\"expected\":null");
            final var context = executeSavedProject(use, "{}");
            if (use.equals("value.equals")) assertThat(context.values()).containsEntry("output", RailixValue.string("RAILIX"));
            else assertThat(context.values()).doesNotContainKey("output");
            page.reload();
            waitForText("#build-state", "Running");
            selectWorldNode("result");
            assertThat(page.locator("[data-replace-comparison]").inputValue()).isEqualTo(use);
            assertThat(page.locator(".condition-predicate [data-input-source]").inputValue()).isEqualTo("operand");
        }
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void changingComparisonDropsIncompatibleBindings() throws Exception {
        openProject(configuredPredicateProject("number.greater-than", "than", "10", "5"));
        selectWorldNode("result");
        final var response = page.waitForResponse(reply -> reply.url().endsWith("/api/editor")
                        && reply.request().method().equals("POST"),
                () -> page.locator("[data-replace-comparison]").selectOption("value.equals"));
        assertThat(response.status()).as(response.text()).isEqualTo(200);
        waitForText("#build-state", "Running");
        assertThat(page.locator(".condition-predicate [data-input-source]").inputValue()).isEmpty();
        assertThat(RailixJson.write(savedCandidate())).contains("\"expected\":null", "\"use\":\"value.equals\"",
                        "\"input_sources\":{\"literal\":{\"configuration\":\"value\"}}")
                .doesNotContain("\"configuration\":\"operand\"", "\"than\"");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"current", "field"})
    void changingCandidateSourceDropsOnlyItsOwnedBindings(final String option) throws Exception {
        openProject(configuredPredicateProject("value.not-equals", "expected", "\"RAILIX\"", "\"BLOCKED\""));
        selectWorldNode("result");
        final var response = page.waitForResponse(reply -> reply.url().endsWith("/api/editor")
                        && reply.request().method().equals("POST"),
                () -> page.locator("#value-0-option").selectOption(option));
        assertThat(response.status()).as(response.text()).isEqualTo(200);
        waitForText("#build-state", "Running");
        final var candidate = savedCandidate();
        assertThat(candidate.values()).doesNotContainKey("input_sources").containsEntry("option", RailixValue.string(option));
        assertThat(RailixJson.write(candidate.values().get("when")))
                .contains("\"input_sources\":{\"expected\":{\"configuration\":\"operand\"}}");
        final var context = executeSavedProject(option, "{\"output\":\"ORIGINAL\",\"payload\":[\"NEW\"]}");
        final RailixValue expected = option.equals("current") ? RailixValue.string("ORIGINAL")
                : RailixValue.array(List.of(RailixValue.string("NEW")));
        assertThat(context.values()).containsEntry("output", expected);
        page.reload();
        waitForText("#build-state", "Running");
        selectWorldNode("result");
        assertThat(page.locator("#value-0-option").inputValue()).isEqualTo(option);
        assertThat(page.locator(".condition-predicate [data-input-source]").inputValue()).isEqualTo("operand");
        final var returned = page.waitForResponse(reply -> reply.url().endsWith("/api/editor")
                        && reply.request().method().equals("POST"),
                () -> page.locator("#value-0-option").selectOption("literal"));
        assertThat(returned.status()).as(returned.text()).isEqualTo(200);
        waitForText("#build-state", "Running");
        assertThat(savedCandidate().values()).doesNotContainKey("input_sources");
        assertThat(RailixJson.write(savedCandidate().values().get("inputs"))).isEqualTo("{\"literal\":null}");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"value", "type", "remove"})
    void savedConfigurationRowsKeepTheirIdentityAfterReload(final String edit) throws Exception {
        openProject(CreatorConfigurationE2eTest.literalProject());
        openConfiguration();
        addValue("z", "string", "Z");
        addValue("a", "string", "A");
        final var reload = page.waitForResponse(response -> response.url().contains("/api/editor"),
                () -> page.locator("#configuration-apply").click());
        assertThat(reload.status()).isEqualTo(200);
        page.waitForFunction("() => !state.editorController && Object.keys(state.project.configuration)[0] === 'a'");
        final int index = ((Number) page.locator("[data-configuration-field=key]")
                .evaluateAll("fields => fields.findIndex(field => field.value === 'z')")).intValue();
        assertThat(index).isNotNegative();
        final var row = page.locator("[data-configuration-row]").nth(index);
        switch (edit) {
            case "value" -> row.locator("[data-configuration-field=value]").fill("EDITED");
            case "type" -> row.locator("[data-configuration-field=type]").selectOption("boolean");
            case "remove" -> row.locator("[data-configuration-remove]").click();
            default -> throw new AssertionError(edit);
        }
        final var saved = page.waitForResponse(response -> response.url().endsWith("/api/editor")
                        && response.request().method().equals("POST"),
                () -> page.locator("#configuration-apply").click());
        assertThat(saved.status()).as(saved.text()).isEqualTo(200);
        final Map<String, RailixValue> expected = switch (edit) {
            case "value" -> Map.of("a", RailixValue.string("A"), "z", RailixValue.string("EDITED"));
            case "type" -> Map.of("a", RailixValue.string("A"), "z", RailixValue.bool(false));
            case "remove" -> Map.of("a", RailixValue.string("A"));
            default -> throw new AssertionError(edit);
        };
        assertThat(savedConfiguration().values()).containsExactlyInAnyOrderEntriesOf(expected);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void importingAProjectClearsConfigurationDraftsAndMessages() throws Exception {
        openProject(CreatorConfigurationE2eTest.configuredProject("\"OLD\""));
        openConfiguration();
        page.locator("[data-configuration-field=value]").fill("INVALID VALUE");
        page.locator("#configuration-apply").click();
        assertThat(page.locator("#configuration-status").textContent()).contains("String values must");
        page.locator("[aria-label='Close Settings']").click();
        page.locator("#project-menu").click();
        page.locator("#open-project-import").click();
        page.locator("#project-import-paste summary").click();
        final String imported = CreatorConfigurationE2eTest.configuredProject("\"NEW\"")
                .replace("\"id\":\"configured-project\"", "\"id\":\"imported-project\"");
        page.locator("#project-import-source").fill(imported);
        page.locator("#project-import-source").press("Tab");
        page.waitForResponse(response -> response.url().endsWith("/api/editor") && response.request().method().equals("GET"),
                () -> page.locator("#project-import-submit").click());
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#project-import-panel")).isHidden();
        openConfiguration();
        assertThat(page.locator("[data-configuration-field=value]").inputValue()).isEqualTo("NEW");
        assertThat(page.locator("#configuration-status").textContent()).isEmpty();
        page.locator("[data-configuration-field=value]").fill("EDITED");
        final var saved = page.waitForResponse(response -> response.url().endsWith("/api/editor")
                        && response.request().method().equals("POST"),
                () -> page.locator("#configuration-apply").click());
        assertThat(saved.status()).as(saved.text()).isEqualTo(200);
        assertThat(savedConfiguration().values()).containsExactlyEntriesOf(Map.of("value", RailixValue.string("EDITED")));
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void inactiveConfigurationDoesNotBuildOrRebuildRows() {
        final var configuration = new LinkedHashMap<String, RailixValue>();
        for (int index = 0; index < 1_000; index++) configuration.put("key_" + index, RailixValue.string("VALUE"));
        openProject(CreatorConfigurationE2eTest.literalProject().replace("\"format\":1",
                "\"format\":1,\"configuration\":" + RailixJson.write(RailixValue.object(configuration))));
        selectWorldNode("result");
        openInspectorTab("overview");
        openInspectorTab("inspect");
        assertThat(page.locator("[data-configuration-row]").count()).isZero();
        page.locator("#open-settings").click();
        assertThat(page.locator("[data-configuration-row]").count()).isZero();
        page.locator("#settings-configuration-tab").click();
        assertThat(page.locator("[data-configuration-row]").count()).isEqualTo(1_000);
        page.evaluate("""
                () => {
                  window.hiddenConfigurationAdditions = 0;
                  new MutationObserver(records => {
                    window.hiddenConfigurationAdditions += records.reduce((count, record) => count + record.addedNodes.length, 0);
                  }).observe(document.querySelector('#configuration-rows'), {childList:true});
                }
                """);
        page.locator("[aria-label='Close Settings']").click();
        openInspectorTab("overview");
        openInspectorTab("inspect");
        page.locator("#open-settings").click();
        assertThat(page.evaluate("() => window.hiddenConfigurationAdditions")).isEqualTo(0);
        page.locator("#settings-configuration-tab").click();
        assertThat(page.locator("[data-configuration-row]").count()).isEqualTo(1_000);
        assertThat(pageErrors).isEmpty();
    }

    private record HeldConfigurationSave(Route route, APIResponse response) { }

    private void openConfiguration() {
        page.locator("#open-settings").click();
        page.locator("#settings-configuration-tab").click();
    }

    private List<HeldConfigurationSave> holdNextConfigurationSave() {
        final var held = new ArrayList<HeldConfigurationSave>();
        final var first = new AtomicBoolean(true);
        page.route("**/api/editor", route -> {
            if (route.request().method().equals("POST") && first.compareAndSet(true, false))
                held.add(new HeldConfigurationSave(route, route.fetch()));
            else route.resume();
        });
        return held;
    }

    private void releaseConfigurationSave(final List<HeldConfigurationSave> held) {
        final var pending = held.removeFirst();
        pending.route().fulfill(new Route.FulfillOptions().setResponse(pending.response()));
    }

    private void finishConfigurationSaves(final List<HeldConfigurationSave> held) {
        page.unroute("**/api/editor");
        held.forEach(pending -> pending.route().abort());
    }

    private RailixValue.ObjectValue savedConfiguration() throws Exception {
        final var project = CreatorServerE2eSupport.object(Files.readString(directory.resolve("project.json")));
        return (RailixValue.ObjectValue) project.values().get("configuration");
    }

    private RailixValue.ObjectValue savedCandidate() throws Exception {
        final var project = CreatorServerE2eSupport.object(Files.readString(directory.resolve("project.json")));
        final var nodes = (RailixValue.ArrayValue) project.values().get("nodes");
        final var result = nodes.values().stream().map(RailixValue.ObjectValue.class::cast)
                .filter(node -> RailixValue.string("result").equals(node.values().get("id"))).findFirst().orElseThrow();
        final var inputs = (RailixValue.ObjectValue) result.values().get("inputs");
        return (RailixValue.ObjectValue) ((RailixValue.ArrayValue) inputs.values().get("value")).values().getFirst();
    }

    private RailixValue.ObjectValue executeSavedProject(final String name, final String context) throws Exception {
        final var saved = ProjectFiles.read(directory.resolve("project.json"));
        assertThat(saved.diagnostics()).isEmpty();
        try (var application = GeneratedApplicationFixture.start(directory.resolve(name), saved.source())) {
            final var response = application.run("command", context);
            assertThat(response.status()).as(response.body()).isEqualTo(200);
            final var result = CreatorServerE2eSupport.object(response.body());
            return (RailixValue.ObjectValue) result.values().get("context");
        }
    }

    private static String configuredPredicateProject(final String use, final String input, final String value, final String operand) {
        return """
                {"format":1,"id":"configured-predicate","configuration":{"value":%s,"operand":%s},"nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"empty","payload":[]}]},
                  {"id":"result","use":"railix.field-manipulation","inputs":{"field":["context","output"],
                    "value":[{"option":"literal","inputs":{},"input_sources":{"literal":{"configuration":"value"}},
                      "when":{"transforms":[],"all":[[{"use":"%s","inputs":{},"input_sources":{"%s":{"configuration":"operand"}}}]]}}],
                    "steps":[]}}
                ],"links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"result"},{"from":"result.next","to":"end"}]}
                """.formatted(value, operand, use, input);
    }

    private void addValue(final String key, final String type, final String value) {
        page.locator("#configuration-add").click();
        final var row = page.locator(".configuration-row").last();
        row.locator("[data-configuration-field=key]").fill(key);
        row.locator("[data-configuration-field=type]").selectOption(type);
        page.keyboard().press("Tab");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(row.locator("[data-configuration-field=value]")).isFocused();
        if (type.equals("boolean")) row.locator("[data-configuration-field=value]").selectOption(value);
        else row.locator("[data-configuration-field=value]").fill(value);
    }
}
