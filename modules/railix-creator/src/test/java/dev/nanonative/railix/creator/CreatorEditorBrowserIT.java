package dev.nanonative.railix.creator;

import com.microsoft.playwright.Route;
import com.microsoft.playwright.Page;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.RailixJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

final class CreatorEditorBrowserIT extends RailixCreatorBrowserSupport {
    @Test
    @Tag("responsive")
    void correctingAFieldClearsItsLocalErrorsBeforeTheSaveCompletes() {
        openProject(choiceProject());
        selectWorldNode("command");
        openInspectorTab("examples");
        page.locator("#example-payload").fill("[");
        page.locator("#example-payload").press("Tab");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#inspector-content .issues article"))
                .hasCount(1);
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("[data-select-node='command']"))
                .hasAttribute("data-error", "true");
        final var held = new ArrayList<Route>();
        page.route("**/api/editor", route -> {
            if (route.request().method().equals("POST")) held.add(route);
            else route.resume();
        });
        try {
            page.locator("#example-payload").fill("[\"corrected\"]");
            page.locator("#example-payload").press("Tab");
            page.waitForCondition(() -> !held.isEmpty());
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#inspector-content .issues article"))
                    .hasCount(0);
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("[data-inspector-mode='inspect']"))
                    .hasAttribute("data-has-error", "false");
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("[data-select-node='command']"))
                    .hasAttribute("data-error", "false");
            assertThat(pageErrors).isEmpty();
        } finally {
            page.unroute("**/api/editor");
            held.forEach(Route::resume);
        }
    }

    @Test
    @Tag("responsive")
    void committingAFieldKeepsTheClickedNeighborFocused() {
        openProject(choiceProject());
        selectWorldNode("command");
        openInspectorTab("examples");
        page.locator("#example-name").fill("renamed");
        page.locator("#example-payload").click();
        assertThat(page.locator("#example-payload").evaluate("element => element === document.activeElement")).isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @Tag("responsive")
    @ValueSource(strings = {"overview", "appearance", "groups"})
    void committingAFieldDoesNotConsumeTheNextInspectorTabClick(final String mode) {
        openProject(choiceProject());
        selectWorldNode("command");
        openInspectorTab("examples");
        page.locator("#example-name").fill("renamed");
        page.locator("[data-inspector-mode='" + mode + "']").click();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(
                page.locator("[data-inspector-mode='" + mode + "']")).hasClass("active");
        waitForText("#build-state", "Built");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @Tag("responsive")
    @ValueSource(strings = {"name", "context"})
    void saveCompletionPreservesAnUncommittedInspectorField(final String field) {
        openProject(choiceProject());
        selectWorldNode("command");
        openInspectorTab("examples");
        final var held = new ArrayList<Route>();
        final var responses = new ArrayList<com.microsoft.playwright.APIResponse>();
        page.route("**/api/editor", route -> {
            if (route.request().method().equals("POST") && held.isEmpty()) {
                responses.add(route.fetch());
                held.add(route);
            } else route.resume();
        });
        try {
            final String first = field.equals("name") ? "#example-payload" : "#example-name";
            page.locator(first).fill(field.equals("name") ? "[\"saved\"]" : "saved-example");
            page.locator(first).press("Tab");
            page.waitForCondition(() -> !held.isEmpty());
            assertThat(responses.getFirst().status()).isEqualTo(200);
            final var editing = page.locator("#example-" + field);
            final String draft = field.equals("name") ? "Still typing" : "{\"draft\":true}";
            editing.fill(draft);
            editing.evaluate("element => element.setSelectionRange(3, 7, 'backward')");
            held.getFirst().fulfill(new Route.FulfillOptions().setResponse(responses.getFirst()));
            page.waitForFunction("() => !state.writeActive");

            assertThat(editing.inputValue()).isEqualTo(draft);
            assertThat(editing.evaluate("element => element === document.activeElement")).isEqualTo(true);
            assertThat(editing.evaluate("element => [element.selectionStart, element.selectionEnd, element.selectionDirection]"))
                    .isEqualTo(List.of(3, 7, "backward"));
            final var reloads = new java.util.concurrent.atomic.AtomicInteger();
            page.onDialog(dialog -> {
                reloads.incrementAndGet();
                dialog.dismiss();
            });
            page.evaluate("() => {setTimeout(() => location.reload(), 0);}");
            page.waitForCondition(() -> reloads.get() == 1);
            assertThat(editing.inputValue()).isEqualTo(draft);
            editing.press("Tab");
            waitForText("#build-state", "Built");
            page.waitForFunction("() => !state.writeActive");
            page.reload();
            waitForText("#build-state", "Built");
            selectWorldNode("command");
            openInspectorTab("examples");
            assertThat(page.locator("#example-" + field).inputValue())
                    .isEqualTo(field.equals("name") ? draft : "{\n  \"draft\": true\n}");
            assertThat(pageErrors).isEmpty();
        } finally {
            page.unroute("**/api/editor");
            responses.forEach(com.microsoft.playwright.APIResponse::dispose);
        }
    }

    @Test
    void failedExampleDoesNotMarkTheApplicationOrItsSuccessfulTriggerAsBroken() {
        page.setDefaultTimeout(5000);
        openProject(attentionProject());
        page.waitForFunction("() => state.application.examples?.state==='completed'");
        page.locator("#issue-count").click();
        assertThat(page.locator("#runtime-summary").isHidden()).isTrue();
        page.locator("#attention-list [data-example-issue='command:1']").click();
        page.waitForFunction("() => document.querySelector('#inspector').dataset.selection==='normalize'");
        page.waitForFunction("() => document.querySelector('[data-select-node=command]')?.dataset.coverage==='selected'");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("[data-select-node=normalize]"))
                .hasAttribute("data-error", "true");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("[data-select-node=command]"))
                .hasAttribute("data-error", "false");
        assertThat(page.locator("[data-select-node=command]").evaluate("element=>getComputedStyle(element).color"))
                .isEqualTo("rgb(160, 220, 204)");
        assertThat(page.locator("[data-select-node=result]").getAttribute("data-coverage")).isNotEqualTo("selected");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#build-state")).hasText("Running");
        page.locator("#visible-example").selectOption("command:0");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("[data-select-node=normalize]"))
                .hasAttribute("data-error", "true");
        page.waitForFunction("() => document.querySelector('[data-select-node=result]')?.dataset.coverage==='selected'");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("[data-field-error]"))
                .hasCount(0);
        page.locator("#inspector-content .persistent-issue[data-example-issue='command:1']").click();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#visible-example"))
                .hasValue("command:1");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("[data-field-error]").first())
                .containsText("String");
        page.locator("[data-clear-example]").click();
        page.locator("#zoom-fit").click();
        awaitScene();
        for(final String id:List.of("app","command")) {
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("[data-select-node='"+id+"']"))
                    .hasAttribute("data-error","false");
        }
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("[data-select-node=app]"))
                .hasAttribute("data-activity","active");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void failureListAndNavigationDoNotDependOnReadingRecordedValues() {
        page.setDefaultTimeout(5000);
        openProject(attentionProject());
        page.route("**/api/examples/*/view",Route::abort);
        page.route("**/api/examples/steps/*",Route::abort);
        page.locator("#issue-count").click();
        final var issue=page.locator("#attention-list [data-example-issue='command:1']");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(issue).containsText("Normalize");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(issue).containsText("invalid-name");
        issue.click();
        page.waitForFunction("() => document.querySelector('#inspector').dataset.selection==='normalize'");
        assertThat(page.locator("#visible-example").inputValue()).isEqualTo("command:1");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#build-state")).hasText("Running");
        assertThat(page.locator("body").innerText()).doesNotContain("Select the Example again to retry");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void anIssueClickSurvivesTheInitialSceneArrivingDuringItsTraceRead() {
        page.setDefaultTimeout(8000);
        openProject(attentionProject());
        final var scenes=new ArrayList<Route>();
        final var traces=new ArrayList<Route>();
        page.route("**/api/scene?**",scenes::add);
        page.route("**/api/examples/*/view",route->{
            if(traces.isEmpty())traces.add(route);else route.resume();
        });
        try {
            page.reload();
            page.locator("#issue-count").click();
            page.locator("[data-example-issue='command:1']").click();
            page.waitForCondition(()->!traces.isEmpty());
            page.unroute("**/api/scene?**");
            scenes.forEach(Route::resume);
            page.waitForFunction("() => Boolean(document.querySelector('#graph').dataset.sceneRevision)");
            traces.getFirst().resume();
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("[data-field-error]").first())
                    .containsText("String");
            assertThat(page.locator("#inspector").getAttribute("data-selection")).isEqualTo("normalize");
            assertThat(pageErrors).isEmpty();
        } finally {
            page.unroute("**/api/scene?**");page.unroute("**/api/examples/*/view");
        }
    }

    @Test
    void shiftSelectionReplacesSettingsWithTheGroupAction() {
        openProject(fourStepProject());
        selectWorldNode("one");
        page.locator("#open-settings").click();
        page.evaluate("() => void state.world.focus('two')");
        awaitScene();
        page.locator("[data-select-node=two]").click(new com.microsoft.playwright.Locator.ClickOptions()
                .setModifiers(List.of(com.microsoft.playwright.options.KeyboardModifier.SHIFT)));
        assertThat(page.locator("#audio-panel").isHidden()).isTrue();
        openInspectorTab("overview");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("[data-group-selection]"))
                .containsText("2");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void selectingAnotherStationReplacesSettingsInTheSamePanel() {
        page.setDefaultTimeout(5000);
        openProject(fourStepProject());
        selectWorldNode("one");
        page.locator("#open-settings").click();
        page.locator("#inspector-resize").press("ArrowLeft");
        assertThat(page.locator("#inspector").boundingBox().width).isGreaterThan(420);
        page.evaluate("() => void state.world.focus('two')");
        awaitScene();
        page.locator("[data-select-node=two]").click();
        page.waitForFunction("() => document.querySelector('#inspector').dataset.selection==='two'");
        assertThat(page.locator("#audio-panel").isHidden()).isTrue();
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isEqualTo("two");
        assertThat(page.locator(".inspector-chrome").isVisible()).isTrue();
        assertThat(page.locator("#open-settings").getAttribute("aria-expanded")).isEqualTo("false");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @Tag("responsive")
    @ValueSource(ints={1440,390})
    void settingsReuseTheInspectorAndRestoreItsSelection(final int width) throws Exception {
        page.setDefaultTimeout(5000);
        page.setViewportSize(width,900);
        openProject(attentionProject());
        selectWorldNode("normalize");
        openInspectorTab("appearance");
        final String project=Files.readString(directory.resolve("project.json"));
        page.locator("#open-settings").click();
        assertThat(page.locator("#inspector #audio-panel").isVisible()).isTrue();
        assertThat(page.locator("#audio-panel").getAttribute("popover")).isNull();
        assertThat(page.locator(".inspector-chrome").isVisible()).isFalse();
        page.locator("#settings-sound-tab").click();
        page.locator("#sound-files > summary").click();
        page.locator("#sound-score").fill("unfinished score draft");
        page.locator("#sound-score").press("Escape");
        assertThat(page.locator("#audio-panel").isVisible()).isFalse();
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isEqualTo("normalize");
        assertThat(page.locator("[data-inspector-mode=appearance]").getAttribute("class")).contains("active");
        page.locator("#open-settings").click();
        page.locator("#settings-sound-tab").click();
        assertThat(page.locator("#sound-score").inputValue()).isEqualTo("unfinished score draft");
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(project);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void closingSettingsDoesNotOpenAPreviouslyClosedInspector() {
        page.setDefaultTimeout(5000);
        assertThat(page.locator("#inspector").isHidden()).isTrue();
        final Object camera=cameraCenter();
        page.locator("#open-settings").click();
        awaitScene();
        assertThat(cameraCenter()).isEqualTo(camera);
        assertThat(page.locator("#inspector #audio-panel").isVisible()).isTrue();
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Close Settings").setExact(true)).click();
        assertThat(page.locator("#inspector").isHidden()).isTrue();
        awaitScene();
        assertThat(cameraCenter()).isEqualTo(camera);
        assertThat(page.locator("#open-settings").getAttribute("aria-expanded")).isEqualTo("false");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void aTimedOutExampleIsListedWithoutBlamingItsApplicationOrTrigger() throws Exception {
        final Path gate = directory.resolve("timeout-gate");
        creator.close();
        GeneratedApplicationFixture.installedCatalog(directory, List.of(
                dev.nanonative.railix.core.step.StepDefinition.named("test.gate", "1")
                        .input("root", dev.nanonative.railix.core.step.StepDefinition.Input.json(
                                dev.nanonative.railix.core.value.ValueShape.STRING).defaultValue(RailixValue.string(gate.toString())))
                        .input("case", dev.nanonative.railix.core.step.StepDefinition.Input.json(
                                dev.nanonative.railix.core.value.ValueShape.STRING).defaultValue(RailixValue.string("timeout")))
                        .input("ignore_interrupt", dev.nanonative.railix.core.step.StepDefinition.Input.json(
                                dev.nanonative.railix.core.value.ValueShape.BOOLEAN).defaultValue(RailixValue.bool(false)))
                        .implementation(thirdparty.conformance.ChunkGateStepHandler.class)
        ), thirdparty.conformance.ChunkGateStepHandler.class);
        Files.writeString(directory.resolve("project.json"), """
                {"format":1,"id":"timeout-status","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"timeout","payload":[]}]},
                  {"id":"work","use":"test.gate","inputs":{}}
                ],"links":[{"from":"app.start","to":"command"},
                  {"from":"command.next","to":"work"},{"from":"work.next","to":"end"}]}
                """);
        creator = CreatorServer.start(0, directory.resolve("project.json"), directory.resolve("railix-home"));
        try {
            page.navigate(creator.baseUri().toString());
            page.locator("#issue-count").waitFor(new com.microsoft.playwright.Locator.WaitForOptions().setTimeout(45_000));
            page.locator("#issue-count").click();
            final var problem = page.locator("#attention-list [data-example-issue='command:0']");
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(problem).containsText("30-second");
            problem.click();
            page.waitForFunction("() => document.querySelector('#inspector').dataset.selection==='command'");
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#build-state")).hasText("Running");
            assertThat(page.locator("#visible-example option[value='command:0']").getAttribute("data-status"))
                    .isEqualTo("timed-out");
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("[data-select-node=command]"))
                    .hasAttribute("data-error", "false");
            page.locator("[data-clear-example]").click();
            page.locator("#close-inspector").click();
            page.locator("#zoom-fit").click();
            awaitScene();
            for (final String id : List.of("app", "command")) {
                com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("[data-select-node='"+id+"']"))
                        .hasAttribute("data-error", "false");
            }
            assertThat(pageErrors).isEmpty();
        } finally {
            Files.createDirectories(gate);
            Files.writeString(gate.resolve("release"), "release");
        }
    }

    @Test
    void exampleOptionsFollowRealQueuedRunningAndCompletedWork() throws Exception {
        final Path gate = directory.resolve("example-gate");
        creator.close();
        GeneratedApplicationFixture.installedCatalog(directory, List.of(
                dev.nanonative.railix.core.step.StepDefinition.named("test.gate", "1")
                        .input("root", dev.nanonative.railix.core.step.StepDefinition.Input.json(
                                dev.nanonative.railix.core.value.ValueShape.STRING).defaultValue(RailixValue.string(gate.toString())))
                        .input("case", dev.nanonative.railix.core.step.StepDefinition.Input.json(
                                dev.nanonative.railix.core.value.ValueShape.STRING).defaultValue(RailixValue.string("work")))
                        .input("ignore_interrupt", dev.nanonative.railix.core.step.StepDefinition.Input.json(
                                dev.nanonative.railix.core.value.ValueShape.BOOLEAN).defaultValue(RailixValue.bool(false)))
                        .implementation(thirdparty.conformance.ChunkGateStepHandler.class)
        ), thirdparty.conformance.ChunkGateStepHandler.class);
        final String examples = java.util.stream.IntStream.range(0, 17).mapToObj(index ->
                "{\"name\":\"case-" + index + "\",\"payload\":[]}").collect(java.util.stream.Collectors.joining(","));
        Files.writeString(directory.resolve("project.json"), """
                {"format":1,"id":"status-progress","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[%s]},
                  {"id":"work","use":"test.gate","inputs":{}}
                ],"links":[{"from":"app.start","to":"command"},
                  {"from":"command.next","to":"work"},{"from":"work.next","to":"end"}]}
                """.formatted(examples));
        creator = CreatorServer.start(0, directory.resolve("project.json"), directory.resolve("railix-home"));
        try {
            page.navigate(creator.baseUri().toString());
            page.waitForFunction("() => document.querySelectorAll('#visible-example option[data-status=running]').length > 0"
                    + " && document.querySelectorAll('#visible-example option[data-status=queued]').length > 0");
            final var chooser=page.locator("#visible-example").elementHandle();
            page.locator("#visible-example").click();
            assertThat(chooser.evaluate("element => element.matches(':open')")).isEqualTo(true);
            Files.writeString(gate.resolve("release"), "release");
            page.waitForFunction("() => document.querySelectorAll('#visible-example option[data-status=succeeded]').length===17");
            assertThat(chooser.evaluate("element => element === document.querySelector('#visible-example') && element.matches(':open')"))
                    .isEqualTo(true);
            page.locator("#visible-example").press("Escape");
            assertThat(page.locator("#visible-example option[data-status=running]").count()).isZero();
            assertThat(pageErrors).isEmpty();
        } finally {
            Files.createDirectories(gate);
            Files.writeString(gate.resolve("release"), "release");
        }
    }

    @Test
    void deletingATriggerFromItsExampleEditorReturnsToTheApplication() {
        page.setDefaultTimeout(5000);
        openProject(choiceProject());
        selectWorldNode("command");
        openInspectorTab("examples");
        page.locator("#delete-step").click();
        page.waitForCondition(() -> page.locator("#inspector").getAttribute("data-selection").equals("app"),
                new Page.WaitForConditionOptions().setTimeout(5000));
        assertThat(page.locator("#example-name").count()).isZero();
        openInspectorTab("overview");
        assertThat(page.locator("#add-trigger").isVisible()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void anIssueBadgeOpensTheFailingFieldAndMarksItsTab() {
        page.setDefaultTimeout(5000);
        openProject(attentionProject());
        page.locator("#issue-count").click();
        page.locator("[data-example-issue='command:1']").click();
        page.waitForFunction("() => state.selection.id==='normalize' && state.traceSummary && !state.editorController");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("[data-inspector-mode=inspect]"))
                .hasAttribute("data-has-error", "true");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("[data-field-error]").first())
                .containsText("String");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#preview-source"))
                .hasAttribute("data-value-error", "true");
        assertThat(page.locator("#preview-source").innerText()).contains("null");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void aSuccessfulTriggerExampleStillLinksToItsOtherFailedRun() {
        page.setDefaultTimeout(5000);
        openProject(attentionProject());
        selectWorldNode("command");
        page.locator("#visible-example").selectOption("command:0");
        openInspectorTab("overview");
        page.locator("#selection-overview [data-example-issue='command:1']").click();
        page.waitForFunction("() => state.selection.id==='normalize' && state.exampleIndex===1");
        assertThat(page.locator("#visible-example").inputValue()).isEqualTo("command:1");
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Locate Example trigger").setExact(true)).click();
        page.waitForFunction("() => document.querySelector('#inspector').dataset.selection==='command' && document.querySelector('#graph').dataset.cameraMoving==='false' && document.querySelector('#zoom-level').textContent==='50%'");
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isEqualTo("command");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void exampleStatusesAndEditingHaveOneSelectionSurface() {
        page.setDefaultTimeout(5000);
        openProject(attentionProject());
        selectWorldNode("command");
        page.waitForFunction("() => document.querySelector('#visible-example option[value=\"command:1\"]')?.dataset.status==='rejected'");
        assertThat(page.locator("#visible-example option[value='command:0']").getAttribute("data-status")).isEqualTo("succeeded");
        assertThat(page.locator("#trigger-example select").count()).isZero();
        assertThat(page.locator("#inspector-nav [data-inspector-mode=examples]").count()).isZero();
        page.locator("#visible-example").selectOption("command:0");
        page.locator("#edit-example").click();
        page.locator("#example-name").waitFor();
        assertThat(page.locator("[data-select-example]").count()).isZero();
        assertThat(page.locator("#example-name").inputValue()).isEqualTo("valid-name");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void inspectorCanBeResizedWithPointerAndKeyboardWithoutEditingTheProject() throws Exception {
        page.setDefaultTimeout(5000);
        page.setViewportSize(1440, 900);
        openProject(attentionProject());
        selectWorldNode("normalize");
        final String source = Files.readString(directory.resolve("project.json"));
        final var handle = page.locator("#inspector-resize");
        final var initial = handle.boundingBox();
        page.mouse().move(initial.x + initial.width / 2, initial.y + initial.height / 2);
        page.mouse().down();
        page.mouse().move(initial.x - 140, initial.y + initial.height / 2);
        page.mouse().up();
        assertThat(page.locator("#inspector").boundingBox().width).isGreaterThan(540);
        handle.focus();
        handle.press("Home");
        assertThat(page.locator("#inspector").boundingBox().width).isCloseTo(320, org.assertj.core.data.Offset.offset(1.0));
        handle.press("End");
        assertThat(page.locator("#inspector").boundingBox().width).isLessThanOrEqualTo(1200);
        page.setViewportSize(390, 844);
        assertThat(handle.isVisible()).isFalse();
        assertThat(page.locator("#inspector").boundingBox().width).isEqualTo(390);
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(source);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void aStepSymbolCanBeSearchedSavedAndUndoneWithoutChangingProcessing() throws Exception {
        page.setDefaultTimeout(5000);
        openProject(attentionProject());
        final String source = Files.readString(directory.resolve("project.json"));
        selectWorldNode("normalize");
        openInspectorTab("appearance");
        page.locator("#presentation-symbols > summary").click();
        page.locator("#symbol-search").fill("database");
        page.locator("[data-select-icon=database]").click();
        page.waitForFunction("() => !state.writeActive && !state.pendingWrite && state.creator.steps.normalize?.icon && state.history.undo");
        assertThat(Files.readString(directory.resolve("railix.creator.json"))).contains("\"icon\"");
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(source);
        assertThat(page.locator("[data-select-node=normalize] .world-symbol img").count()).isEqualTo(1);
        page.locator("#editor-undo").click();
        page.waitForFunction("() => !state.actionPending && !state.creator.steps.normalize?.icon");
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(source);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @Tag("responsive")
    @ValueSource(ints={1280,390})
    void aDelayedSelectionCannotEditThePreviouslySelectedStep(final int width) throws Exception {
        page.setViewportSize(width,800);
        openProject(fourStepProject());
        selectWorldNode("one");
        final String source=Files.readString(directory.resolve("project.json"));
        page.evaluate("() => void state.world.focus('two')");
        awaitScene();
        final var held=new ArrayList<Route>();
        page.route("**/api/editor?**",route->{
            if(route.request().url().contains("node=two&"))held.add(route);
            else route.resume();
        });
        page.locator("[data-select-node='two']").click();
        page.waitForCondition(()->!held.isEmpty());
        assertThat(page.locator("#inspector").getAttribute("aria-busy")).isEqualTo("true");
        assertThat(page.locator("#value-0-option").evaluate("element=>Boolean(element.closest('[inert]'))")).isEqualTo(true);
        assertThat(page.locator("#close-inspector").isEnabled()).isTrue();
        page.unroute("**/api/editor?**");
        held.forEach(Route::resume);
        page.waitForFunction("() => state.selection.id==='two' && !state.editorController");
        assertThat(page.locator("#inspector").getAttribute("aria-busy")).isEqualTo("false");
        assertThat(page.locator("#value-0-option").evaluate("element=>Boolean(element.closest('[inert]'))")).isEqualTo(false);
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(source);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void omittedDefaultsAreVisibleWithoutWritingTheProject() throws Exception {
        openProject(defaultedStepProject());
        final String source = Files.readString(directory.resolve("project.json"));
        selectWorldNode("one");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#value-0-option")).hasValue("current");
        assertThat(page.locator("#inspector-content").innerText()).doesNotContain("No candidate configured");
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(source);
        assertThat(page.locator("#editor-undo").isDisabled()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void anOmittedDefaultSourceCanBeEditedAndUndoRestoresItsOmission() throws Exception {
        openProject(defaultedStepProject());
        final String source = Files.readString(directory.resolve("project.json"));
        selectWorldNode("one");
        page.locator("#value-0-option").selectOption("literal");
        waitForText("#build-state", "Built");
        page.locator("#value-0-literal-value").fill("\"HELLO\"");
        page.locator("#value-0-literal-value").press("Tab");
        waitForText("#build-state", "Built");
        page.locator("#visible-example").selectOption("command:0");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#preview-source")).hasText("\"HELLO\"");
        clickAndWaitForCreatorSave(() -> page.locator("#editor-undo").click());
        clickAndWaitForCreatorSave(() -> page.locator("#editor-undo").click());
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(source);
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#value-0-option")).hasValue("current");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void addingAFallbackPreservesTheImplicitDefaultSource() {
        openProject(defaultedStepProject());
        selectWorldNode("one");
        addLiteralCandidate("\"fallback\"");
        waitForText("#build-state", "Built");
        assertThat(page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).project.nodes
                  .find(node => node.id === 'one').inputs.value.map(candidate => candidate.option)
                """)).isEqualTo(List.of("current", "literal"));
        assertThat(page.evaluate("async () => Object.keys((await (await fetch('/api/project')).json()).project.nodes.find(node => node.id === 'one').inputs)"))
                .isEqualTo(List.of("value"));
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void editingADefaultCandidateDoesNotPinItsUneditedChildDefaults() {
        openProject(defaultedStepProject().replace("railix.field-manipulation", "railix.filter")
                .replace("{\"from\":\"one.next\",\"to\":\"end\"}",
                        "{\"from\":\"one.match\",\"to\":\"end\"},{\"from\":\"one.otherwise\",\"to\":\"end\"}"));
        selectWorldNode("one");
        page.locator(".candidate-add > summary").click();
        page.locator("#conditions-candidate-search").fill("literal");
        page.locator("[data-add-candidate='literal']").click();
        final var literal=page.locator("[data-candidate-index='1'] > .candidate-source [data-input-json]");
        literal.fill("true");
        literal.press("Tab");
        waitForText("#build-state", "Built");
        assertThat(page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).project.nodes
                  .find(node => node.id === 'one').inputs.conditions[0].inputs
                """)).isEqualTo(java.util.Map.of());
        assertThat(page.locator("#conditions-0-field-path").innerText()).contains("context", "payload");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void anOmittedProgramCanBeExtendedUsingItsDeclaredDefaultSource() {
        openProject(defaultedStepProject());
        selectWorldNode("one");
        page.waitForFunction("() => document.querySelector('#visible-example option[value=\"command:0\"]') && !document.querySelector('#visible-example').disabled");
        page.locator("#visible-example").selectOption("command:0");
        fillStepSearch("#steps-search", "to json");
        page.locator("[data-program-role='step'][data-add-nested='value.to-json']").click();
        waitForText("#build-state", "Built");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("[data-preview-stage='0']")).containsText("RAILIX");
        assertThat(page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).project.nodes
                  .find(node => node.id === 'one').inputs.steps[0].use
                """)).isEqualTo("value.to-json");
        assertThat(page.evaluate("async () => Object.keys((await (await fetch('/api/project')).json()).project.nodes.find(node => node.id === 'one').inputs)"))
                .isEqualTo(List.of("steps"));
        assertThat(pageErrors).isEmpty();
    }

    static String defaultedStepProject() {
        return """
                {"format":1,"id":"defaulted-step","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"example","payload":[],"context":{"payload":{"value":"RAILIX"}}}]},
                  {"id":"one","use":"railix.field-manipulation","inputs":{}}
                ],"links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"one"},{"from":"one.next","to":"end"}]}
                """;
    }

    @Test
    void anImportedConstructorIdKeepsPresentationEditsLocalAndUndoable() throws Exception {
        openProject(defaultedStepProject().replace("\"one\"","\"constructor\"").replace("one.next","constructor.next"));
        selectWorldNode("constructor");
        final String defaultName = page.locator("#inspector-identity h2").innerText();
        openInspectorTab("appearance");
        page.locator("#presentation-name").fill("Imported processor");
        final var saved=page.waitForResponse(response->response.url().endsWith("/api/editor")
                && response.request().method().equals("POST"),()->page.locator("#presentation-name").press("Tab"));
        assertThat(saved.status()).isEqualTo(200);
        page.waitForFunction("() => !state.writeActive && state.history.undo");
        assertThat(Files.readString(directory.resolve("railix.creator.json"))).contains("\"constructor\"", "Imported processor");
        page.reload();
        selectWorldNode("constructor");
        assertThat(page.locator("#inspector-identity h2").innerText()).isEqualTo("Imported processor");
        selectWorldNode("command");
        assertThat(page.locator("#inspector-identity h2").innerText()).isNotEqualTo("Imported processor");
        page.locator("#editor-undo").click();
        page.waitForFunction("() => !state.actionPending && state.history.redo");
        selectWorldNode("constructor");
        assertThat(page.locator("#inspector-identity h2").innerText()).isEqualTo(defaultName);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void authoredOutputsAreConnectableAndPrototypeNamedOutcomesUseTheirOwnLabels() throws Exception {
        final String source=nestedSwitchProject().replace("\"outcome\":\"one\"","\"outcome\":\"constructor\"")
                .replace("switch.one","switch.constructor");
        openProject(source);
        assertThat(page.evaluate("""
                async()=> (await fetch('/api/creator',{method:'POST',headers:mutationHeaders(),body:JSON.stringify({format:2,
                  groups:[],steps:{switch:{outcomes:{two:'Backup'}}}})})).status
                """)).isEqualTo(200);
        page.reload();
        selectWorldNode("switch");
        final var output=page.getByRole(com.microsoft.playwright.options.AriaRole.COMBOBOX,
                new Page.GetByRoleOptions().setName("Choose output").setExact(true));
        output.waitFor();
        assertThat(output.locator("option").allTextContents()).containsExactly("Choose output","Constructor","Backup","Three","Four","Otherwise");
        assertThat(page.locator("#inspector-content [data-candidate-label]").first().inputValue()).isEmpty();
        final String saved = Files.readString(directory.resolve("project.json"));
        output.selectOption("switch.constructor");
        assertThat(page.locator(".connection-status").isVisible()).isTrue();
        page.locator("[data-cancel-connection]").click();
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(saved);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void groupAssignmentKeepsKeyboardFocusInsideAndReturnsToItsButton() {
        openProject(fourStepProject());
        selectWorldNode("one");
        openInspectorTab("appearance");
        page.locator("#choose-group").click();
        final var cancel = page.locator("#close-group-picker");
        final var last = page.locator("#group-picker-options button").last();
        cancel.focus();
        page.keyboard().press("Shift+Tab");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(last).isFocused();
        page.keyboard().press("Tab");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(cancel).isFocused();
        page.keyboard().press("Escape");
        assertThat(page.locator(".group-picker").count()).isZero();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#choose-group")).isFocused();
        assertThat(page.locator("#editor-undo").isDisabled()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void selectionUsesOneHeaderAndAnAlignedConnectionPanel() {
        openProject(choiceProject());
        selectWorldNode("choice");
        page.locator("#inspector-identity h2").waitFor();
        final String name = page.locator("#inspector-identity h2").innerText();
        for (final String mode : List.of("inspect", "overview", "appearance", "groups")) {
            openInspectorTab(mode);
            assertThat(page.locator("#inspector-identity h2").innerText()).isEqualTo(name);
            assertThat(page.locator("#inspector-content > .inspector-heading").count()).isZero();
        }
        assertThat(page.locator("#graph [data-output-port]").count()).isZero();
        openInspectorTab("inspect");
        final var outputs = page.locator("#inspector-connections [data-output-port]");
        assertThat(outputs.count()).isEqualTo(2);
        assertThat(outputs.nth(0).boundingBox().y).isEqualTo(outputs.nth(1).boundingBox().y);
        page.locator("#visible-example").selectOption("command:0");
        page.waitForFunction("""
                () => {const source=document.querySelector('.candidate-source');
                  const mode=source?.querySelector(':scope > select')?.getBoundingClientRect();
                  const path=source?.querySelector('.path-button')?.getBoundingClientRect();
                  return mode?.width>0 && path?.width>0 && Math.abs(mode.y-path.y)<=1;}
                """);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void branchConnectionControlsStayAlignedAndOfferExplicitCancellation() {
        openProject(choiceProject());
        selectWorldNode("choice");
        final var match = page.locator("[data-output-port='choice.match']").boundingBox();
        final var otherwise = page.locator("[data-output-port='choice.otherwise']").boundingBox();
        assertThat(match.width).isGreaterThanOrEqualTo(44);
        assertThat(otherwise.y).isEqualTo(match.y);
        page.locator("[data-output-port='choice.match']").click();
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Cancel").setExact(true)).click();
        assertThat(page.locator(".connection-status").isHidden()).isTrue();
        assertThat(page.locator("#editor-undo").isDisabled()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void touchWorkbenchCanEnterAndLeaveAGroupWithoutEditingIt() throws Exception {
        final String url = page.url();
        final var browser = context.browser();
        context.close();
        context = browser.newContext(new com.microsoft.playwright.Browser.NewContextOptions()
                .setViewportSize(390, 844).setHasTouch(true).setIsMobile(true));
        page = context.newPage();
        page.onPageError(pageErrors::add);
        page.navigate(url);
        awaitScene();
        openProject(fourStepProject());
        page.evaluate("""
                async () => fetch('/api/creator',{method:'POST',headers:mutationHeaders(),body:JSON.stringify({
                  format:2,groups:[{id:'work',name:'Work'}],steps:{one:{group:'work'},two:{group:'work'}}})})
                """);
        page.reload();
        awaitScene();
        page.locator("[data-group-region-label]").first().tap();
        page.locator("#group-return:visible").waitFor();
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        assertThat(page.locator("#world-location").innerText()).contains("Work");
        page.locator("#group-return").tap();
        page.waitForCondition(() -> page.locator("#group-return:visible").count() == 0);
        assertThat(page.locator("#editor-undo").isDisabled()).isTrue();
        assertThat(page.locator("#editor-redo").isDisabled()).isTrue();
        final String zoom = page.locator("#zoom-level").innerText();
        final var cdp = context.newCDPSession(page);
        try {
            cdp.send("Input.dispatchTouchEvent", com.google.gson.JsonParser.parseString("""
                    {"type":"touchStart","touchPoints":[{"id":1,"x":150,"y":350},{"id":2,"x":230,"y":350}]}
                    """).getAsJsonObject());
            cdp.send("Input.dispatchTouchEvent", com.google.gson.JsonParser.parseString("""
                    {"type":"touchMove","touchPoints":[{"id":1,"x":110,"y":350},{"id":2,"x":270,"y":350}]}
                    """).getAsJsonObject());
            cdp.send("Input.dispatchTouchEvent", com.google.gson.JsonParser.parseString("""
                    {"type":"touchEnd","touchPoints":[]}
                    """).getAsJsonObject());
            page.waitForCondition(() -> !page.locator("#zoom-level").innerText().equals(zoom));
            assertThat(page.evaluate("window.visualViewport.scale")).isEqualTo(1);
        } finally {
            cdp.detach();
        }
        for (final String selector : List.of("#open-settings", "#toggle-music", "#editor-undo", "#zoom-in")) {
            assertThat(page.locator(selector).boundingBox().height).as(selector).isGreaterThanOrEqualTo(44);
        }
        page.locator("#open-settings").tap();
        page.locator("#settings-sound-tab").tap();
        page.locator("#sound-files > summary").tap();
        page.locator("#sound-defaults").scrollIntoViewIfNeeded();
        final var settings = page.locator("#audio-panel").boundingBox();
        assertThat(settings.y+settings.height).isLessThanOrEqualTo(844-48);
        final var install = page.locator("#sound-defaults").boundingBox();
        assertThat(install.y+install.height).isLessThanOrEqualTo(settings.y+settings.height);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void failedExampleScopeWaitsForAnExplicitRetry() {
        openProject(choiceProject());
        final var reads = new java.util.concurrent.atomic.AtomicInteger();
        page.route("**/api/scene/observations?**", route -> {
            if (route.request().url().contains("cases=")) {
                reads.incrementAndGet();
                route.fulfill(new Route.FulfillOptions().setStatus(503).setContentType("application/json").setBody("{}"));
            } else route.resume();
        });
        selectWorldNode("matched");
        page.locator("[data-retry-example-scope]").waitFor();
        final int failed = reads.get();
        for (int i=0;i<3;i++) page.waitForResponse(response -> response.url().endsWith("/api/examples/status"), () -> {});
        assertThat(reads.get()).isEqualTo(failed);
        page.unroute("**/api/scene/observations?**");
        page.locator("[data-retry-example-scope]").click();
        page.waitForCondition(() -> page.locator("#visible-example option[value='command:0']:not([disabled])").count()==1);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void examplePickerOnlyOffersCasesThatReachTheSelectedStep() {
        openProject(choiceProject());
        selectWorldNode("matched");
        page.waitForCondition(() -> page.locator("#visible-example option[value='command:0']").count() == 1
                && page.locator("#visible-example option[value='command:1']").count() == 0);
        assertThat(page.locator("#example-session").innerText()).doesNotContain("reached", "(command)");
        page.locator("#visible-example").selectOption("command:0");
        selectWorldNode("otherwise");
        page.waitForCondition(() -> page.locator("#visible-example option[value='command:1']").count() == 1
                && page.locator("#visible-example option[value='command:0']:not([disabled])").count() == 0);
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#visible-example")).hasValue("command:0");
        assertThat(page.locator("#visible-example option[value='command:0']").isDisabled()).isTrue();
        selectWorldNode("command");
        assertThat(page.locator("#visible-example option").all().stream().map(option->option.getAttribute("value")).toList())
                .containsExactly("", "command:0", "command:1");
        assertThat(page.locator("#editor-undo").isDisabled()).isTrue();
        assertThat(page.locator("#editor-redo").isDisabled()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void locationAndExampleShareTheToolbarWithoutCoveringTheCanvas() {
        openProject(attentionProject());
        page.locator("#issue-count").click();
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Inspect Example: invalid-name").setExact(true)).click();
        page.locator("#visible-example").waitFor();
        for (final int width : List.of(1440, 430, 320)) {
            page.setViewportSize(width, 900);
            final var header = page.locator(".workbench-header").boundingBox();
            final var graph = page.locator("#graph").boundingBox();
            for (final String selector : List.of("#group-navigation", "#example-session", "#open-settings", ".build-indicator")) {
                final var control = page.locator(selector).boundingBox();
                assertThat(control.x).as(selector + " left at " + width).isGreaterThanOrEqualTo(0);
                assertThat(control.x + control.width).as(selector + " right at " + width).isLessThanOrEqualTo(width);
                assertThat(control.y + control.height).as(selector + " inside toolbar").isLessThanOrEqualTo(header.height);
            }
            assertThat(graph.y).isGreaterThanOrEqualTo(header.height);
            assertThat(((Number) page.locator(".inspector-tabs button").first().evaluate(
                    "element => parseFloat(getComputedStyle(element).fontSize)")).doubleValue()).isGreaterThanOrEqualTo(13);
        }
        page.locator("#visible-example").selectOption("command:0");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#visible-example"))
                .hasValue("command:0");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void workbenchKeepsSecondaryNumbersOnTheSelection() {
        openProject(fourStepProject());
        assertThat(page.locator("[data-world-id]:not(.selected) > .world-detail").allTextContents())
                .allMatch(String::isBlank);
        assertThat(page.locator("[data-world-id]:not(.selected) > .world-duration:visible").count()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void workbenchControlsUseOneQuietMaterialAndKeepRuntimeDetailsAvailable() {
        openProject(fourStepProject());
        assertThat(page.locator("#open-settings").evaluate("element => getComputedStyle(element).boxShadow"))
                .isEqualTo("none");
        assertThat(page.locator(".status-rail").innerText()).doesNotContain("PID", "Heap", "Last build");
        page.locator(".build-indicator").click();
        page.locator("#runtime-summary > summary").click();
        page.locator("#status-memory").waitFor();
        assertThat(page.locator("#status-memory").innerText()).startsWith("Heap ");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void outcomeLabelsDoNotRepeatAnIdenticallyNamedDestination() {
        openProject(choiceProject());
        assertThat(page.evaluate("""
                async () => (await fetch('/api/creator', {method:'POST',headers:mutationHeaders(),body:JSON.stringify({
                  format:2,groups:[],steps:{matched:{name:'Match'},otherwise:{name:'Otherwise'}}})})).status
                """)).isEqualTo(200);
        page.reload();
        awaitScene();
        assertThat(page.locator(".world-link-label[data-branch-outcome]").count()).isZero();
        selectWorldNode("choice");
        assertThat(page.locator("[data-output-port='choice.match']").isVisible()).isTrue();
        assertThat(page.locator("[data-output-port='choice.otherwise']").isVisible()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void workbenchDocksItsInspectorWithoutCoveringTheFlow() {
        openProject(attentionProject());
        selectWorldNode("normalize");
        final var graph = page.locator("#graph").boundingBox();
        final var inspector = page.locator("#inspector").boundingBox();
        assertThat(inspector.x).isGreaterThanOrEqualTo(graph.x + graph.width);
        assertThat(page.locator("#world-location").isVisible()).isTrue();
        assertThat(page.locator("#world-location").innerText()).contains("attention-journey");
        assertThat(page.locator("#toggle-world-view").count()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void aValuePipelineShowsItsExistingWorkBeforeOfferingMoreOptions() {
        openProject(attentionProject());
        selectWorldNode("normalize");
        openInspectorTab("inspect");
        assertThat(page.locator("[data-step-options] button:visible").count()).isZero();
        assertThat(page.locator(".candidate-condition").getAttribute("open")).isNull();
        assertThat(page.locator(".pipeline-stage h3").allTextContents()).containsExactly("Source", "Process", "Destination");
        page.locator("#steps-add > summary").click();
        assertThat(page.locator("#steps-search").isVisible()).isTrue();
        page.locator("#steps-search").fill("uppercase");
        page.locator("[data-add-nested='text.uppercase']").first().waitFor();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void recordedExampleIssueLeadsToItsStepAndDisappearsAfterARealEdit() throws Exception {
        openProject(attentionProject());
        final Path evidence = Files.createDirectories(Path.of("target", "creator-attention"));
        page.screenshot(new Page.ScreenshotOptions().setPath(evidence.resolve("before.png")));
        page.locator("#issue-count").click();
        final var issue = page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Inspect Example: invalid-name").setExact(true));
        issue.waitFor(new com.microsoft.playwright.Locator.WaitForOptions().setTimeout(5_000));
        page.screenshot(new Page.ScreenshotOptions().setPath(evidence.resolve("problem-list.png")));
        issue.click();
        page.waitForFunction("() => document.querySelector('#inspector').dataset.selection === 'normalize'");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#visible-example"))
                .hasValue("command:1");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#inspector-content .issue-message"))
                .containsText("null (expected String)");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#preview-error"))
                .isEmpty();
        page.locator("#world-labels [data-select-node='normalize']:not(.world-icon-only)").waitFor();
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving === 'false'");
        assertThat(page.locator("#zoom-level").innerText()).isEqualTo("50%");
        page.screenshot(new Page.ScreenshotOptions().setPath(evidence.resolve("failed-example.png")));
        page.locator("[data-remove-nested]").first().click();
        page.waitForFunction("() => state.build === 'Built' && state.application.examples?.state === 'completed'"
                + " && Number(state.application.examples.failed) === 0 && !state.writeActive");
        page.locator(".build-indicator").click();
        assertThat(page.locator("#attention-list").count()).isZero();
        assertThat(page.locator("#issue-count").isHidden()).isTrue();
        page.screenshot(new Page.ScreenshotOptions().setPath(evidence.resolve("fixed-example.png")));
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Close build details").setExact(true)).click();
        page.locator("#editor-undo").click();
        page.waitForFunction("() => state.build === 'Built' && state.application.examples?.state === 'completed'"
                + " && Number(state.application.examples.failed) === 1 && !state.actionPending");
        page.locator("#issue-count").click();
        issue.waitFor();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void locationBreadcrumbReturnsToThePreviousCameraAndKeepsTheExample() {
        openProject(fourStepProject());
        final String group = createGroup("one", "two");
        chooseVisibleExample("command:0");
        page.locator("#zoom-fit").click();
        awaitScene();
        page.locator("#scope-overview").click();
        awaitScene();
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving === 'false'");
        final var previous = page.locator("#world-labels [data-region-group='" + group + "']").first().boundingBox();
        page.locator("[data-region-group='" + group + "']").first().click();
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Back to four-steps").setExact(true)).click();
        page.waitForFunction("() => !state.world.scene?.inside && document.querySelector('#graph').dataset.cameraMoving === 'false'");
        page.waitForFunction("args => { const element = document.querySelector('#world-labels [data-region-group=\"' + args[0] + '\"]');"
                + " if (!element) return false; const bounds = element.getBoundingClientRect();"
                + " return Math.abs(bounds.x-args[1]) < 1 && Math.abs(bounds.y-args[2]) < 1; }",
                List.of(group, previous.x, previous.y));
        assertThat(page.locator("#visible-example").inputValue()).isEqualTo("command:0");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void breadcrumbNavigationSupersedesAnUnfinishedSceneFocus() {
        openProject(attentionProject());
        final String group = createGroup("normalize", "result");
        page.locator("#zoom-fit").click();
        awaitScene();
        page.locator("#scope-overview").click();
        awaitScene();
        page.locator("[data-region-group='" + group + "']").first().click();
        page.waitForFunction("() => state.world.scene?.inside && document.querySelector('#graph').dataset.cameraMoving === 'false'");
        final var held = new ArrayList<Route>();
        page.route("**/api/scene?**", route -> {
            if (route.request().url().contains("focus=normalize")) held.add(route);
            else route.resume();
        });
        page.locator("#issue-count").click();
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Inspect Example: invalid-name").setExact(true)).click();
        page.waitForCondition(() -> !held.isEmpty());
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Back to attention-journey").setExact(true)).click();
        page.unroute("**/api/scene?**");
        held.forEach(Route::resume);
        page.waitForFunction("() => !state.world.scene?.inside && !state.world.navigation.length"
                + " && !new URLSearchParams(state.world.query).has('focus')");
        assertThat(page.locator("#inspector").isHidden()).isTrue();
        assertThat(page.locator("#visible-example").inputValue()).isEqualTo("command:1");
        assertThat(pageErrors).isEmpty();
    }

    static String attentionProject() {
        return """
                {"format":1,"id":"attention-journey","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"valid-name","payload":[],"context":{"payload":{"name":"  RAILIX  "}}},
                    {"name":"invalid-name","payload":[],"context":{"payload":{"name":null}}}]},
                  {"id":"normalize","use":"railix.field-manipulation","inputs":{
                    "field":["context","payload","name"],"value":[{"option":"current","inputs":{}}],
                    "steps":[{"use":"text.normalize-space","inputs":{}}]}},
                  {"id":"result","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],"value":[{"option":"field","inputs":{"source":["context","payload","name"]}}],"steps":[]}}
                ],"links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"normalize"},
                  {"from":"normalize.next","to":"result"},{"from":"result.next","to":"end"}]}
                """;
    }

    @ParameterizedTest
    @ValueSource(ints={320,390,1280})
    void anExampleErrorUsesTheOperationNameAndLeadsToItsSettingWithoutChangingTheFlow(final int width) {
        page.setViewportSize(width,width==320?720:900);
        openProject(attentionProject());
        assertThat(page.evaluate("""
                async ()=>(await fetch('/api/creator',{method:'POST',headers:mutationHeaders(),body:JSON.stringify({
                  format:2,groups:[{id:'work',name:'Work'}],steps:{normalize:{group:'work'},result:{group:'work'}}})})).status
                """)).isEqualTo(200);
        page.reload();
        awaitScene();
        page.locator("#issue-count").click();
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Inspect Example: invalid-name").setExact(true)).click();
        final var issue = page.locator("#inspector-content .issues article").first();
        issue.waitFor();
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving==='false' && document.querySelector('#zoom-level').textContent==='50%'");
        if(width<650)page.waitForFunction("() => {const step=document.querySelector('[data-select-node=normalize]').getBoundingClientRect(),legend=document.querySelector('#example-time-scale').getBoundingClientRect();return !legend.width || step.right<=legend.left || step.left>=legend.right || step.bottom<=legend.top || step.top>=legend.bottom;}");
        if(width<650)page.waitForFunction("() => {const back=document.querySelector('#group-return').getBoundingClientRect(),legend=document.querySelector('#example-time-scale').getBoundingClientRect();return !back.width || !legend.width || back.right<=legend.left || back.left>=legend.right || back.bottom<=legend.top || back.top>=legend.bottom;}");
        final var station=page.locator("[data-select-node='normalize']").boundingBox();
        if(width<650)assertThat(station.y+station.height).as("Selected Step stays above the mobile Inspector").isLessThanOrEqualTo(page.locator("#inspector").boundingBox().y);
        if(width>=650) {
            page.setViewportSize(390,900);
            page.waitForFunction("() => {const step=document.querySelector('[data-select-node=normalize]')?.getBoundingClientRect(),panel=document.querySelector('#inspector').getBoundingClientRect();return step && step.bottom<=panel.top && document.querySelector('#graph').dataset.cameraMoving==='false';}");
            assertThat(page.locator("#zoom-level").textContent()).isEqualTo("50%");
        }
        assertThat(issue.locator(".issue-message").innerText()).contains("null", "String")
                .doesNotContain("Nested Step", "text.normalize-space");
        final var fieldError=page.locator(".nested-step > [data-field-error]").first().boundingBox();
        final var fieldRow=page.locator(".nested-step:has(> [data-field-error])").first().boundingBox();
        assertThat(fieldError.width).as("The field error has its own full-width row")
                .isGreaterThanOrEqualTo(fieldRow.width*.8);
        assertThat(issue.locator("details").getAttribute("open")).isNull();
        assertThat(issue.locator("details").textContent()).contains("RUN_NESTED_INPUT_INCOMPATIBLE", "nodes[2].inputs.steps[0]");
        issue.locator("summary").click();
        for(int poll=0;poll<2;poll++)page.waitForResponse(response->response.url().endsWith("/api/application"),()->{});
        assertThat(issue.locator("details").getAttribute("open")).isNotNull();
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Show setting").setExact(true)).click();
        assertThat(page.evaluate("() => document.activeElement?.dataset.settingPath"))
                .isEqualTo("nodes[2].inputs.steps[0]");
        assertThat(page.locator("#inspector-nav [data-inspector-mode] > span").allTextContents())
                .contains("Configure", "Data", "Appearance", "Grouping");
        assertThat(page.locator("#editor-undo").isDisabled()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void aTraceReadFailureDoesNotDisappearBehindAnExistingExampleError() {
        openProject(attentionProject());
        page.locator("#issue-count").click();
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Inspect Example: invalid-name").setExact(true)).click();
        page.locator("#inspector-content .issue-message").waitFor();
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving==='false'");
        page.route("**/api/examples/steps/*", route -> route.abort());
        selectWorldNode("normalize");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#preview-error"))
                .not().isEmpty();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#inspector-content .issue-message"))
                .containsText("Normalize Space");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void anUnavailableRecordedExampleCanBeRetriedWithoutAutomaticReadLoops() {
        openProject(attentionProject());
        final var reads = new ArrayList<String>();
        final var polls = new ArrayList<String>();
        page.route("**/api/examples/*/view", route -> { reads.add(route.request().url()); route.abort(); });
        page.onResponse(response -> { if (response.url().endsWith("/api/examples/status")) polls.add(response.url()); });
        page.locator("#issue-count").click();
        final var issue = page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Inspect Example: invalid-name").setExact(true));
        issue.click();
        page.waitForFunction("() => document.querySelector('#inspector').dataset.selection==='normalize' && !state.traceController");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#inspector-content .issue-message"))
                .containsText("Normalize Space");
        final int failedReads = reads.size(), completedPolls = polls.size();
        page.waitForCondition(() -> polls.size() >= completedPolls + 3);
        assertThat(reads).hasSize(failedReads);
        page.unroute("**/api/examples/*/view");
        page.locator("[data-retry-example]").click();
        page.waitForFunction("() => Boolean(state.traceSummary) && !state.traceReadError");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void closingProblemsCancelsALateExampleNavigation() {
        openProject(attentionProject());
        final var held = new ArrayList<Route>();
        final String request = "**/api/editor?node=normalize&**";
        page.route(request, held::add);
        page.locator("#issue-count").click();
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Inspect Example: invalid-name").setExact(true)).click();
        page.waitForCondition(() -> !held.isEmpty());
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Close problems").setExact(true)).click();
        page.unroute(request);
        held.forEach(Route::resume);
        page.waitForFunction("() => !state.editorController");
        assertThat(page.locator("#inspector").isHidden()).isTrue();
        selectWorldNode("result");
        page.waitForFunction("() => !state.traceController && state.selection.id === 'result'");
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isEqualTo("result");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void manyFailuresStayNavigableWithTheKeyboardInANarrowWindow() throws Exception {
        page.setViewportSize(430, 860);
        final String failures = String.join(",", java.util.stream.IntStream.range(0, 26)
                .mapToObj(index -> "{\"name\":\"invalid-" + index + "\",\"payload\":[],\"context\":{\"payload\":{\"name\":null}}}")
                .toList());
        openProject(attentionProject().replace(
                "{\"name\":\"invalid-name\",\"payload\":[],\"context\":{\"payload\":{\"name\":null}}}", failures));
        page.locator("#issue-count").click();
        page.waitForFunction("() => document.querySelector('#issue-count').textContent === '26'");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("[data-example-issue]"))
                .hasCount(20);
        final var panel = page.locator("#application-status").boundingBox();
        assertThat(panel.x).isGreaterThanOrEqualTo(0);
        assertThat(panel.y + panel.height).isLessThanOrEqualTo(860);
        final var next = page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Next").setExact(true));
        next.focus();
        page.keyboard().press("Enter");
        assertThat(page.locator("[data-example-issue]").count()).isEqualTo(6);
        assertThat(page.locator("[data-example-issue]").first().evaluate("element => element === document.activeElement"))
                .isEqualTo(true);
        assertThat(page.locator("#issue-count").getAttribute("aria-label")).contains("26 issues");
        assertThat(page.locator(".build-indicator").getAttribute("aria-label")).doesNotContain("issues");
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(
                Path.of("target", "creator-attention")).resolve("many-failures-narrow.png")));
        final var issue = page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Inspect Example: invalid-25").setExact(true));
        issue.focus();
        page.keyboard().press("Enter");
        page.waitForFunction("() => document.querySelector('#inspector').dataset.selection === 'normalize'");
        assertThat(page.locator("#visible-example").inputValue()).isEqualTo("command:26");
        page.locator("#inspector-content").hover();
        page.mouse().wheel(0, 800);
        page.waitForFunction("() => document.querySelector('#inspector-content').scrollTop > 0");
        page.locator("#issue-count").click();
        next.click();
        issue.click();
        page.waitForFunction("() => document.activeElement?.dataset.settingPath === 'nodes[2].inputs.steps[0]'");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void closingProblemsAlsoCancelsAnEditorReadRetriedAfterASave() {
        page.clock().install();
        openProject(attentionProject());
        page.locator("#issue-count").click();
        page.locator("[data-example-issue]").waitFor();
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Close problems").setExact(true)).click();
        selectWorldNode("result");
        openInspectorTab("appearance");
        final var held = new ArrayList<Route>();
        page.route("**/api/editor?node=normalize&**", held::add);
        page.clock().pauseAt(((Number) page.evaluate("Date.now()")).longValue() + 10);
        // Hold only the debounce, not the real save or editor requests.
        page.evaluate("""
                async () => {
                  const name = document.querySelector('#presentation-name');
                  name.value = 'Renamed normalizer';
                  name.dispatchEvent(new Event('change', {bubbles:true}));
                  document.querySelector('#close-inspector').click();
                  document.querySelector('#issue-count').click();
                  await Promise.resolve();
                  document.querySelector('[data-example-issue]').click();
                }
                """);
        page.waitForCondition(() -> held.size() == 1);
        page.clock().runFor(121);
        page.waitForCondition(() -> (Boolean) page.evaluate(
                "() => !state.writeActive && state.savedCreator.steps.result?.name === 'Renamed normalizer'"));
        held.getFirst().resume();
        page.waitForCondition(() -> held.size() == 2);
        page.clock().resume();
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Close problems").setExact(true)).click();
        page.unroute("**/api/editor?node=normalize&**");
        held.getLast().resume();
        page.waitForFunction("() => !state.editorController");
        assertThat(page.locator("#inspector").isHidden()).isTrue();
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isEqualTo("result");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void undoOfAnInsertedStepDoesNotLeaveItsGroupInError() {
        openProject(fourStepProject());
        final String group = createGroup("one", "two");
        page.locator("[data-region-group='" + group + "']").first().click();
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving === 'false'");
        selectWorldNode("one");
        openInspectorTab("overview");
        page.locator("#add-next-step").click();
        page.locator("[data-add-step='railix.field-manipulation']").click();
        page.waitForFunction("() => state.build === 'Built' && !state.writeActive && !state.pendingProject");
        page.locator("#editor-undo").click();
        page.waitForFunction("() => !state.actionPending && state.history.redo");
        page.locator("#close-inspector").click();
        page.locator("#leave-group").click();
        final var region = page.locator("#world-labels [data-region-group='" + group + "']").first();
        region.waitFor();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(region)
                .hasAttribute("data-error", "false");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void arrivingExampleEvidenceKeepsTheGroupNamingFormVisible() throws Exception {
        openProject(CreatorServerE2eSupport.threeStepProject());
        chooseVisibleExample("command:0");
        final var held = new ArrayList<Route>();
        page.route("**/api/examples/steps/**", held::add);
        selectWorldNode("one");
        page.locator("[data-inspector-mode=overview]").click();
        page.waitForCondition(() -> !held.isEmpty());
        page.locator("[data-group-selection]").click();
        page.locator("#group-draft input").fill("Still naming this group");
        page.unroute("**/api/examples/steps/**");
        held.forEach(Route::resume);
        page.waitForFunction("() => state.traceStep?.id === 'one'");
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(
                Path.of("target/review-stability")).resolve("group-naming-live.png")));
        assertThat(page.locator("#group-draft input").inputValue()).isEqualTo("Still naming this group");
        assertThat(page.locator("#group-draft").evaluate("form => { const panel = document.querySelector('#inspector-content').getBoundingClientRect(); const bounds = form.getBoundingClientRect(); return bounds.top >= panel.top && bounds.bottom <= panel.bottom; }"))
                .as("Group form geometry: %s", page.locator("#group-draft").evaluate("form => {const content=document.querySelector('#inspector-content');return {form:form.getBoundingClientRect().toJSON(),panel:content.getBoundingClientRect().toJSON(),scroll:content.scrollTop};}"))
                .isEqualTo(true);
        page.locator("#group-draft input").press("Escape");
        assertThat(page.locator("#group-draft").count()).isZero();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#selection-evidence header small"))
                .hasText("Observed changes");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"before-edit", "older-first", "newer-first", "hud"})
    void anEarlyPreferenceChangePreservesTheLoadedSettingsAndCanBeSaved(final String order) throws Exception {
        final boolean hud = order.equals("hud"), overlapping = order.endsWith("first");
        final Path settings = directory.resolve("railix-home/creator.settings.json");
        Files.writeString(settings, Files.readString(settings).replace("\"music_volume\":0.25", "\"music_volume\":0.73"));
        final var held = new ArrayList<Route>();
        final var snapshots = new ArrayList<com.microsoft.playwright.APIResponse>();
        page.route("**/api/settings", route -> {
            if (route.request().method().equals("GET")) {
                snapshots.add(route.fetch());
                held.add(route);
            } else route.resume();
        });
        page.reload();
        waitForText("#build-state", "Built");
        page.waitForCondition(() -> !held.isEmpty());
        page.locator("#open-settings").click();
        page.waitForCondition(() -> held.size() >= 2);
        if (hud) {
            page.locator("#close-settings").click();
            page.locator("#toggle-effects").click();
        } else page.locator("#reduced-motion").check();
        page.waitForFunction("() => document.querySelector('#settings-status').textContent.includes('not loaded')");
        if (overlapping) {
            page.locator("#close-settings").click();
            page.locator("#open-settings").click();
            page.waitForCondition(() -> held.size() >= 3);
        }
        page.unroute("**/api/settings");
        final var sequence = !overlapping ? List.of(0, 1)
                : order.equals("older-first") ? List.of(0, 1, 2) : List.of(2, 0, 1);
        for (final int index : sequence) {
            held.get(index).fulfill(new Route.FulfillOptions().setResponse(snapshots.get(index)));
            snapshots.get(index).dispose();
            if (index == 0 && order.equals("older-first")) {
                page.waitForCondition(() -> Boolean.TRUE.equals(page.evaluate(
                        "async () => (await (await fetch('/api/settings')).json()).values.reduced_motion")),
                        new Page.WaitForConditionOptions().setTimeout(5000));
            }
        }
        page.waitForCondition(() -> Boolean.TRUE.equals(page.evaluate(
                "async hud => {const settings=(await (await fetch('/api/settings')).json()).values;return hud ? settings.effects===false : settings.reduced_motion;}", hud)),
                new Page.WaitForConditionOptions().setTimeout(5000));
        assertThat(page.evaluate("async () => (await (await fetch('/api/settings')).json()).values.music_volume"))
                .isEqualTo(0.73);
        if (hud) com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#toggle-effects"))
                .hasAttribute("aria-pressed", "false");
        else com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#reduced-motion")).isChecked();
        page.waitForFunction("() => !state.settingsTimer && !state.settingsWriting");
        final var dialogs = new java.util.concurrent.atomic.AtomicInteger();
        page.onDialog(dialog -> { dialogs.incrementAndGet(); dialog.dismiss(); });
        page.reload();
        waitForText("#build-state", "Built");
        assertThat(dialogs.get()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void aMusicPreferenceConflictRemainsVisibleOutsideTheAppearanceTab() throws Exception {
        page.setViewportSize(390,844);
        page.locator("#open-settings").click();
        page.waitForFunction("() => state.settingsRevision !== null && !state.themeController?.signal.aborted && state.themes.length > 0");
        page.locator("#settings-music-tab").click();
        final Path settings=directory.resolve("railix-home/creator.settings.json");
        final String external=Files.readString(settings).replace("\"music_volume\":0.25","\"music_volume\":0.73");
        Files.writeString(settings,external);
        final var response=page.waitForResponse(value->value.url().endsWith("/api/settings")
                        && value.request().method().equals("POST"),()->{
            page.locator("#music-volume").focus();
            page.keyboard().press("End");
        });
        assertThat(response.status()).isEqualTo(409);
        final var status=page.locator("#settings-status");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(status).containsText("Settings changed");
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(
                Path.of("target/creator-audit")).resolve("settings-conflict.png")));
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(status).isVisible();
        assertThat(Files.readString(settings)).isEqualTo(external);
        page.locator("#settings-sound-tab").click();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(status).isVisible();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void aDeepPathRemainsSelectableOnAShortScreen() throws Exception {
        openProject(choiceProject().replace("\"value\":\"allow\"", """
                "value":"allow","customer_account":{"delivery_request":{"shipping_preferences":{
                "address_details":{"regional_settings":{"contact_details":{"name":"Ada"}}}}}}
                """));
        page.locator("#visible-example").selectOption("command:0");
        selectWorldNode("matched");
        openInspectorTab("inspect");
        page.waitForFunction("() => state.traceStep?.id === 'matched'");
        page.setViewportSize(320, 360);
        page.locator("#field-path").click();
        page.locator("[data-path-depth='0']").click();
        for (final String part : List.of("payload", "customer_account", "delivery_request", "shipping_preferences",
                "address_details", "regional_settings", "contact_details", "name")) {
            page.locator("[data-path-part='" + part + "']").click();
        }
        page.locator("#apply-path").click();
        waitForText("#build-state", "Built");
        assertThat(page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).project.nodes
                  .find(node => node.id==='matched').inputs.field
                """)).isEqualTo(List.of("context", "payload", "customer_account", "delivery_request", "shipping_preferences",
                "address_details", "regional_settings", "contact_details", "name"));
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(
                Path.of("target/review-stability")).resolve("path-short-screen.png")));
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @Tag("responsive")
    @ValueSource(strings = {"pending", "writing", "failed"})
    void cancellingReloadPreservesUnsavedSettings(final String stage) {
        page.locator("#open-settings").click();
        page.waitForFunction("() => state.settingsRevision !== null");
        final var held = new ArrayList<Route>();
        if (!stage.equals("pending")) {
            page.route("**/api/settings", route -> {
                if (!route.request().method().equals("POST")) route.resume();
                else if (stage.equals("writing")) held.add(route);
                else route.fulfill(new Route.FulfillOptions().setStatus(503).setBody("{}"));
            });
        }
        final var dialogs = new java.util.concurrent.atomic.AtomicInteger();
        page.onDialog(dialog -> {
            assertThat(dialog.type()).isEqualTo("beforeunload");
            dialogs.incrementAndGet();
            dialog.dismiss();
        });
        if (stage.equals("pending")) {
            page.evaluate("() => {document.querySelector('#reduced-motion').click(); location.reload();}");
        } else {
            page.locator("#reduced-motion").check();
            if (stage.equals("writing")) page.waitForCondition(() -> !held.isEmpty());
            else page.waitForFunction("() => document.querySelector('#settings-status').textContent.includes('could not be saved')");
            page.evaluate("() => {setTimeout(() => location.reload(),0);}");
        }
        try {
            page.waitForCondition(() -> dialogs.get() == 1, new Page.WaitForConditionOptions().setTimeout(5000));
            assertThat(page.locator("#reduced-motion").isChecked()).isTrue();
        } finally {
            page.unroute("**/api/settings");
            held.forEach(Route::resume);
        }
        page.waitForFunction("() => !state.settingsWriting");
        page.locator("#reduced-motion").uncheck();
        page.waitForFunction("() => !state.settingsTimer && !state.settingsWriting");
        assertThat(page.evaluate("async () => (await (await fetch('/api/settings')).json()).values.reduced_motion"))
                .isEqualTo(false);
        page.reload();
        waitForText("#build-state", "Built");
        assertThat(dialogs.get()).isEqualTo(1);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @Tag("responsive")
    @ValueSource(booleans = {false, true})
    void newEvidenceCannotMoveAPathCreationActionUnderThePointer(final boolean index) {
        final String source = index ? choiceProject()
                .replace("\"field\":[\"context\",\"result\"]", "\"field\":[\"context\",\"payload\",\"items\"]")
                .replace("\"value\":\"allow\"", "\"value\":\"allow\",\"items\":[1,2]")
                : choiceProject();
        openProject(source);
        page.locator("#visible-example").selectOption("command:0");
        final var held = new ArrayList<Route>();
        page.route("**/api/examples/steps/**", held::add);
        selectWorldNode("matched");
        openInspectorTab("inspect");
        page.waitForCondition(() -> !held.isEmpty());
        page.locator("#field-path").click();
        if (!index) page.locator("[data-path-depth='0']").click();
        final var input = page.locator(index ? "#new-path-index" : "#new-path-field");
        input.fill(index ? "2" : "local_field");
        final var action = page.locator(index ? "#append-path-index" : "#append-path-field");
        action.scrollIntoViewIfNeeded();
        final var before = action.boundingBox();
        page.mouse().move(before.x + before.width / 2, before.y + before.height / 2);
        page.unroute("**/api/examples/steps/**");
        held.forEach(Route::resume);
        page.waitForFunction("() => state.traceStep?.id === 'matched'");
        page.locator(index ? "[data-path-part='[0]']" : "[data-path-part='payload']").waitFor();
        assertThat(action.boundingBox().y).isCloseTo(before.y, within(1d));
        assertThat(input.inputValue()).isEqualTo(index ? "2" : "local_field");
        page.mouse().click(before.x + before.width / 2, before.y + before.height / 2);
        final var last = page.locator(".path-crumb").last();
        assertThat(last.textContent().trim()).isEqualTo(index ? "[2]" : "local_field");
        page.locator("#apply-path").click();
        waitForText("#build-state", "Built");
        assertThat(page.evaluate("""
                async () => JSON.stringify((await (await fetch('/api/project')).json()).project.nodes
                  .find(node => node.id==='matched').inputs.field)
                """)).isEqualTo(index ? "[\"context\",\"payload\",\"items\",2]" : "[\"context\",\"local_field\"]");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @Tag("responsive")
    @ValueSource(booleans = {true, false})
    void refreshingExamplesCannotRemoveAnIntermediatePathChoice(final boolean pointer) {
        openProject("""
                {"format":1,"id":"typed-path","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"text","payload":[],"context":{"payload":{"text":"Hello RAILIX"}}}]},
                  {"id":"lower","use":"text.lowercase","inputs":{},
                   "receives":{"value":["context","payload","text"]},"returns":{"value":["context","result"]}}
                ],"links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"lower"},
                           {"from":"lower.ok","to":"end"}]}
                """);
        page.locator("#visible-example").selectOption("command:0");
        selectWorldNode("lower");
        openInspectorTab("inspect");
        page.waitForFunction("() => state.traceStep?.id === 'lower'");
        delayNextProjectWrite();
        page.locator("#node-metrics").uncheck();
        page.waitForFunction("() => window.__railixProjectWriteStarted");
        page.locator("#receives-value-path").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='payload']").click();
        assertThat(page.locator("#apply-path").isDisabled()).isTrue();
        final var choice = page.locator("[data-path-part='text']");
        final var before = choice.boundingBox();
        final var held = new ArrayList<Route>();
        page.route("**/api/examples/steps/**", held::add);
        if (pointer) {
            page.mouse().move(before.x + before.width / 2, before.y + before.height / 2);
            page.mouse().down();
        } else choice.focus();
        try {
            page.evaluate("() => window.__railixReleaseProjectWrite()");
            waitForText("#build-state", "Built");
            page.waitForCondition(() -> !held.isEmpty());
            assertThat(choice.isVisible()).isTrue();
            assertThat(choice.boundingBox().y).isCloseTo(before.y, within(1d));
            if (!pointer) {
                assertThat(page.locator(":focus").getAttribute("data-path-part")).isEqualTo("text");
                page.keyboard().press("Enter");
            }
        } finally {
            if (pointer) page.mouse().up();
            page.unroute("**/api/examples/steps/**");
            held.forEach(Route::resume);
        }
        page.locator("[data-path-depth='2']").waitFor();
        assertThat(page.locator("[data-path-depth='2']").textContent().trim()).isEqualTo("text");
        page.locator("#apply-path").click();
        waitForText("#build-state", "Built");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @Tag("responsive")
    @ValueSource(booleans = {false, true})
    void initialExampleEvidenceEnablesThePathPickerWithoutLosingKeyboardFocus(final boolean root) {
        openProject(choiceProject().replace("\"option\":\"literal\",\"inputs\":{\"literal\":\"matched\"}",
                "\"option\":\"field\",\"inputs\":{\"source\":[\"context\",\"payload\"]}"));
        page.locator("#visible-example").selectOption("command:0");
        final var held = new ArrayList<Route>();
        page.route("**/api/examples/steps/**", held::add);
        selectWorldNode("matched");
        openInspectorTab("inspect");
        page.waitForCondition(() -> !held.isEmpty());
        page.locator("#value-0-source-path").click();
        if (root) page.locator("[data-path-depth='0']").click();
        final var focused = root ? page.locator("[data-path-part='result']") : page.locator("#cancel-path");
        focused.focus();
        final var before = focused.boundingBox();
        assertThat(page.locator("#apply-path").isDisabled()).isTrue();
        page.unroute("**/api/examples/steps/**");
        held.forEach(Route::resume);
        page.waitForFunction("() => !document.querySelector('#apply-path').disabled", null,
                new Page.WaitForFunctionOptions().setTimeout(5000));
        assertThat(focused.evaluate("element => element === document.activeElement")).isEqualTo(true);
        if (root) {
            assertThat(focused.boundingBox().y).isCloseTo(before.y, within(1d));
            page.locator("[data-path-part='payload']").click();
        }
        assertThat(page.locator("[data-path-part='value']").isVisible()).isTrue();
        page.locator("[data-path-part='value']").click();
        page.locator("#apply-path").click();
        waitForText("#build-state", "Built");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @Tag("responsive")
    @ValueSource(booleans = {true, false})
    void refreshingExamplesCannotCancelAnInProgressPathChoice(final boolean pointer) throws Exception {
        openProject("""
                {"format":1,"id":"stable-path","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"text","payload":[],"context":{"payload":{"text":"Hello RAILIX","other":"unchanged"}}}]},
                  {"id":"result","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],"value":[{"option":"literal","inputs":{"literal":"initial"}}],"steps":[]}}
                ],"links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"result"},
                           {"from":"result.next","to":"end"}]}
                """);
        page.locator("#visible-example").selectOption("command:0");
        selectWorldNode("result");
        openInspectorTab("inspect");
        page.waitForFunction("() => state.traceStep?.id === 'result'");
        delayNextProjectWrite();
        page.locator("#value-0-option").selectOption("field");
        page.waitForFunction("() => window.__railixProjectWriteStarted");
        page.locator("#value-0-source-path").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='payload']").click();
        page.locator("[data-path-part='text']").click();
        final var held = new ArrayList<Route>();
        page.route("**/api/examples/steps/**", held::add);
        final var button = page.locator("#apply-path");
        button.scrollIntoViewIfNeeded();
        final var before = button.boundingBox();
        if (pointer) {
            page.mouse().move(before.x + before.width / 2, before.y + before.height / 2);
            page.mouse().down();
        } else button.focus();
        try {
            page.evaluate("() => window.__railixReleaseProjectWrite()");
            waitForText("#build-state", "Built");
            page.waitForCondition(() -> !held.isEmpty());
            final var after = button.boundingBox();
            assertThat(after.x).isCloseTo(before.x, within(1d));
            assertThat(after.y).isCloseTo(before.y, within(1d));
            assertThat(button.isEnabled()).isTrue();
            page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(
                    Path.of("target/review-stability")).resolve("path-picker-" + pointer + "-" + page.viewportSize().width + ".png")));
            if (!pointer) page.keyboard().press("Enter");
        } finally {
            if (pointer) page.mouse().up();
            page.unroute("**/api/examples/steps/**");
            held.forEach(Route::resume);
        }
        page.locator(".path-browser").waitFor(new com.microsoft.playwright.Locator.WaitForOptions()
                .setState(com.microsoft.playwright.options.WaitForSelectorState.HIDDEN));
        waitForText("#build-state", "Built");
        assertThat(page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).project.nodes
                  .find(node => node.id==='result').inputs.value[0].inputs.source
                """)).isEqualTo(List.of("context", "payload", "text"));
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @Tag("responsive")
    @ValueSource(strings = {"sound", "music"})
    void changingTheScoreFileRequiresExplicitDiscardOfTheCurrentDraft(final String kind) {
        page.locator("#open-settings").click();
        page.locator("#settings-" + kind + "-tab").click();
        page.locator("#sound-files > summary").click();
        page.waitForFunction("() => document.querySelector('#sound-file').options.length > 1");
        page.locator("#sound-file").selectOption("");
        page.locator("#sound-score").fill("unsaved score");
        final String other = page.locator("#sound-file option").first().getAttribute("value");
        final var dialogs = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.function.Consumer<com.microsoft.playwright.Dialog> cancel = dialog -> {
            assertThat(dialog.type()).isEqualTo("confirm");
            dialogs.incrementAndGet();
            dialog.dismiss();
        };
        page.onDialog(cancel);
        page.locator("#sound-file").selectOption(other);
        assertThat(dialogs.get()).isEqualTo(1);
        assertThat(page.locator("#sound-file").inputValue()).isEmpty();
        assertThat(page.locator("#sound-score").inputValue()).isEqualTo("unsaved score");
        page.offDialog(cancel);
        page.onDialog(com.microsoft.playwright.Dialog::accept);
        page.locator("#sound-file").selectOption(other);
        assertThat(page.locator("#sound-file").inputValue()).isEqualTo(other);
        assertThat(page.locator("#sound-score").inputValue()).isNotEqualTo("unsaved score");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @Tag("responsive")
    @ValueSource(strings = {"sound", "music"})
    void cancellingReloadPreservesTheUnsavedScore(final String kind) throws Exception {
        page.locator("#open-settings").click();
        page.locator("#settings-" + kind + "-tab").click();
        page.locator("#sound-files > summary").click();
        page.waitForFunction("() => document.querySelector('#sound-file').options.length > 1");
        page.locator("#sound-file").selectOption("");
        final var score = page.locator("#sound-score");
        score.fill("unfinished local score");
        final var dialogs = new java.util.concurrent.atomic.AtomicInteger();
        page.onDialog(dialog -> {
            assertThat(dialog.type()).isEqualTo("beforeunload");
            dialogs.incrementAndGet();
            dialog.dismiss();
        });
        page.evaluate("() => {setTimeout(() => location.reload(), 0);}");
        page.waitForCondition(() -> dialogs.get() == 1, new Page.WaitForConditionOptions().setTimeout(5000));
        assertThat(score.inputValue()).isEqualTo("unfinished local score");
        final String id = (kind.equals("music") ? "music/" : "sounds/") + "reload-test.mml";
        final String saved = "name: Saved score\ntempo: 120\nsine .2 .01 .1 | o4 c e g e";
        page.locator("#sound-id").fill(id);
        score.fill(saved);
        page.locator("#sound-save").click();
        page.waitForFunction("id => document.querySelector('#sound-file').value==='local:'+id", id);
        page.waitForFunction("() => !state.audio.mutation && !state.audio.dirtyEditor");
        assertThat(Files.readString(directory.resolve("railix-home").resolve(id))).isEqualTo(saved);
        page.reload();
        waitForText("#build-state", "Built");
        assertThat(dialogs.get()).isEqualTo(1);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @Tag("responsive")
    @ValueSource(strings = {"pending", "failed", "invalid", "metadata", "example"})
    void cancellingReloadPreservesUnsavedWorkAndTheRunningEditor(final String change) throws Exception {
        openProject(choiceProject());
        final boolean example = change.equals("example"), metadata = change.equals("metadata");
        selectWorldNode(example ? "command" : "matched");
        if (example) openInspectorTab("examples");
        if (metadata) openInspectorTab("appearance");
        final String selector = example ? "#example-payload" : metadata ? "#presentation-name" : "#value-0-literal-value";
        final var field = page.locator(selector);
        final var held = new ArrayList<Route>();
        if (change.equals("pending") || change.equals("failed") || metadata) {
            page.route("**/api/editor", route -> {
                if (route.request().method().equals("POST")) {
                    if (change.equals("pending")) held.add(route);
                    else route.fulfill(new Route.FulfillOptions().setStatus(503).setBody("{}"));
                }
                else route.resume();
            });
        }
        final String draft = change.equals("invalid") || example ? "[" : metadata ? "Unsaved name" : "\"Unsaved value\"";
        field.fill(draft);
        field.press("Tab");
        if (change.equals("pending")) page.waitForCondition(() -> !held.isEmpty());
        else if (example) page.locator(".issues article").filter(new com.microsoft.playwright.Locator.FilterOptions()
                .setHasText("PROJECT_TRIGGER_EXAMPLE_PAYLOAD_INVALID")).waitFor();
        else waitForText("#build-state", "Not built");
        final var dialogs = new java.util.concurrent.atomic.AtomicInteger();
        page.onDialog(dialog -> {
            assertThat(dialog.type()).isEqualTo("beforeunload");
            dialogs.incrementAndGet();
            dialog.dismiss();
        });
        page.evaluate("() => window.reloadMarker='still editing'");
        page.evaluate("() => {setTimeout(() => location.reload(), 0);}");
        page.waitForCondition(() -> dialogs.get() == 1);
        try {
            assertThat(dialogs.get()).isEqualTo(1);
            assertThat(page.evaluate("() => window.reloadMarker")).isEqualTo("still editing");
            assertThat(field.inputValue()).isEqualTo(draft);
            assertThat(page.evaluate("() => state.world._disposed")).isNotEqualTo(true);
            page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(
                    Path.of("target/review-stability")).resolve("unsaved-" + change + "-" + page.viewportSize().width + ".png")));
        } finally {
            page.unroute("**/api/editor");
            held.forEach(Route::resume);
        }
        final String saved = example ? "[]" : metadata ? "Saved name" : "\"Saved value\"";
        field.fill(saved);
        field.press("Tab");
        waitForText("#build-state", "Built");
        page.waitForFunction("() => !state.writeActive");
        page.reload();
        waitForText("#build-state", "Built");
        assertThat(dialogs.get()).isEqualTo(1);
        selectWorldNode(example ? "command" : "matched");
        if (example) openInspectorTab("examples");
        if (metadata) openInspectorTab("appearance");
        assertThat(field.inputValue()).isEqualTo(saved);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void selectingAnExampleKeepsTheSharedStepsSourceInSync(final boolean pendingSelection) throws Exception {
        openProject("""
                {"format":1,"id":"shared-input","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"cli","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"command","payload":[]}]},
                  {"id":"http","use":"railix.trigger.http","inputs":{"port":0},"examples":[{"name":"request","payload":{"method":"GET","path":"/"}}]},
                  {"id":"shared","use":"railix.field-manipulation","inputs":{}},
                  {"id":"later","use":"railix.field-manipulation","inputs":{}}],
                  "links":[{"from":"app.start","to":"cli"},{"from":"app.start","to":"http"},
                    {"from":"cli.next","to":"shared"},{"from":"http.next","to":"shared"},
                    {"from":"shared.next","to":"later"},{"from":"later.next","to":"end"}]}
                """);
        selectWorldNode("shared");
        openInspectorTab("overview");
        assertThat(page.locator("#visible-example").inputValue()).isEmpty();
        assertThat(page.locator("[data-observation-source]").inputValue()).isEqualTo("cli");
        page.locator("#visible-example").selectOption("cli:0");
        assertThat(page.locator("[data-observation-source]").count()).isZero();
        final var held = new ArrayList<com.microsoft.playwright.Route>();
        if (pendingSelection) {
            page.evaluate("()=>{state.world.focus('later');}");
            awaitScene();
            page.route("**/api/editor?node=later&**", held::add);
            page.locator("[data-world-id='later']").click();
            page.waitForCondition(() -> !held.isEmpty());
        }
        page.locator("#visible-example").selectOption("http:0");
        if (pendingSelection) {
            page.unroute("**/api/editor?node=later&**");
            held.forEach(com.microsoft.playwright.Route::resume);
            page.waitForFunction("() => document.querySelector('#inspector').dataset.selection==='later'");
            openInspectorTab("overview");
        }
        page.waitForFunction("() => document.querySelector('#visible-example')?.value==='http:0'");
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target/review-final")).resolve("example-source.png")));
        page.locator("#visible-example").selectOption("cli:0");
        page.waitForFunction("() => document.querySelector('#visible-example')?.value==='cli:0'");
        assertThat(page.locator("[data-observation-source]").count()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void refusedCanvasContextReleasesNewAssetsAndRetainsTheWorkingTheme() {
        openProject(fourStepProject());
        page.evaluate("async()=>{await applyTheme();}");
        page.evaluate("""
                () => {
                  window.releasedThemeBitmaps=0;
                  window.createdThemeBitmaps=0;
                  const create=window.createImageBitmap;
                  window.createImageBitmap=async(...args)=>{const bitmap=await create(...args);window.createdThemeBitmaps++;return bitmap;};
                  const close=ImageBitmap.prototype.close;
                  ImageBitmap.prototype.close=function(){window.releasedThemeBitmaps++;return close.call(this);};
                  const context=HTMLCanvasElement.prototype.getContext;
                  HTMLCanvasElement.prototype.getContext=function(...args){
                    return this.className==='world-raster' && this!==window.workingCanvas ? null : context.apply(this,args);
                  };
                  window.workingCanvas=document.querySelector('.world-raster');
                }
                """);
        page.locator("#open-settings").click();
        selectTheme("classic/theme.css");
        page.waitForFunction("() => state.themeError.includes('Canvas2D is unavailable')");
        assertThat(page.evaluate("() => document.querySelector('.world-raster')===window.workingCanvas")).isEqualTo(true);
        assertThat(((Number) page.evaluate("() => window.createdThemeBitmaps")).intValue()).isPositive();
        assertThat(page.evaluate("() => window.releasedThemeBitmaps")).isEqualTo(page.evaluate("() => window.createdThemeBitmaps"));
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void invalidLocalDefaultAtlasCannotDisableTheEmbeddedRenderer() throws Exception {
        openProject(fourStepProject());
        final Path atlas = directory.resolve("railix-home/themes/foundry/variants/canvas/atlas.json");
        Files.createDirectories(atlas.getParent());
        Files.writeString(atlas, "{\"version\":0}");
        page.reload();
        waitForText("#build-state", "Built");
        page.waitForFunction("() => state.themeError.includes('Invalid Canvas atlas')");
        page.waitForFunction("() => Number(document.querySelector('.world-raster')?.dataset.stations)>0");
        assertThat(Files.readString(atlas)).isEqualTo("{\"version\":0}");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void disposedRendererCannotReportALateAssetFailureOnItsReplacement() {
        openProject(fourStepProject());
        page.evaluate("async()=>{await applyTheme();}");
        final var held = new ArrayList<com.microsoft.playwright.Route>();
        page.route("**/themes/foundry/variants/canvas/atlas.json", held::add);
        page.evaluate("() => {state.world.dispose();window.retiredWorld=new RailixWorld(document.querySelector('#graph'));}");
        page.waitForCondition(() -> !held.isEmpty());
        page.unroute("**/themes/foundry/variants/canvas/atlas.json");
        page.evaluate("async()=>{window.retiredWorld.dispose();state.world=new RailixWorld(document.querySelector('#graph'));await state.world.refresh();}");
        page.waitForFunction("() => Number(document.querySelector('.world-raster')?.dataset.stations)>0");
        held.forEach(route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions().setStatus(503)));
        page.evaluate("async()=>{await window.retiredWorld._rendererReady;}");
        assertThat(page.locator("#world-error").isVisible()).isFalse();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void failedInitialThemeAndFallbackReportAnErrorWithoutAnUnhandledRejection() {
        openProject(fourStepProject());
        page.route("**/api/themes", route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions().setStatus(500)));
        page.route("**/api/themes/files/**", route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions().setStatus(404)));
        assertThat(page.evaluate("""
                async () => {
                  document.querySelectorAll('link[data-theme-loaded]').forEach(link=>link.remove());
                  await applyTheme();
                  return state.themeError;
                }
                """).toString()).isNotBlank();
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"canvas"})
    void directConnectionsPreviewCancelAndUndoWithoutMixingFlows(final String renderer) throws Exception {
        openProject("""
                {"format":1,"id":"connected-work","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"a","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"first","payload":[]}]},
                  {"id":"b","use":"railix.trigger.http","inputs":{"port":0},"examples":[{"name":"second","payload":{"method":"GET","path":"/"}}]},
                  {"id":"one","use":"railix.field-manipulation","inputs":{}},
                  {"id":"two","use":"railix.field-manipulation","inputs":{}}],
                 "links":[{"from":"app.start","to":"a"},{"from":"app.start","to":"b"},
                  {"from":"a.next","to":"one"},{"from":"b.next","to":"two"},
                  {"from":"one.next","to":"end"},{"from":"two.next","to":"end"}]}
                """);
        page.locator("#open-settings").click();
        page.getByLabel("Renderer", new Page.GetByLabelOptions().setExact(true)).selectOption(renderer);
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Close Settings").setExact(true)).click();
        selectWorldNode("one");
        page.evaluate("() => void state.world.fit()");
        awaitScene();
        final String source = Files.readString(directory.resolve("project.json"));
        page.locator("[data-output-port='one.next']").click();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator(".connection-status")).isVisible();
        final var destination = page.locator("[data-select-node='two']").boundingBox();
        page.mouse().move(destination.x + destination.width / 2, destination.y + destination.height / 2);
        assertThat(page.locator(".connection-preview path").evaluate("path => path.getBBox().height > 100")).isEqualTo(true);
        page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target", "connection-preview-" + renderer + ".png")));
        page.keyboard().press("Escape");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator(".connection-preview")).isHidden();
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(source);
        page.locator("[data-output-port='one.next']").click();
        page.locator("[data-select-node='two']").click();
        page.waitForFunction("() => !state.actionPending && state.history.undo");
        assertThat(((RailixValue.ArrayValue) CreatorServerE2eSupport.object(Files.readString(directory.resolve("project.json"))).values().get("links")).values())
                .contains(CreatorServerE2eSupport.object("{\"from\":\"one.next\",\"to\":\"two\"}"));
        page.locator("#editor-undo").click();
        page.waitForFunction("() => !state.actionPending && state.history.redo");
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(source);
        page.locator("[data-output-port='one.next']").focus();
        page.keyboard().press("Enter");
        page.locator("[data-select-node='two']").focus();
        page.keyboard().press("Enter");
        page.waitForFunction("() => !state.actionPending && state.history.undo && !state.history.redo");
        page.keyboard().press("ControlOrMeta+z");
        page.waitForFunction("() => !state.actionPending && state.history.redo");
        page.locator("[data-output-port='one.next']").dragTo(page.locator("[data-select-node='two']"));
        page.waitForFunction("() => !state.actionPending && state.history.undo && !state.history.redo");
        assertThat(((RailixValue.ArrayValue) CreatorServerE2eSupport.object(Files.readString(directory.resolve("project.json"))).values().get("links")).values())
                .contains(CreatorServerE2eSupport.object("{\"from\":\"one.next\",\"to\":\"two\"}"));
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {1280, 390})
    void groupedSelectionAndReusablePickerAreCompleteEditorActions(final int width) throws Exception {
        page.setViewportSize(width, 800);
        openProject(CreatorServerE2eSupport.threeStepProject());
        selectWorldNode("one");
        page.locator("[data-inspector-mode=overview]").click();
        if(width == 1280) {
            page.locator("#close-inspector").click();
            page.evaluate("() => void state.world.fit()");
            awaitScene();
            page.locator("[data-select-node=three]").click(new com.microsoft.playwright.Locator.ClickOptions()
                    .setModifiers(List.of(com.microsoft.playwright.options.KeyboardModifier.SHIFT)));
        }
        page.locator("[data-group-selection]").click();
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create group").setExact(true)).waitFor();
        if(width == 1280) {
            page.locator("[data-group-range]").click();
            page.waitForFunction("() => state.groupDraft?.nodes.length === 3 && !state.actionPending");
        }
        page.locator("#group-draft input").fill("Normalize");
        page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target", "group-selection-" + width + ".png")));
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create group").setExact(true)).click();
        page.waitForFunction("() => !state.actionPending && state.history.undo");
        selectWorldNode("three");
        page.locator("[data-inspector-mode=overview]").click();
        page.locator("#add-next-step").click();
        page.locator("#step-search").fill("one");
        page.locator("[data-reuse-item=one][data-reuse-type=step]").click();
        page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target", "reuse-picker-" + width + ".png")));
        delayNextProjectWrite();
        page.locator("[data-apply-reuse=reuse]").click();
        page.waitForFunction("() => window.__railixProjectWriteStarted");
        assertThat(page.evaluate("() => document.querySelector('#inspector').inert && document.querySelector('#overlay').inert")).isEqualTo(true);
        page.evaluate("() => window.__railixReleaseProjectWrite()");
        page.waitForFunction("() => !state.actionPending && state.editor.reuse?.count === 2");
        assertThat(page.locator(":focus").getAttribute("id")).isEqualTo("dock-focus");
        assertThat(page.locator("[data-linked-use] option").allTextContents()).doesNotHaveDuplicates();
        assertThat(Files.readString(directory.resolve("railix.creator.json"))).contains("\"reuse\"");
        page.locator("[data-detach-use]").click();
        page.waitForFunction("() => !state.actionPending && state.editor.reuse?.count === 0");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {1280, 390, 320})
    void groupDraftOpensInTheVisibleInspector(final int width) {
        page.setViewportSize(width, 720);
        openProject(CreatorServerE2eSupport.threeStepProject());
        selectWorldNode("one");
        page.locator("[data-inspector-mode=overview]").click();
        page.locator("[data-group-selection]").click();
        page.locator("#group-draft input").waitFor();
        assertThat(page.locator("#group-draft input").evaluate("input => input === document.activeElement")).isEqualTo(true);
        assertThat(page.locator("#group-draft").evaluate("form => { const panel = document.querySelector('#inspector-content').getBoundingClientRect(); const bounds = form.getBoundingClientRect(); return bounds.top >= panel.top && bounds.bottom <= panel.bottom; }"))
                .as("Group form geometry: %s", page.locator("#group-draft").evaluate("form => {const content=document.querySelector('#inspector-content');return {form:form.getBoundingClientRect().toJSON(),panel:content.getBoundingClientRect().toJSON(),scroll:content.scrollTop};}"))
                .isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void groupDraftFollowsSelectionAndRestoresFocusAfterCreate() throws Exception {
        openProject(CreatorServerE2eSupport.threeStepProject());
        selectWorldNode("one");
        page.locator("[data-inspector-mode=overview]").click();
        page.locator("[data-group-selection]").click();
        page.locator("#group-draft input").press("Escape");
        assertThat(page.locator("#group-draft").count()).isZero();
        assertThat(page.locator(":focus").getAttribute("data-group-selection")).isNotNull();

        page.locator("[data-group-selection]").click();
        selectWorldNode("three");
        page.locator("[data-inspector-mode=overview]").click();
        assertThat(page.locator("#group-draft").count()).isZero();
        page.locator("[data-group-selection]").click();
        page.locator("#group-draft input").fill("Third step");
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Create group").setExact(true)).click();
        page.waitForFunction("() => !state.actionPending && state.creator.groups.some(group => group.name === 'Third step')");
        assertThat(page.locator("#inspector").getAttribute("aria-busy")).isEqualTo("false");
        assertThat(page.locator(":focus").getAttribute("id")).isEqualTo("dock-focus");
        final var creator = CreatorServerE2eSupport.object(Files.readString(directory.resolve("railix.creator.json")));
        final var styles = (RailixValue.ObjectValue) creator.values().get("steps");
        assertThat(styles.values()).doesNotContainKey("one");
        assertThat(((RailixValue.ObjectValue) styles.values().get("three")).values()).containsKey("group");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @Tag("responsive")
    @ValueSource(ints={360,720})
    void narrowInspectorTabsKeepTheirCompleteLabels(final int height) {
        page.setViewportSize(320, height);
        openProject(CreatorServerE2eSupport.threeStepProject());
        selectWorldNode("one");
        assertThat(page.locator("#inspector-nav .inspector-tabs button").evaluateAll(
                "tabs => { const nav=document.querySelector('#inspector-nav').getBoundingClientRect(); return tabs.every(tab => {const box=tab.getBoundingClientRect();return tab.scrollWidth<=tab.clientWidth && box.left>=nav.left && box.right<=nav.right && box.width>=44 && box.height>=44;}); }")).isEqualTo(true);
        page.locator("[data-inspector-mode=appearance]").click();
        assertThat(page.locator("[data-inspector-mode=appearance]").evaluate(
                "tab => { const bounds = tab.getBoundingClientRect(); const nav = document.querySelector('#inspector-nav').getBoundingClientRect(); return bounds.left >= nav.left && bounds.right <= nav.right; }")).isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {1280, 390, 320})
    void insertionPickerSeparatesStepAndGroupSearchAndRestoresFocus(final int width) {
        page.setViewportSize(width, 720);
        openProject(CreatorServerE2eSupport.threeStepProject());
        selectWorldNode("one");
        page.locator("[data-inspector-mode=overview]").click();
        page.locator("#add-next-step").click();
        assertThat(page.locator("#group-insert-search").count()).isZero();
        page.locator("#step-search").press("Escape");
        assertThat(page.locator(".construction-palette").count()).isZero();
        assertThat(page.locator(":focus").getAttribute("id")).isEqualTo("add-next-step");

        page.locator("[data-group-selection]").click();
        page.locator("#group-draft input").fill("Shared work");
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Create group").setExact(true)).click();
        page.waitForFunction("() => !state.actionPending && state.creator.groups.some(group => group.name === 'Shared work')");
        selectWorldNode("three");
        page.locator("[data-inspector-mode=overview]").click();
        page.locator("#add-next-step").click();
        assertThat(page.locator("#step-search").isVisible()).isTrue();
        assertThat(page.locator("#group-insert-search").isVisible()).isTrue();
        final double stepSearchY = page.locator("#step-search").boundingBox().y;
        page.locator("#group-insert-search").focus();
        assertThat(page.locator("#step-search").boundingBox().y).isCloseTo(stepSearchY, within(1.0));
        page.locator("#group-insert-search").fill("Shared");
        page.locator("[data-reuse-type=group]").click();
        assertThat(page.locator("[data-apply-reuse]").count()).isEqualTo(2);
        page.locator("[data-cancel-reuse]").click();
        assertThat(page.locator(".construction-palette").isVisible()).isTrue();
        assertThat(page.locator(":focus").getAttribute("id")).isEqualTo("group-insert-search");
        page.locator("#step-search").fill("field manipulation");
        assertThat(page.locator("[data-add-step='railix.field-manipulation']").count()).isEqualTo(1);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"canvas", "hq"})
    @Tag("responsive")
    void addingAStepShowsItsMappingsAndActualExampleResult(final String renderer) throws Exception {
        page.locator("#open-settings").click();
        page.locator("#theme-variant").selectOption(renderer);
        page.waitForFunction("() => !state.settingsWriting && !state.settingsTimer");
        page.locator("[aria-label='Close Settings']").click();
        prepareTextPayloadTrigger();
        final String endpoint = "**/api/project";
        final List<Route> pending = new ArrayList<>();
        page.route(endpoint, route -> {
            if (route.request().method().equals("PATCH")) pending.add(route);
            else route.resume();
        });
        try {
            clickOverview("#add-next-step");
            page.locator("#step-search").fill("lowercase");
            page.locator("[data-add-step='text.lowercase']").click();
            page.waitForCondition(() -> !pending.isEmpty());
            page.locator("[data-input-name=source] [data-open-path]").click();
            assertThat(page.locator("#change-status").textContent()).contains("Applying changes");
            pending.removeFirst().resume();
            waitForText("#build-state", "Built");
            assertThat(page.locator(".path-browser").count()).isEqualTo(1);
            assertThat(page.locator(".path-create").count()).isZero();
            page.locator("[data-path-depth='1']").click();
            assertThat(page.locator("#apply-path").isDisabled()).isTrue();
            assertThat(page.locator("#change-status").count()).isZero();
            page.locator("#cancel-path").click();
        } finally {
            pending.forEach(Route::abort);
            page.unroute(endpoint);
        }

        assertThat(page.locator("[data-inspector-mode=inspect]").getAttribute("class")).contains("active");
        assertThat(page.locator("[data-input-name=source]").textContent()).contains("Read from", "payload", "text");
        assertThat(page.locator("[data-input-name=target]").textContent())
                .contains("Store value at", "Replaces the source field");
        page.waitForFunction("() => document.querySelector('[data-preview-output=value]')?.textContent === '\"railix\"'");
        assertThat(page.locator("[data-preview-input-value=value]").textContent()).isEqualTo("\"RAILIX\"");
        assertThat(page.locator("#graph").getAttribute("data-renderer")).isEqualTo(renderer.equals("hq") ? "css" : "canvas");
        final var inspector = page.locator("#inspector").boundingBox();
        assertThat(inspector.x).isGreaterThanOrEqualTo(0);
        assertThat(inspector.x + inspector.width).isLessThanOrEqualTo((double) page.viewportSize().width);
        page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setPath(
                Files.createDirectories(Path.of("target", "screenshots")).resolve(
                        "step-creation-" + renderer + "-" + page.viewportSize().width + ".png")));
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void anUnreachedFilterCanConfigureAReadPathWithoutInventingExampleValues() {
        final String id = addUnreachedFilter();
        waitForText("[data-input-name='field'] .path-values", "Not reached");
        page.locator("#conditions-0-field-path").click();
        page.locator("#new-path-field").fill("request");
        page.locator("#append-path-field").click();
        page.locator("#new-path-field").fill("path");
        page.locator("#append-path-field").click();

        assertThat(page.locator(".path-preview").textContent())
                .contains("This Example did not reach this Step", "Path not verified by an Example");
        assertThat(page.locator(".path-preview output").count()).isZero();
        assertThat(page.locator("#apply-path").isEnabled()).isTrue();
        page.locator("#apply-path").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async id => (await (await fetch('/api/project')).json()).project.nodes
                  .find(node => node.id === id).inputs.conditions[0].inputs.field
                """, id)).isEqualTo(List.of("context", "payload", "request", "path"));
        page.reload();
        waitForText("#build-state", "Built");
        selectWorldNode(id);
        assertThat(page.locator("#conditions-0-field-path").textContent()).contains("request", "path");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void resultsForAnUnreachedFilterDoNotInventValues() {
        addUnreachedFilter();
        waitForText("[data-input-name='field'] .path-values", "Not reached");
        openInspectorTab("results");

        assertThat(page.locator("#example-results-values").textContent()).contains("This Example did not reach this Step");
        assertThat(page.locator("#example-results-values pre").count()).isZero();
        page.locator("#results-example").selectOption("-1");
        assertThat(page.locator("#example-results-values").textContent()).contains("Choose an Example");
        page.locator("#results-example").selectOption("0");
        waitForText("#example-results-values", "This Example did not reach this Step.");
        assertThat(page.locator("#example-results-values pre").count()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void fieldSourcePickerUsesTheFullCandidateWidth() {
        addFilterAfterTrigger();
        assertThat(page.locator("#conditions-0-option option").allTextContents())
                .anyMatch(label -> label.contains("Read a field"))
                .anyMatch(label -> label.contains("Enter a fixed value"));
        assertThat(page.locator(".candidate-source-label").textContent()).isEqualTo("Value source");
        assertThat(page.locator("[data-input-name='field'] .section-heading").textContent()).contains("Read from");
        page.locator("#conditions-0-field-path").click();

        final var source = page.locator("#conditions-0-option").boundingBox();
        final var picker = page.locator(".path-browser").boundingBox();
        assertThat(picker.x).isCloseTo(source.x, within(1.0));
        assertThat(picker.width).isCloseTo(source.width, within(1.0));
        assertThat(picker.y).isGreaterThan(source.y + source.height);
        page.locator("#cancel-path").click();
        assertThat(pageErrors).isEmpty();
    }

    private String addUnreachedFilter() {
        addFilterAfterTrigger();
        clickOverview("[data-add-outcome='otherwise']");
        page.locator("#step-search").fill("filter");
        page.locator("[data-add-step='railix.filter']").click();
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        return (String) page.evaluate("() => state.selection.id");
    }

    @Test
    @Tag("responsive")
    void resultsAndRouteNavigationPreserveTheFunctionalProject() throws Exception {
        openProject(filterProject());
        selectWorldNode("filter");
        final String project = Files.readString(directory.resolve("project.json"));
        final String pid = applicationPid();
        openInspectorTab("results");
        page.waitForFunction("() => document.querySelector('.example-route strong')?.textContent === 'Match'");
        assertThat(page.locator(".example-context pre").first().textContent()).contains("allow");
        assertThat(page.locator("[data-input-json], [data-open-path]").count()).isZero();
        page.locator("#results-example").selectOption("1");
        page.waitForFunction("() => document.querySelector('.example-route strong')?.textContent === 'Otherwise'");
        assertThat(page.locator(".example-context pre").first().textContent()).contains("deny").doesNotContain("allow");
        openInspectorTab("inspect");
        page.locator("[data-navigate-step='otherwise']").click();
        page.waitForFunction("() => state.selection.id === 'otherwise' && document.querySelector('#step-details')");
        openInspectorTab("results");
        page.waitForFunction("() => document.querySelectorAll('.example-context pre')[1]?.textContent.includes('otherwise')");
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(project);
        assertThat(applicationPid()).isEqualTo(pid);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void writablePickerShowsExpandableBeforeAndAfterValues() {
        final String before = "RAILIX".repeat(20);
        final String after = before.toLowerCase(java.util.Locale.ROOT);
        addGraphPrimitive("\"" + before + "\"", "lowercase", "text.lowercase");
        page.waitForFunction("() => document.querySelector('[data-preview-output=value]')");
        page.locator("[data-input-name=target] [data-open-path]").click();
        final var preview = page.locator(".path-preview");
        assertThat(page.locator("[data-input-name=target] .path-values").count()).isEqualTo(1);
        assertThat(preview.locator("details").count()).isEqualTo(2);
        preview.locator("details summary").first().click();
        assertThat(preview.locator("[data-path-value=before]").textContent()).isEqualTo("\"" + before + "\"");
        preview.locator("details summary").nth(1).click();
        assertThat(preview.locator("[data-path-value=after]").textContent()).isEqualTo("\"" + after + "\"");
        page.locator("#cancel-path").click();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void newlyAddedStepExplainsRequiredConfigurationAndCanRecover() throws Exception {
        prepareTextPayloadTrigger();
        final String before = applicationPid();
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("http client");
        page.locator("[data-add-step='railix.http.client']").click();
        waitForText("#build-state", "Not built");

        assertThat(applicationPid()).isEqualTo(before);
        assertThat(page.locator("#change-status").textContent())
                .contains("Complete required inputs", "last successful application is still running");
        assertThat(page.locator("[data-input-name=url]").textContent()).contains("Required");
        assertThat(page.locator("#url-value").isVisible()).isTrue();
        page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setPath(
                Files.createDirectories(Path.of("target", "screenshots")).resolve(
                        "step-required-configuration-" + page.viewportSize().width + ".png")));
        page.locator("#url-value").scrollIntoViewIfNeeded();
        page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setPath(
                Path.of("target", "screenshots", "step-required-input-" + page.viewportSize().width + ".png")));

        final var service = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        service.createContext("/", exchange -> {
            try (exchange) {
                final byte[] body = "{\"customer\":\"Alice\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        service.start();
        try {
            page.locator("#url-value").fill("http://127.0.0.1:" + service.getAddress().getPort() + "/");
            page.locator("#url-value").press("Tab");
            waitForText("#build-state", "Built");
            page.waitForFunction("() => document.querySelector('[data-preview-output=response]')?.textContent.includes('Alice')");
            assertThat(applicationPid()).isNotEqualTo(before);
            assertThat(page.locator("#change-status").count()).isZero();
        } finally {
            service.stop(0);
        }
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"after", "during", "disconnected"})
    void failedStepSaveCanBeRetriedWithoutAddingTheStepAgain(final String appearanceTiming) throws Exception {
        openProject(fourStepProject());
        selectWorldNode("one");
        final String before = applicationPid();
        final String endpoint = "**/api/project";
        final List<Route> pending = new ArrayList<>();
        final var failure = new Route.FulfillOptions().setStatus(503)
                .setContentType("application/json")
                .setBody("{\"message\":\"Generated application did not build and start.\"}");
        page.route(endpoint, route -> {
            if (route.request().method().equals("PATCH")) pending.add(route);
            else route.resume();
        });
        try {
            clickOverview("#add-next-step");
            page.locator("[data-add-step='railix.field-manipulation']").click();
            page.waitForCondition(() -> !pending.isEmpty());
            if (appearanceTiming.equals("after")) {
                pending.removeFirst().fulfill(failure);
                waitForText("#build-state", "Not built");
                assertThat(page.locator("#change-status").textContent())
                        .contains("Generated application did not build and start.", "last successful application is still running");
            }
            assertThat(applicationPid()).isEqualTo(before);
            assertThat(Files.readString(directory.resolve("project.json"))).doesNotContain("step-");
            presentationName().fill("Retried Step");
            final var appearance = page.waitForResponse(response -> response.url().endsWith("/api/creator")
                    && response.request().method().equals("PATCH"), () -> {
                presentationName().press("Tab");
                if (!appearanceTiming.equals("after")) {
                    page.waitForFunction("() => state.pendingWrite && !state.pendingWrite.projectChanged");
                    final Route build = pending.removeFirst();
                    if (appearanceTiming.equals("disconnected")) build.abort();
                    else build.fulfill(failure);
                }
            });
            assertThat(appearance.status()).isEqualTo(422);
            page.waitForFunction("() => !state.writeActive");
            assertThat(page.locator("#retry-save").isVisible()).isTrue();
        } finally {
            pending.forEach(Route::abort);
            page.unroute(endpoint);
        }

        page.locator("#retry-save").click();
        waitForText("#build-state", "Built");
        assertThat(stepIds()).hasSize(5);
        assertThat(applicationPid()).isNotEqualTo(before);
        page.reload();
        waitForText("#build-state", "Built");
        assertThat(stepIds()).hasSize(5);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"correct", "retry", "appearance"})
    void savedStepFailureDoesNotTurnTheNextEditIntoARevisionConflict(final String recovery) {
        openProject(fourStepProject());
        selectWorldNode("one");
        final var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        page.route("**/api/project", route -> {
            if (!route.request().method().equals("PATCH") || !first.compareAndSet(true, false)) {
                route.resume();
                return;
            }
            final var response = route.fetch();
            assertThat(response.status()).isEqualTo(200);
            final Map<String, RailixValue> receipt = new java.util.LinkedHashMap<>(
                    CreatorServerE2eSupport.object(response.text()).values());
            receipt.remove("diagnostics");
            receipt.put("saved", RailixValue.bool(true));
            receipt.put("status", RailixValue.string("failed"));
            receipt.put("message", RailixValue.string("Generated application could not be activated."));
            route.fulfill(new Route.FulfillOptions().setResponse(response).setStatus(503)
                    .setBody(RailixJson.write(RailixValue.object(receipt))));
        });
        clickOverview("#add-next-step");
        page.locator("[data-add-step='railix.field-manipulation']").click();
        waitForText("#build-state", "Not built");
        openInspectorTab("inspect");

        if (recovery.equals("appearance")) {
            presentationName().fill("Retained Step");
            presentationName().press("Tab");
            page.waitForFunction("() => !state.writeActive && state.savedCreator.steps[state.selection.id]?.name === 'Retained Step'");
        }
        if (recovery.equals("correct")) page.locator("#node-metrics").uncheck();
        else page.locator("#retry-save").click();
        waitForText("#build-state", "Built");
        assertThat(stepIds()).hasSize(5);
        assertThat(page.locator("#inspector").textContent()).doesNotContain("changed in another editor");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {1280, 390})
    void configurationValidationAppearsAtTheEditedFieldAndClearsAfterCorrection(final int width) {
        page.setViewportSize(width, 800);
        openProject("""
                {"format":1,"id":"validation-ui","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"request","use":"railix.trigger.http","inputs":{"port":0},
                   "examples":[{"name":"get","payload":{"method":"GET","path":"/"}}]}],
                 "links":[{"from":"app.start","to":"request"},{"from":"request.next","to":"end"}]}
                """);
        selectWorldNode("request");
        final String pid = applicationPid();
        final var port = page.getByLabel("Port", new Page.GetByLabelOptions().setExact(true));
        assertThat(port.getAttribute("step")).isEqualTo("1");
        port.fill("8080.5");
        port.press("Tab");
        waitForText("#build-state", "Not built");
        assertThat(port.getAttribute("aria-invalid")).isEqualTo("true");
        assertThat(page.locator("#port-error").textContent()).contains("whole number");
        assertThat(port.inputValue()).isEqualTo("8080.5");
        assertThat(applicationPid()).isEqualTo(pid);
        final Number contrast = (Number) page.locator(".issues article").evaluate("""
                element => {
                  const luminance = color => color.match(/[\\d.]+/g).slice(0, 3)
                    .map(Number).map(value => value / 255)
                    .map(value => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4)
                    .reduce((sum, value, index) => sum + value * [.2126, .7152, .0722][index], 0);
                  const style = getComputedStyle(element);
                  const values = [luminance(style.color), luminance(style.backgroundColor)].sort((a, b) => a - b);
                  return (values[1] + .05) / (values[0] + .05);
                }
                """);
        assertThat(contrast.doubleValue()).as("Validation diagnostic remains readable in the HUD").isGreaterThanOrEqualTo(4.5);
        page.locator("#port-error").scrollIntoViewIfNeeded();
        page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target", "configuration-validation-" + width + ".png")));
        port.fill("0");
        port.press("Tab");
        waitForText("#build-state", "Built");
        assertThat(port.getAttribute("aria-invalid")).isNull();
        assertThat(page.locator("#port-error").count()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {1280, 390})
    void settingsOfferOnlyTheNamedHighQualityRenderers(final int width) throws Exception {
        page.setViewportSize(width, 800);
        Files.deleteIfExists(directory.resolve("railix-home/creator.settings.json"));
        page.reload();
        waitForText("#build-state", "Built");
        final String source = Files.readString(directory.resolve("project.json")), pid = applicationPid();
        page.locator("#open-settings").click();
        page.waitForFunction("() => state.themes.length > 0");
        assertThat(page.locator("#theme-variant option").allTextContents())
                .containsExactly("Renderer Canvas");
        page.waitForFunction("() => document.querySelector('#graph').dataset.renderer === 'canvas'");
        assertThat(page.locator("#world-plane .machine").count()).isZero();
        assertThat(page.locator("#world-plane canvas").count()).isEqualTo(1);
        assertThat(page.locator("#theme-variant").inputValue()).isEqualTo("canvas");
        page.waitForFunction("() => getComputedStyle(document.documentElement).getPropertyValue('--canvas-resolution').trim()==='2'");
        assertThat(page.evaluate("() => getComputedStyle(document.documentElement).getPropertyValue('--canvas-resolution').trim()"))
                .isEqualTo("2");
        page.waitForFunction("() => !state.settingsWriting && !state.settingsTimer");
        page.reload();
        page.waitForFunction("() => document.querySelector('#graph').dataset.renderer === 'canvas' && state.settings.theme_variant === ''");
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(source);
        assertThat(applicationPid()).isEqualTo(pid);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void localCanvasVariantLoadsImagesAndRecoversFromAnInvalidAtlas() throws Exception {
        final Path theme = Files.createDirectories(directory.resolve("railix-home/themes/local"));
        Files.writeString(theme.resolve("theme.css"), ":root { --world-buildings: 1; --canvas-resolution: 1; }");
        Files.writeString(theme.resolve("variant.css"), ":root { --machine-light: #bceada; }");
        Files.writeString(theme.resolve("theme.json"), """
                {"name":"Local sprites","defaultVariant":"images","variants":[
                  {"id":"images","name":"Images","stylesheet":"variant.css","renderer":"canvas","atlas":"atlas.json"}]}
                """);
        Files.writeString(theme.resolve("body.svg"), """
                <svg xmlns="http://www.w3.org/2000/svg" width="32" height="32"><path fill="#be5348" d="M0 0h32v32H0z"/></svg>
                """);
        Files.write(theme.resolve("body.webp"), java.util.Base64.getDecoder().decode((String) page.evaluate("""
                () => {
                  const canvas=document.createElement('canvas');canvas.width=canvas.height=32;
                  const ctx=canvas.getContext('2d');ctx.fillStyle='#be5348';ctx.fillRect(0,0,32,32);
                  return canvas.toDataURL('image/webp').split(',')[1];
                }
                """)));
        Files.writeString(theme.resolve("atlas.json"), """
                {"version":1,"sprites":{
                  "step":{"file":"body.svg","frames":1,"scale":1,"anchorX":16,"anchorY":24,"period":1000},
                  "trigger":{"file":"body.webp","frames":1,"scale":1,"anchorX":16,"anchorY":24,"period":1000}}}
                """);
        openProject(choiceProject());
        final String source = Files.readString(directory.resolve("project.json")), pid = applicationPid();
        page.locator("#open-settings").click();
        selectTheme("local/theme.css");
        page.waitForFunction("() => {if(state.themeError)throw Error(state.themeError);return document.querySelector('#graph').dataset.renderer==='canvas';}");
        page.waitForFunction("() => !state.settingsWriting && !state.settingsTimer");
        page.reload();
        page.waitForFunction("() => document.querySelector('#graph').dataset.renderer==='canvas' && !state.themeError");
        awaitScene();
        chooseVisibleExample("command:0");
        assertThat(page.evaluate("() => state.world._surface.assets.sprites.get('trigger').bitmap.width === 32"))
                .isEqualTo(true);
        assertThat(page.locator(".world-scenery").count()).isZero();
        final var point=(List<Number>)page.locator(".world-raster").evaluate("""
                canvas=>{
                  const rgba=canvas.getContext('2d').getImageData(0,0,canvas.width,canvas.height).data,r=canvas.getBoundingClientRect();
                  for(let y=0;y<canvas.height;y++)for(let x=0;x<canvas.width;x++){
                    const i=(y*canvas.width+x)*4,px=r.x+x/canvas.width*r.width,py=r.y+y/canvas.height*r.height;
                    if(rgba[i]===190&&rgba[i+1]===83&&rgba[i+2]===72&&!document.elementFromPoint(px,py)?.closest('#world-hud,#world-labels'))return [px,py];
                  }
                  throw Error('The custom SVG building was not painted.');
                }
                """);
        page.mouse().click(point.get(0).doubleValue(),point.get(1).doubleValue());
        page.waitForFunction("() => !state.editorController && state.selection.type === 'step'");
        page.locator("#inspector").waitFor();
        openInspectorTab("overview");
        page.locator("#selection-evidence").waitFor();
        final double portraitWidth=((Number)page.waitForFunction("""
                () => document.querySelector('.selection-portrait canvas')?.getBoundingClientRect().width || false
                """).jsonValue()).doubleValue();
        page.locator("#zoom-in").click();
        awaitScene();
        assertThat(((Number)page.waitForFunction("""
                () => document.querySelector('.selection-portrait canvas')?.getBoundingClientRect().width || false
                """).jsonValue()).doubleValue()).isEqualTo(portraitWidth);
        Files.writeString(theme.resolve("atlas.json"), """
                {"version":1,"sprites":{"step":{"file":"../outside.svg","frames":1,"scale":1,"anchorX":0,"anchorY":0,"period":1000}}}
                """);
        page.evaluate("async () => {await applyTheme();}");
        assertThat(page.locator("#graph").getAttribute("data-renderer")).isEqualTo("canvas");
        assertThat(page.locator("#world-plane canvas").count()).isEqualTo(1);
        assertThat(page.evaluate("() => state.themeError").toString()).contains("Invalid Canvas sprite");
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(source);
        assertThat(applicationPid()).isEqualTo(pid);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"signal", "circuit"})
    void characterVariantsAnimateAndReturnToExistingRenderers(final String theme) throws Exception {
        openProject(choiceProject());
        final String source = Files.readString(directory.resolve("project.json")), pid = applicationPid();
        page.locator("#open-settings").click();
        selectTheme(theme + "/theme.css");
        page.waitForFunction("() => document.querySelector('#graph').dataset.renderer === 'character' && !state.themeError");
        assertThat(page.locator("#theme-variant option").allTextContents())
                .containsExactly("Renderer Characters", "Renderer Glyphs", "Renderer Pixels");
        final List<String> variants = page.locator("#theme-variant option").all().stream()
                .map(option -> option.getAttribute("value")).toList();
        final Path images = Files.createDirectories(Path.of("target/renderer-navigation"));
        for (final String variant : variants) {
            page.locator("#theme-variant").selectOption(variant);
            page.waitForFunction("variant => state.settings.theme_variant === variant && !state.themeError && Number(document.querySelector('.world-character')?.dataset.stations) > 0", variant);
            assertThat(page.locator("#world-plane canvas").count()).isEqualTo(1);
            page.keyboard().press("Escape");
            page.waitForFunction("() => JSON.parse(document.querySelector('.world-character').dataset.measurements || '{}').samples > 10");
            page.screenshot(new Page.ScreenshotOptions().setPath(images.resolve(theme + "-" + variant + ".png")));
            page.locator("#open-settings").click();
        }
        page.keyboard().press("Escape");
        page.evaluate("() => {Object.defineProperty(document, 'hidden', {configurable:true, value:true});document.dispatchEvent(new Event('visibilitychange'));}");
        page.waitForFunction("() => document.querySelector('.world-character').dataset.animated === 'false'");
        page.evaluate("() => {delete document.hidden;document.dispatchEvent(new Event('visibilitychange'));}");
        page.waitForFunction("() => document.querySelector('.world-character').dataset.animated === 'true'");
        page.locator("#open-settings").click();
        selectTheme("");
        for (final String variant : List.of("canvas")) {
            page.locator("#theme-variant").selectOption(variant);
            page.waitForFunction("variant => !state.themeError && document.querySelector('#graph').dataset.renderer === 'canvas'", variant);
            assertThat(page.locator(".world-character").count()).isZero();
            assertThat(page.locator("#world-plane canvas").count()).isEqualTo(1);
        }
        selectTheme("classic/theme.css");
        page.waitForFunction("() => !state.themeError && document.documentElement.dataset.architecture === 'classic'");
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(source);
        assertThat(applicationPid()).isEqualTo(pid);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void unavailableWebGlReportsAnErrorAndKeepsCanvasUsable() throws Exception {
        final String source = Files.readString(directory.resolve("project.json")), pid = applicationPid();
        page.addInitScript("""
                (() => {
                  const getContext = HTMLCanvasElement.prototype.getContext;
                  HTMLCanvasElement.prototype.getContext = function(type, ...args) {
                    return type === 'webgl2' ? null : getContext.call(this, type, ...args);
                  };
                })();
                """);
        page.reload();
        waitForText("#build-state", "Built");
        page.locator("#open-settings").click();
        page.locator("#theme-select").selectOption("circuit/theme.css");
        page.waitForFunction("() => state.themeError.includes('WebGL 2') && document.querySelector('#graph').dataset.renderer === 'canvas'");
        assertThat(page.locator("#world-plane canvas").count()).isEqualTo(1);
        assertThat(page.locator("#world-plane .machine").count()).isZero();
        selectTheme("");
        page.locator("#theme-variant").selectOption("canvas");
        page.waitForFunction("() => !state.themeError && document.querySelector('#graph').dataset.renderer === 'canvas'");
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(source);
        assertThat(applicationPid()).isEqualTo(pid);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void worldStatusSharesTheSettingsAndAudioRow() {
        openProject(choiceProject());
        assertThat(page.locator(".topbar > button").evaluateAll("""
                buttons => {
                  const centers = buttons.map(button => {const r=button.getBoundingClientRect(); return r.y+r.height/2;});
                  return Math.max(...centers)-Math.min(...centers);
                }
                """)).as("Runtime status stays on the same HUD row as Settings and audio").isEqualTo(0);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void themeVariantsLoadRelativeAssetsAndKeepTheApplicationUnchanged() throws Exception {
        final Path theme = Files.createDirectories(directory.resolve("railix-home/themes/studio"));
        Files.createDirectories(theme.resolve("variants"));
        Files.createDirectories(theme.resolve("assets"));
        Files.writeString(theme.resolve("theme.css"), ":root { --machine-light: #abcdef; }");
        Files.writeString(theme.resolve("theme.json"), """
                {"name":"Studio","defaultVariant":"line","variants":[
                  {"id":"line","name":"Line art","description":"Vector trim","stylesheet":"variants/line.css"},
                  {"id":"plain","name":"Plain","stylesheet":"variants/plain.css"}],
                 "files":["assets/mark.svg","assets/mark.png"]}
                """);
        Files.writeString(theme.resolve("assets/mark.svg"), """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><path fill="#abcdef" d="M0 0h10v10H0z"/></svg>
                """);
        Files.write(theme.resolve("assets/mark.png"), java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jP1sAAAAASUVORK5CYII="));
        Files.writeString(theme.resolve("variants/line.css"), ":root[data-architecture] #world-map { background-image: url('../assets/mark.svg'); }");
        Files.writeString(theme.resolve("variants/plain.css"), ":root[data-architecture] #world-map { background-image: url('../assets/mark.png'); }");
        openProject(choiceProject());
        final String project = Files.readString(directory.resolve("project.json"));
        final String pid = applicationPid();
        page.locator("#open-settings").click();
        page.waitForFunction("() => state.themes.some(theme => theme.id === 'studio/theme.css')");
        selectTheme("studio/theme.css");
        page.waitForFunction("""
                () => {
                  if(state.themeError) throw Error(state.themeError);
                  return getComputedStyle(document.querySelector('#world-map')).backgroundImage.includes('mark.svg');
                }
                """);
        assertThemeImageDecodes();
        assertThat(page.locator("#theme-variant").inputValue()).isEqualTo("line");
        assertThat(page.locator("#theme-select option").allTextContents()).doesNotContain("studio/variants/line", "studio/variants/plain");
        page.locator("#theme-variant").selectOption("plain");
        page.waitForFunction("() => getComputedStyle(document.querySelector('#world-map')).backgroundImage.includes('mark.png')");
        assertThemeImageDecodes();
        page.waitForFunction("() => !state.settingsWriting && !state.settingsTimer");
        assertThat(Files.readString(directory.resolve("railix-home/creator.settings.json"))).contains("\"theme_variant\":\"plain\"");
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(project);
        assertThat(applicationPid()).isEqualTo(pid);
        page.reload();
        waitForText("#build-state", "Built");
        page.waitForFunction("() => getComputedStyle(document.querySelector('#world-map')).backgroundImage.includes('mark.png')");
        assertThat(pageErrors).isEmpty();
    }

    private void assertThemeImageDecodes() {
        assertThat(page.evaluate("""
                async () => {
                  const image=new Image();
                  image.src=getComputedStyle(document.querySelector('#world-map')).backgroundImage.slice(5,-2);
                  await image.decode(); return image.naturalWidth > 0;
                }
                """)).isEqualTo(true);
    }

    @Test
    void overviewPortraitFollowsThemeWithoutFollowingCameraScale() throws Exception {
        openProject(choiceProject());
        selectWorldNode("choice");
        openInspectorTab("overview");
        assertThat(page.locator("#inspector-identity small").textContent()).isEqualTo("Choice");
        page.locator("#visible-example").selectOption("command:0");
        page.locator("#selection-evidence").waitFor();
        final var portrait = page.locator(".selection-portrait canvas");
        assertThat(portrait.count()).isEqualTo(1);
        final double width = portrait.boundingBox().width;
        page.locator("#zoom-in").click();
        page.waitForFunction("width => Math.abs((document.querySelector('.selection-portrait canvas')?.getBoundingClientRect().width || 0)-width)<.1", width);
        assertThat(portrait.boundingBox().width).isCloseTo(width, org.assertj.core.data.Offset.offset(.1));
        assertThat(portrait.evaluate("element => element.getAnimations({subtree:true}).length")).isEqualTo(0);
        page.locator("#open-settings").click();
        selectTheme("classic/theme.css");
        page.waitForFunction("() => document.documentElement.dataset.architecture==='classic' && document.querySelector('.selection-portrait canvas')");
        assertThat(page.locator(".selection-portrait .building-volume").count()).isZero();
        selectTheme("");
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Close Settings").setExact(true)).click();
        page.waitForFunction("width => document.documentElement.dataset.architecture==='buildings' && Math.abs((document.querySelector('.selection-portrait canvas')?.getBoundingClientRect().width || 0)-width)<.1", width);
        assertThat(page.locator(".selection-portrait canvas").boundingBox().width).isEqualTo(width);
        page.locator("#close-inspector").click();
        assertThat(page.locator(".selection-portrait").isVisible()).isFalse();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void embeddedBuildingThemeAndClassicCanBeSwitchedAndCopiedWithoutRebuilding() throws Exception {
        openProject(choiceProject());
        final String source = Files.readString(directory.resolve("project.json"));
        final String pid = applicationPid();
        page.locator("#open-settings").click();
        page.waitForFunction("() => state.themes.length > 0");
        assertThat(page.locator("#theme-select option").allTextContents()).contains("Railix Foundry", "Railix Classic");
        assertThat(page.locator("html").getAttribute("data-architecture")).isEqualTo("buildings");
        page.waitForFunction("() => state.world._surface.stations.some(item=>item.node.kind==='app')");
        selectTheme("classic/theme.css");
        page.waitForFunction("() => document.documentElement.dataset.architecture === 'classic'");
        assertThat(page.locator(".building-volume").count()).isZero();
        assertThat(page.waitForResponse(response -> response.url().endsWith("/api/themes") && response.request().method().equals("POST"),
                () -> page.locator("#theme-download").click()).status()).isEqualTo(200);
        assertThat(Files.readString(directory.resolve("railix-home/themes/classic/theme.css"))).contains("--world-buildings: 0");
        page.waitForFunction("() => state.themes.some(theme => theme.id === 'classic/theme.css' && !theme.builtin)");
        assertThat(page.locator("#theme-select option[value='classic/theme.css']").count()).isEqualTo(1);
        selectTheme("");
        page.waitForFunction("() => document.documentElement.dataset.architecture === 'buildings'");
        assertThat(page.waitForResponse(response -> response.url().endsWith("/api/themes") && response.request().method().equals("POST"),
                () -> page.locator("#theme-download").click()).status()).isEqualTo(200);
        assertThat(Files.readString(directory.resolve("railix-home/themes/foundry/theme.css"))).contains("--world-buildings", "--world-near");
        page.waitForFunction("() => state.themes.some(theme => theme.id === '' && !theme.builtin)");
        assertThat(page.locator("#theme-select option[value='']").count()).isEqualTo(1);
        assertThat(page.locator("#theme-select option[value='railix.css']").count()).isZero();
        page.evaluate("() => changeSettings({theme:'railix.css'})");
        page.waitForFunction("() => !state.settingsWriting && !state.settingsTimer");
        page.reload();
        waitForText("#build-state", "Built");
        page.waitForFunction("() => document.documentElement.dataset.architecture === 'buildings'");
        assertThat(page.locator("#theme-select").inputValue()).isEmpty();
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(source);
        assertThat(applicationPid()).isEqualTo(pid);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void comparisonCanBeChangedInPlaceWithoutLosingItsOperand() {
        openProject(choiceProject());
        selectWorldNode("choice");
        final var comparison = page.locator("[data-replace-comparison]").first();
        assertThat(comparison.inputValue()).isEqualTo("value.equals");
        final String before = page.locator(".condition-predicate textarea").first().inputValue();
        comparison.selectOption("value.not-equals");
        waitForText("#build-state", "Built");
        assertThat(page.locator(".condition-predicate textarea").first().inputValue()).isEqualTo(before);
        assertThat(comparison.inputValue()).isEqualTo("value.not-equals");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void selectedStepKeepsItsGroupNameAfterManagingAnotherGroup() {
        openProject(fourStepProject());
        assertThat(page.evaluate("""
                async () => (await fetch('/api/creator',{method:'POST',headers:mutationHeaders(),body:JSON.stringify({
                  format:2,groups:Array.from({length:65},(_,i)=>({id:'group-'+i,name:'Group '+i})),
                  steps:{one:{group:'group-64'},two:{group:'group-0'}}})})).status
                """)).isEqualTo(200);
        page.reload();
        waitForText("#build-state", "Built");
        selectWorldNode("two");
        openInspectorTab("appearance");
        page.locator("[data-inspector-mode=groups]").click();
        selectWorldNode("one");
        openInspectorTab("appearance");
        assertThat(page.locator("#choose-group").textContent()).isEqualTo("Group 64");
        page.locator("#choose-group").click();
        clickAndWaitForCreatorSave(() -> page.locator("[data-assign-group='']").click());
        assertThat(page.locator("#choose-group").textContent()).isEqualTo("No group");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void appearanceEditsOnlyTheSelectedStep() throws Exception {
        openProject(fourStepProject());
        final String source = Files.readString(directory.resolve("project.json"));
        final String pid = applicationPid();
        selectWorldNode("one");
        openInspectorTab("appearance");
        clickAndWaitForCreatorSave(() -> page.locator("#presentation-shape").selectOption("diamond"));
        selectWorldNode("two");
        openInspectorTab("appearance");
        assertThat(page.locator("#presentation-shape").inputValue()).isEqualTo("rectangle");
        page.locator("#presentation-color").fill("#abcdef");
        clickAndWaitForCreatorSave(() -> page.locator("#presentation-color").press("Tab"));
        assertThat(page.evaluate("async () => (await (await fetch('/api/project')).json()).creator.steps"))
                .isEqualTo(java.util.Map.of("one",java.util.Map.of("shape","diamond"),
                        "two",java.util.Map.of("color","#ABCDEF")));
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(source);
        assertThat(applicationPid()).isEqualTo(pid);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void branchActionsUseTheSameReadableMaterialAsHudControls() {
        openProject(choiceProject());
        chooseVisibleExample("command:0");
        selectWorldNode("choice");
        openInspectorTab("overview");
        final var action = page.locator("#selection-overview [data-add-outcome]").first();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(action)
                .hasCSS("background-image", "none");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(action).hasCSS("text-shadow", "none");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#selection-evidence"))
                .hasCSS("background-color", "rgba(0, 0, 0, 0)");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void hudMaterialPreservesTheSelectedInspectorTab() {
        openProject(fourStepProject());
        selectWorldNode("one");
        assertThat(page.locator(".inspector-tabs button.active").evaluate(
                "element => getComputedStyle(element).backgroundImage")).isEqualTo("none");
        assertThat(page.locator(".inspector-tabs button.active").evaluate(
                "element => getComputedStyle(element).boxShadow")).isNotEqualTo("none");
        assertThat(page.locator(".inspector-tabs button:not(.active)").first().evaluate(
                "element => getComputedStyle(element).boxShadow")).isEqualTo("none");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void groupManagementUsesTheSelectedRegionRatherThanThePreviousStep() {
        openProject(fourStepProject());
        assertThat(page.evaluate("""
                async () => (await fetch('/api/creator',{method:'POST',headers:mutationHeaders(),body:JSON.stringify({
                  format:2,groups:[{id:'first',name:'First'},{id:'second',name:'Second'}],
                  steps:{one:{group:'first'},two:{group:'second'}}})})).status
                """)).isEqualTo(200);
        page.reload();
        waitForText("#build-state", "Built");
        page.locator("[data-region-group=second]").click();
        openInspectorTab("overview");
        page.locator("[data-inspector-mode=groups]").click();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("[data-manage-group=second]"))
                .hasClass(Pattern.compile(".*active.*"));
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void failedGroupSelectionCannotDetachThePreviousLinkedStep() throws Exception {
        openProject(fourStepProject());
        page.evaluate("""
                async () => {
                  let view=await (await fetch('/api/editor?node=app')).json();
                  for(const command of [{kind:'group',id:'other',name:'Other work',nodes:['two']},
                    {kind:'reuse',node:'one',from:'three.next',id:'linked-one'}]) {
                    const response=await fetch('/api/editor',{method:'POST',headers:mutationHeaders(),
                      body:JSON.stringify({revision:view.revision,creator_revision:view.creator_revision,command})});
                    if(!response.ok)throw Error(await response.text());
                    view=await response.json();
                  }
                }
                """);
        page.reload();
        selectWorldNode("one");
        openInspectorTab("overview");
        page.locator("[data-detach-use]").waitFor();
        final String metadata = creatorMetadata();
        final var reads = Pattern.compile(".*/api/editor\\?.*");
        page.route(reads, route -> route.fulfill(new Route.FulfillOptions().setStatus(503)
                .setBody("{\"message\":\"Temporarily unavailable\"}")));
        try {
            page.locator("#close-inspector").click();
            page.locator("[data-region-group=other]").first().click();
            page.waitForFunction("() => !state.editorController");
            assertThat(page.locator("[data-detach-use]").count()).isZero();
            assertThat(page.locator("[data-linked-use]").count()).isZero();
            assertThat(creatorMetadata()).isEqualTo(metadata);
            assertThat(pageErrors).isEmpty();
        } finally {
            page.unroute(reads);
        }
    }

    @Test
    void coloredGroupUsesTheSharedSelectionAccent() throws Exception {
        openProject(fourStepProject());
        final String group = createGroup("one", "two");
        page.locator("#presentation-color").fill("#aa6519");
        clickAndWaitForCreatorSave(() -> page.locator("#presentation-color").press("Tab"));
        page.locator("#close-inspector").click();
        page.locator("[data-region-group='" + group + "']").first().click();
        assertThat(page.evaluate("() => state.world._surface.palette.selected"))
                .isEqualTo("#a9f5ec");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void selectedExampleMovesCargoAndBeltSectionsIntoARecessedEnd(final int example) {
        openProject(choiceProject());
        selectWorldNode("command");
        chooseExampleIndex(example);
        page.evaluate("() => void state.world.fit()");
        awaitScene();
        page.waitForFunction("() => document.querySelector('#graph').dataset.replaying === 'true'");
        assertWorldAnimation();
        assertThat(((Number)page.evaluate("() => state.world._surface.terminals.filter(item=>item.look.coverage==='selected').length")).intValue()).isEqualTo(1);
        assertThat(page.evaluate("() => state.world.scene.nodes.filter(node=>node.kind==='end' && worldAppearance(node).coverage==='selected').map(node=>node.id)"))
                .isEqualTo(List.of("end:" + (example == 0 ? "matched" : "otherwise") + ".next"));
        assertThat(page.locator(".world-symbol[data-symbol=end]:visible").count()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void choiceWithTwoTerminalOutcomesDoesNotGuessTheSelectedExit() {
        openProject("""
                {"format":1,"id":"terminal-choice","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"empty","payload":[],"context":{}}]},
                  {"id":"choice","use":"railix.choice","inputs":{"conditions":[]}}
                ],"links":[{"from":"app.start","to":"command"},
                  {"from":"command.next","to":"choice"},{"from":"choice.match","to":"end"},
                  {"from":"choice.otherwise","to":"end"}]}
                """);
        selectWorldNode("command");
        page.evaluate("() => void state.world.fit()");
        awaitScene();
        page.waitForFunction("() => document.querySelector('#graph').dataset.replaying === 'true'");
        assertThat(((Number)page.evaluate("() => state.world._surface.terminals.length")).intValue()).isEqualTo(2);
        assertThat(page.evaluate("() => state.world._surface.terminals.some(item=>item.look.coverage==='selected')")).isEqualTo(false);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void stepChooserSupportsKeyboardNavigationWithoutMovingTheCamera() {
        openProject(fourStepProject());
        selectWorldNode("one");
        clickOverview("#add-next-step");
        final var search = page.locator("#step-search");
        search.fill("field manipulation");
        final String camera = (String) page.evaluate("() => state.world.query");
        search.press("ArrowDown");
        assertThat(page.locator(":focus").getAttribute("data-add-step")).isEqualTo("railix.field-manipulation");
        page.keyboard().press("Escape");
        assertThat(page.locator(":focus").getAttribute("id")).isEqualTo("step-search");
        assertThat(search.inputValue()).isEqualTo("field manipulation");
        assertThat(page.evaluate("() => state.world.query")).isEqualTo(camera);
        search.press("ArrowDown");
        page.keyboard().press("Enter");
        waitForText("#build-state", "Built");
        assertThat(page.locator(".construction-palette").count()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void selectionOpensInspectorAndSettingsRemainInTheRightHandHud() {
        openProject(fourStepProject());
        page.locator("[data-world-id=one]").click();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#inspector")).isVisible();
        assertThat(page.locator("#selection-dock").count()).isZero();
        openInspectorTab("overview");
        assertThat(page.locator("#selection-overview").isVisible()).isTrue();
        assertThat(page.locator(".inspector-tabs .active").textContent()).isEqualTo("Data");
        assertThat(page.locator("#open-inspector").count()).isZero();
        assertThat(page.locator("#open-settings").textContent()).isBlank();
        final var map=page.locator("#world-map").boundingBox();
        final var controls=page.locator(".topbar").boundingBox();
        assertThat(map.x).isGreaterThan(page.viewportSize().width/2.0);
        assertThat(controls.y + controls.height).isLessThanOrEqualTo(map.y);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void typingAndEscapeInAnInputDoNotRunWorldShortcuts() {
        openProject(choiceProject());
        selectWorldNode("choice");
        final var field=page.locator("#inspector-content textarea").first();
        field.fill("\"ef\"");
        final String camera=(String)page.evaluate("() => state.world.query");
        field.press("ArrowLeft");
        field.press("Escape");
        assertThat(field.inputValue()).isEqualTo("\"ef\"");
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        assertThat(page.evaluate("() => state.world.query")).isEqualTo(camera);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void authoredConditionMarksTheRecordedOutcomeNotTheFirstInputKind() {
        openProject(nestedSwitchProject());
        chooseVisibleExample("command:0");
        selectWorldNode("switch");
        fillStepSearch("#cases-0-when-new-predicate-search", "equals");
        page.locator("[data-candidate-index='0'] [data-add-predicate='value.equals']").click();
        waitForText("#build-state", "Built");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(
                page.locator(".selected-candidate")).hasAttribute("data-candidate-index", "1");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void comparisonEditorDoesNotExposeUnusedOperationLists() {
        openProject(choiceProject());
        selectWorldNode("choice");
        assertThat(page.locator("#inspector").innerText()).doesNotContain("Transform value", "To Json", "Continue matcher");
        assertThat(page.locator(".condition-transforms").first().getAttribute("open")).isNull();
        assertThat(page.locator(".condition-chain").count()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void musicPausesOnHideAndResumesTheSameSessionWithoutChangingPreferences() {
        openProject(fourStepProject());
        page.locator("#open-settings").click();
        page.locator("#settings-music-tab").click();
        page.waitForFunction("() => !document.querySelector('#music-play').disabled");
        assertThat(page.evaluate("() => Boolean(state.audio.musicContext)")).isEqualTo(true);
        page.waitForFunction("() => state.audio.musicContext?.state === 'running'");
        final var playback = page.evaluateHandle("""
                () => ({context:state.audio.musicContext, session:state.audio.musicSession,
                  track:state.audio.track, queue:JSON.stringify(state.audio.queue)})
                """);
        for (int visit = 0; visit < 3; visit++) {
            page.evaluate("""
                    () => { Object.defineProperty(document,'hidden',{configurable:true,value:true});
                      document.dispatchEvent(new Event('visibilitychange')); }
                    """);
            page.waitForFunction("playback => playback.context.state !== 'running'", playback);
            assertThat(page.evaluate("playback => playback.context.state", playback)).isEqualTo("suspended");
            assertThat(page.evaluate("""
                    async playback => {
                      const time = playback.context.currentTime, timer = playback.session.timer;
                      await new Promise(resolve => setTimeout(resolve, 600));
                      return playback.context.currentTime === time && playback.session.timer === timer
                        && state.audio.musicContext === playback.context && state.audio.musicSession === playback.session
                        && state.audio.track === playback.track && JSON.stringify(state.audio.queue) === playback.queue
                        && !playback.session.stopped && state.audio.preferences.music_enabled && state.settings.music_enabled;
                    }
                    """, playback)).isEqualTo(true);
            final Object pausedAt = page.evaluate("playback => playback.context.currentTime", playback);
            page.evaluate("() => { delete document.hidden; document.dispatchEvent(new Event('visibilitychange')); }");
            page.waitForFunction("time => state.audio.musicContext?.state === 'running' && state.audio.musicContext.currentTime > time", pausedAt);
            assertThat(page.evaluate("playback => state.audio.musicSession === playback.session", playback)).isEqualTo(true);
        }
        page.evaluate("() => window.dispatchEvent(new Event('pagehide'))");
        page.waitForFunction("playback => playback.context.state === 'closed'", playback);
        assertThat(page.evaluate("playback => playback.session.stopped && !state.audio.musicContext", playback)).isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void manualMusicPauseSurvivesTabChangesUntilPlayIsPressed(final boolean reopenSettings) {
        page.locator("#open-settings").click();
        page.locator("#settings-music-tab").click();
        page.waitForFunction("() => state.audio.musicContext?.state === 'running'");
        page.locator("#music-play").click();
        page.waitForFunction("() => state.audio.musicContext?.state === 'suspended' && !state.settingsWriting && !state.settingsTimer");
        final var playback = page.evaluateHandle("() => ({context:state.audio.musicContext, session:state.audio.musicSession})");
        if (reopenSettings) {
            page.keyboard().press("Escape");
            page.waitForResponse(response -> response.url().endsWith("/api/settings")
                    && response.request().method().equals("GET") && response.status() == 200,
                    () -> page.locator("#open-settings").click());
            page.locator("#settings-music-tab").click();
        }
        assertThat(page.evaluate("""
                async playback => {
                  const time = playback.context.currentTime;
                  Object.defineProperty(document, 'hidden', {configurable:true, value:true});
                  document.dispatchEvent(new Event('visibilitychange'));
                  delete document.hidden;
                  document.dispatchEvent(new Event('visibilitychange'));
                  await new Promise(resolve => setTimeout(resolve, 600));
                  return playback.context.state === 'suspended' && playback.context.currentTime === time
                    && state.audio.musicContext === playback.context && state.audio.musicSession === playback.session
                    && !state.settings.music_enabled && !state.audio.preferences.music_enabled;
                }
                """, playback)).isEqualTo(true);
        assertThat(page.locator("#music-play span").textContent()).isEqualTo("Play");
        page.locator("#music-play").click();
        page.waitForFunction("playback => state.audio.musicSession === playback.session && playback.context.state === 'running'", playback);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stoppedMusicDoesNotRestartWhenTheTabReturns(final boolean stopWhileHidden) {
        page.locator("#open-settings").click();
        page.locator("#settings-music-tab").click();
        page.waitForFunction("() => state.audio.musicContext?.state === 'running'");
        if (!stopWhileHidden) page.locator("#music-stop").click();
        page.evaluate("""
                () => { Object.defineProperty(document, 'hidden', {configurable:true, value:true});
                  document.dispatchEvent(new Event('visibilitychange')); }
                """);
        if (stopWhileHidden) page.locator("#music-stop").click();
        assertThat(page.evaluate("""
                async () => {
                  delete document.hidden;
                  document.dispatchEvent(new Event('visibilitychange'));
                  await new Promise(resolve => setTimeout(resolve, 600));
                  return !state.audio.musicContext && !state.audio.musicSession
                    && !state.audio.preferences.music_enabled && !state.settings.music_enabled;
                }
                """)).isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void hidingDuringMusicStartOrResumeLeavesNoHiddenScheduler(final boolean starting, final boolean returnBeforeReady) {
        page.locator("#open-settings").click();
        page.locator("#settings-music-tab").click();
        page.waitForFunction("() => state.audio.musicContext?.state === 'running'");
        page.locator(starting ? "#music-stop" : "#music-play").click();
        page.evaluate("""
                () => {
                  const resume = AudioContext.prototype.resume;
                  AudioContext.prototype.resume = function() {
                    AudioContext.prototype.resume = resume;
                    const ready = resume.call(this);
                    return new Promise(resolve => { window.finishMusicResume = () => ready.then(resolve); });
                  };
                }
                """);
        page.locator("#music-play").click();
        page.waitForFunction("() => window.finishMusicResume && state.audio.musicContext?.state === 'running'");
        final var context = page.evaluateHandle("() => state.audio.musicContext");
        page.evaluate("""
                () => { Object.defineProperty(document, 'hidden', {configurable:true, value:true});
                  document.dispatchEvent(new Event('visibilitychange')); }
                """);
        page.waitForFunction("context => context.state === 'suspended'", context);
        if (returnBeforeReady) {
            page.evaluate("() => { delete document.hidden; document.dispatchEvent(new Event('visibilitychange')); }");
        }
        page.evaluate("() => window.finishMusicResume()");
        page.waitForFunction("() => state.audio.musicSession");
        if (returnBeforeReady) {
            page.waitForFunction("context => context.state === 'running'", context, new Page.WaitForFunctionOptions().setTimeout(3_000));
        } else {
            assertThat(page.evaluate("""
                    async context => {
                      const session = state.audio.musicSession, time = context.currentTime, timer = session.timer;
                      await new Promise(resolve => setTimeout(resolve, 600));
                      return context === state.audio.musicContext && context.state === 'suspended'
                        && context.currentTime === time && session.timer === timer;
                    }
                    """, context)).isEqualTo(true);
        }
        page.evaluate("""
                () => {
                  for (const hidden of [false, true, false, true, false]) {
                    Object.defineProperty(document, 'hidden', {configurable:true, value:hidden});
                    document.dispatchEvent(new Event('visibilitychange'));
                  }
                  delete document.hidden;
                }
                """);
        page.waitForFunction("context => context === state.audio.musicContext && context.state === 'running'", context);
        page.locator("#music-stop").click();
        page.waitForFunction("context => context.state === 'closed'", context);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sound", "music"})
    @Tag("browser-diagnostics")
    void audioSettingsRemainUsableWhileTheCatalogIsLoading(final String tab) {
        final List<Route> pending = new ArrayList<>();
        // Keep the catalog's five-second deadline from racing this deliberately held response.
        page.clock().install();
        page.clock().pauseAt(System.currentTimeMillis() + 1_000);
        page.route("**/api/sounds", pending::add);
        try {
            page.reload();
            page.locator("#open-settings").click();
            page.locator("#settings-" + tab + "-tab").click();
            page.waitForCondition(() -> !pending.isEmpty());
            assertThat(pageErrors).as("Opening the editor before the catalog arrives").isEmpty();
            page.locator("#sound-files > summary").click();
            assertThat(page.locator("#sound-file").isVisible()).isTrue();
            page.unroute("**/api/sounds");
            pending.forEach(Route::resume);
            pending.clear();
            page.waitForFunction("() => document.querySelector('#sound-file').options.length > 1");
            assertThat(page.locator("#sound-file option").allTextContents())
                    .anyMatch(label -> label.contains(tab.equals("music") ? "music/" : "sounds/"));
            assertThat(pageErrors).isEmpty();
        } finally {
            pending.forEach(Route::abort);
            page.unroute("**/api/sounds");
            page.clock().resume();
        }
    }

    @Test
    @Tag("browser-diagnostics")
    void musicVolumeReachesFullOutputAndMutesWithoutStoppingPlayback() throws Exception {
        page.locator("#open-settings").click();
        page.locator("#settings-music-tab").click();
        page.waitForFunction("() => state.audio.musicContext?.state === 'running'");
        final var slider = page.locator("#music-volume");
        assertThat(slider.inputValue()).isEqualTo("25");
        assertThat(page.locator("#music-volume-value").textContent()).isEqualTo("25%");
        slider.press("End");
        page.waitForFunction("() => state.settings.music_volume === 1 && !state.settingsWriting && !state.settingsTimer");
        page.waitForFunction("() => state.audio.musicGain.gain.value > .99");
        assertThat(((Number) page.evaluate("() => state.audio.musicGain.gain.value")).doubleValue()).isCloseTo(1, within(.01));
        assertThat(slider.inputValue()).isEqualTo("100");
        assertThat(slider.getAttribute("aria-valuetext")).isEqualTo("100%");
        assertThat(page.locator("#music-volume-value").textContent()).isEqualTo("100%");
        final var context = page.evaluateHandle("() => state.audio.musicContext");
        slider.press("Home");
        page.waitForFunction("() => state.audio.musicGain.gain.value < .0001 && !state.settingsWriting && !state.settingsTimer");
        assertThat(slider.inputValue()).isEqualTo("0");
        assertThat(page.locator("#music-volume-value").textContent()).isEqualTo("0%");
        assertThat(page.evaluate("context => context === state.audio.musicContext && context.state === 'running'", context)).isEqualTo(true);
        assertThat(Files.readString(directory.resolve("railix-home/creator.settings.json"))).contains("\"music_volume\":0");
        slider.press("End");
        page.waitForFunction("() => state.audio.musicGain.gain.value > .99 && !state.settingsWriting && !state.settingsTimer");
        page.reload();
        page.locator("#open-settings").click();
        page.locator("#settings-music-tab").click();
        page.waitForFunction("() => document.querySelector('#music-volume').value === '100'");
        assertThat(page.locator("#music-volume-value").textContent()).isEqualTo("100%");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {390, 1280})
    void audioSlidersReachBothVisibleEnds(final int width) {
        page.setViewportSize(width, 800);
        page.locator("#open-settings").click();
        for (final String kind : List.of("music", "effects")) {
            page.locator("#settings-" + (kind.equals("music") ? "music" : "sound") + "-tab").click();
            final var slider = page.locator("#" + kind + "-volume");
            final var box = slider.boundingBox();
            page.mouse().click(box.x + box.width - 2, box.y + box.height / 2);
            assertThat(slider.inputValue()).isEqualTo("100");
            assertThat(slider.getAttribute("aria-valuetext")).isEqualTo("100%");
            page.mouse().click(box.x + 2, box.y + box.height / 2);
            assertThat(slider.inputValue()).isEqualTo("0");
            assertThat(slider.evaluate("el => getComputedStyle(el).padding")).isEqualTo("0px");
        }
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void originalMusicProvidesDistinctTracksAndSettingsPreserveUnsavedScores() {
        page.locator("#open-settings").click();
        page.locator("#settings-music-tab").click();
        page.waitForFunction("() => !document.querySelector('#music-play').disabled");
        page.waitForFunction("() => state.audio.musicContext?.state === 'running'");
        final List<String> expectedNames = (List<String>) page.evaluate("() => state.audio.music().map(entry => entry.score.name)");
        final var names = new java.util.HashSet<String>();
        names.add((String) page.evaluate("() => state.audio.track.score.name"));
        for (int index = 1; index < expectedNames.size(); index++) {
            final String before = (String) page.evaluate("() => state.audio.track.score.name");
            page.locator("#music-next").click();
            page.waitForFunction("name => state.audio.track?.score.name !== name && state.audio.musicSession", before);
            names.add((String) page.evaluate("() => state.audio.track.score.name"));
        }
        assertThat(expectedNames).hasSize(8);
        assertThat(names).containsExactlyInAnyOrderElementsOf(expectedNames);
        final String last = (String) page.evaluate("() => state.audio.track.key");
        page.locator("#music-next").click();
        page.waitForFunction("key => state.audio.track?.key !== key && state.audio.musicSession", last);
        final List<List<Number>> lengths = (List<List<Number>>) page.evaluate("""
                () => state.audio.music().map(entry => {
                  const beats = Math.max(...entry.score.tracks.map(track => state.audio.trackDuration(track.notes)));
                  return [beats, beats * 60 / entry.score.tempo];
                })
                """);
        assertThat(lengths).allSatisfy(length -> {
            assertThat(length.get(1).doubleValue()).as("Complete compositions, not preview loops").isBetween(180d, 300d);
        });
        final List<List<Number>> melodies = (List<List<Number>>) page.evaluate("""
                () => state.audio.music().map(entry => {
                  const pitches=[];
                  for (const note of state.audio.sequence(entry.score.tracks[0].notes)) {
                    if (note.note !== null) pitches.push(note.note);
                    if (pitches.length === 32) break;
                  }
                  // Compare the lead theme independently of tempo, timbre, transposition or octave.
                  return pitches.map(pitch => ((pitch-pitches[0])%12+12)%12);
                })
                """);
        assertThat(melodies).allSatisfy(melody -> assertThat(melody).hasSize(32));
        assertThat(melodies).as("Each soundtrack has its own lead theme, not a retimed or transposed copy")
                .doesNotHaveDuplicates();
        page.locator("#music-stop").click();
        page.locator("#sound-files > summary").click();
        final Number minimumContrast = (Number) page.evaluate("""
                () => {
                  const luminance = color => color.match(/[\\d.]+/g).slice(0, 3)
                    .map(value => Number(value) / 255)
                    .map(value => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4)
                    .reduce((sum, value, index) => sum + value * [.2126, .7152, .0722][index], 0);
                  return Math.min(...['music-group', 'sound-file', 'sound-id', 'sound-score'].map(id => {
                    const style = getComputedStyle(document.getElementById(id));
                    const values = [luminance(style.color), luminance(style.backgroundColor)].sort((a, b) => a - b);
                    return (values[1] + .05) / (values[0] + .05);
                  }));
                }
                """);
        assertThat(minimumContrast.doubleValue()).as("Settings fields remain readable").isGreaterThanOrEqualTo(4.5);
        page.locator("#sound-score").fill("unfinished local draft");
        page.keyboard().press("Escape");
        page.locator("#open-settings").click();
        page.waitForFunction("() => document.querySelector('#audio-status').textContent.includes('ready')");
        assertThat(page.locator("#sound-score").inputValue()).isEqualTo("unfinished local draft");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sounds/draft.mml", "music/quiet/draft.mml"})
    void refreshingSoundCatalogDoesNotRebaseAnUnsavedDraft(final String id) throws Exception {
        final Path file = directory.resolve("railix-home").resolve(id);
        Files.createDirectories(file.getParent());
        final String source = "name: Original\ntempo: 120\nsine .2 .01 .1 | c4";
        Files.writeString(file, source);
        page.locator("#open-settings").click();
        page.locator(id.startsWith("music/") ? "#settings-music-tab" : "#settings-sound-tab").click();
        page.locator("#sound-files > summary").click();
        page.waitForFunction("id => state.audio.scores.some(score => score.key === 'local:' + id)", id);
        page.locator("#sound-file").selectOption("local:" + id);
        final String draft = source.replace("Original", "My draft");
        page.locator("#sound-score").fill(draft);
        final Object revision = page.evaluate("() => state.audio.revision");
        final String external = source.replace("Original", "External edit");
        Files.writeString(file, external);
        page.keyboard().press("Escape");
        page.locator("#open-settings").click();
        page.locator(id.startsWith("music/") ? "#settings-music-tab" : "#settings-sound-tab").click();
        page.waitForFunction("revision => state.audio.revision !== revision", revision);
        assertThat(page.locator("#sound-score").inputValue()).isEqualTo(draft);
        final var conflict = page.waitForResponse(response -> response.url().endsWith("/api/sounds")
                        && response.request().method().equals("POST"), () -> page.locator("#sound-save").click());
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(Files.readString(file)).isEqualTo(external);
        assertThat(page.locator("#sound-score").inputValue()).isEqualTo(draft);
        page.onDialog(dialog -> {
            assertThat(dialog.type()).isEqualTo("confirm");
            dialog.accept();
        });
        page.locator("#sound-file").selectOption("local:" + id);
        assertThat(page.locator("#sound-score").inputValue()).isEqualTo(external);
        page.locator("#sound-score").fill(source);
        final var saved = page.waitForResponse(response -> response.url().endsWith("/api/sounds")
                        && response.request().method().equals("POST"), () -> page.locator("#sound-save").click());
        assertThat(saved.status()).isEqualTo(200);
        assertThat(Files.readString(file)).isEqualTo(source);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void musicAutomaticallyAdvancesAfterPauseAndWrapsWithoutRepeating() throws Exception {
        final Path group = Files.createDirectories(directory.resolve("railix-home/music/short"));
        for (final String name : List.of("Alpha", "Beta")) {
            Files.writeString(group.resolve(name + ".mml"), "name: " + name + "\ntempo: 120\nsine .2 .01 .1 | c@4");
        }
        page.locator("#open-settings").click();
        page.locator("#settings-music-tab").click();
        page.waitForFunction("() => Array.from(document.querySelector('#music-group').options).some(option=>option.value==='group:short')");
        page.locator("#music-group").selectOption("group:short");
        page.locator("#music-play").click();
        page.waitForFunction("() => state.audio.musicContext?.state === 'running' && state.audio.track?.group === 'short'");
        final String first = (String) page.evaluate("() => state.audio.track.key");
        page.locator("#music-play").click();
        page.waitForFunction("() => state.audio.musicContext?.state === 'suspended'");
        assertThat(page.evaluate("""
                async () => {
                  const context=state.audio.musicContext, time=context.currentTime, key=state.audio.track.key;
                  await new Promise(resolve=>setTimeout(resolve,600));
                  return context.currentTime===time && state.audio.track.key===key;
                }
                """)).isEqualTo(true);
        page.locator("#music-play").click();
        page.waitForFunction("key => state.audio.track?.key !== key && state.audio.musicSession", first);
        page.waitForFunction("key => state.audio.track?.key === key && state.audio.musicSession", first);
        page.locator("#music-stop").click();
        assertThat(page.evaluate("() => state.audio.musicContext")).isNull();
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"kick", "snare", "hat"})
    void percussionScoresProduceShortDecayingHitsWithOneReusableVoice(final String instrument) {
        final var measured = (java.util.Map<?, ?>) page.evaluate("""
                async instrument => {
                  const response = await fetch('/api/sounds', {method:'POST',headers:mutationHeaders(),
                    body:JSON.stringify({action:'preview',content:`name: Percussion\\ntempo: 120\\n${instrument} .6 .002 .12 | o2 c@1 r@1 c@1 r@1`})});
                  if (!response.ok) return {status:response.status};
                  const {score} = await response.json(), context = new OfflineAudioContext(1,44100*3,44100);
                  const session = state.audio.schedule(context,score,context.destination,1);
                  const buffer = await context.startRendering(), samples = buffer.getChannelData(0);
                  const rms = (from,to) => Math.sqrt(samples.slice(from*44100,to*44100).reduce((sum,v)=>sum+v*v,0)/((to-from)*44100));
                  const result = {status:response.status,voices:session.voices.length,hit:rms(.045,.09),tail:rms(.3,.45),second:rms(1.045,1.09)};
                  state.audio.stopSession(session);
                  return result;
                }
                """, instrument);
        assertThat(measured.get("status")).isEqualTo(200);
        assertThat(measured.get("voices")).isEqualTo(1);
        assertThat(((Number) measured.get("hit")).doubleValue()).isGreaterThan(.005);
        assertThat(((Number) measured.get("tail")).doubleValue()).isLessThan(.001);
        assertThat(((Number) measured.get("second")).doubleValue()).isGreaterThan(.005);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void tonalEnvelopeDecaysWithoutAllocatingAnotherVoiceForEachNote() {
        final var measured = (java.util.Map<?, ?>) page.evaluate("""
                async () => {
                  const response = await fetch('/api/sounds', {method:'POST',headers:mutationHeaders(),
                    body:JSON.stringify({action:'preview',content:'name: Pluck\\ntempo: 120\\nsawtooth .5 .005 .12 decay=.12 sustain=.08 cutoff=1800 | o3 a@2 a@2'})});
                  if (!response.ok) return {status:response.status};
                  const {score} = await response.json(), context = new OfflineAudioContext(1,44100*3,44100);
                  const session = state.audio.schedule(context,score,context.destination,1);
                  const buffer = await context.startRendering(), samples = buffer.getChannelData(0);
                  const rms = (from,to) => Math.sqrt(samples.slice(from*44100,to*44100).reduce((sum,v)=>sum+v*v,0)/((to-from)*44100));
                  const result = {status:response.status,voices:session.voices.length,attack:rms(.05,.08),
                    sustain:rms(.3,.5),release:rms(.98,1.03),second:rms(1.05,1.08)};
                  state.audio.stopSession(session);
                  return result;
                }
                """);
        assertThat(measured.get("status")).isEqualTo(200);
        assertThat(measured.get("voices")).isEqualTo(1);
        final double attack = ((Number) measured.get("attack")).doubleValue();
        assertThat(attack).isGreaterThan(.01);
        assertThat(((Number) measured.get("sustain")).doubleValue()).isLessThan(attack / 3);
        assertThat(((Number) measured.get("release")).doubleValue()).isLessThan(attack / 8);
        assertThat(((Number) measured.get("second")).doubleValue()).isGreaterThan(attack * .8);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void longMusicSchedulesABoundedWindowAndKeepsPlayingAcrossRefills() {
        final var result = (java.util.Map<?, ?>) page.evaluate("""
                async () => {
                  const response=await fetch('/api/sounds',{method:'POST',headers:mutationHeaders(),
                    body:JSON.stringify({action:'preview',content:'name: Long\\ntempo: 120\\nsawtooth .4 .01 .1 | o3 /: /: a8 e8 a8 c8 :/16 :/16'})});
                  if(!response.ok) return {status:response.status};
                  const {score}=await response.json(), rate=16000, context=new OfflineAudioContext(1,rate*5,rate);
                  const set=AudioParam.prototype.setValueAtTime, times=[];
                  AudioParam.prototype.setValueAtTime=function(value,time){times.push(time);return set.call(this,value,time);};
                  let session;
                  try {
                    session=state.audio.schedule(context,score,context.destination,1);
                    const initialLatest=Math.max(...times), initialEvents=times.length;
                    const refills=[1,2,3,4].map(time=>context.suspend(time).then(()=>{session.pump();return context.resume();}));
                    const buffer=await context.startRendering();
                    await Promise.all(refills);
                    const samples=buffer.getChannelData(0);
                    const rms=(from,to)=>Math.sqrt(samples.slice(from*rate,to*rate).reduce((sum,v)=>sum+v*v,0)/((to-from)*rate));
                    state.audio.stopSession(session);
                    const before=times.length;
                    session.pump();
                    return {status:response.status,initialLatest,initialEvents,first:rms(.1,.2),later:rms(4.1,4.2),
                      writesAfterStop:times.length-before};
                  } finally {AudioParam.prototype.setValueAtTime=set;state.audio.stopSession(session);}
                }
                """);
        assertThat(result.get("status")).isEqualTo(200);
        assertThat(((Number) result.get("initialLatest")).doubleValue()).isLessThan(3);
        assertThat(((Number) result.get("initialEvents")).intValue()).isLessThan(100);
        assertThat(((Number) result.get("first")).doubleValue()).isGreaterThan(.01);
        assertThat(((Number) result.get("later")).doubleValue()).isGreaterThan(.01);
        assertThat(((Number) result.get("writesAfterStop")).intValue()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void delayedMusicRefillsRecoverActiveNotesWithoutMovingTheirEnd(final boolean loop) {
        final var measured = (java.util.Map<?, ?>) page.evaluate("""
                async loop => {
                  const response=await fetch('/api/sounds',{method:'POST',headers:mutationHeaders(),
                    body:JSON.stringify({action:'preview',content:'name: Recovery\\ntempo: 120\\nsine .3 .01 .1 | r@5 c@4 r@1\\nsine .3 .01 .1 | r@6 e@2 r@2'})});
                  if(!response.ok) return {status:response.status};
                  const {score}=await response.json(), rate=16000, context=new OfflineAudioContext(1,rate*10.5,rate);
                  let completed=0;
                  const session=state.audio.schedule(context,score,context.destination,1,()=>completed++,loop);
                  const advances=[3.5,4.5,5.5,8.5,9.5,10].map(time=>context.suspend(time).then(()=>{session.pump();return context.resume();}));
                  const rendered=await context.startRendering();
                  await Promise.all(advances);
                  const samples=rendered.getChannelData(0);
                  const rms=(from,to)=>Math.sqrt(samples.slice(from*rate,to*rate).reduce((sum,v)=>sum+v*v,0)/((to-from)*rate));
                  const result={status:response.status,recovered:rms(3.65,3.85),ended:rms(4.7,4.85),nextCycle:rms(8.65,8.85),completed};
                  state.audio.stopSession(session);
                  return result;
                }
                """, loop);
        assertThat(measured.get("status")).isEqualTo(200);
        assertThat(((Number) measured.get("recovered")).doubleValue()).isGreaterThan(.01);
        assertThat(((Number) measured.get("ended")).doubleValue()).isLessThan(.0001);
        assertThat(((Number) measured.get("nextCycle")).doubleValue() > .01).isEqualTo(loop);
        assertThat(((Number) measured.get("completed")).intValue()).isEqualTo(loop ? 0 : 1);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"kick", "snare", "hat"})
    void delayedMusicRefillsDiscardMissedPercussionWithoutLosingTheNextHit(final String waveform) {
        final var measured = (java.util.Map<?, ?>) page.evaluate("""
                async waveform => {
                  const response=await fetch('/api/sounds',{method:'POST',headers:mutationHeaders(),
                    body:JSON.stringify({action:'preview',content:`name: Percussion recovery\ntempo: 120\n${waveform} .5 .002 .12 | r@5 c@4 c@1`})});
                  if(!response.ok) return {status:response.status};
                  const {score}=await response.json(), rate=16000, context=new OfflineAudioContext(1,rate*5,rate);
                  const session=state.audio.schedule(context,score,context.destination,1);
                  const refill=context.suspend(3.5).then(()=>{session.pump();return context.resume();});
                  const rendered=await context.startRendering();
                  await refill;
                  const samples=rendered.getChannelData(0);
                  const rms=(from,to)=>Math.sqrt(samples.slice(from*rate,to*rate).reduce((sum,v)=>sum+v*v,0)/((to-from)*rate));
                  state.audio.stopSession(session);
                  return {status:response.status,missed:rms(3.51,3.64),next:rms(4.54,4.66)};
                }
                """, waveform);
        assertThat(measured.get("status")).isEqualTo(200);
        assertThat(((Number) measured.get("missed")).doubleValue()).isLessThan(.0001);
        assertThat(((Number) measured.get("next")).doubleValue()).isGreaterThan(.0001);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void stereoInstrumentControlsRenderEchoesAndStopAllAudio() {
        final var measured = (java.util.Map<?, ?>) page.evaluate("""
                async () => {
                  const response = await fetch('/api/sounds', {method:'POST',headers:mutationHeaders(),
                    body:JSON.stringify({action:'preview',content:'name: Stereo\\ntempo: 120\\nsawtooth .5 .005 .06 decay=.07 sustain=.1 cutoff=2400 detune=7 drive=2 pan=-.6 echo=.3 | o4 a@.25 r@4 /: e@.25 r@.25 :/8'})});
                  if (!response.ok) return {status:response.status};
                  const {score}=await response.json(), rate=44100, context=new OfflineAudioContext(2,rate*2,rate);
                  let oscillators=0;
                  const createOscillator=context.createOscillator.bind(context);
                  context.createOscillator=()=>{ oscillators++; return createOscillator(); };
                  const session=state.audio.schedule(context,score,context.destination,1);
                  const buffer=await context.startRendering(), left=buffer.getChannelData(0), right=buffer.getChannelData(1);
                  const rms=(samples,from,to)=>Math.sqrt(samples.slice(from*rate,to*rate).reduce((sum,v)=>sum+v*v,0)/((to-from)*rate));
                  const result={status:response.status,oscillators,left:rms(left,.05,.13),right:rms(right,.05,.13),
                    gap:rms(left,.23,.3),echo:rms(left,.43,.51)+rms(right,.43,.51),
                    peak:Math.max(...[left,right].map(samples=>samples.reduce((peak,v)=>Math.max(peak,Math.abs(v)),0)))};
                  state.audio.stopSession(session);
                  state.audio.stopSession(session);
                  const silentContext=new OfflineAudioContext(2,rate,rate);
                  const stopped=state.audio.schedule(silentContext,score,silentContext.destination,1);
                  state.audio.stopSession(stopped);
                  const silent=await silentContext.startRendering();
                  result.stoppedPeak=Math.max(...[0,1].map(channel=>silent.getChannelData(channel).reduce((peak,v)=>Math.max(peak,Math.abs(v)),0)));
                  return result;
                }
                """);
        assertThat(measured.get("status")).isEqualTo(200);
        assertThat(((Number) measured.get("oscillators")).intValue()).isEqualTo(2);
        assertThat(((Number) measured.get("left")).doubleValue()).isGreaterThan(((Number) measured.get("right")).doubleValue() * 2);
        assertThat(((Number) measured.get("gap")).doubleValue()).isLessThan(.001);
        assertThat(((Number) measured.get("echo")).doubleValue()).isGreaterThan(.002);
        assertThat(((Number) measured.get("peak")).doubleValue()).isBetween(.01, .95);
        assertThat(((Number) measured.get("stoppedPeak")).doubleValue()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void embeddedMusicRendersAudibleUnclippedReviewExcerpts() throws Exception {
        page.locator("#open-settings").click();
        page.locator("#settings-music-tab").click();
        page.waitForFunction("() => state.audio.music().length > 0");
        page.locator("#music-stop").click();
        page.locator("#music-volume").press("End");
        final List<String> keys = (List<String>) page.evaluate("() => state.audio.music().filter(entry=>entry.builtin).map(entry=>entry.key)");
        assertThat(keys).hasSize(8);
        final boolean full = Boolean.getBoolean("railix.audio.review.full");
        final Path output = Files.createDirectories(Path.of("target", "audio-review", full ? "full" : "excerpts"));
        for (final String key : keys) {
            final var excerpt = (java.util.Map<?, ?>) page.evaluate("""
                async ([key,full]) => {
                    const entry=state.audio.music().find(entry=>entry.key===key);
                    const durations = entry.score.tracks.map(track=>state.audio.trackDuration(track.notes));
                    const seconds = full ? Math.max(...durations)*60/entry.score.tempo+2 : 32;
                    const rate=full ? 44100 : 22050, context=new OfflineAudioContext(2,Math.ceil(rate*seconds),rate);
                    const master=context.createGain();
                    master.gain.value=state.audio.musicVolume();
                    master.connect(context.destination);
                    const session=state.audio.schedule(context,entry.score,master,1);
                    const refills=[];
                    for(let time=1;time<seconds;time++) refills.push(context.suspend(time).then(()=>{session.pump();return context.resume();}));
                    const buffer=await context.startRendering(), channels=[buffer.getChannelData(0),buffer.getChannelData(1)];
                    await Promise.all(refills);
                    let peak=0, power=0;
                    const bytes=new Uint8Array(44+buffer.length*4), view=new DataView(bytes.buffer);
                    const text=(at,value)=>[...value].forEach((char,i)=>view.setUint8(at+i,char.charCodeAt(0)));
                    text(0,'RIFF'); view.setUint32(4,bytes.length-8,true); text(8,'WAVE'); text(12,'fmt ');
                    view.setUint32(16,16,true); view.setUint16(20,1,true); view.setUint16(22,2,true);
                    view.setUint32(24,rate,true); view.setUint32(28,rate*4,true); view.setUint16(32,4,true);
                    view.setUint16(34,16,true); text(36,'data'); view.setUint32(40,buffer.length*4,true);
                    channels.forEach((samples,channel)=>samples.forEach((value,i)=>{
                      peak=Math.max(peak,Math.abs(value)); power+=value*value;
                      view.setInt16(44+i*4+channel*2,Math.round(Math.max(-1,Math.min(1,value))*32767),true);
                    }));
                    let binary='';
                    for(let i=0;i<bytes.length;i+=8192) binary+=String.fromCharCode(...bytes.subarray(i,i+8192));
                    let minimumWindow=Infinity;
                    for(let from=16;from<seconds-16;from+=16) {
                      let sum=0;
                      for(let i=from*rate;i<(from+8)*rate;i++) sum+=channels[0][i]**2+channels[1][i]**2;
                      minimumWindow=Math.min(minimumWindow,Math.sqrt(sum/(16*rate)));
                    }
                    state.audio.stopSession(session);
                    master.disconnect();
                    return {name:entry.score.name,durations,content:entry.score.content,seconds,minimumWindow,
                      peak,rms:Math.sqrt(power/(buffer.length*2)),wav:btoa(binary)};
                }
                """, List.of(key, full));
            assertThat(((Number) excerpt.get("peak")).doubleValue()).isBetween(.001, .95);
            assertThat(((Number) excerpt.get("rms")).doubleValue()).as("Audible music at 100% master volume: " + key).isGreaterThan(.02);
            if (full) assertThat(((Number) excerpt.get("minimumWindow")).doubleValue()).as("No silent gaps after scheduler refills: " + key).isGreaterThan(.0001);
            final String name = excerpt.get("name").toString().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            Files.write(output.resolve(name + ".wav"), java.util.Base64.getDecoder().decode(excerpt.get("wav").toString()));
            final var durations = (List<Number>) excerpt.get("durations");
            assertThat(durations).allSatisfy(value -> assertThat(value.doubleValue()).isCloseTo(durations.getFirst().doubleValue(), org.assertj.core.data.Offset.offset(.000001)));
            Files.writeString(output.resolve(name + ".mml"), excerpt.get("content").toString());
            System.out.printf("Audio review: %s; %.2f s; peak %.4f; RMS %.4f%n", name, ((Number) excerpt.get("seconds")).doubleValue(),
                    ((Number) excerpt.get("peak")).doubleValue(), ((Number) excerpt.get("rms")).doubleValue());
        }
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void newMusicScoreUsesItsSelectedGroupAndReleasesPreviewOnHide() throws Exception {
        page.locator("#open-settings").click();
        page.locator("#settings-music-tab").click();
        page.locator("#music-group").selectOption("group:quiet");
        page.locator("#sound-files > summary").click();
        page.locator("#sound-file").selectOption("");
        assertThat(page.locator("#sound-id").inputValue()).isEqualTo("music/quiet/new.mml");
        page.locator("#sound-score").fill("name: Local melody\ntempo: 60\nsine .2 .01 .1 | o4 [ceg]@16");
        page.locator("#sound-save").click();
        page.waitForFunction("() => !state.audio.dirtyEditor && !state.audio.mutation");
        assertThat(Files.readString(directory.resolve("railix-home/music/quiet/new.mml"))).contains("Local melody");
        page.locator("#sound-preview").click();
        page.waitForFunction("() => state.audio.previewSession");
        final var context = page.evaluateHandle("() => state.audio.previewContext");
        page.evaluate("""
                () => { Object.defineProperty(document,'hidden',{configurable:true,value:true});
                  document.dispatchEvent(new Event('visibilitychange')); }
                """);
        page.waitForFunction("context => context.state === 'closed'", context);
        assertThat(page.evaluate("() => state.audio.previewContext")).isNull();
        page.evaluate("() => { delete document.hidden; document.dispatchEvent(new Event('visibilitychange')); }");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void stoppingMusicCancelsAnInFlightCatalogStart() {
        assertThat(page.evaluate("""
                async () => {
                  state.audio.dispose();
                  const audio = new RailixAudio(document.querySelector('#audio-panel'));
                  try {
                    const pending = audio.play();
                    audio.stop();
                    await pending;
                    return Boolean(audio.musicContext || audio.musicSession);
                  } finally { audio.dispose(); }
                }
                """)).isEqualTo(false);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void musicStopPersistsMuteAndSettingsPlayRestoresIt() {
        openProject(fourStepProject());
        page.locator("#open-settings").click();
        page.locator("#settings-music-tab").click();
        page.waitForFunction("() => !document.querySelector('#music-play').disabled");
        page.waitForResponse(response -> response.url().endsWith("/api/settings")
                && response.request().method().equals("POST") && response.status() == 200,
                () -> page.locator("#music-stop").click());
        page.waitForFunction("() => state.settings.music_enabled === false");
        page.reload();
        page.waitForFunction("() => state.settings.music_enabled === false");
        page.locator("#open-settings").click();
        page.locator("#settings-music-tab").click();
        page.locator("#music-play").click();
        page.waitForFunction("() => state.settings.music_enabled === true && state.audio.musicContext?.state === 'running'");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void reloadedMusicSelectionDiscardsThePreviousShuffleQueue() throws Exception {
        page.locator("#open-settings").click();
        page.locator("#settings-music-tab").click();
        page.waitForFunction("() => state.audio.musicContext?.state === 'running' && state.audio.queue.length > 0");
        final String track = (String) page.evaluate("() => state.audio.track.key");
        page.keyboard().press("Escape");
        page.waitForFunction("() => !state.settingsWriting && !state.settingsTimer");
        final String settings = (String) page.evaluate("""
                track => JSON.stringify({...state.settings, music:'track:' + track})
                """, track);
        Files.writeString(directory.resolve("railix-home/creator.settings.json"), settings);
        page.locator("#open-settings").click();
        page.waitForFunction("track => state.audio.preferences.music === 'track:' + track", track);
        page.locator("#settings-music-tab").click();
        page.locator("#music-play").click();
        page.waitForFunction("() => state.audio.musicContext?.state === 'running'");
        assertThat(page.evaluate("() => state.audio.track.key")).isEqualTo(track);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"app", "command", "matched"})
    void emptyFloorClearsSelectionWithoutEditingOrLosingTheExample(final String id) throws Exception {
        openProject(choiceProject());
        selectWorldNode("command");
        chooseExampleIndex(1);
        selectWorldNode(id);
        final String source = Files.readString(directory.resolve("project.json"));
        page.locator("#graph").click(new com.microsoft.playwright.Locator.ClickOptions().setPosition(8, 220));
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isBlank();
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        page.waitForFunction("() => !state.world._surface.stations.some(item=>item.look.selected)");
        page.evaluate("() => void state.world.fit()");
        awaitScene();
        page.waitForFunction("() => !state.world._surface.stations.some(item=>item.look.selected)");
        waitForCoverage("otherwise", "selected");
        selectWorldNode("command");
        assertThat(page.locator("#visible-example").inputValue()).isEqualTo("command:1");
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(source);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void escapeClosesTheInspectorBeforeClearingSelection() {
        openProject(fourStepProject());
        selectWorldNode("one");
        page.keyboard().press("Escape");
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isNotBlank();
        page.keyboard().press("Escape");
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isBlank();
        page.waitForFunction("() => !state.world._surface.stations.some(item=>item.look.selected)");
        page.keyboard().press("Escape");
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void escapeAlsoClearsAVisualGroup() throws Exception {
        openProject(fourStepProject());
        assertThat(page.evaluate("""
                async () => (await fetch('/api/creator', {method:'POST', headers:mutationHeaders(), body:JSON.stringify({format:2,
                  groups:[{id:'prepare',name:'Preparation'}],steps:Object.fromEntries(['one','two','three','four'].map(id=>[id,{group:'prepare'}]))})})).status
                """)).isEqualTo(200);
        page.reload();
        waitForText("#build-state", "Built");
        page.locator("[data-region-group=prepare]").click();
        page.locator("#world-location [data-inspect-region]").click();
        openInspectorTab("overview");
        final String metadata = Files.readString(directory.resolve("railix.creator.json"));
        clearWorldSelection();
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isBlank();
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        page.waitForFunction("() => !state.world._surface.stations.some(item=>item.look.selected)");
        page.locator("#world-location [data-inspect-region]").click();
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isNotBlank();
        openInspectorTab("overview");
        final var editor = page.waitForResponse(response -> response.url().contains("/api/editor?"),
                () -> page.locator("[data-inspector-mode=groups]").click());
        assertThat(editor.status()).isEqualTo(200);
        page.locator("[data-inspector-mode=groups].active").waitFor();
        assertThat(Files.readString(directory.resolve("railix.creator.json"))).isEqualTo(metadata);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void draggingTheFloorPreservesSelection() {
        openProject(fourStepProject());
        selectWorldNode("one");
        page.locator("#close-inspector").click();
        final var floor = page.locator("#graph").boundingBox();
        page.mouse().move(floor.x + 8, floor.y + 220);
        page.mouse().down();
        page.mouse().move(floor.x + 80, floor.y + 230);
        page.mouse().up();
        awaitScene();
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isEqualTo("one");
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isNotBlank();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void escapeDismissesAudioWithoutClearingSelection() {
        openProject(fourStepProject());
        selectWorldNode("one");
        page.locator("#close-inspector").click();
        page.locator("#open-settings").click();
        page.keyboard().press("Escape");
        assertThat(page.locator("#audio-panel").isVisible()).isFalse();
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isNotBlank();
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isEqualTo("one");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void clearingSelectionSupersedesAnAlreadySuccessfulEditorResponse() {
        openProject(choiceProject());
        page.evaluate("""
                () => {
                  const fetch = window.fetch;
                  window.fetch = async (...args) => {
                    const response = await fetch.apply(window, args);
                    const url = new URL(String(args[0]), location.href);
                    // Hover reads omit group; hold only the actual selection response.
                    if (url.pathname === '/api/editor' && url.searchParams.get('node') === 'command'
                        && url.searchParams.has('group')) {
                      const body = await response.text();
                      response.text = async () => body;
                      window.editorStatus = response.status;
                      await new Promise(resolve => { window.releaseEditor = resolve; });
                    }
                    return response;
                  };
                  window.restoreEditor = () => { window.releaseEditor?.(); window.fetch = fetch; };
                }
                """);
        try {
            page.locator("[data-select-node=command]").click();
            page.waitForFunction("() => Boolean(window.releaseEditor)");
            assertThat(page.evaluate("() => window.editorStatus")).isEqualTo(200);
            clearWorldSelection();
            page.evaluate("() => window.restoreEditor()");
            page.waitForFunction("() => !state.editorController");
            assertThat(page.locator("#inspector").getAttribute("data-selection")).isBlank();
        page.waitForFunction("() => !state.world._surface.stations.some(item=>item.look.selected)");
            assertThat(pageErrors).isEmpty();
        } finally {
            page.evaluate("() => window.restoreEditor()");
        }
    }

    @Test
    void hudUsesCssSymbolsWithConsistentQuietControls() {
        openProject(fourStepProject());
        assertThat(page.locator(".build-indicator").evaluate("element => getComputedStyle(element).color"))
                .as("Running keeps its green status color under the shared button material")
                .isEqualTo("rgb(160, 220, 204)");
        assertThat(page.locator("#open-settings").evaluate("element => getComputedStyle(element).color"))
                .as("Unselected HUD controls retain readable foreground contrast")
                .isEqualTo("rgb(226, 235, 238)");
        selectWorldNode("one");
        final var configure = page.locator("#open-settings");
        assertThat(configure.textContent()).isBlank();
        assertThat(configure.getAttribute("title")).isEqualTo("Settings");
        assertThat(configure.locator(".hud-symbol").getAttribute("aria-hidden")).isEqualTo("true");
        assertThat(configure.evaluate("element => getComputedStyle(element).boxShadow")).isEqualTo("none");
        final var box = configure.boundingBox();
        page.mouse().move(box.x + box.width / 2, box.y + box.height / 2);
        page.mouse().down();
        assertThat(configure.evaluate("element => getComputedStyle(element).translate")).isEqualTo("none");
        page.mouse().up();
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        assertThat(page.locator(".inspector-tabs .active").evaluate("element => getComputedStyle(element).backgroundImage"))
                .isEqualTo("none");
        assertThat(page.locator(".inspector-tabs .active").evaluate("element => getComputedStyle(element).boxShadow"))
                .as("The active tab has an underline instead of a raised face")
                .isNotEqualTo("none");
        assertThat(page.locator(".inspector-tabs button:not(.active)").first().evaluate("element => getComputedStyle(element).boxShadow"))
                .isEqualTo("none");
        assertThat(page.locator(".topbar svg, .canvas-tools svg, .inspector-chrome svg").count()).isZero();
        assertThat(page.locator("#close-inspector .hud-symbol").getAttribute("data-symbol")).isEqualTo("close");
        assertThat(page.locator(".hud-symbol").evaluateAll("elements => elements.filter(element => getComputedStyle(element).maskImage === 'none').map(element => element.dataset.symbol)"))
                .as("Every shared control has real artwork instead of a missing-mask square")
                .isEqualTo(List.of());
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void clearingSelectionKeepsApplicationStatusUpdating() {
        openProject(fourStepProject());
        selectWorldNode("command");
        page.waitForFunction("() => document.querySelector('#status-memory').textContent.startsWith('Heap ')");
        final var response = page.waitForResponse(result -> result.url().endsWith("/api/metrics"),
                new Page.WaitForResponseOptions().setTimeout(5_000), () -> clearWorldSelection());
        assertThat(response.status()).isEqualTo(200);
        page.locator(".build-indicator").click();
        page.locator("#runtime-summary > summary").click();
        page.locator("#status-memory").waitFor();
        assertThat(page.locator("#status-memory").textContent()).startsWith("Heap ");
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isBlank();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void groupTimingShowsTheSlowestStepAndSelectionKeepsTheInspectorOpen() throws Exception {
        openProject(fourStepProject());
        assertThat(page.evaluate("""
                async () => (await fetch('/api/creator', {method:'POST', headers:mutationHeaders(), body:JSON.stringify({format:2,
                  groups:[{id:'prepare',name:'Preparation'}],steps:Object.fromEntries(['one','two','three','four'].map(id=>[id,{group:'prepare'}]))})})).status
                """)).isEqualTo(200);
        page.reload();
        waitForText("#build-state", "Built");
        chooseVisibleExample("command:0");
        page.waitForFunction("() => Boolean(document.querySelector('[data-region-group=prepare] .world-duration')?.textContent)");
        assertThat(page.locator("[data-region-group=prepare] .world-duration").textContent()).doesNotContain("avg sum");
        final Number slowest = (Number) page.evaluate("""
                async () => {
                  let maximum = 0;
                  for (const id of ['one','two','three','four']) {
                    const response = await (await fetch('/api/metrics/nodes/'+id)).json();
                    const value = response.steps.find(step=>step.id === id).metrics;
                    maximum = Math.max(maximum,value.duration_nanos_total / value.duration_samples);
                  }
                  return maximum;
                }
                """);
        final String label = page.locator("[data-region-group=prepare] .world-duration").textContent();
        final String regionId = page.locator("[data-region-group=prepare]").getAttribute("data-group-region-label");
        final String[] parts = label.split(" ");
        final double unit = switch (parts[1]) {case "us" -> 1_000; case "ms" -> 1_000_000; case "s" -> 1_000_000_000; default -> 1;};
        assertThat(Double.parseDouble(parts[0]) * unit).isCloseTo(slowest.doubleValue(), org.assertj.core.data.Offset.offset(unit / 2));
        page.locator(".app-node").click();
        openInspectorTab("overview");
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        page.evaluate("id => void state.world.focus(id,.5)",regionId);
        awaitScene();
        page.locator("[data-region-group=prepare]").click();
        page.locator("#world-location [data-inspect-region]").click();
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isEqualTo(regionId);
        assertThat(page.locator("#inspector").textContent()).contains("Preparation", "Group metrics");
        page.waitForFunction("() => document.querySelector('[data-region-group=prepare]')?.title.includes('Group timing summary unavailable.')");
        assertThat(page.locator("[data-region-group=prepare]").getAttribute("title")).doesNotContain("0/4 sampled Steps");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void replayAndPowerActuallyAnimateWithoutAdditionalExecutions() {
        openProject(choiceProject());
        selectWorldNode("command");
        page.waitForFunction("() => document.querySelector('#graph').dataset.replaying === 'true'");
        assertWorldAnimation();
        page.evaluate("() => void state.world.fit()");
        awaitScene();
        assertThat(page.evaluate("() => state.world._surface.runs.some(run=>run.look.power)")).isEqualTo(true);
        assertWorldAnimation();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void minimapIsBoundedClickableAndDoesNotSaveItsCamera() throws Exception {
        openProject(deepBranchProject(128));
        final String source = Files.readString(directory.resolve("project.json"));
        page.locator("#world-map").waitFor();
        assertThat(page.locator("#world-map .map-cells i").count()).isBetween(1, 640);
        final Object before = page.evaluate("() => state.world.query");
        final var map = page.locator("#world-map").boundingBox();
        page.locator("#world-map").click(new com.microsoft.playwright.Locator.ClickOptions().setPosition(map.width * .75, map.height * .25));
        page.waitForFunction("before => state.world.query !== before", before);
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(source);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void minimapSeparatesMachinesFromTransport() {
        openProject(choiceProject());
        page.locator("#world-map").waitFor();
        assertThat(page.locator(".map-cells i[data-kind=step]").count()).isPositive();
        assertThat(page.locator(".map-cells i[data-axis]:not([data-axis='0'])").count()).isPositive();
        assertThat(page.locator(".map-cells i[data-kind=step]").first().evaluate("element=>getComputedStyle(element,'::before').backgroundColor"))
                .isNotEqualTo("rgba(0, 0, 0, 0)");
        assertThat(page.locator(".map-cells i").count()).isLessThanOrEqualTo(640);
    }

    @Test
    void foundryRadarHasReadableLandscapeContentAndASeparateCaption() throws Exception {
        openProject(choiceProject());
        page.locator("#world-map").waitFor();
        final var bounds = page.locator("#world-map").boundingBox();
        final var content=page.locator("#world-map .map-content").boundingBox();
        final var caption=page.locator("#world-map .map-title").boundingBox();
        assertThat(content.width).isCloseTo(content.height*1.6,within(2.0));
        assertThat(caption.y).isGreaterThanOrEqualTo(content.y+content.height);
        assertThat(caption.y+caption.height).isLessThanOrEqualTo(bounds.y+bounds.height);
        assertThat(page.locator("#world-map").evaluate("element=>getComputedStyle(element).borderRadius")).isEqualTo("8px");
        assertThat(page.locator(".map-cells i[data-kind=step]").evaluateAll("""
                marks=>marks.every(mark=>parseFloat(getComputedStyle(mark,'::before').width)>=4)
                """)).isEqualTo(true);
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target", "screenshots"))
                .resolve("foundry-radar.png")));
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void radarConnectionsStopAtTheirTerminalMarkers() {
        openProject(choiceProject());
        page.locator("#world-map").waitFor();
        assertThat(page.locator(".map-cells i[data-ports='2'], .map-cells i[data-ports='8']").count())
                .as("Terminal connections have one real direction, not full crossing strokes").isPositive();
        assertThat(page.locator(".map-cells i[data-ports='2']").evaluateAll("""
                marks=>marks.every(mark=>getComputedStyle(mark).getPropertyValue('--west').trim()==='0%')
                """)).isEqualTo(true);
    }

    @Test
    void minimapRetainsSelectionWhenTravellingAndClearsItOnDeselect() {
        openProject(choiceProject());
        selectWorldNode("matched");
        awaitScene();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator(".map-cells i[data-selected=true]")).hasCount(1);
        assertThat(page.locator(".map-cells i[data-selected=true]").evaluate("element=>getComputedStyle(element,'::after').content")).isEqualTo("none");
        final var selected = page.locator(".map-cells i[data-selected=true]").boundingBox();
        page.locator("#world-map").press("ArrowRight");
        awaitScene();
        assertThat(page.locator(".map-cells i[data-selected=true]").count()).isEqualTo(1);
        assertThat(page.locator(".map-cells i[data-selected=true]").boundingBox().x).isEqualTo(selected.x);
        clearWorldSelection();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator(".map-cells i[data-selected=true]")).hasCount(0);
    }

    @Test
    void minimapDistinguishesUnreachedFromDisabledMetrics() {
        openProject(unreachedBranchProject());
        page.locator("[data-world-id=command]").click();
        page.waitForFunction("() => state.world._surface.stations.some(item=>item.node.id==='otherwise' && item.look.never)");
        assertThat(page.locator(".map-cells i[data-state=never]").count()).isEqualTo(1);
        page.waitForFunction("() => document.querySelector('.map-cells i[data-state=uncovered]')");
    }

    @Test
    void factoryCursorLeavesHudControlsNative() {
        openProject(choiceProject());
        final var graph = page.locator("#graph").boundingBox();
        page.mouse().move(graph.x+12, graph.y+220);
        assertThat(page.locator("#world-cursor").isVisible()).isTrue();
        assertThat(page.locator("#graph").evaluate("element => getComputedStyle(element).cursor")).isEqualTo("none");
        assertThat(page.locator("#world-cursor").evaluate("element => getComputedStyle(element).filter").toString())
                .contains("drop-shadow");
        final var fit = page.locator("#zoom-fit").boundingBox();
        page.mouse().move(fit.x+fit.width/2,fit.y+fit.height/2);
        assertThat(page.locator("#world-cursor").isVisible()).isFalse();
        assertThat(page.locator("#zoom-fit").evaluate("element => getComputedStyle(element).cursor")).isNotEqualTo("none");
    }

    @ParameterizedTest
    @ValueSource(strings = {"matched", "otherwise"})
    void selectingAResultDoesNotInventAnError(final String id) {
        openProject(choiceProject());
        page.locator("[data-world-id="+id+"]").click();
        openInspectorTab("overview");
        awaitScene();
        assertThat(page.evaluate("() => state.world._surface.stations.some(item=>item.look.error)")).isEqualTo(false);
        assertThat(page.locator(".map-cells i[data-state=error]").count()).isZero();
        assertThat(page.locator("#inspector .issues").count()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void minimapShowsAnInvalidDraftWithoutPaintingSelectionAsAnError() {
        openProject(choiceProject());
        selectWorldNode("matched");
        page.locator("#value-0-literal-value").fill("[");
        page.locator("#value-0-literal-value").press("Tab");
        page.waitForFunction("() => document.querySelector('.map-cells i[data-state=error]')");
        assertThat(page.locator(".map-cells i[data-state=error]").count()).isEqualTo(1);
        page.locator("#close-inspector").click();
        page.evaluate("() => void state.world.fit()");
        awaitScene();
        page.locator("[data-world-id=otherwise]").click();
        awaitScene();
        assertThat(page.evaluate("() => state.world._surface.stations.some(item=>item.look.selected && item.look.error)")).isEqualTo(false);
        assertThat(page.locator(".map-cells i[data-state=error]").count()).isEqualTo(1);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void minimapKeepsStateSeparateFromTheVisibleTimingLegend() {
        openProject(fourStepProject());
        page.locator("#world-map").waitFor();
        page.locator("#example-time-scale").waitFor();
        assertThat(page.locator(".map-cells i").evaluateAll("marks=>marks.every(mark=>!mark.style.getPropertyValue('--heat'))"))
                .isEqualTo(true);
        assertThat(page.locator(".map-cells i[title*='sampled time']").count()).isZero();
        assertThat(page.locator("#example-time-scale").textContent()).contains("Visible avg");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void disposingAnOldWorldAgainDoesNotClearTheReplacementRadar() {
        openProject(fourStepProject());
        page.evaluate("""
                async () => {
                  window.previousWorld = state.world;
                  previousWorld.dispose();
                  state.world = new RailixWorld(document.querySelector('#graph'));
                  await state.world.refresh();
                }
                """);
        page.locator("#world-map").waitFor();
        page.evaluate("() => previousWorld.dispose()");
        assertThat(page.locator("#world-map").isVisible()).isTrue();
        assertThat(page.locator(".map-cells i").count()).isBetween(1,640);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {320, 823, 1280})
    void minimapAndAudioStayReachableBesideTheSelectionHud(final int width) {
        page.setViewportSize(width, page.viewportSize().height);
        page.locator("#zoom-fit").click();
        awaitScene();
        page.locator("#world-map").waitFor();
        page.locator(".app-node").click();
        final var map = page.locator("#world-map").boundingBox();
        final var dock = page.locator(".topbar").boundingBox();
        assertThat(map.x + map.width <= dock.x || dock.x + dock.width <= map.x
                || map.y + map.height <= dock.y || dock.y + dock.height <= map.y).isTrue();
        for (final var button : page.locator(".topbar > button:visible, .canvas-tools button").all()) {
            final var bounds = button.boundingBox();
            assertThat(bounds.x).isGreaterThanOrEqualTo(0);
            assertThat(bounds.x + bounds.width).isLessThanOrEqualTo(width);
        }
        page.locator("#open-settings").click();
        final var audio = page.locator("#audio-panel").boundingBox();
        assertThat(audio.x).isGreaterThanOrEqualTo(0);
        assertThat(audio.x + audio.width).isLessThanOrEqualTo(page.viewportSize().width);
        page.keyboard().press("Escape");
        assertThat(page.locator("#audio-panel").isVisible()).isFalse();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void minimapRecoversAfterAnUnavailableOverview() {
        final var failures = new java.util.concurrent.atomic.AtomicInteger();
        page.route("**/api/scene?**", route -> {
            final double scale = java.util.Arrays.stream(java.net.URI.create(route.request().url()).getQuery().split("&"))
                    .filter(field -> field.startsWith("scale=")).mapToDouble(field -> Double.parseDouble(field.substring(6))).findFirst().orElse(1);
            if (scale < 1e-9 && failures.getAndIncrement() == 0) route.abort();
            else route.resume();
        });
        page.reload();
        page.locator("#world-map").waitFor();
        assertThat(failures.get()).isGreaterThan(1);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void soundScoresCanBeEditedGroupedPlayedPausedAndDeleted() throws Exception {
        final Path music = Files.createDirectories(directory.resolve("railix-home/music/review"));
        final String score = """
                {"version":1,"name":"Review track","tempo":90,"tracks":[
                  {"waveform":"sine","volume":0.2,"attack":0.02,"release":0.1,
                   "notes":[{"note":60,"duration":16},{"note":null,"duration":16}]}]}
                """;
        Files.writeString(music.resolve("review.json"), score);
        assertThat(page.locator("#audio-effects").isChecked()).isTrue();
        assertThat(page.locator("audio").count()).isZero();
        page.locator("#open-settings").click();
        page.locator("#settings-music-tab").click();
        page.waitForFunction("() => !document.querySelector('#music-play').disabled");
        page.locator("#music-group").selectOption("group:review");
        page.locator("#music-play").click();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#audio-status")).containsText("Review track");
        page.waitForFunction("() => state.audio.musicContext?.state === 'running'");
        final var context = page.evaluateHandle("() => state.audio.musicContext");
        page.locator("#music-play").click();
        page.waitForFunction("context => context.state === 'suspended'", context);
        page.locator("#music-play").click();
        page.waitForFunction("context => context.state === 'running'", context);
        page.locator("#music-stop").click();
        page.waitForFunction("context => context.state === 'closed'", context);
        page.locator("#sound-files > summary").click();
        page.locator("#sound-file").selectOption("local:music/review/review.mml");
        assertThat(page.locator("#sound-score").inputValue()).contains("o4 c@16", "r@16");
        page.locator("#sound-score").fill(page.locator("#sound-score").inputValue().replace("Review track", "Edited track"));
        page.locator("#sound-save").click();
        page.waitForFunction("() => document.querySelector('#sound-score').value.includes('Edited track') && !state.audio.dirtyEditor");
        assertThat(Files.readString(music.resolve("review.json"))).contains("Review track");
        assertThat(Files.readString(music.resolve("review.mml"))).contains("Edited track");
        page.locator("#sound-delete").click();
        page.waitForFunction("() => !state.audio.mutation && state.audio.scores.some(entry => entry.id === 'music/review/review.mml' && entry.score.name === 'Review track')");
        assertThat(Files.exists(music.resolve("review.json"))).isTrue();
        assertThat(Files.exists(music.resolve("review.mml"))).isFalse();
        page.locator("#sound-defaults").click();
        page.waitForFunction("() => [...document.querySelector('#sound-file').options].some(option => option.value === 'local:music/quiet/lantern.mml')");
        assertThat(Files.readString(directory.resolve("railix-home/sounds/working.mml"))).contains("name:", "tempo:");
        page.locator("#settings-sound-tab").click();
        page.locator("#audio-effects").check();
        page.waitForFunction("() => state.audio.context?.state === 'running'");
        page.locator("#audio-effects").uncheck();
        page.waitForFunction("() => !state.audio.context");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {1280, 320})
    void appMetricsSeparateAppJvmAndSystem(final int width) {
        page.setViewportSize(width, 800);
        openProject(fourStepProject());
        selectWorldNode("app");
        page.locator("#runtime-details").click();
        page.waitForFunction("() => document.querySelector('.metric-systems th')");
        assertThat(page.locator(".metric-systems thead th").allTextContents()).containsExactly("Metric", "App", "JVM", "System");
        assertThat(page.locator(".metric-systems [data-metric-id=system_cpu_load_ppm]").count()).isEqualTo(1);
        final var cpu = page.locator(".metric-systems tr:has([data-metric-id=system_cpu_load_ppm])");
        assertThat(cpu.locator("th[scope=row]").textContent()).isEqualTo("CPU load");
        assertThat(cpu.locator("td").nth(0).textContent()).isEmpty();
        assertThat(cpu.locator("td").nth(1).getAttribute("data-metric-id")).isEqualTo("process_cpu_load_ppm");
        assertThat(cpu.locator("td").nth(2).getAttribute("data-metric-id")).isEqualTo("system_cpu_load_ppm");
        final var heap = page.locator(".metric-systems tr:has([data-metric-id=heap_max_bytes])");
        assertThat(heap.locator("td").nth(0).textContent()).isEmpty();
        assertThat(heap.locator("td").nth(2).textContent()).isEmpty();
        final var table = page.locator(".metric-systems").boundingBox();
        assertThat(table.x).isGreaterThanOrEqualTo(0);
        assertThat(table.x + table.width).isLessThanOrEqualTo(width);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void appMetricComparisonUsesCatalogIdentifiersAndKeepsDifferentUnitsSeparate() {
        page.route("**/api/metrics{,/catalog}", route -> {
            final var response = route.fetch();
            final var body = new HashMap<>(CreatorServerE2eSupport.object(response.text()).values());
            if (route.request().url().endsWith("/catalog")) {
                final var definitions = new HashMap<>(((RailixValue.ObjectValue) body.get("metrics")).values());
                definitions.putAll(CreatorServerE2eSupport.object("""
                        {"app_widgets":{"label":"App widgets","unit":"count"},
                         "application_widgets":{"label":"Application widgets","unit":"count"},
                         "process_widgets":{"label":"Process widgets","unit":"count"},
                         "system_widgets":{"label":"System widgets","unit":"count"},
                         "app_level":{"label":"App level","unit":"count"},
                         "system_level":{"label":"System level","unit":"bytes"}}
                        """).values());
                body.put("metrics", RailixValue.object(definitions));
            } else {
                final var application = new HashMap<>(((RailixValue.ObjectValue) body.get("application")).values());
                final var values = new HashMap<>(((RailixValue.ObjectValue) application.get("metrics")).values());
                values.put("app_widgets", RailixValue.number(7));
                values.put("application_widgets", RailixValue.number(17));
                values.put("app_level", RailixValue.number(9));
                application.put("metrics", RailixValue.object(values));
                body.put("application", RailixValue.object(application));
                final var process = new HashMap<>(((RailixValue.ObjectValue) body.get("process")).values());
                process.put("process_widgets", RailixValue.number(11));
                process.put("system_widgets", RailixValue.number(13));
                process.put("system_level", RailixValue.number(1024));
                body.put("process", RailixValue.object(process));
            }
            route.fulfill(new Route.FulfillOptions().setResponse(response).setBody(RailixJson.write(RailixValue.object(body))));
        });
        page.reload();
        waitForText("#build-state", "Built");
        selectWorldNode("app");
        page.locator("#runtime-details").click();
        page.locator("[data-metric-id=app_widgets]").waitFor();
        final var widgets = page.locator(".metric-systems tr:has([data-metric-id=app_widgets])");
        assertThat(widgets.locator("td").allTextContents()).containsExactly("7", "11", "13");
        assertThat(page.locator(".metric-systems tr:has([data-metric-id=application_widgets]) td")
                .allTextContents()).containsExactly("17", "", "");
        final var level = page.locator(".metric-systems tr:has([data-metric-id=app_level])");
        assertThat(level.locator("td").allTextContents()).containsExactly("9", "", "");
        final var systemLevel = page.locator(".metric-systems tr:has([data-metric-id=system_level])");
        assertThat(systemLevel.locator("td").nth(0).textContent()).isEmpty();
        assertThat(systemLevel.locator("td").nth(1).textContent()).isEmpty();
        assertThat(systemLevel.locator("td").nth(2).textContent()).isNotEmpty();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void selectingMachinesPlaysTheirCustomWorkingAndUncoveredScores() throws Exception {
        final Path sounds = directory.resolve("railix-home/sounds");
        final String score = """
                {"version":1,"name":"State cue","tempo":60,"tracks":[
                  {"waveform":"sine","volume":0.2,"attack":0.01,"release":0.1,
                   "notes":[{"note":%d,"duration":16}]}]}
                """;
        Files.writeString(sounds.resolve("working.json"), score.formatted(60));
        Files.writeString(sounds.resolve("uncovered.json"), score.formatted(36));
        openProject(choiceProject().replace("\"value\":\"deny\"", "\"value\":\"allow\""));
        selectWorldNode("command");
        chooseExampleIndex(0);
        page.locator("#close-inspector").click();
        page.locator("#open-settings").click();
        page.locator("#settings-sound-tab").click();
        page.waitForFunction("() => !document.querySelector('#music-play').disabled");
        page.locator("#effect-choices > summary").click();
        page.locator("select[data-event-id=working]").selectOption("local:sounds/working.mml");
        page.locator("select[data-event-id=uncovered]").selectOption("local:sounds/uncovered.mml");
        page.locator("#audio-effects").check();
        page.waitForFunction("() => state.audio.context?.state === 'running'");
        page.keyboard().press("Escape");
        page.evaluate("() => void state.world.focus('matched')");
        awaitScene();
        page.waitForFunction("() => state.world._surface.stations.some(item=>item.node.id==='matched' && item.look.coverage==='selected')");
        page.locator("[data-select-node=matched]").click();
        page.waitForFunction("() => Math.abs(state.audio.effectSession?.voices[0].voice.frequency.value - 261.6256) < .01");
        page.locator("#close-inspector").click();
        page.evaluate("() => void state.world.focus('otherwise')");
        awaitScene();
        page.waitForFunction("""
                () => state.world._surface.stations.some(item=>item.node.id==='otherwise' && item.look.coverage==='uncovered')
                """);
        page.locator("[data-select-node=otherwise]").click();
        page.waitForFunction("() => Math.abs(state.audio.effectSession?.voices[0].voice.frequency.value - 65.4064) < .01");
        page.locator("#open-settings").click();
        page.locator("#settings-sound-tab").click();
        final var context = page.evaluateHandle("() => state.audio.context");
        page.locator("#audio-effects").uncheck();
        page.waitForFunction("context => context.state === 'closed'", context);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void settingsLoadNestedThemesWithoutRebuildingTheApplication() throws Exception {
        final Path themes = Files.createDirectories(directory.resolve("railix-home/themes/orbit"));
        Files.writeString(themes.resolve("ice.css"), ":root { --machine-light: #abcdef; }");
        final String project = Files.readString(directory.resolve("project.json"));
        final String pid = page.locator("#status-pid").textContent();
        page.locator("#open-settings").click();
        page.locator("#theme-select option[value='orbit/ice.css']").waitFor(new com.microsoft.playwright.Locator.WaitForOptions()
                .setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED));
        selectTheme("orbit/ice.css");
        page.waitForFunction("() => getComputedStyle(document.documentElement).getPropertyValue('--machine-light').trim() === '#abcdef'");
        page.reload();
        waitForText("#build-state", "Built");
        page.waitForFunction("() => getComputedStyle(document.documentElement).getPropertyValue('--machine-light').trim() === '#abcdef'");
        assertThat(page.locator("#status-pid").textContent()).isEqualTo(pid);
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(project);
        assertThat(Files.readString(directory.resolve("railix.creator.json"))).doesNotContain("\"theme\"");
        assertThat(Files.readString(directory.resolve("railix-home/creator.settings.json"))).contains("\"theme\":\"orbit/ice.css\"");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void selectingNoExampleStopsReplayWithoutChangingTheApplication() throws Exception {
        openProject(choiceProject());
        selectWorldNode("command");
        assertThat(page.locator("#visible-example option[value='']").count()).isEqualTo(1);
        page.waitForFunction("() => document.querySelector('#graph').dataset.replaying === 'true'");
        final Object executions = page.evaluate("async () => (await (await fetch('/api/metrics/nodes/command')).json()).steps[0].metrics.executions");
        assertThat(executions).isNotNull();
        final String source = Files.readString(directory.resolve("project.json"));
        chooseExampleIndex(-1);
        page.waitForFunction("() => document.querySelector('#graph').dataset.replaying === 'false'");
        assertThat(page.evaluate("() => state.world._surface.stations.some(item=>item.look.coverage==='selected')")).isEqualTo(false);
        chooseExampleIndex(0);
        page.waitForFunction("() => document.querySelector('#graph').dataset.replaying === 'true'");
        assertThat(page.evaluate("async () => (await (await fetch('/api/metrics/nodes/command')).json()).steps[0].metrics.executions"))
                .isEqualTo(executions);
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(source);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void browserMotionPreferenceDoesNotHideOperationalReplay() {
        openProject(choiceProject());
        selectWorldNode("command");
        page.waitForFunction("() => document.querySelector('#graph').dataset.replaying === 'true'");
        page.emulateMedia(new Page.EmulateMediaOptions().setReducedMotion(com.microsoft.playwright.options.ReducedMotion.REDUCE));
        assertThat(page.locator("#graph").getAttribute("data-replaying")).isEqualTo("true");
        assertWorldAnimation();
        assertThat(pageErrors).isEmpty();
    }


    @Test
    void neverExecutedStepsLookUnpoweredButMetricsDisabledStepsDoNot() {
        openProject(unreachedBranchProject());
        page.waitForFunction("() => state.world._surface.stations.some(item=>item.node.id==='otherwise' && item.look.never)");
        assertThat(page.evaluate("() => state.world._surface.stations.some(item=>item.node.id==='matched' && item.look.never)")).isEqualTo(false);
        assertThat(page.evaluate("() => state.world._surface.stations.filter(item=>item.look.never).every(item=>!state.world._surface.active(item.look))")).isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void applicationSuppliesPowerRatherThanTransport() {
        openProject(choiceProject());
        page.evaluate("() => void state.world.fit()");
        awaitScene();
        assertThat(page.evaluate("() => state.world._surface.runs.some(run=>run.look.power)")).isEqualTo(true);
        assertThat(page.evaluate("() => state.world._surface.routes.every(route=>!route.look.power)")).isEqualTo(true);
        assertThat(page.evaluate("() => state.world._surface.runs.some(run=>!run.look.power)")).isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void missingThemeIsVisibleAndResetRestoresTheBuiltInTheme() throws Exception {
        final Path theme = directory.resolve("railix-home/themes/temporary.css");
        Files.writeString(theme, ":root { --machine-light: #abcdef; }");
        page.locator("#open-settings").click();
        page.waitForFunction("() => document.querySelector('#theme-select option[value=\"temporary.css\"]')");
        selectTheme("temporary.css");
        Files.delete(theme);
        page.reload();
        page.locator("#open-settings").click();
        waitForText("#theme-status", "Theme temporary.css is unavailable. Railix styling is shown.");
        page.waitForFunction("() => state.world._surface?.stations.some(item=>item.node.id==='app' && item.look.warning)");
        selectTheme("");
        page.waitForFunction("() => document.querySelector('#theme-status').textContent === ''");
        assertThat(Files.readString(directory.resolve("railix.creator.json"))).doesNotContain("\"theme\"");
        assertThat(pageErrors).isEmpty();
    }

    private void selectTheme(final String id) {
        final var response = page.waitForResponse(candidate -> candidate.url().endsWith("/api/settings")
                        && candidate.request().method().equals("POST"),
                () -> page.locator("#theme-select").selectOption(id));
        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    void settingsKeepEmbeddedDownloadsAndPersistMotionWithoutChangingTheProject() throws Exception {
        final String project = Files.readString(directory.resolve("project.json"));
        final String metadata = Files.readString(directory.resolve("railix.creator.json"));
        final String pid = page.locator("#status-pid").textContent();
        page.locator("#open-settings").click();
        page.waitForFunction("() => state.settingsRevision !== null && state.audio.scores.length > 0 && state.themes.length > 0");
        assertThat(page.locator("#theme-select option[value='']").count()).isEqualTo(1);
        final var theme = page.waitForResponse(response -> response.url().endsWith("/api/themes") && response.request().method().equals("POST"),
                () -> page.locator("#theme-download").click());
        assertThat(theme.status()).isEqualTo(200);
        assertThat(Files.readString(directory.resolve("railix-home/themes/foundry/theme.css"))).contains("--world-buildings", "--world-near");
        page.waitForResponse(response -> response.url().endsWith("/api/settings") && response.request().method().equals("POST"),
                () -> page.locator("#reduced-motion").check());
        page.locator("#settings-sound-tab").click();
        page.locator("#sound-files > summary").click();
        page.locator("#sound-file").selectOption("builtin:sounds/working.mml");
        assertThat(page.locator("#sound-delete").isDisabled()).isTrue();
        final var sound = page.waitForResponse(response -> response.url().endsWith("/api/sounds") && response.request().method().equals("POST"),
                () -> page.locator("#sound-download").click());
        assertThat(sound.status()).isEqualTo(200);
        assertThat(Files.readString(directory.resolve("railix-home/sounds/working.mml"))).contains("name:", "tempo:");
        page.reload();
        page.waitForFunction("() => state.settings.reduced_motion === true");
        assertThat(page.locator("body").getAttribute("data-reduced-motion")).isEqualTo("true");
        assertThat(page.locator("#graph").evaluate("element => element.getAnimations({subtree:true}).filter(animation => animation.playState === 'running').length"))
                .isEqualTo(0);
        final Object musicBefore = page.evaluate("() => state.settings.music_enabled");
        page.locator("#toggle-music").click();
        page.waitForFunction("before => state.settings.music_enabled !== before", musicBefore);
        assertThat(page.locator("#toggle-music").getAttribute("aria-pressed")).isEqualTo(String.valueOf(!Boolean.TRUE.equals(musicBefore)));
        final Object effectsBefore = page.evaluate("() => state.settings.effects");
        page.locator("#toggle-effects").click();
        page.waitForFunction("before => state.settings.effects !== before", effectsBefore);
        assertThat(page.locator("#toggle-effects").getAttribute("aria-pressed")).isEqualTo(String.valueOf(!Boolean.TRUE.equals(effectsBefore)));
        assertThat(page.locator("#audio-panel").isVisible()).isFalse();
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(project);
        assertThat(Files.readString(directory.resolve("railix.creator.json"))).isEqualTo(metadata);
        assertThat(page.locator("#status-pid").textContent()).isEqualTo(pid);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void invalidStepInputAlsoMarksItsCollapsedGroup() {
        openProject(fourStepProject());
        final String group = createGroup("one", "two");
        selectWorldNode("one");
        page.locator("#value-0-literal-value").fill("[");
        page.locator("#close-inspector").click();
        while (page.locator("#leave-group").isVisible()) {
            page.locator("#leave-group").click();
            awaitScene();
        }
        page.evaluate("() => void state.world.fit()");
        page.waitForFunction("group => document.querySelector(`[data-region-group='${group}']`)?.dataset.error === 'true'", group);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void motionHasNoExtraPauseControl() {
        assertThat(page.locator("#world-motion").count()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void inspectorHeaderRemainsVisibleWhileItsFieldsScroll() {
        openProject(fourStepProject());
        selectWorldNode("one");
        final var close = page.locator("#close-inspector").boundingBox();
        final var tabs = page.locator(".inspector-tabs").boundingBox();
        page.locator("#inspector-content").evaluate("element => element.scrollTop = element.scrollHeight");
        assertThat(page.locator("#inspector-content").evaluate("element => element.scrollTop")).isNotEqualTo(0);
        assertThat(page.locator("#close-inspector").boundingBox().y).isEqualTo(close.y);
        assertThat(page.locator(".inspector-tabs").boundingBox().y).isEqualTo(tabs.y);
        page.locator("#close-inspector").click();
        assertThat(page.locator("#inspector").isVisible()).isFalse();
    }

    @Test
    @Tag("responsive")
    void selectedStationHudNeverCoversCameraControls() {
        page.locator(".app-node").click();
        final var dock = page.locator(".topbar").boundingBox();
        final var camera = page.locator(".canvas-tools").boundingBox();
        assertThat(dock.x + dock.width <= camera.x || camera.x + camera.width <= dock.x
                || dock.y + dock.height <= camera.y || camera.y + camera.height <= dock.y).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void cssFactoryShapesRemainEditableInARealBranchingApplication() throws Exception {
        openProject(choiceProject());
        final String functional = Files.readString(directory.resolve("project.json"));
        final String[][] stations = {{"command", "ellipse"}, {"choice", "diamond"},
                {"matched", "rectangle"}, {"otherwise", "triangle"}};
        for (final String[] station : stations) {
            selectWorldNode(station[0]);
            page.locator("[data-inspector-mode=appearance]").click();
            if (!page.locator("#presentation-shape").inputValue().equals(station[1])) {
                clickAndWaitForCreatorSave(() -> page.locator("#presentation-shape").selectOption(station[1]));
            }
        }
        page.locator("#close-inspector").click();
        page.evaluate("() => void state.world.fit()");
        awaitScene();
        final Path screenshots = Files.createDirectories(Path.of("target", "screenshots"));
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("css-factory-hud.png")));
        selectWorldNode("choice");
        page.locator("#close-inspector").click();
        awaitScene();
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("css-factory-detail.png")));
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(functional);
        assertThat(Integer.parseInt(page.locator(".world-raster").getAttribute("data-stations"))).isPositive();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void buildDetailsDoNotReplaceTheSelectedStationOrOpenItsInspector() {
        openProject(fourStepProject());
        selectWorldNode("one");
        page.locator("#close-inspector").click();
        page.locator(".build-indicator").click();
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isEqualTo("one");
        assertThat(page.locator("#application-status").isVisible()).isTrue();
        assertThat(page.locator("#application-status #build-path").textContent()).isNotBlank();
        page.keyboard().press("Escape");
        assertThat(page.locator("#application-status").isVisible()).isFalse();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void buildDetailsImportsACompleteProjectJson() throws Exception {
        final String imported = CreatorProjects.previewBoundary()
                .replace("\"id\":\"preview-boundary\"", "\"id\":\"uploaded-project\"");
        final Path upload = directory.resolve("uploaded-railix.project.json");
        Files.writeString(upload, imported);
        page.locator(".build-indicator").click();

        page.locator("#project-import-file").setInputFiles(upload);
        page.waitForFunction("() => document.querySelector('#project-import-source').value.includes('uploaded-project')");
        final var response = page.waitForResponse(candidate -> candidate.url().endsWith("/api/project")
                        && candidate.request().method().equals("POST"),
                () -> page.locator("#project-import-submit").click());

        assertThat(response.status()).isEqualTo(200);
        page.waitForFunction("""
                () => state.project.id === 'uploaded-project'
                  && state.workspace.flow_count === 1
                  && state.workspace.step_count === 4
                  && state.build === 'Built'
                  && !state.pendingProject
                  && !state.writeActive
                """);
        waitForText("#project-import-status", "Imported and built.");
        assertThat(page.locator("#flow-count").textContent()).isEqualTo("1 flow");
        assertThat(page.locator("#step-count").textContent()).isEqualTo("4 steps");
        assertThat(page.title()).isEqualTo("uploaded-project - Railix Creator");
        assertThat(Files.readString(directory.resolve("project.json"))).contains("\"id\":\"uploaded-project\"");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void closingBuildDetailsKeepsAnAlreadyOpenInspectorAndItsSelection() {
        openProject(fourStepProject());
        selectWorldNode("one");
        page.locator(".build-indicator").click();
        page.keyboard().press("Escape");
        assertThat(page.locator("#application-status").isVisible()).isFalse();
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isEqualTo("one");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void configureIsTheOnlyGeneralInspectorActionOnTheStationDock() {
        openProject(fourStepProject());
        page.locator("[data-world-id=one]").click();
        assertThat(page.locator(".brand").count()).isZero();
        assertThat(page.locator("#selection-dock [data-open-panel]").count()).isZero();
        openInspectorTab("overview");
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        assertThat(page.locator("[data-inspector-mode=appearance]").isVisible()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void factoryHudControlsDoNotOverlapOrOpenTheWrongAction() {
        final var build = page.locator(".build-indicator").boundingBox();
        final var zoom = page.locator(".canvas-tools").boundingBox();
        assertThat(build.x + build.width <= zoom.x || zoom.x + zoom.width <= build.x
                || build.y + build.height <= zoom.y || zoom.y + zoom.height <= build.y).isTrue();
        page.evaluate("() => void state.world.fit()");
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        page.locator(".build-indicator").click();
        page.getByText("Build details", new Page.GetByTextOptions().setExact(true)).click();
        page.locator("#application-status #workspace-details").waitFor();
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void centralExampleChooserDoesNotPanTheWorldOrCoverItsTrigger() {
        openProject(choiceProject());
        selectTrigger();
        page.locator("#close-inspector").click();
        clickOverview("#dock-focus");
        awaitScene();
        page.locator("#visible-example").waitFor();
        final String camera = (String) page.evaluate("() => state.world.query");
        final var before = stationBounds("command");
        page.locator("#visible-example").focus();
        page.locator("#visible-example").press("ArrowLeft");
        page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))");
        assertThat(page.evaluate("() => state.world.query")).isEqualTo(camera);
        chooseExampleIndex(1);
        awaitScene();
        assertThat(page.evaluate("() => state.world.query")).isEqualTo(camera);
        final var panel = page.locator("#example-session").boundingBox();
        final var trigger = stationBounds("command");
        assertThat(trigger.x).isCloseTo(before.x, org.assertj.core.data.Offset.offset(.5));
        assertThat(panel.y + panel.height <= trigger.y || panel.y >= trigger.y + trigger.height
                || panel.x + panel.width <= trigger.x || panel.x >= trigger.x + trigger.width).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void startupFailureIsVisibleWithoutOpeningTheInspector() {
        page.navigate(creator.baseUri().resolve("/?unauthorized") + "#token=invalid");
        waitForText("#build-state", "Unavailable");
        assertThat(page.locator("#creator-startup").isVisible()).isTrue();
        assertThat(page.locator("#creator-startup").innerText()).contains("Open the current Creator link", "shown by the running Creator");
        assertThat(page.locator("#creator-retry").isVisible()).isFalse();
        assertThat(page.evaluate("async () => (await fetch('/api/editor')).status")).isEqualTo(401);
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isBlank();
    }

    @Test
    void startupNetworkFailureOffersAnExplicitRetry() {
        page.route("**/api/catalog", route -> route.abort());
        page.reload();
        page.locator("#creator-retry").waitFor();
        assertThat(page.locator("#creator-startup").innerText()).contains("Creator is not reachable");
        page.unroute("**/api/catalog");
        page.locator("#creator-retry").click();
        awaitScene();
        assertThat(page.locator("#creator-startup").isVisible()).isFalse();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void overviewShowsRealInputAndOutputWithoutEditingTheStep() {
        addGraphPrimitive("\"RAILIX\"", "lowercase", "text.lowercase");
        page.locator("[data-input-name='target'] [data-path-value='after']").waitFor();
        openInspectorTab("overview");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#selection-evidence [data-context-change='payload.value']"))
                .containsText("RAILIX");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#selection-evidence [data-context-change='payload.value']"))
                .containsText("railix");
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void switchingTheDockExampleReadsItsRecordedRouteWithoutBuildingOrExecuting() throws Exception {
        openProject(choiceProject());
        selectTrigger();
        page.locator(".run-result").waitFor();
        page.locator("#close-inspector").click();
        final String before = Files.readString(directory.resolve("project.json"));
        final String pid = applicationPid();
        final List<String> writes = new ArrayList<>();
        page.onRequest(request -> {
            if (!List.of("GET", "HEAD").contains(request.method())) writes.add(request.method() + " " + request.url());
        });
        page.locator("#visible-example").focus();
        chooseExampleIndex(1);
        page.waitForFunction("() => document.querySelector('#visible-example')?.value === 'command:1' && state.application.example?.name === 'otherwise'");
        assertThat(page.evaluate("() => document.activeElement?.id")).isEqualTo("visible-example");
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        assertThat(applicationPid()).isEqualTo(pid);
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(before);
        assertThat(writes).isEmpty();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void observationPollingPreservesTheExampleChooserAndRecordedValues() {
        openProject(choiceProject());
        selectTrigger();
        page.locator(".run-result").waitFor();
        openInspectorTab("overview");
        page.locator("#visible-example").focus();
        final var chooser = page.locator("#visible-example").elementHandle();
        final var value = page.locator("[data-dock-value='output'] output").elementHandle();
        page.waitForResponse(response -> response.url().contains("/api/scene/observations"), () -> { });
        assertThat(chooser.evaluate("element => element === document.activeElement")).isEqualTo(true);
        assertThat(value.evaluate("element => element === document.querySelector('[data-dock-value=output] output')")).isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void closingConstructionPreservesTheInspectorSelectionWithoutEditingTheProject() throws Exception {
        openProject(fourStepProject());
        selectWorldNode("one");
        page.locator("#close-inspector").click();
        final String before = Files.readString(directory.resolve("project.json"));
        clickOverview("#add-next-step");
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isEqualTo("one");
        page.locator("[data-close-picker]").click();
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isNotBlank();
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(before);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void groupEntryOffersItsInspectorAndAppearanceActions() throws Exception {
        openProject(fourStepProject());
        final String group = createGroup("one", "two");
        page.locator("#close-inspector").click();
        page.locator("#scope-overview").click();
        awaitScene();
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(
                Path.of("target", "friendly-group")).resolve("overview.png")));
        assertThat(page.locator("[data-region-group='" + group + "']").count())
                .as("Group remains selectable in overview: %s", page.locator("#world-labels").innerText()).isPositive();
        page.locator("[data-region-group='" + group + "']").first().click();
        page.locator("#world-location [data-inspect-region]").click();
        page.locator(".inspector-chrome [data-leave-group]").waitFor();
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        openInspectorTab("overview");
        page.locator("[data-inspector-mode=appearance]").waitFor();
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void insertingInsideEnteredGroupPreservesZoomAndUpdatesNavigation() {
        openProject(fourStepProject());
        final String group = createGroup("one", "two");
        page.locator("[data-region-group='" + group + "']").first().click();
        page.waitForFunction("() => document.querySelector('#group-navigation:not([hidden])')?.textContent.includes('2 Steps')"
                + " && document.querySelector('#graph').dataset.cameraMoving === 'false'");
        page.locator("[data-select-node='one']").click();
        page.waitForFunction("() => document.querySelector('#inspector')?.dataset.selection === 'one'");
        openInspectorTab("overview");
        final String zoom = page.locator("#zoom-level").textContent();
        page.locator("#add-next-step").click();
        page.locator("[data-add-step='railix.field-manipulation']").click();
        page.waitForFunction("() => state.build === 'Built' && "
                + "state.world.scene.nodes.some(node => node.kind === 'region' && node.count === 3)");

        assertThat(page.locator("#group-navigation").textContent()).contains("3 Steps");
        assertThat(page.locator("#zoom-level").textContent()).isEqualTo(zoom);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void placingLinkedStepKeepsTheCurrentZoom() {
        openProject(CreatorServerE2eSupport.threeStepProject());
        page.locator("[data-select-node='three']").click();
        page.locator("[data-inspector-mode=overview]").click();
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving === 'false'");
        final String zoom = page.locator("#zoom-level").textContent();

        page.locator("#add-next-step").click();
        page.locator("[data-reuse-item=one][data-reuse-type=step]").click();
        page.locator("[data-apply-reuse=reuse]").click();
        page.waitForFunction("() => !state.actionPending && state.editor.reuse?.count === 2");

        assertThat(page.locator("#zoom-level").textContent()).isEqualTo(zoom);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void selectingAGroupDismissesConstructionAndItsPlacementPreview() {
        openProject(fourStepProject());
        final String group = createGroup("one", "two");
        selectTrigger();
        page.locator("#close-inspector").click();
        clickOverview("#add-next-step");
        page.locator("[data-add-step]").first().focus();
        page.locator(".world-placement").waitFor();
        page.locator("[data-region-group='" + group + "']").first().click();
        assertThat(page.locator(".construction-palette").count()).isZero();
        page.waitForFunction("() => !document.querySelector('.world-placement')");
        assertThat(page.locator("#leave-group").isVisible()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void selectingAGroupSupersedesADelayedApplicationSelection() {
        openProject(fourStepProject());
        final String group = createGroup("one", "two");
        page.locator("#close-inspector").click();
        page.evaluate("""
                () => {
                  const fetch = window.fetch;
                  let held = false;
                  window.fetch = async (...args) => {
                    const response = await fetch.apply(window, args);
                    if (!held && String(args[0]).startsWith('/api/editor?node=app')) {
                      held = true;
                      await new Promise(resolve => window.__releaseAppEditor = resolve);
                    }
                    return response;
                  };
                  window.__restoreAppEditor = () => { window.__releaseAppEditor?.(); window.fetch = fetch; };
                }
                """);
        try {
            page.locator("[data-world-id=app]").click();
            page.waitForFunction("() => Boolean(window.__releaseAppEditor)");
            page.locator("[data-region-group='" + group + "']").first().click();
            page.locator("#world-location [data-inspect-region]").click();
            page.evaluate("() => window.__releaseAppEditor()");
            page.waitForFunction("() => !state.editorController");
            assertThat(page.locator("#inspector").isVisible()).isTrue();
            assertThat(page.locator(".inspector-chrome [data-leave-group]").isVisible()).isTrue();
            assertThat(pageErrors).isEmpty();
        } finally {
            page.evaluate("() => window.__restoreAppEditor()");
        }
    }

    @Test
    void deletingTheSelectedGroupRestoresItsStepsConstructionTools() {
        openProject(fourStepProject());
        final String group = createGroup("one", "two");
        page.locator("#close-inspector").click();
        page.locator("[data-region-group='" + group + "']").first().click();
        openInspectorTab("overview");
        clickAndWaitForCreatorSave(() -> page.locator("[data-delete-region]").click());
        assertThat(page.locator("#enter-region").isVisible()).isFalse();
        selectWorldNode("one");
        openInspectorTab("overview");
        assertThat(page.locator("#selection-overview #add-next-step").isVisible()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void workspaceStartsWithConstructionToolsInsteadOfAnOpenInspector() {
        page.reload();
        waitForText("#build-state", "Built");
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isNotBlank();
        openInspectorTab("overview");
        assertThat(page.locator("#selection-overview #add-trigger").isVisible()).isTrue();
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        page.keyboard().press("Escape");
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void selectingAStationKeepsTheCanvasAndContextualActionsVisible() {
        openProject(fourStepProject());
        page.reload();
        waitForText("#build-state", "Built");
        page.evaluate("() => void state.world.focus('one')");
        awaitScene();
        page.locator("[data-select-node='one']").click();
        page.waitForFunction("() => document.querySelector('#inspector')?.dataset.selection === 'one'");
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isNotBlank();
        openInspectorTab("overview");
        assertThat(page.locator("#selection-overview #add-next-step").isVisible()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void constructingAFlowFromOverviewBuildsTheRealApplication() throws Exception {
        page.reload();
        waitForText("#build-state", "Built");
        clickOverview("#add-trigger");
        page.locator("[data-add-step='railix.trigger.cli']").click();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(
                page.locator("#inspector-identity small")).hasText("Trigger");
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("field manipulation");
        page.locator("[data-add-step='railix.field-manipulation']").click();
        waitForText("#build-state", "Built");
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        assertThat(Files.readString(directory.resolve("project.json"))).contains("railix.field-manipulation");
        assertThat(applicationPid()).isNotBlank();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void stationNamesStayWithinTheirReadableModuleLabels() {
        openProject(fourStepProject());
        selectWorldNode("one");
        page.locator("#close-inspector").click();
        page.evaluate("() => void state.world.focus('one', 1)");
        awaitScene();
        final var label = page.locator("[data-node-id='one']");
        label.locator("strong").waitFor();
        final var name = label.locator("strong").boundingBox();
        final var surface = label.boundingBox();
        assertThat(page.evaluate("() => state.world._surface.stations.some(item=>item.node.id==='one')")).isEqualTo(true);
        assertThat(name.x).isGreaterThanOrEqualTo(surface.x);
        assertThat(name.x + name.width).isLessThanOrEqualTo(surface.x + surface.width);
        assertThat(label.getAttribute("aria-label")).contains("Field Manipulation");
        assertThat(label.locator(".world-detail").textContent()).doesNotContain("executions", "sampled", "context");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void appearanceControlsReflectTheDefaultMachineShape() {
        openProject(fourStepProject());
        selectWorldNode("one");
        openInspectorTab("appearance");
        assertThat(page.locator("#presentation-aspect").inputValue()).isEqualTo(page.evaluate("() => String(RailixWorld.appearance.aspect)"));
        assertThat(page.locator("#presentation-roundness").inputValue()).isEqualTo(page.evaluate("() => String(RailixWorld.appearance.roundness)"));
        assertThat(page.locator("#presentation-shape").inputValue()).isEqualTo("rectangle");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void choosingAStepPreviewsItsConnectionWithoutEditingTheProject() throws Exception {
        openProject(fourStepProject());
        selectWorldNode("one");
        final String source = Files.readString(directory.resolve("project.json"));
        clickOverview("#add-next-step");
        page.locator("[data-add-step]").first().focus();
        page.locator(".world-placement").waitFor();
        assertThat(page.locator(".world-placement").textContent()).isNotBlank();
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(source);
        page.keyboard().press("Escape");
        page.waitForFunction("() => !document.querySelector('.world-placement')");
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(source);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void filteringAwayAPreviewClearsTheProspectiveStep() {
        openProject(fourStepProject());
        selectWorldNode("one");
        clickOverview("#add-next-step");
        page.locator("[data-add-step]").first().focus();
        page.locator(".world-placement").waitFor();
        page.locator("#step-search").fill("no-such-step");
        page.waitForFunction("() => !document.querySelector('.world-placement')");
        assertThat(page.locator("[data-add-step]").count()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void acceptingAPlacementBuildsTheRealInsertedStep() throws Exception {
        openProject(fourStepProject());
        selectWorldNode("one");
        final String before = applicationPid();
        clickOverview("#add-next-step");
        page.locator("[data-add-step='railix.field-manipulation']").focus();
        page.locator(".world-placement").waitFor();
        page.locator("[data-add-step='railix.field-manipulation']").click();
        page.waitForFunction("() => !document.querySelector('.world-placement')");
        page.waitForFunction("before => Boolean(document.querySelector('#status-pid')?.textContent) && document.querySelector('#status-pid').textContent !== before", "PID " + before);
        waitForText("#build-state", "Built");
        assertThat(applicationPid()).isNotEqualTo(before);
        assertThat(Files.readString(directory.resolve("project.json"))).contains("step-");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void leavingAHoveredOptionRestoresTheKeyboardPlacementPreview() {
        openProject(fourStepProject());
        selectWorldNode("one");
        awaitScene();
        clickOverview("#add-next-step");
        final var options = page.locator("[data-add-step]");
        final String focused = "Insert " + options.first().locator("strong").textContent();
        final String hovered = "Insert " + options.nth(1).locator("strong").textContent();
        page.locator(".step-picker header").hover();
        options.first().focus();
        page.waitForFunction("focused => document.querySelector('.world-placement strong')?.textContent === focused", focused);
        options.nth(1).hover();
        page.waitForFunction("hovered => document.querySelector('.world-placement strong')?.textContent === hovered", hovered);
        page.locator(".step-picker header").hover();
        page.waitForFunction("focused => document.querySelector('.world-placement strong')?.textContent === focused", focused);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void triggerTargetsShowOnlyTheirKnownExampleOutput() {
        prepareTextPayloadTrigger();
        page.locator("[data-input-name='target'] [data-path-value='after']").waitFor();
        assertThat(page.locator("[data-input-name='target'] [data-path-value='after']").textContent()).isEqualTo("\"RAILIX\"");
        assertThat(page.locator("[data-input-name='target'] [data-path-value='before']").count()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"aspect", "roundness"})
    void invalidShapeNumbersStayAtTheInputWithoutChangingTheDiagram(final String field) {
        openProject(fourStepProject());
        selectWorldNode("one");
        openInspectorTab("appearance");
        final String metadata = creatorMetadata();
        page.locator("#presentation-" + field).fill("-1");
        page.locator("#presentation-" + field).press("Tab");
        assertThat(page.locator("#presentation-" + field).inputValue()).isEqualTo("-1");
        assertThat(page.locator("#presentation-" + field).evaluate("input => input.validity.rangeUnderflow")).isEqualTo(true);
        assertThat(creatorMetadata()).isEqualTo(metadata);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void stalledObservationsExpireMotionWithoutDiscardingTheSelectedExample() {
        openProject(choiceProject());
        chooseVisibleExample("command:0");
        selectWorldNode("matched");
        page.waitForFunction("() => worldMotionActive() && currentWorldObservations()?.links.size > 0");
        page.evaluate("""
                () => {
                  const fetch = window.fetch;
                  const releases = [];
                  window.fetch = async (...args) => {
                    const response = await fetch.apply(window, args);
                    if (String(args[0]).startsWith('/api/scene/observations?')) await new Promise(resolve => releases.push(resolve));
                    return response;
                  };
                  window.__restoreObservations = () => { window.fetch = fetch; releases.forEach(resolve => resolve()); };
                }
                """);
        try {
            page.waitForFunction("() => !worldMotionActive()");
            assertThat(page.evaluate("() => Boolean(currentWorldObservations())")).isEqualTo(true);
            assertThat(page.locator("[data-node-id='matched']").getAttribute("data-coverage")).isEqualTo("selected");
        } finally {
            page.evaluate("() => window.__restoreObservations()");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"rectangle", "ellipse", "triangle", "diamond"})
    void appearanceShapeChangesOnlyMetadataAndSurvivesReload(final String shape) throws Exception {
        openProject(fourStepProject());
        selectWorldNode("one");
        final String functional = Files.readString(directory.resolve("project.json"));
        final String pid = applicationPid();
        openInspectorTab("appearance");
        page.locator("#presentation-shape").selectOption(shape);
        page.locator("#presentation-aspect").fill("2.625");
        page.locator("#presentation-aspect").press("Tab");
        page.waitForFunction("() => !state.writeActive && Number(numberText(state.savedCreator.steps.one?.aspect)) === 2.625");
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(functional);
        assertThat(applicationPid()).isEqualTo(pid);
        page.reload();
        waitForText("#build-state", "Built");
        selectWorldNode("one");
        openInspectorTab("appearance");
        assertThat(page.locator("#presentation-shape").inputValue()).isEqualTo(shape);
        assertThat(page.locator("#presentation-aspect").inputValue()).isEqualTo("2.625");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void sourceAndTargetShowTheBuiltExamplesValuesAtTheirFields() throws Exception {
        addGraphPrimitive("\"RAILIX\"", "lowercase", "text.lowercase");
        page.locator("[data-input-name='target'] [data-path-value='after']").waitFor();

        assertThat(page.locator("[data-input-name='source'] [data-path-value='before']").textContent())
                .isEqualTo("\"RAILIX\"");
        assertThat(page.locator("[data-input-name='target'] [data-path-value='before']").textContent())
                .isEqualTo("\"RAILIX\"");
        assertThat(page.locator("[data-input-name='target'] [data-path-value='after']").textContent())
                .isEqualTo("\"railix\"");
        assertThat(page.locator("#inspector").textContent()).doesNotContain("Built example", "Built output");
        final Path screenshots = Files.createDirectories(Path.of("target", "screenshots"));
        page.screenshot(new Page.ScreenshotOptions().setFullPage(true)
                .setPath(screenshots.resolve("inspector-field-values-" + page.viewportSize().width + ".png")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "false", "0", "\"\"", "[]", "{}", "0.10000000000000001"})
    void anExistingFieldIsNotConfusedWithMissing(final String value) {
        openProject(fourStepProject().replace("\"context\":{\"payload\":{}}",
                "\"context\":{\"payload\":{\"one\":" + value + "}}"));
        chooseVisibleExample("command:0");
        selectWorldNode("one");
        page.locator("[data-input-name='field'] [data-path-value='before']").waitFor();

        assertThat(page.locator("[data-input-name='field'] [data-path-value='before']").textContent())
                .isEqualTo(value);
    }

    @Test
    void closingAndReopeningKeepsAnInvalidJsonDraft() {
        selectWorldNode("app");
        addTrigger();
        openInspectorTab("examples");
        examplePayload().fill("{not finished");
        page.locator("#close-inspector").click();
        assertThat(page.locator("#inspector").isHidden()).isTrue();
        openInspectorTab("overview");

        assertThat(examplePayload().inputValue()).isEqualTo("{not finished");
    }

    @Test
    void pathChoicesShowOnlyTheSelectedExamplesValues() {
        openProject(choiceProject());
        selectTrigger();
        openInspectorTab("examples");
        chooseExampleIndex(1);
        selectWorldNode("choice");
        page.waitForFunction("() => state.traceStep?.id === 'choice'");
        page.locator("[data-open-path]").first().click();
        page.locator("[data-path-depth='1']").click();

        assertThat(page.locator("[data-path-part='value'] output").textContent()).isEqualTo("\"deny\"");
        assertThat(page.locator(".path-choices").textContent()).doesNotContain("allow");
    }

    @Test
    void aNewTargetShowsMissingBeforeAndItsRealWrittenValueAfter() {
        openProject(fourStepProject());
        chooseVisibleExample("command:0");
        selectWorldNode("one");
        page.locator("[data-input-name='field'] [data-path-value='after']").waitFor();

        assertThat(page.locator("[data-input-name='field'] [data-path-value='before']").textContent())
                .isEqualTo("Missing");
        assertThat(page.locator("[data-input-name='field'] [data-path-value='after']").textContent())
                .isEqualTo("1");
    }

    @Test
    void anUnreachedStepDoesNotDisplayMissingAsIfItRan() {
        openProject(choiceProject());
        chooseVisibleExample("command:0");
        selectWorldNode("otherwise");

        waitForText("[data-input-name='field'] .path-values", "Not reached by this Example");
        assertThat(page.locator("[data-input-name='field'] [data-path-value]").count()).isZero();
    }

    @Test
    void inspectorClosePreservesSelectionExampleAndCamera() {
        openProject(choiceProject());
        selectTrigger();
        openInspectorTab("examples");
        chooseExampleIndex(1);
        awaitScene();
        final Object center = cameraCenter();
        final Object before = page.evaluate("""
                () => ({selection: state.selection, example: state.exampleIndex,
                  project: JSON.stringify(state.project), creator: JSON.stringify(state.creator)})
                """);

        page.locator("#close-inspector").click();
        page.waitForFunction("() => document.querySelector('#inspector').hidden");
        awaitScene();

        assertThat(page.evaluate("""
                () => ({selection: state.selection, example: state.exampleIndex,
                  project: JSON.stringify(state.project), creator: JSON.stringify(state.creator)})
                """)).isEqualTo(before);
        assertThat(cameraCenter()).isEqualTo(center);
        assertThat(page.locator("#graph").boundingBox().width)
                .isGreaterThan(page.viewportSize().width - 40);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void reselectingAStationOpensItsInspectorImmediately() {
        openProject(choiceProject());
        selectTrigger();
        page.locator("#close-inspector").click();
        page.locator("[data-node-id='command']").click();

        page.waitForFunction("() => !state.editorController && document.querySelector('#inspector').dataset.selection === 'command'");
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        openInspectorTab("overview");
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isEqualTo("command");
    }

    @Test
    void escapeClosesThePathChooserBeforeTheInspectorWithoutChangingThePath() {
        openProject(choiceProject());
        selectTrigger();
        final String before = page.locator("[data-open-path]").first().textContent();
        page.locator("[data-open-path]").first().click();

        page.keyboard().press("Escape");

        assertThat(page.locator(".path-browser").count()).isZero();
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        assertThat(page.locator("[data-open-path]").first().textContent()).isEqualTo(before);
        page.keyboard().press("Escape");
        assertThat(page.locator("#inspector").isHidden()).isTrue();
    }

    @Test
    void diagramNeedsNoViewModeOrDiscoveryControl() {
        openProject(choiceProject());

        assertThat(page.locator("[data-lens], #world-example, #world-objective").count()).isZero();
    }

    @Test
    void triggerExampleSelectionHighlightsItsRouteWithoutChoosingAView() {
        openProject(choiceProject());
        selectTrigger();
        openInspectorTab("examples");
        chooseExampleIndex(1);

        page.evaluate("() => void state.world.fit()");
        awaitScene();
        waitForCoverage("otherwise", "selected");
        assertThat(page.locator("#visible-example").inputValue()).endsWith(":1");
    }

    @Test
    void selectedExampleSurvivesAppAndDownstreamInspection() {
        openProject(choiceProject());
        selectTrigger();
        openInspectorTab("examples");
        chooseExampleIndex(1);
        selectWorldNode("app");
        selectWorldNode("otherwise");

        waitForCoverage("otherwise", "selected");
        selectTrigger();
        openInspectorTab("examples");
        assertThat(page.locator("#visible-example").inputValue()).endsWith(":1");
    }

    @Test
    void rejectedTriggerSelectionKeepsTheApplicationSelected() {
        openProject(choiceProject());
        final var editorRequests = Pattern.compile(".*/api/editor\\?.*");
        page.route(editorRequests, route -> {
            final var headers = new HashMap<>(route.request().headers());
            headers.put("x-railix-creator-token", "invalid-token");
            route.resume(new Route.ResumeOptions().setHeaders(headers));
        });
        try {
            final var response = page.waitForResponse(candidate -> editorRequests.matcher(candidate.url()).matches(),
                    () -> page.locator("[data-select-node='command']").click());
            assertThat(response.status()).isEqualTo(401);
            response.finished();
            page.waitForFunction("""
                    () => !state.editorController && state.localDiagnostics.some(issue =>
                      issue.code === 'CREATOR_EDITOR_UNAVAILABLE' && issue.node === 'command')
                    """);
            page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))");

            assertThat(page.locator("#inspector").getAttribute("data-selection")).isEqualTo("app");
            assertThat(page.locator("#inspector").getAttribute("data-selection")).isEqualTo("app");
            assertThat(pageErrors).isEmpty();
        } finally {
            page.unroute(editorRequests);
        }
    }

    @Test
    void supersededTriggerSelectionPreservesTheNewerCanvasSelection() {
        openProject(choiceProject());
        page.evaluate("() => void state.world.fit()");
        awaitScene();
        page.evaluate("""
                () => {
                  const fetch = window.fetch;
                  const probe = window.__supersededExample = {held:false};
                  window.fetch = async (...args) => {
                    const url = new URL(String(args[0]), location.href);
                    const editor = !probe.held && url.pathname === '/api/editor'
                      && url.searchParams.get('node') === 'command';
                    if (editor) probe.held = true;
                    const response = await fetch.apply(window, args);
                    if (editor) {
                      probe.status = response.status;
                      const text = response.text.bind(response);
                      response.text = async () => {
                        try {
                          return await text();
                        } finally {
                          // Reselection can abort the real body; wait for either consumer path to finish.
                          setTimeout(() => { probe.editorConsumed = true; }, 0);
                        }
                      };
                      await new Promise(resolve => { probe.releaseEditor = resolve; });
                    }
                    return response;
                  };
                  probe.restore = () => { probe.releaseEditor?.(); window.fetch = fetch; };
                }
                """);
        try {
            page.locator("[data-select-node='command']").click();
            page.waitForFunction("() => Boolean(window.__supersededExample.releaseEditor)");
            assertThat(page.evaluate("() => window.__supersededExample.status")).isEqualTo(200);

            final var response = page.waitForResponse(candidate -> candidate.url().contains("/api/editor?")
                            && List.of(java.net.URI.create(candidate.url()).getRawQuery().split("&")).contains("node=app"),
                    () -> page.locator("[data-select-node='app']").click());
            assertThat(response.status()).isEqualTo(200);
            response.finished();
            page.waitForFunction("""
                    () => !state.editorController && document.querySelector('#inspector')?.dataset.selection === 'app'
                      && document.querySelector('[data-select-node=app]')?.getAttribute('aria-pressed') === 'true'
                    """);
            final Object selectedExample = page.evaluate("""
                    () => JSON.stringify({selection:state.selection, index:state.exampleIndex, draft:state.exampleDraft})
                    """);

            page.evaluate("() => window.__supersededExample.releaseEditor()");
            page.waitForFunction("() => window.__supersededExample.editorConsumed === true");
            page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))");

            assertThat(page.locator("#inspector").getAttribute("data-selection")).isEqualTo("app");
            assertThat(page.locator("[data-select-node='app']").getAttribute("aria-pressed")).isEqualTo("true");
            assertThat(page.evaluate("""
                    () => JSON.stringify({selection:state.selection, index:state.exampleIndex, draft:state.exampleDraft})
                    """)).isEqualTo(selectedExample);
            assertThat(pageErrors).isEmpty();
        } finally {
            page.evaluate("() => window.__supersededExample.restore()");
        }
    }

    @Test
    void addingStepSupersedesAnUnfinishedTriggerFocus() {
        addTrigger();
        waitForText("#build-state", "Built");
        selectTrigger();
        page.evaluate("""
                () => {
                  const fetch = window.fetch, trigger = state.selection.id;
                  const probe = window.__pendingFocus = {releases:[]};
                  window.fetch = async (...args) => {
                    const response = await fetch.apply(window, args);
                    const url = new URL(String(args[0]), location.href);
                    if (url.pathname === '/api/scene' && url.searchParams.get('focus') === trigger) {
                      const json = response.json.bind(response);
                      response.json = async () => {
                        const scene = await json();
                        if (!probe.released) await new Promise(resolve => probe.releases.push(resolve));
                        return scene;
                      };
                    }
                    return response;
                  };
                  probe.restore = () => {
                    probe.released = true;
                    window.fetch = fetch;
                    probe.releases.splice(0).forEach(release => release());
                  };
                  state.world.focus(trigger);
                }
                """);
        try {
            page.waitForFunction("() => window.__pendingFocus.releases.length > 0");
            addManipulationAfterSelected();
            waitForText("#build-state", "Built");
            page.evaluate("() => window.__pendingFocus.restore()");
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator(".step-node.selected"))
                    .isVisible();
            assertThat(pageErrors).isEmpty();
        } finally {
            page.evaluate("() => window.__pendingFocus.restore()");
        }
    }

    @Test
    void fittingWhileStepEditorLoadsRetainsTheFittedCamera() {
        openProject(filterProject());
        selectTrigger();
        openInspectorTab("examples");
        chooseExampleIndex(1);
        page.locator("#delete-example").click();
        waitForText("#build-state", "Built");
        waitForText("#status-coverage", "75% coverage");
        page.locator("#close-inspector").click();
        awaitScene();
        page.evaluate("""
                () => {
                  const fetch = window.fetch;
                  const probe = window.__unvisitedFit = {held:false, focusAfterFit:[]};
                  window.fetch = async (...args) => {
                    const url = new URL(String(args[0]), location.href);
                    const editor = !probe.held && url.pathname === '/api/editor'
                      && url.searchParams.get('node') === 'otherwise';
                    if (editor) probe.held = true;
                    if (probe.fitted && url.pathname === '/api/scene' && url.searchParams.has('focus')) {
                      probe.focusAfterFit.push(url.searchParams.get('focus'));
                    }
                    const response = await fetch.apply(window, args);
                    if (editor) {
                      probe.status = response.status;
                      await new Promise(resolve => { probe.releaseEditor = resolve; });
                      const text = response.text.bind(response);
                      response.text = async () => {
                        const body = await text();
                        // Cross a task boundary after the editor and selection continuations consume the body.
                        setTimeout(() => { probe.editorConsumed = true; }, 0);
                        return body;
                      };
                    }
                    return response;
                  };
                  probe.restore = () => { probe.releaseEditor?.(); window.fetch = fetch; };
                }
                """);
        try {
            page.locator("[data-select-node='otherwise']").click();
            page.waitForFunction("() => Boolean(window.__unvisitedFit.releaseEditor)");
            assertThat(page.evaluate("() => window.__unvisitedFit.status")).isEqualTo(200);

            page.evaluate("() => void state.world.fit()");
            awaitScene();
            final Object fitted = cameraCenter();
            final String fittedZoom = page.locator("#zoom-level").textContent();
            page.evaluate("() => { window.__unvisitedFit.fitted = true; window.__unvisitedFit.releaseEditor(); }");
            page.waitForFunction("() => window.__unvisitedFit.editorConsumed === true && !state.editorController");
            awaitScene();

            assertThat(cameraCenter()).isEqualTo(fitted);
            assertThat(page.locator("#zoom-level").textContent()).isEqualTo(fittedZoom);
            assertThat(page.evaluate("() => window.__unvisitedFit.focusAfterFit")).isEqualTo(List.of());
            assertThat(pageErrors).isEmpty();
        } finally {
            page.evaluate("() => window.__unvisitedFit.restore()");
        }
    }

    @Test
    void searchingGroupsFromTheSecondPageResetsPaginationToTheFirstMatchPage() {
        openProject(fourStepProject());
        assertThat(page.evaluate("""
                async () => {
                  const groups = Array.from({length:130}, (_, index) => {
                    const suffix = String(index).padStart(3, '0');
                    return {id:'group-' + suffix, name:(index < 70 ? 'Match ' : 'Other ') + suffix};
                  });
                  return (await fetch('/api/creator', {method:'POST', headers:mutationHeaders(),
                    body:JSON.stringify({format:2, groups, steps:{}})})).status;
                }
                """)).isEqualTo(200);
        page.reload();
        waitForText("#build-state", "Built");
        selectWorldNode("app");
        page.locator("[data-inspector-mode=groups]").click();
        page.locator("#group-list [data-manage-group='group-000']").waitFor();
        page.locator("#inspector [data-group-page='64']").click();
        page.locator("#group-list [data-manage-group='group-100']").waitFor();
        // A nonmatching selection is retained by the server but must not add a seventh search result.
        page.locator("#group-list [data-manage-group='group-100']").click();
        assertThat(page.locator("#inspector [data-group-page]").last().getAttribute("data-group-page"))
                .isEqualTo("128");

        final var search = page.waitForResponse(candidate -> candidate.url().contains("/api/editor?")
                        && candidate.url().contains("q=match") && candidate.url().contains("offset=0"),
                () -> page.locator("#group-search").fill("match"));
        assertThat(search.status()).isEqualTo(200);
        search.finished();
        page.waitForFunction("""
                () => {
                  const names = [...document.querySelectorAll('#group-list [data-manage-group] strong')];
                  return names.length === 64 && names.every(name => name.textContent.startsWith('Match '));
                }
                """);

        final var next = page.waitForResponse(candidate -> candidate.url().contains("/api/editor?"),
                () -> page.locator("#inspector [data-group-page]").last().click());
        assertThat(next.status()).isEqualTo(200);
        assertThat(java.net.URI.create(next.url()).getRawQuery().split("&")).contains("q=match", "offset=64");
        next.finished();
        page.waitForFunction("() => document.querySelectorAll('#group-list [data-manage-group]').length === 6");
        assertThat(page.locator("#group-list [data-manage-group] strong").allTextContents())
                .containsExactly("Match 064", "Match 065", "Match 066", "Match 067", "Match 068", "Match 069");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void fittingDuringAPendingSceneRefreshDoesNotRevealAnOlderEdit() {
        openProject(fourStepProject());
        selectWorldNode("one");
        page.evaluate("""
                () => {
                  const fetch = window.fetch;
                  window.fetch = async (...args) => {
                    const response = await fetch.apply(window, args);
                    if (String(args[0]).startsWith('/api/scene?') && !window.__releaseScene) {
                      await new Promise(resolve => window.__releaseScene = resolve);
                    }
                    return response;
                  };
                  window.__restoreSceneFetch = () => window.fetch = fetch;
                }
                """);
        try {
            addManipulationAfterSelected();
            page.waitForFunction("() => typeof window.__releaseScene === 'function'");
            page.evaluate("() => void state.world.fit()");
            page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving !== 'true'");
            final String fitted = page.locator("#zoom-level").textContent();
            page.evaluate("() => window.__releaseScene()");
            awaitScene();

            assertThat(page.locator("#zoom-level").textContent()).isEqualTo(fitted);
            assertThat(page.locator("[data-select-node='app']").isVisible()).isTrue();
            assertThat(pageErrors).isEmpty();
        } finally {
            page.evaluate("() => { window.__releaseScene?.(); window.__restoreSceneFetch(); }");
        }
    }

    @Test
    void selectingAnotherNodeCancelsADelayedZoomWithoutMovingTheCamera() {
        openProject(fourStepProject());
        selectWorldNode("one");
        page.evaluate("() => void state.world.fit()");
        awaitScene();
        page.evaluate("""
                () => {
                  const fetch = window.fetch;
                  window.fetch = async (...args) => {
                    const response = await fetch.apply(window, args);
                    const url = new URL(String(args[0]), location.href);
                    if (url.pathname === '/api/scene' && url.searchParams.get('focus') === 'one'
                        && !window.__releaseZoom) {
                      await new Promise(resolve => window.__releaseZoom = resolve);
                    }
                    return response;
                  };
                  window.__restoreZoomFetch = () => window.fetch = fetch;
                }
                """);
        try {
            page.locator("[data-select-node='one']").dblclick();
            page.waitForFunction("() => typeof window.__releaseZoom === 'function'");
            page.locator("[data-select-node='app']").click();
            page.waitForFunction("() => document.querySelector('#inspector').dataset.selection === 'app'");
            page.evaluate("() => window.__releaseZoom()");
            awaitScene();

            assertThat(page.locator("[data-select-node='app']").isVisible()).isTrue();
            assertThat(page.locator("[data-select-node='app']").getAttribute("aria-pressed")).isEqualTo("true");
            assertThat(pageErrors).isEmpty();
        } finally {
            page.evaluate("() => { window.__releaseZoom?.(); window.__restoreZoomFetch(); }");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"command", "matched"})
    void nodeMetricsStayBehindADisclosureWithoutHidingTheirSetting(final String id) {
        openProject(choiceProject());
        selectWorldNode(id);

        assertThat(page.locator("#node-metrics").isVisible()).isTrue();
        assertThat(page.locator("#metrics-panel").isHidden()).isTrue();
        openInspectorSection("Runtime metrics");
        waitForText(".runtime-metrics .section-heading", "Step metrics");
        assertThat(page.locator(".runtime-metrics").textContent()).contains("Step metrics");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void unchangedStepMetricsKeepTheirVisibleValues() {
        openProject(choiceProject());
        selectWorldNode("matched");
        openInspectorSection("Runtime metrics");
        final var metric=page.locator("#metrics-panel dd").first();
        metric.waitFor();
        final var value=page.evaluateHandle("() => document.querySelector('#metrics-panel dd')");
        for(int poll=0;poll<3;poll++)page.waitForResponse(
                response->response.url().endsWith("/api/metrics/nodes/matched"),()->{});
        assertThat(page.evaluate("value => value.isConnected",value)).isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void unchangedApplicationPollsDoNotRedrawTheWorldOrReplaceTheExamplePreview() {
        openProject(choiceProject());
        chooseVisibleExample("command:0");
        selectWorldNode("matched");
        awaitScene();
        page.waitForFunction("""
                () => Boolean(state.preview) && state.application.examples?.state === 'completed'
                  && !state.applicationRefreshing && state.observations?.nodes.get('matched')?.rate === 0
                """);
        // The independently loaded overview repaints scenery once it arrives.
        page.locator("#world-map").waitFor();
        page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))");
        page.evaluate("""
                () => {
                  const fetch = window.fetch;
                  const probe = window.__idleWorld = {draws:0, replacements:0, polls:0};
                  const observer = new MutationObserver(records => probe.replacements += records.length);
                  observer.observe(document.querySelector('#preview-values'), {childList:true,subtree:true});
                  const world = new MutationObserver(records => probe.draws += records.length);
                  world.observe(document.querySelector('#world-plane'), {childList:true,subtree:true,attributes:true});
                  window.fetch = async (...args) => {
                    const response = await fetch.apply(window, args);
                    if (String(args[0]) === '/api/examples/status') probe.polls++;
                    return response;
                  };
                  probe.restore = () => {
                    observer.disconnect();
                    world.disconnect();
                    window.fetch = fetch;
                  };
                }
                """);
        try {
            page.waitForFunction("() => window.__idleWorld.polls >= 3 && !state.applicationRefreshing");
            page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))");
            assertThat(page.evaluate("() => window.__idleWorld.draws")).isEqualTo(0);
            assertThat(page.evaluate("() => window.__idleWorld.replacements")).isEqualTo(0);
            assertThat(pageErrors).isEmpty();
        } finally {
            page.evaluate("() => { window.__idleWorld.restore(); delete window.__idleWorld; }");
        }
    }

    @Test
    void diagramDistinguishesDisabledMetricsFromIdleStepsWithoutInventingTimings() {
        openProject(unreachedBranchProject());
        page.waitForFunction("() => document.querySelector('[data-node-id=matched]')?.dataset.activity === 'disabled'");
        assertThat(page.locator("#status-observations").isHidden()).isTrue();

        assertThat(page.locator("[data-node-id='matched']").getAttribute("title")).endsWith("Metrics off");
        assertThat(page.locator("[data-node-id='otherwise']").getAttribute("data-activity")).isEqualTo("idle");
        assertThat(page.locator("[data-node-id='otherwise']").getAttribute("title"))
                .contains("0 executions").doesNotContain("sampled");
        assertThat(page.locator("[data-node-id='otherwise']").getAttribute("title"))
                .contains("No duration inferred.");
        assertThat(pageErrors).isEmpty();
    }

    private static String unreachedBranchProject() {
        // Both the real CLI startup and Examples must leave the other branch untouched.
        return choiceProject().replace("\"id\":\"matched\",", "\"id\":\"matched\",\"metrics\":false,")
                .replace("\"option\":\"field\",\"inputs\":{\"field\":[\"context\",\"payload\",\"value\"]}",
                        "\"option\":\"literal\",\"inputs\":{\"value\":\"allow\"}");
    }

    @Test
    void observationFooterReportsFailureAndDisappearsAfterRecovery() {
        openProject(choiceProject());
        page.waitForFunction("() => document.querySelector('[data-node-id=otherwise]')?.dataset.activity === 'active'");
        assertThat(page.locator("#status-observations").isHidden()).isTrue();
        final String endpoint = "**/api/scene/observations?*";
        page.route(endpoint, route -> route.fulfill(new Route.FulfillOptions().setStatus(503).setBody("Unavailable")));
        try {
            waitForText("#status-observations", "Observations unavailable");
            assertThat(page.locator("#status-observations").isVisible()).isTrue();
        } finally {
            page.unroute(endpoint);
        }
        page.waitForFunction("() => document.querySelector('[data-node-id=otherwise]')?.dataset.activity === 'active'");
        assertThat(page.locator("#status-observations").isHidden()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void stoppedApplicationClearsTheLiveSceneInsteadOfRetainingConnectedMetrics() {
        openProject(choiceProject());
        selectWorldNode("otherwise");
        page.waitForFunction("() => document.querySelector('[data-node-id=otherwise]')?.dataset.activity === 'active'");
        final ProcessHandle child = ProcessHandle.of(Long.parseLong(applicationPid())).orElseThrow();

        assertThat(child.destroy()).isTrue();
        page.waitForFunction("() => document.querySelector('[data-node-id=otherwise]')?.dataset.activity === ''");

        assertThat(page.locator("[data-node-id='otherwise']").getAttribute("title")).doesNotContain("executions");
        assertThat(page.locator("#status-observations").isHidden()
                || !page.locator("#status-observations").textContent().contains("connected")).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void unloadedGroupUsesItsPortableIconAndApplicationOwnedExampleCoverage() throws Exception {
        openProject(fourStepProject());
        assertThat(page.evaluate("""
                async () => {
                  const icon = (await (await fetch('/api/icons')).json()).icons[0];
                  const metadata = {format:2,groups:[{id:'flow-group',name:'Value preparation',
                    icon:{media_type:icon.media_type,data:icon.data}}],
                    steps:Object.fromEntries(['one','two','three','four'].map(id=>[id,{group:'flow-group'}]))};
                  return (await fetch('/api/creator',{method:'POST',headers:mutationHeaders(),body:JSON.stringify(metadata)})).status;
                }
                """)).isEqualTo(200);
        page.reload();
        waitForText("#build-state", "Built");
        page.waitForFunction("() => document.querySelector('[data-region-group=flow-group] img')?.naturalWidth > 0");
        assertThat(page.evaluate("() => state.editor.full")).isEqualTo(List.of("app"));
        selectTrigger();
        openInspectorTab("examples");
        chooseExampleIndex(0);
        page.waitForFunction("""
                () => document.querySelector('[data-region-group=flow-group]')?.dataset.coverage === 'selected'
                """);

        assertThat(page.locator("[data-region-group='flow-group'] img").getAttribute("src")).startsWith("blob:");
        assertThat(page.locator("#status-coverage").textContent()).isEqualTo("100% coverage");
        assertThat(pageErrors).isEmpty();
        page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setPath(
                Files.createDirectories(Path.of("target", "screenshots"))
                        .resolve("world-group-example-" + page.viewportSize().width + ".png")));
    }

    @Test
    void selectingAnUnloadedTriggerLoadsItsExamplesWithoutLoadingTheFlow() {
        final List<String> wholeProjectReads = new ArrayList<>();
        page.onRequest(request -> {
            if (request.method().equals("GET") && request.url().endsWith("/api/project")) wholeProjectReads.add(request.url());
        });
        openProject(choiceProject());
        assertThat(page.evaluate("() => state.project.nodes.every(node => node.id === 'app' || !node.inputs)"))
                .isEqualTo(true);
        selectTrigger();
        try {
            page.waitForFunction("() => state.selection.type === 'trigger' && Boolean(state.runResult)",
                    null, new com.microsoft.playwright.Page.WaitForFunctionOptions().setTimeout(10_000));
        } catch (final com.microsoft.playwright.TimeoutError failure) {
            throw new AssertionError(page.evaluate("() => JSON.stringify({selection:state.selection,editor:state.editor,project:state.project,errors:state.localDiagnostics,trace:state.traceStep,summary:state.traceSummary,application:state.application,traceCases:state.traceCases})")
                    + " Browser errors: " + pageErrors, failure);
        }
        assertThat(pageErrors).isEmpty();
        assertThat(page.locator("#inspector").textContent()).contains("matched");
        assertThat(wholeProjectReads).isEmpty();
    }

    @Test
    void delayedGroupSearchCannotRestoreAnOlderRevisionDuringANewerWrite() {
        openProject(fourStepProject());
        selectWorldNode("four");
        openInspectorTab("groups");
        page.locator("#group-library > summary").click();
        page.locator("#group-search").waitFor();
        final Object expected = page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  project.nodes.find(node => node.id === 'four').metrics = false;
                  project.nodes.find(node => node.id === 'two').metrics = false;
                  return project;
                }
                """);
        final List<String> writes = new ArrayList<>();
        page.onRequest(request -> {
            if (request.method().equals("POST") && request.url().endsWith("/api/editor")) {
                writes.add(request.postData());
            }
        });
        page.evaluate("""
                () => {
                  const request = window.fetch.bind(window);
                  let editorHeld = false;
                  let writeHeld = false;
                  window.__editorWriteRace = {};
                  window.fetch = async (input, options = {}) => {
                    const url = new URL(typeof input === 'string' ? input : input.url, location.href);
                    const editor = !editorHeld && url.pathname === '/api/editor'
                      && url.searchParams.get('node') === 'four' && url.searchParams.get('q') === 'unused';
                    const write = !writeHeld && url.pathname === '/api/editor' && options.method === 'POST';
                    if (editor) editorHeld = true;
                    if (write) writeHeld = true;
                    const response = await request(input, options);
                    if (editor || write) {
                      const payload = await response.clone().json();
                      const key = editor ? 'editor' : 'write';
                      window.__editorWriteRace[key] = {status: response.status, revision: Number(payload.revision)};
                      await new Promise(resolve => {
                        window.__editorWriteRace[editor ? 'releaseEditor' : 'releaseWrite'] = resolve;
                      });
                    }
                    if (editor) {
                      const text = response.text.bind(response);
                      response.text = async () => {
                        const body = await text();
                        // Observe the next task only after loadEditor can consume this real response body.
                        setTimeout(() => { window.__editorWriteRace.editorConsumed = true; }, 0);
                        return body;
                      };
                    }
                    return response;
                  };
                }
                """);
        try {
            page.locator("#group-search").fill("unused");
            page.waitForFunction("() => Boolean(window.__editorWriteRace.releaseEditor)");
            openInspectorTab("inspect");
            page.locator("#node-metrics").uncheck();
            page.waitForFunction("() => Boolean(window.__editorWriteRace.releaseWrite)");
            assertThat(page.evaluate("""
                    () => window.__editorWriteRace.editor.status === 200
                      && window.__editorWriteRace.write.status === 200
                      && window.__editorWriteRace.write.revision > window.__editorWriteRace.editor.revision
                    """)).isEqualTo(true);

            page.evaluate("() => window.__editorWriteRace.releaseEditor()");
            page.waitForFunction("() => window.__editorWriteRace.editorConsumed === true");
            assertThat(page.locator("#inspector").getAttribute("data-selection"))
                    .as("The stale editor read must not replace the Inspector before the newer write is acknowledged")
                    .isEqualTo("four");
            assertThat(page.locator("#node-metrics").isChecked()).isFalse();

            page.evaluate("() => window.__editorWriteRace.releaseWrite()");
            page.waitForFunction("() => !state.writeActive && !state.editorController");
            waitForText("#build-state", "Built");
            selectWorldNode("two");
            page.locator("#node-metrics").uncheck();
            waitForText("#build-state", "Built");

            assertThat(writes).hasSize(2);
            for (int index = 0; index < writes.size(); index++) {
                final var edit = CreatorServerE2eSupport.object(writes.get(index));
                final var changes = (RailixValue.ObjectValue) ((RailixValue.ObjectValue) edit.values().get("command")).values().get("project");
                assertThat(changes.values()).containsOnlyKeys("nodes");
                assertThat(((RailixValue.ObjectValue) changes.values().get("nodes")).values())
                        .containsOnlyKeys(List.of("four", "two").get(index));
            }
            assertThat(page.evaluate("async () => (await (await fetch('/api/project')).json()).project"))
                    .isEqualTo(expected);
            assertThat(pageErrors).isEmpty();
        } finally {
            page.evaluate("""
                    () => {
                      window.__editorWriteRace.releaseEditor?.();
                      window.__editorWriteRace.releaseWrite?.();
                    }
                    """);
        }
    }

    @Test
    void invalidLiteralDraftSurvivesNavigationAndAnotherNodesSave() {
        openProject(fourStepProject());
        final Object expected = page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  project.nodes.find(node => node.id === 'two').metrics = false;
                  return project;
                }
                """);
        selectWorldNode("four");
        page.locator("#value-0-literal-value").fill("[");
        page.locator("#value-0-literal-value").press("Tab");
        assertThat(page.locator("#inspector").textContent()).contains("Value must be valid JSON.");

        selectWorldNode("app");
        selectWorldNode("two");
        final var response = page.waitForResponse(candidate -> candidate.url().endsWith("/api/editor")
                && candidate.request().method().equals("POST"), () -> page.locator("#node-metrics").uncheck());
        assertThat(response.status()).isEqualTo(200);
        waitForText("#build-state", "Built");
        assertThat(page.evaluate("async () => (await (await fetch('/api/project')).json()).project"))
                .isEqualTo(expected);

        selectWorldNode("four");
        assertThat(page.locator("#value-0-literal-value").inputValue()).isEqualTo("[");
        assertThat(page.locator("#inspector").textContent()).contains("Value must be valid JSON.");
        assertThat(page.locator("#preview-values output:not([data-field-error])").count()).isZero();
        assertThat(pageErrors).isEmpty();
    }
}
