package dev.nanonative.railix.creator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.BoundingBox;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@Timeout(120)
final class CreatorWorldBrowserIT extends RailixCreatorBrowserSupport {
    @ParameterizedTest
    @ValueSource(doubles={0,25_000})
    void aSingleMeasuredDurationHasNoArtificialSpread(final double nanos) {
        openProject(fourStepProject());
        assertThat(page.evaluate("""
                value=>{
                  const renderer=new RailixCanvas(document.createElement('div'),{sprites:new Map()});
                  try {
                    renderer.begin({width:100,height:100},{m11:1,m12:0,m21:0,m22:1,m31:0,m32:0},[0,0],getComputedStyle(document.documentElement));
                    renderer.timing([value,value]);
                    return renderer.timeScale.length===1 && renderer.timeScale[0]===value
                      && renderer.timeColor(value)!==renderer.timeColor(null);
                  } finally {renderer.dispose();}
                }
                """,nanos)).isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void disabledMeasurementsDoNotCreateATimingLegend() {
        final var project=JsonParser.parseString(fourStepProject()).getAsJsonObject();
        project.getAsJsonArray("nodes").forEach(value->{
            if(!value.getAsJsonObject().get("id").getAsString().equals("app"))value.getAsJsonObject().addProperty("metrics",false);
        });
        openProject(project.toString());awaitScene();
        assertThat(page.locator("#example-time-scale").isHidden()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void endCapsHaveRoundFootprintsInsteadOfTheStationAspect() {
        openProject(fourStepProject());
        page.locator("#scope-overview").click();awaitScene();
        assertThat(page.evaluate("""
                async()=>{
                  const prototype=CanvasRenderingContext2D.prototype,ellipse=prototype.ellipse,shapes=[];
                  prototype.ellipse=function(x,y,rx,ry,...rest){shapes.push([rx,ry]);return ellipse.call(this,x,y,rx,ry,...rest);};
                  try {
                    state.world.repaint();await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
                    return shapes.length>0 && shapes.every(([rx,ry])=>Math.abs(rx-ry)<.001);
                  } finally {prototype.ellipse=ellipse;}
                }
                """)).as("Painted end rings use a circular ground footprint before the camera projection").isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void minimapRetainsTopologyWhileItsViewportMoves() {
        openProject(fourStepProject());
        for(int i=0;i<5;i++)page.locator("#zoom-in").click();
        page.locator("#world-map").waitFor();
        awaitScene();
        final String before=page.locator(".map-camera").getAttribute("style");
        final Object cells=page.locator(".map-cells i").evaluateAll("items=>items.map(item=>item.style.left+':'+item.style.top)");
        final var graph=page.locator("#graph").boundingBox();
        page.mouse().move(graph.x+100,graph.y+200);page.mouse().down();
        page.mouse().move(graph.x+280,graph.y+230);page.mouse().up();
        page.waitForFunction("before=>document.querySelector('.map-camera').getAttribute('style')!==before",before);
        assertThat(page.locator(".map-cells i").evaluateAll("items=>items.map(item=>item.style.left+':'+item.style.top)"))
                .isEqualTo(cells);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void overviewAfterHomeDoesNotReuseTheDepartedGroupsBounds() throws Exception {
        final StressScene fixture=systemStressScene();
        openProject(fixture.project());
        assertThat(page.evaluate("""
                async creator => (await fetch('/api/creator',{method:'POST',headers:mutationHeaders(),body:creator})).status
                """,fixture.creator())).isEqualTo(200);
        page.reload();awaitScene();
        page.locator("[data-group-region-label='area:stock-command']").click();
        page.waitForFunction("() => state.world.scene?.inside==='area:stock-command' && document.querySelector('#graph').dataset.cameraMoving==='false'");
        final var held=new java.util.ArrayList<Route>();
        page.route("**/api/scene?**",route->{
            if(route.request().url().contains("focus=app"))held.add(route);
            else route.resume();
        });
        try {
            page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Home").setExact(true)).click();
            page.waitForCondition(()->!held.isEmpty());
            page.locator("#scope-overview").click();
            held.forEach(Route::resume);
            awaitScene();
            assertThat(page.evaluate("() => state.world.navigation.length")).isEqualTo(0);
            assertThat(page.locator("[data-group-region-label^='area:']").count()).isEqualTo(8);
            assertThat(pageErrors).isEmpty();
        } finally {
            page.unroute("**/api/scene?**");
        }
    }

    @Test
    void narrowWorkbenchOpensWithEveryWorkAreaReachable() throws Exception {
        page.setViewportSize(430, 860);
        final StressScene fixture = systemStressScene();
        openProject(fixture.project());
        assertThat(page.evaluate("""
                async creator => (await fetch('/api/creator', {method:'POST',headers:mutationHeaders(),body:creator})).status
                """, fixture.creator())).isEqualTo(200);
        for (final int width : List.of(430, 320)) {
            page.setViewportSize(width, 860);
            page.reload();
            awaitScene();
            page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(
                    Path.of("target", "context-review")).resolve("system-" + width + ".png")));
            final var areas = page.locator("[data-group-region-label^='area:']");
            assertThat(areas.count()).isEqualTo(8);
            for (final var area : areas.all()) {
                final var box = area.boundingBox();
                assertThat(area.locator("strong").isVisible()).as("Area name at " + width).isTrue();
                assertThat(box.x).isGreaterThanOrEqualTo(0);
                assertThat(box.x + box.width).isLessThanOrEqualTo(width);
                assertThat(box.y).isGreaterThanOrEqualTo(0);
                assertThat(box.y + box.height).isLessThanOrEqualTo(860);
            }
            assertThat(areas.evaluateAll("""
                    items=>items.every((item,i)=>{
                      const a=item.getBoundingClientRect();
                      return items.slice(i+1).every(other=>{
                        const b=other.getBoundingClientRect();
                        return a.right<=b.left || a.left>=b.right || a.bottom<=b.top || a.top>=b.bottom;
                      });
                    })
                    """)).as("Named areas must not cover each other at %d",width).isEqualTo(true);
        }
        page.locator("[data-group-region-label='area:stock-command']").click();
        page.waitForFunction("() => state.world.scene?.inside === 'area:stock-command'");
        page.locator("#issue-count").click();
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Inspect Example: invalid-name").setExact(true)).click();
        page.waitForFunction("() => state.world.navigation.length === 2 && document.querySelector('#graph').dataset.cameraMoving === 'false'");
        assertThat(page.locator("#group-navigation").boundingBox().height).isLessThanOrEqualTo(52);
        assertThat(page.locator("#status-coverage").innerText()).endsWith("coverage");
        assertThat(((Number) page.locator("#status-coverage").evaluate(
                "element => parseFloat(getComputedStyle(element).fontSize)")).doubleValue()).isGreaterThanOrEqualTo(10);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void defaultAtlasKeepsTheSameCullingEnvelopeAcrossPalettes() {
        openProject(choiceProject());
        final double height = stationBounds("command").height;
        page.locator("#open-settings").click();
        page.locator("#theme-select").selectOption("classic/theme.css");
        page.waitForFunction("() => document.documentElement.dataset.architecture==='classic' && !state.themeError");
        awaitScene();
        assertThat(stationBounds("command").height).isCloseTo(height, within(.1));
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void selectedLabelsDoNotCoverNeighbouringBuildings() {
        openProject(fourStepProject());
        page.evaluate("""
                async () => {
                  state.world.dispose();
                  const original=window.fetch;
                  const nodes=Array.from({length:48},(_,i)=>({id:'label-'+i,kind:'step',name:'Selected neighbouring work '+i,
                    x:i%8*300,y:Math.floor(i/8)*180,width:168,height:64,station_scale:1}));
                  const scene={revision:'label-clearance',bounds:{x:0,y:0,width:2268,height:964},nodes,links:[],icons:{}};
                  window.fetch=(url,...args)=>String(url).startsWith('/api/scene?')
                    ?Promise.resolve(new Response(JSON.stringify(scene))):original(url,...args);
                  window.labelTarget='label-0';
                  window.__renderer=new RailixWorld(document.querySelector('#graph'),{
                    appearance:node=>({selected:node.id===window.labelTarget})
                  });
                  await window.__renderer.refresh();
                }
                """);
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving==='false'");
        assertThat((List<?>) page.evaluate("""
                async () => {
                  const world=window.__renderer,failures=[];
                  for(const node of world.scene.nodes) {
                    window.labelTarget=node.id;world.repaint();
                    await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
                    const label=document.querySelector('.world-label.selected:not(.world-icon-only)');
                    if(!label)continue;
                    const rect=label.getBoundingClientRect(),stage=document.querySelector('#graph').getBoundingClientRect();
                    const a={x:rect.x-stage.x,y:rect.y-stage.y,width:rect.width,height:rect.height};
                    for(const other of world.scene.nodes) {
                      if(other.id===node.id)continue;
                      const b=world._screenBounds(other);
                      if(a.x<b.x+b.width && a.x+a.width>b.x && a.y<b.y+b.height && a.y+a.height>b.y)
                        failures.push(node.id+' covers '+other.id);
                    }
                  }
                  return failures;
                }
                """)).as("Selecting a station must not force its name onto another building").isEmpty();
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings={"canvas"})
    void appConnectionsAreNotConstructionTargetsWithoutTheirOriginInView(final String variant) {
        openProject(ConvergingFlowE2eTest.project());
        final var metadata=(Map<?,?>)page.evaluate("""
                async () => {
                  const trigger=state.world.scene.nodes.find(node=>node.id==='command');
                  const query=new URLSearchParams({x:trigger.x,y:trigger.y,width:trigger.width,height:trigger.height,scale:1});
                  const scene=await (await fetch('/api/scene?'+query)).json();
                  const link=scene.links.find(link=>link.from==='app' && link.to==='command');
                  window.powerScene={...scene,links:[link]};
                  return {power:link.power===true,sourcePresent:scene.nodes.some(node=>node.id==='app')};
                }
                """);
        assertThat(metadata.get("sourcePresent")).isEqualTo(false);
        assertThat(metadata.get("power")).isEqualTo(true);
        page.evaluate("""
                async variant => {
                  state.world.dispose();const original=window.fetch;
                  window.fetch=(url,...args)=>String(url).startsWith('/api/scene?')
                    ?Promise.resolve(new Response(JSON.stringify(window.powerScene))):original(url,...args);
                  window.insertCalls=0;window.deselectCalls=0;
                  window.__renderer=new RailixWorld(document.querySelector('#graph'),{
                    insert:()=>window.insertCalls++,deselect:()=>window.deselectCalls++});
                  const catalog=await (await fetch('/api/themes')).json();
                  await window.__renderer.renderer(catalog.themes.find(theme=>theme.id==='').variants.find(item=>item.id===variant));
                  await window.__renderer.refresh();window.__renderer.fit();await window.__renderer.refresh();
                }
                """,variant);
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving==='false'");
        final var point=(List<?>)page.evaluate("""
                () => {
                  const world=window.__renderer,link=world.scene.links[0],a=world._project(...link.points[0]),b=world._project(...link.points[1]);
                  return world._view((a[0]+b[0])/2,(a[1]+b[1])/2,6*world._camera.scale*link.station_scale);
                }
                """);
        final var box=page.locator("#graph").boundingBox();
        page.mouse().click(box.x+((Number)point.get(0)).doubleValue(),box.y+((Number)point.get(1)).doubleValue());
        assertThat(page.evaluate("() => window.insertCalls")).isEqualTo(0);
        assertThat(page.evaluate("() => window.deselectCalls")).isEqualTo(1);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings={"canvas"})
    void anOffscreenArrivalMustBeLocatedRatherThanSilentlySkipped(final String variant) {
        final String source=ConvergingFlowE2eTest.project()
                .replace("{\"id\":\"shared\",", """
                        {"id":"work-1","use":"railix.field-manipulation","inputs":{}},
                        {"id":"work-2","use":"railix.field-manipulation","inputs":{}},{"id":"shared",
                        """)
                .replace("\"http.next\",\"to\":\"shared\"", "\"http.next\",\"to\":\"work-1\"},{\"from\":\"work-1.next\",\"to\":\"work-2\"},{\"from\":\"work-2.next\",\"to\":\"shared\"");
        openProject(source);
        page.locator("#open-settings").click();
        page.locator("#theme-variant").selectOption(variant);
        page.keyboard().press("Escape");
        finishScene(page.waitForResponse(response -> response.url().contains("focus=shared"),
                () -> page.evaluate("() => {state.world.focus('shared',3);return true;}")));
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving==='false'");
        awaitScene();
        assertThat(page.evaluate("() => state.world.scene.nodes.some(node=>node.id==='command')")).isEqualTo(false);
        final Object original=page.evaluate("async () => (await (await fetch('/api/project')).json()).project");
        clickSharedApproach("shared");
        page.locator("[data-insert-arrival='command.next']").waitFor();
        assertThat(page.locator("[data-insert-arrival='command.next']").textContent()).contains("Locate");
        page.locator("[data-insert-arrival='command.next']").click();
        page.waitForFunction("() => state.world.scene.nodes.some(node=>node.id==='command') && document.querySelector('#graph').dataset.cameraMoving==='false'");
        assertThat(page.locator("#step-search").count()).isZero();
        assertThat(page.evaluate("async () => (await (await fetch('/api/project')).json()).project")).isEqualTo(original);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings={"canvas"})
    void collapsedOutcomesOfOneChoiceRequireAnExplicitOutput(final String variant) {
        openProject("""
                {"format":1,"id":"choice-arrivals","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"route","payload":[]}]},
                  {"id":"choice","use":"railix.switch","inputs":{"cases":[
                    {"outcome":"match","option":"literal","inputs":{"value":1},"when":{"transforms":[],"all":[]}},
                    {"outcome":"extra","option":"literal","inputs":{"value":2},"when":{"transforms":[],"all":[]}}]}},
                  {"id":"shared","use":"railix.field-manipulation","inputs":{}},
                  {"id":"elsewhere","use":"railix.field-manipulation","inputs":{}}
                ],"links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"choice"},
                  {"from":"choice.match","to":"shared"},{"from":"choice.otherwise","to":"shared"},
                  {"from":"choice.extra","to":"elsewhere"},{"from":"elsewhere.next","to":"end"},
                  {"from":"shared.next","to":"end"}]}
                """);
        page.locator("#open-settings").click();
        page.locator("#theme-variant").selectOption(variant);
        page.keyboard().press("Escape");
        finishScene(page.waitForResponse(response -> response.url().contains("focus=shared"),
                () -> page.evaluate("() => {state.world.focus('shared',1);return true;}")));
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving==='false'");
        final var point=(List<?>)page.evaluate("""
                () => {
                  const world=state.world,link=world.scene.links.find(link=>link.from==='choice' && link.to==='shared');
                  const stations=new Map(world.scene.nodes.map(node=>[node.id,{node,look:world._appearance(node),box:world._planeBounds(node)}]));
                  const points=world._linkPoints(link,stations),a=points.at(-2),b=points.at(-1);
                  return world._view((a[0]+b[0])/2,(a[1]+b[1])/2,6*world._camera.scale*link.station_scale);
                }
                """);
        final var box=page.locator("#graph").boundingBox();
        page.mouse().click(box.x+((Number)point.get(0)).doubleValue(),box.y+((Number)point.get(1)).doubleValue());
        page.locator("[data-insert-arrival='choice.otherwise']").waitFor();
        assertThat(page.locator("[data-insert-arrival='choice.extra']").count()).isZero();
        assertThat(page.locator("#step-search").count()).isZero();
        page.locator("[data-insert-arrival='choice.otherwise']").click();
        page.locator("#step-search").fill("Field Manipulation");
        page.locator("[data-add-step='railix.field-manipulation']").click();
        page.waitForFunction("() => !state.actionPending && state.history.undo");
        assertThat(page.evaluate("""
                async () => {
                  const project=(await (await fetch('/api/project')).json()).project;
                  return project.links.find(link=>link.from==='choice.match').to==='shared'
                    && project.links.find(link=>link.from==='choice.extra').to==='elsewhere'
                    && project.links.find(link=>link.from==='choice.otherwise').to!=='shared';
                }
                """)).isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @Tag("responsive")
    @ValueSource(ints={1280,390})
    void openingAnEnteredGroupsInspectorDoesNotCancelItsPendingEntry(final int width) {
        page.setViewportSize(width,800);
        openNamedGroupProject();
        final Locator group=page.locator("[data-region-group='pair']");
        final String region=group.getAttribute("data-group-region-label");
        final List<Route> held=new ArrayList<>();
        page.route("**/api/scene?**",route->{
            final var query=java.net.URLDecoder.decode(java.net.URI.create(route.request().url()).getRawQuery(),StandardCharsets.UTF_8);
            if(List.of(query.split("&")).contains("focus="+region))held.add(route);
            else route.resume();
        });
        group.click();
        page.waitForCondition(()->!held.isEmpty());
        page.locator("#world-location [data-inspect-region]").click();
        page.waitForFunction("id=>document.querySelector('#inspector').dataset.selection===id",region);
        page.unroute("**/api/scene?**");
        held.forEach(Route::resume);
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving==='false' && document.querySelector('#zoom-level').textContent==='50%'");
        assertThat(page.locator("[data-select-node='one']").isVisible()).isTrue();
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"true,false","false,false","true,true","false,true"})
    void bridgePickingSeparatesTheVisibleDeckFromItsNearbyUnderpass(final boolean forward, final boolean shared) {
        openProject(fourStepProject());
        page.evaluate("""
                async settings=>{
                  state.world.dispose();
                  const upper={id:'upper',from:'left',to:'right',outcome:'next',station_scale:1,
                    points:settings.forward?[[0,200],[640,200]]:[[640,200],[0,200]],bridges:[{at:[316,200],under:'lower'}]};
                  const scene={revision:'bridge-picking',bounds:{x:-100,y:-100,width:840,height:600},nodes:[],links:[upper,
                    {id:'lower',from:'top',to:'bottom',outcome:'next',station_scale:1,points:[[316,40],[316,360]]},
                    ...(settings.shared?[{...upper,id:'shared-upper',from:'other'}]:[])]};
                  const original=window.fetch;
                  window.fetch=(url,...args)=>String(url).startsWith('/api/scene?')
                    ?Promise.resolve(new Response(JSON.stringify(scene))):original(url,...args);
                  window.__renderer=new RailixWorld(document.querySelector('#graph'),{
                    insert:(_from,_outcome,arrivals)=>window.selectedArrivals=arrivals.map(link=>link.id)
                  });
                  await window.__renderer.refresh();
                }
                """,Map.of("forward",forward,"shared",shared));
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving==='false'");
        page.evaluate("async()=>{window.__renderer.zoom(.25);await window.__renderer.refresh();}");
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving==='false'");
        final List<?> point=(List<?>)page.evaluate("""
                ()=>{const world=window.__renderer,box=document.querySelector('#graph').getBoundingClientRect();
                  const p=world._view(...world._project(316,200),30*world._camera.scale);
                  return [box.x+p[0],box.y+p[1]];}
                """);
        final double x=((Number)point.get(0)).doubleValue(),y=((Number)point.get(1)).doubleValue();
        page.mouse().click(x,y+8);
        assertThat(page.evaluate("()=>window.selectedArrivals")).isEqualTo(List.of("lower"));
        page.mouse().click(x,y);
        assertThat(page.evaluate("()=>window.selectedArrivals")).isEqualTo(shared?List.of("upper","shared-upper"):List.of("upper"));
        page.evaluate("async()=>{window.__renderer.zoom(4);await window.__renderer.refresh();}");
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving==='false'");
        final List<?> edge=(List<?>)page.evaluate("""
                ()=>{const world=window.__renderer,box=document.querySelector('#graph').getBoundingClientRect(),unit=world._camera.scale;
                  const plane=world._project(316,200),p=world._view(plane[0],plane[1]+14*unit,30*unit);
                  return [box.x+p[0],box.y+p[1]];}
                """);
        page.mouse().click(((Number)edge.get(0)).doubleValue(),((Number)edge.get(1)).doubleValue());
        assertThat(page.evaluate("()=>window.selectedArrivals")).isEqualTo(shared?List.of("upper","shared-upper"):List.of("upper"));
        page.evaluate("()=>window.__renderer.dispose()");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans={true,false})
    void bridgeParcelsFollowTheRenderedSurfaceDuringTheWholeCycle(final boolean forward) throws IOException {
        startRendererTraffic(10,30_000,true);
        page.evaluate("""
                async forward=>{
                  const world=window.__renderer,original=window.fetch;
                  const scene={revision:'bridge-cargo',bounds:{x:0,y:0,width:640,height:400},nodes:[],icons:{},links:[
                    {id:'upper',from:'left',to:'right',outcome:'next',station_scale:1,
                      points:forward?[[0,200],[640,200]]:[[640,200],[0,200]],bridges:[{at:[316,200],under:'lower'}]},
                    {id:'lower',from:'top',to:'bottom',outcome:'next',station_scale:1,points:[[316,40],[316,360]]}]};
                  window.fetch=(url,...args)=>String(url).startsWith('/api/scene?')
                    ?Promise.resolve(new Response(JSON.stringify(scene))):original(url,...args);
                  await world.refresh();world.fit();await world.refresh();
                }
                """,forward);
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving==='false'");
        assertThat((List<?>)page.evaluate("""
                async()=>{
                  const world=window.__renderer,failures=[];
                  const ctx=document.querySelector('.world-raster').getContext('2d');
                  let path=[],roofs=[];
                  const move=ctx.moveTo,line=ctx.lineTo,begin=ctx.beginPath,fill=ctx.fill;
                  ctx.beginPath=function(...args){path=[];return begin.apply(this,args);};
                  ctx.moveTo=function(x,y){path.push([x,y]);return move.call(this,x,y);};
                  ctx.lineTo=function(x,y){path.push([x,y]);return line.call(this,x,y);};
                  ctx.fill=function(...args){if(this.fillStyle===world._surface.palette.cargoTop && path.length===4)roofs.push(path.slice());return fill.apply(this,args);};
                  let checks=0;
                  try {
                    for(const factor of [.5,2,2]) {
                      world.zoom(factor);await world.refresh();
                      await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
                      for(let frame=0;frame<100;frame++) {
                        roofs=[];await new Promise(requestAnimationFrame);
                        const surface=world._surface,m=surface.m;
                        const paths=surface.routes.flatMap(route=>route.segments.map(segment=>
                          [segment.a,segment.b].map(p=>surface.point(p[0],p[1],6*route.unit+(p[2]||0)))));
                        for(const roof of roofs) {
                          const unit=surface.routes[0].unit;
                          const p=[roof.reduce((sum,v)=>sum+v[0],0)/4-m.m31*12*unit,
                            roof.reduce((sum,v)=>sum+v[1],0)/4-m.m32*12*unit];
                          const distance=Math.min(...paths.map(([a,b])=>{
                            const dx=b[0]-a[0],dy=b[1]-a[1],t=Math.max(0,Math.min(1,((p[0]-a[0])*dx+(p[1]-a[1])*dy)/(dx*dx+dy*dy)));
                            return Math.hypot(p[0]-a[0]-t*dx,p[1]-a[1]-t*dy);
                          }));
                          checks++;if(distance>1)failures.push({factor,frame,distance});
                        }
                      }
                    }
                  } finally {ctx.moveTo=move;ctx.lineTo=line;ctx.beginPath=begin;ctx.fill=fill;}
                  if(checks<100)failures.push({error:'Insufficient painted parcels',checks});
                  return failures;
                }
                """)).as("Painted parcel contact points stay on the actual projected ramp paths through a complete cycle").isEmpty();
        final Path output=Files.createDirectories(Path.of("target/bridge-cargo-canvas"));
        page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve(forward?"east.png":"west.png")));
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"canvas,true", "canvas,false"})
    void collapsedArrivalCannotBeSilentlySkippedWhenEditingASharedBelt(final String variant, final boolean shared) {
        final String source=ConvergingFlowE2eTest.project()
                .replace("{\"id\":\"shared\",", "{\"id\":\"payment\",\"use\":\"railix.field-manipulation\",\"inputs\":{}},{\"id\":\"shared\",")
                .replace("\"http.next\",\"to\":\"shared\"", "\"http.next\",\"to\":\"payment\"},{\"from\":\"payment.next\",\"to\":\"shared\"");
        openProject(shared ? source : source.replace("\"command.next\",\"to\":\"shared\"", "\"command.next\",\"to\":\"end\""));
        assertThat(page.evaluate("""
                async () => (await fetch('/api/creator',{method:'POST',headers:mutationHeaders(),
                  body:JSON.stringify({format:2,groups:[{id:'payments',name:'Payments'}],steps:{payment:{group:'payments'}}})})).status
                """)).isEqualTo(200);
        page.reload();
        page.locator("#open-settings").click();
        page.locator("#theme-variant").selectOption(variant);
        page.keyboard().press("Escape");
        finishScene(page.waitForResponse(response -> response.url().contains("focus=shared"),
                () -> page.evaluate("() => {state.world.focus('shared',1);return true;}")));
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving==='false'");
        final Object original=page.evaluate("async () => (await (await fetch('/api/project')).json()).project");
        if (shared) clickSharedApproach("shared");
        else {
            final var point=(List<?>)page.evaluate("""
                    () => {
                      const world=state.world,link=world.scene.links.find(link=>link.to==='shared');
                      const stations=new Map(world.scene.nodes.map(node=>[node.id,{node,look:world._appearance(node),box:world._planeBounds(node)}]));
                      const points=world._linkPoints(link,stations),a=points[0],b=points[1];
                      return world._view((a[0]+b[0])/2,(a[1]+b[1])/2,6*world._camera.scale*link.station_scale);
                    }
                    """);
            final var box=page.locator("#graph").boundingBox();
            page.mouse().click(box.x+((Number)point.get(0)).doubleValue(),box.y+((Number)point.get(1)).doubleValue());
        }
        page.getByRole(com.microsoft.playwright.options.AriaRole.DIALOG,
                new Page.GetByRoleOptions().setName("Choose incoming route").setExact(true)).waitFor();
        assertThat(page.locator("[data-insert-arrival]").count()).isEqualTo(shared ? 2 : 1);
        page.locator("[data-insert-arrival]").filter(new Locator.FilterOptions().setHasText("Payments")).click();
        page.waitForFunction("() => state.world.scene.inside?.startsWith('group-region:payments:')");
        assertThat(page.locator("#step-search").count()).isZero();
        assertThat(page.evaluate("async () => (await (await fetch('/api/project')).json()).project")).isEqualTo(original);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"canvas"})
    void sharedBeltRequiresAnExplicitArrivalBeforeInsertion(final String variant) throws IOException {
        openProject(ConvergingFlowE2eTest.project());
        assertThat(page.evaluate("""
                async () => (await fetch('/api/creator',{method:'POST',headers:mutationHeaders(),
                  body:JSON.stringify({format:2,groups:[],steps:{command:{name:'Receive event'},http:{name:'Receive event'}}})})).status
                """)).isEqualTo(200);
        page.reload();
        page.locator("#open-settings").click();
        page.locator("#theme-variant").selectOption(variant);
        page.keyboard().press("Escape");
        finishScene(page.waitForResponse(response -> response.url().contains("focus=shared"),
                () -> page.evaluate("() => {state.world.focus('shared',1);return true;}")));
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving==='false'");
        final Object original = page.evaluate("async () => (await (await fetch('/api/project')).json()).project");
        final List<?> point=sharedApproach("shared");
        assertThat(point).as("A real common approach must be clickable").isNotNull();
        final var box = page.locator("#graph").boundingBox();
        final double x=box.x+((Number)point.get(0)).doubleValue(), y=box.y+((Number)point.get(1)).doubleValue();
        page.mouse().click(x,y);
        page.getByRole(com.microsoft.playwright.options.AriaRole.DIALOG,
                new Page.GetByRoleOptions().setName("Choose incoming route").setExact(true)).waitFor();
        assertThat(page.locator("#step-search").count()).isZero();
        assertThat(page.locator("[data-insert-arrival]").count()).isEqualTo(2);
        assertThat(page.locator("[data-insert-arrival='http.next']").textContent()).contains("HTTP");
        assertThat(page.locator("[data-insert-arrival='command.next']").textContent()).contains("CLI");
        page.locator("[data-insert-arrival='http.next']").focus();
        assertThat(page.evaluate("() => state.world.scene.links.filter(link=>worldLinkAppearance(link).editing).map(link=>link.from+'.'+link.outcome)"))
                .isEqualTo(List.of("http.next"));
        final Path output=Files.createDirectories(Path.of("target/system-corridors",variant));
        page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve("arrival-chooser.png")));
        assertArrivalChooserClearsHud();
        page.keyboard().press("Escape");
        assertThat(page.evaluate("async () => (await (await fetch('/api/project')).json()).project")).isEqualTo(original);
        page.mouse().click(x,y);
        page.locator("[data-insert-arrival='http.next']").click();
        page.locator("#step-search").waitFor();
        assertThat(page.evaluate("() => state.picker.anchor+'.'+state.picker.outcome")).isEqualTo("http.next");
        page.locator("#step-search").fill("Field Manipulation");
        page.locator("[data-add-step='railix.field-manipulation']").click();
        page.waitForFunction("() => !state.actionPending && state.history.undo");
        assertThat(page.evaluate("""
                async () => {
                  const project=(await (await fetch('/api/project')).json()).project;
                  const chosen=project.links.find(link=>link.from==='http.next').to;
                  return chosen!=='shared' && project.links.some(link=>link.from===chosen+'.next' && link.to==='shared')
                    && project.links.some(link=>link.from==='command.next' && link.to==='shared');
                }
                """)).isEqualTo(true);
        page.locator("#editor-undo").click();
        page.waitForFunction("() => !state.actionPending && state.history.redo");
        assertThat(page.evaluate("async () => (await (await fetch('/api/project')).json()).project")).isEqualTo(original);
        finishScene(page.waitForResponse(response -> response.url().contains("focus=shared"),
                () -> page.evaluate("() => {state.world.focus('shared',1);return true;}")));
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving==='false'");
        clickSharedApproach("shared");
        page.locator("[data-insert-arrival='command.next']").waitFor();
        page.setViewportSize(430,860);
        page.locator("[data-insert-arrival='command.next']").focus();
        page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve("arrival-chooser-narrow.png")));
        assertThat(page.locator("[data-insert-arrival='command.next']").evaluate("element => {const box=element.getBoundingClientRect();return box.left>=0 && box.right<=innerWidth && box.top>=0 && box.bottom<=innerHeight;}"))
                .isEqualTo(true);
        assertArrivalChooserClearsHud();
        for (final int width : List.of(640,320)) {
            page.setViewportSize(width,360);
            awaitScene();
            for (final Locator control : page.locator(".arrival-palette button").all()) {
                control.scrollIntoViewIfNeeded();
                page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve("arrival-" + width + ".png")));
                assertThat(control.evaluate("""
                        element=>{const box=element.getBoundingClientRect(),panel=element.closest('.step-picker').getBoundingClientRect();
                          const hit=document.elementFromPoint(box.x+box.width/2,box.y+box.height/2);
                          return box.top>=panel.top && box.bottom<=panel.bottom && box.top>=0 && box.bottom<=innerHeight
                            && (hit===element || element.contains(hit));}
                        """)).as("Arrival control must remain reachable at %dx360: %s",width,control.textContent()).isEqualTo(true);
            }
            assertArrivalChooserClearsHud();
            page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve("arrival-chooser-"+width+"x360.png")));
        }
        assertThat(pageErrors).isEmpty();
    }

    private List<?> sharedApproach(final String target) {
        return (List<?>)page.evaluate("""
                target => {
                  const world=state.world,links=world.scene.links.filter(link=>link.to===target);
                  const stations=new Map(world.scene.nodes.map(node=>[node.id,{node,look:world._appearance(node),box:world._planeBounds(node)}]));
                  const paths=links.map(link=>world._linkPoints(link,stations));
                  for(let i=1;i<paths[0].length;i++) for(let j=1;j<paths[1].length;j++) {
                    const [a,b]=[paths[0][i-1],paths[0][i]], [c,d]=[paths[1][j-1],paths[1][j]];
                    if(a[1]===b[1] && c[1]===d[1] && a[1]===c[1]) {
                      const left=Math.max(Math.min(a[0],b[0]),Math.min(c[0],d[0]));
                      const right=Math.min(Math.max(a[0],b[0]),Math.max(c[0],d[0]));
                      if(right-left>1) return world._view((left+right)/2,a[1],6*world._camera.scale*links[0].station_scale);
                    }
                    if(a[0]===b[0] && c[0]===d[0] && a[0]===c[0]) {
                      const top=Math.max(Math.min(a[1],b[1]),Math.min(c[1],d[1]));
                      const bottom=Math.min(Math.max(a[1],b[1]),Math.max(c[1],d[1]));
                      if(bottom-top>1) return world._view(a[0],(top+bottom)/2,6*world._camera.scale*links[0].station_scale);
                    }
                  }
                  return null;
                }
                """,target);
    }

    private void clickSharedApproach(final String target) {
        final var point=sharedApproach(target);
        assertThat(point).as("A real common approach must be clickable").isNotNull();
        final var box=page.locator("#graph").boundingBox();
        page.mouse().click(box.x+((Number)point.get(0)).doubleValue(),box.y+((Number)point.get(1)).doubleValue());
    }

    private void assertArrivalChooserClearsHud() {
        assertThat(page.locator(".arrival-palette").evaluate("""
                element=>{const a=element.getBoundingClientRect();
                  return [...document.querySelectorAll('#world-hud button, #workbench-actions button')]
                    .filter(button=>button.checkVisibility()).every(button=>{
                      const b=button.getBoundingClientRect();
                      return a.right<=b.left || a.left>=b.right || a.bottom<=b.top || a.top>=b.bottom;
                    });}
                """)).as("Choosing an arrival must not cover the radar or app controls").isEqualTo(true);
        assertThat(page.locator(".arrival-palette").evaluate("""
                element=>{const a=element.getBoundingClientRect(),b=document.querySelector('#world-hud').getBoundingClientRect();
                  return a.bottom<=b.top || a.top>=b.bottom || a.right+8<=b.left || a.left>=b.right+8;}
                """)).as("The chooser must leave a visible gap beside the radar").isEqualTo(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"canvas"})
    @Timeout(300)
    void hugeSystemKeepsIndependentRoutesReadableAndExamplesLive(final String variant) throws IOException {
        final StressScene fixture = systemStressScene();
        final int port;
        try (var socket = new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        final String source = fixture.project().replace("\"port\":0", "\"port\":" + port);
        final Path output = Files.createDirectories(Path.of("target/system-corridors", variant));
        Files.writeString(output.resolve("railix.project.json"), source);
        Files.writeString(output.resolve("railix.creator.json"), fixture.creator());
        openProject(source);
        final String generated = Files.readString(Path.of((String)page.evaluate("() => state.application.source_path")));
        assertThat(page.evaluate("""
                async creator => (await fetch('/api/creator', {
                  method:'POST',headers:mutationHeaders(),body:creator
                })).status
                """, fixture.creator())).isEqualTo(200);
        page.reload();
        page.waitForFunction("() => typeof state !== 'undefined' && state.application?.examples?.state === 'completed'");
        assertThat(Files.readString(Path.of((String)page.evaluate("() => state.application.source_path")))).isEqualTo(generated);
        Files.writeString(output.resolve("examples.json"),(String)page.evaluate("async () => JSON.stringify(await (await fetch('/api/examples')).json())"));
        assertThat(page.evaluate("() => state.application.examples.succeeded")).isEqualTo(27);
        assertThat(page.evaluate("() => state.application.examples.failed")).isEqualTo(1);
        page.locator("#open-settings").click();
        page.locator("#theme-variant").selectOption(variant);
        page.waitForFunction("variant => document.querySelector('#graph').dataset.renderer === 'canvas'", variant);
        page.keyboard().press("Escape");
        try {
            page.waitForFunction("() => [...document.querySelectorAll('[data-group-region-label^=\"area:\"]:not(.world-icon-only)')].length===8",null,new Page.WaitForFunctionOptions().setTimeout(5000));
        } catch (final com.microsoft.playwright.TimeoutError failure) {
            page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve("home.png")));
            throw new AssertionError((String)page.evaluate("() => JSON.stringify({labels:[...document.querySelectorAll('[data-group-region-label]')].map(e=>({id:e.dataset.groupRegionLabel,classes:e.className,text:e.textContent})),nodes:state.world.scene.nodes.map(n=>({id:n.id,expanded:n.expanded,summary:n.summary})),error:document.querySelector('#world-error').textContent})"),failure);
        }
        page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve("home.png")));
        selectWorldNode("command");
        page.locator("#visible-example").selectOption("command:6");
        page.waitForFunction("() => state.application.example?.id === 'command:6'");
        page.locator("#close-inspector").click();
        fitWorld();
        page.waitForFunction("""
                () => {
                  const areas=[...document.querySelectorAll('[data-group-region-label^="area:"]')];
                  return areas.length===8 && areas.filter(element=>element.dataset.coverage==='selected').length===1
                    && areas.find(element=>element.dataset.groupRegionLabel==='area:stock-command')?.dataset.coverage==='selected';
                }
                """);
        page.locator("[data-group-region-label='area:stock-command']").click();
        page.waitForFunction("() => state.world.scene?.inside==='area:stock-command'");
        page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve("areas.png")));
        inspectGroup("stock-group-lane-0-0");
        page.waitForFunction("() => document.querySelector('#selection-evidence')?.textContent.includes('ada lovelace')");
        page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve("group-example.png")));
        page.locator("#close-inspector").click();
        finishScene(page.waitForResponse(response -> response.url().contains("focus=stock-audit-0"),
                () -> page.evaluate("() => {state.world.focus('stock-audit-0',.85);return true;}")));
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving==='false' && state.world.scene.nodes.some(node=>node.id==='stock-audit-0')");
        page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve("corridors.png")));
        final var bridge = (Map<?, ?>)page.evaluate("""
                () => {
                  const world=state.world,stations=new Map(world.scene.nodes.map(node=>[node.id,{node,look:world._appearance(node),box:world._planeBounds(node)}]));
                  for(const link of world.scene.links) {
                    const rail={link,points:world._linkPoints(link,stations),look:{unit:world._camera.scale*link.station_scale}};
                    for(const bridge of world._bridges(rail)) {
                      const [x,y]=world._view(bridge.at[0],bridge.at[1],30*bridge.unit);
                      if(x>120 && x<world._size.width-160 && y>240 && y<world._size.height-120)
                        return {x,y,from:link.from,outcome:link.outcome};
                    }
                  }
                  return null;
                }
                """);
        assertThat(bridge).as("A visible crossing, not an inferred junction, must be inspectable").isNotNull();
        final var graphBox = page.locator("#graph").boundingBox();
        final double bridgeX = graphBox.x + ((Number)bridge.get("x")).doubleValue(), bridgeY = graphBox.y + ((Number)bridge.get("y")).doubleValue();
        page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve("bridge.png"))
                .setClip(bridgeX-110,bridgeY-80,220,160));
        page.mouse().click(bridgeX,bridgeY);
        page.locator("#step-search").waitFor();
        assertThat(page.evaluate("() => state.picker.anchor")).isEqualTo(bridge.get("from"));
        assertThat(page.evaluate("() => state.picker.outcome")).isEqualTo(bridge.get("outcome"));
        page.keyboard().press("Escape");
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Clear Example").setExact(true)).click();
        final var body = JsonParser.parseString(source).getAsJsonObject().getAsJsonArray("nodes").asList().stream()
                .map(com.google.gson.JsonElement::getAsJsonObject).filter(node -> node.get("id").getAsString().equals("api"))
                .findFirst().orElseThrow().getAsJsonArray("examples").get(0).getAsJsonObject().getAsJsonObject("payload").get("body").toString();
        final var responses = new java.util.concurrent.ConcurrentLinkedQueue<Integer>();
        try (var client = java.net.http.HttpClient.newHttpClient();var traffic = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()) {
            final var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/orders"))
                    .header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
            final var sending = traffic.scheduleAtFixedRate(() -> {
                try { responses.add(client.send(request,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()); }
                catch (final InterruptedException failure) { Thread.currentThread().interrupt();throw new IllegalStateException(failure); }
                catch (final IOException failure) { throw new UncheckedIOException(failure); }
            },0,100,java.util.concurrent.TimeUnit.MILLISECONDS);
            try {
                page.waitForFunction("() => state.world.scene.links.some(link => worldLinkAppearance(link).rate>0)");
                assertThat(responses).isNotEmpty().containsOnly(200);
                page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve("live-http.png")));
            } finally { sending.cancel(false); }
        }
        Files.writeString(output.resolve("scene.json"),(String)page.evaluate("() => JSON.stringify(state.world.scene)"));
        final Object measurements = page.evaluate("""
                async () => {
                  const times=[];let previous=await new Promise(requestAnimationFrame);
                  for(let index=0;index<100;index++) {
                    const now=await new Promise(requestAnimationFrame);
                    if(index>=10)times.push(now-previous);
                    previous=now;
                  }
                  times.sort((a,b)=>a-b);
                  return {nodes:state.world.scene.nodes.length,links:state.world.scene.links.length,
                    bridgeCount:state.world.scene.links.reduce((sum,link)=>sum+(link.bridges?.length||0),0),
                    p50:times[45],p95:times[85],heap:performance.memory?.usedJSHeapSize??null,
                    dom:document.querySelectorAll('#graph *').length};
                }
                """);
        Files.writeString(output.resolve("measurements.json"),new com.google.gson.Gson().toJson(measurements));
        System.out.println("RAILIX_SYSTEM_CORRIDORS renderer=" + variant + " " + measurements);
        page.setViewportSize(430, 860);
        awaitScene();
        selectWorldNode("stock-audit-0");
        openInspectorTab("overview");
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve("narrow.png")));
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void contextJsonPreservesArraysAbsenceExactNumbersAndLazyUnchangedBranches() throws IOException {
        final JsonObject before = JsonParser.parseString("""
                {"items":[{"amount":1,"keep":true},2,3],"mode":{"old":1},"arrayMode":[1],
                 "empty":{},"cleared":{"gone":1},"clearedArray":[1,2],"removed":null,"nullable":1,"zero":2,"flag":true,"__proto__":null,
                 "a.b":{},"unsafe":"<img src=x onerror=alert(1)>"}
                """).getAsJsonObject();
        final JsonObject after = JsonParser.parseString("""
                {"items":[{"amount":2,"keep":true},2],"mode":"done","arrayMode":{"nested":{"ready":true}},
                 "empty":[],"cleared":{},"clearedArray":[],"nullable":null,"zero":0,"flag":false,"__proto__":{"safe":true},
                 "a.b":{},"unsafe":"<script>alert(1)</script>"}
                """).getAsJsonObject();
        before.getAsJsonObject("a.b").addProperty("quote\"", "before");
        after.getAsJsonObject("a.b").addProperty("quote\"", "after");
        before.addProperty("long", "a".repeat(100_000));
        after.addProperty("long", "b".repeat(100_000));
        final JsonObject stable = new JsonObject();
        for (int index = 0; index < 201; index++) stable.addProperty("field" + index, "unchanged-" + index);
        before.add("stable", stable);
        after.add("stable", stable.deepCopy());
        before.addProperty("precise", new java.math.BigDecimal("9007199254740993.123456789"));
        after.addProperty("precise", new java.math.BigDecimal("9007199254740993.123456788"));
        openProject("""
                {"format":1,"id":"context-diff","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"changes","payload":[],"context":{"document":%s,"replacement":%s}}]},
                  {"id":"change","use":"railix.field-manipulation","inputs":{
                    "field":["context","document"],"value":[{"option":"field","inputs":{"source":["context","replacement"]}}],"steps":[]}}],
                 "links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"change"},{"from":"change.next","to":"end"}]}
                """.formatted(before, after));
        selectTrigger();
        page.locator("#visible-example").selectOption("command:0");
        selectWorldNode("change");
        openInspectorTab("overview");
        page.waitForFunction("() => document.querySelector('#selection-evidence')?.textContent.includes('9007199254740993.123456788')");
        final Locator evidence = page.locator("#selection-evidence");
        assertThat(evidence.locator("[data-context-change='document.items[0].amount']").textContent()).contains("1", "2");
        assertThat(evidence.locator("[data-context-change='document.items[2]']").getAttribute("aria-label")).isEqualTo("Removed document.items[2]");
        assertThat(evidence.locator("[data-context-change='document.cleared.gone']").textContent()).contains("1");
        assertThat(evidence.locator("[data-context-change='document.clearedArray[0]']").getAttribute("aria-label"))
                .isEqualTo("Removed document.clearedArray[0]");
        assertThat(evidence.locator("[data-context-change='document.removed']").textContent()).contains("null");
        assertThat(evidence.locator("[data-context-change='document.removed'] ins").count()).isZero();
        assertThat(evidence.locator("[data-context-change='document.nullable'] ins").textContent()).isEqualTo("null");
        assertThat(evidence.locator("[data-context-change='document.zero'] ins").textContent()).isEqualTo("0");
        assertThat(evidence.locator("[data-context-change='document.flag'] ins").textContent()).isEqualTo("false");
        assertThat(evidence.locator("[data-context-change='document.precise']").textContent())
                .contains("9007199254740993.123456789", "9007199254740993.123456788");
        assertThat(evidence.locator("[data-context-change='document.empty']").textContent()).contains("{}", "[]");
        assertThat(evidence.locator("[data-context-change='document.__proto__.safe']").textContent()).contains("true");
        assertThat(evidence.locator("[data-context-change='document.arrayMode.nested.ready']").textContent()).contains("true");
        assertThat(evidence.locator("img,script").count()).isZero();
        assertThat(evidence.locator("[data-context-change='document.long']").textContent().length()).isLessThan(250);
        assertThat(evidence.textContent()).contains("<script>alert(1)</script>", "a.b", "after");
        assertThat(evidence.locator("[data-context-change='document.stable']").count()).isZero();
        final Locator old = evidence.locator("[data-context-change='document.mode'] .context-old");
        old.locator("summary").click();
        page.waitForFunction("() => document.querySelector('.context-old[open]')?.textContent.includes('old')");
        assertThat(old.textContent()).contains("old", "1");
        final Locator eye = evidence.locator("[data-evidence-fields]");
        eye.focus();
        page.keyboard().press("Enter");
        assertThat(eye.getAttribute("aria-pressed")).isEqualTo("true");
        assertThat(eye.evaluate("element => element === document.activeElement")).isEqualTo(true);
        final Locator stableBranch = evidence.locator("[data-context-change='document.stable']");
        assertThat(stableBranch.getAttribute("open")).isNull();
        stableBranch.locator("summary").click();
        page.waitForFunction("() => document.querySelector('[data-context-more]')?.textContent.includes('121')");
        assertThat(stableBranch.locator(".context-leaf").count()).isEqualTo(80);
        stableBranch.locator("[data-context-more]").click();
        stableBranch.locator("[data-context-more]").click();
        assertThat(stableBranch.locator(".context-leaf").count()).isEqualTo(201);
        final Locator changedBranch = evidence.locator("[data-context-change='document.items']");
        changedBranch.locator(":scope > summary").click();
        page.waitForFunction("() => !document.querySelector('[data-context-change=\"document.items\"]').open");
        openInspectorTab("inspect");
        openInspectorTab("overview");
        assertThat(changedBranch.getAttribute("open")).isNull();
        assertThat(stableBranch.getAttribute("open")).isNotNull();
        assertThat(stableBranch.locator(".context-leaf").count()).isEqualTo(201);
        changedBranch.locator(":scope > summary").click();
        page.waitForFunction("""
                () => [...document.querySelectorAll('#selection-evidence [data-context-change]')]
                  .some(row=>row.dataset.contextChange==='document.items[0].keep')
                """);
        assertThat(evidence.locator("[data-context-change='document.items[0].keep']").count()).isEqualTo(1);
        assertThat(evidence.locator("[data-context-change='document.items[0].keep']").getAttribute("aria-label"))
                .isEqualTo("Unchanged document.items[0].keep");
        page.waitForResponse(response -> response.url().endsWith("/api/application") && response.status() == 200,
                () -> page.evaluate("() => document.querySelector('[data-context-change=\"document.stable\"]').dataset.sentinel='kept'"));
        assertThat(stableBranch.getAttribute("data-sentinel")).isEqualTo("kept");
        assertThat(stableBranch.getAttribute("open")).isNotNull();
        assertThat(stableBranch.locator(".context-leaf").count()).isEqualTo(201);
        final Locator longValue = evidence.locator("[data-context-change='document.long'] [data-context-side='after']");
        longValue.locator("summary").click();
        page.waitForFunction("() => document.querySelector('[data-context-value][data-context-side=\"after\"] output')?.textContent.length>100_000");
        assertThat(longValue.locator("output").textContent()).contains("b".repeat(100_000));
        longValue.locator("summary").click();
        page.waitForFunction("() => !document.querySelector('[data-context-value][data-context-side=\"after\"]').open");
        page.setViewportSize(390, 760);
        eye.click();
        final Path screenshots = Files.createDirectories(Path.of("target/creator-evidence/json"));
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("changed-fields-narrow.png")));
        assertThat(evidence.locator("[data-context-change='document.stable']").count()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints={390,1280})
    void aStoppedExampleKeepsItsRecordedInputInspectableWithoutInventingOutput(final int width) {
        page.setViewportSize(width,900);
        openProject(CreatorEditorBrowserIT.attentionProject().replace("\"name\":null",
                "\"name\":null,\"zero\":0,\"flag\":false,\"empty\":\"\",\"markup\":\"<script>unsafe</script>\""));
        page.locator("#issue-count").click();
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Inspect Example: invalid-name").setExact(true)).click();
        openInspectorTab("overview");
        final Locator evidence=page.locator("#selection-evidence");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(evidence).containsText("Stopped here");
        final Locator eye=page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Show input context").setExact(true));
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(eye).isVisible();
        assertThat(evidence.locator(".context-tree").count()).isZero();
        assertThat(evidence.innerText()).doesNotContain("Nested Step","text.normalize-space");
        final Locator details=evidence.locator(".issues details");
        details.locator("summary").click();
        final var reads=new ArrayList<String>();
        page.onRequest(request->{if(request.url().matches(".*/api/examples/(?:steps/.*|.*/view.*)"))reads.add(request.url());});
        eye.focus();page.keyboard().press("Enter");
        assertThat(details.getAttribute("open")).as("Opening input keeps technical details open").isNotNull();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(evidence.getByText("Input context",
                new Locator.GetByTextOptions().setExact(true))).isVisible();
        final Locator input=evidence.locator("[aria-label='Recorded input context']");
        input.locator("[data-context-change='payload'] > summary").click();
        input.locator("[data-context-change='payload.name']").waitFor();
        assertThat(details.getAttribute("open")).isNotNull();
        assertThat(input.textContent()).contains("false","0","\"\"","<script>unsafe</script>");
        assertThat(input.locator("ins,del,script").count()).isZero();
        assertThat(input.locator("[data-context-change='payload.name']").getAttribute("aria-label")).isEqualTo("Before payload.name");
        details.locator("summary").click();
        assertThat(evidence.innerText()).doesNotContain("No fields changed","Nested Step","text.normalize-space");
        assertThat(evidence.locator(".issues details").textContent()).contains("text.normalize-space");
        assertThat(reads).isEmpty();
        for(int poll=0;poll<2;poll++)page.waitForResponse(response->response.url().endsWith("/api/application"),()->{});
        openInspectorTab("inspect");openInspectorTab("overview");
        assertThat(input.locator("[data-context-change='payload']").getAttribute("open")).isNotNull();
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Show setting").setExact(true)).click();
        assertThat(page.evaluate("() => document.activeElement.dataset.settingPath")).isEqualTo("nodes[2].inputs.steps[0]");
        page.locator("#visible-example").selectOption("command:0");
        openInspectorTab("overview");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(evidence).containsText("RAILIX");
        assertThat(evidence.textContent()).doesNotContain("Stopped here","unsafe");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Show all fields").setExact(true))).isVisible();
        page.locator("#visible-example").selectOption("command:1");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(eye).isVisible();
        assertThat(evidence.locator(".context-tree").count()).isZero();
        selectWorldNode("result");openInspectorTab("overview");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(evidence).containsText("Not reached");
        assertThat(evidence.locator("[data-evidence-fields],.context-tree").count()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void unavailableRecordedInputClearsTheOldTreeAndWaitsForExplicitRetry() {
        openProject(CreatorEditorBrowserIT.attentionProject());
        page.locator("#issue-count").click();
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Inspect Example: invalid-name").setExact(true)).click();
        openInspectorTab("overview");
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Show input context").setExact(true)).click();
        final Locator evidence=page.locator("#selection-evidence");
        evidence.locator(".context-tree").waitFor();
        final var reads=new ArrayList<String>();
        page.route("**/api/examples/steps/**",route->{reads.add(route.request().url());route.abort();});
        selectWorldNode("normalize");openInspectorTab("overview");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(evidence).containsText("Unavailable");
        assertThat(evidence.locator(".context-tree,[data-evidence-fields]").count()).isZero();
        final int failedReads=reads.size();
        assertThat(failedReads).isPositive();
        for(int poll=0;poll<2;poll++)page.waitForResponse(response->response.url().endsWith("/api/application"),()->{});
        assertThat(reads).hasSize(failedReads);
        page.unroute("**/api/examples/steps/**");
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Retry Example values").setExact(true)).click();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(evidence).containsText("Stopped here");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(evidence.locator("[data-evidence-fields]"))
                .isVisible();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void failedInputKeepsLargeBranchesAndLongValuesBoundedUntilExpanded() {
        final var payload=new JsonObject();
        payload.add("name",com.google.gson.JsonNull.INSTANCE);
        final var fields=new JsonObject();
        for(int index=0;index<201;index++)fields.addProperty("field"+index,index);
        payload.add("many",fields);
        payload.addProperty("long","x".repeat(100_000));
        openProject(CreatorEditorBrowserIT.attentionProject().replace("{\"name\":null}",payload.toString()));
        page.locator("#issue-count").click();
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Inspect Example: invalid-name").setExact(true)).click();
        openInspectorTab("overview");
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Show input context").setExact(true)).click();
        final Locator input=page.locator("[aria-label='Recorded input context']");
        assertThat(input.locator(".context-leaf").count()).isLessThan(10);
        input.locator("[data-context-change='payload'] > summary").click();
        final Locator many=input.locator("[data-context-change='payload.many']");
        many.locator("summary").click();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(many.locator(".context-leaf")).hasCount(80);
        many.locator("[data-context-more]").click();
        many.locator("[data-context-more]").click();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(many.locator(".context-leaf")).hasCount(201);
        final Locator longValue=input.locator("[data-context-change='payload.long']");
        assertThat(longValue.textContent().length()).isLessThan(250);
        longValue.locator("summary").click();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(longValue.locator("output"))
                .hasText("\""+"x".repeat(100_000)+"\"");
        assertThat(input.locator("ins,del").count()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void aStoppedGroupOffersItsRecordedEntranceWithoutAChangeSummary() {
        openProject(CreatorEditorBrowserIT.attentionProject());
        assertThat(page.evaluate("""
                async ()=>(await fetch('/api/creator',{method:'POST',headers:mutationHeaders(),body:JSON.stringify({
                  format:2,groups:[{id:'work',name:'Work'}],steps:{normalize:{group:'work'},result:{group:'work'}}})})).status
                """)).isEqualTo(200);
        page.reload();awaitScene();
        page.locator("#visible-example").selectOption("command:1");
        openInspectorTab("groups");
        page.locator("[data-manage-group='work']").click();
        page.locator("#focus-group").click();
        awaitScene();
        page.locator("#world-location [data-inspect-region]").click();
        openInspectorTab("overview");
        final Locator evidence=page.locator("#selection-evidence");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(evidence).containsText("Stopped at");
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Show input context").setExact(true)).click();
        evidence.locator("[data-context-change='payload'] > summary").click();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(evidence.locator("[data-context-change='payload.name']"))
                .containsText("null");
        assertThat(evidence.locator("ins,del").count()).isZero();
        assertThat(evidence.textContent()).doesNotContain("No fields changed");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(evidence.locator(".evidence-boundary"))
                .containsText("Stopped: Field Manipulation");
        final Locator issue=page.locator("#dock-observation .dock-issues button");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(issue).containsText("Field Manipulation");
        assertThat(issue.innerText()).doesNotContain("Nested Step","text.normalize-space");
        issue.click();
        page.waitForFunction("() => document.querySelector('#inspector').dataset.selection==='normalize'");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#inspector-content .issue-message"))
                .containsText("null (expected String)");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"canvas"})
    void selectedExampleExplainsChangesInsideALargeGroupAndSurvivesNavigation(final String variant) throws IOException {
        final StressScene fixture = evidenceStressScene();
        openProject(fixture.project());
        assertThat(page.evaluate("""
                async creator => (await fetch('/api/creator', {
                  method: 'POST', headers: mutationHeaders(), body: creator
                })).status
                """, fixture.creator())).isEqualTo(200);
        page.reload();
        page.waitForFunction("() => typeof state !== 'undefined' && state.application?.examples?.state === 'completed'");
        assertThat(page.evaluate("() => state.application.examples.succeeded")).isEqualTo(3);
        page.locator("#open-settings").click();
        page.locator("#theme-variant").selectOption(variant);
        page.waitForFunction("variant => document.querySelector('#graph').dataset.renderer === (variant === 'canvas' ? 'canvas' : 'css')", variant);
        page.keyboard().press("Escape");
        selectTrigger();
        page.locator("#visible-example").selectOption("command:0");
        page.waitForFunction("() => state.application.example?.name === 'standard-order'");
        assertThat(page.locator("#example-session").isVisible()).isTrue();
        retryUnavailableExample(java.util.regex.Pattern.compile(".*/api/examples/.*/view\\?.*"),
                () -> inspectGroup("group-lane-0-0"));
        page.waitForFunction("() => document.querySelector('#selection-evidence')?.textContent.includes('ada lovelace')");
        assertThat(page.locator("#example-session").textContent()).contains("standard-order");
        final Locator allFields = page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Show all fields").setExact(true));
        final Path screenshots = Files.createDirectories(Path.of("target/creator-evidence", variant));
        allFields.waitFor();
        assertThat(page.locator("#selection-evidence [data-context-branch='[\"work\"]']").getAttribute("open")).isNotNull();
        assertThat(page.locator("#selection-evidence [data-context-branch='[\"job\"]']").count()).isZero();
        allFields.click();
        final Locator unchanged = page.locator("#selection-evidence [data-context-branch='[\"job\"]']");
        assertThat(unchanged.getAttribute("open")).isNull();
        page.locator("#inspector").screenshot(new Locator.ScreenshotOptions().setPath(screenshots.resolve("all-fields-inspector.png")));
        unchanged.locator("summary").first().click();
        page.waitForFunction("""
                () => [...document.querySelectorAll('#selection-evidence details')].some(branch =>
                  branch.dataset.contextBranch === JSON.stringify(['job']) && branch.textContent.includes('ADA LOVELACE'))
                """);
        assertThat(unchanged.textContent()).contains("ADA LOVELACE");
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Show changed fields only").setExact(true)).click();
        assertThat(page.locator("#selection-evidence [data-context-branch='[\"job\"]']").count()).isZero();
        assertThat(page.locator("#selection-evidence [data-context-change='work.name']").textContent())
                .contains("ada lovelace");
        assertThat(page.locator("#selection-evidence [data-context-change='work.name']").getAttribute("aria-label"))
                .isEqualTo("Added work.name");
        assertThat(page.locator("#selection-evidence [data-context-change='work.city']").getAttribute("aria-label"))
                .isEqualTo("Changed work.city");
        assertThat(page.locator("#selection-evidence [data-context-change='work.city']").textContent()).contains("Paris", "london");
        assertThat(page.evaluate("""
                () => { const row=document.querySelector('[data-context-change="work.city"]').getBoundingClientRect();
                  const panel=document.querySelector('#inspector-content').getBoundingClientRect();
                  return row.top>=panel.top && row.bottom<=panel.bottom; }
                """)).isEqualTo(true);
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("group-changes-desktop.png")));
        page.locator("#inspector").screenshot(new Locator.ScreenshotOptions().setPath(screenshots.resolve("changes-inspector.png")));
        assertThat(page.evaluate("""
                () => [...document.querySelectorAll('.world-label[data-coverage="selected"] strong')].every(label =>
                  getComputedStyle(label,'::before').content==='none')
                """)).isEqualTo(true);
        selectTrigger();
        final var heldMetadata = new ArrayList<com.microsoft.playwright.Route>();
        final String previousMetadata = "**/api/examples/command%3A0";
        page.waitForRequest(previousMetadata, () -> page.route(previousMetadata, heldMetadata::add));
        try {
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#visible-example")).isEnabled();
        } finally {
            page.unroute(previousMetadata);
            heldMetadata.forEach(com.microsoft.playwright.Route::resume);
        }
        page.locator("#visible-example").selectOption("command:1");
        inspectGroup("group-lane-0-0");
        page.waitForFunction("() => document.querySelector('#selection-evidence')?.textContent.includes('Not reached by this Example')");
        assertThat(page.locator("#selection-evidence").textContent()).doesNotContain("ada lovelace");
        inspectGroup("audit-group-1");
        page.waitForFunction("() => document.querySelector('#selection-evidence')?.textContent.includes('recorded')");
        assertThat(page.locator("[data-linked-use] option").count()).isEqualTo(4);
        assertThat(page.evaluate("""
                () => {const use=document.querySelector('[data-linked-use]').getBoundingClientRect();
                  const panel=document.querySelector('#inspector-content').getBoundingClientRect();
                  return use.top>=panel.top && use.bottom<=panel.bottom;}
                """)).isEqualTo(true);
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("linked-audit-example.png")));
        selectTrigger();
        page.locator("#visible-example").selectOption("command:0");
        inspectGroup("group-lane-0-0");
        page.waitForFunction("() => document.querySelector('#selection-evidence')?.textContent.includes('ada lovelace')");
        if(page.locator("#enter-region").isVisible())page.locator("#enter-region").click();
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving==='false'");
        retryUnavailableExample(java.util.regex.Pattern.compile(".*/api/examples/steps/[0-9]+$"),
                () -> page.locator("[data-select-node='lane-0-0']").click());
        openInspectorTab("overview");
        page.waitForFunction("() => document.querySelector('#selection-evidence')?.textContent.includes('ada lovelace')");
        assertThat(page.locator("#example-session").textContent()).contains("standard-order");
        assertThat(page.locator("#selection-evidence [data-context-branch='[\"job\"]']").count()).isZero();
        allFields.click();
        assertThat(page.locator("#selection-evidence [data-context-branch='[\"job\"]']").getAttribute("open")).isNull();
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Show changed fields only").setExact(true)).click();
        final Object previousPid = page.evaluate("() => state.application.pid");
        final var deletion = page.waitForResponse(response -> response.url().contains("/api/editor") && response.request().method().equals("POST"),
                () -> page.locator("#delete-step").click());
        assertThat(deletion.status()).as(deletion.text()).isEqualTo(200);
        try {
            page.waitForFunction("pid => state.build === 'Built' && state.application.examples?.state === 'completed' && state.application.pid !== pid", previousPid);
        } catch (final com.microsoft.playwright.TimeoutError failure) {
            throw new AssertionError((String) page.evaluate("() => JSON.stringify({build:state.build, application:state.application, diagnostics:state.localDiagnostics, pending:state.actionPending, selection:state.selection})"), failure);
        }
        page.keyboard().press("Escape");
        page.keyboard().press("Escape");
        page.waitForFunction("() => state.selection.type === 'none'");
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Show failing Step").setExact(true)).click();
        page.waitForFunction("() => state.selection.id === 'lane-0-1'");
        openInspectorTab("overview");
        page.waitForFunction("() => state.traceCases.some(example => example.id === state.application.example?.id)");
        page.waitForFunction("() => document.querySelector('#dock-observation')?.textContent.includes('work.name')");
        try {
            page.waitForFunction("() => document.querySelector('#world-error').hidden",null,new Page.WaitForFunctionOptions().setTimeout(10_000));
        } catch (final com.microsoft.playwright.TimeoutError failure) {
            page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("missing-producer-error.png")));
            throw new AssertionError((String)page.evaluate("() => JSON.stringify({error:document.querySelector('#world-error').textContent,query:state.world.query,nodes:state.world.scene?.nodes.map(node=>node.id)})"),failure);
        }
        assertThat(page.locator("#dock-observation").textContent()).contains("standard-order", "work.name");
        assertThat(page.locator("#selection-evidence").textContent()).contains("Stopped here").doesNotContain("No fields changed");
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("missing-producer-desktop.png")));
        page.setViewportSize(390, 760);
        awaitScene();
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("missing-producer-narrow.png")));
        assertThat(page.locator("#visible-example").isVisible()).isTrue();
        assertThat(page.evaluate("""
                () => {const group=document.querySelector('#group-navigation').getBoundingClientRect();
                  const radar=document.querySelector('#world-map').getBoundingClientRect();
                  return group.right<=radar.left || group.bottom<=radar.top || group.top>=radar.bottom;}
                """)).isEqualTo(true);
        page.locator("#visible-example").click();
        page.keyboard().press("Escape");
        if (page.locator("#inspector").isVisible()) page.locator("#close-inspector").click();
        page.keyboard().press("Escape");
        page.waitForFunction("() => state.selection.type === 'none'");
        selectTrigger();
        page.locator("#close-inspector").click();
        retryUnavailableExample(java.util.regex.Pattern.compile(".*/api/examples/.*/view$"),
                () -> page.locator("#visible-example").selectOption("command:2"));
        page.waitForFunction("() => state.application.example?.name === 'special-order' && document.querySelector('#visible-example').value==='command:2'");
        assertThat(page.locator("#graph .issue-error").count()).isZero();
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("example-without-inspector.png")));
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Clear Example").setExact(true)).click();
        page.waitForFunction("() => document.querySelector('#visible-example').value==='' && document.querySelector('#graph').dataset.replaying === 'false'");
        assertThat(page.locator("#selection-evidence").count()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    private void inspectGroup(final String id) {
        openInspectorTab("groups");
        if(page.locator("#group-library").getAttribute("open")==null)page.locator("#group-library > summary").click();
        page.locator("[data-manage-group='" + id + "']").click();
        page.locator("#focus-group").click();
        awaitScene();
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving==='false'");
        final Locator group=page.locator("[data-region-group='" + id + "']");
        if(group.count()>0)group.click();
        if(!page.locator("#selection-evidence").isVisible())
            page.locator("#world-location [data-inspect-region]").click();
        openInspectorTab("overview");
    }

    private void retryUnavailableExample(final java.util.regex.Pattern path, final Runnable selection) {
        final var reads = new java.util.concurrent.atomic.AtomicInteger();
        page.route(path, route -> {
            reads.incrementAndGet();
            route.fulfill(new com.microsoft.playwright.Route.FulfillOptions().setStatus(503)
                    .setContentType("application/json").setBody("{\"status\":\"unavailable\",\"reason\":\"saturated\"}"));
        });
        selection.run();
        page.waitForFunction("() => document.querySelector('#example-session output')?.textContent === 'Unavailable'");
        for (int poll = 0; poll < 3; poll++) page.waitForResponse(response -> response.url().endsWith("/api/application"), () -> {});
        assertThat(reads.get()).as("A failed read must stay paused across application polls").isEqualTo(1);
        page.unroute(path);
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Retry Example values").setExact(true)).click();
    }

    @Test
    void complexBranchingGraphUsesOneAccessibleWorkbench() throws IOException {
        final StressScene fixture = branchingStressScene();
        final JsonObject project = JsonParser.parseString(fixture.project()).getAsJsonObject();
        assertThat(project.getAsJsonArray("nodes").size()).isEqualTo(155);
        assertThat(project.getAsJsonArray("links").size()).isEqualTo(162);
        openProject(fixture.project());
        assertThat(page.evaluate("""
                async creator => (await fetch('/api/creator', {
                  method: 'POST', headers: mutationHeaders(), body: creator
                })).status
                """, fixture.creator())).isEqualTo(200);
        page.reload();
        page.waitForFunction("() => typeof state !== 'undefined' && state.creator?.groups?.length === 13 && state.world?.scene?.nodes.length > 0");
        final Path screenshots = Files.createDirectories(Path.of("target/usability-baseline"));
        Files.writeString(screenshots.resolve("project.json"), fixture.project());
        Files.writeString(screenshots.resolve("creator.json"), fixture.creator());
        Files.writeString(screenshots.resolve("scene.json"), (String) page.evaluate(
                "() => JSON.stringify(state.world.scene)"));
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("factory-overview.png")));
        assertThat(page.locator("#toggle-world-view").count()).isZero();
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Overview").setExact(true)).click();
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving === 'false'");
        assertThat(page.locator("#world-labels button").count()).isPositive();
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("workbench-overview.png")));
        assertThat(page.evaluate("() => state.world.scene.links.filter(link => link.from === 'gate').length"))
                .isEqualTo(2);
        assertThat(page.evaluate("() => state.creator.groups.length")).isEqualTo(13);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void canvasFactoryKeepsFoundryAppearanceAndNavigationOnBranchHeavyProject() throws IOException {
        final StressScene fixture = branchingStressScene();
        openProject(fixture.project());
        assertThat(page.evaluate("""
                async creator => (await fetch('/api/creator', {
                  method: 'POST', headers: mutationHeaders(), body: creator
                })).status
                """, fixture.creator())).isEqualTo(200);
        page.reload();
        page.waitForFunction("() => typeof state !== 'undefined' && state.creator?.groups?.length === 13 && state.world?.scene?.nodes.length > 0");
        page.locator("#open-settings").click();
        page.locator("#theme-variant").selectOption("canvas");
        page.waitForFunction("() => document.querySelector('#graph').dataset.renderer === 'canvas'");
        page.keyboard().press("Escape");
        page.waitForFunction("() => state.world.scene?.nodes.some(node => node.kind === 'region' && node.roof?.length)");
        page.waitForFunction("() => document.querySelector('.world-raster')?.dataset.stations");
        assertThat((List<?>) page.evaluate("""
                async () => {
                  const scene=await (await fetch('/api/scene?scale=0.1')).json(), collisions=[];
                  const stations=scene.nodes.filter(node=>!node.expanded && node.kind!=='end');
                  for(const link of scene.links) for(let index=1;index<link.points.length;index++) {
                    const a=link.points[index-1],b=link.points[index];
                    for(const node of stations) {
                      if(node.id===link.from || node.id===link.to)continue;
                      const half=68*Math.min(1,node.station_scale),x=node.x+node.width/2,y=node.y+node.height/2;
                      if(a[0]===b[0] && a[0]>x-half && a[0]<x+half
                        && Math.max(a[1],b[1])>y-half && Math.min(a[1],b[1])<y+half
                        || a[1]===b[1] && a[1]>y-half && a[1]<y+half
                        && Math.max(a[0],b[0])>x-half && Math.min(a[0],b[0])<x+half)
                        collisions.push(link.from+' -> '+link.to+' crosses '+node.id);
                    }
                  }
                  return collisions;
                }
                """)).as("Bands must not pass through unrelated buildings in the branch-heavy project").isEmpty();
        assertThat(page.evaluate("""
                async () => {
                  const atlas=await (await fetch('/api/themes/files/foundry/variants/canvas/atlas.json')).json();
                  const style=getComputedStyle(document.documentElement);
                  return atlas.sprites.region.file==='region.svg' && atlas.sprites.branch.frames===1
                    && style.getPropertyValue('--world-near').trim()==='#28333b'
                    && style.getPropertyValue('--world-far').trim()==='#1d272f';
                }
                """)).isEqualTo(true);
        assertThat(page.evaluate("""
                () => state.world.scene.nodes.filter(node => node.roof).every(node =>
                  node.roof.length <= 6 && node.roof.every(member =>
                    member.x >= 0 && member.x <= 1 && member.y >= 0 && member.y <= 1))
                """)).isEqualTo(true);
        final Path screenshots = Files.createDirectories(Path.of("target/visualization-lab"));
        Files.writeString(screenshots.resolve("canvas-scene.json"),
                (String) page.evaluate("() => JSON.stringify(state.world.scene)"));
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("canvas-factory-desktop.png")));
        final String region = (String) page.evaluate("() => state.world.scene.nodes.find(node => node.roof).id");
        page.evaluate("id => void state.world.focus(id,.6)", region);
        page.waitForFunction("""
                () => Number(document.querySelector('#graph').dataset.sceneScale) >= .59
                  && document.querySelector('#graph').dataset.cameraMoving === 'false'
                """);
        assertThat(page.evaluate("""
                () => state.world.scene.nodes.some(item => item.kind === 'region' && item.expanded)
                """)).isEqualTo(true);
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("canvas-factory-medium.png")));
        page.evaluate("id => void state.world.focus(id,.85)", region);
        page.waitForFunction("""
                () => Number(document.querySelector('#graph').dataset.sceneScale) >= .84
                  && document.querySelector('#graph').dataset.cameraMoving === 'false'
                  && state.world.scene?.nodes.some(node => node.expanded)
                """);
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("canvas-factory-group.png")));
        assertThat(page.evaluate("() => state.world.scene.nodes.some(item => item.kind === 'region' && item.expanded)")).isEqualTo(true);
        page.evaluate("id => void state.world.focus(id,2)", region);
        page.waitForFunction("""
                () => Number(document.querySelector('#graph').dataset.sceneScale) >= 1.95
                  && document.querySelector('#graph').dataset.cameraMoving === 'false'
                """);
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("canvas-factory-detail.png")));
        assertThat(page.evaluate("() => state.world.scene.nodes.some(item => item.kind === 'region' && item.expanded)")).isEqualTo(true);
        final CDPSession cdp = context.newCDPSession(page);
        cdp.send("Performance.enable");
        final JsonObject metricsBefore = cdp.send("Performance.getMetrics");
        final Object cadence = page.evaluate("""
                async () => {
                  const times=[];let previous=await new Promise(requestAnimationFrame);
                  for(let i=0;i<120;i++) {
                    const now=await new Promise(requestAnimationFrame);
                    if(i>=20)times.push(now-previous);
                    previous=now;
                  }
                  times.sort((a,b)=>a-b);
                  const canvas=document.querySelector('.world-raster');
                  return {p50:times[50],p95:times[95],max:times.at(-1),
                    animated:canvas.dataset.animated,routes:Number(canvas.dataset.routes),
                    stations:Number(canvas.dataset.stations),canvasPixels:canvas.width*canvas.height,
                    heap:performance.memory?.usedJSHeapSize??null};
                }
                """);
        final JsonObject profile = new JsonObject();
        profile.add("cadence", new com.google.gson.Gson().toJsonTree(cadence));
        profile.add("before", metricsBefore);
        profile.add("after", cdp.send("Performance.getMetrics"));
        Files.writeString(screenshots.resolve("canvas-profile.json"), profile.toString());
        page.locator("#world-location [data-inspect-region]").click();
        page.waitForFunction("id => state.regionSelection?.id === id && !document.querySelector('#inspector').hidden", region);
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("canvas-factory-selected.png")));
        page.locator("#close-inspector").click();
        page.setViewportSize(390, 760);
        page.evaluate("() => {state.world.focus(state.regionSelection.id, .85); return true;}");
        page.waitForFunction("""
                () => state.world._pendingFocus === null && state.world._size.width === 390
                  && Number(document.querySelector('#graph').dataset.sceneScale) >= .84
                  && document.querySelector('#graph').dataset.cameraMoving === 'false'
                  && state.world.scene?.nodes.some(node => node.id === state.regionSelection.id && node.expanded)
                """);
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("canvas-factory-narrow.png")));
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void authRouteCanBeInspectedAndGroupedWithoutChangingItsExecution() throws IOException {
        openProject(Files.readString(Path.of("../../examples/auth-http-app/railix.project.json")));
        final String source = Files.readString(directory.resolve("project.json"));
        selectWorldNode("credentials");
        assertThat(page.locator("#inspector-identity h2").innerText()).isNotBlank();
        selectWorldNode("not-found-body");
        page.evaluate("() => void state.world.focus('not-found-status')");
        awaitScene();
        page.locator("[data-select-node='not-found-status']").click(
                new Locator.ClickOptions().setModifiers(List.of(com.microsoft.playwright.options.KeyboardModifier.SHIFT)));
        page.waitForFunction("() => state.selectedSteps.has('not-found-body') && state.selectedSteps.has('not-found-status')");
        openInspectorTab("overview");
        page.locator("[data-group-selection]").click();
        page.locator("#group-draft input").fill("Not-found response");
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Create group").setExact(true)).click();
        page.waitForFunction("() => !state.actionPending && state.creator.groups.some(group => group.name === 'Not-found response')");
        assertThat(page.locator("#inspector-identity h2").textContent()).isEqualTo("Not-found response");
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(source);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void canvasBeltOpensInsertionOnTheVisibleRoute() {
        openProject(fourStepProject());
        final var point = (List<?>) page.evaluate("""
                () => {
                  const world=state.world,link=world.scene.links.find(item=>item.from==='one' && item.to==='two');
                  const stations=new Map(world.scene.nodes.map(node=>[node.id,{node,look:world._appearance(node),box:world._planeBounds(node)}]));
                  const points=world._linkPoints(link,stations);
                  const runs=points.slice(1).map((b,i)=>({a:points[i],b,length:Math.hypot(b[0]-points[i][0],b[1]-points[i][1])}));
                  const {a,b}=runs.sort((a,b)=>b.length-a.length)[0];
                  return world._view((a[0]+b[0])/2,(a[1]+b[1])/2,6*world._camera.scale*link.station_scale);
                }
                """);
        final var graph=page.locator("#graph").boundingBox();
        page.mouse().click(graph.x+((Number)point.get(0)).doubleValue(),graph.y+((Number)point.get(1)).doubleValue());
        page.locator(".step-picker").waitFor();
        assertThat(page.evaluate("() => state.picker?.anchor === 'one' && state.picker?.outcome === 'next'")).isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void canvasZoomKeepsThePointUnderTheCursor() {
        openProject(fourStepProject());
        page.evaluate("() => void state.world.focus('one',1)");
        awaitScene();
        final BoundingBox before=stationBounds("one");
        page.mouse().move(before.x+before.width/2,before.y+before.height/2);
        page.mouse().wheel(0,-180);
        page.waitForFunction("width => state.world._screenBounds(state.world.scene.nodes.find(node=>node.id==='one')).width > width",before.width);
        final BoundingBox after=stationBounds("one");
        assertThat(after.x+after.width/2).isCloseTo(before.x+before.width/2,within(2.0));
        assertThat(after.y+after.height/2).isCloseTo(before.y+before.height/2,within(2.0));
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {320, 390})
    void compactFooterDoesNotClipCoverage(final int width) {
        page.setViewportSize(width, 800);
        openProject(choiceProject());
        page.waitForFunction("() => !document.querySelector('#status-coverage').hidden");
        assertThat(page.locator("#status-coverage").evaluate("""
                element => {
                  const metric = element.getBoundingClientRect();
                  const rail = element.parentElement.getBoundingClientRect();
                  return metric.left >= rail.left && metric.right <= rail.right;
                }
                """)).isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"canvas,1280", "canvas,320", "classic,390"})
    void footerZoomAndRoundHomeStayReachable(final String variant, final int width) throws IOException {
        page.setViewportSize(width, 800);
        page.locator("#open-settings").click();
        if (variant.equals("classic")) page.locator("#theme-select").selectOption("classic/theme.css");
        else page.locator("#theme-variant").selectOption(variant);
        page.waitForFunction("variant => !state.themeError && document.querySelector('#graph').dataset.renderer === 'canvas' && (variant!=='classic' || document.documentElement.dataset.architecture==='classic')", variant);
        page.keyboard().press("Escape");

        assertThat(page.locator(".status-rail .canvas-tools #zoom-in, .status-rail .canvas-tools #zoom-out, .status-rail .canvas-tools #zoom-level").count()).isEqualTo(3);
        assertThat(page.locator(".topbar #toggle-music + #zoom-fit").count()).isEqualTo(1);
        final var home = page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Home").setExact(true));
        final BoundingBox homeBounds = home.boundingBox();
        assertThat(home.textContent()).isEmpty();
        assertThat(homeBounds.width).isCloseTo(homeBounds.height, within(.1));
        assertThat(home.evaluate("element => getComputedStyle(element).borderRadius")).isEqualTo("6px");
        final BoundingBox footer = page.locator(".status-rail").boundingBox();
        final BoundingBox zoom = page.locator(".canvas-tools").boundingBox();
        assertThat(footer.x + footer.width - zoom.x - zoom.width).isBetween(0.0, 16.0);
        assertThat(zoom.y).isGreaterThanOrEqualTo(footer.y);
        assertThat(zoom.y + zoom.height).isLessThanOrEqualTo(footer.y + footer.height);
        final BoundingBox metrics = page.locator(".status-metrics").boundingBox();
        assertThat(metrics.x + metrics.width).isLessThanOrEqualTo(zoom.x);
        final BoundingBox music = page.locator("#toggle-music").boundingBox();
        final BoundingBox running = page.locator(".build-indicator").boundingBox();
        assertThat(homeBounds.y + homeBounds.height / 2).isCloseTo(music.y + music.height / 2, within(.1));
        assertThat(running.y + running.height / 2).isCloseTo(music.y + music.height / 2, within(.1));
        assertThat(running.x).isGreaterThanOrEqualTo(homeBounds.x + homeBounds.width);
        assertThat(running.x + running.width).isLessThanOrEqualTo(width);
        assertThat(page.locator(".build-indicator strong").evaluate("element => element.scrollWidth <= element.clientWidth")).isEqualTo(true);

        home.click();
        page.waitForFunction("() => document.querySelector('#zoom-level').textContent === '50%'");
        page.locator("#zoom-in").press("Enter");
        page.waitForFunction("() => document.querySelector('#zoom-level').textContent === '70%'");
        page.locator("#zoom-out").click();
        page.waitForFunction("() => document.querySelector('#zoom-level').textContent === '50%'");
        final Path screenshot = Files.createDirectories(Path.of("target/footer-hud")).resolve(variant + "-" + width + ".png");
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshot));
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void embeddedThemeSpritesAreReleasedOnDisposalWithoutDecorativeSceneNodes() throws IOException {
        openProject(fourStepProject());
        final List<String> decorativeReads=new ArrayList<>();
        page.onRequest(request->{if(request.url().contains("/scenery-"))decorativeReads.add(request.url());});
        page.reload();
        awaitScene();
        page.evaluate("() => {state.creator.created_at=1;state.settings.reduced_motion=true;state.world.fit();}");
        awaitScene();
        page.waitForFunction("() => state.world._surface?.assets.sprites.has('step')");
        assertThat(decorativeReads).as("Unused decorative images must not be fetched or decoded").isEmpty();
        final String project = Files.readString(directory.resolve("project.json"));
        assertThat(page.locator(".world-scenery").count()).isZero();
        assertThat(page.evaluate("() => ['app','trigger','step','region','branch'].every(id=>state.world._surface.assets.sprites.has(id))"))
                .isEqualTo(true);
        final var assets=page.evaluateHandle("() => state.world._surface.assets");
        System.out.println("RAILIX_THEME_ASSETS " + page.evaluate("() => ({sprites:state.world._surface.assets.sprites.size,pixels:state.world._surface.assets.pixels,decorativeReads:0})"));
        page.evaluate("async()=>{state.world.repaint();await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));}");
        assertThat(page.evaluate("assets=>state.world._surface.assets===assets",assets)).isEqualTo(true);
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target/scenery-atlas")).resolve("canvas.png")));
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(project);
        page.evaluate("()=>{window.retiredSprites=state.world._surface.assets.sprites;state.world.dispose();}");
        assertThat(page.evaluate("()=>window.retiredSprites.size")).isEqualTo(0);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"canvas,1280", "canvas,390"})
    void oneClickOpensNamedGroupsAndTheirLocationOpensTheInspector(final String variant, final int width) {
        page.setViewportSize(width, width < 560 ? 720 : 800);
        openNamedGroupProject();
        if (page.locator("#leave-group").isVisible()) page.locator("#leave-group").click();
        page.waitForFunction("() => !new URLSearchParams(state.world.query).has('inside')");
        page.locator("#scope-overview").click();
        awaitScene();
        page.locator("#open-settings").click();
        page.locator("#theme-variant").selectOption(variant);
        page.waitForFunction("variant => state.settings.theme_variant === variant && !state.themeError && document.querySelector('#graph').dataset.renderer === 'canvas'",variant);
        page.keyboard().press("Escape");
        page.locator("[data-region-group='pair']").click();
        page.waitForFunction("() => new URLSearchParams(state.world.query).has('inside')", null,
                new Page.WaitForFunctionOptions().setTimeout(5000));
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving === 'false'");
        assertThat(page.locator("#inspector").isVisible())
                .as("Selection: %s; location: %s", page.locator("#inspector").getAttribute("data-selection"),
                        page.locator("#world-location").innerText()).isFalse();
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Inspect Pair").setExact(true)).click();
        assertThat(page.locator("#enter-region").count()).isZero();
        page.locator("[data-inspector-mode=groups]").dblclick(new Locator.DblclickOptions().setTimeout(5000));
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        assertThat(page.evaluate("() => state.inspectorMode")).isEqualTo("groups");
        assertThat(page.evaluate("() => new URLSearchParams(state.world.query).has('inside')")).isEqualTo(true);
        page.keyboard().press("Escape");
        page.locator("#leave-group").click();
        page.waitForFunction("() => !new URLSearchParams(state.world.query).has('inside')");
        page.locator("#scope-overview").click();
        final Locator pair = page.locator("[data-region-group='pair']");
        pair.dblclick();
        page.waitForFunction("() => new URLSearchParams(state.world.query).has('inside') && document.querySelector('#graph').dataset.cameraMoving === 'false'");
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        assertThat(page.evaluate("() => state.world.navigation.length")).isEqualTo(1);
        page.locator("#graph").press("e");
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void automaticRegionIsAOneClickNavigationPointNotAnEditableGroup() throws IOException {
        openProject(deepBranchProject(128));
        final Locator region = page.locator("[data-group-region-label]:not([data-region-group])").first();
        region.waitFor();
        final String name = region.locator("strong").textContent();
        assertThat(name).doesNotContain("Automatic section");
        awaitScene();
        page.waitForFunction("() => document.getAnimations().every(animation => animation.playState !== 'running' || animation.effect.getComputedTiming().iterations === Infinity)");
        final Path screenshots = Files.createDirectories(Path.of("target", "navigation-review"));
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("automatic-overview.png")));

        region.click();
        page.waitForFunction("() => new URLSearchParams(state.world.query).has('inside')");
        awaitScene();
        page.waitForFunction("() => document.getAnimations().every(animation => animation.playState !== 'running' || animation.effect.getComputedTiming().iterations === Infinity)");
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        assertThat(page.locator("#group-navigation").textContent()).contains(name);
        assertThat((List<?>) page.evaluate("""
                () => state.world.scene.nodes.filter(node => node.kind === 'region' && !node.group && !node.expanded)
                  .map(node => node.name)
                """)).doesNotHaveDuplicates().noneMatch(value -> value.toString().contains("Automatic section"));
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("automatic-entered.png")));
        page.locator("#leave-group").click();
        page.waitForFunction("() => !new URLSearchParams(state.world.query).has('inside')");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"canvas"})
    void linearRunSummaryHasAVisibleRouteAndOpensItsSteps(final String variant) throws IOException {
        openProject(deepBranchProject(128));
        page.locator("#open-settings").click();
        page.locator("#theme-variant").selectOption(variant);
        page.waitForFunction("variant => state.settings.theme_variant === variant && !state.themeError && document.querySelector('#graph').dataset.renderer === 'canvas'", variant);
        page.keyboard().press("Escape");
        awaitScene();
        final String id = (String) page.evaluate("() => state.world.scene.nodes.find(node => node.summary === 'run' && node.count === 128)?.id");
        assertThat(id).isNotBlank();
        final Locator run = page.locator("[data-group-region-label='" + id + "']");
        run.waitFor();
        assertThat(run.getAttribute("aria-label")).contains("128 Steps", "Click to follow this run");
        final Path images = Files.createDirectories(Path.of("target/route-summary"));
        page.screenshot(new Page.ScreenshotOptions().setPath(images.resolve(variant + "-overview.png")));
        run.click();
        page.waitForFunction("() => new URLSearchParams(state.world.query).has('inside')");
        awaitScene();
        assertThat(page.locator("[data-select-node='step-0']").isVisible()).isTrue();
        page.screenshot(new Page.ScreenshotOptions().setPath(images.resolve(variant + "-entered.png")));
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"foundry,canvas", "signal,mixed", "circuit,ascii"})
    void themeVariantsKeepGroupEntryZoomAndHomeBehavior(final String theme, final String variant) throws IOException {
        openProject(deepBranchProject(128));
        page.locator("#open-settings").click();
        if (!theme.equals("foundry")) page.locator("#theme-select").selectOption(theme + "/theme.css");
        page.locator("#theme-variant").selectOption(variant);
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Close Settings").setExact(true)).click();
        page.waitForFunction("variant => state.settings.theme_variant === variant && !state.themeError && document.querySelector('#graph').dataset.renderer === (variant==='canvas'?'canvas':'character')",variant);
        if (variant.equals("canvas")) page.evaluate("""
                () => {
                  window.visibleShadowDraws=0;window.visibleBuildingDraws=0;
                  const draw=CanvasRenderingContext2D.prototype.drawImage;
                  CanvasRenderingContext2D.prototype.drawImage=function(source,...args){
                    if(this.canvas.matches('.world-raster')){
                      if(this.shadowBlur>0)window.visibleShadowDraws++;
                      if(source instanceof ImageBitmap)window.visibleBuildingDraws++;
                    }
                    return draw.call(this,source,...args);
                  };
                }
                """);
        page.locator("[data-group-region-label]").first().click();
        page.waitForFunction("() => new URLSearchParams(state.world.query).has('inside') && document.querySelector('#graph').dataset.cameraMoving !== 'true'");
        if (variant.equals("canvas")) {
            assertThat(page.evaluate("""
                    async () => {
                      const samples=[];
                      for(let i=0;i<40;i++){
                        await new Promise(requestAnimationFrame);
                        if(i>=20)samples.push(window.visibleBuildingDraws);
                      }
                      return window.visibleShadowDraws===0 && new Set(samples).size===1;
                    }
                    """)).as("Settled group buildings and shadows must stay cached while transport animates").isEqualTo(true);
        }
        awaitScene();
        page.waitForFunction("() => document.querySelector('#zoom-level').textContent === '50%'");
        final String inside=(String)page.evaluate("() => new URLSearchParams(state.world.query).get('inside')");
        assertThat(page.locator("[data-select-node=step-0]").isVisible()).isTrue();
        final Path images=Files.createDirectories(Path.of("target/renderer-navigation"));
        page.screenshot(new Page.ScreenshotOptions().setPath(images.resolve(theme+"-"+variant+"-50.png")));
        page.locator("#zoom-in").click();
        page.waitForFunction("() => document.querySelector('#zoom-level').textContent === '70%'");
        page.screenshot(new Page.ScreenshotOptions().setPath(images.resolve(theme+"-"+variant+"-70.png")));
        if (variant.startsWith("canvas")) assertThat(((Number) page.locator(".world-raster").evaluate("""
                canvas => {
                  const rgba=canvas.getContext('2d').getImageData(0,0,canvas.width,canvas.height).data;
                  let opaque=0;for(let i=3;i<rgba.length;i+=4)if(rgba[i]>200)opaque++;
                  return opaque/(canvas.width*canvas.height);
                }
                """)).doubleValue()).as("A group outline must not paint a giant solid stripe across the viewport").isLessThan(.2);
        page.locator("#zoom-out").click();
        awaitScene();
        page.locator("[data-select-node=step-0]").click();
        page.locator("#inspector").waitFor(new com.microsoft.playwright.Locator.WaitForOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE));
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        page.locator("#close-inspector").click();
        page.locator("#zoom-out").click();page.locator("#zoom-out").click();
        awaitScene();
        assertThat(page.evaluate("() => new URLSearchParams(state.world.query).get('inside')")).isEqualTo(inside);
        page.locator("#leave-group").click();
        page.waitForFunction("() => !new URLSearchParams(state.world.query).has('inside')");
        page.locator("#zoom-fit").click();
        page.waitForFunction("() => document.querySelector('#zoom-level').textContent === '50%'");
        assertThat(page.locator("[data-select-node=app]").isVisible()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"canvas,true", "canvas,false"})
    void sharedBridgeKeepsTheActiveArrivalsHighlight(final String variant, final boolean forward) throws IOException {
        openProject(fourStepProject());
        page.evaluate("""
                async settings => {
                  state.world.dispose();
                  document.documentElement.style.setProperty('--canvas-selection','#ff00ff');
                  const original=window.fetch;
                  const upper={id:'upper',from:'left',to:'right',outcome:'next',station_scale:1,
                    points:settings.forward ? [[0,200],[640,200]] : [[640,200],[0,200]],bridges:[{at:[316,200],under:'lower'}]};
                  const scene={revision:'shared-bridge',bounds:{x:0,y:0,width:640,height:400},nodes:[],icons:{},links:[
                    upper,{...upper,id:'upper-selected',from:'other'},
                    {id:'lower',from:'top',to:'bottom',outcome:'next',station_scale:1,points:[[316,40],[316,360]]}]};
                  window.fetch=(url,...args)=>String(url).startsWith('/api/scene?')
                    ?Promise.resolve(new Response(JSON.stringify(scene))):original(url,...args);
                  window.__renderer=new RailixWorld(document.querySelector('#graph'),{
                    linkAppearance:link=>({editing:link.id==='upper-selected',rate:link.id==='upper-selected'?10:0})});
                  const catalog=await (await fetch('/api/themes')).json();
                  await window.__renderer.renderer(catalog.themes.find(theme=>theme.id==='').variants.find(item=>item.id===settings.variant));
                  await window.__renderer.refresh();window.__renderer.fit();await window.__renderer.refresh();
                }
                """, Map.of("variant",variant,"forward",forward));
        page.waitForFunction("() => window.__renderer.scene?.revision==='shared-bridge' && document.querySelector('#graph').dataset.cameraMoving==='false'");
        page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/system-corridors",variant,
                forward ? "shared-bridge-selected.png" : "shared-bridge-selected-west.png")));
            assertThat(page.locator(".world-raster").evaluate("""
                    canvas=>{
                      const world=window.__renderer,point=world._view(...world._project(316,215),30*world._camera.scale);
                      const ratio=canvas.width/canvas.getBoundingClientRect().width;
                      const rgba=canvas.getContext('2d').getImageData(Math.round(point[0]*ratio)-4,Math.round(point[1]*ratio)-4,9,9).data;
                      for(let i=0;i<rgba.length;i+=4)if(rgba[i]>220 && rgba[i+1]<40 && rgba[i+2]>220)return true;
                      return false;
                    }
                    """)).as("The raised deck, not only the ground run, must retain the selected rim").isEqualTo(true);
            assertThat(page.locator(".world-raster").evaluate("""
                    canvas=>{
                      const world=window.__renderer,point=world._view(...world._project(284,215),6*world._camera.scale);
                      const ratio=canvas.width/canvas.getBoundingClientRect().width;
                      const rgba=canvas.getContext('2d').getImageData(Math.round(point[0]*ratio)-2,Math.round(point[1]*ratio)-2,5,5).data;
                      for(let i=0;i<rgba.length;i+=4)if(rgba[i]>220 && rgba[i+1]<40 && rgba[i+2]>220)return false;
                      return true;
                    }
                    """)).as("The selected upper belt must not leave a second magenta rim on the ground").isEqualTo(true);
            assertThat(((Number)page.locator(".world-raster").evaluate("""
                    canvas=>{
                      const world=window.__renderer,ratio=canvas.width/canvas.getBoundingClientRect().width,ctx=canvas.getContext('2d');
                      const pixel=(x,y,z)=>{
                        const p=world._view(...world._project(x,y),z*world._camera.scale);
                        return ctx.getImageData(Math.round(p[0]*ratio),Math.round(p[1]*ratio),1,1).data;
                      };
                      let mismatch=0,total=0;
                      for(let x=302;x<=330;x+=2)for(const y of [193,197,203,207]) {
                        const a=pixel(x,y,30),b=pixel(x-256,y,6);
                        if(Math.hypot(a[0]-b[0],a[1]-b[1],a[2]-b[2])>45)mismatch++;
                        total++;
                      }
                      return mismatch/total;
                    }
                    """)).doubleValue()).as("Deck plates retain the ground belt's orientation and world-anchored phase in either direction").isLessThan(.25);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"canvas"})
    void adjacentCrossingsUseOneContinuousBridge(final String variant) throws IOException {
        startRendererTraffic(10, 30_000, true);
        // The renderer's public scene input isolates a bridge over three parallel lanes.
        page.evaluate("""
                async variant => {
                  const world=window.__renderer,original=window.fetch;
                  const scene={revision:'parallel-crossings',bounds:{x:0,y:0,width:640,height:400},nodes:[],icons:{},links:[
                    {id:'upper',from:'left',to:'right',outcome:'next',station_scale:1,points:[[0,200],[640,200]],
                      bridges:[280,316,352].map(x=>({at:[x,200],under:'lower-'+x}))},
                    ...[280,316,352].map(x=>({id:'lower-'+x,from:'top-'+x,to:'bottom-'+x,outcome:'next',station_scale:1,points:[[x,40],[x,360]]}))]};
                  window.fetch=(url,...args)=>String(url).startsWith('/api/scene?')
                    ?Promise.resolve(new Response(JSON.stringify(scene))):original(url,...args);
                  const catalog=await (await fetch('/api/themes')).json();
                  await world.renderer(catalog.themes.find(theme=>theme.id==='').variants.find(item=>item.id===variant));
                  await world.refresh();world.fit();await world.refresh();
                }
                """, variant);
        page.waitForFunction("() => window.__renderer.scene?.revision==='parallel-crossings' && document.querySelector('#graph').dataset.cameraMoving==='false'");
        page.waitForFunction("() => document.querySelector('.world-raster')?.dataset.bridges==='1'");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeTransportMovesForTrafficAndExampleReplayAndStopsWhenHidden(final boolean replay) {
        startRendererTraffic(replay ? 0 : 10,30_000,replay);
        page.evaluate("""
                async () => {
                  const catalog=await (await fetch('/api/themes')).json();
                  const variant=catalog.themes.find(theme=>theme.id==='').variants.find(variant=>variant.id==='canvas');
                  await window.__renderer.renderer(variant);
                }
                """);
        page.waitForFunction("() => Number(document.querySelector('.world-raster')?.dataset.routes)>0");
        page.evaluate("""
                () => window.readParcelPixels=()=>{
                  const canvas=document.querySelector('.world-raster'),rgba=canvas.getContext('2d').getImageData(0,0,canvas.width,canvas.height).data;
                  const sample=document.createElement('canvas');sample.width=sample.height=1;
                  const context=sample.getContext('2d');context.fillStyle=window.__renderer._surface.palette.cargoTop;
                  context.fillRect(0,0,1,1);const top=context.getImageData(0,0,1,1).data;
                  const pixels=[];for(let i=0;i<rgba.length;i+=4)if(rgba[i]===top[0]&&rgba[i+1]===top[1]&&rgba[i+2]===top[2])pixels.push(i/4);
                  return pixels.join(',');
                }
                """);
        final String before=(String)page.evaluate("() => window.readParcelPixels()");
        assertThat(before).as("Raised parcels, not just moving lights, must be visible").isNotEmpty();
        page.waitForFunction("before=>window.readParcelPixels()!==before",before);
        page.evaluate("() => void window.__renderer.focus('step-0',1)");
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving !== 'true'");
        page.evaluate("() => {Object.defineProperty(document,'hidden',{configurable:true,value:true});document.dispatchEvent(new Event('visibilitychange'));}");
        page.waitForFunction("() => document.querySelector('.world-raster').dataset.animated==='false'");
        final String paused=(String)page.locator(".world-raster").evaluate("canvas=>canvas.toDataURL()");
        page.evaluate("() => new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))");
        assertThat(page.locator(".world-raster").evaluate("canvas=>canvas.toDataURL()")).isEqualTo(paused);
        page.evaluate("() => {delete document.hidden;document.dispatchEvent(new Event('visibilitychange'));}");
        page.waitForFunction("() => document.querySelector('.world-raster').dataset.animated==='true'");
        page.evaluate("""
                async () => {
                  const original=window.fetch,empty={...window.__renderer.scene,revision:'empty-view',nodes:[],links:[]};
                  window.fetch=(url,...args)=>String(url).startsWith('/api/scene?')
                    ?Promise.resolve(new Response(JSON.stringify(empty))):original(url,...args);
                  try {await window.__renderer.refresh();} finally {window.fetch=original;}
                  await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
                }
                """);
        assertThat(page.locator(".world-raster").getAttribute("data-animated")).as("Empty space must not keep the graphics loop running").isEqualTo("false");
        page.evaluate("() => { window.__renderer.dispose(); }");
        assertThat(page.locator("#world-plane canvas").count()).isZero();
        assertThat(pageErrors).isEmpty();
    }


    @Test
    @Timeout(480)
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "railix.renderer.comparison", matches = "true")
    void compareProductionVariantsWith128VisibleStations() throws IOException {
        page.setViewportSize(1920, 1080);
        openProject(fourStepProject());
        final CDPSession cdp = context.newCDPSession(page);
        final CDPSession processes = context.browser().newBrowserCDPSession();
        cdp.send("Performance.enable");
        System.out.println("RAILIX_GRAPHICS " + processes.send("SystemInfo.getInfo"));
        page.evaluate("""
                async () => {
                  state.world.dispose();
                  const nodes=Array.from({length:128},(_,i)=>({id:'probe-'+i,kind:'step',name:'Processor',
                    x:i%16*240,y:Math.floor(i/16)*210,width:168,height:64,station_scale:1}));
                  const links=nodes.filter((_,i)=>i%16!==15).map(node=>{
                    const next=nodes[Number(node.id.slice(6))+1];
                    return {id:node.id+'-out',from:node.id,to:next.id,station_scale:1,
                      points:[[node.x+168,node.y+32],[next.x,next.y+32]]};
                  });
                  const scene={revision:'renderer-comparison',bounds:{x:-160,y:-160,width:4240,height:1820},nodes,links};
                  const original=window.fetch;
                  window.fetch=(url,...args)=>String(url).startsWith('/api/scene?')
                    ?Promise.resolve(new Response(JSON.stringify(scene),{headers:{'Content-Type':'application/json'}})):original(url,...args);
                  window.restoreProbeFetch=()=>window.fetch=original;
                  window.probeRate=100;
                  state.world=new RailixWorld(document.querySelector('#graph'),{
                    appearance:()=>({rate:window.probeRate,color:'#53d8d1'}),
                    linkAppearance:()=>({rate:window.probeRate}),motionActive:()=>true
                  });
                  await state.world.refresh();
                }
                """);
        final Path output=Files.createDirectories(Path.of("target/renderer-comparison/current-canvas-dpr" + page.evaluate("() => devicePixelRatio")));
        final Path report=output.resolve("measurements.jsonl");
        Files.writeString(report,"");
        final List<String> variants=(List<String>)page.evaluate("() => state.themes.find(theme=>theme.id==='').variants.map(variant=>variant.id)");
        final List<String> rounds=new ArrayList<>(variants);
        rounds.addAll(variants.reversed());
        try {
            for (final String variant : rounds) {
                page.evaluate("""
                        async variant => {state.settings.theme_variant=variant;await applyTheme();if(state.themeError)throw Error(state.themeError);}
                        """, variant);
                for (final String phase : List.of("active","idle")) {
                page.evaluate("phase => {window.probeRate=phase==='active'?100:0;state.world.repaint();}",phase);
                page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving !== 'true'");
                page.evaluate("() => new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))");
                final JsonObject before=cdp.send("Performance.getMetrics"), processBefore=processes.send("SystemInfo.getProcessInfo");
                final long start=System.nanoTime();
                final Object frames=page.evaluate("""
                        async () => {
                          const times=[];let previous=await new Promise(requestAnimationFrame);
                          for(let i=0;i<100;i++){
                            const now=await new Promise(requestAnimationFrame);if(i>=20)times.push(now-previous);previous=now;
                          }
                          times.sort((a,b)=>a-b);
                          const canvas=document.querySelector('.world-raster');
                          return {p50:times[Math.floor(times.length*.5)],p95:times[Math.floor(times.length*.95)],max:times.at(-1),
                            stations:Number(canvas.dataset.stations),
                            dom:document.querySelectorAll('#world-plane *').length,
                            canvasPixels:canvas?canvas.width*canvas.height:0,dpr:devicePixelRatio,
                            heap:performance.memory?.usedJSHeapSize??null};
                        }
                        """);
                final var result=new JsonObject();
                result.addProperty("variant",variant);
                result.addProperty("phase",phase);
                result.addProperty("seconds",(System.nanoTime()-start)/1e9);
                result.add("frames",new com.google.gson.Gson().toJsonTree(frames));
                result.add("before",before);result.add("after",cdp.send("Performance.getMetrics"));
                result.add("processBefore",processBefore);result.add("processAfter",processes.send("SystemInfo.getProcessInfo"));
                Files.writeString(report,result+"\n",StandardOpenOption.APPEND);
                System.out.println("RAILIX_VARIANT "+result);
                assertThat(((Number)((Map<?,?>)frames).get("stations")).intValue()).isEqualTo(128);
                if(phase.equals("active"))page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve(variant+".png")));
                }
            }
            for(final String variant:variants){
                page.evaluate("async variant=>{window.probeRate=100;state.settings.theme_variant=variant;await applyTheme();}",variant);
                page.evaluate("() => new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))");
                final Object navigation=page.evaluate("""
                        async () => {
                          const times=[];let previous=performance.now();
                          for(let i=0;i<32;i++){
                            state.world.zoom(i<16?1.015:1/1.015);
                            const now=await new Promise(requestAnimationFrame);times.push(now-previous);previous=now;
                          }
                          times.sort((a,b)=>a-b);return {p95:times[Math.floor(times.length*.95)],max:times.at(-1)};
                        }
                        """);
                System.out.println("RAILIX_VARIANT_NAVIGATION "+variant+" "+navigation);
            }
            assertThat(pageErrors).isEmpty();
        } finally {
            cdp.detach();processes.detach();
            page.evaluate("() => {state.world.dispose();window.restoreProbeFetch();}");
        }
    }

    @Test
    void buildingFamiliesReplaceIconPlatesAndTheirRaisedFacesRemainSelectable() throws Exception {
        openProject(choiceProject());
        for (final var entry : Map.of("app", "app", "command", "trigger", "choice", "branch").entrySet()) {
            page.evaluate("id => void state.world.focus(id, 1)", entry.getKey());
            page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving === 'false'");
            awaitScene();
            assertThat(page.evaluate("id=>state.world._surface.stations.some(item=>item.node.id===id && item.sprite.bitmap.width>0)", entry.getKey()))
                    .isEqualTo(true);
            final BoundingBox roof = stationBounds(entry.getKey());
            assertThat(page.locator("[data-world-id='" + entry.getKey() + "'] .world-symbol").isVisible()).isFalse();
            final BoundingBox bounds = roof;
            page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target", "screenshots"))
                    .resolve("foundry-" + entry.getValue() + ".png")));
            page.mouse().click(bounds.x+bounds.width/2, bounds.y+bounds.height/2);
            page.waitForFunction("id => state.selection.id === id && !document.querySelector('#inspector').hidden", entry.getKey());
            assertThat(page.locator("#inspector").isVisible()).isTrue();
            page.locator("#close-inspector").click();
            final double width = stationBounds(entry.getKey()).width;
            page.evaluate("() => void state.world.zoom(1.2)");
            page.evaluate("() => new Promise(resolve => requestAnimationFrame(()=>requestAnimationFrame(resolve)))");
            assertThat(stationBounds(entry.getKey()).width).isCloseTo(width*1.2, within(.03));
        }
        assertThat(pageErrors).isEmpty();
    }


    @Test
    void cameraNavigationReusesFactoryMaterials() {
        startRendererTraffic(10, 30_000);
        page.evaluate("() => void window.__renderer.focus('step-0', 1)");
        page.waitForFunction("() => Number(document.querySelector('#graph').dataset.sceneScale) === 1 && document.querySelector('#graph').dataset.cameraMoving === 'false'");
        page.locator("#world-map").waitFor();
        page.evaluate("async () => { await window.__renderer.refresh(); await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))); }");
        final var pending = new ArrayList<com.microsoft.playwright.Route>();
        page.route("**/api/scene?*", pending::add);
        try {
            final JsonObject observation = JsonParser.parseString((String) page.evaluate("""
                    async () => {
                      const surface=window.__renderer._surface, ground=surface.ground, roofs=surface.roofs;
                      const before = window.__renderer._screenBounds(window.__renderer.scene.nodes.find(node=>node.id==='step-0'));
                      const changes = [];
                      const watch = new MutationObserver(records => changes.push(...records));
                      watch.observe(document.querySelector('#world-plane'), {attributes:true,attributeFilter:['style'],subtree:true});
                      window.__renderer.zoom(1.02);
                      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                      watch.disconnect();
                      return JSON.stringify({ratio:window.__renderer._screenBounds(window.__renderer.scene.nodes.find(node=>node.id==='step-0')).width/before.width,
                        materials:surface.ground===ground && surface.roofs===roofs ? 0 : 1});
                    }
                    """)).getAsJsonObject();
            assertThat(observation.get("ratio").getAsDouble()).isCloseTo(1.02, within(.001));
            assertThat(observation.get("materials").getAsInt())
                    .as("Camera-only frames move the factory, not every material").isZero();
        } finally {
            page.unroute("**/api/scene?*");
            pending.forEach(com.microsoft.playwright.Route::resume);
            page.evaluate("() => void window.__renderer.dispose()");
        }
    }

    @Test
    void zoomScalesDetailedMachinesWithTheCamera() {
        openProject(fourStepProject());
        page.evaluate("() => void state.world.focus('one')");
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving === 'false'");
        awaitScene();
        final BoundingBox before = stationBounds("one");
        page.evaluate("() => void state.world.zoom(1.4)");
        page.evaluate("() => new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))");
        assertThat(stationBounds("one").width).isCloseTo(before.width * 1.4, within(.01));
        assertThat(stationBounds("one").height).as("Camera scaling includes the casing's vertical depth")
                .isCloseTo(before.height * 1.4, within(.02));
        page.evaluate("async () => { await state.world.refresh(); await new Promise(requestAnimationFrame); }");
        assertThat(stationBounds("one").width).as("Scene refresh retains the camera's rendered scale")
                .isCloseTo(before.width * 1.4, within(.01));
    }

    @Test
    void cameraBatchKeepsThemeProjectionAndPickingAligned() {
        openProject(fourStepProject());
        page.evaluate("""
                () => {
                  document.querySelector('#world-plane').style.setProperty('--world-camera','rotateX(45deg) rotateZ(-40deg)');
                  dispatchEvent(new Event('resize'));
                  state.world.focus('one', 1);
                }
                """);
        page.waitForFunction("() => Number(document.querySelector('#graph').dataset.sceneScale) === 1 && document.querySelector('#graph').dataset.cameraMoving === 'false'");
        page.evaluate("async () => { await state.world.refresh(); await new Promise(requestAnimationFrame); }");
        final BoundingBox before = stationBounds("one");
        page.evaluate("() => void state.world.zoom(1.1)");
        page.locator("#graph").press("ArrowRight");
        page.evaluate("() => new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))");
        final BoundingBox moved = stationBounds("one");
        assertThat(moved.width).isCloseTo(before.width * 1.1, within(.02));
        page.mouse().click(moved.x+moved.width/2, moved.y+moved.height/2);
        page.waitForFunction("() => state.selection.id === 'one'");
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void homeReturnsToTheAppAtHalfScaleRegardlessOfGraphSize() {
        openProject(deepBranchProject(128));
        page.locator("#zoom-fit").click();
        page.waitForFunction("() => Number(new URLSearchParams(state.world.query).get('scale')) === .5");
        assertThat(page.locator("[data-world-id=app]").isVisible()).isTrue();
        assertThat(page.locator("#zoom-fit").getAttribute("aria-label")).isEqualTo("Home");
    }

    @Test
    void clickingANamedGroupEntersAndItsLocationOpensTheInspector() {
        openNamedGroupProject();
        page.locator("[data-region-group='pair']").click();
        page.locator("#world-location [data-inspect-region]").click();
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        assertThat(page.locator("#inspector [data-leave-group]").count()).isEqualTo(1);
        assertThat(page.locator("#clear-selection, #world-hud #enter-region, #open-inspector").count()).isZero();
    }

    @Test
    void longGroupNavigationReportsCostsWithViewportCulling() {
        openProject(deepBranchProject(128));
        page.locator("[data-group-region-label]").first().click();
        page.waitForFunction("() => document.querySelector('#zoom-level').textContent === '50%' && document.querySelector('#graph').dataset.cameraMoving !== 'true'");
        final CDPSession cdp = context.newCDPSession(page);
        try {
            cdp.send("Performance.enable");
            final JsonObject before = cdp.send("Performance.getMetrics");
            final Object frames = page.evaluate("""
                    async () => {
                      const times = [], commands = [];
                      let previous = performance.now();
                      for (let i=0; i<60; i++) {
                        await new Promise(requestAnimationFrame);
                        const now = performance.now();
                        times.push(now-previous); previous=now;
                        state.world.zoom(i%2 ? 1/1.005 : 1.005);
                        commands.push(performance.now()-now);
                      }
                      const summary = values => ({p95:values.toSorted((a,b)=>a-b)[Math.ceil(values.length*.95)-1], max:Math.max(...values)});
                      return {frames:summary(times), commands:summary(commands),
                        machines:state.world._surface.stations.length,
                        dom:document.querySelectorAll('#world-plane *').length};
                    }
                    """);
            System.out.printf("RAILIX_CHAIN_128_VIEWPORT frames=%s before=%s after=%s%n", frames, before, cdp.send("Performance.getMetrics"));
            assertThat(((Number)page.evaluate("() => state.world._surface.stations.filter(item=>item.node.kind==='step').length")).intValue()).isBetween(2, 127);
            assertThat(page.evaluate("() => state.world.scene.nodes.filter(node => node.kind === 'step').length")).isNotEqualTo(128);
        } finally {
            cdp.send("Performance.disable");
            cdp.detach();
        }
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void inspectorKeepsNavigationInItsHeaderAndRemovesDuplicateGroupSummary() {
        openNamedGroupProject();
        page.locator("[data-region-group='pair']").click();
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Inspect Pair").setExact(true)).click();
        assertThat(page.locator("#inspector-identity small").textContent()).isEqualTo("Group / 2 Steps");
        assertThat(page.locator(".selection-portrait canvas").count()).isEqualTo(1);
        assertThat(page.locator("#inspector-content").textContent()).doesNotContain("This section is not saved");
        assertThat(page.locator(".inspector-chrome [data-leave-group]").count()).isEqualTo(1);
        assertThat(page.locator(".inspector-chrome #dock-focus").count()).isEqualTo(1);
        assertThat(page.locator(".inspector-chrome [data-leave-group]").textContent()).isEmpty();
        assertThat(page.locator("#dock-observation .dock-counters").textContent()).isEmpty();
        assertThat(page.locator("#inspector .section-heading").allTextContents()).noneMatch(text -> text.contains("Connected"));
        page.locator("[data-inspector-mode=groups]").click();
        assertThat(page.locator("[data-inspector-mode=overview]").isVisible()).isTrue();
        page.locator("[data-inspector-mode=overview]").click();
        assertThat(page.locator("#selection-overview").isVisible()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void stepOverviewUsesPlainValuesAndAFooterTrashControl() throws IOException {
        openProject(fourStepProject());
        selectWorldNode("one");
        openInspectorTab("overview");
        assertThat(page.locator("#inspector-identity small").textContent()).startsWith("Step");
        assertThat(page.locator(".selection-portrait canvas").count()).isEqualTo(1);
        assertThat(page.locator(".selection-portrait").boundingBox().width).isBetween(32.0, 64.0);
        assertThat(page.locator(".inspector-chrome #dock-focus").getAttribute("aria-label")).isEqualTo("Focus");
        assertThat(page.locator("#inspector-footer #delete-step").getAttribute("aria-label")).startsWith("Delete ");
        assertThat(page.locator("#inspector-footer #delete-step").textContent()).isEqualTo("Delete");
        assertThat(page.locator("#dock-observation").evaluate("element=>getComputedStyle(element).borderTopWidth")).isEqualTo("0px");
        assertThat(page.locator("#dock-observation").evaluate("element=>getComputedStyle(element).backgroundColor")).isEqualTo("rgba(0, 0, 0, 0)");
        assertThat(page.locator("#selection-overview #manage-groups, #selection-overview #dock-focus, #selection-overview #delete-step").count()).isZero();
        page.waitForFunction("() => document.querySelector('#dock-observation .dock-counters').textContent.includes('runs')");
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target", "screenshots"))
                .resolve("inspector-header-controls.png")));
        openInspectorTab("appearance");
        assertThat(page.locator("#choose-icon,#reset-icon,.icon-editor").count()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"app,Application,app", "command,Trigger,trigger"})
    void overviewIdentifiesTheSelectedEntryBuilding(final String id, final String type, final String building) {
        openProject(choiceProject());
        selectWorldNode(id);
        openInspectorTab("overview");
        assertThat(page.locator("#inspector-identity small").textContent()).isEqualTo(type);
        assertThat(page.locator("#inspector-identity h2").textContent()).isNotBlank();
        assertThat(page.locator(".selection-portrait canvas").count()).isEqualTo(1);
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isEqualTo(id);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void enteredAutomaticGroupStartsAtItsFirstStepWithLocalHalfScale() throws IOException {
        openProject(deepBranchProject(128));
        final String parentZoom = page.locator("#zoom-level").textContent();
        page.locator("[data-group-region-label]").first().click();
        page.waitForFunction("() => new URLSearchParams(state.world.query).has('inside')");
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving !== 'true'");
        page.evaluate("() => new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))");
        page.waitForFunction("() => document.getAnimations().every(animation => animation.playState !== 'running' || animation.effect.getComputedTiming().iterations === Infinity)");
        assertThat(page.locator("#zoom-level").textContent()).isEqualTo("50%");
        assertThat(page.locator("[data-select-node=step-0]").isVisible()).isTrue();
        final double width = stationBounds("step-0").width;
        page.locator("#zoom-in").click();
        page.evaluate("() => new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))");
        assertThat(page.locator("#zoom-level").textContent()).isEqualTo("70%");
        assertThat(stationBounds("step-0").width)
                .isCloseTo(width * 1.4, org.assertj.core.data.Offset.offset(.5));
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target", "screenshots"))
                .resolve("world-128-entry.png")));
        final String inside = (String) page.evaluate("() => new URLSearchParams(state.world.query).get('inside')");
        page.locator("#zoom-out").click();
        page.locator("#zoom-out").click();
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving !== 'true'");
        page.evaluate("async () => {await state.world.refresh();}");
        assertThat(page.evaluate("() => new URLSearchParams(state.world.query).get('inside')")).isEqualTo(inside);
        assertThat(page.locator("#leave-group").isVisible()).isTrue();
        page.locator("#leave-group").click();
        page.waitForFunction("() => !new URLSearchParams(state.world.query).has('inside')");
        assertThat(page.locator("#zoom-level").textContent()).isEqualTo(parentZoom);
        assertThat(page.locator("#world-map").isVisible()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void centralExamplesRemainAvailableWithoutHoverOrSelection() {
        openProject(choiceProject());
        page.locator("#zoom-fit").click();
        awaitScene();
        final String selected = (String) page.evaluate("() => state.selection.id");
        page.locator("[data-select-node=command]").hover();
        page.locator("#visible-example").waitFor();
        assertThat(page.evaluate("() => state.selection.id")).isEqualTo(selected);
        page.locator("#visible-example").focus();
        // A native popup can leave the page's hover tree while its select still owns focus.
        page.mouse().move(1, 1);
        page.waitForTimeout(900);
        assertThat(page.locator("#visible-example").isVisible()).isTrue();
        page.locator("#visible-example").selectOption("");
        page.waitForFunction("() => document.querySelector('#graph').dataset.replaying === 'false'");
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        page.locator("#graph").focus();
        assertThat(page.locator("#trigger-example").count()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void wheelEntryPinsTheGroupAndHomeLeavesIt() {
        openProject(deepBranchProject(128));
        final var group = page.locator("[data-group-region-label]").first();
        // A narrow viewport starts farther out; cross the detail boundary with real wheel gestures.
        for (int zoom = 0; zoom < 3 && !Boolean.TRUE.equals(page.evaluate(
                "() => new URLSearchParams(state.world.query).has('inside')")); zoom++) {
            group.hover();
            final Object before = page.evaluate("() => state.world.query");
            page.mouse().wheel(0, -1100);
            page.waitForFunction("before => state.world.query !== before", before);
            awaitScene();
        }
        page.waitForFunction("() => new URLSearchParams(state.world.query).has('inside')");
        page.waitForFunction("() => document.querySelector('#zoom-level').textContent === '50%'");
        page.locator("#zoom-fit").click();
        page.waitForFunction("() => !new URLSearchParams(state.world.query).has('inside') && document.querySelector('#zoom-level').textContent === '50%'");
        assertThat(page.locator("#leave-group").isVisible()).isFalse();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void changingANeighbourCannotResizeAnUnchangedMachine() {
        openProject(fourStepProject());
        page.evaluate("""
                async () => {
                  state.world.dispose();
                  window.__aspect = 1;
                  window.__renderer = new RailixWorld(document.querySelector('#graph'), {
                    appearance: node => ({aspect:node.id === 'two' ? window.__aspect : 1})
                  });
                  await window.__renderer.refresh();
                  await window.__renderer.refresh();
                }
                """);
        page.waitForFunction("() => window.__renderer._surface.stations.some(item=>item.node.id==='one')");
        final double width = stationBounds("one").width;
        page.evaluate("() => { window.__aspect=2.625; window.__renderer.repaint(); }");
        page.evaluate("() => new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))");
        assertThat(stationBounds("one").width).isEqualTo(width);
        page.evaluate("() => void window.__renderer.dispose()");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"zoom", "pan"})
    void cameraNavigationCannotAbruptlyResizeExistingStations(final String gesture) {
        openProject(deepBranchProject(128));
        awaitScene();
        page.evaluate("""
                () => void state.world.focus(state.world.scene.nodes.find(node=>node.kind==='region' && node.count===128).id)
                """);
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving === 'false'");
        awaitScene();
        final String sizes = """
                () => Object.fromEntries(state.world.scene.nodes.filter(node=>!node.expanded)
                  .map(node=>[node.id,state.world._screenBounds(node).width]))
                """;
        int comparisons = 0;
        for (int tick = 0; tick < 12; tick++) {
            final Object before = page.evaluate(sizes);
            final String cameraScale = "() => Number(new URLSearchParams(state.world.query).get('scale'))";
            final double previousScale = ((Number) page.evaluate(cameraScale)).doubleValue();
            if (gesture.equals("zoom")) page.locator("#zoom-in").click();
            else page.locator("#graph").press(tick < 6 ? "ArrowRight" : "ArrowLeft");
            // Deep zoom can legitimately frame empty space; the general helper requires a label.
            page.evaluate("""
                    async () => {
                      await state.world.refresh();
                      await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
                    }
                    """);
            assertThat(page.locator("#graph").getAttribute("data-scene-error")).isNull();
            final var result = (List<?>) page.evaluate("""
                    settings => {
                      const before=settings[0],factor=settings[1];
                      const pairs=state.world.scene.nodes.filter(node=>!node.expanded && before[node.id])
                        .map(node=>({id:node.id,ratio:state.world._screenBounds(node).width/before[node.id]}));
                      return [pairs.length,pairs.filter(pair=>pair.ratio<.999 || pair.ratio>factor+.001)];
                    }
                    """, List.of(before, ((Number) page.evaluate(cameraScale)).doubleValue() / previousScale));
            comparisons += ((Number) result.getFirst()).intValue();
            assertThat((List<?>) result.get(1)).as("Station continuity during " + gesture + " tick " + tick).isEmpty();
        }
        assertThat(comparisons).isPositive();
        assertThat(pageErrors).isEmpty();
    }


    @Test
    void changingAMachineShapeKeepsItsBranchAxisInPlace() {
        openProject(choiceProject());
        selectWorldNode("choice");
        openInspectorTab("appearance");
        page.evaluate("""
                () => {
                  const draw=RailixCanvas.prototype.junction,station=RailixCanvas.prototype.station;
                  RailixCanvas.prototype.junction=function(point,...args){window.cornerPaints.push([...point]);return draw.call(this,point,...args);};
                  RailixCanvas.prototype.station=function(item,...args){
                    const scale=Number(document.querySelector('#graph').dataset.sceneScale),{node,box}=item;
                    window.cornerOffset=[box.x+box.width/2-(node.x+node.width/2)*scale,box.y+box.height/2-(node.y+node.height/2)*scale];
                    return station.call(this,item,...args);
                  };
                  window.cornerPaints=[];
                }
                """);
        final String painted="""
                async()=>{window.cornerPaints=[];state.world.repaint();await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));const scale=Number(document.querySelector('#graph').dataset.sceneScale);return [...new Set(window.cornerPaints.map(point=>JSON.stringify(point.map((value,axis)=>Number(((value-window.cornerOffset[axis])/scale).toFixed(3))))))].sort();}
                """;
        page.waitForResponse(response->response.url().endsWith("/api/editor") && response.request().method().equals("POST"),()->{
            page.locator("#presentation-aspect").fill("1");page.locator("#presentation-aspect").press("Tab");
        });
        final var expected=(List<?>)page.evaluate("async()=>{const scene=await(await fetch('/api/scene?'+state.world.query)).json();return [...new Set(scene.links.flatMap(link=>link.points.slice(1,-1)).map(JSON.stringify))].sort();}");
        final Object positions=page.evaluate(painted);
        assertThat(expected).containsAll((List)positions);
        page.waitForResponse(response->response.url().endsWith("/api/editor") && response.request().method().equals("POST"),()->{
            page.locator("#presentation-aspect").fill("2.625");page.locator("#presentation-aspect").press("Tab");
        });
        final var changed=(List<?>)page.evaluate(painted);
        assertThat(changed).isNotEmpty();
        assertThat(expected).containsAll((List)changed);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void panningKeepsRenderedBeltCornersAtTheirSceneCoordinates() {
        final StressScene fixture=systemStressScene();
        openProject(fixture.project());
        assertThat(page.evaluate("async creator=>(await fetch('/api/creator',{method:'POST',headers:mutationHeaders(),body:creator})).status",fixture.creator())).isEqualTo(200);
        page.reload();
        awaitScene();
        page.locator("[data-group-region-label='area:payments-command']").click();
        page.waitForFunction("() => state.world.scene.inside==='area:payments-command' && document.querySelector('#graph').dataset.cameraMoving==='false'");
        page.evaluate("""
                () => {
                  const begin=RailixCanvas.prototype.begin,junction=RailixCanvas.prototype.junction,station=RailixCanvas.prototype.station;
                  RailixCanvas.prototype.begin=function(...args){window.paintJobs={corners:[],offset:null};return begin.apply(this,args);};
                  RailixCanvas.prototype.junction=function(point,...args){window.paintJobs.corners.push([...point]);return junction.call(this,point,...args);};
                  RailixCanvas.prototype.station=function(item,...args){
                    const scale=Number(document.querySelector('#graph').dataset.sceneScale),{node,box}=item;
                    window.paintJobs.offset=[box.x+box.width/2-(node.x+node.width/2)*scale,box.y+box.height/2-(node.y+node.height/2)*scale];
                    return station.call(this,item,...args);
                  };
                  state.world.repaint();
                }
                """);
        final String capture = """
                async () => {
                  await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
                  const scene=await (await fetch('/api/scene?'+state.world.query)).json();
                  const scale=Number(document.querySelector('#graph').dataset.sceneScale),{corners,offset}=window.paintJobs;
                  if(!offset)return {};
                  const painted=corners.map(point=>point.map((value,axis)=>(value-offset[axis])/scale));
                  const result={};
                  for(const link of scene.links.filter(link=>!link.power))for(let i=1;i<link.points.length-1;i++) {
                    const point=link.points[i];
                    const nearest=painted.toSorted((a,b)=>Math.hypot(a[0]-point[0],a[1]-point[1])-Math.hypot(b[0]-point[0],b[1]-point[1]))[0];
                    if(nearest&&Math.hypot(nearest[0]-point[0],nearest[1]-point[1])<48*link.station_scale)result[link.id+':'+i]=nearest;
                  }
                  return result;
                }
                """;
        int comparisons=0;
        for(int tick=0;tick<18;tick++) {
            final Object before=page.evaluate(capture);
            final String direction=tick<9?"ArrowRight":"ArrowLeft";
            page.waitForResponse(response->response.url().contains("/api/scene?"),
                    ()->page.locator("#graph").press(direction));
            final Object after=page.evaluate(capture);
            final var compared=(List<?>)page.evaluate("""
                    pair=>{
                      const [before,after]=pair,common=Object.keys(before).filter(id=>after[id]);
                      return [common.length,common.filter(id=>Math.hypot(before[id][0]-after[id][0],before[id][1]-after[id][1])>.01)
                        .map(id=>({id,before:before[id],after:after[id]}))];
                    }
                    """,List.of(before,after));
            comparisons+=((Number)compared.getFirst()).intValue();
            assertThat((List<?>)compared.get(1)).as("Rendered corners during camera pan %s",tick).isEmpty();
        }
        assertThat(comparisons).isGreaterThan(20);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void panningDoesNotShiftTheTextureWithinAnIdleBeltCorner() {
        openProject(fourStepProject());
        assertThat(page.evaluate("""
                () => {
                  const renderer=new RailixCanvas(document.createElement('div'),{sprites:new Map()});
                  try {
                    const paint=point=>{
                      renderer.begin({width:640,height:480},{m11:1,m12:0,m21:0,m22:1,m31:0,m32:0},[0,0],getComputedStyle(document.documentElement));
                      renderer.junction(point,6,{},1);
                      renderer.animate({enabled:false,powered:false,moving:false});
                      return [...renderer.context.getImageData(point[0]-10,point[1]-10,20,20).data];
                    };
                    return JSON.stringify(paint([200,200]))===JSON.stringify(paint([213,207]));
                  } finally {renderer.dispose();}
                }
                """)).as("Moving the camera must translate the corner, not slide its idle tread").isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void choiceLateralElbowsHaveContinuousSurfacesWithoutAnExternalSplitter() {
        openProject(choiceProject());
        awaitScene();
        assertThat(((Number)page.evaluate("() => state.world._surface.junctions.length")).intValue()).isGreaterThanOrEqualTo(2);
        assertThat(page.evaluate("() => state.world._surface.junctions.every(item=>item.mask>0 && item.mask<=15 && item.unit>0)"))
                .isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }


    @Test
    void visibleMeasurementsSetTheTimeScaleWithoutAnExampleOrEnablingMissingMetrics() {
        openProject(fourStepProject().replace("\"id\":\"two\",", "\"id\":\"two\",\"metrics\":false,"));
        awaitScene();
        final Locator scale = page.locator("#example-time-scale");
        scale.waitFor();
        assertThat(scale.innerText()).contains("Visible avg", "No data");
        page.locator("#visible-example").selectOption("command:0");
        scale.waitFor();
        assertThat(scale.innerText()).contains("Visible avg", "No data");
        assertThat(scale.getAttribute("title")).contains("previous Step", "slowest measured Step", "Not CPU load");
        assertThat(scale.evaluate("element=>getComputedStyle(element).cursor")).isEqualTo("default");
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Home").setExact(true)).click();
        page.locator("#scope-overview").click();
        assertThat(scale.innerText()).doesNotContain("1 s+");
        page.locator("[data-select-node='two']").click();
        page.waitForFunction("() => document.querySelector('[data-world-id=two]')?.title.includes('Metrics off')");
        assertThat(page.locator("[data-world-id=two] .world-duration").count()).isZero();
        page.locator("#visible-example").selectOption("");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(scale).isVisible();
        assertThat(page.locator("#editor-undo").isDisabled()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void timePaletteUsesVisibleMeasurementsAndKeepsMissingDistinctFromZero() {
        openProject(fourStepProject());
        assertThat(page.evaluate("""
                () => {
                  const renderer=new RailixCanvas(document.createElement('div'),{sprites:new Map()});
                  try {
                    renderer.begin({width:100,height:100},{m11:1,m12:0,m21:0,m22:1,m31:0,m32:0},[0,0],getComputedStyle(document.documentElement));
                    renderer.timing([10_000,40_000,160_000]);
                    const short=renderer.timeColor(10_000),long=renderer.timeColor(160_000),missing=renderer.timeColor(null);
                    return short!==long && short!==missing && long!==missing
                      && renderer.timeColor(0)===short && renderer.timeColor(1e12)===long
                      && renderer.timeColor(undefined)===missing && renderer.timeColor(-1)===missing
                      && renderer.timeColor(40_000)!==short && renderer.timeColor(40_000)!==long
                      && renderer.timeScale[0]===10_000 && renderer.timeScale.at(-1)===160_000;
                  } finally {renderer.dispose();}
                }
                """)).isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void timingHasAStablePlaceWithoutRelativeHeatOrSelection() {
        openProject(fourStepProject());
        page.evaluate("""
                async () => {
                  state.world.dispose();
                  window.__renderer = new RailixWorld(document.querySelector('#graph'), {
                    appearance: node => ({duration:'12 ms',heat:0,selected:false})
                  });
                  await window.__renderer.refresh();
                  window.__renderer.focus('one');
                }
                """);
        final Locator timing = page.locator("[data-world-id=one] .world-duration");
        timing.waitFor();
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving === 'false'");
        assertThat(timing.textContent()).isEqualTo("12 ms avg");
        final double before = timing.boundingBox().y - page.locator("[data-world-id=one]").boundingBox().y;
        page.locator("#graph").press("ArrowRight");
        assertThat(timing.boundingBox().y - page.locator("[data-world-id=one]").boundingBox().y).isCloseTo(before, within(.1));
        assertThat(page.locator(".world-metric").count()).isZero();
        page.evaluate("() => { window.__renderer.dispose(); }");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void measuredDurationIsReadableWithoutSelectingTheMachine() {
        openProject(fourStepProject());
        page.evaluate("""
                async () => {
                  state.world.dispose();
                  window.__renderer = new RailixWorld(document.querySelector('#graph'), {appearance: () => ({duration:'12 ms'})});
                  await window.__renderer.refresh(); window.__renderer.focus('one');
                }
                """);
        page.locator("[data-world-id=one] .world-duration").waitFor();
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving === 'false'");
        assertThat(page.locator("[data-world-id=one] .world-duration").textContent()).contains("12 ms", "avg");
        final BoundingBox text = page.locator("[data-world-id=one] .world-duration").boundingBox();
        final BoundingBox machine = stationBounds("one");
        assertThat(text.y).isGreaterThanOrEqualTo(machine.y);
        final BoundingBox viewport=page.locator("#graph").boundingBox();
        assertThat(text.y + text.height).isLessThanOrEqualTo(viewport.y + viewport.height);
        assertThat(text.x).isGreaterThanOrEqualTo(viewport.x);
        assertThat(text.x + text.width).isLessThanOrEqualTo(viewport.x + viewport.width);
        page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/screenshots/readable-step-duration.png")));
        assertThat(((Number) page.locator("[data-world-id=one] .world-duration")
                .evaluate("element => parseFloat(getComputedStyle(element).fontSize)")).doubleValue()).isGreaterThanOrEqualTo(12);
        page.evaluate("() => void window.__renderer.dispose()");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void applicationPollingDoesNotDuplicateThePendingSelectedStepRead() {
        openProject(fourStepProject());
        awaitScene();
        page.waitForFunction("() => state.application.examples?.state === 'completed'");
        page.locator("[data-world-id=command]").click();
        openInspectorTab("overview");
        waitForText("#dock-observation [data-dock-value=output] output", "Array (0)");
        page.evaluate("""
                () => {
                  const fetch = window.fetch;
                  window.__stepReads = 0;
                  window.__statusReads = 0;
                  window.__releaseStepReads = [];
                  window.fetch = async (...args) => {
                    const response = await fetch(...args);
                    if (String(args[0]) === '/api/examples/status') window.__statusReads++;
                    if (String(args[0]).startsWith('/api/examples/steps/')) {
                      window.__stepReads++;
                      await new Promise(resolve => window.__releaseStepReads.push(resolve));
                    }
                    return response;
                  };
                  window.__restoreFetch = () => {
                    window.fetch = fetch;
                    window.__releaseStepReads.forEach(resolve => resolve());
                  };
                }
                """);
        try {
            page.locator("[data-world-id=one]").click();
            page.waitForFunction("() => window.__stepReads > 1 || window.__statusReads >= 2 && window.__stepReads > 0");
            assertThat(page.evaluate("() => window.__stepReads")).isEqualTo(1);
        } finally {
            page.evaluate("() => window.__restoreFetch()");
        }
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("browser-diagnostics")
    void navigatingKeepsObservationsForUnchangedVisibleStationsWhileTheNextReadIsPending() {
        holdObservationsAfterFirstSample();
        openProject(fourStepProject());
        awaitScene();
        page.waitForFunction("() => document.querySelector('[data-world-id=one]')?.dataset.activity === 'active'");
        try {
            // Hold the current read before taking the baseline. The first sample has no rate
            // that could legitimately appear or expire while navigation is pending.
            page.waitForFunction("() => window.__releaseObservations.some(held => held.signal === state.observationController?.signal)");
            final String before = page.locator("[data-world-id=one]").getAttribute("title");
            assertThat(before).doesNotContain("/s");
            final String query = (String) page.evaluate("() => state.world.query");
            final var previousController = page.evaluateHandle("() => state.observationController");
            page.locator("#graph").press("ArrowRight");
            page.waitForFunction("query => state.world.query !== query", query);
            page.waitForFunction("""
                    previous => state.observationController !== previous
                      && window.__releaseObservations.some(held => held.signal === state.observationController?.signal)
                    """, previousController);
            assertThat(page.locator("[data-world-id=one]").getAttribute("data-activity")).isEqualTo("active");
            assertThat(page.locator("[data-world-id=one]").getAttribute("title")).isEqualTo(before);
        } finally {
            page.evaluate("() => window.__restoreFetch()");
        }
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("browser-diagnostics")
    void aSecondObservationAddsItsRateBeforeAnyNavigation() {
        holdObservationsAfterFirstSample();
        openProject(fourStepProject());
        awaitScene();
        page.waitForFunction("() => document.querySelector('[data-world-id=one]')?.dataset.activity === 'active'");
        try {
            final String before = page.locator("[data-world-id=one]").getAttribute("title");
            final String query = (String) page.evaluate("() => state.world.query");
            assertThat(before).doesNotContain("/s");
            page.waitForFunction("() => window.__releaseObservations.some(held => held.signal === state.observationController?.signal)");
            page.evaluate("() => window.__releaseObservations.find(held => held.signal === state.observationController.signal).resolve()");
            page.waitForFunction("() => document.querySelector('[data-world-id=one]').title.includes('0.00/s')");
            assertThat(page.evaluate("() => state.world.query")).isEqualTo(query);
            assertThat(page.locator("[data-world-id=one]").getAttribute("title"))
                    .isEqualTo(before.replace(" executions · ", " executions · 0.00/s · "));
            assertThat(page.locator("[data-world-id=one]").getAttribute("data-activity")).isEqualTo("active");
            assertThat(pageErrors).isEmpty();
        } finally {
            page.evaluate("() => window.__restoreFetch()");
        }
    }

    private void holdObservationsAfterFirstSample() {
        page.addInitScript("""
                const observationFetch = window.fetch;
                let holdObservations = false;
                window.__releaseObservations = [];
                window.fetch = async (...args) => {
                  const response = await observationFetch(...args);
                  if (String(args[0]).startsWith('/api/scene/observations?')) {
                    // Once sampling is established, losing observations must not release later reads.
                    holdObservations ||= Boolean(currentWorldObservations()?.nodes.has('one'));
                    if (holdObservations)
                      await new Promise(resolve => window.__releaseObservations.push({resolve, signal:args[1]?.signal}));
                  }
                  return response;
                };
                window.__restoreFetch = () => {
                  window.fetch = observationFetch;
                  window.__releaseObservations.forEach(held => held.resolve());
                };
                """);
    }


    @Test
    void gridKeepsItsDominantSpacingAcrossAZoomBoundary() {
        openProject(fourStepProject());
        awaitScene();
        page.evaluate("() => void state.world.zoom(.9999 / Number(new URLSearchParams(state.world.query).get('scale')))");
        awaitScene();
        final double before = ((Number) page.evaluate("() => parseFloat(getComputedStyle(document.querySelector('.world-floor')).getPropertyValue('--grid'))")).doubleValue();
        page.evaluate("() => void state.world.zoom(1.0001 / Number(new URLSearchParams(state.world.query).get('scale')))");
        awaitScene();
        final double after = ((Number) page.evaluate("() => 2 * parseFloat(getComputedStyle(document.querySelector('.world-floor')).getPropertyValue('--grid'))")).doubleValue();
        assertThat(after).isCloseTo(before, within(.02));
        assertThat(((Number) page.evaluate("() => Number(getComputedStyle(document.querySelector('.world-floor')).getPropertyValue('--grid-alpha'))")).doubleValue()).isLessThan(.001);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void factoryRendererLoadsLocallyWithoutDynamicCodeExecution() {
        page.route("**/", route -> {
            final var response = route.fetch();
            final var headers = new java.util.HashMap<>(response.headers());
            headers.put("Content-Security-Policy", "script-src 'self'; object-src 'none'; base-uri 'none'");
            route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                    .setResponse(response).setHeaders(headers));
        });
        final List<String> externalRequests = new ArrayList<>();
        page.onRequest(request -> {
            if (!request.url().startsWith(creator.baseUri().toString().split("/#")[0]))
                externalRequests.add(request.url());
        });
        openProject(fourStepProject());
        awaitScene();
        assertThat(page.locator("#graph").getAttribute("data-renderer")).isEqualTo("canvas");
        assertThat(page.evaluate("() => typeof window.PIXI")).isEqualTo("undefined");
        assertThat(((Number)page.evaluate("() => state.world._surface.stations.length")).intValue()).isGreaterThan(2);
        assertThat(externalRequests).isEmpty();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void denseBranchStationsHaveSeparateVisibleBodies() {
        openProject(nestedSwitchProject());
        awaitScene();
        final List<?> overlaps = (List<?>) page.evaluate("""
                () => {
                  const bodies = state.world._surface.stations.map(item => ({left:item.x,top:item.y,right:item.x+item.width,bottom:item.y+item.height}));
                  return bodies.flatMap((a, index) => bodies.slice(index + 1)
                    .filter(b => a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom)
                    .map(b => ({first: a.toJSON(), second: b.toJSON()})));
                }
                """);
        assertThat(((Number)page.evaluate("() => state.world._surface.stations.length")).intValue()).isGreaterThanOrEqualTo(4);
        assertThat(overlaps).isEmpty();
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void focusingAStationUsesBoundedMotionAndSceneRequests(final boolean reduced) {
        openProject(fourStepProject());
        page.emulateMedia(new Page.EmulateMediaOptions().setReducedMotion(reduced
                ? com.microsoft.playwright.options.ReducedMotion.REDUCE
                : com.microsoft.playwright.options.ReducedMotion.NO_PREFERENCE));
        final List<String> requests = new ArrayList<>();
        page.onRequest(request -> {
            if (request.url().contains("/api/scene?")) requests.add(request.url());
        });
        page.evaluate("""
                () => {
                  window.__cameraMoved = false;
                  window.__cameraObserver = new MutationObserver(records => {
                    if (records.some(record => record.attributeName === 'data-camera-moving' && record.oldValue === 'true'))
                      window.__cameraMoved = true;
                  });
                  window.__cameraObserver.observe(document.querySelector('#graph'), {attributes:true,attributeOldValue:true});
                  state.world.focus('one',1);
                }
                """);
        page.waitForFunction("() => Number(new URLSearchParams(state.world.query).get('scale')) >= 1");
        awaitScene();
        assertThat(page.evaluate("() => window.__cameraMoved")).isEqualTo(true);
        assertThat(requests).hasSizeBetween(2, 4);
        assertThat(page.locator("#graph").getAttribute("data-camera-moving")).isEqualTo("false");
        page.evaluate("() => window.__cameraObserver.disconnect()");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void browserScrollingCannotMoveTheWorldOutsideItsCamera() {
        openProject(deepBranchProject(128));
        awaitScene();
        final BoundingBox tools = page.locator(".canvas-tools").boundingBox();
        page.evaluate("() => document.querySelector('#graph').scrollBy(0, 350)");
        final BoundingBox after = page.locator(".canvas-tools").boundingBox();
        assertThat(after.y).as("Browser focus scrolling must not displace the camera controls").isEqualTo(tools.y);
        assertThat(page.evaluate("() => document.querySelector('#graph').scrollTop")).isEqualTo(0);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void locatingATriggerKeepsItVisibleBesideALargeAutomaticGroup() {
        openProject(deepBranchProject(128));
        selectWorldNode("command");
        openInspectorTab("overview");
        final Response response = page.waitForResponse(CreatorWorldBrowserIT::viewportResponse,
                () -> page.locator("#dock-focus").click());
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        finishScene(response);
        awaitScene();
        final BoundingBox body = stationBounds("command");
        final BoundingBox graph = page.locator("#graph").boundingBox();
        assertThat(body).isNotNull();
        assertThat(body.x + body.width / 2).isBetween(graph.x, graph.x + graph.width);
        assertThat(body.y + body.height / 2).isBetween(graph.y, graph.y + graph.height);
        assertThat(page.locator("[data-world-id=command]").getAttribute("aria-pressed")).isEqualTo("true");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"zoom", "press", "visibility"})
    void aCanvasGestureCancelsAnInProgressFocusFlight(final String gesture) {
        openProject(fourStepProject());
        awaitScene();
        page.bringToFront();
        page.waitForFunction("() => !document.hidden");
        page.evaluate("() => { state.world.focus('one'); }");
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving === 'true'");
        final Response response = page.waitForResponse(CreatorWorldBrowserIT::viewportResponse, () -> {
            if ("zoom".equals(gesture)) page.evaluate("() => { state.world.zoom(0.9); }");
            else if ("visibility".equals(gesture)) page.evaluate("""
                    () => {
                      // Control browser lifecycle state, not application execution or endpoint results.
                      Object.defineProperty(document, 'hidden', {configurable:true, value:true});
                      document.dispatchEvent(new Event('visibilitychange'));
                      delete document.hidden;
                      document.dispatchEvent(new Event('visibilitychange'));
                    }
                    """);
            else {
                final BoundingBox graph = page.locator("#graph").boundingBox();
                page.mouse().click(graph.x + 8, graph.y + 8);
            }
            assertThat(page.locator("#graph").getAttribute("data-camera-moving")).isEqualTo("false");
        });
        finishScene(response);
        awaitScene();
        final String query = (String) page.evaluate("() => state.world.query");
        page.evaluate("() => new Promise(resolve => setTimeout(resolve, 250))");
        assertThat(page.evaluate("() => state.world.query")).isEqualTo(query);
        assertThat(page.locator("#graph").getAttribute("data-camera-moving")).isEqualTo("false");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void fittingInvalidatesAnEarlierFocusBeforeTheFirstAnimationFrame() {
        openProject(fourStepProject());
        page.evaluate("""
                () => {
                  const fetch = window.fetch;
                  window.fetch = async (...args) => {
                    const response = await fetch.apply(window, args);
                    if (String(args[0]).includes('focus=one')) await new Promise(resolve => window.__releaseFocus = resolve);
                    return response;
                  };
                  window.__restoreFocus = () => { window.__releaseFocus?.(); window.fetch = fetch; };
                  state.world.focus('one');
                }
                """);
        try {
            page.waitForFunction("() => Boolean(window.__releaseFocus)");
            finishScene(page.waitForResponse(CreatorWorldBrowserIT::viewportResponse, () -> page.evaluate("""
                    () => { state.world.fit(); window.__releaseFocus(); }
                    """)));
            assertThat(((Number) page.evaluate("() => Number(new URLSearchParams(state.world.query).get('scale'))")).doubleValue())
                    .isLessThanOrEqualTo(1);
            assertThat(pageErrors).isEmpty();
        } finally {
            page.evaluate("() => window.__restoreFocus()");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void selectedExamplesAndActiveTrafficRemainVisibleOnSharedBranchTrunks(final boolean highlight) {
        openProject(deepBranchProject(4));
        page.evaluate("""
                async highlight => {
                  state.world.dispose();
                  let selected;
                  window.__renderer = new RailixWorld(document.querySelector('#graph'), {
                    linkAppearance: link => ({selected:highlight && link.id===selected,rate:link.id===selected?10:0})
                  });
                  await window.__renderer.refresh();
                  const scene = window.__renderer.scene;
                  const link = scene.links.find(link => scene.links.filter(other => other.from===link.from).length>1);
                  selected = link.id;
                  await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                }
                """, highlight);
        page.waitForFunction("() => document.querySelector('#graph').dataset.trafficAnimated === 'true'");
        assertThat(page.evaluate("() => window.__renderer._surface.runs.some(run=>run.look.rate>0)")).isEqualTo(true);
        assertThat(page.evaluate("highlight => window.__renderer._surface.runs.some(run=>Boolean(run.look.selected)===highlight)", highlight)).isEqualTo(true);
        page.evaluate("() => { window.__renderer.dispose(); }");
        assertThat(pageErrors).isEmpty();
    }


    @ParameterizedTest
    @Tag("responsive")
    @ValueSource(strings = {"ellipse", "triangle", "diamond", "rectangle", "hexagon", "event", "storage", "subsystem"})
    void shapeCornersDoNotCaptureClicksOutsideTheirOutline(final String shape) {
        openProject(fourStepProject());
        final Number status = (Number) page.evaluate("""
                shape => fetch('/api/creator', {method:'PATCH',headers:mutationHeaders(),body:JSON.stringify({
                  revision:state.creatorVersion, changes:{steps:{one:{shape,aspect:1,roundness:50}}}
                })}).then(response => response.status)
                """, shape);
        assertThat(status.intValue()).isEqualTo(200);
        page.reload();
        waitForText("#build-state", "Built");
        selectWorldNode("command");
        page.locator("#close-inspector").click();
        page.evaluate("() => { state.world.focus('one'); }");
        awaitScene();
        final BoundingBox outline = stationBounds("one");
        final double x = outline.x + outline.width/2;
        final double y = outline.y + outline.height/2;
        final double outsideX = shape.equals("triangle") ? outline.x + outline.width - 1 : outline.x + 1;
        final double outsideY = outline.y + 1;
        assertThat(page.evaluate("point => document.elementFromPoint(...point)?.closest('[data-world-id]') === null",
                List.of(outsideX, outsideY))).as("The outline probe must not click the separate text label").isEqualTo(true);
        page.mouse().click(outsideX, outsideY);
        page.waitForFunction("() => !state.editorController");
        assertThat(page.evaluate("() => state.world._surface.stations.some(item=>item.node.id==='one' && item.look.selected)")).isEqualTo(false);
        page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target", "screenshots", "shape-hit-" + shape + ".png")));
        assertThat(page.evaluate("""
                point => {
                  const target = document.elementFromPoint(...point);
                  return target && target.closest('#graph') && !target.closest('[data-world-overlay], .canvas-tools')
                    ? 'world' : JSON.stringify({point, target:target?.outerHTML});
                }
                """, List.of(x, y))).as("The focused machine body must remain clear of the HUD").isEqualTo("world");
        page.mouse().click(x, y);
        page.waitForFunction("() => document.querySelector('[data-world-id=one]')?.getAttribute('aria-pressed') === 'true'");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(doubles = {1, 100, 1_000_000})
    void trafficFramesReuseGeometryAndLabelsAtEveryRate(final double rate) {
        startRendererTraffic(rate, 30_000);
        page.evaluate("""
                () => { window.__parts = [...document.querySelectorAll('#world-plane *, #world-labels *')];
                  window.__time = window.__renderer._surface.time; }
                """);
        page.waitForFunction("() => window.__renderer._surface.time > window.__time + 100");
        assertThat(page.evaluate("() => window.__parts.every(element => element.isConnected) && window.__parts.length === document.querySelectorAll('#world-plane *, #world-labels *').length")).isEqualTo(true);
        assertThat(page.evaluate("() => window.__renderer._surface.runs.some(run=>run.look.rate>0)")).isEqualTo(true);
        System.out.println("FOUNDRY_TRAFFIC rate=" + rate + " " + page.evaluate("""
                async () => {
                  const frames=[]; let previous=await new Promise(requestAnimationFrame);
                  for (let i=0;i<60;i++) {
                    const now=await new Promise(requestAnimationFrame); frames.push(now-previous); previous=now;
                  }
                  frames.sort((a,b)=>a-b);
                  return {p95:frames[56],max:frames[59],routes:window.__renderer._surface.routes.length,
                    elements:document.querySelectorAll('#world-plane *').length};
                }
                """));
        assertThat(pageErrors).isEmpty();
        page.evaluate("() => void window.__renderer.dispose()");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expiredObservationsStopTrafficButNotSelectedExampleReplay(final boolean replay) {
        startRendererTraffic(10, 1_000, replay);
        page.waitForFunction("() => document.querySelector('#graph').dataset.trafficAnimated === 'false'");
        assertThat(page.evaluate("() => window.__renderer._surface.routes.some(route=>window.__renderer._surface.active(route.look))")).isEqualTo(replay);
        page.evaluate("() => void window.__renderer.dispose()");
    }

    @Test
    void browserMotionPreferenceDoesNotHideTraffic() {
        startRendererTraffic(10, 10_000);
        page.emulateMedia(new Page.EmulateMediaOptions().setReducedMotion(com.microsoft.playwright.options.ReducedMotion.REDUCE));
        assertThat(page.locator("#graph").getAttribute("data-traffic-animated")).isEqualTo("true");
        page.emulateMedia(new Page.EmulateMediaOptions().setReducedMotion(com.microsoft.playwright.options.ReducedMotion.NO_PREFERENCE));
        page.waitForFunction("() => document.querySelector('#graph').dataset.trafficAnimated === 'true'");
        page.evaluate("() => void window.__renderer.dispose()");
    }

    @Test
    void disposingTrafficReleasesItsDrawingSurfaces() {
        startRendererTraffic(10, 10_000);
        page.evaluate("() => void window.__renderer.dispose()");
        assertThat(page.locator("#world-plane *").count()).isZero();
        assertThat(page.locator("#graph").getAttribute("data-traffic-animated")).isEqualTo("false");
    }

    @Test
    void disposedRendererDoesNotRetainItsDetachedScene() {
        startRendererTraffic(10, 10_000, true);
        page.evaluate("""
                () => {
                  window.__releasedParcel = new WeakRef(window.__renderer._surface.routes[0]);
                  window.__releasedMachine = new WeakRef(window.__renderer._surface.stations[0]);
                  window.__renderer.dispose();
                }
                """);
        page.evaluate("() => new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))");
        final CDPSession cdp = context.newCDPSession(page);
        try {
            cdp.send("HeapProfiler.collectGarbage");
            assertThat(page.evaluate("() => !window.__releasedParcel.deref() && !window.__releasedMachine.deref()"))
                    .as("Keeping the disposed public renderer alive must not keep its old scene alive").isEqualTo(true);
        } finally {
            cdp.detach();
        }
    }

    @Test
    void disposingAndRecreatingTheRendererRebuildsNativeTraffic() {
        startRendererTraffic(10, 30_000);
        page.evaluate("""
                async () => {
                  const stage = document.querySelector('#graph');
                  window.__renderer.dispose();
                  window.__renderer = new RailixWorld(stage, {
                    linkAppearance: () => ({rate:10, selected:true}), motionActive: () => true
                  });
                  await window.__renderer.refresh();
                }
                """);
        page.waitForFunction("() => document.querySelector('#graph').dataset.trafficAnimated === 'true'");
        assertThat(page.evaluate("() => window.__renderer._surface.runs.some(run=>run.look.rate>0)")).isEqualTo(true);
        assertThat(page.evaluate("() => typeof window.PIXI")).isEqualTo("undefined");
        assertThat(pageErrors).isEmpty();
        page.evaluate("() => void window.__renderer.dispose()");
    }

    @Test
    void visibleDrawingSurfacesStayBoundedAndReleaseOnDisposal() {
        openProject(deepBranchProject(24));
        awaitScene();
        assertThat(((Number)page.evaluate("() => state.world._surface.stations.length")).intValue()).isBetween(1, 256);
        assertThat(page.locator("#world-plane canvas").count()).isEqualTo(1);
        assertThat(page.locator("#world-plane .machine, #world-plane .belt-run").count()).isZero();
        page.evaluate("() => void state.world.dispose()");
        assertThat(page.locator("#world-plane *").count()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    private void startRendererTraffic(final double rate, final int lifetime) {
        startRendererTraffic(rate, lifetime, false);
    }

    private void startRendererTraffic(final double rate, final int lifetime, final boolean replay) {
        openProject(deepBranchProject(4));
        // Exercise the renderer's public rate input, not fabricated application execution counters.
        page.evaluate("""
                async settings => {
                  state.world.dispose();
                  const until = performance.now() + settings[1];
                  window.__renderer = new RailixWorld(document.querySelector('#graph'), {
                    linkAppearance: () => ({rate: settings[0], selected: settings[2]}),
                    motionActive: () => performance.now() < until
                  });
                  await window.__renderer.refresh();
                }
                """, List.of(rate, lifetime, replay));
        page.waitForFunction("() => document.querySelector('#graph').dataset.trafficAnimated === 'true' || document.querySelector('#graph').dataset.replaying === 'true'");
        // Initial fitting publishes a second viewport. Inspect the settled drawing state.
        page.evaluate("""
                async () => {
                  await window.__renderer.refresh();
                  await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                }
                """);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.7, 1.0, 1.4, 3.0})
    void renderedBranchConnectionsKeepOrthogonalRunsAtEveryZoom(final double zoom) {
        openProject(deepBranchProject(24));
        page.evaluate("scale => { state.world.zoom(scale); }", zoom);
        awaitScene();

        assertThat(page.evaluate("() => state.world._surface.runs.length>0 && state.world._surface.runs.every(run=>Math.abs(Math.sin(2*run.angle))<.001 && run.length>0)"))
                .isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void appBodyFirstClickSelectsWithoutFocusing() {
        final BoundingBox label = fittedAppBodyWithTriggerSelected();
        final String camera = (String) page.evaluate("() => state.world.query");

        page.mouse().click(label.x + label.width / 2, label.y + label.height / 2);

        awaitAppSelection();
        awaitScene();
        assertThat(page.evaluate("() => state.world.query")).isEqualTo(camera);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void appBodyClickWithTwoPixelJitterDoesNotMoveTheCamera() {
        final BoundingBox label = fittedAppBodyWithTriggerSelected();
        final String camera = (String) page.evaluate("() => state.world.query");

        page.mouse().move(label.x + label.width / 2, label.y + label.height / 2);
        page.mouse().down();
        page.mouse().move(label.x + label.width / 2 + 2, label.y + label.height / 2);
        page.mouse().up();

        awaitAppSelection();
        awaitScene();
        assertThat(page.evaluate("() => state.world.query"))
                .as("A two-pixel click gesture must not move the fitted camera")
                .isEqualTo(camera);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void appBodyDragOutsideCanvasEndsWithoutSelecting() {
        final BoundingBox label = fittedAppBodyWithTriggerSelected();
        final BoundingBox graph = page.locator("#graph").boundingBox();
        final String camera = (String) page.evaluate("() => state.world.query");

        page.mouse().move(label.x + label.width / 2, label.y + label.height / 2);
        page.mouse().down();
        page.mouse().move(label.x + label.width / 2, graph.y - 12);
        page.mouse().up();

        awaitScene(false);
        final String panned = (String) page.evaluate("() => state.world.query");
        assertThat(panned).isNotEqualTo(camera);
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isEqualTo("command");
        assertThat(page.locator("#graph").getAttribute("class")).doesNotContain("panning");
        page.mouse().move(graph.x + 8, graph.y + 8);
        awaitScene(false);
        assertThat(page.evaluate("() => state.world.query")).isEqualTo(panned);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void appLabelClickPreservesNativeSelection() {
        fittedAppBodyWithTriggerSelected();
        final BoundingBox label = page.locator("[data-node-id='app']").boundingBox();
        final String camera = (String) page.evaluate("() => state.world.query");

        page.mouse().click(label.x + label.width / 2, label.y + label.height / 2);

        awaitAppSelection();
        awaitScene();
        assertThat(page.evaluate("() => state.world.query")).isEqualTo(camera);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void collapsedGroupMatchesRegularStepFootprint() {
        openProject(fourStepProject());
        assertThat(page.evaluate("""
                async () => (await fetch('/api/creator', {
                  method: 'POST', headers: mutationHeaders(), body: JSON.stringify({
                    format: 2, groups: [{id: 'pair', name: 'Pair'}],
                    steps: {one: {group: 'pair'}, two: {group: 'pair'}}
                  })
                })).status
                """)).isEqualTo(200);
        page.reload();
        waitForText("#build-state", "Built");
        awaitScene();
        assertThat(page.evaluate("""
                () => state.world.scene.nodes.some(node => node.kind === 'region'
                  && node.group === 'pair' && !node.expanded)
                """)).isEqualTo(true);

        assertMatchingFootprints(page.locator("[data-region-group='pair']"),
                page.locator("[data-node-id='three']"));
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void collapsedAutomaticRunUsesItsCompactTransportEnvelope() {
        openProject(deepBranchProject(96));
        awaitScene();
        assertThat(page.evaluate("""
                () => state.world.scene.nodes.some(node => node.kind === 'region'
                  && !node.group && !node.expanded && node.count > 8)
                """)).isEqualTo(true);

        assertThat(page.evaluate("""
                () => state.world.scene.nodes.filter(node=>node.summary==='run' && !node.expanded).every(node=>{
                  const box=state.world._planeBounds(node);
                  return Math.abs(box.width/box.height-2.4)<.001;
                })
                """)).isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void automaticRegionKeepsItsBoundaryWhenEntered() {
        openProject(deepBranchProject(128));
        awaitScene();
        final String region = (String) page.evaluate("""
                () => state.world.scene.nodes.find(node => node.kind === 'region' && node.count === 128).id
                """);
        page.evaluate("id => void state.world.focus(id)", region);
        page.waitForFunction("id => state.world.scene.nodes.some(node => node.id === id && node.expanded)", region);
        assertThat(page.evaluate("id=>state.world.scene.nodes.some(node=>node.id===id && node.expanded)",region)).isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void enteringAGroupRevealsItsChildrenWithABoundedSpatialTransition() {
        openNamedGroupProject();
        awaitScene();
        page.evaluate("""
                () => {
                  window.revealFrames = [];
                  window.watchReveal = true;
                  const record = () => {
                    for (const item of state.world._surface.stations)
                      if(item.reveal) window.revealFrames.push({dx:item.reveal.dx,dy:item.reveal.dy,start:item.reveal.start});
                    if (window.watchReveal) requestAnimationFrame(record);
                  };
                  requestAnimationFrame(record);
                  state.world.enter(state.world.scene.nodes.find(node => node.group === 'pair').id);
                }
                """);
        try {
            page.waitForFunction("() => window.revealFrames.length > 0");
            assertThat(page.evaluate("""
                    () => window.revealFrames.every(reveal => Number.isFinite(reveal.dx) && Number.isFinite(reveal.dy))
                    """)).isEqualTo(true);
        } finally {
            page.evaluate("() => { window.watchReveal = false; }");
        }
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void zoomingIntoAnAutomaticRegionDoesNotRevealTinyMachines() {
        openProject(deepBranchProject(128));
        awaitScene();
        page.evaluate("""
                () => void state.world.focus(state.world.scene.nodes.find(node => node.kind === 'region' && node.count === 128).id)
                """);
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving === 'false'");
        awaitScene();
        for (int index = 0; index < 6; index++) {
            final var graph=page.locator("#graph").boundingBox();
            final List<?> point=(List<?>)page.evaluate("""
                    () => {
                      const world=state.world;
                      for(const node of world.scene.nodes) {
                        if(node.expanded || node.kind==='end')continue;
                        const box=world._screenBounds(node),x=box.x+box.width/2,y=box.y+box.height/2;
                        if(x>=24 && x<=world._size.width-24 && y>=24 && y<=world._size.height-24)return [x,y];
                      }
                      return null;
                    }
                    """);
            assertThat(point).as("A visible machine must remain available as the zoom target").isNotNull();
            page.mouse().move(graph.x+((Number)point.get(0)).doubleValue(),graph.y+((Number)point.get(1)).doubleValue());
            page.mouse().wheel(0,-180);
            awaitScene();
            assertThat(((Number) page.evaluate("""
                    () => Math.min(...state.world.scene.nodes.filter(node=>!node.expanded && node.kind!=='end').map(node=>state.world._screenBounds(node).width))
                    """)).doubleValue()).as("Newly revealed machines must remain readable at zoom tick " + index)
                    .isGreaterThanOrEqualTo(64);
        }
        assertThat(pageErrors).isEmpty();
    }

    private BoundingBox fittedAppBodyWithTriggerSelected() {
        openProject(deepBranchProject(4));
        awaitScene();
        page.locator("[data-node-id='command']").click();
        page.waitForFunction("""
                () => !state.editorController && document.querySelector('#inspector').dataset.selection === 'command'
                """);
        awaitScene();
        final BoundingBox body = stationBounds("app");
        assertThat(body).isNotNull();
        // The module's label occupies its right side. Probe the actual canvas body on the left.
        body.width *= .25;
        assertThat(page.evaluate("""
                point => {
                  const hit=document.elementFromPoint(point[0], point[1]);
                  return hit?.id === 'graph' || Boolean(hit?.closest('#world-plane'));
                }
                """, List.of(body.x + body.width / 2, body.y + body.height / 2)))
                .as("The App body click must land on the graph, outside the label button")
                .isEqualTo(true);
        return body;
    }

    private void awaitAppSelection() {
        page.waitForFunction("""
                () => !state.editorController && document.querySelector('#inspector').dataset.selection === 'app'
                  && document.querySelector('[data-node-id="app"]')?.getAttribute('aria-pressed') === 'true'
                """);
    }

    private void assertMatchingFootprints(final Locator region, final Locator step) {
        final BoundingBox regionBox = stationBounds(region.getAttribute("data-world-id"));
        final BoundingBox stepBox = stationBounds(step.getAttribute("data-world-id"));
        assertThat(regionBox).as("Visible collapsed region body content").isNotNull();
        assertThat(stepBox).as("Visible ordinary Step body content").isNotNull();
        assertThat(stepBox.width).isGreaterThan(20);
        assertThat(stepBox.height).isGreaterThan(18.0);
        assertThat(regionBox.width).as("Collapsed region width at the same camera scale")
                .isCloseTo(stepBox.width, within(0.1));
        assertThat(regionBox.height).as("Collapsed region height at the same camera scale")
                .isCloseTo(stepBox.height, within(0.1));
    }

    @Test
    @Tag("responsive")
    @Timeout(600)
    void supportedWorldScaleKeepsRenderingBoundedThroughRepeatedNavigation() throws Exception {
        final int rounds = Integer.getInteger("railix.world.navigation.rounds", 3);
        assertThat(rounds).as("Navigation measurement rounds").isPositive();
        final boolean extendedRetention = rounds > 30;
        final Path retentionOutput = Path.of("target", "world-retention-" + page.viewportSize().width + ".jsonl");
        final String source = deepBranchProject(6_000);
        final RailixValue.ObjectValue project = (RailixValue.ObjectValue) (
                (RailixJson.Parsed) RailixJson.parse(source)
        ).value();
        assertThat(((RailixValue.ArrayValue) project.values().get("nodes")).values()).hasSize(6_003);
        openProject(source);
        final String persisted = Files.readString(directory.resolve("project.json"));
        final String metadata = creatorMetadata();
        final List<String> stations = List.of("step-0", "step-2999", "step-5999");
        final List<String> directions = List.of("ArrowRight", "ArrowDown", "ArrowLeft", "ArrowUp");
        final List<Double> requests = new ArrayList<>();
        final List<Long> heaps = new ArrayList<>();
        final List<Integer> checkpoints = new ArrayList<>();
        final CDPSession cdp = context.newCDPSession(page);
        try {
            cdp.send("Performance.enable");
            if (extendedRetention) {
                cdp.send("HeapProfiler.enable");
                Files.writeString(retentionOutput, "");
            }
            page.waitForFunction("() => Boolean(state.world?.scene)");
            installMeasurements();
            for (final String station : stations) {
                focusStation(station);
                for (final String direction : directions) {
                    navigate(direction);
                    collectMeasurements();
                }
            }
            fitWorld();
            collectMeasurements();
            final JsonObject baseline = retainedHeap(cdp);
            heaps.add(baseline.get("usedSize").getAsLong());
            checkpoints.add(0);
            if (extendedRetention) recordRetention(cdp, baseline, 0, retentionOutput, false);

            for (int round = 0; round < rounds; round++) {
                focusStation(stations.get(round % stations.size()));
                for (int index = 0; index < 20; index++) {
                    requests.add(navigate(directions.get(index % directions.size())));
                    collectMeasurements();
                }
                fitWorld();
                collectMeasurements();
                final JsonObject heap = retainedHeap(cdp);
                heaps.add(heap.get("usedSize").getAsLong());
                checkpoints.add(requests.size());
                if (extendedRetention) {
                    recordRetention(cdp, heap, requests.size(), retentionOutput,
                            requests.size() == 600 || round + 1 == rounds);
                }
                System.out.printf("RAILIX_WORLD_HEAP viewport=%d navigation_requests=%d post_gc_used_js_heap_bytes=%d%n",
                        page.viewportSize().width, requests.size(), heaps.getLast());
            }

            collectMeasurements();
            assertThat(requests).hasSize(rounds * 20);
            assertThat(pageErrors).isEmpty();
            assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(persisted);
            assertThat(creatorMetadata()).isEqualTo(metadata);

            final int width = page.viewportSize().width;
            final Path screenshots = Files.createDirectories(Path.of("target", "screenshots"));
            final Path overview = screenshots.resolve("world-6003-" + width + "-overview.png");
            final Path expanded = screenshots.resolve("world-6003-" + width + "-region.png");
            final Path detail = screenshots.resolve("world-6003-" + width + "-detail.png");
            final Object overviewScene = sceneSnapshot();
            page.screenshot(new Page.ScreenshotOptions().setPath(overview));
            final var region = (Map<?, ?>) page.evaluate("""
                    () => {
                      const graph = document.querySelector('#graph');
                      return state.world.scene.nodes.filter(node => node.kind === 'region' && !node.expanded)
                        .map(node => {
                          const box = state.world._screenBounds(node);
                          return {...node, screen_x: box.x + box.width / 2, screen_y: box.y + box.height / 2};
                        }).find(node => node.screen_x > 0 && node.screen_x < graph.clientWidth
                          && node.screen_y > 0 && node.screen_y < graph.clientHeight);
                    }
                    """);
            assertThat(region).as("Rendered semantic-zoom aggregate").isNotNull();
            final String aggregateId = String.valueOf(region.get("id"));
            finishScene(page.waitForResponse(CreatorWorldBrowserIT::viewportResponse,
                    () -> page.locator("#graph").dblclick(new Locator.DblclickOptions().setPosition(
                            ((Number) region.get("screen_x")).doubleValue(),
                            ((Number) region.get("screen_y")).doubleValue()))));
            page.waitForFunction("""
                    region => !state.world.scene.nodes.some(node => node.id === region.id && !node.expanded)
                      && state.world.scene.nodes.some(node => node.id !== region.id && node.count < region.count
                        && node.x >= region.x && node.y >= region.y
                        && node.x + node.width <= region.x + region.width
                        && node.y + node.height <= region.y + region.height)
                    """, region);
            final Object expandedScene = sceneSnapshot();
            assertThat(page.evaluate("""
                    () => {
                      const boxes = state.world.scene.nodes.filter(node=>!node.expanded).map(node=>({id:node.id,
                        x:node.x,y:node.y,w:node.width,h:node.height}));
                      return boxes.flatMap((a,index) => boxes.slice(index+1)
                        .filter(b => Math.min(a.x+a.w,b.x+b.w)-Math.max(a.x,b.x) > .01
                          && Math.min(a.y+a.h,b.y+b.h)-Math.max(a.y,b.y) > .01)
                        .map(b => a.id + ' overlaps ' + b.id));
                    }
                    """)).isEqualTo(List.of());
            page.screenshot(new Page.ScreenshotOptions().setPath(expanded));
            focusStation("step-2999");
            page.locator("[data-node-id='step-2999']").waitFor();
            final Object detailScene = sceneSnapshot();
            page.screenshot(new Page.ScreenshotOptions().setPath(detail));
            final Map<String, Object> counts = collectMeasurements();
            assertThat(pageErrors).isEmpty();
            assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(persisted);
            assertThat(creatorMetadata()).isEqualTo(metadata);
            final List<Long> tail = heaps.subList(Math.max(0, heaps.size() - 10), heaps.size());
            final JsonObject browserPerformance = cdp.send("Performance.getMetrics");
            assertThat(browserPerformance.getAsJsonArray("metrics")).isNotEmpty();

            final String report = String.format(Locale.ROOT, """
                    RAILIX_WORLD_MEASUREMENTS advisory=true
                    captured_at_epoch_ms=%d
                    project_nodes=6003 viewport=%dx%d browser=%s os=%s java=%s
                    warmup_navigation_requests=12 navigation_rounds=%d measured_navigation_requests=%d
                    viewport_request_p95_ms=%.3f
                    max_scene_glyphs=%s max_route_segments=%s max_labels=%s max_world_and_label_dom_elements=%s
                    post_gc_navigation_counts=%s
                    post_gc_used_js_heap_bytes=%s
                    browser_performance_metrics=%s
                    retained_js_heap_delta_bytes=%d
                    last_%d_gc_samples_range_bytes=%d last_%d_gc_samples_delta_bytes=%d
                    semantic_zoom_aggregate=%s
                    overview_scene=%s
                    expanded_region_scene=%s
                    detail_scene=%s
                    overview_screenshot=%s
                    expanded_region_screenshot=%s
                    detail_screenshot=%s
                    Request timing is browser request start through response completion, excluding input debounce.
                    Heap is CDP Runtime.getHeapUsage usedSize after forced GC, at the same fitted camera after each round.
                    The heap series is observational evidence, not proof of leak freedom or a retained-memory threshold.
                    Percentiles use nearest rank; timings and heap growth have no pass/fail thresholds.
                    """, System.currentTimeMillis(), width, page.viewportSize().height,
                    context.browser().version(), System.getProperty("os.name"), System.getProperty("java.version"),
                    rounds, requests.size(), percentile95(requests),
                    counts.get("glyphs"), counts.get("segments"), counts.get("labels"), counts.get("dom"),
                    checkpoints, heaps, browserPerformance, heaps.getLast() - heaps.getFirst(),
                    tail.size(), tail.stream().mapToLong(Long::longValue).max().orElseThrow()
                            - tail.stream().mapToLong(Long::longValue).min().orElseThrow(),
                    tail.size(), tail.getLast() - tail.getFirst(), aggregateId,
                    overviewScene, expandedScene, detailScene,
                    overview.toAbsolutePath(), expanded.toAbsolutePath(), detail.toAbsolutePath())
                    + (extendedRetention ? "retention_checkpoints=" + retentionOutput.toAbsolutePath()
                    + "\nHeap snapshots are local-only ignored artifacts at navigation 600 and the final checkpoint."
                    + "\nCheckpoint counters precede snapshot capture; snapshots may perturb subsequent measurements.\n" : "");
            final Path output = Path.of("target", "world-measurements-" + width + ".txt");
            Files.writeString(output, report);
            assertThat(Files.readString(output)).isEqualTo(report);
            System.out.print(report);
        } finally {
            try {
                page.evaluate("() => delete window.__railixWorldMeasurements");
            } finally {
                try {
                    cdp.send("Performance.disable");
                    if (extendedRetention) cdp.send("HeapProfiler.disable");
                } finally {
                    cdp.detach();
                }
            }
        }
    }

    private void installMeasurements() {
        page.evaluate("""
                () => {
                  window.__railixWorldMeasurements = {glyphs: 0, segments: 0, labels: 0, dom: 0};
                }
                """);
    }

    private double navigate(final String direction) {
        final Response response = page.waitForResponse(CreatorWorldBrowserIT::viewportResponse,
                () -> page.locator("#graph").press(direction));
        finishScene(response);
        final double elapsed = response.request().timing().responseEnd;
        assertThat(elapsed).as("Completed viewport request timing").isFinite().isGreaterThanOrEqualTo(0);
        return elapsed;
    }

    private void focusStation(final String id) {
        final Response response = page.waitForResponse(candidate -> candidate.url().contains("focus=" + id),
                () -> page.evaluate("id => void state.world.focus(id)", id));
        finishScene(response);
        page.waitForFunction("""
                id => document.querySelector('#graph').dataset.cameraMoving === 'false'
                  && !new URLSearchParams(state.world.query).has('focus')
                  && state.world.scene.nodes.some(node => node.id === id)
                """, id);
    }

    private void fitWorld() {
        final Response response = page.waitForResponse(CreatorWorldBrowserIT::viewportResponse,
                () -> page.evaluate("() => void state.world.fit()"));
        finishScene(response);
    }

    private static boolean viewportResponse(final Response response) {
        // Minimum-scale topology reads update the radar, not the camera viewport.
        return response.url().contains("/api/scene?") && !response.url().contains("focus=")
                && java.util.Arrays.stream(java.net.URI.create(response.url()).getRawQuery().split("&"))
                .filter(value -> value.startsWith("scale="))
                .mapToDouble(value -> Double.parseDouble(java.net.URLDecoder.decode(value.substring(6),
                        java.nio.charset.StandardCharsets.UTF_8)))
                .anyMatch(scale -> scale > Math.scalb(1d,-32));
    }

    private void finishScene(final Response response) {
        assertThat(response.status()).isEqualTo(200);
        response.finished();
        page.evaluate("""
                async () => {
                  await new Promise(requestAnimationFrame);
                  await new Promise(requestAnimationFrame);
                }
                """);
        assertThat(page.locator("#world-error[data-severity=error]").isVisible())
                .as("Scene navigation error: %s", page.locator("#world-error").textContent()).isFalse();
        assertThat(page.locator("#world-plane").count()).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> collectMeasurements() {
        final Map<String, Object> measurements = (Map<String, Object>) page.evaluate("""
                () => {
                  const probe = window.__railixWorldMeasurements;
                  const scene = state.world.scene;
                  const labels = document.querySelector('#world-labels');
                  const plane = document.querySelector('#world-plane');
                  const stations=state.world._surface.stations.filter(item=>!item.node.preview).map(item=>item.node.id);
                  const visible=new Set(scene.nodes.map(node=>node.id));
                  probe.glyphs = Math.max(probe.glyphs, scene.nodes.length);
                  probe.segments = Math.max(probe.segments,
                    scene.links.reduce((sum, link) => sum + link.points.length - 1, 0));
                  probe.labels = Math.max(probe.labels, labels.childElementCount);
                  probe.dom = Math.max(probe.dom, plane.querySelectorAll('*').length + labels.querySelectorAll('*').length);
                  return {glyphs: probe.glyphs, segments: probe.segments, labels: probe.labels, dom: probe.dom,
                    visibleStations:stations.every(id=>visible.has(id)) && new Set(stations).size===stations.length};
                }
                """);
        assertThat(((Number) measurements.get("glyphs")).intValue()).isBetween(1, 2_048);
        assertThat(((Number) measurements.get("segments")).intValue()).isBetween(1, 4_096);
        assertThat(((Number) measurements.get("labels")).intValue()).isBetween(1, 256);
        assertThat(((Number) measurements.get("dom")).intValue()).isPositive();
        assertThat(measurements.get("visibleStations")).as("No duplicate or off-scene machine objects survive navigation").isEqualTo(true);
        return measurements;
    }

    private JsonObject retainedHeap(final CDPSession cdp) {
        page.evaluate("() => performance.clearResourceTimings()");
        cdp.send("HeapProfiler.collectGarbage");
        return cdp.send("Runtime.getHeapUsage");
    }

    private void recordRetention(
            final CDPSession cdp,
            final JsonObject heap,
            final int navigations,
            final Path output,
            final boolean captureSnapshot
    ) throws IOException {
        final JsonObject evidence = new JsonObject();
        evidence.addProperty("navigation_requests", navigations);
        evidence.add("runtime_heap", heap);
        evidence.add("dom", cdp.send("Memory.getDOMCounters"));
        evidence.add("retained", JsonParser.parseString((String) page.evaluate("""
                () => JSON.stringify({
                  world: {
                    scene_nodes: state.world.scene.nodes.length,
                    scene_links: state.world.scene.links.length,
                    scene_node_map: state.world._sceneNodes.size,
                    label_map: state.world._labelElements.size,
                    label_nodes: document.querySelector('#world-labels').childElementCount,
                    label_dom_elements: document.querySelector('#world-labels').querySelectorAll('*').length,
                    visible_machines: state.world._surface.stations.length,
                    visible_belts: state.world._surface.runs.length,
                    visible_junctions: document.querySelectorAll('#world-plane .belt-junction').length
                  },
                  editor: {
                    project_nodes: state.project.nodes.length,
                    project_links: state.project.links.length,
                    baseline_nodes: state.builtProject.nodes.length,
                    baseline_links: state.builtProject.links.length,
                    node_index: Object.keys(state.editor.nodes).length,
                    full_nodes: state.editor.full.length,
                    groups: state.creator.groups.length,
                    styles: Object.keys(state.creator.steps).length,
                    saved_groups: state.savedCreator.groups.length,
                    saved_styles: Object.keys(state.savedCreator.steps).length,
                    world_nodes: state.worldNodes.size,
                    world_groups: state.worldGroups.size,
                    world_changes: state.worldChanges.size,
                    world_issues: state.worldIssues.size,
                    world_covered: state.worldCovered.size,
                    world_selected: state.worldSelected.size,
                    observation_nodes: state.observations?.nodes.size ?? 0,
                    observation_links: state.observations?.links.size ?? 0,
                    icon_urls: state.iconUrls.size,
                    trace_cases: state.traceCases.length,
                    preview_cases: state.previewCases.length,
                    json_drafts: Number(Boolean(state.jsonDraft)),
                    pending_writes: Number(Boolean(state.pendingWrite))
                  }
                })
                """)));
        if (captureSnapshot) {
            final Path snapshot = heapSnapshot(cdp, navigations);
            evidence.addProperty("heap_snapshot", snapshot.toAbsolutePath().toString());
            evidence.addProperty("heap_snapshot_bytes", Files.size(snapshot));
            System.out.printf("RAILIX_WORLD_HEAP_SNAPSHOT navigation_requests=%d path=%s bytes=%d%n",
                    navigations, snapshot.toAbsolutePath(), Files.size(snapshot));
        }
        Files.writeString(output, evidence + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private Path heapSnapshot(final CDPSession cdp, final int navigations) throws IOException {
        final Path output = Files.createTempFile(Files.createDirectories(Path.of("target", "heap-snapshots")),
                "world-6003-" + page.viewportSize().width + "-navigation-" + navigations + "-", ".heapsnapshot");
        try (final var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            final Consumer<JsonObject> chunks = event -> {
                try {
                    writer.write(event.get("chunk").getAsString());
                } catch (final IOException failure) {
                    throw new UncheckedIOException("Could not stream heap snapshot to " + output, failure);
                }
            };
            cdp.on("HeapProfiler.addHeapSnapshotChunk", chunks);
            try {
                cdp.send("HeapProfiler.takeHeapSnapshot");
            } finally {
                cdp.off("HeapProfiler.addHeapSnapshotChunk", chunks);
            }
        }
        assertThat(Files.size(output)).as("Completed heap snapshot bytes").isPositive();
        return output;
    }

    private Object sceneSnapshot() {
        return page.evaluate("""
                () => ({
                  glyphs: state.world.scene.nodes.length,
                  route_segments: state.world.scene.links.reduce((sum, link) => sum + link.points.length - 1, 0),
                  labels: document.querySelector('#world-labels').childElementCount,
                  label_dom_elements: document.querySelector('#world-labels').querySelectorAll('*').length,
                  collapsed_regions: state.world.scene.nodes.filter(node => node.kind === 'region' && !node.expanded).length,
                  real_steps: state.world.scene.nodes.filter(node => node.kind === 'step').length,
                  scale: Number(document.querySelector('#graph').dataset.sceneScale)
                })
                """);
    }

    private void openNamedGroupProject() {
        openProject(fourStepProject());
        assertThat(page.evaluate("""
                async () => (await fetch('/api/creator', {
                  method: 'POST', headers: mutationHeaders(), body: JSON.stringify({
                    format: 2, groups: [{id: 'pair', name: 'Pair'}],
                    steps: {one: {group: 'pair'}, two: {group: 'pair'}}
                  })
                })).status
                """)).isEqualTo(200);
        page.reload();
        waitForText("#build-state", "Built");
        awaitScene();
        page.locator("[data-region-group='pair']").waitFor();
    }

    private static StressScene branchingStressScene() {
        final StringBuilder nodes = new StringBuilder("""
                {"format":1,"id":"branching-stress","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"sample","payload":[],"context":{"payload":{}}}]},
                  {"id":"gate","use":"railix.choice","inputs":{}},
                  {"id":"left","use":"railix.choice","inputs":{}},
                  {"id":"right","use":"railix.choice","inputs":{}},
                  {"id":"merge-left","use":"railix.field-manipulation","inputs":{}},
                  {"id":"merge-right","use":"railix.field-manipulation","inputs":{}}
                """);
        final StringBuilder links = new StringBuilder("""
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"gate"},
                  {"from":"gate.match","to":"left"},
                  {"from":"gate.otherwise","to":"right"}
                """);
        final StringBuilder groups = new StringBuilder();
        final StringBuilder names = new StringBuilder();
        final String[] sectionNames = {"Intake", "Rules", "Delivery"};
        final String[] stepNames = {"Read", "Normalize", "Check", "Prepare", "Write"};
        for (int lane = 0; lane < 4; lane++) {
            final String parent = lane < 2 ? "left" : "right";
            links.append(",{\"from\":\"").append(parent).append(lane % 2 == 0 ? ".match" : ".otherwise")
                    .append("\",\"to\":\"lane-").append(lane).append("-0\"}");
            for (int section = 0; section < 3; section++) {
                final String id = "group-lane-" + lane + "-" + section;
                groups.append(",{\"id\":\"").append(id).append("\",\"name\":\"Path ")
                        .append(lane + 1).append(' ').append(sectionNames[section])
                        .append("\"}");
            }
            final String choice = "audit-" + lane;
            nodes.append(",{\"id\":\"").append(choice).append("\",\"use\":\"railix.choice\",\"inputs\":{}}");
            names.append(",\"").append(choice).append("\":{\"name\":\"Audit path ")
                    .append(lane + 1).append("\"}");
            for (int index = 0; index < 30; index++) {
                final String id = "lane-" + lane + "-" + index;
                nodes.append(",{\"id\":\"").append(id)
                        .append("\",\"use\":\"railix.field-manipulation\",\"inputs\":{}}");
                names.append(",\"").append(id).append("\":{\"name\":\"")
                        .append(stepNames[index % 5])
                        .append(' ').append(index + 1).append("\",\"group\":\"group-lane-")
                        .append(lane).append('-').append(index / 10).append("\"}");
                links.append(",{\"from\":\"").append(id).append(".next\",\"to\":\"")
                        .append(index == 14 ? choice : index == 29 ? "merge-" + (lane < 2 ? "left" : "right")
                                : "lane-" + lane + "-" + (index + 1))
                        .append("\"}");
            }
            links.append(",{\"from\":\"").append(choice).append(".otherwise\",\"to\":\"lane-")
                    .append(lane).append("-15\"}");
            links.append(",{\"from\":\"").append(choice).append(".match\",\"to\":\"audit-")
                    .append(lane).append("-step-0\"}");
            for (int index = 0; index < 6; index++) {
                final String id = choice + "-step-" + index;
                nodes.append(",{\"id\":\"").append(id)
                        .append("\",\"use\":\"railix.field-manipulation\",\"inputs\":{}}");
                names.append(",\"").append(id).append("\":{\"name\":\"Audit ")
                        .append(index + 1).append("\",\"group\":\"audits\"}");
                links.append(",{\"from\":\"").append(id).append(".next\",\"to\":\"")
                        .append(index == 5 ? "end" : choice + "-step-" + (index + 1)).append("\"}");
            }
        }
        links.append(",{\"from\":\"merge-left.next\",\"to\":\"end\"}")
                .append(",{\"from\":\"merge-right.next\",\"to\":\"end\"}");
        final String creator = "{\"format\":2,\"groups\":[{\"id\":\"audits\",\"name\":\"Shared audits\"}"
                + groups + "],\"steps\":{\"gate\":{\"name\":\"Region gate\"},"
                + "\"left\":{\"name\":\"Standard routes\"},\"right\":{\"name\":\"Special routes\"}"
                + names + "}}";
        return new StressScene(nodes.append(links).append("]}").toString(), creator);
    }

    record StressScene(String project, String creator) {
    }

    static StressScene systemStressScene() {
        final StressScene base = evidenceStressScene();
        final JsonObject project = JsonParser.parseString("{\"format\":1,\"id\":\"commerce-system\",\"nodes\":[{\"id\":\"app\",\"use\":\"railix.app\",\"inputs\":{}}],\"links\":[]}").getAsJsonObject();
        final JsonObject metadata = JsonParser.parseString("{\"format\":2,\"groups\":[],\"steps\":{},\"reuse\":[]}").getAsJsonObject();
        final var examples = new com.google.gson.JsonArray();
        final var httpExamples = new com.google.gson.JsonArray();
        final var cases = new com.google.gson.JsonArray();
        for (final String area : List.of("orders", "payments", "stock", "shipping", "returns", "billing", "accounts", "support")) {
            final JsonObject part = JsonParser.parseString(base.project()).getAsJsonObject();
            final JsonObject style = JsonParser.parseString(base.creator()).getAsJsonObject();
            for (final var value : part.getAsJsonArray("nodes")) {
                final var node = value.getAsJsonObject();
                if (node.get("id").getAsString().equals("app")) continue;
                if (node.get("id").getAsString().equals("command")) {
                    for (final var exampleValue : node.getAsJsonArray("examples")) {
                        final var example = exampleValue.getAsJsonObject();
                        example.addProperty("name", area + "-" + example.get("name").getAsString());
                        example.getAsJsonObject("context").getAsJsonObject("job").addProperty("area", area);
                        examples.add(example);
                        if (area.equals("stock")) {
                            final var http = example.deepCopy();
                            final var payload = new JsonObject();
                            payload.addProperty("method", "POST");payload.addProperty("path", "/");
                            payload.add("body", example.getAsJsonObject("context").get("job").deepCopy());
                            http.add("payload", payload);
                            httpExamples.add(http);
                        }
                    }
                    node.remove("examples");
                    node.addProperty("use", "railix.field-manipulation");
                }
                if (node.get("id").getAsString().startsWith("merge-"))
                    node.getAsJsonObject("inputs").add("field", JsonParser.parseString("[\"context\",\"completion\"]"));
                node.addProperty("id", area + "-" + node.get("id").getAsString());
                project.getAsJsonArray("nodes").add(node);
            }
            for (final var value : part.getAsJsonArray("links")) {
                final var link = value.getAsJsonObject();
                final String from = link.get("from").getAsString();
                if (from.equals("app.start")) continue;
                link.addProperty("from", from.equals("app.start") ? from : area + "-" + from);
                if (!link.get("to").getAsString().equals("end")) link.addProperty("to", area + "-" + link.get("to").getAsString());
                project.getAsJsonArray("links").add(link);
            }
            if (!area.equals("support")) cases.add(JsonParser.parseString("""
                    {"outcome":"%s","option":"field","inputs":{"field":["context","job","area"]},
                     "when":{"transforms":[],"all":[[{"use":"value.equals","inputs":{"expected":"%s"}}]]}}
                    """.formatted(area, area)));
            project.getAsJsonArray("links").add(JsonParser.parseString("{\"from\":\"dispatch." + (area.equals("support") ? "otherwise" : area) + "\",\"to\":\"" + area + "-command\"}"));
            for (final var value : style.getAsJsonArray("groups")) {
                final var group = value.getAsJsonObject();
                group.addProperty("id", area + "-" + group.get("id").getAsString());
                group.addProperty("name", area + ": " + group.get("name").getAsString());
                metadata.getAsJsonArray("groups").add(group);
            }
            style.getAsJsonObject("steps").entrySet().forEach(entry -> {
                final var step = entry.getValue().getAsJsonObject();
                if (step.has("group")) step.addProperty("group", area + "-" + step.get("group").getAsString());
                metadata.getAsJsonObject("steps").add(area + "-" + entry.getKey(), step);
            });
            metadata.getAsJsonObject("steps").add(area + "-command", JsonParser.parseString("{\"name\":\"" + area + "\"}"));
            for (final var value : style.getAsJsonArray("reuse")) {
                final var family = value.getAsJsonObject();
                family.addProperty("id", area + "-" + family.get("id").getAsString());
                for (final var occurrence : family.getAsJsonArray("uses")) {
                    final var use = occurrence.getAsJsonObject();
                    use.addProperty("id", area + "-" + use.get("id").getAsString());
                    use.addProperty("group", area + "-" + use.get("group").getAsString());
                    use.getAsJsonObject("members").entrySet().forEach(member -> member.setValue(new com.google.gson.JsonPrimitive(area + "-" + member.getValue().getAsString())));
                }
                metadata.getAsJsonArray("reuse").add(family);
            }
        }
        final var invalid = httpExamples.get(0).getAsJsonObject().deepCopy();
        invalid.addProperty("name", "invalid-name");
        invalid.getAsJsonObject("payload").getAsJsonObject("body").add("name", com.google.gson.JsonNull.INSTANCE);
        httpExamples.add(invalid);
        project.getAsJsonArray("nodes").add(JsonParser.parseString("{\"id\":\"command\",\"use\":\"railix.trigger.cli\",\"inputs\":{},\"examples\":" + examples + "}"));
        project.getAsJsonArray("nodes").add(JsonParser.parseString("{\"id\":\"api\",\"use\":\"railix.trigger.http\",\"inputs\":{\"port\":0},\"examples\":" + httpExamples + "}"));
        project.getAsJsonArray("nodes").add(JsonParser.parseString("{\"id\":\"request-body\",\"use\":\"railix.field-manipulation\",\"inputs\":{\"field\":[\"context\",\"job\"],\"value\":[{\"option\":\"field\",\"inputs\":{\"source\":[\"context\",\"payload\",\"request\",\"body\"]}}],\"steps\":[]}}"));
        project.getAsJsonArray("nodes").add(JsonParser.parseString("{\"id\":\"dispatch\",\"use\":\"railix.switch\",\"inputs\":{\"cases\":" + cases + "}}"));
        project.getAsJsonArray("nodes").add(JsonParser.parseString("{\"id\":\"default-job\",\"use\":\"railix.field-manipulation\",\"inputs\":{\"field\":[\"context\",\"job\"],\"value\":[{\"option\":\"field\",\"inputs\":{\"source\":[\"context\",\"job\"]}},{\"option\":\"literal\",\"inputs\":{\"literal\":"
                + examples.get(0).getAsJsonObject().getAsJsonObject("context").get("job") + "}}],\"steps\":[]}}"));
        for (final String link : List.of("{\"from\":\"app.start\",\"to\":\"command\"}", "{\"from\":\"app.start\",\"to\":\"api\"}",
                "{\"from\":\"api.next\",\"to\":\"request-body\"}", "{\"from\":\"request-body.next\",\"to\":\"dispatch\"}",
                "{\"from\":\"command.next\",\"to\":\"default-job\"}", "{\"from\":\"default-job.next\",\"to\":\"dispatch\"}")) project.getAsJsonArray("links").add(JsonParser.parseString(link));
        metadata.getAsJsonObject("steps").add("default-job",JsonParser.parseString("{\"name\":\"Default request\"}"));
        metadata.getAsJsonObject("steps").add("request-body",JsonParser.parseString("{\"name\":\"Read request\"}"));
        metadata.getAsJsonObject("steps").add("dispatch",JsonParser.parseString("{\"name\":\"Route by area\"}"));
        return new StressScene(project.toString(), metadata.toString());
    }

    static StressScene evidenceStressScene() {
        final StressScene base = branchingStressScene();
        final JsonObject project = JsonParser.parseString(base.project()).getAsJsonObject();
        final JsonObject creator = JsonParser.parseString(base.creator()).getAsJsonObject();
        final String[] fields = {"name", "nameLength", "city", "postalCode", "country", "currency", "channel", "locale", "reference", "source"};
        for (final var value : project.getAsJsonArray("nodes")) {
            final JsonObject operation = value.getAsJsonObject();
            final String id = operation.get("id").getAsString();
            if (id.equals("command")) {
                operation.add("examples", JsonParser.parseString("""
                        [{"name":"standard-order","payload":[],"context":{"job":{"region":"standard","priority":"normal","audit":false,"name":"  ADA LOVELACE  ","city":"  LONDON ","postalCode":" SW1 ","country":" GB ","currency":" GBP ","channel":" WEB ","locale":" EN ","reference":" R-1 ","source":" SHOP "}}},
                         {"name":"priority-order","payload":[],"context":{"job":{"region":"standard","priority":"urgent","audit":true,"name":"  GRACE HOPPER  ","city":" NEW YORK ","postalCode":" 10001 ","country":" US ","currency":" USD ","channel":" API ","locale":" EN ","reference":" R-2 ","source":" PARTNER "}}},
                         {"name":"special-order","payload":[],"context":{"job":{"region":"special","priority":"normal","audit":false,"name":"  KATHERINE JOHNSON  ","city":" HAMPTON ","postalCode":" 23666 ","country":" US ","currency":" USD ","channel":" CLI ","locale":" EN ","reference":" R-3 ","source":" OFFICE "}}}]
                        """));
            } else if (operation.get("use").getAsString().equals("railix.choice")) {
                final String field = id.equals("gate") ? "region" : id.startsWith("audit-") ? "audit" : "priority";
                final String expected = id.equals("gate") ? "\"standard\"" : id.startsWith("audit-") ? "true" : "\"normal\"";
                operation.add("inputs", JsonParser.parseString("""
                        {"conditions":[[{"option":"field","inputs":{"field":["context","job","%s"]},
                        "when":{"transforms":[],"all":[[{"use":"value.equals","inputs":{"expected":%s}}]]}}]]}
                        """.formatted(field, expected)));
            } else if (id.startsWith("lane-")) {
                final int index = Integer.parseInt(id.substring(id.lastIndexOf('-') + 1));
                final String field = fields[index % 10];
                final String target = index < 10 ? "work" : index < 20 ? "mapped" : "delivery";
                final String source = index < 10 ? "job" : index < 20 ? "work" : "mapped";
                creator.getAsJsonObject("steps").getAsJsonObject(id).addProperty("name", index == 1 ? "Measure name"
                        : (index < 10 ? "Normalize " : index < 20 ? "Map " : "Prepare ") + field);
                operation.add("inputs", JsonParser.parseString(index == 1 ? """
                        {"field":["context","work","nameLength"],"value":[{"option":"field","inputs":{"source":["context","work","name"]}}],
                        "steps":[{"use":"text.length","inputs":{}}]}
                        """ : """
                        {"field":["context","%s","%s"],"value":[{"option":"field","inputs":{"source":["context","%s","%s"]}}],"steps":%s}
                        """.formatted(target, field, source, field, index < 10
                        ? "[{\"use\":\"text.normalize-space\",\"inputs\":{}},{\"use\":\"text.lowercase\",\"inputs\":{}}]" : "[]")));
                if (index == 1) {
                    operation.addProperty("use", "text.length");
                    operation.add("inputs", new JsonObject());
                    operation.add("receives", JsonParser.parseString("{\"value\":[\"context\",\"work\",\"name\"]}"));
                    operation.add("returns", JsonParser.parseString("{\"value\":[\"context\",\"work\",\"nameLength\"]}"));
                }
            } else if (id.startsWith("merge-")) {
                operation.add("inputs", JsonParser.parseString("""
                        {"field":["context","status"],"value":[{"option":"literal","inputs":{"literal":"complete"}}],"steps":[]}
                        """));
            } else if (id.contains("-step-")) {
                operation.add("inputs", JsonParser.parseString("""
                        {"field":["context","audit","stage-%s"],"value":[{"option":"literal","inputs":{"literal":"recorded"}}],"steps":[]}
                        """.formatted(id.substring(id.lastIndexOf('-') + 1))));
            }
        }
        project.getAsJsonArray("nodes").get(1).getAsJsonObject().getAsJsonArray("examples").get(0)
                .getAsJsonObject().getAsJsonObject("context").add("work", JsonParser.parseString("{\"city\":\"Paris\",\"country\":null,\"channel\":\"\"}"));
        for (final var value : project.getAsJsonArray("links")) {
            final var link = value.getAsJsonObject();
            if (link.get("from").getAsString().matches("lane-[0-3]-1\\.next"))
                link.addProperty("from", link.get("from").getAsString().replace(".next", ".ok"));
        }
        final var groups = creator.getAsJsonArray("groups");
        groups.remove(0);
        final var uses = new com.google.gson.JsonArray();
        for (int lane = 0; lane < 4; lane++) {
            final var group = new JsonObject();
            group.addProperty("id", "audit-group-" + lane);
            group.addProperty("name", "Shared audits");
            groups.add(group);
            final var use = new JsonObject();
            use.addProperty("id", "audit-use-" + lane);
            use.addProperty("group", "audit-group-" + lane);
            final var members = new JsonObject();
            for (int index = 0; index < 6; index++) {
                final String id = "audit-" + lane + "-step-" + index;
                creator.getAsJsonObject("steps").getAsJsonObject(id).addProperty("group", "audit-group-" + lane);
                members.addProperty("part-" + index, id);
            }
            use.add("members", members);
            uses.add(use);
        }
        final var relationship = new JsonObject();
        relationship.addProperty("id", "shared-audits");
        relationship.add("uses", uses);
        final var relationships = new com.google.gson.JsonArray();
        relationships.add(relationship);
        creator.add("reuse", relationships);
        return new StressScene(project.toString(), creator.toString());
    }

    private static double percentile95(final List<Double> values) {
        final List<Double> sorted = values.stream().sorted().toList();
        return sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
    }
}
