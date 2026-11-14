package dev.nanonative.railix.creator;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

final class RailixCreatorCustomStepsBrowserIT extends RailixCreatorBrowserSupport {
    @Test
    @Tag("responsive")
    @Tag("browser-diagnostics")
    void definitionsShareFlowHistoryAndDeletionRequiresConfirmation() throws Exception {
        openProject(choiceProject());
        // A confirmed compile already supplies its catalog; unrelated editor reads must not gate it.
        page.route("**/api/editor**", route -> {
            if (route.request().method().equals("GET")) route.fulfill(new Route.FulfillOptions()
                    .setStatus(503).setContentType("application/json").setBody("{\"message\":\"Editor read unavailable\"}"));
            else route.resume();
        });
        try { createCustomStep("Normalize"); }
        finally { page.unroute("**/api/editor**"); }
        assertThat(page.locator("#editor-undo")).isEnabled();
        assertThat(page.locator("#editor-redo")).isDisabled();
        flowHistory("undo");
        assertThat(page.locator("#editor-undo")).isDisabled();
        assertThat(page.locator("#editor-redo")).isEnabled();
        org.assertj.core.api.Assertions.assertThat(Files.readString(directory.resolve(CustomSteps.FILE)))
                .contains("\"steps\":[]");
        flowHistory("redo");
        selectWorldNode("command");
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("Normalize");
        clickAndWaitForCreatorSave(() -> page.locator("[data-add-step='custom.normalize']").click());
        final String placement = page.locator("#inspector").getAttribute("data-selection");
        flowHistory("undo");
        org.assertj.core.api.Assertions.assertThat(Files.readString(directory.resolve("project.json")))
                .doesNotContain("custom.normalize");
        flowHistory("redo");
        selectWorldNode(placement);
        openCustomStepManager();
        assertThat(page.locator("#custom-step-list button")).isDisabled();
        assertThat(page.locator("#custom-step-list")).containsText("In use");
        final Path screenshots = Files.createDirectories(Path.of("target", "screenshots"));
        page.screenshot(new Page.ScreenshotOptions().setPath(
                screenshots.resolve("custom-step-manager-used-" + page.viewportSize().width + ".png")));
        page.locator("#close-custom-step-manager").click();
        selectWorldNode(placement);
        deleteAcceptedStep();
        flowHistory("undo"); // removing an occurrence remains undoable
        selectWorldNode(placement);
        assertThat(page.locator("#inspector")).containsText("Normalize");
        flowHistory("redo");
        openCustomStepManager();
        assertThat(page.locator("#custom-step-list button")).isEnabled();
        assertThat(page.locator("#custom-step-list button")).hasClass("danger");
        final Path custom = directory.resolve(CustomSteps.FILE);
        final String saved = Files.readString(custom);
        final var history = CreatorServerE2eSupport.object(CreatorServerE2eSupport.request(
                creator.baseUri(), "GET", "/api/editor", "").body()).values().get("history");
        final var deletionSent = new AtomicBoolean();
        page.onRequest(request -> {
            if (request.url().endsWith("/api/custom-steps") && request.method().equals("DELETE")) deletionSent.set(true);
        });
        page.locator("#custom-step-list button").click();
        assertThat(page.locator("#custom-step-delete-dialog")).isVisible();
        assertThat(page.locator("#custom-step-delete-dialog")).hasAttribute("role", "alertdialog");
        assertThat(page.locator("#custom-step-delete-dialog")).containsText("Normalize");
        assertThat(page.locator("#custom-step-delete-dialog")).containsText("can undo this deletion");
        assertThat(page.locator("#custom-step-delete-dialog")).containsText("lost when Creator closes");
        assertThat(page.locator("#custom-step-delete-dialog")).containsText("across all tabs");
        assertThat(page.locator("#cancel-custom-step-deletion")).isFocused();
        assertThat(page.locator("#confirm-custom-step-deletion")).hasClass("danger");
        org.assertj.core.api.Assertions.assertThat(deletionSent).isFalse();
        page.screenshot(new Page.ScreenshotOptions().setPath(
                screenshots.resolve("custom-step-deletion-warning-" + page.viewportSize().width + ".png")));
        page.locator("#cancel-custom-step-deletion").click();
        assertThat(page.locator("#custom-step-delete-dialog")).isHidden();
        assertThat(page.locator("#custom-step-list button")).isFocused();
        page.locator("#custom-step-list button").click();
        assertThat(page.locator("#custom-step-delete-dialog")).isVisible();
        page.locator("#cancel-custom-step-deletion").press("Escape");
        assertThat(page.locator("#custom-step-delete-dialog")).isHidden();
        assertThat(page.locator("#custom-step-manager")).isVisible();
        org.assertj.core.api.Assertions.assertThat(deletionSent).isFalse();
        org.assertj.core.api.Assertions.assertThat(Files.readString(custom)).isEqualTo(saved);
        org.assertj.core.api.Assertions.assertThat(CreatorServerE2eSupport.object(CreatorServerE2eSupport.request(
                creator.baseUri(), "GET", "/api/editor", "").body()).values().get("history")).isEqualTo(history);
        assertThat(page.locator("#custom-step-list button")).isEnabled();
        confirmCustomStepDeletion();
        assertThat(page.locator("#custom-step-manager-status")).containsText("no custom Steps");
        org.assertj.core.api.Assertions.assertThat(deletionSent).isTrue();
        assertThat(page.locator("#editor-undo")).isEnabled();
        assertThat(page.locator("#editor-redo")).isDisabled();
        page.locator("#close-custom-step-manager").click();
        flowHistory("undo");
        org.assertj.core.api.Assertions.assertThat(Files.readString(custom)).isEqualTo(saved);
        flowHistory("undo");
        selectWorldNode(placement);
        assertThat(page.locator("#inspector")).containsText("Normalize");
        flowHistory("redo");
        flowHistory("redo");
        org.assertj.core.api.Assertions.assertThat(Files.readString(custom)).contains("\"steps\":[]");
        createCustomStep("Normalize", CustomStepsE2eTest.METHOD.replace("toLowerCase", "toUpperCase"));
        assertThat(page.locator("#editor-undo")).isEnabled();
        assertThat(page.locator("#editor-redo")).isDisabled();
        org.assertj.core.api.Assertions.assertThat(Files.readString(custom))
                .contains("toUpperCase").doesNotContain("toLowerCase");
        org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
    }

    private void openCustomStepManager() {
        openProjectActions();
        page.locator("[data-manage-custom-steps]").click();
        assertThat(page.locator("#custom-step-manager")).isVisible();
        assertThat(page.locator("#custom-step-manager-status")).not().containsText("Loading");
    }

    private void flowHistory(final String action) {
        final var response = page.waitForResponse(candidate -> candidate.url().endsWith("/api/editor")
                        && candidate.request().method().equals("POST"), () -> page.locator("#editor-" + action).click());
        org.assertj.core.api.Assertions.assertThat(response.status()).isEqualTo(200);
        page.waitForFunction("() => !state.actionPending");
    }

    private void confirmCustomStepDeletion() {
        page.locator("#custom-step-list button").click();
        assertThat(page.locator("#custom-step-delete-dialog")).isVisible();
        final var response = page.waitForResponse(candidate -> candidate.url().endsWith("/api/custom-steps")
                        && candidate.request().method().equals("DELETE"), () -> page.locator("#confirm-custom-step-deletion").click());
        org.assertj.core.api.Assertions.assertThat(response.status()).isEqualTo(200);
    }

    @Test
    @Tag("responsive")
    @Tag("browser-diagnostics")
    void deletedDefinitionCanBeRecompiledWithoutEditingItsDraft() throws Exception {
        openProject(choiceProject());
        createCustomStep("Normalize");
        openCustomStepManager();
        confirmCustomStepDeletion();
        assertThat(page.locator("#custom-step-manager-status")).containsText("no custom Steps");
        page.locator("#close-custom-step-manager").click();

        openCustomStepEditor();
        assertThat(page.locator("#custom-step-name")).hasValue("Normalize");
        assertThat(page.locator("#custom-step-source")).hasValue(CustomStepsE2eTest.METHOD);
        assertThat(page.locator("#compile-custom-step")).isEnabled();
        assertThat(page.locator("#custom-step-status")).containsText("Not compiled");
        final var recreated = page.waitForResponse(response -> response.url().endsWith("/api/custom-steps")
                && response.request().method().equals("POST"), () -> page.locator("#compile-custom-step").click());
        org.assertj.core.api.Assertions.assertThat(recreated.status()).as(recreated.text()).isEqualTo(200);
        assertThat(page.locator("#custom-step-status")).containsText("Compiled.");
        page.locator("#close-custom-step").click();

        flowHistory("undo");
        openCustomStepEditor();
        assertThat(page.locator("#compile-custom-step")).isEnabled();
        assertThat(page.locator("#custom-step-source")).hasValue(CustomStepsE2eTest.METHOD);
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target", "screenshots"))
                .resolve("custom-step-draft-after-undo-" + page.viewportSize().width + ".png")));
        page.locator("#close-custom-step").click();
        flowHistory("redo");
        openCustomStepEditor();
        assertThat(page.locator("#custom-step-status")).containsText("Compiled.");
        assertThat(page.locator("#compile-custom-step")).isDisabled();
        org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    @Tag("browser-diagnostics")
    void compiledDraftDistinguishesRestoredDefinitionsWithTheSameId() throws Exception {
        openProject(choiceProject());
        createCustomStep("Normalize");
        openCustomStepManager();
        confirmCustomStepDeletion();
        assertThat(page.locator("#custom-step-manager-status")).containsText("no custom Steps");
        page.locator("#close-custom-step-manager").click();
        final String replacement = CustomStepsE2eTest.METHOD.replace("toLowerCase", "toUpperCase");
        createCustomStep("Normalize", replacement);
        flowHistory("undo"); // remove the replacement
        flowHistory("undo"); // restore the original definition, with the same ID

        openCustomStepEditor();
        assertThat(page.locator("#custom-step-name")).hasValue("Normalize");
        assertThat(page.locator("#custom-step-source")).hasValue(replacement);
        assertThat(page.locator("#compile-custom-step")).isEnabled();
        assertThat(page.locator("#custom-step-status")).containsText("Not compiled");
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target", "screenshots"))
                .resolve("custom-step-draft-replaced-" + page.viewportSize().width + ".png")));
        page.locator("#close-custom-step").click();
        flowHistory("redo");
        flowHistory("redo");
        openCustomStepEditor();
        assertThat(page.locator("#custom-step-status")).containsText("Compiled.");
        assertThat(page.locator("#compile-custom-step")).isDisabled();
        page.locator("#close-custom-step").click();

        final var other = CreatorServerE2eSupport.request(creator.baseUri(), "POST", "/api/custom-steps",
                "{\"name\":\"Other\",\"source\":\"public static boolean other() { return true; }\"}");
        org.assertj.core.api.Assertions.assertThat(other.statusCode()).as(other.body()).isEqualTo(200);
        openCustomStepManager(); // accept a catalog revision that changed an unrelated definition
        assertThat(page.locator("#custom-step-list li")).hasCount(2);
        page.locator("#close-custom-step-manager").click();
        openCustomStepEditor();
        assertThat(page.locator("#custom-step-status")).containsText("Compiled.");
        assertThat(page.locator("#compile-custom-step")).isDisabled();
        assertThat(page.locator("#custom-step-source")).hasValue(replacement);
        page.locator("#custom-step-source").fill(replacement + "\n");
        assertThat(page.locator("#compile-custom-step")).isEnabled();
        assertThat(page.locator("#custom-step-status")).containsText("Not compiled");
        org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    @Tag("browser-diagnostics")
    void multipleParametersHaveIndependentTypedFieldMappings() throws Exception {
        openProject(choiceProject()
                .replace("\"name\":\"match\"", "\"id\":\"first-case\",\"name\":\"match\"")
                .replace("\"name\":\"otherwise\"", "\"id\":\"second-case\",\"name\":\"otherwise\"")
                .replace("{\"payload\":{\"value\":\"allow\"}}",
                        "{\"payload\":{\"aString\":\"ignore\",\"first\":\"Ada\",\"last\":\"Lovelace\",\"formal\":true,\"value\":\"allow\"}}")
                .replace("{\"payload\":{\"value\":\"deny\"}}",
                        "{\"payload\":{\"aString\":\"ignore\",\"first\":\"Grace\",\"last\":\"Hopper\",\"formal\":false,\"value\":\"deny\"}}"));
        createCustomStep("Greeting", """
                public static String greet(String first, String last, boolean formal) {
                    return (formal ? "Hello " : "Hi ") + first + " " + last;
                }
                """);
        selectWorldNode("command");
        chooseVisibleExample("first-case");
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("Greeting");
        clickAndWaitForCreatorSave(() -> page.locator("[data-add-step='custom.greeting']").click());
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        for (final String parameter : List.of("first", "last", "formal"))
            assertThat(page.locator("[data-input-name='source_" + parameter + "'] .path-button")).hasCount(1);
        clickAndWaitForCreatorSave(() -> choosePath("source_first", "payload", "first"));
        waitForText("#build-state", "Built");
        clickAndWaitForCreatorSave(() -> choosePath("source_last", "payload", "last"));
        waitForText("#build-state", "Built");

        page.locator("[data-input-name='source_formal'] .path-button").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='payload']").click();
        assertThat(page.locator("[data-path-part='formal']")).hasCount(1);
        assertThat(page.locator("[data-path-part='first']")).hasCount(0);
        assertThat(page.locator("[data-path-part='last']")).hasCount(0);
        page.locator("#cancel-path").click();
        clickAndWaitForCreatorSave(() -> chooseCustomPathFor("returns-result", "greeting"));
        waitForText("#build-state", "Built");

        final var nodes = ((RailixValue.ArrayValue) CreatorServerE2eSupport.object(
                Files.readString(directory.resolve("project.json"))).values().get("nodes")).values();
        final var placed = nodes.stream().map(RailixValue.ObjectValue.class::cast)
                .filter(node -> RailixValue.string("custom.greeting").equals(node.values().get("use")))
                .findFirst().orElseThrow();
        org.assertj.core.api.Assertions.assertThat(placed.values().get("receives")).isEqualTo(CreatorServerE2eSupport.object("""
                {"first":["context","payload","first"],"last":["context","payload","last"],
                 "formal":["context","payload","formal"]}
                """));
        for (final var example : Map.of("first-case", "Hello Ada Lovelace", "second-case", "Hi Grace Hopper").entrySet()) {
            final var view = CreatorServerE2eSupport.successfulExampleView(creator.baseUri(), example.getKey());
            final var result = (RailixValue.ObjectValue) view.values().get("result");
            final var context = (RailixValue.ObjectValue) result.values().get("context");
            org.assertj.core.api.Assertions.assertThat(context.values().get("greeting")).isEqualTo(RailixValue.string(example.getValue()));
        }
        page.reload();
        waitForText("#build-state", "Built");
        selectWorldNode(((RailixValue.StringValue) placed.values().get("id")).value());
        openInspectorTab("inspect");
        assertThat(page.locator("[data-input-name='source_first'] .path-button")).containsText("first");
        assertThat(page.locator("[data-input-name='source_last'] .path-button")).containsText("last");
        assertThat(page.locator("[data-input-name='source_formal'] .path-button")).containsText("formal");
        final Path screenshots = Files.createDirectories(Path.of("target", "screenshots"));
        page.screenshot(new Page.ScreenshotOptions().setPath(
                screenshots.resolve("custom-step-multiple-inputs-" + page.viewportSize().width + ".png")));
        org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    @Tag("browser-diagnostics")
    void nestedHttpBranchUsesOnlyExamplesThatReachedTheStep() throws Exception {
        openProject(nestedHttpProject());
        createCustomStep("customLower");
        selectWorldNode("method");
        chooseVisibleExample("post-one");
        clickOverview("[data-add-outcome='post']");
        page.locator("#step-search").fill("customLower");
        assertThat(page.locator("[data-add-step='custom.customlower']")).hasCount(1);
        final Path screenshots = Files.createDirectories(Path.of("target", "screenshots"));
        page.screenshot(new Page.ScreenshotOptions().setPath(
                screenshots.resolve("custom-step-post-picker-" + page.viewportSize().width + ".png")));
        clickAndWaitForCreatorSave(() -> page.locator("[data-add-step='custom.customlower']").click());
        waitForText("#build-state", "Built");

        final var nodes = ((RailixValue.ArrayValue) CreatorServerE2eSupport.object(
                Files.readString(directory.resolve("project.json"))).values().get("nodes")).values();
        final var placed = nodes.stream().map(RailixValue.ObjectValue.class::cast)
                .filter(node -> RailixValue.string("custom.customlower").equals(node.values().get("use")))
                .findFirst().orElseThrow();
        final var source = RailixValue.array(List.of("context", "payload", "request", "body", "text")
                .stream().<RailixValue>map(RailixValue::string).toList());
        org.assertj.core.api.Assertions.assertThat(placed.values().get("receives"))
                .isEqualTo(RailixValue.object(Map.of("text", source)));
        org.assertj.core.api.Assertions.assertThat(placed.values().get("returns"))
                .isEqualTo(RailixValue.object(Map.of("result", source)));
        final int index = nodes.indexOf(placed);
        for (final var example : Map.of("post-one", "first", "post-two", "second").entrySet()) {
            CreatorServerE2eSupport.successfulExampleView(creator.baseUri(), example.getKey());
            final var response = CreatorServerE2eSupport.request(creator.baseUri(), "GET",
                    "/api/examples/" + example.getKey() + "/steps/" + index, "");
            org.assertj.core.api.Assertions.assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            final var invocation = CreatorServerE2eSupport.object(response.body());
            org.assertj.core.api.Assertions.assertThat(invocation.values().get("status")).isEqualTo(RailixValue.string("succeeded"));
            org.assertj.core.api.Assertions.assertThat(invocation.values().get("returns"))
                    .isEqualTo(RailixValue.object(Map.of("result", RailixValue.string(example.getValue()))));
        }
        // The source chooser must still exclude a field absent from one of the two visiting Examples.
        openInspectorTab("inspect");
        page.locator("[data-input-name='source'] .path-button").click();
        page.locator("[data-path-depth='0']").click();
        for (final String part : List.of("payload", "request", "body"))
            page.locator("[data-path-part='" + part + "']").click();
        assertThat(page.locator("[data-path-part='text']")).hasCount(1);
        assertThat(page.locator("[data-path-part='optional']")).hasCount(0);
        page.locator("#cancel-path").click();
        page.reload();
        waitForText("#build-state", "Built");
        selectWorldNode(((RailixValue.StringValue) placed.values().get("id")).value());
        assertThat(page.locator("[data-input-name='source']")).containsText("text");
        org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    @Tag("browser-diagnostics")
    void failedCatalogRefreshOffersRetryWithoutLosingTheSearch() throws Exception {
        openProject(choiceProject());
        final Page author = page;
        try (Page observer = context.newPage()) {
            observer.onPageError(pageErrors::add);
            observer.navigate(creator.baseUri().toString());
            page = observer;
            waitForText("#build-state", "Built");
            awaitScene();
            selectWorldNode("command");
            clickOverview("#add-next-step");
            page.locator("#step-search").fill("Shared");
            assertThat(page.locator("#step-options [role='status']")).hasCount(0);
            page = author;
            createCustomStep("Shared normalize");

            final var failedOnce = new AtomicBoolean();
            observer.route("**/api/catalog", route -> {
                if (failedOnce.compareAndSet(false, true)) {
                    route.fulfill(new Route.FulfillOptions().setStatus(503).setContentType("application/json")
                            .setBody("{\"message\":\"Temporarily unavailable\"}"));
                } else route.resume();
            });
            page = observer;
            final var failed = page.waitForResponse(response -> response.url().endsWith("/api/catalog"),
                    () -> observer.locator("#step-search").fill("Shared normalize"));
            org.assertj.core.api.Assertions.assertThat(failed.status()).isEqualTo(503);
            assertThat(page.locator("#step-options [role='alert']")).containsText("Step definitions could not be refreshed");
            assertThat(page.locator("#step-search")).hasValue("Shared normalize");
            assertThat(page.locator("#step-options")).not().containsText("No Step matches.");
            final Path screenshots = Files.createDirectories(Path.of("target", "screenshots"));
            page.screenshot(new Page.ScreenshotOptions().setPath(
                    screenshots.resolve("custom-step-catalog-retry-" + page.viewportSize().width + ".png")));
            page.locator("[data-retry-picker]").click();
            assertThat(page.locator("[data-add-step='custom.shared-normalize']")).hasCount(1);
            assertThat(page.locator("#step-options [role='alert']")).hasCount(0);
            assertThat(page.locator("#step-search")).hasValue("Shared normalize");
            clickAndWaitForCreatorSave(() -> page.locator("[data-add-step='custom.shared-normalize']").click());
            waitForText("#build-state", "Built");
            assertThat(page.locator("#inspector")).containsText("Shared normalize");
            org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
        } finally { page = author; }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Tag("responsive")
    @Tag("browser-diagnostics")
    void anotherOpenTabRefreshesCompiledDefinitionsBeforePlacementAndInspection(final boolean alreadyPlaced) throws Exception {
        openProject(choiceProject());
        final Page author = page;
        try (Page observer = context.newPage()) {
            observer.onPageError(pageErrors::add);
            observer.navigate(creator.baseUri().toString());
            page = observer;
            waitForText("#build-state", "Built");
            awaitScene();
            if (!alreadyPlaced) {
                selectWorldNode("command");
                clickOverview("#add-next-step");
                page.locator("#step-search").fill("Shared");
                assertThat(page.locator("#step-options [role='status']")).hasCount(0);
                assertThat(page.locator("[data-add-step='custom.shared-normalize']")).hasCount(0);
            }
            page = author;
            createCustomStep("Shared normalize");

            // Either tab can place the Step; the observer has never seen its definition.
            page = alreadyPlaced ? author : observer;
            if (alreadyPlaced) {
                selectWorldNode("command");
                clickOverview("#add-next-step");
            }
            page.locator("#step-search").fill("Shared normalize");
            assertThat(page.locator("[data-add-step='custom.shared-normalize']")).hasCount(1);
            clickAndWaitForCreatorSave(() -> page.locator("[data-add-step='custom.shared-normalize']").click());
            final String selected = page.locator("#inspector").getAttribute("data-selection");

            page = observer;
            selectWorldNode(selected);
            assertThat(page.locator("#inspector")).containsText("Shared normalize");
            org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
        } finally { page = author; }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Tag("browser-diagnostics")
    void aLateCatalogResponseCannotReplaceNewerDefinitions(final boolean deleteFirst) throws Exception {
        openProject(choiceProject());
        final Page author = page;
        try (Page observer = context.newPage()) {
            observer.onPageError(pageErrors::add);
            observer.navigate(creator.baseUri().toString());
            page = observer;
            waitForText("#build-state", "Built");
            selectWorldNode("command");
            clickOverview("#add-next-step");
            assertThat(page.locator("#step-options [role='status']")).hasCount(0);
            page = author;
            createCustomStep("First");

            final var captured = new AtomicBoolean();
            final var delayed = new AtomicReference<Route>();
            final var older = new AtomicReference<APIResponse>();
            observer.route("**/api/catalog", route -> {
                if (captured.compareAndSet(false, true)) {
                    older.set(route.fetch());
                    delayed.set(route);
                } else route.resume();
            });
            try {
                page = observer;
                page.locator("#step-search").fill(deleteFirst ? "First" : "Second");
                page.waitForCondition(() -> delayed.get() != null);
                org.assertj.core.api.Assertions.assertThat(CreatorServerE2eSupport.number(
                        CreatorServerE2eSupport.object(older.get().text()), "revision")).isEqualTo(1);
                page = author;
                if (deleteFirst) {
                    openCustomStepManager();
                    confirmCustomStepDeletion();
                    assertThat(page.locator("#custom-step-manager-status")).containsText("no custom Steps");
                    page.locator("#close-custom-step-manager").click();
                } else createCustomStep("Second");
                page = observer;
                // Group navigation has its own editor request; the picker's older request stays pending.
                final var newer = page.waitForResponse(response -> response.url().endsWith("/api/catalog"),
                        () -> page.locator("[data-inspector-mode='groups']").click());
                newer.finished();
                org.assertj.core.api.Assertions.assertThat(CreatorServerE2eSupport.number(
                        CreatorServerE2eSupport.object(newer.text()), "revision")).isEqualTo(2);
                final String selector = deleteFirst ? "[data-add-step='custom.first']" : "[data-add-step='custom.second']";
                assertThat(page.locator(selector)).hasCount(deleteFirst ? 0 : 1);
                assertThat(page.locator("#step-options [role='status']")).hasCount(1);

                final Route pending = delayed.getAndSet(null);
                final var late = page.waitForResponse(response -> response.url().endsWith("/api/catalog"),
                        () -> pending.fulfill(new Route.FulfillOptions().setResponse(older.get())));
                late.finished();
                assertThat(page.locator("#step-options [role='status']")).hasCount(0);
                assertThat(page.locator(selector)).hasCount(deleteFirst ? 0 : 1);
                org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
            } finally {
                observer.unroute("**/api/catalog");
                final Route pending = delayed.getAndSet(null);
                if (pending != null) pending.fulfill(new Route.FulfillOptions().setResponse(older.get()));
                if (older.get() != null) older.get().dispose();
            }
        } finally { page = author; }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Tag("responsive")
    @Tag("browser-diagnostics")
    void anotherTabDeletesASelectedDefinitionWithoutBreakingTheInspectorOrDiscardingDrafts(final boolean unsavedExample) throws Exception {
        openProject(choiceProject());
        createCustomStep("Normalize");
        selectWorldNode("command");
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("Normalize");
        clickAndWaitForCreatorSave(() -> page.locator("[data-add-step='custom.normalize']").click());
        final String placement = page.locator("#inspector").getAttribute("data-selection");
        final Page author = page;
        try (Page observer = context.newPage()) {
            observer.onPageError(pageErrors::add);
            observer.navigate(creator.baseUri().toString());
            page = observer;
            waitForText("#build-state", "Built");
            selectWorldNode(placement);
            assertThat(page.locator("#inspector")).containsText("Normalize");
            if (unsavedExample) {
                selectWorldNode("command");
                chooseExampleIndex(0);
                page.locator("#edit-example").click();
                page.locator("#example-payload").fill("[");
                page.locator("#example-payload").press("Tab");
                assertThat(page.locator("#inspector-content .issues article")
                        .filter(new Locator.FilterOptions().setHasText("PROJECT_TRIGGER_EXAMPLE_PAYLOAD_INVALID"))).isVisible();
            }

            page = author;
            selectWorldNode(placement);
            deleteAcceptedStep();
            openCustomStepManager();
            confirmCustomStepDeletion();
            assertThat(page.locator("#custom-step-manager-status")).containsText("no custom Steps");
            page.locator("#close-custom-step-manager").click();
            final String savedExamples = Files.readString(directory.resolve("railix.examples.json"));

            page = observer;
            openCustomStepManager();
            if (unsavedExample) {
                assertThat(page.locator("#custom-step-manager-status")).containsText("unsaved changes are retained");
                page.locator("#close-custom-step-manager").click();
                openInspectorTab("examples");
                assertThat(page.locator("#example-payload")).hasValue("[");
            } else {
                assertThat(page.locator("#custom-step-manager-status")).containsText("no custom Steps");
                page.locator("#close-custom-step-manager").click();
                assertThat(page.locator("#inspector")).hasAttribute("data-selection", "app");
                assertThat(page.locator("#editor-undo")).isEnabled();
                assertThat(page.locator("#editor-redo")).isDisabled();
                selectWorldNode("command");
                clickOverview("#add-next-step");
                page.locator("#step-search").fill("Normalize");
                assertThat(page.locator("[data-add-step='custom.normalize']")).hasCount(0);
            }
            org.assertj.core.api.Assertions.assertThat(Files.readString(directory.resolve("railix.examples.json"))).isEqualTo(savedExamples);
            org.assertj.core.api.Assertions.assertThat(Files.readString(directory.resolve("project.json"))).doesNotContain("custom.normalize");
            page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target", "screenshots"))
                    .resolve("custom-step-deleted-in-another-tab-" + unsavedExample + "-" + page.viewportSize().width + ".png")));
            org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
        } finally { page = author; }
    }

    @Test
    @Tag("responsive")
    @Tag("browser-diagnostics")
    void customStepAppearsOnlyAfterCompilationAndRunsThroughBranchingAndReuse() throws Exception {
        openProject(choiceProject().replace("\"name\":\"match\"", "\"id\":\"match-case\",\"name\":\"match\"")
                .replace("\"name\":\"otherwise\"", "\"id\":\"otherwise-case\",\"name\":\"otherwise\""));
        selectWorldNode("command");
        openInspectorTab("inspect");
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("Shout");
        assertThat(page.locator("[data-add-step='custom.shout']")).hasCount(0);
        page.locator(".construction-palette [data-create-custom-step]").click();
        assertThat(page.locator("#custom-step-dialog")).isVisible();
        page.locator("#custom-step-name").fill("Shout");
        final String broken = "public static String normalize(String text) {\n    return missing;\n}";
        page.locator("#custom-step-source").fill(broken);
        final var rejected = page.waitForResponse(response -> response.url().endsWith("/api/custom-steps")
                && response.request().method().equals("POST"), () -> page.locator("#compile-custom-step").click());
        org.assertj.core.api.Assertions.assertThat(rejected.status()).as(rejected.text()).isEqualTo(422);
        assertThat(page.locator("#custom-step-diagnostics")).containsText("Line 2");
        assertThat(page.locator("#custom-step-source")).hasValue(broken);
        assertThat(page.locator("[data-add-step='custom.shout']")).hasCount(0);
        assertThat(page.locator("#compile-custom-step")).isEnabled();

        final String reflective = "public static String normalize(String text) {\n    return String.class.getDeclaredMethods()[0].getName();\n}";
        page.locator("#custom-step-source").fill(reflective);
        final var denied = page.waitForResponse(response -> response.url().endsWith("/api/custom-steps")
                && response.request().method().equals("POST"), () -> page.locator("#compile-custom-step").click());
        org.assertj.core.api.Assertions.assertThat(denied.status()).as(denied.text()).isEqualTo(422);
        assertThat(page.locator("#custom-step-diagnostics")).containsText("Line 2");
        assertThat(page.locator("#custom-step-diagnostics")).containsText("Dynamic API");
        assertThat(page.locator("#custom-step-source")).hasValue(reflective);
        assertThat(page.locator("[data-add-step='custom.shout']")).hasCount(0);
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target", "screenshots"))
                .resolve("custom-step-dynamic-api-" + page.viewportSize().width + ".png")));

        page.locator("#custom-step-source").fill(CustomStepsE2eTest.METHOD.replace("toLowerCase", "toUpperCase"));
        final var compiled = page.waitForResponse(response -> response.url().endsWith("/api/custom-steps")
                && response.request().method().equals("POST"), () -> page.locator("#compile-custom-step").click());
        org.assertj.core.api.Assertions.assertThat(compiled.status()).as(compiled.text()).isEqualTo(200);
        assertThat(page.locator("#custom-step-status")).containsText("Compiled.");
        assertThat(page.locator("#compile-custom-step")).isDisabled();
        assertThat(page.locator("[data-add-step='custom.shout']")).hasCount(1);
        final int width = page.viewportSize().width;
        final Path screenshots = Files.createDirectories(Path.of("target", "screenshots"));
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("custom-step-compiled-" + width + ".png")));
        final var dialog = page.locator("#custom-step-dialog").boundingBox();
        org.assertj.core.api.Assertions.assertThat(dialog.x).isGreaterThanOrEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(dialog.x + dialog.width).isLessThanOrEqualTo(width);

        page.locator("#custom-step-source").fill(broken);
        assertThat(page.locator("#custom-step-status")).containsText("Not compiled");
        assertThat(page.locator("#compile-custom-step")).isEnabled();
        // Closing an edited draft must leave the previously compiled code in use.
        page.locator("#custom-step-source").press("Escape");
        assertThat(page.locator("#custom-step-dialog")).not().isVisible();
        assertThat(page.locator("#step-search")).isVisible();
        assertThat(page.locator(".construction-palette [data-create-custom-step]")).isFocused();
        clickAndWaitForCreatorSave(() -> page.locator("[data-add-step='custom.shout']").click());
        waitForText("#build-state", "Built");
        clickAndWaitForCreatorSave(() -> chooseCustomPathFor("returns-result", "shout"));
        waitForText("#build-state", "Built");
        final var nodes = (RailixValue.ArrayValue) CreatorServerE2eSupport.object(
                Files.readString(directory.resolve("project.json"))).values().get("nodes");
        final var first = nodes.values().stream().map(RailixValue.ObjectValue.class::cast)
                .filter(value -> RailixValue.string("custom.shout").equals(value.values().get("use"))).findFirst().orElseThrow();
        final String firstId = ((RailixValue.StringValue) first.values().get("id")).value();
        final var matched = (RailixValue.ObjectValue) CreatorServerE2eSupport.successfulExampleView(
                creator.baseUri(), "match-case").values().get("result");
        final var matchedContext = (RailixValue.ObjectValue) matched.values().get("context");
        org.assertj.core.api.Assertions.assertThat(matchedContext.values().get("shout")).isEqualTo(RailixValue.string("ALLOW"));
        org.assertj.core.api.Assertions.assertThat(matchedContext.values().get("result")).isEqualTo(RailixValue.string("matched"));

        selectWorldNode("otherwise");
        openInspectorTab("inspect");
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("Shout");
        page.locator("[data-reuse-item='" + firstId + "'][data-reuse-type='step']").click();
        clickAndWaitForCreatorSave(() -> page.locator("[data-apply-reuse='reuse']").click());
        waitForText("#build-state", "Built");
        final var completed = CreatorServerE2eSupport.successfulExampleView(creator.baseUri(), "otherwise-case");
        final var result = (RailixValue.ObjectValue) completed.values().get("result");
        org.assertj.core.api.Assertions.assertThat(result.values().get("status")).isEqualTo(RailixValue.string("succeeded"));
        final var context = (RailixValue.ObjectValue) result.values().get("context");
        org.assertj.core.api.Assertions.assertThat(context.values().get("shout")).isEqualTo(RailixValue.string("DENY"));
        org.assertj.core.api.Assertions.assertThat(context.values().get("result")).isEqualTo(RailixValue.string("otherwise"));
        final var savedNodes = (RailixValue.ArrayValue) CreatorServerE2eSupport.object(
                Files.readString(directory.resolve("project.json"))).values().get("nodes");
        final var shoutIndexes = IntStream.range(0, savedNodes.values().size())
                .filter(index -> RailixValue.string("custom.shout").equals(
                        ((RailixValue.ObjectValue) savedNodes.values().get(index)).values().get("use")))
                .boxed().toList();
        org.assertj.core.api.Assertions.assertThat(shoutIndexes).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(((RailixValue.ArrayValue) completed.values().get("nodes")).values())
                .containsAll(shoutIndexes.stream().map(RailixValue::number).toList());
        for (final int index : shoutIndexes) {
            final var response = CreatorServerE2eSupport.request(creator.baseUri(), "GET", "/api/examples/otherwise-case/steps/" + index, "");
            org.assertj.core.api.Assertions.assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            final var invocation = CreatorServerE2eSupport.object(response.body());
            org.assertj.core.api.Assertions.assertThat(invocation.values().get("status")).isEqualTo(RailixValue.string("succeeded"));
            org.assertj.core.api.Assertions.assertThat(CreatorServerE2eSupport.number(invocation, "visits")).isEqualTo(1);
            org.assertj.core.api.Assertions.assertThat(invocation.values().get("returns"))
                    .isEqualTo(RailixValue.object(Map.of("result", RailixValue.string("DENY"))));
        }
        org.assertj.core.api.Assertions.assertThat(Files.readString(directory.resolve("railix.creator.json"))).contains("\"reuse\"");
        page.reload();
        waitForText("#build-state", "Built");
        awaitScene();
        org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("custom-step-branches-" + width + ".png")));
    }

    private static String nestedHttpProject() {
        return """
                {"format":1,"id":"nested-http","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"request","use":"railix.trigger.http","inputs":{"target":["context","payload","request"]},"examples":[
                    {"id":"post-one","name":"first POST","payload":{"method":"POST","path":"/login","body":{"text":" FIRST ","optional":"one"}}},
                    {"id":"post-two","name":"second POST","payload":{"method":"POST","path":"/login","body":{"text":" SECOND "}}},
                    {"id":"get-login","name":"GET login","payload":{"method":"GET","path":"/login","body":{"text":" GET "}}},
                    {"id":"health","name":"health","payload":{"method":"GET","path":"/health","body":null}}
                  ]},
                  {"id":"route","use":"railix.branch","inputs":{"routes":[{"outcome":"login","inputs":{},"groups":[[
                    {"option":"field","inputs":{"field":["context","payload","request","path"]},
                     "when":{"transforms":[],"all":[[{"use":"value.equals","inputs":{"expected":"/login"}}]]}}
                  ]]}]}},
                  {"id":"method","use":"railix.branch","inputs":{"routes":[{"outcome":"post","inputs":{},"groups":[[
                    {"option":"field","inputs":{"field":["context","payload","request","method"]},
                     "when":{"transforms":[],"all":[[{"use":"value.equals","inputs":{"expected":"POST"}}]]}}
                  ]]}]}},
                  {"id":"accepted","use":"railix.field-manipulation","inputs":{"field":["context","body"],
                    "value":[{"option":"literal","inputs":{"literal":{"accepted":true}}}]}},
                  {"id":"fallback","use":"railix.field-manipulation","inputs":{"field":["context","body"],
                    "value":[{"option":"literal","inputs":{"literal":{"accepted":false}}}]}}
                ],"links":[
                  {"from":"app.start","to":"request"},{"from":"request.next","to":"route"},
                  {"from":"route.login","to":"method"},{"from":"route.otherwise","to":"fallback"},
                  {"from":"method.post","to":"accepted"},{"from":"method.otherwise","to":"fallback"},
                  {"from":"accepted.next","to":"end"},{"from":"fallback.next","to":"end"}
                ]}
                """;
    }

    private void createCustomStep(final String name) {
        createCustomStep(name, CustomStepsE2eTest.METHOD);
    }

    private void createCustomStep(final String name, final String source) {
        openCustomStepEditor();
        page.locator("#custom-step-name").fill(name);
        page.locator("#custom-step-source").fill(source);
        page.locator("#compile-custom-step").click();
        assertThat(page.locator("#custom-step-status")).containsText("Compiled.");
        page.locator("#close-custom-step").click();
    }

    private void openCustomStepEditor() {
        openProjectActions();
        page.locator("#project-actions [data-create-custom-step]").click();
        assertThat(page.locator("#custom-step-dialog")).isVisible();
    }

    private void openProjectActions() {
        if (page.locator("#inspector").isVisible() && !page.locator("#project-menu").isVisible())
            page.locator("#close-inspector").click();
        page.locator("#project-menu").click();
    }
}
