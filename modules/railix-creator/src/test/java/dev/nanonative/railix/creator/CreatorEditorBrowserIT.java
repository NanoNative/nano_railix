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
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

final class CreatorEditorBrowserIT extends RailixCreatorBrowserSupport {
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
                .containsExactly("Renderer Canvas", "Renderer CSS");
        page.waitForFunction("() => document.querySelector('#graph').dataset.renderer === 'canvas'");
        assertThat(page.locator("#world-plane .machine").count()).isZero();
        assertThat(page.locator("#world-plane canvas").count()).isEqualTo(1);
        assertThat(page.locator("#theme-variant").inputValue()).isEqualTo("canvas");
        assertThat(page.evaluate("() => getComputedStyle(document.documentElement).getPropertyValue('--canvas-resolution').trim()"))
                .isEqualTo("2");
        page.getByLabel("Renderer", new Page.GetByLabelOptions().setExact(true)).selectOption("hq");
        page.waitForFunction("() => document.querySelector('#graph').dataset.renderer === 'css'");
        assertThat(page.locator("#world-plane canvas").count()).isZero();
        assertThat(page.locator("#world-plane .machine").count()).isPositive();
        page.waitForFunction("() => !state.settingsWriting && !state.settingsTimer");
        page.reload();
        page.waitForFunction("() => document.querySelector('#graph').dataset.renderer === 'css' && state.settings.theme_variant === 'hq'");
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
        Files.write(theme.resolve("body.png"), java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jP1sAAAAASUVORK5CYII="));
        Files.writeString(theme.resolve("atlas.json"), """
                {"version":1,"sprites":{
                  "step":{"file":"body.svg","frames":1,"scale":1,"anchorX":16,"anchorY":24,"period":1000},
                  "trigger":{"file":"body.png","frames":1,"scale":1,"anchorX":0,"anchorY":0,"period":1000}}}
                """);
        openProject(choiceProject());
        final String source = Files.readString(directory.resolve("project.json")), pid = applicationPid();
        page.locator("#open-settings").click();
        selectTheme("local/theme.css");
        page.waitForFunction("() => {if(state.themeError)throw Error(state.themeError);return document.querySelector('#graph').dataset.renderer==='canvas';}");
        page.waitForFunction("() => !state.settingsWriting && !state.settingsTimer");
        page.reload();
        page.waitForFunction("() => document.querySelector('#graph').dataset.renderer==='canvas' && !state.themeError");
        page.waitForFunction("() => document.querySelector('.world-scenery') !== null");
        assertThat(page.locator(".world-raster").getAttribute("data-scenery")).as("Themes without scenery images keep the CSS environment").isEqualTo("0");
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
        assertThat(page.locator("#graph").getAttribute("data-renderer")).isEqualTo("css");
        assertThat(page.locator("#world-plane canvas").count()).isZero();
        assertThat(page.evaluate("() => state.themeError").toString()).contains("Invalid Canvas sprite");
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
        assertThat(page.locator(".dock-heading small").textContent()).isEqualTo("Choice");
        final var portrait = page.locator(".selection-portrait .machine");
        assertThat(portrait.getAttribute("data-building")).isEqualTo("branch");
        final double width = portrait.boundingBox().width;
        page.locator("#zoom-in").click();
        assertThat(portrait.boundingBox().width).isCloseTo(width, org.assertj.core.data.Offset.offset(.1));
        assertThat(portrait.evaluate("element => element.getAnimations({subtree:true}).length")).isEqualTo(0);
        page.locator("#open-settings").click();
        selectTheme("classic/theme.css");
        page.waitForFunction("() => document.querySelector('.selection-portrait .machine')?.dataset.building === ''");
        assertThat(page.locator(".selection-portrait .building-volume").count()).isZero();
        selectTheme("");
        page.locator("#theme-variant").selectOption("hq");
        page.waitForFunction("() => document.querySelector('.selection-portrait .machine')?.dataset.building === 'branch'");
        assertThat(page.locator(".selection-portrait .building-volume").count()).isEqualTo(2);
        page.keyboard().press("Escape");
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
        page.waitForFunction("() => getComputedStyle(document.querySelector('.machine[data-building=app] .building-volume[data-slot=tower]')).backgroundImage !== 'none'");
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
        assertThat(Files.readString(directory.resolve("railix-home/themes/foundry/theme.css"))).contains(".building-volume", ".belt-run");
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
        selectWorldNode("choice");
        openInspectorTab("overview");
        final var action = page.locator("#selection-overview [data-add-outcome]").first();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(action)
                .hasCSS("background-image", java.util.regex.Pattern.compile(".*linear-gradient.*"));
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(action).hasCSS("text-shadow", "none");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#dock-case"))
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
    void coloredGroupUsesTheSharedSelectionAccent() throws Exception {
        openProject(fourStepProject());
        final String group = createGroup("one", "two");
        page.locator("#presentation-color").fill("#aa6519");
        clickAndWaitForCreatorSave(() -> page.locator("#presentation-color").press("Tab"));
        page.locator("#close-inspector").click();
        page.locator("[data-region-group='" + group + "']").first().click();
        assertThat(page.locator(".machine[data-selected=true]").evaluate(
                "element => getComputedStyle(element,'::after').getPropertyValue('--selection').trim()"))
                .isEqualTo("#a9f5ec");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void selectedExampleMovesCargoAndBeltSectionsIntoARecessedEnd(final int example) {
        openProject(choiceProject());
        selectWorldNode("command");
        page.locator("#dock-example").selectOption(Integer.toString(example));
        page.evaluate("() => state.world.fit()");
        awaitScene();
        page.waitForFunction("() => document.querySelector('#graph').dataset.replaying === 'true'");
        final var track = page.locator(".belt-run[data-replay=true] .belt-track").first();
        assertThat(track.evaluate("element => getComputedStyle(element,'::before').backgroundImage").toString())
                .contains("linear-gradient", "repeating-linear-gradient");
        final Object before = track.evaluate("element => getComputedStyle(element,'::before').transform");
        page.waitForFunction("before => [...document.querySelectorAll('.belt-run[data-replay=true] .belt-track')]"
                + ".some(element=>getComputedStyle(element,'::before').transform !== before)",before);
        final var end = page.locator(".machine[data-kind=end][data-coverage=selected]");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(end).hasCount(1);
        assertThat(end.getAttribute("data-station-id")).isEqualTo("end:" + (example == 0 ? "matched" : "otherwise") + ".next");
        assertThat(page.locator(".world-symbol[data-symbol=end]:visible").count()).isZero();
        assertThat(end.locator(".machine-side,.machine-core").count()).isZero();
        assertThat(end.evaluate("element=>element.style.getPropertyValue('--depth')")).isEqualTo("0px");
        assertThat(end.locator(".machine-top").evaluate("element=>getComputedStyle(element,'::after').animationName"))
                .isEqualTo("terminal-intake");
        assertThat(page.locator(".machine[data-kind=end]:not([data-coverage=selected]) .machine-top")
                .evaluate("element=>getComputedStyle(element,'::after').animationName")).isEqualTo("none");
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
        page.evaluate("() => state.world.fit()");
        awaitScene();
        page.waitForFunction("() => document.querySelector('#graph').dataset.replaying === 'true'");
        assertThat(page.locator(".machine[data-kind=end]").count()).isEqualTo(2);
        assertThat(page.locator(".machine[data-kind=end][data-coverage=selected]").count()).isZero();
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
        assertThat(page.locator(".inspector-tabs .active").textContent()).isEqualTo("Overview");
        assertThat(page.locator("#open-inspector").count()).isZero();
        assertThat(page.locator("#open-settings").textContent()).isBlank();
        final var map=page.locator("#world-map").boundingBox();
        final var controls=page.locator(".topbar").boundingBox();
        assertThat(map.x).isGreaterThan(page.viewportSize().width/2.0);
        assertThat(controls.y).isGreaterThanOrEqualTo(map.y+map.height);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void typingAndEscapeInAnInputDoNotRunWorldShortcuts() {
        openProject(choiceProject());
        selectWorldNode("choice");
        final var field=page.locator("#inspector textarea").first();
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
        selectWorldNode("switch");
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
        assertThat(page.locator(".condition-chain").first().getAttribute("open")).isNull();
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

    @Test
    void manualMusicPauseSurvivesTabChangesUntilPlayIsPressed() {
        page.locator("#open-settings").click();
        page.locator("#settings-music-tab").click();
        page.waitForFunction("() => state.audio.musicContext?.state === 'running'");
        page.locator("#music-play").click();
        page.waitForFunction("() => state.audio.musicContext?.state === 'suspended' && !state.settingsWriting && !state.settingsTimer");
        final var playback = page.evaluateHandle("() => ({context:state.audio.musicContext, session:state.audio.musicSession})");
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

    @Test
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

    @ParameterizedTest
    @ValueSource(strings = {"app", "command", "matched"})
    void emptyFloorClearsSelectionWithoutEditingOrLosingTheExample(final String id) throws Exception {
        openProject(choiceProject());
        selectWorldNode("command");
        page.locator("#dock-example").selectOption("1");
        selectWorldNode(id);
        final String source = Files.readString(directory.resolve("project.json"));
        page.locator("#graph").click(new com.microsoft.playwright.Locator.ClickOptions().setPosition(8, 220));
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isBlank();
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator(".machine[data-selected=true]")).hasCount(0);
        page.evaluate("() => state.world.fit()");
        awaitScene();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator(".machine[data-selected=true]")).hasCount(0);
        waitForCoverage("otherwise", "selected");
        selectWorldNode("command");
        assertThat(page.locator("#dock-example").inputValue()).isEqualTo("1");
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
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator(".machine[data-selected=true]")).hasCount(0);
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
        openInspectorTab("overview");
        final String metadata = Files.readString(directory.resolve("railix.creator.json"));
        clearWorldSelection();
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isBlank();
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator(".machine[data-selected=true]")).hasCount(0);
        page.locator("[data-region-group=prepare]").click();
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
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator(".machine[data-selected=true]")).hasCount(0);
            assertThat(pageErrors).isEmpty();
        } finally {
            page.evaluate("() => window.restoreEditor()");
        }
    }

    @Test
    void hudUsesCssSymbolsWithLabeledRaisedControls() {
        openProject(fourStepProject());
        assertThat(page.locator(".build-indicator").evaluate("element => getComputedStyle(element).color"))
                .as("Running keeps its green status color under the shared button material")
                .isEqualTo("rgb(135, 227, 182)");
        assertThat(page.locator("#open-settings").evaluate("element => getComputedStyle(element).color"))
                .as("Unselected HUD controls retain readable foreground contrast")
                .isEqualTo("rgb(220, 230, 227)");
        selectWorldNode("one");
        final var configure = page.locator("#open-settings");
        assertThat(configure.textContent()).isBlank();
        assertThat(configure.getAttribute("title")).isEqualTo("Settings");
        assertThat(configure.locator(".hud-symbol").getAttribute("aria-hidden")).isEqualTo("true");
        assertThat(configure.evaluate("element => getComputedStyle(element).boxShadow")).isNotEqualTo("none");
        final var box = configure.boundingBox();
        page.mouse().move(box.x + box.width / 2, box.y + box.height / 2);
        page.mouse().down();
        assertThat(configure.evaluate("element => getComputedStyle(element).translate")).isEqualTo("0px 2px");
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
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void clearingSelectionKeepsApplicationStatusUpdating() {
        openProject(fourStepProject());
        selectWorldNode("command");
        page.locator("#status-memory").waitFor();
        final var response = page.waitForResponse(result -> result.url().endsWith("/api/metrics"),
                new Page.WaitForResponseOptions().setTimeout(5_000), () -> clearWorldSelection());
        assertThat(response.status()).isEqualTo(200);
        page.locator("#status-memory").waitFor();
        assertThat(page.locator("#status-memory").textContent()).startsWith("Heap ");
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isBlank();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void groupTimingSumsIndividualStepAveragesAndSelectionKeepsTheInspectorOpen() throws Exception {
        openProject(fourStepProject());
        assertThat(page.evaluate("""
                async () => (await fetch('/api/creator', {method:'POST', headers:mutationHeaders(), body:JSON.stringify({format:2,
                  groups:[{id:'prepare',name:'Preparation'}],steps:Object.fromEntries(['one','two','three','four'].map(id=>[id,{group:'prepare'}]))})})).status
                """)).isEqualTo(200);
        page.reload();
        waitForText("#build-state", "Built");
        page.waitForFunction("() => document.querySelector('[data-region-group=prepare] .world-duration')?.textContent.includes('avg sum')");
        final Number sum = (Number) page.evaluate("""
                async () => {
                  let sum = 0;
                  for (const id of ['one','two','three','four']) {
                    const response = await (await fetch('/api/metrics/nodes/'+id)).json();
                    const value = response.steps.find(step=>step.id === id).metrics;
                    sum += value.duration_nanos_total / value.duration_samples;
                  }
                  return sum;
                }
                """);
        final String label = page.locator("[data-region-group=prepare] .world-duration").textContent();
        final String[] parts = label.split(" ");
        final double unit = switch (parts[1]) {case "us" -> 1_000; case "ms" -> 1_000_000; case "s" -> 1_000_000_000; default -> 1;};
        assertThat(Double.parseDouble(parts[0]) * unit).isCloseTo(sum.doubleValue(), org.assertj.core.data.Offset.offset(unit / 2));
        page.locator(".app-node").click();
        openInspectorTab("overview");
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        page.locator("[data-region-group=prepare]").click();
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        assertThat(page.locator(".machine[data-selected=true]").count()).isEqualTo(1);
        assertThat(page.locator("#inspector").textContent()).contains("Preparation", "Group metrics");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void replayAndPowerActuallyAnimateWithoutAdditionalExecutions() {
        openProject(choiceProject());
        selectWorldNode("command");
        page.waitForFunction("() => document.querySelector('#graph').dataset.replaying === 'true'");
        final var track = page.locator(".belt-run[data-replay=true] .belt-track").first();
        final Object before = track.evaluate("element => getComputedStyle(element, '::after').transform");
        page.waitForFunction("before => [...document.querySelectorAll('.belt-run[data-replay=true] .belt-track')].some(element => getComputedStyle(element, '::after').transform !== before)", before);
        page.evaluate("() => state.world.fit()");
        awaitScene();
        assertThat(page.locator(".power-run").first().evaluate("element => getComputedStyle(element, '::after').animationName")).isEqualTo("power-flow");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void replayKeepsDirectionChevronsVisible() {
        openProject(choiceProject());
        selectWorldNode("command");
        page.waitForFunction("() => document.querySelector('#graph').dataset.replaying === 'true'");
        assertThat(page.locator(".belt-run[data-replay=true] .belt-track").first().evaluate(
                "element => getComputedStyle(element, '::after').backgroundImage").toString())
                .contains("linear-gradient(45deg", "linear-gradient(-45deg");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void sceneryLeavesMachinesAndBeltsClear() {
        openProject(deepBranchProject(128));
        page.evaluate("() => state.world.fit()");
        awaitScene();
        assertThat(page.locator(".world-scenery").count()).isPositive();
        assertThat(page.evaluate("""
                () => {
                  const obstacles = [...document.querySelectorAll('.machine:not([data-expanded=true]), .belt-run, .power-run')]
                    .map(element => element.getBoundingClientRect());
                  return [...document.querySelectorAll('.world-scenery')].filter(element => {
                    const box = element.getBoundingClientRect();
                    return obstacles.some(other => box.left < other.right && box.right > other.left
                      && box.top < other.bottom && box.bottom > other.top);
                  }).length;
                }
                """)).isEqualTo(0);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void sceneryDoesNotDisappearWhenZoomingCloserToAVisibleStructure(final int seed) {
        openProject(fourStepProject());
        page.evaluate("seed => {state.creator.created_at=seed;state.world.fit();}",seed);
        awaitScene();
        final Object structure = page.evaluate("""
                () => {
                  const stage=document.querySelector('#graph').getBoundingClientRect();
                  for (const element of document.querySelectorAll('.world-scenery')) {
                    // A projected rectangle's bounding-box corner may be empty space, not its plot.
                    const marker=document.createElement('span');element.append(marker);
                    try {
                      for(const x of [50,25,75])for(const y of [50,25,75]){
                        marker.style.cssText=`position:absolute;left:${x}%;top:${y}%;width:0;height:0`;
                        const point=marker.getBoundingClientRect();
                        if(point.x>stage.left+20&&point.x<stage.right-20&&point.y>stage.top+20&&point.y<stage.bottom-20)
                          return {x:point.x,y:point.y,worldX:element.dataset.worldX,worldY:element.dataset.worldY};
                      }
                    }finally{marker.remove();}
                  }
                  throw new Error('No visible scenery structure in the real project view.');
                }
                """);
        page.evaluate("""
                async item => {
                  const scale=Number(new URLSearchParams(state.world.query).get('scale'));
                  state.world.zoom(8.1/scale,item.x,item.y);
                  await state.world.refresh();
                  await new Promise(resolve=>requestAnimationFrame(resolve));
                }
                """,structure);
        assertThat(page.evaluate("""
                item => [...document.querySelectorAll('.world-scenery')].some(element =>
                  element.dataset.worldX===item.worldX && element.dataset.worldY===item.worldY)
                """,structure)).isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(doubles = {.001, 1, 100})
    void terrainRemainsBoundedAtEveryZoom(final double zoom) {
        openProject(deepBranchProject(128));
        page.evaluate("zoom => state.world.zoom(zoom)",zoom);
        page.evaluate("async () => {await state.world.refresh();await new Promise(resolve=>requestAnimationFrame(resolve));}");
        assertThat(page.locator(".world-terrain").count()).isLessThanOrEqualTo(64);
        assertThat(page.locator(".world-terrain").evaluateAll(
                "elements => elements.every(element => parseFloat(element.style.width) <= 2048)")).isEqualTo(true);
        assertThat(page.locator(".world-scenery").count()).isLessThanOrEqualTo(100);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void minimapIsBoundedClickableAndDoesNotSaveItsCamera() throws Exception {
        openProject(deepBranchProject(128));
        final String source = Files.readString(directory.resolve("project.json"));
        page.locator("#world-map").waitFor();
        assertThat(page.locator("#world-map .map-cells i").count()).isBetween(1, 640);
        final Object before = page.evaluate("() => state.world.query");
        page.locator("#world-map").click(new com.microsoft.playwright.Locator.ClickOptions().setPosition(140, 80));
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
    void foundryRadarIsRoundWithReadableMachineMarkers() throws Exception {
        openProject(choiceProject());
        page.locator("#world-map").waitFor();
        final var bounds = page.locator("#world-map").boundingBox();
        assertThat(bounds.width).isEqualTo(bounds.height);
        assertThat(page.locator("#world-map").evaluate("element=>getComputedStyle(element).borderRadius")).isEqualTo("50%");
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
        final var outline = page.locator(".map-camera").boundingBox();
        final var selected = page.locator(".map-cells i[data-selected=true]").boundingBox();
        page.locator("#world-map").press("ArrowRight");
        awaitScene();
        assertThat(page.locator(".map-cells i[data-selected=true]").count()).isEqualTo(1);
        assertThat(page.locator(".map-camera").boundingBox().x).isEqualTo(outline.x);
        assertThat(page.locator(".map-cells i[data-selected=true]").boundingBox().x).isLessThan(selected.x);
        clearWorldSelection();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator(".map-cells i[data-selected=true]")).hasCount(0);
    }

    @Test
    void minimapDistinguishesUnreachedFromDisabledMetrics() {
        openProject(choiceProject().replace("\"id\":\"matched\",", "\"id\":\"matched\",\"metrics\":false,")
                .replace("\"value\":\"deny\"", "\"value\":\"allow\""));
        page.locator("[data-world-id=command]").click();
        page.waitForFunction("() => document.querySelector('.machine[data-station-id=otherwise]')?.dataset.never === 'true'");
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
        assertThat(page.locator(".machine[data-error=true]").count()).isZero();
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
        page.evaluate("() => state.world.fit()");
        awaitScene();
        page.locator("[data-world-id=otherwise]").click();
        awaitScene();
        assertThat(page.locator(".machine[data-selected=true]").getAttribute("data-error")).isEqualTo("false");
        assertThat(page.locator(".map-cells i[data-state=error]").count()).isEqualTo(1);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void minimapTimingUsesSamplesWithoutClaimingUtilization() {
        openProject(fourStepProject());
        page.waitForFunction("() => [...document.querySelectorAll('.map-cells i')].some(element => Number(element.style.getPropertyValue('--heat')) > 0)");
        assertThat(page.locator(".map-cells i[title*='sampled time']").first().getAttribute("title"))
                .contains("not utilization");
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
        page.locator("#dock-example").selectOption("0");
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
        page.evaluate("() => state.world.focus('matched')");
        awaitScene();
        page.waitForFunction("() => document.querySelector('.machine[data-station-id=matched]')?.dataset.coverage === 'selected'");
        page.locator("[data-select-node=matched]").click();
        page.waitForFunction("() => Math.abs(state.audio.effectSession?.voices[0].voice.frequency.value - 261.6256) < .01");
        page.locator("#close-inspector").click();
        page.evaluate("() => state.world.focus('otherwise')");
        awaitScene();
        page.waitForFunction("""
                () => { const machine = document.querySelector('.machine[data-station-id=otherwise]');
                  return machine?.dataset.coverage === 'uncovered' && machine.dataset.busy !== 'true'; }
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
        assertThat(page.locator("#dock-example option[value='-1']").count()).isEqualTo(1);
        page.waitForFunction("() => document.querySelector('#graph').dataset.replaying === 'true'");
        final Object executions = page.evaluate("async () => (await (await fetch('/api/metrics/nodes/command')).json()).steps[0].metrics.executions");
        assertThat(executions).isNotNull();
        final String source = Files.readString(directory.resolve("project.json"));
        page.locator("#dock-example").selectOption("-1");
        page.waitForFunction("() => document.querySelector('#graph').dataset.replaying === 'false'");
        assertThat(page.locator(".machine[data-coverage=selected]").count()).isZero();
        page.locator("#dock-example").selectOption("0");
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
        assertThat(page.locator(".belt-run[data-replay=true] .belt-track").first().evaluate(
                "element => getComputedStyle(element, '::after').animationName")).isEqualTo("belt-travel");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void seededSceneryIsRecreatedWithoutPersistingGeometry() throws Exception {
        openProject(fourStepProject());
        page.evaluate("() => state.world.fit()");
        awaitScene();
        final String metadata = Files.readString(directory.resolve("railix.creator.json"));
        final String inspect = "elements => elements.map(element => [element.dataset.variant, element.dataset.worldX, element.dataset.worldY]).sort()";
        final Object scenery = page.locator(".world-scenery").evaluateAll(inspect);
        assertThat(page.locator(".world-scenery").count()).isBetween(1, 100);
        page.reload();
        waitForText("#build-state", "Built");
        page.evaluate("() => state.world.fit()");
        awaitScene();
        assertThat(page.locator(".world-scenery").evaluateAll(inspect)).isEqualTo(scenery);
        assertThat(Files.readString(directory.resolve("railix.creator.json"))).isEqualTo(metadata);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void neverExecutedStepsLookUnpoweredButMetricsDisabledStepsDoNot() {
        openProject(choiceProject().replace("\"id\":\"matched\",", "\"id\":\"matched\",\"metrics\":false,")
                .replace("\"value\":\"deny\"", "\"value\":\"allow\""));
        page.waitForFunction("() => document.querySelector('.machine[data-station-id=otherwise]')?.dataset.never === 'true'");
        assertThat(page.locator(".machine[data-station-id=matched]").getAttribute("data-never")).isEqualTo("false");
        assertThat(page.locator(".machine[data-station-id=otherwise] .machine-core")
                .evaluate("element => getComputedStyle(element).animationName")).isEqualTo("none");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void applicationSuppliesPowerRatherThanTransport() {
        openProject(choiceProject());
        page.evaluate("() => state.world.fit()");
        awaitScene();
        assertThat(page.locator(".power-run").count()).isPositive();
        assertThat(page.locator(".power-run[data-active=true]").count()).isZero();
        assertThat(page.locator(".power-run .belt-track").first().isVisible()).isFalse();
        assertThat(page.locator(".belt-run").count()).isPositive();
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
        assertThat(page.locator(".machine[data-station-id=app]").getAttribute("data-warning")).isEqualTo("true");
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
        assertThat(Files.readString(directory.resolve("railix-home/themes/foundry/theme.css"))).contains(".machine", ".belt-run");
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
        page.evaluate("() => state.world.fit()");
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
        page.evaluate("() => state.world.fit()");
        awaitScene();
        final Path screenshots = Files.createDirectories(Path.of("target", "screenshots"));
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("css-factory-hud.png")));
        selectWorldNode("choice");
        page.locator("#close-inspector").click();
        awaitScene();
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("css-factory-detail.png")));
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(functional);
        assertThat(page.locator("#world-plane .machine-side").count()).isPositive();
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
        page.evaluate("() => state.world.fit()");
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        page.locator(".build-indicator").click();
        page.locator("#application-status #workspace-details").waitFor();
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void exampleChooserIsAtItsTriggerAndKeyboardInputDoesNotPanTheWorld() {
        openProject(choiceProject());
        selectTrigger();
        page.locator("#close-inspector").click();
        clickOverview("#dock-focus");
        awaitScene();
        page.locator("#trigger-example:not([hidden]) #dock-example").waitFor();
        final String camera = (String) page.evaluate("() => state.world.query");
        final var before = page.locator("#world-plane .machine[data-station-id=command] .building-volume").first().boundingBox();
        page.locator("#dock-example").focus();
        page.locator("#dock-example").press("ArrowLeft");
        page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))");
        assertThat(page.evaluate("() => state.world.query")).isEqualTo(camera);
        page.locator("#dock-example").selectOption("1");
        awaitScene();
        assertThat(page.evaluate("() => state.world.query")).isEqualTo(camera);
        final var panel = page.locator("#trigger-example").boundingBox();
        final var trigger = page.locator("#world-plane .machine[data-station-id=command] .building-volume").first().boundingBox();
        assertThat(trigger.x).isCloseTo(before.x, org.assertj.core.data.Offset.offset(.5));
        assertThat(panel.y + panel.height <= trigger.y || panel.y >= trigger.y + trigger.height
                || panel.x + panel.width <= trigger.x || panel.x >= trigger.x + trigger.width).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void startupFailureIsVisibleWithoutOpeningTheInspector() {
        page.navigate(creator.baseUri().resolve("/?unauthorized") + "#token=invalid");
        waitForText("#build-state", "Unavailable");
        assertThat(page.locator("#world-error").isVisible()).isTrue();
        assertThat(page.locator("#world-error").textContent()).contains("Creator could not open the project");
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isBlank();
    }

    @Test
    @Tag("responsive")
    void overviewShowsRealInputAndOutputWithoutEditingTheStep() {
        addGraphPrimitive("\"RAILIX\"", "lowercase", "text.lowercase");
        page.locator("[data-input-name='target'] [data-path-value='after']").waitFor();
        openInspectorTab("overview");
        assertThat(page.locator("[data-dock-value='input'] output").textContent()).isEqualTo("\"RAILIX\"");
        assertThat(page.locator("[data-dock-value='output'] output").textContent()).isEqualTo("\"railix\"");
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
        page.locator("#dock-example").focus();
        page.locator("#dock-example").selectOption("1");
        page.waitForFunction("() => document.querySelector('#dock-example')?.selectedOptions[0]?.textContent === 'otherwise' && state.application.example?.name === 'otherwise'");
        assertThat(page.evaluate("() => document.activeElement?.id")).isEqualTo("dock-example");
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
        page.locator("#dock-example").focus();
        final var chooser = page.locator("#dock-example").elementHandle();
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
    void groupSelectionOpensInspectorWithEnterAndAppearanceActions() {
        openProject(fourStepProject());
        final String group = createGroup("one", "two");
        page.locator("#close-inspector").click();
        page.locator("[data-region-group='" + group + "']").first().click();
        page.locator("#enter-region").waitFor();
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        openInspectorTab("overview");
        page.locator("[data-inspector-mode=appearance]").waitFor();
        assertThat(page.locator("#inspector").isVisible()).isTrue();
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
        assertThat(page.locator("#enter-region").isVisible()).isTrue();
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
                  window.fetch = async (...args) => {
                    const response = await fetch.apply(window, args);
                    if (String(args[0]).startsWith('/api/editor?node=app'))
                      await new Promise(resolve => window.__releaseAppEditor = resolve);
                    return response;
                  };
                  window.__restoreAppEditor = () => { window.__releaseAppEditor?.(); window.fetch = fetch; };
                }
                """);
        try {
            page.locator("[data-world-id=app]").click();
            page.waitForFunction("() => Boolean(window.__releaseAppEditor)");
            page.locator("[data-region-group='" + group + "']").first().click();
            page.evaluate("() => window.__releaseAppEditor()");
            page.waitForFunction("() => !state.editorController");
            assertThat(page.locator("#inspector").isVisible()).isTrue();
            assertThat(page.locator("#enter-region").isVisible()).isTrue();
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
                page.locator("#selection-overview .dock-heading small")).hasText("Trigger");
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
    void stationNamesSitOutsideCompactMachineBodies() {
        openProject(fourStepProject());
        selectWorldNode("one");
        page.locator("#close-inspector").click();
        page.evaluate("() => state.world.focus('one', 1)");
        awaitScene();
        final var label = page.locator("[data-node-id='one']");
        label.locator("strong").waitFor();
        final var name = label.locator("strong").boundingBox();
        final var surface = page.locator("#world-plane .machine[data-station-id=one] .machine-top").boundingBox();
        assertThat(page.locator("#world-plane .machine[data-station-id=one] .building-volume").isVisible()).isTrue();
        assertThat(name.y >= surface.y + surface.height || name.y + name.height <= surface.y
                || name.x >= surface.x + surface.width || name.x + name.width <= surface.x).isTrue();
        assertThat(label.locator(".world-detail").textContent()).doesNotContain("executions", "sampled", "context");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void appearanceControlsReflectTheDefaultMachineShape() {
        openProject(fourStepProject());
        selectWorldNode("one");
        openInspectorTab("appearance");
        assertThat(page.locator("#presentation-aspect").inputValue()).isEqualTo("1");
        assertThat(page.locator("#presentation-roundness").inputValue()).isEqualTo("12");
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
        page.locator("[data-select-example='1']").click();
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
        selectWorldNode("otherwise");

        waitForText("[data-input-name='field'] .path-values", "Not reached");
        assertThat(page.locator("[data-input-name='field'] [data-path-value]").count()).isZero();
    }

    @Test
    void inspectorClosePreservesSelectionExampleAndCamera() {
        openProject(choiceProject());
        selectTrigger();
        openInspectorTab("examples");
        page.locator("[data-select-example='1']").click();
        awaitScene();
        final Object before = page.evaluate("""
                () => ({selection: state.selection, example: state.exampleIndex,
                  camera: ['x','y','scale'].map(key => new URLSearchParams(state.world.query).get(key)),
                  project: JSON.stringify(state.project), creator: JSON.stringify(state.creator)})
                """);

        page.locator("#close-inspector").click();
        page.waitForFunction("() => document.querySelector('#inspector').hidden");
        awaitScene();

        assertThat(page.evaluate("""
                () => ({selection: state.selection, example: state.exampleIndex,
                  camera: ['x','y','scale'].map(key => new URLSearchParams(state.world.query).get(key)),
                  project: JSON.stringify(state.project), creator: JSON.stringify(state.creator)})
                """)).isEqualTo(before);
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
        page.locator("[data-select-example='1']").click();

        waitForCoverage("otherwise", "selected");
        assertThat(page.locator("[data-select-example='1']").getAttribute("class")).contains("active");
    }

    @Test
    void selectedExampleSurvivesAppAndDownstreamInspection() {
        openProject(choiceProject());
        selectTrigger();
        openInspectorTab("examples");
        page.locator("[data-select-example='1']").click();
        selectWorldNode("app");
        selectWorldNode("otherwise");

        waitForCoverage("otherwise", "selected");
        selectTrigger();
        openInspectorTab("examples");
        assertThat(page.locator("[data-select-example='1']").getAttribute("class")).contains("active");
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
        page.evaluate("() => state.world.fit()");
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
        page.locator("[data-select-example='1']").click();
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

            page.evaluate("() => state.world.fit()");
            awaitScene();
            final Object fitted = page.evaluate("() => state.world.query");
            final String fittedZoom = page.locator("#zoom-level").textContent();
            page.evaluate("() => { window.__unvisitedFit.fitted = true; window.__unvisitedFit.releaseEditor(); }");
            page.waitForFunction("() => window.__unvisitedFit.editorConsumed === true && !state.editorController");
            awaitScene();

            assertThat(page.evaluate("() => state.world.query")).isEqualTo(fitted);
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
            page.evaluate("() => state.world.fit()");
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
        page.evaluate("() => state.world.fit()");
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
    void unchangedApplicationPollsDoNotRedrawTheWorldOrReplaceTheExamplePreview() {
        openProject(choiceProject());
        selectWorldNode("matched");
        awaitScene();
        page.waitForFunction("""
                () => Boolean(state.preview) && state.application.examples?.state === 'completed'
                  && !state.applicationRefreshing && state.observations?.nodes.get('matched')?.rate === 0
                """);
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
        openProject(choiceProject().replace("\"id\":\"matched\",", "\"id\":\"matched\",\"metrics\":false,")
                .replace("\"value\":\"deny\"", "\"value\":\"allow\""));
        page.waitForFunction("() => document.querySelector('[data-node-id=matched]')?.dataset.activity === 'disabled'");
        assertThat(page.locator("#status-observations").isHidden()).isTrue();

        assertThat(page.locator("[data-node-id='matched']").getAttribute("title")).isEqualTo("Metrics off");
        assertThat(page.locator("[data-node-id='otherwise']").getAttribute("data-activity")).isEqualTo("idle");
        assertThat(page.locator("[data-node-id='otherwise']").getAttribute("title"))
                .startsWith("0 executions").doesNotContain("sampled");
        assertThat(page.locator("[data-node-id='otherwise']").getAttribute("title"))
                .contains("No duration inferred.");
        assertThat(pageErrors).isEmpty();
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
        page.locator("[data-select-example='0']").click();
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
    void delayedEditorReadWaitsForANewerWriteBeforeChangingNeighborhoods() {
        openProject(fourStepProject());
        selectWorldNode("four");
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
            if (request.method().equals("PATCH") && request.url().endsWith("/api/project")) {
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
                      && url.searchParams.get('node') === 'two';
                    const write = !writeHeld && url.pathname === '/api/project' && options.method === 'PATCH';
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
            page.evaluate("() => void state.world.focus('two')");
            page.locator("[data-select-node='two']").click();
            page.waitForFunction("() => Boolean(window.__editorWriteRace.releaseEditor)");
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

            page.evaluate("() => window.__editorWriteRace.releaseWrite()");
            page.waitForFunction("() => document.querySelector('#inspector')?.dataset.selection === 'two'");
            waitForText("#build-state", "Built");
            page.locator("#node-metrics").uncheck();
            waitForText("#build-state", "Built");

            assertThat(writes).hasSize(2);
            for (int index = 0; index < writes.size(); index++) {
                final var edit = CreatorServerE2eSupport.object(writes.get(index));
                final var changes = (RailixValue.ObjectValue) edit.values().get("changes");
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
        final var response = page.waitForResponse(candidate -> candidate.url().endsWith("/api/project")
                && candidate.request().method().equals("PATCH"), () -> page.locator("#node-metrics").uncheck());
        assertThat(response.status()).isEqualTo(200);
        waitForText("#build-state", "Built");
        assertThat(page.evaluate("async () => (await (await fetch('/api/project')).json()).project"))
                .isEqualTo(expected);

        selectWorldNode("four");
        assertThat(page.locator("#value-0-literal-value").inputValue()).isEqualTo("[");
        assertThat(page.locator("#inspector").textContent()).contains("Value must be valid JSON.");
        assertThat(page.locator("#preview-values output").count()).isZero();
        assertThat(pageErrors).isEmpty();
    }
}
