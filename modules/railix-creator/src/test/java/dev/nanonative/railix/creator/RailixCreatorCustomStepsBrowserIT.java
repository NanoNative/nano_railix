package dev.nanonative.railix.creator;

import com.microsoft.playwright.APIResponse;
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

    @Test
    @Tag("browser-diagnostics")
    void aLateCatalogResponseCannotReplaceNewerDefinitions() throws Exception {
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
                page.locator("#step-search").fill("Second");
                page.waitForCondition(() -> delayed.get() != null);
                org.assertj.core.api.Assertions.assertThat(CreatorServerE2eSupport.number(
                        CreatorServerE2eSupport.object(older.get().text()), "revision")).isEqualTo(1);
                page = author;
                createCustomStep("Second");
                page = observer;
                // Group navigation has its own editor request; the picker's older request stays pending.
                final var newer = page.waitForResponse(response -> response.url().endsWith("/api/catalog"),
                        () -> page.locator("[data-inspector-mode='groups']").click());
                newer.finished();
                org.assertj.core.api.Assertions.assertThat(CreatorServerE2eSupport.number(
                        CreatorServerE2eSupport.object(newer.text()), "revision")).isEqualTo(2);
                assertThat(page.locator("[data-add-step='custom.second']")).hasCount(1);
                assertThat(page.locator("#step-options [role='status']")).hasCount(1);

                final Route pending = delayed.getAndSet(null);
                final var late = page.waitForResponse(response -> response.url().endsWith("/api/catalog"),
                        () -> pending.fulfill(new Route.FulfillOptions().setResponse(older.get())));
                late.finished();
                assertThat(page.locator("#step-options [role='status']")).hasCount(0);
                assertThat(page.locator("[data-add-step='custom.second']")).hasCount(1);
                org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
            } finally {
                observer.unroute("**/api/catalog");
                final Route pending = delayed.getAndSet(null);
                if (pending != null) pending.fulfill(new Route.FulfillOptions().setResponse(older.get()));
                if (older.get() != null) older.get().dispose();
            }
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
        page.locator("#project-menu").click();
        page.locator("#project-actions [data-create-custom-step]").click();
        page.locator("#custom-step-name").fill(name);
        page.locator("#custom-step-source").fill(source);
        page.locator("#compile-custom-step").click();
        assertThat(page.locator("#custom-step-status")).containsText("Compiled.");
        page.locator("#close-custom-step").click();
    }
}
