package dev.nanonative.railix.creator;

import com.microsoft.playwright.Page;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

final class RailixCreatorTransformBrowserIT extends RailixCreatorBrowserSupport {
    @Test
    @Tag("responsive")
    @Tag("browser-diagnostics")
    @Timeout(180)
    void authorMixedTypeNormalizationWithFallbackAndReopenIt() throws Exception {
        openProject(RailixCreatorDataFlowBrowserIT.routedRequests("{\"email\":34}")
                .replace("email", "quantity").replace("FIRST@EXAMPLE.COM", "12"));
        selectWorldNode("auth-entry");
        chooseVisibleExample("http:0");
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("transform");
        page.locator("[data-add-step='railix.transform']").click();
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        final String inserted = page.locator("#inspector").getAttribute("data-selection");
        choosePath("source", "request", "body", "quantity");
        waitForText("#build-state", "Built");
        page.locator("[data-input-name='convert_to'] > select").selectOption("integer");
        waitForText("#build-state", "Built");
        chooseCustomPathFor("target", "quantity");
        waitForText("#build-state", "Built");
        page.locator("[data-input-name='fallback'] input[type='checkbox']").check();
        waitForText("#build-state", "Built");
        final var fallback = page.locator("[data-input-name='fallback'] input[type='number']");
        fallback.fill("0");
        fallback.press("Tab");
        waitForText("#build-state", "Built");
        assertThat(page.locator("[data-input-name='source'] .path-values")).containsText("12");
        assertThat(page.locator("[data-input-name='target'] .path-values")).containsText("12");
        page.locator("#inspector-content").evaluate("element => element.scrollTop = 0");
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target", "screenshots"))
                .resolve("transform-integer-" + page.viewportSize().width + ".png")));

        org.assertj.core.api.Assertions.assertThat(caseContext(0).values().get("quantity")).isEqualTo(RailixValue.number(12));
        org.assertj.core.api.Assertions.assertThat(caseContext(1).values().get("quantity")).isEqualTo(RailixValue.number(34));
        org.assertj.core.api.Assertions.assertThat(caseContext(2).values().get("quantity")).isEqualTo(RailixValue.number(0));
        org.assertj.core.api.Assertions.assertThat(caseContext(4).values()).doesNotContainKey("quantity");

        page.reload();
        waitForText("#build-state", "Built");
        selectWorldNode(inserted);
        assertThat(page.locator("[data-input-name='convert_to'] > select")).hasValue("integer");
        assertThat(page.locator("[data-input-name='fallback'] input[type='number']")).hasValue("0");
        assertThat(page.locator("[data-input-name='source'] .path-button")).containsText("quantity");
        chooseVisibleExample("http:2");
        assertThat(page.locator("[data-input-name='target'] .path-values")).containsText("0");
        page.locator("[data-input-name='fallback'] input[type='checkbox']").uncheck();
        waitForText("#build-state", "Built");
        org.assertj.core.api.Assertions.assertThat(caseContext(2).values()).doesNotContainKey("quantity");
        selectWorldNode(inserted);
        chooseVisibleExample("http:0");
        assertThat(page.locator("[data-input-name='target'] .path-values")).containsText("12");
        org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
        org.assertj.core.api.Assertions.assertThat((Boolean) page.evaluate(
                "document.documentElement.scrollWidth <= document.documentElement.clientWidth")).isTrue();
    }

    @Test
    @Tag("browser-diagnostics")
    @Timeout(120)
    void typedFallbackRejectsFractionAndChangingTargetDoesNotReuseIt() {
        openProject(TransformGeneratedE2eTest.cliProject("integer", "0"));
        selectWorldNode("convert");
        chooseVisibleExample("command:0");
        openInspectorTab("inspect");
        final var fallback = page.locator("[data-input-name='fallback'] input[type='number']");
        fallback.fill("0.5");
        fallback.press("Tab");
        assertThat(page.locator("[data-input-name='fallback'] .input-error")).containsText("whole number");
        fallback.fill("0");
        fallback.press("Tab");
        waitForText("#build-state", "Built");
        page.locator("[data-input-name='convert_to'] > select").selectOption("text");
        waitForText("#build-state", "Built");
        assertThat(page.locator("[data-input-name='fallback'] input[type='checkbox']")).not().isChecked();
        assertThat(page.locator("[data-input-name='fallback'] input[type='number']")).hasCount(0);
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
}
