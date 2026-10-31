package dev.nanonative.railix.creator;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class RailixCreatorProjectImportBrowserIT extends RailixCreatorBrowserSupport {
    @Test
    void successfulReplacementDoesNotNeedTheUnusedResponseBody() throws Exception {
        openProject(fourStepProject());
        openImport();
        upload();
        final var replacements = new ArrayList<String>();
        page.route("**/api/project", route -> {
            if (!route.request().method().equals("POST")) { route.resume(); return; }
            replacements.add(route.request().postData());
            final var response = route.fetch();
            assertThat(response.status()).isEqualTo(200);
            route.fulfill(new Route.FulfillOptions().setResponse(response).setBody("{"));
        });
        try {
            page.locator("#project-import-submit").click();
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#project-import-panel")).isHidden();
            assertThat(page.title()).isEqualTo("uploaded-project - Railix Creator");
            openImport();
            assertThat(page.locator("#project-import-source").inputValue()).isEmpty();
            assertThat(replacements).hasSize(1);
            assertThat(pageErrors).isEmpty();
        } finally {
            page.unroute("**/api/project");
        }
    }

    @Test
    void homeCameraResizingDoesNotCloseANewImportPanel() {
        openProject(fourStepProject());
        final var held = new ArrayList<Route>();
        page.route("**/api/scene?**", route -> {
            if (route.request().url().contains("focus=app")) held.add(route); else route.resume();
        });
        try {
            page.locator("#zoom-fit").click();
            page.waitForCondition(() -> !held.isEmpty());
            openImport();
            final var response = page.waitForResponse(candidate -> candidate.url().contains("/api/scene?")
                    && candidate.status() == 200, () -> {
                page.unroute("**/api/scene?**");
                held.forEach(Route::resume);
                held.clear();
            });
            assertThat(response.status()).isEqualTo(200);
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#project-import-panel")).isVisible();
            assertThat(pageErrors).isEmpty();
        } finally {
            page.unroute("**/api/scene?**");
            held.forEach(Route::resume);
        }
    }

    @ParameterizedTest
    @Tag("responsive")
    @ValueSource(ints = {320, 390})
    void projectActionsAndRuntimeControlsRemainSeparateOnNarrowScreens(final int width) {
        page.setViewportSize(width, 844);
        openProject(CreatorEditorBrowserIT.attentionProject());
        page.locator("#issue-count").waitFor();
        final var controls = page.locator("#project-menu, .topbar > button, .build-status-controls > button").all();
        final var boxes = new ArrayList<com.microsoft.playwright.options.BoundingBox>();
        for (final var control : controls) {
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(control).isVisible();
            final var box = control.boundingBox();
            assertThat(box.x).isGreaterThanOrEqualTo(0);
            assertThat(box.x + box.width).isLessThanOrEqualTo(width);
            assertThat(box.width).isGreaterThanOrEqualTo(44);
            assertThat(box.height).isGreaterThanOrEqualTo(44);
            for (final var other : boxes) {
                assertThat(box.x >= other.x + other.width || box.x + box.width <= other.x
                        || box.y >= other.y + other.height || box.y + box.height <= other.y).isTrue();
            }
            boxes.add(box);
        }
        openImport();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#project-import-file")).isVisible();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void projectImportKeepsItsDraftAcrossNavigationWithoutReplacingAnything() throws Exception {
        openProject(fourStepProject());
        final String before = Files.readString(directory.resolve("project.json"));
        assertThat(page.locator("#project-menu").isVisible()).isTrue();
        openImport();
        page.locator("#project-import-paste summary").click();
        page.locator("#project-import-source").fill("{unfinished");
        page.locator("#project-import-source").press("Tab");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#project-import-source"))
                .hasAttribute("aria-invalid", "true");
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Close import").setExact(true)).click();
        selectWorldNode("one");
        openImport();
        assertThat(page.locator("#project-import-source").inputValue()).isEqualTo("{unfinished");
        assertThat(page.locator("#project-import-submit").isDisabled()).isTrue();
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(before);
        assertThat(page.locator("#application-status #project-import-file").count()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void replacementShowsItsTargetAndRefreshesTheWholeEditor() throws Exception {
        openProject(fourStepProject());
        selectWorldNode("app");
        page.locator("#project-id").fill("before-import");
        page.locator("#project-id").press("Tab");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#editor-undo")).isEnabled();
        openImport();
        upload();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#project-import-preview"))
                .containsText("uploaded-project");
        assertThat(page.locator("#project-import-target").textContent()).contains("before-import", "project.json");
        assertThat(page.locator("#project-import-submit").textContent()).contains("Replace project");
        assertThat(Files.readString(directory.resolve("project.json"))).contains("before-import");
        final var response = page.waitForResponse(candidate -> candidate.url().endsWith("/api/project")
                && candidate.request().method().equals("POST"), () -> page.locator("#project-import-submit").click());
        assertThat(response.status()).isEqualTo(200);
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#project-import-panel")).isHidden();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#editor-undo")).isDisabled();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#graph")).isFocused();
        assertThat(page.title()).isEqualTo("uploaded-project - Railix Creator");
        assertThat(page.locator("#flow-count").textContent()).isEqualTo("1 flow");
        assertThat(page.locator("#step-count").textContent()).isEqualTo("4 steps");
        assertThat(Files.readString(directory.resolve("project.json"))).contains("uploaded-project");
        page.locator(".build-indicator").click();
        assertThat(page.locator("#application-details").isVisible()).isTrue();
        assertThat(page.locator("#runtime-summary").count()).isEqualTo(1);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void replacementIsExclusiveWhileTheRealRequestIsPending() throws Exception {
        openProject(fourStepProject());
        selectWorldNode("one");
        openImport();
        upload();
        final List<Route> pending = new ArrayList<>();
        final List<String> edits = new ArrayList<>();
        page.onRequest(request -> {
            if (request.method().equals("POST") && request.url().endsWith("/api/editor")) edits.add(request.postData());
        });
        page.route("**/api/project", route -> {
            if (route.request().method().equals("POST")) pending.add(route);
            else route.resume();
        });
        try {
            page.locator("#project-import-submit").click();
            page.waitForCondition(() -> !pending.isEmpty());
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#inspector"))
                    .hasAttribute("inert", "");
            assertThat(page.locator("#project-menu").isDisabled()).isTrue();
            assertThat(page.locator("#editor-undo").isDisabled()).isTrue();
            page.keyboard().press("ControlOrMeta+z");
            assertThat(edits).isEmpty();
            pending.removeFirst().resume();
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#project-import-panel")).isHidden();
            assertThat(Files.readString(directory.resolve("project.json"))).contains("uploaded-project");
            assertThat(pageErrors).isEmpty();
        } finally {
            page.unroute("**/api/project");
            pending.forEach(Route::resume);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"mutation", "refresh", "unsubmitted"})
    void lostReplacementResponseCanRefreshWithoutReplacingTwice(final String boundary) throws Exception {
        openProject(fourStepProject());
        openImport();
        upload();
        final List<String> replacements = new ArrayList<>();
        final var loseRead = new java.util.concurrent.atomic.AtomicBoolean(boundary.equals("refresh"));
        page.route("**/api/project", route -> {
            if (!route.request().method().equals("POST")) { route.resume(); return; }
            replacements.add(route.request().postData());
            if (boundary.equals("unsubmitted")) route.abort();
            else if (boundary.equals("mutation")) {
                assertThat(route.fetch().status()).isEqualTo(200);
                route.abort();
            } else route.resume();
        });
        page.route("**/api/editor", route -> {
            if (route.request().method().equals("GET") && loseRead.compareAndSet(true, false)) route.abort();
            else route.resume();
        });
        try {
            page.locator("#project-import-submit").click();
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#project-import-reload")).isVisible();
            assertThat(page.locator("#project-import-submit").isDisabled()).isTrue();
            if (boundary.equals("refresh")) assertThat(page.locator("#project-import-source").inputValue()).isEmpty();
            else assertThat(page.locator("#project-import-source").inputValue()).contains("uploaded-project");
            page.locator("#close-project-import").click();
            openImport();
            page.locator("#project-import-reload").click();
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#project-import-panel")).isHidden();
            assertThat(page.title()).isEqualTo(boundary.equals("unsubmitted")
                    ? "four-steps - Railix Creator" : "uploaded-project - Railix Creator");
            assertThat(replacements).hasSize(1);
            openImport();
            if (boundary.equals("refresh")) {
                assertThat(page.locator("#project-import-source").inputValue()).isEmpty();
                com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#project-import-submit")).isDisabled();
            } else {
                assertThat(page.locator("#project-import-source").inputValue()).contains("uploaded-project");
                com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#project-import-submit")).isEnabled();
            }
            assertThat(pageErrors).isEmpty();
        } finally {
            page.unroute("**/api/project");
            page.unroute("**/api/editor");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"oversized", "utf8", "syntax"})
    void unreadableFilesNeverBecomeReplacementCandidates(final String failure) throws Exception {
        openProject(fourStepProject());
        final String before = Files.readString(directory.resolve("project.json"));
        openImport();
        final Path file = directory.resolve("invalid.json");
        if (failure.equals("oversized")) Files.write(file,
                new byte[dev.nanonative.railix.core.value.RailixData.DEFAULT_MAX_SOURCE_BYTES + 1]);
        else if (failure.equals("utf8")) Files.write(file, new byte[]{'{', '"', (byte) 255, '"', ':', '1', '}'});
        else Files.writeString(file, "{unfinished");
        page.locator("#project-import-file").setInputFiles(file);
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#project-import-file"))
                .hasAttribute("aria-invalid", "true");
        assertThat(page.locator("#project-import-submit").isDisabled()).isTrue();
        assertThat(page.locator("#project-import-preview").isHidden()).isTrue();
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(before);
        upload();
        assertThat(page.locator("#project-import-file").getAttribute("aria-invalid")).isNull();
        page.locator("#project-import-clear").click();
        assertThat(page.locator("#project-import-source").inputValue()).isEmpty();
        assertThat(page.locator("#project-import-file").inputValue()).isEmpty();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void rejectedReplacementKeepsTheCurrentApplicationAndItsDraft() throws Exception {
        openProject(fourStepProject());
        final String before = Files.readString(directory.resolve("project.json"));
        final String pid = applicationPid();
        openImport();
        page.locator("#project-import-paste summary").click();
        final String rejected = """
                {"format":1,"id":"unknown-step","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"unknown","use":"external.not-installed","inputs":{}}],
                  "links":[{"from":"app.start","to":"unknown"}]}
                """;
        page.locator("#project-import-source").fill(rejected);
        page.locator("#project-import-source").press("Tab");
        final var response = page.waitForResponse(candidate -> candidate.url().endsWith("/api/project")
                && candidate.request().method().equals("POST"), () -> page.locator("#project-import-submit").click());
        assertThat(response.status()).isEqualTo(422);
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#project-import-status"))
                .hasAttribute("data-severity", "error");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#project-import-submit")).isEnabled();
        assertThat(page.locator("#project-import-source").inputValue()).isEqualTo(rejected);
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(before);
        assertThat(applicationPid()).isEqualTo(pid);
        assertThat(page.locator("#build-state").textContent()).isEqualTo("Running");
        assertThat(pageErrors).isEmpty();
    }

    private void openImport() {
        page.locator("#project-menu").click();
        page.locator("#open-project-import").click();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#project-import-panel")).isVisible();
    }

    private void upload() throws Exception {
        final String source = CreatorProjects.previewBoundary()
                .replace("\"id\":\"preview-boundary\"", "\"id\":\"uploaded-project\"");
        final Path file = directory.resolve("uploaded-railix.project.json");
        Files.writeString(file, source);
        page.locator("#project-import-file").setInputFiles(file);
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#project-import-submit")).isEnabled();
    }
}
