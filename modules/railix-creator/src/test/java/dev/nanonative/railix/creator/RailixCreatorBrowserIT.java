package dev.nanonative.railix.creator;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Execution(ExecutionMode.SAME_THREAD)
@Timeout(40)
final class RailixCreatorBrowserIT {
    private static final int VIEWPORT_WIDTH = Integer.getInteger("railix.browser.viewport.width", 1_280);
    private static Playwright playwright;
    private static Browser browser;

    @TempDir
    Path directory;

    private CreatorServer creator;
    private BrowserContext context;
    private Page page;
    private final List<String> pageErrors = new ArrayList<>();

    @BeforeAll
    static void startBrowser() {
        playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of(
                "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD",
                "1"
        )));
        browser = launchBrowser();
    }

    private static Browser launchBrowser() {
        return playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setChannel(System.getenv().getOrDefault("RAILIX_BROWSER_CHANNEL", "chrome"))
                .setHeadless(true));
    }

    @AfterAll
    static void stopBrowser() {
        if (playwright != null) {
            playwright.close();
        }
    }

    @BeforeEach
    void openCreator() throws Exception {
        Files.createDirectories(directory.resolve("railix-home/icons"));
        Files.writeString(directory.resolve("railix-home/icons/bolt.svg"), "<svg/>");
        creator = CreatorServer.start(0, directory.resolve("project.json"), directory.resolve("railix-home"));
        context = browser.newContext(new Browser.NewContextOptions().setViewportSize(
                VIEWPORT_WIDTH,
                VIEWPORT_WIDTH <= 560 ? 720 : 800
        ));
        page = context.newPage();
        page.onPageError(error -> pageErrors.add(error));
        page.navigate(creator.baseUri().toString());
        waitForText("#build-state", "Built");
    }

    @AfterEach
    void closeCreator() {
        try {
            if (context != null) {
                context.close();
            }
        } finally {
            if (creator != null) {
                creator.close();
            }
        }
    }

    @Test
    void newProjectStartsWithOnePermanentCenteredApp() {
        assertThat(page.locator(".graph-stage").textContent())
                .contains("Application")
                .doesNotContain("Add Trigger")
                .doesNotContain("CLI Trigger", "Field Manipulation");
        assertThat(page.locator(".app-node").count()).isEqualTo(1);
        assertThat(page.locator(".graph-stage .node button").count()).isZero();
        assertThat(page.locator("#inspector").textContent()).contains("Add Trigger");
        assertThat(page.locator("#delete-step").count()).isZero();
    }

    @Test
    void appInspectorShowsTruthfulWorkspaceFacts() {
        assertThat(page.locator("#inspector").textContent()).contains(
                directory.resolve("project.json").toAbsolutePath().normalize().toString(),
                "0 flows",
                "1 step",
                "Last build"
        );
    }

    @Test
    void appInspectorShowsTheRunningBuildPathAndPid() {
        assertThat(page.locator("#build-path").textContent()).isNotBlank();
        assertThat(page.locator("#application-pid").textContent()).matches("[1-9][0-9]*");
    }

    @Test
    void enterSelectsTheApplicationNode() {
        addTrigger();

        page.locator(".app-node").press("Enter");

        assertThat(page.locator(".inspector-heading h2").textContent()).isEqualTo("Application");
    }

    @Test
    void spaceSelectsATriggerNode() {
        addTrigger();
        final String flowName = page.locator(".trigger-node h2").textContent();
        page.locator(".app-node").click();

        page.locator(".trigger-node").press("Space");

        assertThat(page.locator(".inspector-heading h2").textContent()).isEqualTo(flowName);
    }

    @Test
    void rollingBuildCompletionPreservesFocusedGraphNode() {
        delayNextProjectWrite();
        addTrigger();
        page.locator(".app-node").click();
        page.locator(".trigger-node").focus();
        page.waitForFunction("() => window.__railixProjectWriteStarted === true");

        page.evaluate("() => window.__railixReleaseProjectWrite()");
        waitForText("#build-state", "Built");

        assertThat(page.locator(".trigger-node").evaluate("node => node === document.activeElement"))
                .isEqualTo(true);
    }

    @Test
    void enterSelectsTheFieldManipulationNode() {
        createResultJourney();
        selectTrigger();

        page.locator(".step-node").press("Enter");

        assertThat(page.locator(".inspector-heading h2").textContent())
                .isEqualTo("Field Manipulation");
    }

    @Test
    void selectedNodeIsVisuallyExclusive() {
        addTrigger();

        assertThat(page.locator(".trigger-node").getAttribute("class")).contains("selected");
        assertThat(page.locator(".trigger-node").getAttribute("aria-selected")).isEqualTo("true");
        assertThat(page.locator(".app-node").getAttribute("class")).doesNotContain("selected");

        page.locator(".app-node").click();

        assertThat(page.locator(".app-node").getAttribute("class")).contains("selected");
        assertThat(page.locator(".app-node").getAttribute("aria-selected")).isEqualTo("true");
        assertThat(page.locator(".trigger-node").getAttribute("class")).doesNotContain("selected");
    }

    @Test
    void addingAFilterPersistsEveryDeclaredOutcomeConnection() {
        addFilterAfterTrigger();

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const filter = project.nodes.find(node => node.use === 'railix.filter');
                  return project.links.filter(link => link.from.startsWith(filter.id + '.'))
                    .map(link => link.from.substring(filter.id.length) + ':' + link.to)
                    .sort().join('|');
                }
                """)).isEqualTo(".match:end|.otherwise:end");
    }

    @Test
    void filterRendersOneDeterministicLanePerOutcome() {
        addFilterAfterTrigger();

        assertThat(page.locator(".branch-route").count()).isEqualTo(2);
        assertThat(page.locator(".branch-route-label").allTextContents())
                .containsExactly("Match", "Otherwise");
    }

    @Test
    void filterInspectorAddsANormalStepToTheChosenOutcome() {
        addFilterAfterTrigger();

        page.locator("[data-add-outcome='otherwise']").click();
        page.locator("#step-search").fill("field");
        page.locator("[data-add-step='railix.field-manipulation']").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const filter = project.nodes.find(node => node.use === 'railix.filter');
                  const branch = project.links.find(link => link.from === filter.id + '.otherwise');
                  const inserted = project.nodes.find(node => node.id === branch.to);
                  const terminal = project.links.find(link => link.from === inserted.id + '.next');
                  return inserted.use + ':' + terminal.to;
                }
                """)).isEqualTo("railix.field-manipulation:end");
    }

    @Test
    void branchLayoutIsStableAcrossReload() {
        addFilterAfterTrigger();
        final String before = positions();

        page.reload();
        waitForText("#build-state", "Built");

        assertThat(positions()).isEqualTo(before);
    }

    @Test
    void nestedFilterLayoutIsStableAcrossReload() {
        addNestedFilterToMatchRoute();
        final String before = positions();

        page.reload();
        waitForText("#build-state", "Built");

        assertThat(page.locator(".branch-route").count()).isEqualTo(4);
        assertThat(positions()).isEqualTo(before);
    }

    @Test
    void nestedFiltersRenderOutcomesInDeclaredDepthFirstOrder() {
        addNestedFilterToMatchRoute();

        assertThat(page.locator(".branch-route-label").allTextContents())
                .containsExactly("Match", "Match", "Otherwise", "Otherwise");
    }

    @Test
    void branchRenderingHandlesSixThousandLinearStepsWithoutCallStackGrowth() {
        openProject(deepBranchProject(6_000));

        assertThat(page.locator(".step-node").count()).isEqualTo(6_001);
        assertThat(page.locator(".branch-route").count()).isEqualTo(2);
    }

    @Test
    void nestedFilterAddsANormalStepOnlyToItsSelectedRoute() {
        addNestedFilterToMatchRoute();

        page.locator("[data-add-outcome='otherwise']").last().click();
        page.locator("#step-search").fill("field");
        page.locator("[data-add-step='railix.field-manipulation']").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const filters = project.nodes.filter(node => node.use === 'railix.filter');
                  const outer = filters.find(filter => project.links.some(link =>
                    link.from === filter.id + '.match' && filters.some(inner => inner.id === link.to)));
                  const inner = filters.find(filter => filter.id !== outer.id);
                  const inserted = project.links.find(link => link.from === inner.id + '.otherwise')?.to;
                  return project.nodes.find(node => node.id === inserted)?.use + '|'
                    + project.links.find(link => link.from === inner.id + '.match')?.to + '|'
                    + project.links.find(link => link.from === outer.id + '.otherwise')?.to;
                }
                """)).isEqualTo("railix.field-manipulation|end|end");
    }

    @Test
    void deletingANestedBranchLeafRestoresOnlyTheNestedOutcome() {
        addNestedFilterToMatchRoute();
        page.locator("[data-add-outcome='otherwise']").last().click();
        page.locator("#step-search").fill("field");
        page.locator("[data-add-step='railix.field-manipulation']").click();
        waitForText("#build-state", "Built");

        page.locator("#delete-step").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const filters = project.nodes.filter(node => node.use === 'railix.filter');
                  const outer = filters.find(filter => project.links.some(link =>
                    link.from === filter.id + '.match' && filters.some(inner => inner.id === link.to)));
                  const inner = filters.find(filter => filter.id !== outer.id);
                  return project.links.find(link => link.from === inner.id + '.otherwise')?.to + '|'
                    + project.links.find(link => link.from === outer.id + '.match')?.to;
                }
                """)).asString().startsWith("end|step-");
    }

    @Test
    void rollingBuiltApplicationExecutesTheMatchingFilterExample() {
        openProject(filterProject());
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(runResult(0)).isEqualTo(RailixValue.string("matched"));
    }

    @Test
    void rollingBuiltApplicationExecutesTheOtherwiseFilterExample() {
        openProject(filterProject());
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(runResult(1)).isEqualTo(RailixValue.string("otherwise"));
    }

    @Test
    void addingAChoicePersistsEveryDeclaredOutcomeConnection() {
        addChoiceAfterTrigger();

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const choice = project.nodes.find(node => node.use === 'railix.choice');
                  return project.links.filter(link => link.from.startsWith(choice.id + '.'))
                    .map(link => link.from.substring(choice.id.length) + ':' + link.to)
                    .sort().join('|');
                }
                """)).isEqualTo(".match:end|.otherwise:end");
    }

    @Test
    void choiceAddsACompleteOrGroupAndPersistsItAcrossReload() {
        addChoiceAfterTrigger();

        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");
        page.reload();
        waitForText("#build-state", "Built");
        final String choice = String.valueOf(page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).project.nodes
                  .find(node => node.use === 'railix.choice').id
                """));
        page.locator("[data-select-step='" + choice + "']").click();

        assertThat(page.locator("[data-matcher-group='0']").count()).isEqualTo(1);
        assertThat(page.locator("[data-matcher-group='0'] [data-candidate-index='0'] select").inputValue())
                .isEqualTo("field");
        assertThat(page.locator("[data-matcher-group='0'] [data-remove-candidate='0']").isDisabled())
                .isTrue();
        assertThat(page.locator("[data-matcher-group='0'] [data-add-candidate='field']").count())
                .isEqualTo(1);
    }

    @Test
    void choiceAddsAnAndMatcherToAnExistingGroup() {
        addChoiceAfterTrigger();
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");

        page.locator("[data-matcher-group='0'] [data-add-candidate='field']").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  return project.nodes.find(node => node.use === 'railix.choice').inputs.conditions[0].length;
                }
                """)).isEqualTo(2);
    }

    @Test
    void choiceReordersAndMatchersInsideTheirGroup() {
        addChoiceAfterTrigger();
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-matcher-group='0'] [data-add-candidate='literal']").click();
        final Locator literal = page.locator("[data-matcher-group='0'] [data-input-json]");
        literal.fill("\"constant\"");
        literal.press("Tab");
        waitForText("#build-state", "Built");

        page.locator("[data-matcher-group='0'] [data-move-candidate='1'][data-direction='-1']").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const first = project.nodes.find(node => node.use === 'railix.choice').inputs.conditions[0][0];
                  return first.option + ':' + first.inputs.value;
                }
                """)).isEqualTo("literal:constant");
    }

    @Test
    void reorderingChoiceMatchersClearsPositionBoundStepSearch() {
        addChoiceAfterTrigger();
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-matcher-group='0'] [data-add-candidate='field']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-matcher-group='0'] [data-candidate-index='0'] [data-step-query]").fill("equals");

        page.locator("[data-matcher-group='0'] [data-move-candidate='0'][data-direction='1']").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("[data-matcher-group='0'] [data-candidate-index='0'] [data-step-query]")
                .inputValue()).isEmpty();
        assertThat(page.locator("[data-matcher-group='0'] [data-candidate-index='1'] [data-step-query]")
                .inputValue()).isEmpty();
    }

    @Test
    void choiceRemovesOneAndMatcherWithoutRemovingItsGroup() {
        addChoiceAfterTrigger();
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-matcher-group='0'] [data-add-candidate='field']").click();
        waitForText("#build-state", "Built");

        page.locator("[data-matcher-group='0'] [data-remove-candidate='1']").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const conditions = project.nodes.find(node => node.use === 'railix.choice').inputs.conditions;
                  return conditions.length + ':' + conditions[0].length;
                }
                """)).isEqualTo("1:1");
    }

    @Test
    void replacingARemovedChoiceMatcherDoesNotRestoreItsStepSearch() {
        addChoiceAfterTrigger();
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-matcher-group='0'] [data-add-candidate='field']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-matcher-group='0'] [data-candidate-index='1'] [data-step-query]").fill("equals");

        page.locator("[data-matcher-group='0'] [data-remove-candidate='1']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-matcher-group='0'] [data-add-candidate='field']").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("[data-matcher-group='0'] [data-candidate-index='1'] [data-step-query]")
                .inputValue()).isEmpty();
    }

    @Test
    void choiceReordersWholeOrGroupsWithoutChangingTheirMatchers() {
        addChoiceAfterTrigger();
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-add-matcher-group='literal']").click();
        final Locator literal = page.locator("[data-matcher-group='1'] [data-input-json]");
        literal.fill("\"constant\"");
        literal.press("Tab");
        waitForText("#build-state", "Built");

        page.locator("[data-move-matcher-group='1'][data-direction='-1']").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const first = project.nodes.find(node => node.use === 'railix.choice').inputs.conditions[0][0];
                  return first.option + ':' + first.inputs.value;
                }
                """)).isEqualTo("literal:constant");
    }

    @Test
    void reorderingChoiceGroupsClearsPositionBoundStepSearch() {
        addChoiceAfterTrigger();
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-matcher-group='0'] [data-step-query]").fill("equals");

        page.locator("[data-move-matcher-group='0'][data-direction='1']").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("[data-matcher-group='0'] [data-step-query]").inputValue()).isEmpty();
        assertThat(page.locator("[data-matcher-group='1'] [data-step-query]").inputValue()).isEmpty();
    }

    @Test
    void choiceRemovesOneWholeOrGroup() {
        addChoiceAfterTrigger();
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");

        page.locator("[data-remove-matcher-group='0']").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  return project.nodes.find(node => node.use === 'railix.choice').inputs.conditions.length;
                }
                """)).isEqualTo(1);
    }

    @Test
    void replacingARemovedChoiceGroupDoesNotRestoreItsStepSearch() {
        addChoiceAfterTrigger();
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-matcher-group='1'] [data-step-query]").fill("equals");

        page.locator("[data-remove-matcher-group='1']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("[data-matcher-group='1'] [data-step-query]").inputValue()).isEmpty();
    }

    @Test
    void choiceDoesNotAllowAnEmptyAndGroup() {
        addChoiceAfterTrigger();
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("[data-matcher-group='0'] [data-remove-candidate='0']").isDisabled())
                .isTrue();
    }

    @Test
    void choiceMatcherOffersCompatibleOrdinaryBooleanSteps() {
        addChoiceAfterTrigger();
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");

        final Locator search = page.locator("[data-matcher-group='0'] [data-predicate-query]");
        search.fill("equals");

        assertThat(page.locator("[data-matcher-group='0'] [data-add-predicate='value.equals']").count())
                .isEqualTo(1);
    }

    @Test
    void choiceProgramDistinguishesMatchersFromTransformsByContract() {
        addChoiceAfterTrigger();
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("[data-matcher-group='0'] .condition-predicates"
                + " [data-add-predicate='value.equals']").count()).isEqualTo(1);
        assertThat(page.locator("[data-matcher-group='0'] .condition-transforms"
                + " [data-add-nested='value.to-json']").count()).isEqualTo(1);
        assertThat(page.locator("[data-matcher-group='0'] .condition-transforms"
                + " [data-add-nested='value.equals']").count()).isZero();
    }

    @Test
    void choiceProgramMakesItsStepSearchPurposeExplicit() {
        addChoiceAfterTrigger();
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("[data-matcher-group='0'] .condition-transforms").textContent())
                .contains("Transform value", "Run once", "Add transform");
        assertThat(page.locator("[data-matcher-group='0'] .condition-predicates").textContent())
                .contains("Matchers", "All must pass", "Add AND matcher");
        assertThat(page.locator("[data-matcher-group='0'] [data-predicate-query]")
                .getAttribute("placeholder")).isEqualTo("Search matchers");
    }

    @Test
    void greaterOrEqualAliasIsDiscoverableAfterANumericTransform() {
        prepareSizeChoiceMatcher();

        final Locator search = page.locator("[data-matcher-group='0'] [data-predicate-query]");
        search.fill("gte");

        assertThat(page.locator("[data-matcher-group='0']"
                + " [data-add-predicate='number.greater-or-equal']").count())
                .isEqualTo(1);
    }

    @Test
    void newGreaterOrEqualMatcherHasAnImmediatelyBuildableDefault() {
        prepareSizeChoiceMatcher();
        final Locator search = page.locator("[data-matcher-group='0'] [data-predicate-query]");
        search.fill("gte");
        page.locator("[data-matcher-group='0'] [data-add-predicate='number.greater-or-equal']").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).project.nodes
                  .find(node => node.use === 'railix.choice').inputs.conditions[0][0]
                  .when.all[0][0].inputs.than
                """)).isEqualTo(0);
    }

    @Test
    void numericMatcherRemainsAvailableAfterAnotherNumericMatcher() {
        prepareSizeChoiceMatcher();
        final Locator search = page.locator("[data-matcher-group='0'] [data-predicate-query]");
        search.fill("gt");
        page.locator("[data-matcher-group='0'] [data-add-predicate='number.greater-than']").click();
        waitForText("#build-state", "Built");

        search.fill("lt");

        assertThat(page.locator("[data-matcher-group='0'] [data-add-predicate='number.less-than']").count())
                .isEqualTo(1);
    }

    @Test
    void numericMatchersArePersistedAsIndependentAndLanes() {
        addSizeBounds(1);

        assertThat(page.evaluate("""
                async () => {
                  const when = (await (await fetch('/api/project')).json()).project.nodes
                    .find(node => node.use === 'railix.choice').inputs.conditions[0][0].when;
                  return JSON.stringify({
                    transforms: when.transforms.map(step => step.use),
                    predicates: when.all.map(program => [program[0].use, program[0].inputs.than])
                  });
                }
                """)).isEqualTo("{\"transforms\":[\"list.size\"],\"predicates\":["
                + "[\"number.greater-than\",1],[\"number.less-than\",5]]}");
    }

    @Test
    void independentMatchersCanBeReordered() {
        addSizeBounds(1);

        page.locator("[data-condition-predicate='1'] [data-move-predicate='1'][data-direction='-1']").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => JSON.stringify(
                  (await (await fetch('/api/project')).json()).project.nodes
                    .find(node => node.use === 'railix.choice').inputs.conditions[0][0]
                    .when.all.map(program => program[0].use)
                )
                """)).isEqualTo("[\"number.less-than\",\"number.greater-than\"]");
    }

    @Test
    void oneIndependentMatcherCanBeRemoved() {
        addSizeBounds(1);

        page.locator("[data-condition-predicate='0'] [data-remove-predicate='0']").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => JSON.stringify(
                  (await (await fetch('/api/project')).json()).project.nodes
                    .find(node => node.use === 'railix.choice').inputs.conditions[0][0]
                    .when.all.map(program => program[0].use)
                )
                """)).isEqualTo("[\"number.less-than\"]");
    }

    @Test
    void notEqualsAliasAddsABuildableDefaultThatReloadsPreviewsAndExecutes() {
        openProject(choiceProject());
        page.locator("[data-select-step='choice']").click();
        page.locator("[data-matcher-group='0'] [data-remove-predicate='0']").click();
        waitForText("#build-state", "Built");

        final Locator search = page.locator("[data-matcher-group='0'] [data-predicate-query]");
        search.fill("neq");
        page.locator("[data-matcher-group='0'] [data-add-predicate='value.not-equals']").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => JSON.stringify(
                  (await (await fetch('/api/project')).json()).project.nodes
                    .find(node => node.use === 'railix.choice').inputs.conditions[0][0]
                    .when.all[0][0].inputs
                )
                """)).isEqualTo("{\"expected\":null}");

        page.reload();
        waitForText("#build-state", "Built");
        page.locator("[data-select-step='choice']").click();
        page.locator("[data-matcher-group='0'] [data-preview-slot='0']").waitFor();

        assertThat(page.locator("[data-matcher-group='0'] [data-input-json]").inputValue())
                .isEqualTo("null");
        assertThat(page.locator("[data-matcher-group='0'] [data-preview-slot='0']").textContent())
                .isEqualTo("true");

        selectTrigger();
        page.locator(".run-result").waitFor();
        assertThat(runResult(0)).isEqualTo(RailixValue.string("matched"));
        assertThat(runResult(1)).isEqualTo(RailixValue.string("matched"));
    }

    @Test
    void builtApplicationExecutesAChoiceMatcherPipelineInOrder() {
        openProject(branchProject("choice-pipeline", "choice", "railix.choice", """
                [[{
                  "option":"literal","inputs":{"value":["one","two"]},
                  "when":[
                    {"use":"list.size","inputs":{}},
                    {"use":"number.greater-or-equal","inputs":{"than":2}}
                  ]
                }]]
                """));
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(runResult(0)).isEqualTo(RailixValue.string("matched"));
    }

    @ParameterizedTest(name = "list size {0} routes to {1}")
    @CsvSource({"1,otherwise", "2,matched", "4,matched", "5,otherwise"})
    void builtApplicationRequiresEveryNumericMatcher(final int size, final String expected) {
        openProject(branchProject("choice-bounds-" + size, "choice", "railix.choice", """
                [[{
                  "option":"literal","inputs":{"value":%s},
                  "when":{
                    "transforms":[{"use":"list.size","inputs":{}}],
                    "all":[
                      [{"use":"number.greater-than","inputs":{"than":1}}],
                      [{"use":"number.less-than","inputs":{"than":5}}]
                    ]
                  }
                }]]
                """.formatted(numberList(size))));
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(runResult(0)).isEqualTo(RailixValue.string(expected));
    }

    @ParameterizedTest(name = "UI-authored list size {0} routes to {1}")
    @CsvSource({"1,otherwise", "2,matched", "4,matched", "5,otherwise"})
    void uiAuthoredNumericMatchersExecuteAfterReload(final int size, final String expected) {
        addSizeBounds(size);

        page.reload();
        waitForText("#build-state", "Built");
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(runResult(0)).isEqualTo(RailixValue.string(expected));
    }

    @Test
    void choiceBranchesUseStrongTwoPixelConnectors() {
        openProject(choiceProject());

        assertThat(page.evaluate("""
                () => [
                  getComputedStyle(document.querySelector('.branch-routes'), '::before').height,
                  getComputedStyle(document.querySelector('.branch-route'), '::before').width,
                  getComputedStyle(document.querySelector('.branch-route-label')).fontWeight
                ].join('|')
                """)).isEqualTo("2px|2px|700");
    }

    @Test
    void choiceConnectorHasATrunkBetweenTheNodeAndBranchBus() {
        openProject(choiceProject());
        positions();

        @SuppressWarnings("unchecked")
        final Map<String, Object> geometry = (Map<String, Object>) page.evaluate("""
                () => {
                  const choice = document.querySelector("[data-select-step='choice']").getBoundingClientRect();
                  const routes = document.querySelector('.branch-routes').getBoundingClientRect();
                  const trunk = document.querySelector('.branch-trunk')?.getBoundingClientRect();
                  if (!trunk) return {missing: true};
                  const busY = routes.top + parseFloat(getComputedStyle(
                    document.querySelector('.branch-routes'), '::before').top);
                  return {
                    missing: false,
                    centerDelta: (trunk.left + trunk.width / 2) - (choice.left + choice.width / 2),
                    startDelta: trunk.top - choice.bottom,
                    endDelta: trunk.bottom - busY,
                    busDistance: busY - choice.bottom
                  };
                }
                """);

        assertThat(geometry.get("missing")).as("geometry: %s", geometry).isEqualTo(false);
        assertThat(((Number) geometry.get("centerDelta")).doubleValue())
                .as("geometry: %s", geometry).isBetween(-1.0, 1.0);
        assertThat(((Number) geometry.get("startDelta")).doubleValue())
                .as("geometry: %s", geometry).isBetween(-1.0, 1.0);
        assertThat(((Number) geometry.get("endDelta")).doubleValue())
                .as("geometry: %s", geometry).isBetween(-1.0, 1.0);
        assertThat(((Number) geometry.get("busDistance")).doubleValue())
                .as("geometry: %s", geometry).isGreaterThanOrEqualTo(20.0);
    }

    @Test
    void horizontalChoiceBusEndsAtTheOuterBranchCenters() {
        openProject(choiceProject());
        positions();

        assertThat(page.evaluate("""
                () => {
                  const routes = document.querySelector('.branch-routes');
                  const branches = [...routes.querySelectorAll(':scope > .branch-route')];
                  const first = branches[0].getBoundingClientRect();
                  const last = branches.at(-1).getBoundingClientRect();
                  const firstCenter = first.left + first.width / 2;
                  const lastCenter = last.left + last.width / 2;
                  if (Math.abs(firstCenter - lastCenter) < 1) return true;
                  const box = routes.getBoundingClientRect();
                  const style = getComputedStyle(routes, '::before');
                  const busLeft = box.left + parseFloat(style.left);
                  const busRight = box.right - parseFloat(style.right);
                  return Math.abs(busLeft - firstCenter) < 1 && Math.abs(busRight - lastCenter) < 1;
                }
                """)).isEqualTo(true);
    }

    @Test
    void nestedBranchDropsConnectEveryBusToItsRoutes() {
        addNestedFilterToMatchRoute();
        positions();

        assertThat(page.evaluate("""
                () => [...document.querySelectorAll('.branch-routes')].every(routes => {
                  const routeBox = routes.getBoundingClientRect();
                  const busY = routeBox.top + parseFloat(getComputedStyle(routes, '::before').top);
                  return [...routes.querySelectorAll(':scope > .branch-route')].every(branch => {
                    const branchBox = branch.getBoundingClientRect();
                    const drop = getComputedStyle(branch, '::before');
                    const dropTop = branchBox.top + parseFloat(drop.top);
                    const dropBottom = dropTop + parseFloat(drop.height);
                    return Math.abs(dropTop - busY) < 1 && Math.abs(dropBottom - branchBox.top) < 1;
                  });
                })
                """)).isEqualTo(true);
    }

    @Test
    void rollingBuiltApplicationExecutesTheMatchingChoiceExample() {
        openProject(choiceProject());
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(runResult(0)).isEqualTo(RailixValue.string("matched"));
    }

    @Test
    void rollingBuiltApplicationExecutesTheOtherwiseChoiceExample() {
        openProject(choiceProject());
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(runResult(1)).isEqualTo(RailixValue.string("otherwise"));
    }

    @Test
    void choicePreviewShowsTheBooleanResolvedByTheBuiltApplication() {
        openProject(choiceProject());

        page.locator("[data-select-step='choice']").click();
        page.locator("[data-preview-input-value='conditions']").waitFor();

        assertThat(page.locator("[data-preview-input-value='conditions']").first().textContent())
                .isEqualTo("true");
    }

    @Test
    void choicePreviewShowsTheBuiltMatcherPredicateStage() {
        openProject(choiceProject());

        page.locator("[data-select-step='choice']").click();
        page.locator("[data-matcher-group='0'] [data-preview-slot='0']").waitFor();

        assertThat(page.locator("[data-matcher-group='0'] [data-preview-slot='0']").textContent())
                .isEqualTo("true");
    }

    @Test
    void insertingAFilterPreservesTheExistingPrimaryRoute() {
        addTrigger();
        addManipulationAfterSelected();
        selectTrigger();
        page.locator("#add-next-step").click();
        page.locator("#step-search").fill("filter");
        page.locator("[data-add-step='railix.filter']").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const filter = project.nodes.find(node => node.use === 'railix.filter');
                  const manipulation = project.nodes.find(node => node.use === 'railix.field-manipulation');
                  return project.links.find(link => link.from === filter.id + '.match')?.to === manipulation.id
                    && project.links.find(link => link.from === filter.id + '.otherwise')?.to === 'end';
                }
                """)).isEqualTo(true);
    }

    @Test
    void populatedBranchPreventsDeletingItsFilter() {
        addStepToOtherwiseBranch();
        final String filter = String.valueOf(page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).project.nodes
                  .find(node => node.use === 'railix.filter').id
                """));

        page.locator("[data-select-step='" + filter + "']").click();

        assertThat(page.locator("#delete-step").isDisabled()).isEqualTo(true);
    }

    @Test
    void deletingABranchLeafRestoresOnlyThatOutcomeToEnd() {
        addStepToOtherwiseBranch();

        page.locator("#delete-step").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const filter = project.nodes.find(node => node.use === 'railix.filter');
                  return project.links.find(link => link.from === filter.id + '.otherwise')?.to;
                }
                """)).isEqualTo("end");
    }

    @Test
    void deletingAnEmptyFilterReconnectsItsIncomingRoute() {
        addFilterAfterTrigger();

        page.locator("#delete-step").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const trigger = project.nodes.find(node => node.use === 'railix.trigger.cli');
                  return [
                    project.nodes.some(node => node.use === 'railix.filter'),
                    project.links.find(link => link.from === trigger.id + '.next')?.to
                  ].join('|');
                }
                """)).isEqualTo("false|end");
    }

    @Test
    void deletingAFilterWithAPopulatedPrimaryRouteKeepsThatRouteConnected() {
        addFilterAfterTrigger();
        page.locator("[data-add-outcome='match']").click();
        page.locator("#step-search").fill("field");
        page.locator("[data-add-step='railix.field-manipulation']").click();
        waitForText("#build-state", "Built");
        final String filter = String.valueOf(page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).project.nodes
                  .find(node => node.use === 'railix.filter').id
                """));
        page.locator("[data-select-step='" + filter + "']").click();

        page.locator("#delete-step").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const trigger = project.nodes.find(node => node.use === 'railix.trigger.cli');
                  const target = project.links.find(link => link.from === trigger.id + '.next')?.to;
                  return project.nodes.some(node => node.use === 'railix.filter') + '|'
                    + project.nodes.find(node => node.id === target)?.use;
                }
                """)).isEqualTo("false|railix.field-manipulation");
    }

    @Test
    void missingOutcomeLinkIsShownAsMissingInsteadOfEnd() {
        addFilterAfterTrigger();

        page.evaluate("""
                () => {
                  const filter = state.project.nodes.find(node => node.use === 'railix.filter');
                  state.project.links = state.project.links.filter(link =>
                    link.from !== filter.id + '.otherwise');
                  dirty(true);
                }
                """);
        waitForText("#build-state", "Not built");

        assertThat(page.locator("[data-branch-outcome='otherwise']").textContent())
                .contains("Missing link").doesNotContain("Trigger result");
        assertThat(page.locator(".next-routes").textContent()).contains("Missing link");
        assertThat(page.locator("[data-add-outcome='otherwise']").isDisabled()).isTrue();
    }

    @Test
    void duplicateOutcomeLinksAreShownAsMultipleInsteadOfChoosingOne() {
        addFilterAfterTrigger();

        page.evaluate("""
                () => {
                  const filter = state.project.nodes.find(node => node.use === 'railix.filter');
                  state.project.links.push({from: filter.id + '.otherwise', to: 'end'});
                  dirty(true);
                }
                """);
        waitForText("#build-state", "Not built");

        assertThat(page.locator("[data-branch-outcome='otherwise']").textContent())
                .contains("Multiple links").doesNotContain("Trigger result");
        assertThat(page.locator(".next-routes").textContent()).contains("Multiple links");
    }

    @Test
    void duplicateOutcomeDisablesStepInsertion() {
        addFilterAfterTrigger();

        page.evaluate("""
                () => {
                  const filter = state.project.nodes.find(node => node.use === 'railix.filter');
                  state.project.links.push({from: filter.id + '.otherwise', to: 'end'});
                  dirty(true);
                }
                """);
        waitForText("#build-state", "Not built");

        assertThat(page.locator("[data-add-outcome='otherwise']").isDisabled()).isTrue();
    }

    @Test
    void unknownOutcomeTargetIsShownAsUnknownInsteadOfDisappearing() {
        addFilterAfterTrigger();

        page.evaluate("""
                () => {
                  const filter = state.project.nodes.find(node => node.use === 'railix.filter');
                  state.project.links.find(link => link.from === filter.id + '.otherwise').to = 'missing-step';
                  dirty(true);
                }
                """);
        waitForText("#build-state", "Not built");

        assertThat(page.locator("[data-branch-outcome='otherwise']").textContent())
                .contains("Unknown Step").doesNotContain("Trigger result");
        assertThat(page.locator("[data-add-outcome='otherwise']").isDisabled()).isTrue();
    }

    @Test
    void repeatedOutcomeTargetIsShownAsRepeatedInsteadOfRenderedTwice() {
        addFilterAfterTrigger();
        page.locator("[data-add-outcome='match']").click();
        page.locator("#step-search").fill("field");
        page.locator("[data-add-step='railix.field-manipulation']").click();
        waitForText("#build-state", "Built");

        final String filter = String.valueOf(page.evaluate("""
                () => {
                  const filter = state.project.nodes.find(node => node.use === 'railix.filter');
                  const target = state.project.links.find(link => link.from === filter.id + '.match').to;
                  state.project.links.find(link => link.from === filter.id + '.otherwise').to = target;
                  dirty(true);
                  return filter.id;
                }
                """));
        waitForText("#build-state", "Not built");

        assertThat(page.locator("[data-branch-outcome='otherwise']").textContent())
                .contains("Repeated Step").doesNotContain("Field Manipulation");
        page.locator("[data-select-step='" + filter + "']").click();
        assertThat(page.locator("[data-add-outcome='otherwise']").isDisabled()).isTrue();
    }

    @Test
    void deletingAFlowRemovesEveryBranchNode() {
        addStepToOtherwiseBranch();

        selectTrigger();
        page.locator("#delete-step").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  return project.nodes.map(node => node.id).join('|') + ':' + project.links.length;
                }
                """)).isEqualTo("app:0");
    }

    @Test
    void existingGroupRemainsCollapsedAfterAddingAFilterBeforeIt() {
        openProject(fourStepProject());
        groupSteps(0, 1);
        selectTrigger();
        page.locator("#add-next-step").click();
        page.locator("#step-search").fill("filter");
        page.locator("[data-add-step='railix.filter']").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator(".flow-node").count()).isEqualTo(1);
        assertThat(page.locator("[data-select-step='one']").count()).isZero();
    }

    @Test
    void collapsedGroupInsideABranchHasOnlyOneIncomingConnector() {
        openProject(fourStepProject());
        groupSteps(0, 1);
        selectTrigger();
        page.locator("#add-next-step").click();
        page.locator("#step-search").fill("filter");
        page.locator("[data-add-step='railix.filter']").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator(".branch-route .lane-connector + .lane-connector").count()).isZero();
    }

    @Test
    void controlStepIsNotOfferedInsideAnExistingGroup() {
        openProject(fourStepProject());
        groupSteps(0, 1);
        page.locator("#open-group").click();
        page.locator("[data-select-step='one']").click();

        page.locator("#add-next-step").click();
        page.locator("#step-search").fill("filter");

        assertThat(page.locator("[data-add-step='railix.filter']").count()).isZero();
        assertThat(page.locator("#step-options .empty-options").textContent())
                .isEqualTo("Branch Steps cannot be added inside a group.");
    }

    @Test
    void branchStepCannotBeGroupedUntilBranchAwareEditingExists() {
        addFilterAfterTrigger();
        page.locator("[data-add-outcome='match']").click();
        page.locator("#step-search").fill("field");
        page.locator("[data-add-step='railix.field-manipulation']").click();
        waitForText("#build-state", "Built");
        final String filter = String.valueOf(page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).project.nodes
                  .find(node => node.use === 'railix.filter').id
                """));
        final String manipulation = String.valueOf(page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).project.nodes
                  .find(node => node.use === 'railix.field-manipulation').id
                """));

        page.locator("[data-inspector-mode='groups']").click();
        page.locator("#new-group").click();
        page.locator("[data-select-step='" + filter + "']").click();
        page.locator("[data-select-step='" + manipulation + "']").click();

        assertThat(page.locator("[data-select-step='" + filter + "']").getAttribute("class"))
                .contains("issue-error");
        assertThat(page.locator(".issues").textContent())
                .contains(
                        "CREATOR_GROUP_BRANCH_UNSUPPORTED",
                        "This Step has multiple routes. Group one route at a time so no exit is hidden."
                );
        assertThat(page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).creator.groups.length
                """)).isEqualTo(0);
    }

    @Test
    void addTriggerSearchUsesInstalledTriggerCatalog() {
        page.locator("#add-trigger").click();
        page.locator("#step-search").fill("cli");

        assertThat(page.locator("[data-add-step]").count()).isEqualTo(1);
        assertThat(page.locator("[data-add-step]").textContent()).contains("CLI", "Trigger");
    }

    @Test
    void addingTriggerCreatesARealFlowAndKeepsTheLastBuiltApplication() {
        addTrigger();
        waitForText("#build-state", "Built");

        assertThat(page.locator(".graph-stage").textContent())
                .contains("Trigger", "End")
                .doesNotContain("stream");
        assertThat(page.locator("#inspector").textContent())
                .doesNotContain("PROJECT_TRIGGER_RESULT_REQUIRED", "Trigger example: example");
    }

    @Test
    void triggerTargetPathCanBeEditedFromItsGenericContract() {
        addTrigger();

        page.locator("#target-path").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("#new-path-field").fill("command");
        page.locator("#append-path-field").click();
        page.locator("#apply-path").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("#target-path").textContent()).contains("context", "command");
    }

    @Test
    void triggerInspectorSeparatesConfigurationExamplesAndAppearance() {
        addTrigger();

        assertThat(page.locator("[data-inspector-mode]").allTextContents())
                .containsExactly("Inspector", "Appearance", "Examples", "Groups");
        assertThat(((String) page.locator(".inspector-tabs").evaluate(
                "tabs => getComputedStyle(tabs).gridTemplateColumns"
        )).split(" ")).hasSize(4);
        assertThat(page.locator("#target-path").count()).isEqualTo(1);
        assertThat(page.locator("#example-payload, #presentation-name").count()).isZero();

        openInspectorTab("examples");

        assertThat(page.locator("#example-payload").count()).isEqualTo(1);
        assertThat(page.locator("#target-path, #presentation-name").count()).isZero();

        openInspectorTab("appearance");

        assertThat(page.locator("#presentation-name").count()).isEqualTo(1);
        assertThat(page.locator("#target-path, #example-payload").count()).isZero();
    }

    @Test
    void triggerTargetIsAlwaysVisibleAndCanResetToItsContractDefault() {
        addTrigger();

        assertThat(page.locator("#target-path").count()).isEqualTo(1);
        assertThat(page.locator("#target-present").count()).isZero();
        assertThat(page.locator("#inspector").textContent())
                .doesNotContain("Use target", "Override target");

        chooseCustomPathFor("target", "command");
        waitForText("#build-state", "Built");
        assertThat(page.locator("#target-path").textContent()).contains("context", "command");
        assertThat(page.locator("[data-reset-path]").isEnabled()).isTrue();

        page.locator("[data-reset-path]").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("#target-path").textContent())
                .contains("context", "payload", "arguments");
        assertThat(page.locator("[data-reset-path]").isEnabled()).isFalse();
    }

    @Test
    void graphTargetResetFollowsTheCurrentSourcePath() {
        addTrigger();
        chooseCustomPathFor("target", "payload");
        waitForText("#build-state", "Built");
        openInspectorTab("examples");
        replaceExamplePayloads(
                "{\"first\":\"A\",\"second\":\"B\"}",
                "{\"first\":\"C\",\"second\":\"D\"}",
                "{\"first\":\"E\",\"second\":\"F\"}"
        );
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        page.locator("#add-next-step").click();
        page.locator("#step-search").fill("lowercase");
        page.locator("[data-add-step='text.lowercase']").click();
        waitForText("#build-state", "Built");

        choosePath("source", "payload", "second");
        waitForText("#build-state", "Built");

        assertThat(page.locator("[data-input-name='target'] .path-button").textContent())
                .contains("context", "payload", "first");
        assertThat(page.locator("[data-input-name='target'] [data-reset-path]").isEnabled()).isTrue();

        page.locator("[data-input-name='target'] [data-reset-path]").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("[data-input-name='target'] .path-button").textContent())
                .contains("context", "payload", "second");
    }

    @Test
    void triggerExampleKeepsPayloadAndOptionalContextSeparate() {
        addTrigger();
        openInspectorTab("examples");

        assertThat(examplePayload().inputValue()).isEqualTo("[]");
        assertThat(exampleContext().inputValue()).isBlank();

        examplePayload().fill("[\"arg1\",\"arg2\",\"arg3\"]");
        examplePayload().press("Tab");
        exampleContext().fill("{\"header\":{\"request\":\"local\"}}");
        exampleContext().press("Tab");
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => JSON.stringify(
                  (await (await fetch('/api/project')).json()).project.nodes
                    .find(node => node.use === 'railix.trigger.cli').examples[0]
                )
                """)).isEqualTo(
                "{\"context\":{\"header\":{\"request\":\"local\"}},\"name\":\"no-arguments\","
                        + "\"payload\":[\"arg1\",\"arg2\",\"arg3\"]}"
        );
    }

    @Test
    void clearingOptionalExampleContextRemovesItFromTheProject() {
        addTrigger();
        openInspectorTab("examples");
        exampleContext().fill("{\"header\":{\"request\":\"local\"}}");
        exampleContext().press("Tab");
        waitForText("#build-state", "Built");

        exampleContext().fill("");
        exampleContext().press("Tab");
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => Object.hasOwn(
                  (await (await fetch('/api/project')).json()).project.nodes
                    .find(node => node.use === 'railix.trigger.cli').examples[0],
                  'context'
                )
                """)).isEqualTo(false);
    }

    @Test
    void examplesTabRemainsSelectedWhileRollingBuildCompletes() {
        addTrigger();
        openInspectorTab("examples");

        examplePayload().fill("[\"railix\"]");
        examplePayload().press("Tab");
        waitForText("#build-state", "Built");

        assertThat(page.locator("[data-inspector-mode='examples']").getAttribute("class"))
                .contains("active");
        assertThat(page.locator("#example-payload").inputValue()).contains("railix");
    }

    @Test
    void selectingAnotherNodeReturnsToItsInspectorTab() {
        addTrigger();
        openInspectorTab("examples");

        page.locator(".app-node").click();

        assertThat(page.locator("[data-inspector-mode='inspect']").getAttribute("class"))
                .contains("active");
        assertThat(page.locator("#example-payload").count()).isZero();
    }

    @Test
    void triggerNodeShowsItsExampleCoverageCount() {
        addTrigger();
        openInspectorTab("examples");

        assertThat(page.locator(".trigger-node").textContent()).contains("3 examples");

        page.locator("#add-example").click();

        assertThat(page.locator(".trigger-node").textContent()).contains("4 examples");
    }

    @Test
    void examplePayloadFollowsTheCurrentTriggerTargetWhenTheBuiltApplicationRuns() {
        addTrigger();
        chooseCustomPathFor("target", "payload", "command");
        waitForText("#build-state", "Built");
        openInspectorTab("examples");
        examplePayload().fill("[\"hello\",\"railix\"]");
        examplePayload().press("Tab");
        exampleContext().fill("{\"header\":{\"request\":\"local\"}}");
        exampleContext().press("Tab");
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");

        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent())
                .contains("\"command\": [", "\"hello\"", "\"railix\"")
                .contains("\"request\": \"local\"");
    }

    @Test
    void primitiveCanBeAddedAsAnOrdinaryMappedGraphStep() {
        prepareTextPayloadTrigger();

        page.locator("#add-next-step").click();
        page.locator("#step-search").fill("lowercase");

        assertThat(page.locator("[data-add-step='text.lowercase']").count()).isEqualTo(1);

        page.locator("[data-add-step='text.lowercase']").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator(".step-node").textContent()).contains("Lowercase");
        assertThat(page.locator("[data-input-name='source'] .path-button").textContent())
                .contains("context", "payload", "text");
        assertThat(page.locator("[data-input-name='target'] .path-button").textContent())
                .contains("context", "payload", "text");
        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const step = project.nodes.find(node => node.use === 'text.lowercase');
                  return JSON.stringify({receives: step.receives, returns: step.returns});
                }
                """)).isEqualTo(
                "{\"receives\":{\"value\":[\"context\",\"payload\",\"text\"]},"
                        + "\"returns\":{\"value\":[\"context\",\"payload\",\"text\"]}}"
        );
    }

    @Test
    void primitiveGraphStepExecutesThroughTheBuiltApplication() {
        prepareTextPayloadTrigger();
        page.locator("#add-next-step").click();
        page.locator("#step-search").fill("lowercase");
        page.locator("[data-add-step='text.lowercase']").click();
        waitForText("#build-state", "Built");

        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent()).contains("\"text\": \"railix\"");
    }

    @Test
    void primitiveGraphStepPreviewShowsRealBuiltInputAndOutput() {
        addGraphPrimitive("\"RAILIX\"", "lowercase", "text.lowercase");

        page.locator("#preview-source").waitFor();

        assertThat(page.locator("#preview-values").textContent())
                .contains("Built example", "RAILIX", "Built output", "railix");
    }

    @Test
    void selectedGraphStepSourcePickerDoesNotOfferItsOwnReturnedPath() {
        prepareTextPayloadTrigger();
        page.locator("#add-next-step").click();
        page.locator("#step-search").fill("lowercase");
        page.locator("[data-add-step='text.lowercase']").click();
        waitForText("#build-state", "Built");
        chooseCustomPathFor("target", "payload", "lower");
        waitForText("#build-state", "Built");
        page.locator("#preview-source").waitFor();

        page.locator("[data-input-name='source'] .path-button").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='payload']").click();

        assertThat(page.locator("[data-path-part='lower']").count()).isZero();
    }

    @Test
    void typeChangingPrimitiveWritesItsNumberToTheMappedTarget() {
        final String stepId = addGraphPrimitive("\"12.9\"", "to number", "text.to-number");

        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent())
                .contains("\"value\": 12.9")
                .contains("\"id\": \"" + stepId + "\"")
                .contains("\"outcome\": \"ok\"");
    }

    @Test
    void falliblePrimitiveUsesItsExplicitInvalidOutcomeWithoutFailingTheRun() {
        final String stepId = addGraphPrimitive("\"not-a-number\"", "to number", "text.to-number");

        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent())
                .contains("\"status\": \"succeeded\"")
                .contains("\"value\": \"not-a-number\"")
                .contains("\"id\": \"" + stepId + "\"")
                .contains("\"outcome\": \"invalid\"");
    }

    @Test
    void filterFieldPickerUsesTheUnionOfRealPayloadExamples() {
        addTrigger();
        chooseCustomPathFor("target", "payload");
        waitForText("#build-state", "Built");
        openInspectorTab("examples");
        replaceExamplePayloads(
                "{\"route\":\"status\"}",
                "{\"priority\":2}",
                "{}"
        );
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        page.locator("#add-next-step").click();
        page.locator("#step-search").fill("filter");
        page.locator("[data-add-step='railix.filter']").click();
        waitForText("#build-state", "Built");

        page.locator("#conditions-0-field-path").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='payload']").click();

        assertThat(page.locator(".path-choices").textContent()).contains("route", "priority");
    }

    @Test
    void graphPickerDoesNotOfferAValueStepWithoutACompatibleExampleField() {
        addTrigger();

        page.locator("#add-next-step").click();
        page.locator("#step-search").fill("lowercase");

        assertThat(page.locator("[data-add-step='text.lowercase']").count()).isZero();
        assertThat(page.locator("#step-options").textContent()).contains("No installed Step matches.");
    }

    @Test
    void graphPickerRequiresAnAutomaticSourceToMatchEveryExample() {
        addTrigger();
        chooseCustomPathFor("target", "payload");
        waitForText("#build-state", "Built");
        openInspectorTab("examples");
        replaceExamplePayloads("\"Railix\"", "7", "\"Railix\"");
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");

        page.locator("#add-next-step").click();
        page.locator("#step-search").fill("lowercase");

        assertThat(page.locator("[data-add-step='text.lowercase']").count()).isZero();
    }

    @Test
    void requiredGraphSourcePathCannotUsePartialExampleCoverage() {
        addTrigger();
        chooseCustomPathFor("target", "payload");
        waitForText("#build-state", "Built");
        openInspectorTab("examples");
        replaceExamplePayloads(
                "{\"value\":\"one\",\"other\":\"two\"}",
                "{\"other\":\"three\"}",
                "{\"other\":\"four\"}"
        );
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        page.locator("#add-next-step").click();
        page.locator("#step-search").fill("lowercase");
        page.locator("[data-add-step='text.lowercase']").click();
        waitForText("#build-state", "Built");

        page.locator("[data-input-name='source'] .path-button").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='payload']").click();

        assertThat(page.locator("[data-path-part='value']").count()).isZero();
        assertThat(page.locator("[data-path-part='other']").count()).isEqualTo(1);
    }

    @Test
    void prototypeNamedExampleTargetCannotMutateTheBrowserPrototype() {
        addTrigger();

        chooseCustomPathFor("target", "payload", "__proto__", "polluted");
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("Object.hasOwn(Object.prototype, 'polluted')")).isEqualTo(false);
        assertThat(page.evaluate("({}).polluted === undefined")).isEqualTo(true);
    }

    @Test
    void generatedFlowNameIsShortMemorableAndEditable() {
        addTrigger();
        waitForText("#build-state", "Built");
        final String generated = presentationName().inputValue();
        final String pid = applicationPid();

        presentationName().fill("quiet-vector");
        clickAndWaitForCreatorSave(() -> presentationName().press("Tab"));

        assertThat(generated).matches("[a-z]+-[a-z]+");
        assertThat(page.locator(".trigger-node").textContent()).contains("quiet-vector");
        assertThat(page.evaluate("""
                async () => Object.values((await (await fetch('/api/project')).json()).creator.steps)
                  .filter(step => step.name === 'quiet-vector').length
                """)).isEqualTo(1);
        assertThat(applicationPid()).isEqualTo(pid);
    }

    @Test
    void editingAFlowNameNeverChangesItsStableStepId() {
        addTrigger();
        final String id = page.locator(".trigger-node").getAttribute("data-node-id");

        presentationName().fill("sales/eu");
        clickAndWaitForCreatorSave(() -> presentationName().press("Tab"));
        page.reload();
        final String reloadedProject = String.valueOf(page.evaluate("""
                async () => JSON.stringify((await (await fetch('/api/project')).json()).project)
                """));
        page.waitForFunction("""
                () => document.querySelector('#build-state')?.textContent !== 'Loading'
                """);
        final String loadedState = String.valueOf(page.evaluate("""
                () => JSON.stringify({
                  build: state.build,
                  project: state.project,
                  creator: state.creator,
                  diagnostics: state.diagnostics
                })
                """));

        assertThat(reloadedProject).contains("\"id\":\"" + id + "\"");
        assertThat(page.locator("#build-state").textContent())
                .as(loadedState).isEqualTo("Built");
        assertThat(page.locator(".trigger-node").count()).as(loadedState).isEqualTo(1);
        assertThat(page.locator(".trigger-node").getAttribute("data-node-id"))
                .as(loadedState).isEqualTo(id);
    }

    @Test
    void installedSingletonTriggerCannotBeAddedTwice() {
        addTrigger();
        page.locator(".app-node").click();

        assertThat(page.locator("#add-trigger").count()).isZero();
        assertThat(page.locator(".trigger-node").count()).isEqualTo(1);
    }

    @Test
    void compilerDiagnosticAppearsOnlyOnItsOwningNode() {
        addTrigger();
        waitForText("#build-state", "Built");

        exampleContext().fill("{\"runtime\":{}}");
        exampleContext().press("Tab");

        assertThat(page.locator("#build-state").textContent()).isEqualTo("Built");
        assertThat(page.locator(".trigger-node").getAttribute("class")).contains("issue-error");
        assertThat(page.locator(".app-node").getAttribute("class")).doesNotContain("issue-error");
        openInspectorTab("inspect");
        assertThat(page.locator("#inspector").textContent())
                .contains("PROJECT_TRIGGER_EXAMPLE_CONTEXT_INVALID")
                .contains("Context must be an object without context.runtime.");

        page.locator(".app-node").click();

        assertThat(page.locator("#inspector").textContent())
                .doesNotContain("Example context must be an object without context.runtime.");
    }

    @Test
    void fieldManipulationUsesFullContextPathsAndCompletesTheFlow() {
        addTrigger();
        addManipulationAfterSelected();
        choosePath("field", "result");
        page.locator("#value-0-option").selectOption("field");
        choosePath("value-0-source", "payload");
        waitForText("#build-state", "Built");

        assertThat(page.locator(".step-node").textContent())
                .contains("Field Manipulation", "context.payload", "context.result");
    }

    @Test
    void fieldManipulationCanCreateACustomContextField() {
        addTrigger();
        addManipulationAfterSelected();
        chooseCustomField("auth");
        page.locator("#value-0-option").selectOption("literal");
        page.locator("#value-0-literal-value").fill("{\"token\":\"railix\"}");
        page.locator("#value-0-literal-value").press("Tab");

        assertThat(page.locator(".step-node").textContent())
                .contains("Literal", "context.auth");
    }

    @Test
    void orderedCandidateSourcesAndPredicatesExecuteThroughTheBuiltApplication() {
        prepareRejectedCurrentCandidate();
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent()).contains("\"value\": \"fallback\"");
    }

    @Test
    void builtPreviewHighlightsTheCandidateThatSuppliedTheValue() {
        prepareRejectedCurrentCandidate();
        page.locator(".candidate.selected-candidate").waitFor();

        assertThat(page.locator(".candidate.selected-candidate").getAttribute("data-candidate-index"))
                .isEqualTo("1");
    }

    @Test
    void candidatePredicatePickerExcludesFalliblePrimitives() {
        addTrigger();
        exampleContext().fill("{\"payload\":{\"value\":\"12\"}}");
        exampleContext().press("Tab");
        addManipulationAfterSelected();
        choosePath("field", "payload", "value");
        page.locator("#value-0-when-new-predicate-search").fill("to number");

        assertThat(page.locator("[data-candidate-index='0'] [data-add-predicate='text.to-number']").count())
                .isZero();
    }

    @Test
    void reorderedCandidatesRemainInOrderAfterReload() {
        addTrigger();
        addManipulationAfterSelected();
        addLiteralCandidate("\"fallback\"");
        page.locator("[data-move-candidate='1'][data-direction='-1']").click();
        waitForText("#build-state", "Built");

        page.reload();
        waitForText("#build-state", "Built");
        page.locator(".step-node").click();

        assertThat(page.locator("#value-0-option").inputValue()).isEqualTo("literal");
    }

    @Test
    void reorderedCandidateKeepsItsInvalidJsonDraftAtTheNewPosition() {
        addTrigger();
        addManipulationAfterSelected();
        addLiteralCandidate("null");
        page.locator("#value-1-literal-value").fill("[");
        page.locator("#value-1-literal-value").press("Tab");

        page.locator("[data-move-candidate='1'][data-direction='-1']").click();

        assertThat(page.locator("#value-0-literal-value").inputValue()).isEqualTo("[");
        assertThat(page.locator("#inspector").textContent()).contains("Value must be valid JSON.");
    }

    @Test
    void invalidLiteralJsonRemainsVisibleForCorrection() {
        addTrigger();
        addManipulationAfterSelected();
        page.locator("#value-0-option").selectOption("literal");
        waitForText("#build-state", "Built");

        page.locator("#value-0-literal-value").fill("{\"unfinished\":");
        page.locator("#value-0-literal-value").press("Tab");

        assertThat(page.locator("#value-0-literal-value").inputValue()).isEqualTo("{\"unfinished\":");
        assertThat(page.locator("#inspector").textContent()).contains("Value must be valid JSON.");
    }

    @Test
    void invalidLaterCandidateJsonRemainsVisibleForCorrection() {
        addTrigger();
        addManipulationAfterSelected();
        page.locator("#value-candidate-search").fill("literal");
        page.locator("[data-add-candidate='literal']").click();
        waitForText("#build-state", "Built");

        page.locator("#value-1-literal-value").fill("[");
        page.locator("#value-1-literal-value").press("Tab");

        assertThat(page.locator("#value-1-literal-value").inputValue()).isEqualTo("[");
        assertThat(page.locator("#inspector").textContent()).contains("Value must be valid JSON.");
    }

    @Test
    void leavingLiteralModeDiscardsItsInvalidDraft() {
        addTrigger();
        addManipulationAfterSelected();
        page.locator("#value-0-option").selectOption("literal");
        waitForText("#build-state", "Built");
        page.locator("#value-0-literal-value").fill("[");
        page.locator("#value-0-literal-value").press("Tab");

        page.locator("#value-0-option").selectOption("current");
        waitForText("#build-state", "Built");
        page.locator("#value-0-option").selectOption("literal");
        waitForText("#build-state", "Built");

        assertThat(page.locator("#value-0-literal-value").inputValue()).isEqualTo("null");
        assertThat(page.locator("#inspector").textContent()).doesNotContain("Value must be valid JSON.");
    }

    @Test
    void removingACandidateDiscardsItsInvalidDraft() {
        addTrigger();
        addManipulationAfterSelected();
        page.locator("#value-candidate-search").fill("literal");
        page.locator("[data-add-candidate='literal']").click();
        waitForText("#build-state", "Built");
        page.locator("#value-1-literal-value").fill("[");
        page.locator("#value-1-literal-value").press("Tab");

        page.locator("[data-remove-candidate='1']").click();
        waitForText("#build-state", "Built");
        page.locator("#value-candidate-search").fill("literal");
        page.locator("[data-add-candidate='literal']").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("#value-1-literal-value").inputValue()).isEqualTo("null");
        assertThat(page.locator("#inspector").textContent()).doesNotContain("Value must be valid JSON.");
    }

    @Test
    void invalidLiteralJsonIsNotPersisted() {
        addTrigger();
        addManipulationAfterSelected();
        page.locator("#value-0-option").selectOption("literal");
        page.locator("#value-0-literal-value").fill("\"kept\"");
        page.locator("#value-0-literal-value").press("Tab");
        waitForText("#build-state", "Built");

        page.locator("#value-0-literal-value").fill("[");
        page.locator("#value-0-literal-value").press("Tab");
        page.reload();
        waitForText("#build-state", "Built");
        page.locator(".step-node").first().click();

        assertThat(page.locator("#value-0-literal-value").inputValue()).isEqualTo("\"kept\"");
        assertThat(page.locator("#inspector").textContent()).doesNotContain("Value must be valid JSON.");
    }

    @Test
    void invalidLiteralDraftSurvivesAnotherBuildWithoutPreviewingOldData() {
        addTrigger();
        addManipulationAfterSelected();
        page.locator("#value-0-option").selectOption("literal");
        waitForText("#build-state", "Built");
        page.locator("#value-0-literal-value").fill("[");
        page.locator("#value-0-literal-value").press("Tab");

        choosePath("field", "result");
        waitForText("#build-state", "Built");

        assertThat(page.locator("#value-0-literal-value").inputValue()).isEqualTo("[");
        assertThat(page.locator("#inspector").textContent()).contains("Value must be valid JSON.");
        assertThat(page.locator("#preview-values").textContent()).isEmpty();
    }

    @Test
    void cancellingAFieldPathChoiceKeepsTheCurrentField() {
        addTrigger();
        addManipulationAfterSelected();
        waitForText("#build-state", "Built");
        final String current = page.locator("#field-path").textContent();

        page.locator("#field-path").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='payload']").click();
        page.locator("#cancel-path").click();

        assertThat(page.locator(".path-browser").count()).isZero();
        assertThat(page.locator("#field-path").textContent()).isEqualTo(current);
        assertThat(page.locator("#build-state").textContent()).isEqualTo("Built");
    }

    @Test
    void cancellingASourcePathChoiceKeepsTheCurrentSource() {
        addTrigger();
        addManipulationAfterSelected();
        page.locator("#value-0-option").selectOption("field");
        waitForText("#build-state", "Built");
        final String current = page.locator("#value-0-source-path").textContent();

        page.locator("#value-0-source-path").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='payload']").click();
        page.locator("#cancel-path").click();

        assertThat(page.locator(".path-browser").count()).isZero();
        assertThat(page.locator("#value-0-source-path").textContent()).isEqualTo(current);
        assertThat(page.locator("#build-state").textContent()).isEqualTo("Built");
    }

    @Test
    void laterOperationCanSelectAFieldCreatedByAnEarlierOperation() {
        addTrigger();
        addManipulationAfterSelected();
        chooseCustomField("auth");
        page.locator("#value-0-option").selectOption("literal");
        page.locator("#value-0-literal-value").fill("{\"token\":\"railix\"}");
        page.locator("#value-0-literal-value").press("Tab");
        addManipulationAfterSelected();
        assertThat(page.locator(".path-browser").count()).isZero();
        page.locator("#field-path").click();
        page.locator("[data-path-depth='0']").click();
        assertThat(page.locator("[data-path-part='result']").count())
                .as(page.locator("#inspector").textContent()).isEqualTo(1);
        page.locator("[data-path-part='result']").click();
        page.locator("#apply-path").click();
        page.locator("#value-0-option").selectOption("field");
        page.locator("#value-0-source-path").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='auth']").click();

        assertThat(page.locator("[data-path-part='token']").count()).isEqualTo(1);
    }

    @Test
    void adjacentFieldManipulationsRemainSeparateUntilTheUserGroupsThem() {
        createLowercaseJourney();

        assertThat(page.locator("[data-select-step]").count()).isEqualTo(2);
        assertThat(page.locator("[data-select-group]").count()).isZero();
    }

    @Test
    void creatingAGroupPersistsOnlyCreatorMetadataWithoutRestarting() throws Exception {
        createLowercaseJourney();
        final String project = Files.readString(directory.resolve("project.json"));
        final String pid = applicationPid();

        groupSteps(0, 1);

        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(project);
        assertThat(Files.readString(directory.resolve("railix.creator.json")))
                .contains("\"groups\":[{", "\"occurrences\":[{", "\"steps\":{")
                .doesNotContain("\"members\"");
        assertThat(applicationPid()).isEqualTo(pid);
    }

    @Test
    void groupedRangeRendersAsOneCollapsedNode() {
        createLowercaseJourney();

        groupSteps(0, 1);

        assertThat(page.locator("[data-select-group]").count()).isEqualTo(1);
        assertThat(page.locator("[data-select-step]").count()).isZero();
        assertThat(page.locator("[data-select-group]").textContent()).contains("2 Steps");
    }

    @Test
    void openingAndClosingAGroupShowsItsFlatSteps() {
        createLowercaseJourney();
        groupSteps(0, 1);

        page.locator("[data-select-group]").click();
        page.locator("#open-group").click();

        assertThat(page.locator("#close-group").count()).isEqualTo(1);
        assertThat(page.locator("[data-select-step]").count()).isEqualTo(2);

        page.locator("#close-group").click();

        assertThat(page.locator("[data-select-group]").count()).isEqualTo(1);
    }

    @Test
    void deletingAGroupPreservesEveryFunctionalStep() throws Exception {
        createLowercaseJourney();
        groupSteps(0, 1);
        final String project = Files.readString(directory.resolve("project.json"));
        final String pid = applicationPid();

        page.locator("[data-select-group]").click();
        clickAndWaitForCreatorSave(() -> page.locator("#delete-group").click());

        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(project);
        assertThat(page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).creator.groups.length
                """)).isEqualTo(0);
        assertThat(page.locator("[data-select-step]").count()).isEqualTo(2);
        assertThat(applicationPid()).isEqualTo(pid);
    }

    @Test
    void groupNamePersistsAcrossReloadWithoutRestarting() {
        createLowercaseJourney();
        groupSteps(0, 1);
        final String pid = applicationPid();

        presentationName().fill("Normalize result");
        clickAndWaitForCreatorSave(() -> presentationName().press("Tab"));
        page.reload();
        waitForText("#build-state", "Built");

        assertThat(page.locator("[data-select-group] h2").textContent()).isEqualTo("Normalize result");
        assertThat(applicationPid()).isEqualTo(pid);
    }

    @Test
    void groupColorPersistsAcrossReloadWithoutRestarting() {
        createLowercaseJourney();
        groupSteps(0, 1);
        final String pid = applicationPid();

        openInspectorTab("appearance");
        page.locator("#presentation-color").fill("#A10F22");
        clickAndWaitForCreatorSave(() -> page.locator("#presentation-color").press("Tab"));
        page.reload();
        waitForText("#build-state", "Built");

        assertThat(page.locator("[data-select-group]").getAttribute("style"))
                .contains("--node-accent:#A10F22");
        assertThat(applicationPid()).isEqualTo(pid);
    }

    @Test
    void customGroupIconIsEmbeddedAndPortableAcrossReload() {
        createLowercaseJourney();
        groupSteps(0, 1);
        final String pid = applicationPid();

        openInspectorTab("appearance");
        page.locator("#choose-icon").click();
        page.locator("#icon-search").fill("custom");
        clickAndWaitForCreatorSave(() -> page.locator("[data-select-icon='custom:bolt']").click());
        page.reload();
        waitForText("#build-state", "Built");

        assertThat(page.locator("[data-select-group] .flow-icon").getAttribute("src"))
                .isEqualTo("data:image/svg+xml;base64,PHN2Zy8+");
        assertThat(page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).creator.groups[0].icon.data
                """)).isEqualTo("PHN2Zy8+");
        assertThat(applicationPid()).isEqualTo(pid);
    }

    @Test
    void cancellingGroupRangeCreationLeavesMetadataUnchanged() {
        createLowercaseJourney();
        final String before = creatorMetadata();

        page.locator("[data-inspector-mode='groups']").click();
        page.locator("#new-group").click();
        page.locator("[data-select-step]").first().click();
        page.locator("#cancel-group-draft").click();

        assertThat(creatorMetadata()).isEqualTo(before);
        assertThat(page.locator("[data-select-group]").count()).isZero();
        assertThat(page.locator("#cancel-group-draft").count()).isZero();
    }

    @Test
    void nestedGroupOpensInsideItsParent() {
        prepareNestedGroup();

        assertThat(page.locator("[data-select-group]").count()).isEqualTo(1);
        assertThat(page.locator("[data-select-step]").count()).isEqualTo(1);
        page.locator("#open-group").click();

        assertThat(page.locator("[data-select-step]").count()).isEqualTo(1);
        assertThat(page.locator("#project-title").textContent()).isEqualTo("Field Manipulation");
        assertThat(page.locator(".flow-scope-header").textContent())
                .contains("Step Group", "Field Manipulation");
    }

    @Test
    void deletingParentGroupReparentsNestedGroupAndPreservesSteps() throws Exception {
        prepareNestedGroup();
        final String project = Files.readString(directory.resolve("project.json"));
        final String pid = applicationPid();
        page.locator("#close-group").click();
        page.locator("[data-select-group]").click();

        clickAndWaitForCreatorSave(() -> page.locator("#delete-group").click());

        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(project);
        assertThat(page.locator("[data-select-group]").count()).isEqualTo(1);
        assertThat(page.locator("[data-select-step]").count()).isEqualTo(1);
        assertThat(page.evaluate("""
                async () => {
                  const groups = (await (await fetch('/api/project')).json()).creator.groups;
                  return groups.length + ':' + groups[0].occurrences[0].parent;
                }
                """)).isEqualTo("1:null");
        assertThat(applicationPid()).isEqualTo(pid);
    }

    @Test
    void sharedStepListsEveryExplicitEditChoice() {
        prepareSharedGroup();

        openSharedStep(1, 0);

        assertThat(page.locator("[data-shared-action]").allTextContents())
                .containsExactly("Update all", "Detach this", "Create variant", "Cancel");
    }

    @Test
    void updateAllCopiesTheEditedStepPresentationToEveryOccurrence() {
        prepareSharedGroup();
        final String pid = applicationPid();
        openSharedStep(1, 0);

        page.locator("[data-shared-action='all']").click();
        presentationName().fill("Shared value");
        clickAndWaitForCreatorSave(() -> presentationName().press("Tab"));

        assertThat(page.evaluate("""
                async () => Object.values((await (await fetch('/api/project')).json()).creator.steps)
                  .filter(step => step.name === 'Shared value').length
                """)).isEqualTo(2);
        assertThat(applicationPid()).isEqualTo(pid);
    }

    @Test
    void addedOccurrenceMapsLogicalSlotsInVisibleFlowOrder() {
        prepareSharedGroup();

        assertThat(page.evaluate("""
                async () => {
                  const occurrences = (await (await fetch('/api/project')).json())
                    .creator.groups[0].occurrences;
                  return Object.keys(occurrences[0].steps)
                    .map(slot => occurrences[0].steps[slot] + ':' + occurrences[1].steps[slot])
                    .sort().join(',');
                }
                """)).isEqualTo("one:three,two:four");
    }

    @Test
    void rejectedCreatorMetadataHighlightsOnlyTheAffectedGroup() {
        prepareSharedGroup();
        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  project.nodes = project.nodes.filter(node => node.id !== 'one');
                  project.links = project.links.filter(link => !link.from.startsWith('one.'));
                  project.links.find(link => link.from === 'command.next').to = 'two';
                  return (await fetch('/api/project', {
                    method: 'POST', headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify(project)
                  })).status;
                }
                """)).isEqualTo(200);
        page.locator("[data-select-group]").first().click();

        presentationName().fill("Drifted group");
        final var response = page.waitForResponse(candidate ->
                        candidate.url().endsWith("/api/creator")
                                && "POST".equals(candidate.request().method()),
                () -> presentationName().press("Tab"));

        assertThat(response.status()).isEqualTo(422);
        page.locator("[data-select-group].issue-error").waitFor();
        assertThat(page.locator(".app-node.issue-error").count()).isZero();
        assertThat(page.locator("[data-select-group].issue-error").count()).isEqualTo(1);
        openInspectorTab("inspect");
        assertThat(page.locator("#inspector").textContent()).contains("CREATOR_OCCURRENCE_STEP_UNKNOWN");
    }

    @Test
    void updateAllCopiesTheEditedStepInputToEveryOccurrence() {
        prepareSharedGroup();
        openSharedStep(1, 0);

        page.locator("[data-shared-action='all']").click();
        page.locator("#value-0-literal-value").fill("7");
        page.locator("#value-0-literal-value").press("Tab");
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).project.nodes
                  .filter(node => node.id === 'one' || node.id === 'three')
                  .map(node => node.inputs.value[0].inputs.literal).join(',')
                """)).isEqualTo("7,7");
    }

    @Test
    void updateAllInsertsOneFlatStepIntoEveryOccurrence() {
        prepareSharedGroup();
        openSharedStep(1, 0);

        page.locator("[data-shared-action='all']").click();
        addManipulationAfterSelected();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const payload = await (await fetch('/api/project')).json();
                  const occurrences = payload.creator.groups[0].occurrences;
                  const known = new Set(['app', 'command', 'one', 'two', 'three', 'four']);
                  const slot = Object.keys(occurrences[0].steps)
                    .find(candidate => !known.has(occurrences[0].steps[candidate]));
                  const first = occurrences[0].steps[slot];
                  const second = occurrences[1].steps[slot];
                  const links = payload.project.links;
                  return [
                    occurrences[0].steps[slot] !== occurrences[1].steps[slot],
                    payload.project.nodes.find(node => node.id === first)?.use,
                    payload.project.nodes.find(node => node.id === second)?.use,
                    links.find(link => link.from === 'one.next')?.to === first,
                    links.find(link => link.from === first + '.next')?.to === 'two',
                    links.find(link => link.from === 'three.next')?.to === second,
                    links.find(link => link.from === second + '.next')?.to === 'four'
                  ].join('|');
                }
                """)).isEqualTo("true|railix.field-manipulation|railix.field-manipulation|true|true|true|true");
    }

    @Test
    void updateAllDeletesTheMatchingFlatStepFromEveryOccurrence() {
        prepareSharedGroup();
        openSharedStep(1, 0);

        page.locator("[data-shared-action='all']").click();
        page.locator("#delete-step").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  return [
                    project.nodes.some(node => node.id === 'one'),
                    project.nodes.some(node => node.id === 'three'),
                    project.links.find(link => link.from === 'command.next')?.to,
                    project.links.find(link => link.from === 'two.next')?.to
                  ].join('|');
                }
                """)).isEqualTo("false|false|two|four");
    }

    @Test
    void detachThisRemovesOnlyTheSelectedOccurrenceFromTheSharedGroup() {
        prepareSharedGroup();
        openSharedStep(1, 0);

        clickAndWaitForCreatorSave(() -> page.locator("[data-shared-action='detach']").click());

        assertThat(page.locator("[data-select-step]").count()).isEqualTo(2);
        assertThat(page.evaluate("""
                async () => (await (await fetch('/api/project')).json())
                  .creator.groups[0].occurrences.length
                """)).isEqualTo(1);
    }

    @Test
    void createVariantMovesOnlyTheSelectedOccurrenceIntoANewGroup() {
        prepareSharedGroup();
        openSharedStep(1, 0);

        clickAndWaitForCreatorSave(() -> page.locator("[data-shared-action='variant']").click());

        final String remote = String.valueOf(page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).creator.groups
                  .map(group => group.occurrences.length).join(',')
                """));

        assertThat(remote).isEqualTo("1,1");
    }

    @Test
    void cancelSharedEditReturnsToTheGroupWithoutChangingMetadata() {
        prepareSharedGroup();
        final String before = creatorMetadata();
        openSharedStep(1, 0);

        page.locator("[data-shared-action='cancel']").click();

        assertThat(page.locator(".inspector-heading").textContent()).contains("Group");
        assertThat(creatorMetadata()).isEqualTo(before);
    }

    @Test
    void oneOrdinaryStepDoesNotPretendToBeAGroup() {
        addTrigger();
        addManipulationAfterSelected();

        assertThat(page.locator(".step-node .operation-stack").count()).isZero();
        assertThat(page.locator(".operation-tabs").count()).isZero();
        assertThat(page.locator("#delete-step").textContent()).isEqualTo("Delete Step");
    }

    @Test
    void ordinaryStepDoesNotExposeGenericOutcomeRouting() {
        addTrigger();
        addManipulationAfterSelected();

        assertThat(page.locator(".outcome-routes").count()).isZero();
        assertThat(page.locator("#inspector").textContent())
                .doesNotContain("Outcome routes", "Explicit branches", "Ends flow");
        waitForText("#build-state", "Built");
        assertThat(page.locator("#build-state").textContent()).isEqualTo("Built");
    }

    @Test
    void falliblePrimitiveRemainsInsideOneLinearStep() {
        createFallibleNumberJourney("not-a-number");

        assertThat(page.locator(".step-node").count()).isEqualTo(1);
        assertThat(page.locator(".step-node .operation-stack").count()).isZero();
        assertThat(page.locator(".outcome-routes").count()).isZero();
        assertThat(page.locator("#build-state").textContent()).isEqualTo("Built");
    }

    @Test
    void deletingALinearFallibleStepPreservesTheTrigger() {
        createFallibleNumberJourney("12.5");

        page.locator("#delete-step").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator(".step-node").count()).isZero();
        selectTrigger();
        page.locator(".run-result").waitFor();
        assertThat(runResult()).isEqualTo(RailixValue.nullValue());
    }

    @Test
    void emptyPercentileRemainsOneLinearStepWithoutRoutes() {
        createPercentileJourney("[]", "");

        assertThat(page.locator(".step-node").count()).isEqualTo(1);
        assertThat(page.locator(".outcome-routes").count()).isZero();
        selectTrigger();
        page.locator(".run-result").waitFor();
        assertThat(runResult()).isEqualTo(RailixValue.nullValue());
    }

    @Test
    void selectedStringFieldFiltersAndAddsCompatiblePrimitive() {
        createResultJourney();
        selectTrigger();
        addManipulationAfterSelected();
        choosePath("field", "payload", "text");
        page.locator("#steps-search").fill("lower");

        assertThat(page.locator("#steps-options [data-add-nested]").count()).isEqualTo(1);
        page.locator("#steps-options [data-add-nested='text.lowercase']").click();
        waitForText("#build-state", "Built");
        assertThat(page.locator(".step-node.selected").textContent()).contains("Lowercase");
    }

    @Test
    void unaryValueStepIsOfferedAsAStandaloneGraphNodeWhenInputIsAvailable() {
        addTrigger();

        page.locator("#add-next-step").click();
        page.locator("#step-search").fill("to json");

        assertThat(page.locator("[data-add-step='value.to-json']").count()).isEqualTo(1);
    }

    @Test
    void numberFloorCanBeSelectedAndExecuted() {
        createPrimitiveResult("{\"payload\":{\"value\":1.9}}", "floor", "number.floor");

        assertThat(page.locator(".run-result").textContent()).contains("\"result\": 1");
    }

    @Test
    void numberCeilCanBePreviewedAndExecuted() {
        createPrimitivePreviewAndResult(
                "{\"payload\":{\"value\":1.2}}",
                "ceil",
                "number.ceil",
                "2"
        );

        assertThat(runResult()).isEqualTo(RailixValue.number(2));
    }

    @Test
    void numberRoundCanBePreviewedAndExecuted() {
        createPrimitivePreviewAndResult(
                "{\"payload\":{\"value\":-1.5}}",
                "round",
                "number.round",
                "-2"
        );

        assertThat(runResult()).isEqualTo(RailixValue.number(-2));
    }

    @Test
    void numberAbsoluteValueCanBePreviewedAndExecuted() {
        createPrimitivePreviewAndResult(
                "{\"payload\":{\"value\":-12.5}}",
                "abs",
                "number.abs",
                "12.5"
        );

        assertThat(runResult()).isEqualTo(RailixValue.number(new BigDecimal("12.5")));
    }

    @Test
    void numberNegateCanBePreviewedAndExecuted() {
        createPrimitivePreviewAndResult(
                "{\"payload\":{\"value\":12.5}}",
                "negate",
                "number.negate",
                "-12.5"
        );

        assertThat(runResult()).isEqualTo(RailixValue.number(new BigDecimal("-12.5")));
    }

    @Test
    void numberNegateMaximumMagnitudeCanBePreviewedAndExecuted() {
        final String magnitude = "1".repeat(1_024);
        createPrimitivePreviewAndResult(
                "{\"payload\":{\"value\":" + magnitude + "}}",
                "negate",
                "number.negate",
                "-" + magnitude
        );

        assertThat(runResult())
                .isEqualTo(RailixValue.number(new BigDecimal("-" + magnitude)));
    }

    @Test
    void numberSignCanBePreviewedAndExecuted() {
        createPrimitivePreviewAndResult(
                "{\"payload\":{\"value\":-0.1}}",
                "sign",
                "number.sign",
                "-1"
        );

        assertThat(runResult()).isEqualTo(RailixValue.number(-1));
    }

    @ParameterizedTest(name = "{0} required configuration, preview, and run")
    @CsvSource({
            "number.greater-than, 6, 5, true",
            "number.greater-or-equal, 5.00, 5, true",
            "number.less-than, 6, 5, false",
            "number.less-or-equal, 6, 5, false"
    })
    void numberComparisonCanBeConfiguredPreviewedAndExecuted(
            final String primitive,
            final String value,
            final String than,
            final boolean expected
    ) {
        preparePrimitiveSearch(
                "{\"payload\":{\"value\":" + value + "}}",
                primitive
        );
        page.locator("#steps-options [data-add-nested='" + primitive + "']").click();
        waitForText("#build-state", "Built");

        final var config = page.locator("#steps-0-than-value");
        assertThat(config.inputValue()).isEqualTo("0");

        config.fill(than);
        config.press("Tab");
        waitForText("#build-state", "Built");
        page.locator("[data-preview-stage='0']").waitFor();
        assertThat(page.locator("[data-preview-stage='0']").textContent())
                .isEqualTo(Boolean.toString(expected));

        selectTrigger();
        page.locator(".run-result").waitFor();
        assertThat(runResult()).isEqualTo(RailixValue.bool(expected));
    }

    @ParameterizedTest(name = "{0} is hidden for a string source")
    @ValueSource(strings = {
            "number.greater-than",
            "number.greater-or-equal",
            "number.less-than",
            "number.less-or-equal"
    })
    void numberComparisonIsHiddenForAStringSource(final String primitive) {
        preparePrimitiveSearch("{\"payload\":{\"value\":\"6\"}}", primitive);

        assertThat(page.locator("#steps-options [data-add-nested='" + primitive + "']").count()).isZero();
    }

    @Test
    void textContainsStringConfigurationCanBeEditedPreviewedAndExecuted() {
        preparePrimitiveSearch("{\"payload\":{\"value\":\"Nano Railix\"}}", "contains");
        page.locator("#steps-options [data-add-nested='text.contains']").click();
        waitForText("#build-state", "Built");

        final var config = page.locator("#steps-0-needle-value");
        assertThat(config.count()).isEqualTo(1);
        assertThat(config.inputValue()).isEmpty();

        config.fill("Rail");
        config.press("Tab");
        waitForText("#build-state", "Built");
        page.locator("[data-preview-stage='0']").waitFor();
        assertThat(page.locator("[data-preview-stage='0']").textContent()).isEqualTo("true");

        selectTrigger();
        page.locator(".run-result").waitFor();
        assertThat(runResult()).isEqualTo(RailixValue.bool(true));
    }

    @Test
    void textContainsStartsWithItsBuildableEmptyStringDefault() {
        preparePrimitiveSearch("{\"payload\":{\"value\":\"Nano Railix\"}}", "contains");
        page.locator("#steps-options [data-add-nested='text.contains']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-preview-stage='0']").waitFor();

        assertThat(page.locator("#steps-0-needle-value").inputValue()).isEmpty();
        assertThat(page.locator("[data-preview-stage='0']").textContent()).isEqualTo("true");
    }

    @ParameterizedTest(name = "{0} can be configured, previewed, and executed")
    @CsvSource({
            "text.starts-with, prefix, Nano",
            "text.ends-with, suffix, Railix"
    })
    void textBoundaryCanBeConfiguredPreviewedAndExecuted(
            final String primitive,
            final String input,
            final String boundary
    ) {
        preparePrimitiveSearch("{\"payload\":{\"value\":\"Nano Railix\"}}", primitive);
        page.locator("#steps-options [data-add-nested='" + primitive + "']").click();
        waitForText("#build-state", "Built");

        final var config = page.locator("#steps-0-" + input + "-value");
        assertThat(config.inputValue()).isEmpty();

        config.fill(boundary);
        config.press("Tab");
        waitForText("#build-state", "Built");
        page.locator("[data-preview-stage='0']").waitFor();
        assertThat(page.locator("[data-preview-stage='0']").textContent()).isEqualTo("true");

        selectTrigger();
        page.locator(".run-result").waitFor();
        assertThat(runResult()).isEqualTo(RailixValue.bool(true));
    }

    @ParameterizedTest(name = "{0} is hidden for a number source")
    @ValueSource(strings = {"text.starts-with", "text.ends-with"})
    void textBoundaryIsHiddenForANumberSource(final String primitive) {
        preparePrimitiveSearch("{\"payload\":{\"value\":7}}", primitive);

        assertThat(page.locator("#steps-options [data-add-nested='" + primitive + "']").count()).isZero();
    }

    @Test
    void valueEqualsCanBeConfiguredPreviewedAndExecuted() {
        preparePrimitiveSearch(
                "{\"payload\":{\"value\":{\"answer\":42}}}",
                "equals"
        );
        page.locator("#steps-options [data-add-nested='value.equals']").click();
        waitForText("#build-state", "Built");

        final var expected = page.locator("#steps-0-expected-value");
        assertThat(expected.inputValue()).isEqualTo("null");

        expected.fill("{\"answer\":42}");
        expected.press("Tab");
        waitForText("#build-state", "Built");
        page.locator("[data-preview-stage='0']").waitFor();
        assertThat(page.locator("[data-preview-stage='0']").textContent()).isEqualTo("true");

        selectTrigger();
        page.locator(".run-result").waitFor();
        assertThat(runResult()).isEqualTo(RailixValue.bool(true));
    }

    @Test
    void textUppercaseCanBePreviewedAndExecuted() {
        createPrimitivePreviewAndResult(
                "{\"payload\":{\"value\":\"stra\\u00dfe\"}}",
                "uppercase",
                "text.uppercase",
                "\"STRASSE\""
        );

        assertThat(runResult()).isEqualTo(RailixValue.string("STRASSE"));
    }

    @Test
    void textTrimCanBePreviewedAndExecuted() {
        createPrimitivePreviewAndResult(
                "{\"payload\":{\"value\":\"\\u2003Railix\\u2003\"}}",
                "trim",
                "text.trim",
                "\"Railix\""
        );

        assertThat(runResult()).isEqualTo(RailixValue.string("Railix"));
    }

    @Test
    void textNormalizeSpaceCanBePreviewedAndExecuted() {
        createPrimitivePreviewAndResult(
                "{\"payload\":{\"value\":\" railix\\t creator \"}}",
                "normalize space",
                "text.normalize-space",
                "\"railix creator\""
        );

        assertThat(runResult()).isEqualTo(RailixValue.string("railix creator"));
    }

    @Test
    void textNormalizeNfcCanBePreviewedAndExecuted() {
        createPrimitivePreviewAndResult(
                "{\"payload\":{\"value\":\"e\\u0301\"}}",
                "normalize nfc",
                "text.normalize-nfc",
                "\"\u00e9\""
        );

        assertThat(runResult()).isEqualTo(RailixValue.string("\u00e9"));
    }

    @Test
    void textLengthCanBePreviewedAndExecuted() {
        createPrimitivePreviewAndResult(
                "{\"payload\":{\"value\":\"A\\ud83d\\ude80B\"}}",
                "length",
                "text.length",
                "3"
        );

        assertThat(runResult()).isEqualTo(RailixValue.number(3));
    }

    @Test
    void textIsEmptyCanBePreviewedAndExecuted() {
        createPrimitivePreviewAndResult(
                "{\"payload\":{\"value\":\"\"}}",
                "is empty",
                "text.is-empty",
                "true"
        );

        assertThat(runResult()).isEqualTo(RailixValue.bool(true));
    }

    @Test
    void listIsEmptyCanBePreviewedAndExecuted() {
        createPrimitivePreviewAndResult(
                "{\"payload\":{\"value\":[]}}",
                "is empty",
                "list.is-empty",
                "true"
        );

        assertThat(runResult()).isEqualTo(RailixValue.bool(true));
    }

    @Test
    void listIsEmptyIsHiddenForAnObjectSource() {
        preparePrimitiveSearch("{\"payload\":{\"value\":{}}}", "list.is-empty");

        assertThat(page.locator("#steps-options [data-add-nested='list.is-empty']").count()).isZero();
    }

    @Test
    void booleanToTextCanBePreviewedAndExecuted() {
        createPrimitivePreviewAndResult(
                "{\"payload\":{\"value\":true}}",
                "to text",
                "boolean.to-text",
                "\"true\""
        );

        assertThat(runResult()).isEqualTo(RailixValue.string("true"));
    }

    @Test
    void booleanToTextIsHiddenForAStringSource() {
        preparePrimitiveSearch("{\"payload\":{\"value\":\"true\"}}", "boolean.to-text");

        assertThat(page.locator("#steps-options [data-add-nested='boolean.to-text']").count()).isZero();
    }

    @Test
    void booleanToNumberCanBePreviewedAndExecuted() {
        createPrimitivePreviewAndResult(
                "{\"payload\":{\"value\":true}}",
                "to number",
                "boolean.to-number",
                "1"
        );

        assertThat(runResult()).isEqualTo(RailixValue.number(1));
    }

    @Test
    void booleanToNumberIsHiddenForAStringSource() {
        preparePrimitiveSearch("{\"payload\":{\"value\":\"true\"}}", "boolean.to-number");

        assertThat(page.locator("#steps-options [data-add-nested='boolean.to-number']").count()).isZero();
    }

    @Test
    void listReverseCanBePreviewedAndExecuted() {
        createPrimitivePreviewAndResult(
                "{\"payload\":{\"value\":[1,2]}}",
                "reverse",
                "list.reverse",
                "[2,1]"
        );

        assertThat(runResult()).isEqualTo(RailixValue.array(java.util.List.of(
                RailixValue.number(2),
                RailixValue.number(1)
        )));
    }

    @Test
    void listReverseIsHiddenForAnObjectSource() {
        preparePrimitiveSearch("{\"payload\":{\"value\":{}}}", "list.reverse");

        assertThat(page.locator("#steps-options [data-add-nested='list.reverse']").count()).isZero();
    }

    @Test
    void numberToTextCanBePreviewedAndExecuted() {
        createPrimitivePreviewAndResult(
                "{\"payload\":{\"value\":1.2300}}",
                "to text",
                "number.to-text",
                "\"1.23\""
        );

        assertThat(runResult()).isEqualTo(RailixValue.string("1.23"));
    }

    @Test
    void numberToTextIsHiddenForAStringSource() {
        preparePrimitiveSearch("{\"payload\":{\"value\":\"1\"}}", "number.to-text");

        assertThat(page.locator("#steps-options [data-add-nested='number.to-text']").count()).isZero();
    }

    @Test
    void numberToTextIsOfferedAtTheCanonicalNumberLimit() {
        preparePrimitiveSearch("{\"payload\":{\"value\":1e1023}}", "number.to-text");

        assertThat(page.locator("#steps-options [data-add-nested='number.to-text']").count()).isEqualTo(1);
    }

    @Test
    void numberToTextIsHiddenBeyondTheCanonicalNumberLimit() {
        preparePrimitiveSearch("{\"payload\":{\"value\":1e1024}}", "number.to-text");

        assertThat(page.locator("#steps-options [data-add-nested='number.to-text']").count()).isZero();
    }

    @Test
    void valueWrapListCanBePreviewedAndExecuted() {
        createPrimitivePreviewAndResult(
                "{\"payload\":{\"value\":null}}",
                "wrap list",
                "value.wrap-list",
                "[null]"
        );

        assertThat(runResult()).isEqualTo(RailixValue.array(java.util.List.of(RailixValue.nullValue())));
    }

    @Test
    void valueToJsonCanBePreviewedAndExecuted() {
        createPrimitivePreviewAndResult(
                "{\"payload\":{\"value\":{\"z\":2,\"a\":1}}}",
                "to json",
                "value.to-json",
                "\"{\\\"a\\\":1,\\\"z\\\":2}\""
        );

        assertThat(runResult()).isEqualTo(RailixValue.string("{\"a\":1,\"z\":2}"));
    }

    @Test
    void valueToJsonIsOfferedAtItsCanonicalJsonByteLimit() {
        prepareLiteralPrimitiveSearch(canonicalJsonBytes(RailixData.DEFAULT_MAX_SOURCE_BYTES), "value.to-json");

        assertThat(page.locator("#steps-options [data-add-nested='value.to-json']").count()).isEqualTo(1);
    }

    @Test
    void valueToJsonIsHiddenBeyondItsCanonicalJsonByteLimit() {
        prepareLiteralPrimitiveSearch(canonicalJsonBytes(RailixData.DEFAULT_MAX_SOURCE_BYTES + 1), "value.to-json");

        assertThat(page.locator("#steps-options [data-add-nested='value.to-json']").count()).isZero();
    }

    @Test
    void primitiveRefinementHidesAValueWithoutRequiredDepthHeadroom() {
        String value = "null";
        for (int depth = 0; depth < 64; depth++) {
            value = "[" + value + "]";
        }
        preparePrimitiveSearch("{\"payload\":{\"value\":" + value + "}}", "wrap list");

        assertThat(page.locator("#steps-options [data-add-nested='value.wrap-list']").count()).isZero();
    }

    @Test
    void primitiveRefinementChecksEveryTriggerExample() {
        String deep = "null";
        for (int depth = 0; depth < 64; depth++) {
            deep = "[" + deep + "]";
        }
        addTrigger();
        exampleContext().fill("{\"payload\":{\"value\":true}}");
        exampleContext().press("Tab");
        page.locator("#add-example").click();
        exampleContext().fill("{\"payload\":{\"value\":" + deep + "}}");
        exampleContext().press("Tab");
        waitForText("#build-state", "Not built");
        page.locator("[data-select-example='0']").click();
        addManipulationAfterSelected();
        choosePath("field", "result");
        page.locator("#value-0-option").selectOption("field");
        choosePath("value-0-source", "payload", "value");
        page.locator("#steps-search").fill("wrap list");

        assertThat(page.locator("#steps-options [data-add-nested='value.wrap-list']").count()).isZero();
    }

    @Test
    void canonicalRefinementKeepsTheUiResponsiveBeyondTheGlobalDepth() {
        String value = "null";
        for (int depth = 0; depth < 65; depth++) {
            value = "[" + value + "]";
        }
        prepareLiteralPrimitiveSearch(value, "list.reverse");

        assertThat(page.locator("#steps-options").isVisible()).isTrue();
        assertThat(page.locator("#steps-options [data-add-nested='list.reverse']").count()).isZero();
    }

    @Test
    void falliblePrimitiveDoesNotCreateAGraphOutcomeOrBlockTheBuild() {
        preparePrimitiveSearch("{\"payload\":{\"value\":\"12.5\"}}", "to number");

        page.locator("#steps-options [data-add-nested='text.to-number']").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("#inspector").textContent())
                .doesNotContain("PROJECT_NODE_OUTCOME_CONNECTION_REQUIRED", "Outcome routes");
        assertThat(page.locator("[data-add-outcome]").count()).isZero();
    }

    @Test
    void falliblePrimitiveValidInputFollowsNextAndReturnsANumber() {
        createFallibleNumberJourney("12.5");

        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent())
                .contains("\"result\": 12.5", "\"id\": \"text.to-number\"", "\"outcome\": \"ok\"");
    }

    @Test
    void falliblePrimitiveInvalidPreviewLeavesTheTargetUnchangedAndContinues() {
        createFallibleNumberJourney("not-a-number");

        page.locator(".step-node").first().click();
        page.locator("[data-preview-stage='0']").waitFor();

        assertThat(page.locator("[data-preview-stage='0']").textContent()).isEqualTo("invalid");
        assertThat(page.locator("[data-preview-stage='0']").getAttribute("data-preview-status"))
                .isEqualTo("invalid");
        selectTrigger();
        page.locator(".run-result").waitFor();
        assertThat(page.locator(".run-result").textContent())
                .contains("\"result\": null", "\"id\": \"text.to-number\"", "\"outcome\": \"invalid\"");
    }

    @Test
    void falliblePrimitivePersistsOnlyThePrimaryGraphRoute() {
        createFallibleNumberJourney("not-a-number");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const convert = project.nodes.find(node =>
                    node.inputs?.steps?.some(step => step.use === 'text.to-number'));
                  return project.links.filter(link => link.from.startsWith(convert.id + '.'))
                    .map(link => link.from.substring(convert.id.length) + ':' + link.to).join('|');
                }
                """)).isEqualTo(".next:end");
    }

    @Test
    void percentileShowsItsDefaultConfigurationWithoutGraphRoutes() {
        preparePrimitiveSearch("{\"payload\":{\"value\":[4,1,3,2]}}", "percentile");

        page.locator("#steps-options [data-add-nested='list.percentile']").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("#steps-0-percentile-value").inputValue())
                .isEqualTo("95");
        assertThat(page.locator("#steps-0-percentile-value").getAttribute("min"))
                .isEqualTo("0");
        assertThat(page.locator("#steps-0-percentile-value").getAttribute("max"))
                .isEqualTo("100");
        assertThat(page.locator("[data-add-outcome]").count()).isZero();
    }

    @Test
    void percentileConfigurationExecutesACustomNearestRank() {
        createPercentileJourney("[4,1,3,2]", "50");

        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent())
                .contains("\"result\": 2", "\"id\": \"list.percentile\"", "\"outcome\": \"ok\"");
    }

    @Test
    void percentileConfigurationPersistsAcrossReload() {
        createPercentileJourney("[4,1,3,2]", "50");

        page.reload();
        waitForText("#build-state", "Built");
        page.locator(".step-node").first().click();

        assertThat(page.locator("#steps-0-percentile-value").inputValue())
                .isEqualTo("50");
    }

    @Test
    void percentileConfigurationPreservesAnExactFractionalRank() {
        final String percentile = "50.0000000000000000001";
        createPercentileJourney("[1,2]", percentile);

        page.reload();
        waitForText("#build-state", "Built");
        page.locator(".step-node").first().click();

        assertThat(page.locator("#steps-0-percentile-value").inputValue())
                .isEqualTo(percentile);
        selectTrigger();
        page.locator(".run-result").waitFor();
        assertThat(page.locator(".run-result").textContent()).contains("\"result\": 2");
    }

    @Test
    void percentileConfigurationOutsideTheRangeShowsCompilerFeedback() {
        preparePrimitiveSearch("{\"payload\":{\"value\":[1]}}", "percentile");
        page.locator("#steps-options [data-add-nested='list.percentile']").click();

        page.locator("#steps-0-percentile-value").fill("100.1");
        page.locator("#steps-0-percentile-value").press("Tab");
        waitForText("#build-state", "Not built");

        assertThat(page.locator("#inspector").textContent())
                .contains("PROJECT_INPUT_RANGE_INVALID", "from 0 through 100");
    }

    @Test
    void percentileConfigurationMovesWithItsPrimitiveInvocation() {
        preparePrimitiveSearch("{\"payload\":{\"value\":[1,2]}}", "percentile");
        page.locator("#steps-options [data-add-nested='list.percentile']").click();
        page.locator("#steps-0-percentile-value").fill("50.5");
        page.locator("#steps-0-percentile-value").press("Tab");
        page.locator("#steps-search").fill("floor");
        page.locator("#steps-options [data-add-nested='number.floor']").click();

        page.locator("[data-move-nested='0'][data-direction='1']").click();

        assertThat(page.locator("#steps-1-percentile-value").inputValue())
                .isEqualTo("50.5");
    }

    @Test
    void percentileEmptyPreviewLeavesTheTargetUnchangedAndContinues() {
        createPercentileJourney("[]", "");
        page.locator(".step-node").first().click();
        page.locator("[data-preview-stage='0']").waitFor();

        assertThat(page.locator("[data-preview-stage='0']").textContent()).isEqualTo("empty");
        selectTrigger();
        page.locator(".run-result").waitFor();
        assertThat(page.locator(".run-result").textContent())
                .contains("\"result\": null", "\"outcome\": \"empty\"");
    }

    @Test
    void percentileInvalidPreviewLeavesTheTargetUnchangedAndContinues() {
        createPercentileJourney("[1,\"two\"]", "");
        page.locator(".step-node").first().click();
        page.locator("[data-preview-stage='0']").waitFor();

        assertThat(page.locator("[data-preview-stage='0']").textContent()).isEqualTo("invalid");
        selectTrigger();
        page.locator(".run-result").waitFor();
        assertThat(page.locator(".run-result").textContent())
                .contains("\"result\": null", "\"outcome\": \"invalid\"");
    }

    @Test
    void booleanNotCanBeSelectedAndExecuted() {
        createPrimitiveResult("{\"payload\":{\"value\":true}}", "not", "boolean.not");

        assertThat(page.locator(".run-result").textContent()).contains("\"result\": false");
    }

    @Test
    void listSizeCanBeSelectedAndExecuted() {
        createPrimitiveResult("{\"payload\":{\"value\":[1,2]}}", "size", "list.size");

        assertThat(page.locator(".run-result").textContent()).contains("\"result\": 2");
    }

    @Test
    void utcMillisValidationCanBeSelectedAndExecuted() {
        createPrimitiveResult("{\"payload\":{\"value\":0}}", "utc", "date.is-utc-millis");

        assertThat(page.locator(".run-result").textContent()).contains("\"result\": true");
    }

    @Test
    void numberPrimitiveIsHiddenForAStringField() {
        createResultJourney();
        selectTrigger();
        addManipulationAfterSelected();
        choosePath("field", "payload", "text");
        page.locator("#steps-search").fill("floor");

        assertThat(page.locator("#steps-options [data-add-nested]").count()).isZero();
    }

    @Test
    void primitiveSearchShowsReadableNameAndStableFamilyId() {
        preparePrimitiveSearch("{\"payload\":{\"value\":0}}", "utc");

        assertThat(page.locator("#steps-options [data-add-nested='date.is-utc-millis']").textContent())
                .contains("Is UTC Millis", "date.is-utc-millis");
    }

    @Test
    void primitiveSearchUsesThePreviousPrimitiveOutputShape() {
        preparePrimitiveSearch("{\"payload\":{\"value\":0}}", "utc");
        page.locator("#steps-options [data-add-nested='date.is-utc-millis']").click();
        page.locator("#steps-search").fill("not");

        assertThat(page.locator("#steps-options [data-add-nested='boolean.not']").count()).isEqualTo(1);
    }

    @Test
    void primitiveSearchHidesAnOperationMadeIncompatibleByThePreviousPrimitive() {
        preparePrimitiveSearch("{\"payload\":{\"value\":0}}", "utc");
        page.locator("#steps-options [data-add-nested='date.is-utc-millis']").click();
        page.locator("#steps-search").fill("floor");

        assertThat(page.locator("#steps-options [data-add-nested='number.floor']").count()).isZero();
    }

    @Test
    void compatiblePrimitiveChainExecutesInTheBuiltApplication() {
        preparePrimitiveSearch("{\"payload\":{\"value\":0}}", "utc");
        page.locator("#steps-options [data-add-nested='date.is-utc-millis']").click();
        page.locator("#steps-search").fill("not");
        page.locator("#steps-options [data-add-nested='boolean.not']").click();
        waitForText("#build-state", "Built");
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent()).contains("\"result\": false");
    }

    @Test
    void selectedFieldOperationAutomaticallyPreviewsItsActualSourceValue() {
        createLowercaseJourney();
        page.locator("#preview-source").waitFor();

        assertThat(page.locator("#preview-source").textContent()).isEqualTo("\"Hello RAILIX\"");
    }

    @Test
    void selectedFieldOperationAutomaticallyPreviewsActualPrimitiveOutputsInOrder() {
        prepareDateNotChain(2);
        page.locator("[data-preview-stage='2']").waitFor();

        assertThat(page.locator("[data-preview-stage]").allTextContents())
                .containsExactly("true", "false", "true");
    }

    @Test
    void selectedFieldOperationPreviewShowsTheFirstAvailableCandidate() {
        prepareMissingResultCandidate();
        page.locator("#steps-search").fill("lower");
        page.locator("#steps-options [data-add-nested='text.lowercase']").click();
        waitForText("#build-state", "Built");
        page.locator("#preview-source").waitFor();

        assertThat(page.locator("#preview-source").textContent()).isEqualTo("\"RAILIX\"");
    }

    @Test
    void selectingALaterFieldOperationPreviewsTheEarlierBuiltOutputAsItsSource() {
        createLowercaseJourney();
        page.locator("[data-select-step]").nth(1).click();
        page.locator("#preview-source").waitFor();

        assertThat(page.locator("#preview-source").textContent()).isEqualTo("\"hello railix\"");
    }

    @Test
    void rollingBuildRefreshesTheSelectedFieldOperationPreview() {
        prepareMissingResultCandidate();
        page.locator("#steps-search").fill("lower");
        page.locator("#steps-options [data-add-nested='text.lowercase']").click();
        waitForText("#build-state", "Built");
        page.locator("#preview-source").waitFor();

        page.locator("#value-1-literal-value").fill("\"CREATOR\"");
        page.locator("#value-1-literal-value").press("Tab");
        waitForText("#build-state", "Built");
        waitForText("#preview-source", "\"CREATOR\"");

        assertThat(page.locator("#preview-source").textContent()).isEqualTo("\"CREATOR\"");
    }

    @Test
    void editingTheSelectedOperationImmediatelyClearsItsBuiltPreview() {
        createLowercaseJourney();
        page.locator("#preview-source").waitFor();

        page.locator("[data-remove-nested='0']").click();

        assertThat(page.locator("#preview-source").count()).isZero();
    }

    @Test
    void selectingATriggerWhilePreviewStartsCannotRestoreStaleValues() {
        createLowercaseJourney();
        page.locator("#preview-source").waitFor();
        page.evaluate("""
                () => {
                  const request = window.fetch.bind(window);
                  window.__previewStarted = false;
                  window.__releasePreview = null;
                  window.fetch = async (...arguments) => {
                    const response = await request(...arguments);
                    if (String(arguments[0]).startsWith("/api/preview/")) {
                      window.__previewStarted = true;
                      await new Promise(resolve => window.__releasePreview = resolve);
                    }
                    return response;
                  };
                }
                """);

        page.locator("[data-select-step]").first().click();
        page.waitForFunction("window.__previewStarted === true");
        page.locator(".trigger-node").click();
        page.evaluate("window.__releasePreview()");
        page.evaluate("""
                () => new Promise(resolve =>
                  requestAnimationFrame(() => requestAnimationFrame(resolve))
                )
                """);

        assertThat(page.locator("#preview-source").count()).isZero();
    }

    @Test
    void stoppedBuiltApplicationShowsPreviewUnavailable() {
        createLowercaseJourney();
        page.locator("#preview-source").waitFor();
        final long pid = ((Number) page.evaluate(
                "async () => (await (await fetch('/api/application')).json()).pid"
        )).longValue();
        stopProcess(pid);

        page.locator("[data-select-step]").first().click();
        page.locator(".preview-error").waitFor();

        assertThat(page.locator(".preview-error").textContent()).contains("Preview unavailable");
    }

    @Test
    void unavailableEarlierStepCannotReuseALaterStepsPreviewPaths() {
        addTrigger();
        exampleContext().fill("{\"payload\":{}}");
        exampleContext().press("Tab");
        addManipulationAfterSelected();
        chooseCustomPath("payload", "first");
        page.locator("#value-0-option").selectOption("literal");
        page.locator("#value-0-literal-value").fill("\"one\"");
        page.locator("#value-0-literal-value").press("Tab");
        addManipulationAfterSelected();
        chooseCustomPath("payload", "later");
        page.locator("#value-0-option").selectOption("literal");
        page.locator("#value-0-literal-value").fill("\"two\"");
        page.locator("#value-0-literal-value").press("Tab");
        waitForText("#build-state", "Built");
        page.locator("#preview-source").waitFor();
        final long pid = ((Number) page.evaluate(
                "async () => (await (await fetch('/api/application')).json()).pid"
        )).longValue();
        stopProcess(pid);

        page.locator("[data-select-step]").first().click();
        page.locator(".preview-error").waitFor();
        page.locator("#field-path").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='payload']").click();

        assertThat(page.locator(".path-choices").textContent()).doesNotContain("later");
    }

    @Test
    void laterOperationOffersPrimitiveForPreviousPrimitiveOutputShape() {
        prepareOperationAfterListSize();
        page.locator("#steps-search").fill("floor");

        assertThat(page.locator("#steps-options [data-add-nested='number.floor']").count()).isEqualTo(1);
    }

    @Test
    void laterOperationExecutesAgainstPreviousPrimitiveOutput() {
        prepareOperationAfterListSize();
        page.locator("#steps-search").fill("floor");
        page.locator("#steps-options [data-add-nested='number.floor']").click();
        waitForText("#build-state", "Built");
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent()).contains("\"id\": \"number.floor\"");
    }

    @Test
    void movingPrimitiveIntoAnIncompatibleOrderShowsTheCompilerDiagnostic() {
        prepareDateNotChain(1);

        page.locator("[data-move-nested='1'][data-direction='-1']").click();
        waitForText("#build-state", "Not built");

        assertThat(page.locator("#inspector").textContent())
                .contains("PROJECT_NESTED_INPUT_INCOMPATIBLE");
    }

    @Test
    void restoringPrimitiveOrderRestoresTheRunningApplication() {
        prepareDateNotChain(1);
        page.locator("[data-move-nested='1'][data-direction='-1']").click();
        waitForText("#build-state", "Not built");

        page.locator("[data-move-nested='1'][data-direction='-1']").click();
        waitForText("#build-state", "Built");
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent()).contains("\"result\": false");
    }

    @Test
    void removingMiddlePrimitivePreservesTheRemainingExecutionOrder() {
        prepareDateNotChain(2);

        page.locator("[data-remove-nested='1']").click();
        waitForText("#build-state", "Built");
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent()).contains("\"result\": false");
    }

    @Test
    void primitiveCanBeRemovedWithoutRemovingItsFieldOperation() {
        createLowercaseJourney();
        page.locator(".step-node").first().click();

        page.locator("[data-remove-nested='0']").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("#inspector").textContent()).contains("Steps", "Lowercase");
        assertThat(page.locator(".program-list .nested-step").count()).isZero();
    }

    @Test
    void deletingSingleResultStepRebuildsWithTheSilentTriggerDefault() {
        createResultJourney();
        page.locator(".step-node").click();

        page.locator("#delete-step").click();
        waitForText("#build-state", "Built");
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent()).contains("\"result\": null");
    }

    @Test
    void laterStringCandidateOffersCompatiblePrimitiveWhenCurrentFieldIsMissing() {
        prepareMissingResultCandidate();
        page.locator("#steps-search").fill("lower");

        assertThat(page.locator("#steps-options [data-add-nested='text.lowercase']").count()).isEqualTo(1);
    }

    @Test
    void laterCandidateExecutesThroughTheRunningApplicationWhenCurrentFieldIsMissing() {
        prepareMissingResultCandidate();
        page.locator("#steps-search").fill("lower");
        page.locator("#steps-options [data-add-nested='text.lowercase']").click();
        waitForText("#build-state", "Built");
        addManipulationAfterSelected();
        choosePath("field", "result");
        page.locator("#value-0-option").selectOption("field");
        choosePath("value-0-source", "payload", "value");
        waitForText("#build-state", "Built");
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent())
                .contains("\"result\": \"railix\"");
    }

    @Test
    void removingTheLastAvailableCandidateFollowsTheExplicitMissingOutcomeAndTriggerDefault() {
        prepareMissingResultCandidate();
        page.locator("#steps-search").fill("lower");
        page.locator("#steps-options [data-add-nested='text.lowercase']").click();
        waitForText("#build-state", "Built");

        page.locator("[data-remove-candidate='1']").click();
        waitForText("#build-state", "Built");
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent()).contains("\"result\": null");
    }

    @Test
    void deletingTriggerReturnsToBuiltAppOnlyProject() {
        createResultJourney();
        selectTrigger();

        page.locator("#delete-step").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator(".trigger-node").count()).isZero();
        assertThat(page.locator("#flow-count").textContent()).isEqualTo("0 flows");
    }

    @Test
    void exampleContextPopulatesTheFirstGuidedFieldChoices() {
        addTrigger();
        exampleContext().fill("""
                {
                  "payload": {"person": {"name": "Ada RAILIX"}},
                  "header": {"authorization": "Bearer token"}
                }
                """);
        exampleContext().press("Tab");
        addManipulationAfterSelected();
        page.locator("#field-path").click();
        page.locator("[data-path-depth='0']").click();

        assertThat(page.locator(".path-choices").textContent()).contains("payload", "header");
    }

    @Test
    void triggerExamplesAreAnEditableList() {
        addTrigger();

        assertThat(examplePayload().inputValue()).isEqualTo("[]");
        assertThat(exampleContext().inputValue()).isBlank();
        page.locator("#add-example").click();

        assertThat(page.locator("[data-select-example]").count()).isEqualTo(4);
        assertThat(page.locator("#example-name").inputValue()).isEqualTo("example-4");

        page.locator("#example-name").fill("empty-input");
        page.locator("#example-name").press("Tab");
        exampleContext().fill("{\"payload\":{}}");
        exampleContext().press("Tab");
        waitForText("#build-state", "Built");

        page.locator("[data-select-example='0']").click();
        assertThat(page.locator("#example-name").inputValue()).isEqualTo("no-arguments");

        page.locator("[data-select-example='3']").click();
        assertThat(page.locator("#example-name").inputValue()).isEqualTo("empty-input");
        assertThat(exampleContext().inputValue()).contains("\"payload\": {}");
    }

    @Test
    void cliTriggerStartsWithSeveralUsefulExamplePayloads() {
        addTrigger();
        openInspectorTab("examples");

        assertThat(page.locator("[data-select-example]").allTextContents())
                .containsExactly("no-arguments", "one-argument", "multiple-arguments");
        page.locator("[data-select-example='1']").click();
        assertThat(examplePayload().inputValue()).isEqualTo("[\n  \"railix\"\n]");
        page.locator("[data-select-example='2']").click();
        assertThat(examplePayload().inputValue()).isEqualTo("[\n  \"hello\",\n  \"railix\"\n]");
    }

    @Test
    void guidedFieldChoicesUseTheUnionOfExamplesWithoutMergingTheirValues() {
        addTrigger();
        exampleContext().fill("{\"payload\":{\"text\":\"Railix\"}}");
        exampleContext().press("Tab");
        page.locator("#add-example").click();
        exampleContext().fill("{\"payload\":{\"count\":2}}");
        exampleContext().press("Tab");
        waitForText("#build-state", "Built");
        assertThat(exampleContext().inputValue())
                .contains("\"count\": 2")
                .doesNotContain("\"text\"");

        addManipulationAfterSelected();
        page.locator("#field-path").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='payload']").click();

        assertThat(page.locator(".path-choices").textContent()).contains("text", "count");
    }

    @Test
    void conflictingExampleShapesDoNotCreateSyntheticPrimitiveCompatibility() {
        addTrigger();
        exampleContext().fill("{\"payload\":{\"value\":\"Railix\"}}");
        exampleContext().press("Tab");
        page.locator("#add-example").click();
        exampleContext().fill("{\"payload\":{\"value\":7}}");
        exampleContext().press("Tab");
        waitForText("#build-state", "Built");

        addManipulationAfterSelected();
        choosePath("field", "payload", "value");

        assertThat(page.locator("#steps-options [data-add-nested='value.equals']").count())
                .isEqualTo(1);
        assertThat(page.locator("#steps-options [data-add-nested='value.wrap-list']").count())
                .isEqualTo(1);
        assertThat(page.locator("#steps-options [data-add-nested='value.to-json']").count())
                .isEqualTo(1);
        assertThat(page.locator("#steps-options [data-add-nested='text.lowercase']").count())
                .isZero();
        assertThat(page.locator("#steps-options [data-add-nested='number.floor']").count())
                .isZero();
    }

    @Test
    void allTriggerExamplesRunAsIndependentCases() {
        addTrigger();
        openInspectorTab("examples");
        page.locator("#add-example").click();
        page.locator("#example-name").fill("second");
        page.locator("#example-name").press("Tab");
        waitForText("#build-state", "Built");

        openInspectorTab("inspect");
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent())
                .contains("\"name\": \"no-arguments\"", "\"name\": \"second\"")
                .contains("\"exit_code\": 0", "\"result\": null");
    }

    @Test
    void compatiblePrimitiveStepsAreScrollableBeforeSearchAndShrinkWhileTyping() {
        preparePrimitiveSearch("{\"payload\":{\"value\":\" Railix \"}}", "");

        assertThat(page.locator("#steps-options [data-add-nested]").count()).isGreaterThan(1);
        assertThat(page.locator("#steps-options").textContent())
                .contains("Lowercase", "Uppercase", "Trim");

        page.locator("#steps-search").fill("uppercase");

        assertThat(page.locator("#steps-options [data-add-nested]").count()).isEqualTo(1);
        assertThat(page.locator("#steps-options").textContent()).contains("Uppercase");
    }

    @Test
    void exampleContextPreservesAnExactDecimalAcrossReload() {
        preparePrimitiveSearch(
                "{\"payload\":{\"value\":0.10000000000000001}}",
                ""
        );
        waitForText("#build-state", "Built");

        page.reload();
        waitForText("#build-state", "Built");
        selectTrigger();

        assertThat(exampleContext().inputValue())
                .contains("\"value\": 0.10000000000000001");
    }

    @Test
    void previewPreservesAnExactDecimalFromTheBuiltApplication() {
        preparePrimitiveSearch(
                "{\"payload\":{\"value\":0.10000000000000001}}",
                ""
        );
        waitForText("#build-state", "Built");
        page.locator("#preview-source").waitFor();

        assertThat(page.locator("#preview-source").textContent())
                .isEqualTo("0.10000000000000001");
    }

    @Test
    void builtExampleShowsTheEffectiveValueWithoutConfigurationPlumbing() {
        addTrigger();
        addManipulationAfterSelected();
        addLiteralCandidate("\"fallback\"");
        waitForText("#build-state", "Built");
        page.locator("#preview-source").waitFor();

        assertThat(page.locator("#preview-values").textContent())
                .contains("Value")
                .doesNotContain("Field", "Candidates", "Source");
    }

    @Test
    void guidedPathBuilderRevealsTheSampleOneLevelAtATime() {
        addTrigger();
        exampleContext().fill("""
                {
                  "payload": {"person": {"name": "Ada RAILIX"}},
                  "header": {"authorization": "Bearer token"}
                }
                """);
        exampleContext().press("Tab");
        addManipulationAfterSelected();
        page.locator("#field-path").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='payload']").click();

        assertThat(page.locator(".path-choices").textContent())
                .contains("person")
                .doesNotContain("authorization");
    }

    @Test
    void guidedTargetBuilderCreatesANestedFieldWithoutPathSyntax() {
        addTrigger();
        addManipulationAfterSelected();
        chooseCustomField("auth", "token");
        page.locator("#value-0-option").selectOption("literal");
        page.locator("#value-0-literal-value").fill("\"railix\"");
        page.locator("#value-0-literal-value").press("Tab");

        assertThat(page.locator(".step-node").textContent()).contains("context.auth.token");
    }

    @Test
    void guidedArrayIndexPathExecutesInTheBuiltApplication() {
        createLiteralResult(
                "\"Ada\"",
                new Object[]{"payload", "items", 0, "name"},
                "payload", "items", "[0]", "name"
        );

        assertThat(page.locator(".run-result").textContent()).contains("\"result\": \"Ada\"");
    }

    @Test
    void fieldNameContainingADotExecutesAsOneTypedSegment() {
        createLiteralResult(
                "\"Ada\"",
                new Object[]{"payload", "full.name"},
                "payload", "full.name"
        );

        assertThat(page.locator(".run-result").textContent()).contains("\"result\": \"Ada\"");
    }

    @Test
    void numericFieldNameExecutesAsAFieldRatherThanAnArrayIndex() {
        createLiteralResult(
                "\"zero\"",
                new Object[]{"payload", "0"},
                "payload", "0"
        );

        assertThat(page.locator(".run-result").textContent()).contains("\"result\": \"zero\"");
    }

    @Test
    void literalValuePreservesAnExactDecimalInTheBuiltApplication() {
        createLiteralResult(
                "0.10000000000000001",
                new Object[]{"payload", "decimal"},
                "payload", "decimal"
        );

        assertThat(page.locator(".run-result").textContent())
                .contains("\"result\": 0.10000000000000001");
    }

    @Test
    void runtimePrefixFieldIsNotMistakenForReservedRuntime() {
        addTrigger();
        addManipulationAfterSelected();
        chooseCustomField("runtimeX");
        page.locator("#value-0-option").selectOption("literal");
        page.locator("#value-0-literal-value").fill("{}");
        page.locator("#value-0-literal-value").press("Tab");
        addManipulationAfterSelected();
        page.locator("#field-path").click();
        page.locator("[data-path-depth='0']").click();

        page.locator("[data-path-part='runtimeX']").waitFor();
        assertThat(page.locator("[data-path-part='runtimeX']").count()).isEqualTo(1);
    }

    @Test
    void objectTargetOffersAFieldSegmentControl() {
        openFieldBuilder("{\"payload\":{\"person\":{\"name\":\"Ada\"}}}", "payload", "person");

        assertThat(page.locator("#append-path-field").count()).isEqualTo(1);
    }

    @Test
    void objectTargetDoesNotOfferAnArrayIndexControl() {
        openFieldBuilder("{\"payload\":{\"person\":{\"name\":\"Ada\"}}}", "payload", "person");

        assertThat(page.locator("#append-path-index").count()).isZero();
    }

    @Test
    void arrayTargetOffersAnArrayIndexControl() {
        openFieldBuilder("{\"payload\":{\"people\":[{\"name\":\"Ada\"}]}}", "payload", "people");

        assertThat(page.locator("#append-path-index").count()).isEqualTo(1);
    }

    @Test
    void arrayPathBuilderPresentsIndexesAsAlternativeSegments() {
        openFieldBuilder("{\"payload\":{\"people\":[null,\"Ada\"]}}", "payload", "people");

        assertThat(page.locator(".path-choices-label").textContent()).isEqualTo("Choose one array index");
        assertThat(page.locator("#append-path-index").textContent()).isEqualTo("Use index");
        assertThat(page.locator(".path-browser").textContent()).doesNotContain("Add item");
    }

    @Test
    void arrayTargetDoesNotOfferAFieldSegmentControl() {
        openFieldBuilder("{\"payload\":{\"people\":[{\"name\":\"Ada\"}]}}", "payload", "people");

        assertThat(page.locator("#append-path-field").count()).isZero();
    }

    @Test
    void scalarTargetCannotBeExtended() {
        openFieldBuilder("{\"payload\":{\"name\":\"Ada\"}}", "payload", "name");

        assertThat(page.locator(".path-create").count()).isZero();
    }

    @Test
    void sourcePathCannotInventNewSegments() {
        addTrigger();
        addManipulationAfterSelected();
        choosePath("field", "result");
        page.locator("#value-0-option").selectOption("field");
        page.locator("#value-0-source-path").click();
        page.locator("[data-path-depth='0']").click();

        assertThat(page.locator(".path-create").count()).isZero();
    }

    @Test
    void defaultedTriggerResultIsSuggestedAsATarget() {
        openFieldBuilder("{\"payload\":{}}");

        assertThat(page.locator("[data-path-part='result']").count()).isEqualTo(1);
    }

    @Test
    void defaultNullTriggerResultIsOfferedAsAnExplicitSource() {
        addTrigger();
        addManipulationAfterSelected();
        choosePath("field", "result");
        page.locator("#value-0-option").selectOption("field");
        page.locator("#value-0-source-path").click();
        page.locator("[data-path-depth='0']").click();

        assertThat(page.locator("[data-path-part='result']").count()).isEqualTo(1);
    }

    @Test
    void explicitNullDoesNotAdvanceToALaterCandidateForTypeFiltering() {
        addTrigger();
        exampleContext().fill("{\"payload\":{\"value\":null}}");
        exampleContext().press("Tab");
        addManipulationAfterSelected();
        choosePath("field", "payload", "value");
        addLiteralCandidate("\"RAILIX\"");
        page.locator("#steps-search").fill("lower");

        assertThat(page.locator("#steps-options [data-add-nested='text.lowercase']").count()).isZero();
    }

    @Test
    void blankTargetFieldCannotBeAdded() {
        openFieldBuilder("{\"payload\":{}}");

        assertThat(page.locator("#append-path-field").isDisabled()).isTrue();
    }

    @Test
    void negativeArrayIndexCannotBeAdded() {
        openFieldBuilder("{\"payload\":{\"people\":[]}}", "payload", "people");
        page.locator("#new-path-index").fill("-1");

        assertThat(page.locator("#append-path-index").isDisabled()).isTrue();
    }

    @Test
    void reservedRuntimeTargetCannotBeAdded() {
        openFieldBuilder("{\"payload\":{}}");
        page.locator("#new-path-field").fill("runtime");

        assertThat(page.locator("#append-path-field").isDisabled()).isTrue();
    }

    @Test
    void sparseArrayPreviewIncludesRuntimeFilledIndexes() {
        openSourceAfterSparseArrayWrite();

        assertThat(page.locator(".path-choice").count()).isEqualTo(3);
    }

    @Test
    void sparseArrayPreviewTypesRuntimeFilledIndexAsNull() {
        openSourceAfterSparseArrayWrite();

        assertThat(page.locator("[data-path-part='[0]']").textContent()).contains("null");
    }

    @Test
    void automaticExampleRunUsesTheBuiltChildAndShowsTheResultContext() {
        createLowercaseJourney();
        selectTrigger();

        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent())
                .contains("\"status\": \"succeeded\"", "\"result\": \"hello railix\"", "\"exit_code\": 0");
    }

    @Test
    void completedRunCannotOverwriteANewerDraft() {
        createLowercaseJourney();
        selectTrigger();
        page.evaluate("""
                window.actualRailixFetch = window.fetch.bind(window);
                window.railixRunDelayed = false;
                window.fetch = (input, options) => String(input).includes("/api/run/")
                  && !window.railixRunDelayed ? new Promise(resolve => {
                      window.railixRunDelayed = true;
                      window.releaseRailixRun = () => window.actualRailixFetch(input, options).then(resolve);
                    })
                  : window.actualRailixFetch(input, options);
                """);

        exampleContext().fill("{\"payload\":{\"text\":\"Delayed\"}}");
        exampleContext().press("Tab");
        page.waitForFunction("() => typeof window.releaseRailixRun === 'function'");
        presentationName().fill("newer-draft");
        presentationName().press("Tab");
        page.evaluate("window.releaseRailixRun()");

        assertThat(page.locator(".run-result").count()).isZero();
    }

    @Test
    void completedExampleRunPreservesTheOpenStepPicker() {
        addTrigger();
        waitForText("#build-state", "Built");
        page.evaluate("""
                window.actualRailixFetch = window.fetch.bind(window);
                window.railixRunDelayed = false;
                window.fetch = (input, options) => String(input).includes("/api/run/")
                  && !window.railixRunDelayed ? new Promise(resolve => {
                      window.railixRunDelayed = true;
                      window.releaseRailixRun = () => window.actualRailixFetch(input, options).then(resolve);
                    })
                  : window.actualRailixFetch(input, options);
                """);
        openInspectorTab("examples");
        examplePayload().fill("[\"delayed\"]");
        examplePayload().press("Tab");
        waitForText("#build-state", "Built");
        page.waitForFunction("() => typeof window.releaseRailixRun === 'function'");
        openInspectorTab("inspect");
        page.locator("#add-next-step").click();
        page.locator("#step-search").fill("json");
        assertThat(page.locator("[data-add-step='value.to-json']").count()).isEqualTo(1);

        page.evaluate("window.releaseRailixRun()");
        page.locator(".run-result").waitFor();

        assertThat(page.locator("#step-search").inputValue()).isEqualTo("json");
        assertThat(page.locator("#step-search").evaluate("input => input === document.activeElement"))
                .isEqualTo(true);
        page.locator("[data-add-step='value.to-json']").click();
        waitForText("#build-state", "Built");
        page.locator("#preview-source").waitFor();
    }

    @Test
    void invalidExampleDraftCannotPublishOlderRunResult() {
        addTrigger();
        waitForText("#build-state", "Built");
        page.evaluate("""
                window.actualRailixFetch = window.fetch.bind(window);
                window.railixRunDelayed = false;
                window.fetch = (input, options) => String(input).includes("/api/run/")
                  && !window.railixRunDelayed ? new Promise(resolve => {
                      window.railixRunDelayed = true;
                      window.releaseRailixRun = () => window.actualRailixFetch(input, options).then(resolve);
                    })
                  : window.actualRailixFetch(input, options);
                """);

        examplePayload().fill("[\"Delayed\"]");
        examplePayload().press("Tab");
        page.waitForFunction("() => typeof window.releaseRailixRun === 'function'");
        examplePayload().fill("[");
        examplePayload().press("Tab");
        page.evaluate("window.releaseRailixRun()");
        openInspectorTab("inspect");
        page.evaluate("""
                () => new Promise(resolve =>
                  requestAnimationFrame(() => requestAnimationFrame(resolve))
                )
                """);

        assertThat(page.locator(".run-result").count()).isZero();
    }

    @Test
    void obsoletePanelsLifecycleButtonsAndTransportRoutesAreAbsent() {
        assertThat(page.locator(
                ".palette, .diagnostics, #validate, #start, #run, #build, #run-example, #run-all-examples"
        ).count()).isZero();
        assertThat(page.locator("body").textContent())
                .doesNotContain("/api/run", "/v1/run", "instance ", "Changes compile");
    }

    @Test
    void graphLayoutIsDeterministicAcrossReload() {
        createResultJourney();
        final String before = positions();

        page.reload();
        page.locator(".trigger-node").waitFor();
        waitForText("#build-state", "Built");

        assertThat(positions()).isEqualTo(before);
    }

    @Test
    void inspectorFitsDesktopAndMobileViewport() {
        final double viewport = ((Number) page.evaluate("window.innerWidth")).doubleValue();
        final var box = page.locator("#inspector").boundingBox();

        assertThat(box).isNotNull();
        assertThat(box.x).isGreaterThanOrEqualTo(0);
        assertThat(box.x + box.width).isLessThanOrEqualTo(viewport + 0.5);
    }

    private void createResultJourney() {
        addTrigger();
        exampleContext().fill("{\"payload\":{\"text\":\"Hello RAILIX\"}}");
        exampleContext().press("Tab");
        waitForText("#build-state", "Built");
        addManipulationAfterSelected();
        choosePath("field", "result");
        page.locator("#value-0-option").selectOption("field");
        choosePath("value-0-source", "payload", "text");
        waitForText("#build-state", "Built");
    }

    private void createLowercaseJourney() {
        createResultJourney();
        selectTrigger();
        addManipulationAfterSelected();
        choosePath("field", "payload", "text");
        page.locator("#steps-search").fill("lower");
        page.locator("#steps-options [data-add-nested='text.lowercase']").click();
        waitForText("#build-state", "Built");
    }

    private void createPrimitiveResult(
            final String example,
            final String search,
            final String primitive
    ) {
        preparePrimitiveSearch(example, search);
        page.locator("#steps-options [data-add-nested='" + primitive + "']").click();
        waitForText("#build-state", "Built");
        selectTrigger();
        page.locator(".run-result").waitFor();
    }

    private void createPrimitivePreviewAndResult(
            final String example,
            final String search,
            final String primitive,
            final String preview
    ) {
        preparePrimitiveSearch(example, search);
        page.locator("#steps-options [data-add-nested='" + primitive + "']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-preview-stage='0']").waitFor();
        assertThat(page.locator("[data-preview-stage='0']").textContent()).isEqualTo(preview);
        selectTrigger();
        page.locator(".run-result").waitFor();
    }

    private RailixValue runResult() {
        return runResult(0);
    }

    private RailixValue runResult(final int index) {
        final RailixValue.ArrayValue cases = (RailixValue.ArrayValue) (
                (RailixJson.Parsed) RailixJson.parse(page.locator(".run-result").textContent())
        ).value();
        final RailixValue.ObjectValue response = (RailixValue.ObjectValue) (
                (RailixValue.ObjectValue) cases.values().get(index)
        ).values().get("result");
        return ((RailixValue.ObjectValue) response.values().get("context"))
                .values()
                .get("result");
    }

    private void createFallibleNumberJourney(final String value) {
        preparePrimitiveSearch(
                "{\"payload\":{\"value\":\"" + value + "\"}}",
                "to number"
        );
        page.locator("#steps-options [data-add-nested='text.to-number']").click();
        waitForText("#build-state", "Built");
    }

    private void createPercentileJourney(final String values, final String percentile) {
        preparePrimitiveSearch(
                "{\"payload\":{\"value\":" + values + "}}",
                "percentile"
        );
        page.locator("#steps-options [data-add-nested='list.percentile']").click();
        waitForText("#build-state", "Built");
        if (!percentile.isEmpty()) {
            page.locator("#steps-0-percentile-value").fill(percentile);
            page.locator("#steps-0-percentile-value").press("Tab");
        }
        waitForText("#build-state", "Built");
    }

    private void preparePrimitiveSearch(final String example, final String search) {
        addTrigger();
        exampleContext().fill(example);
        exampleContext().press("Tab");
        addManipulationAfterSelected();
        choosePath("field", "result");
        page.locator("#value-0-option").selectOption("field");
        choosePath("value-0-source", "payload", "value");
        page.locator("#steps-search").fill(search);
    }

    private void prepareLiteralPrimitiveSearch(final String value, final String search) {
        addTrigger();
        addManipulationAfterSelected();
        choosePath("field", "result");
        page.locator("#value-0-option").selectOption("literal");
        page.locator("#value-0-literal-value").fill(value);
        page.locator("#value-0-literal-value").press("Tab");
        page.locator("#steps-search").fill(search);
    }

    private static String canonicalJsonBytes(final int bytes) {
        final int fixedBytes = 1_048;
        return "{\"number\":1e1023,\"padding\":\""
                + "a".repeat(bytes - fixedBytes)
                + "\"}";
    }

    private void prepareOperationAfterListSize() {
        addTrigger();
        exampleContext().fill("{\"payload\":{\"value\":[1,2]}}");
        exampleContext().press("Tab");
        addManipulationAfterSelected();
        chooseCustomField("payload", "size");
        page.locator("#value-0-option").selectOption("field");
        choosePath("value-0-source", "payload", "value");
        page.locator("#steps-search").fill("size");
        page.locator("#steps-options [data-add-nested='list.size']").click();
        addManipulationAfterSelected();
        choosePath("field", "result");
        page.locator("#value-0-option").selectOption("field");
        choosePath("value-0-source", "payload", "size");
    }

    private void prepareDateNotChain(final int notCount) {
        preparePrimitiveSearch("{\"payload\":{\"value\":0}}", "utc");
        page.locator("#steps-options [data-add-nested='date.is-utc-millis']").click();
        for (int index = 0; index < notCount; index++) {
            page.locator("#steps-search").fill("not");
            page.locator("#steps-options [data-add-nested='boolean.not']").click();
        }
        waitForText("#build-state", "Built");
    }

    private void prepareRejectedCurrentCandidate() {
        addTrigger();
        exampleContext().fill("{\"payload\":{\"value\":\"\"}}");
        exampleContext().press("Tab");
        addManipulationAfterSelected();
        choosePath("field", "payload", "value");
        page.locator("#value-0-when-new-predicate-search").fill("equals");
        page.locator("[data-candidate-index='0'] [data-add-predicate='value.equals']").click();
        page.locator("#value-0-when-all-0-0-expected-value").fill("\"\"");
        page.locator("#value-0-when-all-0-0-expected-value").press("Tab");
        page.locator("#value-0-when-all-0-search").fill("not");
        page.locator("#value-0-when-all-0-options [data-add-nested='boolean.not']").click();
        addLiteralCandidate("\"fallback\"");
        waitForText("#build-state", "Built");
    }

    private void addLiteralCandidate(final String value) {
        page.locator("#value-candidate-search").fill("literal");
        page.locator("[data-add-candidate='literal']").click();
        final int index = page.locator(".candidate[data-candidate-index]").count() - 1;
        page.locator("#value-" + index + "-literal-value").fill(value);
        page.locator("#value-" + index + "-literal-value").press("Tab");
    }

    private void prepareMissingResultCandidate() {
        addTrigger();
        exampleContext().fill("{\"payload\":{}}");
        exampleContext().press("Tab");
        addManipulationAfterSelected();
        page.locator("#field-path").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='payload']").click();
        page.locator("#new-path-field").fill("value");
        page.locator("#append-path-field").click();
        page.locator("#apply-path").click();
        addLiteralCandidate("\"RAILIX\"");
    }

    private void openSourceAfterSparseArrayWrite() {
        addTrigger();
        exampleContext().fill("{\"payload\":{}}");
        exampleContext().press("Tab");
        addManipulationAfterSelected();
        chooseCustomPath("payload", "people", 2);
        page.locator("#value-0-option").selectOption("literal");
        page.locator("#value-0-literal-value").fill("\"Ada\"");
        page.locator("#value-0-literal-value").press("Tab");
        addManipulationAfterSelected();
        choosePath("field", "result");
        page.locator("#value-0-option").selectOption("field");
        page.locator("#value-0-source-path").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='payload']").click();
        page.locator("[data-path-part='people']").click();
    }

    private void openFieldBuilder(final String example, final String... parts) {
        addTrigger();
        exampleContext().fill(example);
        exampleContext().press("Tab");
        addManipulationAfterSelected();
        page.locator("#field-path").click();
        page.locator("[data-path-depth='0']").click();
        for (final String part : parts) {
            page.locator("[data-path-part='" + part + "']").click();
        }
    }

    private void createLiteralResult(
            final String literal,
            final Object[] target,
            final String... source
    ) {
        addTrigger();
        addManipulationAfterSelected();
        chooseCustomPath(target);
        page.locator("#value-0-option").selectOption("literal");
        page.locator("#value-0-literal-value").fill(literal);
        page.locator("#value-0-literal-value").press("Tab");
        addManipulationAfterSelected();
        choosePath("field", "result");
        page.locator("#value-0-option").selectOption("field");
        choosePath("value-0-source", source);
        waitForText("#build-state", "Built");
        selectTrigger();
        page.locator(".run-result").waitFor();
    }

    private void groupSteps(final int start, final int end) {
        page.locator("[data-inspector-mode='groups']").click();
        page.locator("#new-group").click();
        page.locator("[data-select-step]").nth(start).click();
        clickAndWaitForCreatorSave(() -> page.locator("[data-select-step]").nth(end).click());
    }

    private void prepareSharedGroup() {
        openProject(fourStepProject());
        groupSteps("one", "two");
        page.locator("[data-inspector-mode='groups']").click();
        page.locator("[data-add-occurrence]").click();
        page.locator("[data-select-step='three']").click();
        clickAndWaitForCreatorSave(() -> page.locator("[data-select-step='four']").click());
    }

    private void prepareNestedGroup() {
        createLowercaseJourney();
        groupSteps(0, 1);
        page.locator("[data-select-group]").click();
        page.locator("#open-group").click();
        groupSteps(0, 0);
    }

    private void groupSteps(final String start, final String end) {
        page.locator("[data-inspector-mode='groups']").click();
        page.locator("#new-group").click();
        page.locator("[data-select-step='" + start + "']").click();
        clickAndWaitForCreatorSave(() -> page.locator("[data-select-step='" + end + "']").click());
    }

    private void clickAndWaitForCreatorSave(final Runnable click) {
        final var response = page.waitForResponse(candidate ->
                candidate.url().endsWith("/api/creator")
                        && "POST".equals(candidate.request().method()), click);
        assertThat(response.status()).isEqualTo(200);
    }

    private void openSharedStep(final int occurrence, final int step) {
        final String id = new String[][]{{"one", "two"}, {"three", "four"}}[occurrence][step];
        page.locator("[data-inspector-mode='groups']").click();
        page.locator("[data-manage-occurrence]").nth(occurrence).click();
        page.locator("#open-group").click();
        page.locator("[data-select-step='" + id + "']").click();
    }

    private String creatorMetadata() {
        return String.valueOf(page.evaluate("""
                async () => JSON.stringify((await (await fetch('/api/project')).json()).creator)
                """));
    }

    private static String filterProject() {
        return branchProject("filter-browser", "filter", "railix.filter", """
                [{
                  "option":"field","inputs":{"field":["context","payload","value"]},
                  "when":[{"use":"value.equals","inputs":{"expected":"allow"}}]
                }]
                """);
    }

    private static String choiceProject() {
        return branchProject("choice-browser", "choice", "railix.choice", """
                [[{
                  "option":"field","inputs":{"field":["context","payload","value"]},
                  "when":[{"use":"value.equals","inputs":{"expected":"allow"}}]
                }]]
                """);
    }

    private static String branchProject(
            final String projectId,
            final String branchId,
            final String branchStep,
            final String conditions
    ) {
        return """
                {"format":1,"id":"%s","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"match","payload":[],"context":{"payload":{"value":"allow"}}},
                    {"name":"otherwise","payload":[],"context":{"payload":{"value":"deny"}}}
                  ]},
                  {"id":"%s","use":"%s","inputs":{"conditions":%s}},
                  {"id":"matched","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],
                    "value":[{"option":"literal","inputs":{"literal":"matched"}}],"steps":[]}},
                  {"id":"otherwise","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],
                    "value":[{"option":"literal","inputs":{"literal":"otherwise"}}],"steps":[]}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"%s"},
                  {"from":"%s.match","to":"matched"},
                  {"from":"%s.otherwise","to":"otherwise"},
                  {"from":"matched.next","to":"end"},
                  {"from":"otherwise.next","to":"end"}
                ]}
                """.formatted(
                        projectId,
                        branchId,
                        branchStep,
                        conditions,
                        branchId,
                        branchId,
                        branchId
                );
    }

    private static String deepBranchProject(final int steps) {
        final StringBuilder nodes = new StringBuilder("""
                {"format":1,"id":"deep-branch","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"example","payload":[],"context":{"payload":{}}}]},
                  {"id":"filter","use":"railix.filter","inputs":{"conditions":[]}}
                """);
        final StringBuilder links = new StringBuilder("""
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"filter"},
                  {"from":"filter.match","to":"step-0"},
                  {"from":"filter.otherwise","to":"end"}
                """);
        for (int index = 0; index < steps; index++) {
            nodes.append(",{\"id\":\"step-").append(index)
                    .append("\",\"use\":\"railix.field-manipulation\",\"inputs\":{}}");
            links.append(",{\"from\":\"step-").append(index).append(".next\",\"to\":\"")
                    .append(index + 1 < steps ? "step-" + (index + 1) : "end")
                    .append("\"}");
        }
        return nodes.append(links).append("]}").toString();
    }

    private static String fourStepProject() {
        return """
                {"format":1,"id":"four-steps","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"example","payload":[],"context":{"payload":{}}
                  }]},
                  {"id":"one","use":"railix.field-manipulation","inputs":{
                    "field":["context","payload","one"],
                    "value":[{"option":"literal","inputs":{"literal":1}}],"steps":[]}},
                  {"id":"two","use":"railix.field-manipulation","inputs":{
                    "field":["context","payload","two"],
                    "value":[{"option":"literal","inputs":{"literal":2}}],"steps":[]}},
                  {"id":"three","use":"railix.field-manipulation","inputs":{
                    "field":["context","payload","one"],
                    "value":[{"option":"literal","inputs":{"literal":1}}],"steps":[]}},
                  {"id":"four","use":"railix.field-manipulation","inputs":{
                    "field":["context","payload","two"],
                    "value":[{"option":"literal","inputs":{"literal":2}}],"steps":[]}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"one"},
                  {"from":"one.next","to":"two"},
                  {"from":"two.next","to":"three"},
                  {"from":"three.next","to":"four"},
                  {"from":"four.next","to":"end"}
                ]}
                """;
    }

    private void addTrigger() {
        page.locator("#add-trigger").click();
        page.locator("#step-search").fill("cli");
        page.locator("[data-add-step='railix.trigger.cli']").click();
    }

    private void addFilterAfterTrigger() {
        addTrigger();
        openInspectorTab("inspect");
        page.locator("#add-next-step").click();
        page.locator("#step-search").fill("filter");
        page.locator("[data-add-step='railix.filter']").click();
        waitForText("#build-state", "Built");
    }

    private void addChoiceAfterTrigger() {
        addTrigger();
        openInspectorTab("inspect");
        page.locator("#add-next-step").click();
        page.locator("#step-search").fill("choice");
        page.locator("[data-add-step='railix.choice']").click();
        waitForText("#build-state", "Built");
    }

    private void prepareSizeChoiceMatcher() {
        prepareSizeChoiceMatcher(1);
    }

    private void prepareSizeChoiceMatcher(final int size) {
        openProject(branchProject("choice-size", "choice", "railix.choice", """
                [[{
                  "option":"literal","inputs":{"value":%s},
                  "when":[]
                }]]
        """.formatted(numberList(size))));
        page.locator("[data-select-step='choice']").click();
        final Locator search = page.locator("[data-matcher-group='0'] .condition-transforms [data-step-query]");
        search.fill("size");
        page.locator("[data-matcher-group='0'] .condition-transforms [data-add-nested='list.size']").click();
        waitForText("#build-state", "Not built");
    }

    private void addSizeBounds(final int size) {
        prepareSizeChoiceMatcher(size);
        final Locator search = page.locator("[data-matcher-group='0'] [data-predicate-query]");
        search.fill("gt");
        page.locator("[data-matcher-group='0'] [data-add-predicate='number.greater-than']").click();
        page.locator("[data-condition-predicate='0'] [data-input-json]").fill("1");
        page.locator("[data-condition-predicate='0'] [data-input-json]").press("Tab");
        search.fill("lt");
        page.locator("[data-matcher-group='0'] [data-add-predicate='number.less-than']").click();
        page.locator("[data-condition-predicate='1'] [data-input-json]").fill("5");
        page.locator("[data-condition-predicate='1'] [data-input-json]").press("Tab");
        waitForText("#build-state", "Built");
    }

    private static String numberList(final int size) {
        return size == 0 ? "[]" : "[" + "0,".repeat(size - 1) + "0]";
    }

    private void addNestedFilterToMatchRoute() {
        addFilterAfterTrigger();
        page.locator("[data-add-outcome='match']").click();
        page.locator("#step-search").fill("filter");
        page.locator("[data-add-step='railix.filter']").click();
        waitForText("#build-state", "Built");
    }

    private void addStepToOtherwiseBranch() {
        addFilterAfterTrigger();
        page.locator("[data-add-outcome='otherwise']").click();
        page.locator("#step-search").fill("field");
        page.locator("[data-add-step='railix.field-manipulation']").click();
        waitForText("#build-state", "Built");
    }


    private void openProject(final String source) {
        final Number status = (Number) page.evaluate("""
                source => fetch('/api/project', {
                  method: 'POST',
                  headers: {'Content-Type': 'application/json'},
                  body: source
                }).then(response => response.status)
                """, source);
        assertThat(status.intValue()).isEqualTo(200);
        page.reload();
        page.waitForFunction("""
                () => document.querySelector('#build-state')?.textContent !== 'Loading'
                """);
        assertThat(page.locator("#build-state").textContent())
                .as("Creator state; browser errors: %s; inspector: %s", pageErrors, page.locator("#inspector").textContent())
                .isEqualTo("Built");
    }

    private void restartCreator(final Path project, final String source) throws Exception {
        context.close();
        context = null;
        creator.close();
        creator = null;
        Files.createDirectories(project.getParent());
        Files.writeString(project, source);
        creator = CreatorServer.start(0, project, directory.resolve("railix-home"));
        context = browser.newContext(new Browser.NewContextOptions().setViewportSize(
                VIEWPORT_WIDTH,
                VIEWPORT_WIDTH <= 560 ? 720 : 800
        ));
        page = context.newPage();
        page.navigate(creator.baseUri().toString());
        waitForText("#build-state", "Built");
    }

    private void delayNextProjectWrite() {
        page.evaluate("""
                () => {
                  const original = window.fetch.bind(window);
                  let delayed = false;
                  let release = null;
                  window.__railixProjectWriteStarted = false;
                  window.__railixReleaseProjectWrite = () => {
                    const current = release;
                    release = null;
                    current?.();
                  };
                  window.fetch = (input, options = {}) => {
                    const url = typeof input === 'string' ? input : input.url;
                    if (!delayed && url.endsWith('/api/project') && options.method === 'POST') {
                      delayed = true;
                      window.__railixProjectWriteStarted = true;
                      return new Promise((resolve, reject) => {
                        release = () => original(input, options).then(resolve, reject);
                      });
                    }
                    return original(input, options);
                  };
                }
                """);
    }

    private String applicationPid() {
        return String.valueOf(((Number) page.evaluate(
                "async () => (await (await fetch('/api/application')).json()).pid"
        )).longValue());
    }

    private void addManipulationAfterSelected() {
        openInspectorTab("inspect");
        page.locator("#add-next-step").click();
        page.locator("#step-search").fill("field");
        page.locator("[data-add-step='railix.field-manipulation']").click();
    }

    private void selectTrigger() {
        page.locator(".trigger-node").click();
    }

    private void choosePath(final String target, final String... parts) {
        final Locator identified = page.locator("#" + target + "-path");
        final Locator picker = identified.count() == 1
                ? identified
                : page.locator("[data-input-name='" + target + "'] .path-button");
        picker.click();
        page.locator("[data-path-depth='0']").click();
        for (final String part : parts) {
            page.locator("[data-path-part='" + part + "']").click();
        }
        page.locator("#apply-path").click();
    }

    private void chooseCustomField(final String... fields) {
        chooseCustomPath((Object[]) fields);
    }

    private void chooseCustomPath(final Object... parts) {
        chooseCustomPathFor("field", parts);
    }

    private void chooseCustomPathFor(final String target, final Object... parts) {
        final Locator identified = page.locator("#" + target + "-path");
        final Locator picker = identified.count() == 1
                ? identified
                : page.locator("[data-input-name='" + target + "'] .path-button");
        picker.click();
        page.locator("[data-path-depth='0']").click();
        for (final Object part : parts) {
            if (part instanceof Number number) {
                page.locator("#new-path-index").fill(number.toString());
                page.locator("#append-path-index").click();
            } else {
                page.locator("#new-path-field").fill(part.toString());
                page.locator("#append-path-field").click();
            }
        }
        page.locator("#apply-path").click();
    }

    private void openInspectorTab(final String mode) {
        page.locator("[data-inspector-mode='" + mode + "']").click();
    }

    private Locator examplePayload() {
        if (page.locator("#example-payload").count() == 0) {
            openInspectorTab("examples");
        }
        return page.locator("#example-payload");
    }

    private Locator exampleContext() {
        if (page.locator("#example-context").count() == 0) {
            openInspectorTab("examples");
        }
        return page.locator("#example-context");
    }

    private Locator presentationName() {
        if (page.locator("#presentation-name").count() == 0) {
            openInspectorTab("appearance");
        }
        return page.locator("#presentation-name");
    }

    private void prepareTextPayloadTrigger() {
        addTrigger();
        chooseCustomPathFor("target", "payload", "text");
        waitForText("#build-state", "Built");
        openInspectorTab("examples");
        replaceExamplePayloads("\"RAILIX\"", "\"Railix\"", "\"railix\"");
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
    }

    private String addGraphPrimitive(final String payload, final String query, final String id) {
        addTrigger();
        chooseCustomPathFor("target", "payload", "value");
        waitForText("#build-state", "Built");
        openInspectorTab("examples");
        replaceExamplePayloads(payload, payload, payload);
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        page.locator("#add-next-step").click();
        page.locator("#step-search").fill(query);
        final Locator option = page.locator("[data-add-step='" + id + "']");
        assertThat(option.count())
                .as("Catalog options: %s; browser errors: %s", page.locator("#step-options").textContent(), pageErrors)
                .isEqualTo(1);
        option.click();
        waitForText("#build-state", "Built");
        return page.locator(".step-node.selected").getAttribute("data-node-id");
    }

    private void replaceExamplePayloads(final String... payloads) {
        assertThat(page.locator("[data-select-example]").count()).isEqualTo(payloads.length);
        for (int index = 0; index < payloads.length; index++) {
            page.locator("[data-select-example='" + index + "']").click();
            examplePayload().fill(payloads[index]);
            examplePayload().press("Tab");
        }
    }

    private String positions() {
        page.evaluate("""
                () => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))
                  .then(() => Promise.allSettled(
                    [...document.querySelectorAll(".node")]
                      .flatMap(node => node.getAnimations())
                      .map(animation => animation.finished)
                  ))
                """);
        return (String) page.locator(".graph-stage").evaluate(
                "root => { const rootBox = root.getBoundingClientRect();"
                        + "const nodes = [...root.querySelectorAll('.node')];"
                        + "const firstBox = nodes[0].getBoundingClientRect(); return JSON.stringify("
                        + "nodes.map(node => {"
                        + "const box = node.getBoundingClientRect();"
                        + "return [node.dataset.nodeId,"
                        + "Math.round(box.x - rootBox.x),"
                        + "Math.round(box.y - firstBox.y)];"
                        + "})); }"
        );
    }

    private void waitForText(final String selector, final String text) {
        page.waitForFunction(
                "expected => document.querySelector(expected.selector)?.textContent === expected.text",
                Map.of("selector", selector, "text", text)
        );
    }

    private static void stopProcess(final long pid) {
        final ProcessHandle process = ProcessHandle.of(pid).orElseThrow();
        process.destroyForcibly();
        process.onExit().orTimeout(2, java.util.concurrent.TimeUnit.SECONDS).join();
    }
}
