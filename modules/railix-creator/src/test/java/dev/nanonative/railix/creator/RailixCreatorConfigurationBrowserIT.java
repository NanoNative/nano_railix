package dev.nanonative.railix.creator;

import com.microsoft.playwright.Page;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

final class RailixCreatorConfigurationBrowserIT extends RailixCreatorBrowserSupport {
    @ParameterizedTest
    @ValueSource(strings = {"hq", "canvas"})
    @Tag("responsive")
    @Tag("browser-diagnostics")
    void configurationCanBeAuthoredBoundExecutedAndReopened(final String renderer) throws Exception {
        openProject(CreatorConfigurationE2eTest.literalProject());
        page.locator("#open-settings").click();
        page.locator("#theme-variant").selectOption(renderer);
        page.waitForFunction("() => !state.settingsWriting && !state.settingsTimer");
        page.locator("#settings-configuration-tab").click();
        addValue("title", "string", "HELLO_CONFIG");
        addValue("account_code", "string", "00123");
        addValue("precise", "number", "9007199254740993");
        addValue("off", "boolean", "false");
        addValue("zero", "number", "0");
        addValue("empty", "string", "");
        page.locator("#configuration-apply").click();
        waitForText("#build-state", "Built");
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target", "screenshots"))
                .resolve("configuration-" + renderer + "-" + page.viewportSize().width + ".png")));
        page.locator("[aria-label='Close Settings']").click();
        selectWorldNode("result");
        page.locator("[data-input-source]").selectOption("precise");
        waitForText("#build-state", "Built");
        openInspectorTab("results");
        page.waitForFunction("() => document.querySelector('#example-results-values')?.textContent.includes('9007199254740993')");
        openInspectorTab("inspect");
        page.locator("[data-input-source]").selectOption("title");
        waitForText("#build-state", "Built");
        openInspectorTab("results");
        page.waitForFunction("() => document.querySelector('#example-results-values')?.textContent.includes('HELLO_CONFIG')");
        final String source = Files.readString(directory.resolve("project.json"));
        assertThat(source).contains("9007199254740993", "\"account_code\":\"00123\"", "\"off\":false", "\"zero\":0", "\"empty\":\"\"",
                "\"input_sources\":{\"literal\":{\"configuration\":\"title\"}}");
        page.reload();
        waitForText("#build-state", "Built");
        selectWorldNode("result");
        assertThat(page.locator("[data-input-source]").inputValue()).isEqualTo("title");
        assertThat(page.locator(".configuration-reference-value").textContent()).contains("HELLO_CONFIG");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void incompleteAndDuplicateDraftRowsNeverOverwriteTheProject() throws Exception {
        openProject(CreatorConfigurationE2eTest.literalProject());
        final String before = Files.readString(directory.resolve("project.json"));
        page.locator("#open-settings").click();
        page.locator("#settings-configuration-tab").click();
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
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void deletingAnInUseKeyRetainsTheRejectedDraftAndRunningApplication() throws Exception {
        openProject(CreatorConfigurationE2eTest.configuredProject("\"RAILIX\""));
        final String before = Files.readString(directory.resolve("project.json"));
        final String pid = applicationPid();
        page.locator("#open-settings").click();
        page.locator("#settings-configuration-tab").click();
        page.locator("[data-configuration-remove]").click();
        page.locator("#configuration-apply").click();
        waitForText("#build-state", "Not built");
        assertThat(page.locator("#configuration-status").textContent()).contains("Configuration key does not exist");
        assertThat(page.locator(".configuration-row").count()).isZero();
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(before);
        assertThat(applicationPid()).isEqualTo(pid);
        page.locator("#configuration-reset").click();
        waitForText("#build-state", "Built");
        assertThat(page.locator(".configuration-row").count()).isEqualTo(1);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void characterRulesRejectInvalidDraftsAndPreserveEscapedValues() throws Exception {
        openProject(CreatorConfigurationE2eTest.literalProject());
        final String before = Files.readString(directory.resolve("project.json"));
        page.locator("#open-settings").click();
        page.locator("#settings-configuration-tab").click();
        addValue("valid", "string", "RAILIX");
        final var row = page.locator(".configuration-row").first();
        for (final String key : List.of("bad name", "bad.name", "bad@name", "bad/name", "bad\\name", "bad\nname", "bad\tname")) {
            row.locator("[data-configuration-field=key]").fill(key);
            page.locator("#configuration-apply").click();
            assertThat(page.locator("#configuration-status").textContent()).contains("Configuration names must");
            assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(before);
        }
        row.locator("[data-configuration-field=key]").fill("09CONFIG_name-test");
        for (final String value : List.of("has space", " leading", "trailing ", "line\nvalue", "return\rvalue", "tab\tvalue")) {
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
        waitForText("#build-state", "Built");
        page.locator("[aria-label='Close Settings']").click();
        selectWorldNode("result");
        page.locator("[data-input-source]").selectOption("09CONFIG_name-test");
        waitForText("#build-state", "Built");
        openInspectorTab("results");
        page.waitForFunction("() => document.querySelector('#example-results-values')?.textContent.includes('https://Aa09-_.@:/')");
        assertThat(Files.readString(directory.resolve("project.json"))).contains(RailixJson.write(RailixValue.string(value)));
        page.reload();
        waitForText("#build-state", "Built");
        page.locator("#open-settings").click();
        page.locator("#settings-configuration-tab").click();
        assertThat(page.locator("[data-configuration-field=value]").inputValue()).isEqualTo(value);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void changingComparisonKeepsItsConfiguredOperand() throws Exception {
        openProject(configuredPredicateProject("value.equals", "expected", "\"RAILIX\"", "\"RAILIX\""));
        selectWorldNode("result");
        for (final String use : List.of("value.not-equals", "value.equals")) {
            final var response = page.waitForResponse(reply -> reply.url().endsWith("/api/project")
                            && reply.request().method().equals("PATCH"),
                    () -> page.locator("[data-replace-comparison]").selectOption(use));
            assertThat(response.status()).as(response.text()).isEqualTo(200);
            waitForText("#build-state", "Built");
            assertThat(page.locator(".condition-predicate [data-input-source]").inputValue()).isEqualTo("operand");
            assertThat(RailixJson.write(savedCandidate())).contains("\"use\":\"" + use + "\"",
                            "\"input_sources\":{\"expected\":{\"configuration\":\"operand\"}}")
                    .doesNotContain("\"expected\":null");
            final var context = executeSavedProject(use, "{}");
            if (use.equals("value.equals")) assertThat(context.values()).containsEntry("output", RailixValue.string("RAILIX"));
            else assertThat(context.values()).doesNotContainKey("output");
            page.reload();
            waitForText("#build-state", "Built");
            selectWorldNode("result");
            assertThat(page.locator("[data-replace-comparison]").inputValue()).isEqualTo(use);
            assertThat(page.locator(".condition-predicate [data-input-source]").inputValue()).isEqualTo("operand");
        }
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void changingComparisonDropsIncompatibleBindings() throws Exception {
        openProject(configuredPredicateProject("number.greater-than", "than", "10", "5"));
        selectWorldNode("result");
        final var response = page.waitForResponse(reply -> reply.url().endsWith("/api/project")
                        && reply.request().method().equals("PATCH"),
                () -> page.locator("[data-replace-comparison]").selectOption("value.equals"));
        assertThat(response.status()).as(response.text()).isEqualTo(200);
        waitForText("#build-state", "Built");
        assertThat(page.locator(".condition-predicate [data-input-source]").inputValue()).isEmpty();
        assertThat(RailixJson.write(savedCandidate())).contains("\"expected\":null", "\"use\":\"value.equals\"",
                        "\"input_sources\":{\"literal\":{\"configuration\":\"value\"}}")
                .doesNotContain("\"configuration\":\"operand\"", "\"than\"");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"current", "field"})
    @Tag("responsive")
    void changingCandidateSourceDropsOnlyItsOwnedBindings(final String option) throws Exception {
        openProject(configuredPredicateProject("value.not-equals", "expected", "\"RAILIX\"", "\"BLOCKED\""));
        selectWorldNode("result");
        final var response = page.waitForResponse(reply -> reply.url().endsWith("/api/project")
                        && reply.request().method().equals("PATCH"),
                () -> page.locator("#value-0-option").selectOption(option));
        assertThat(response.status()).as(response.text()).isEqualTo(200);
        waitForText("#build-state", "Built");
        final var candidate = savedCandidate();
        assertThat(candidate.values()).doesNotContainKey("input_sources").containsEntry("option", RailixValue.string(option));
        assertThat(RailixJson.write(candidate.values().get("when")))
                .contains("\"input_sources\":{\"expected\":{\"configuration\":\"operand\"}}");
        final var context = executeSavedProject(option, "{\"output\":\"ORIGINAL\",\"payload\":[\"NEW\"]}");
        final RailixValue expected = option.equals("current") ? RailixValue.string("ORIGINAL")
                : RailixValue.array(List.of(RailixValue.string("NEW")));
        assertThat(context.values()).containsEntry("output", expected);
        page.reload();
        waitForText("#build-state", "Built");
        selectWorldNode("result");
        assertThat(page.locator("#value-0-option").inputValue()).isEqualTo(option);
        assertThat(page.locator(".condition-predicate [data-input-source]").inputValue()).isEqualTo("operand");
        final var returned = page.waitForResponse(reply -> reply.url().endsWith("/api/project")
                        && reply.request().method().equals("PATCH"),
                () -> page.locator("#value-0-option").selectOption("literal"));
        assertThat(returned.status()).as(returned.text()).isEqualTo(200);
        waitForText("#build-state", "Built");
        assertThat(savedCandidate().values()).doesNotContainKey("input_sources");
        assertThat(RailixJson.write(savedCandidate().values().get("inputs"))).isEqualTo("{\"literal\":null}");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"value", "type", "remove"})
    @Tag("responsive")
    void savedConfigurationRowsKeepTheirIdentityAfterReload(final String edit) throws Exception {
        openProject(CreatorConfigurationE2eTest.literalProject());
        page.locator("#open-settings").click();
        page.locator("#settings-configuration-tab").click();
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
        final var saved = page.waitForResponse(response -> response.url().endsWith("/api/project")
                        && response.request().method().equals("PATCH"),
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
    @Tag("responsive")
    void importingAProjectClearsConfigurationDraftsAndMessages() throws Exception {
        openProject(CreatorConfigurationE2eTest.configuredProject("\"OLD\""));
        page.locator("#open-settings").click();
        page.locator("#settings-configuration-tab").click();
        page.locator("[data-configuration-field=value]").fill("INVALID VALUE");
        page.locator("#configuration-apply").click();
        assertThat(page.locator("#configuration-status").textContent()).contains("String values must");
        page.locator("[aria-label='Close Settings']").click();
        page.locator(".build-indicator").click();
        final String imported = CreatorConfigurationE2eTest.configuredProject("\"NEW\"")
                .replace("\"id\":\"configured-project\"", "\"id\":\"imported-project\"");
        page.locator("#project-import-source").fill(imported);
        page.locator("#project-import-submit").click();
        waitForText("#project-import-status", "Imported and built.");
        page.keyboard().press("Escape");
        page.locator("#open-settings").click();
        page.locator("#settings-configuration-tab").click();
        assertThat(page.locator("[data-configuration-field=value]").inputValue()).isEqualTo("NEW");
        assertThat(page.locator("#configuration-status").textContent()).isEmpty();
        page.locator("[data-configuration-field=value]").fill("EDITED");
        final var saved = page.waitForResponse(response -> response.url().endsWith("/api/project")
                        && response.request().method().equals("PATCH"),
                () -> page.locator("#configuration-apply").click());
        assertThat(saved.status()).as(saved.text()).isEqualTo(200);
        assertThat(savedConfiguration().values()).containsExactlyEntriesOf(Map.of("value", RailixValue.string("EDITED")));
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
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

    private RailixValue.ObjectValue savedConfiguration() throws Exception {
        final var project = (RailixValue.ObjectValue) ((RailixJson.Parsed)
                RailixJson.parse(Files.readString(directory.resolve("project.json")))).value();
        return (RailixValue.ObjectValue) project.values().get("configuration");
    }

    private RailixValue.ObjectValue savedCandidate() throws Exception {
        final var project = (RailixValue.ObjectValue) ((RailixJson.Parsed)
                RailixJson.parse(Files.readString(directory.resolve("project.json")))).value();
        final var nodes = (RailixValue.ArrayValue) project.values().get("nodes");
        final var result = nodes.values().stream().map(RailixValue.ObjectValue.class::cast)
                .filter(node -> RailixValue.string("result").equals(node.values().get("id"))).findFirst().orElseThrow();
        final var inputs = (RailixValue.ObjectValue) result.values().get("inputs");
        return (RailixValue.ObjectValue) ((RailixValue.ArrayValue) inputs.values().get("value")).values().getFirst();
    }

    private RailixValue.ObjectValue executeSavedProject(final String name, final String context) throws Exception {
        try (var application = GeneratedApplicationFixture.start(directory.resolve(name), Files.readString(directory.resolve("project.json")))) {
            final var response = application.run("command", context);
            assertThat(response.status()).as(response.body()).isEqualTo(200);
            final var result = (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(response.body())).value();
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
