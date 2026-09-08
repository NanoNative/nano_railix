package dev.nanonative.railix.creator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
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

final class CreatorWorldBrowserIT extends RailixCreatorBrowserSupport {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void selectedExamplesAndActiveTrafficRemainVisibleOnSharedBranchTrunks(final boolean highlight) {
        openProject(deepBranchProject(4));
        final Map<?, ?> rail = (Map<?, ?>) page.evaluate("""
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
                  const query = new URLSearchParams(window.__renderer.query), scale = Number(query.get('scale'));
                  const from = scene.nodes.find(node=>node.id===link.from);
                  const right = (from.x+from.width/2-Number(query.get('x')))*scale+Math.min(scale,1,from.width*scale/168,from.height*scale/64)*84;
                  const x = (right+(link.points[1][0]-Number(query.get('x')))*scale)/2;
                  const y = (link.points[0][1]-Number(query.get('y')))*scale;
                  return new Promise(resolve => {
                    const draw = WebGL2RenderingContext.prototype.drawArrays;
                    WebGL2RenderingContext.prototype.drawArrays = function(...args) {
                      const result = draw.apply(this,args);
                      if (this.canvas.id!=='world-canvas') return result;
                      WebGL2RenderingContext.prototype.drawArrays = draw;
                      const program = this.getParameter(this.CURRENT_PROGRAM);
                      const position = this.getAttribLocation(program,'position'), color = this.getAttribLocation(program,'color');
                      const stride = this.getVertexAttrib(position,this.VERTEX_ATTRIB_ARRAY_STRIDE)/4;
                      const offset = this.getVertexAttribOffset(color,this.VERTEX_ATTRIB_ARRAY_POINTER)/4;
                      const traffic = this.getVertexAttribOffset(this.getAttribLocation(program,'traffic'),this.VERTEX_ATTRIB_ARRAY_POINTER)/4;
                      const vertices = new Float32Array(args[2]*stride);
                      this.getBufferSubData(this.ARRAY_BUFFER,0,vertices);
                      let ink = [], spacing = 0;
                      for (let i=0;i<args[2];i+=3) {
                        const points = [0,1,2].map(j=>[vertices[(i+j)*stride],vertices[(i+j)*stride+1]]);
                        const crosses = points.map((a,j)=>{
                          const b=points[(j+1)%3]; return (b[0]-a[0])*(y-a[1])-(b[1]-a[1])*(x-a[0]);
                        });
                        if (crosses.every(v=>v>=-.001)||crosses.every(v=>v<=.001)) {
                          ink=[...vertices.slice(i*stride+offset,i*stride+offset+3)]; spacing=vertices[i*stride+traffic+1];
                        }
                      }
                      resolve({painted:ink.length===3,moving:spacing>0,selected:(highlight?[18/255,109/255,120/255]:[92/255,99/255,94/255]).every((v,i)=>Math.abs(v-ink[i])<.001)});
                      return result;
                    };
                    window.__renderer.repaint();
                  });
                }
                """, highlight);
        assertThat(rail.get("painted")).isEqualTo(true);
        assertThat(rail.get("selected")).isEqualTo(true);
        assertThat(rail.get("moving")).isEqualTo(true);
        page.evaluate("() => { window.__renderer.dispose(); }");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"triangle,0.5", "triangle,1", "triangle,2.625", "ellipse,0.5", "ellipse,1", "ellipse,2.625", "diamond,0.5", "rectangle,0.5"})
    void pendingBuildMarksStayInsideNarrowShapes(final String shape, final double aspect) {
        openProject(fourStepProject());
        page.evaluate("""
                async settings => {
                  state.world.dispose();
                  window.__renderer = new RailixWorld(document.querySelector('#graph'), {
                    appearance: node => node.id === 'one' ? {shape: settings[0],aspect: settings[1],changed:true} : {}
                  });
                  await window.__renderer.refresh();
                  window.__renderer.focus('one');
                }
                """, List.of(shape, aspect));
        page.waitForFunction("() => window.__renderer.scene?.nodes.some(node => node.id === 'one') && Number(new URLSearchParams(window.__renderer.query).get('scale')) >= 1");
        final Map<?, ?> marks = (Map<?, ?>) page.evaluate("""
                settings => new Promise(resolve => {
                  const draw = WebGL2RenderingContext.prototype.drawArrays;
                  WebGL2RenderingContext.prototype.drawArrays = function(...args) {
                    const result = draw.apply(this, args);
                    if (this.canvas.id !== 'world-canvas') return result;
                    WebGL2RenderingContext.prototype.drawArrays = draw;
                    const scene = window.__renderer.scene, query = new URLSearchParams(window.__renderer.query);
                    const node = scene.nodes.find(node => node.id === 'one'), scale = Number(query.get('scale'));
                    const height = Math.min(64,168/settings[1]), width = height*settings[1];
                    const x = (node.x+node.width/2-Number(query.get('x')))*scale-width/2;
                    const y = (node.y+node.height/2-Number(query.get('y')))*scale-height/2;
                    const program = this.getParameter(this.CURRENT_PROGRAM);
                    const position = this.getAttribLocation(program,'position'), color = this.getAttribLocation(program,'color');
                    const stride = this.getVertexAttrib(position,this.VERTEX_ATTRIB_ARRAY_STRIDE)/4;
                    const offset = this.getVertexAttribOffset(color,this.VERTEX_ATTRIB_ARRAY_POINTER)/4;
                    const vertices = new Float32Array(args[2]*stride);
                    this.getBufferSubData(this.ARRAY_BUFFER,0,vertices);
                    const points = [];
                    for (let i=0;i<args[2];i++) {
                      if (![164/255,99/255,20/255].every((v,j)=>Math.abs(v-vertices[i*stride+offset+j])<.001)) continue;
                      points.push([(vertices[i*stride]-x)/width,(vertices[i*stride+1]-y)/height]);
                    }
                    resolve({count:points.length,inside:points.every(([x,y]) => {
                      if (x<0 || y<0 || x>1 || y>1) return false;
                      if (settings[0]==='ellipse') return (x-.5)**2+(y-.5)**2<=.2501;
                      if (settings[0]==='diamond') return Math.abs(x-.5)+Math.abs(y-.5)<=.5001;
                      if (settings[0]==='triangle') return y>=x/2 && y<=1-x/2;
                      return true;
                    })});
                    return result;
                  };
                  window.__renderer.repaint();
                })
                """, List.of(shape, aspect));
        assertThat(((Number) marks.get("count")).intValue()).isPositive();
        assertThat(marks.get("inside")).isEqualTo(true);
        page.evaluate("() => { window.__renderer.dispose(); }");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ellipse", "triangle", "diamond", "rectangle"})
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
        page.evaluate("() => { state.world.focus('one'); }");
        awaitScene();
        final var point = (List<?>) page.evaluate("""
                () => {
                  const node = state.world.scene.nodes.find(node => node.id === 'one');
                  const query = new URLSearchParams(state.world.query);
                  const scale = Number(query.get('scale'));
                  const stage = document.querySelector('#graph').getBoundingClientRect();
                  return [stage.x + (node.x + node.width / 2 - Number(query.get('x'))) * scale,
                    stage.y + (node.y + node.height / 2 - Number(query.get('y'))) * scale,
                    Math.min(scale, 1, node.width * scale / 168, node.height * scale / 64)];
                }
                """);
        final double x = ((Number) point.get(0)).doubleValue();
        final double y = ((Number) point.get(1)).doubleValue();
        final double scale = ((Number) point.get(2)).doubleValue();
        page.mouse().click(x + 28 * scale, y - 28 * scale);
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isEqualTo("command");
        page.mouse().click(x, y);
        page.waitForFunction("() => document.querySelector('#inspector').dataset.selection === 'one'");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(doubles = {1, 100, 1_000_000})
    void trafficFramesReuseGeometryAndLabelsAtEveryRate(final double rate) {
        startRendererTraffic(rate, 10_000);
        page.waitForFunction("() => window.__traffic.draws >= 8");
        assertThat(page.evaluate("() => window.__traffic.uploads")).isEqualTo(0);
        assertThat(page.evaluate("() => window.__traffic.labels")).isEqualTo(0);
        assertThat(page.evaluate("() => window.__traffic.vertices")).isInstanceOfSatisfying(Number.class,
                number -> assertThat(number.intValue()).isBetween(1, 20_000));
        assertThat(pageErrors).isEmpty();
        page.evaluate("() => { window.__traffic.restore(); window.__renderer.dispose(); }");
    }

    @Test
    void trafficStopsWhenItsObservationExpires() {
        startRendererTraffic(10, 1_000);
        page.waitForFunction("() => document.querySelector('#graph').dataset.trafficAnimated === 'false'");
        page.evaluate("() => window.__traffic.draws = 0");
        page.evaluate("() => new Promise(resolve => setTimeout(resolve, 150))");
        assertThat(page.evaluate("() => window.__traffic.draws")).isEqualTo(0);
        page.evaluate("() => { window.__traffic.restore(); window.__renderer.dispose(); }");
    }

    @Test
    void reducedMotionStopsAndCanResumeTraffic() {
        startRendererTraffic(10, 10_000);
        page.emulateMedia(new Page.EmulateMediaOptions().setReducedMotion(com.microsoft.playwright.options.ReducedMotion.REDUCE));
        page.waitForFunction("() => document.querySelector('#graph').dataset.trafficAnimated === 'false'");
        page.evaluate("() => window.__traffic.draws = 0");
        page.evaluate("() => new Promise(resolve => setTimeout(resolve, 150))");
        assertThat(page.evaluate("() => window.__traffic.draws")).isEqualTo(0);
        page.emulateMedia(new Page.EmulateMediaOptions().setReducedMotion(com.microsoft.playwright.options.ReducedMotion.NO_PREFERENCE));
        page.waitForFunction("() => document.querySelector('#graph').dataset.trafficAnimated === 'true'");
        page.evaluate("() => { window.__traffic.restore(); window.__renderer.dispose(); }");
    }

    @Test
    void disposingTrafficStopsItsFrames() {
        startRendererTraffic(10, 10_000);
        page.evaluate("() => { window.__renderer.dispose(); window.__traffic.draws = 0; }");
        page.evaluate("() => new Promise(resolve => setTimeout(resolve, 150))");
        assertThat(page.evaluate("() => window.__traffic.draws")).isEqualTo(0);
        page.evaluate("() => window.__traffic.restore()");
    }

    @Test
    void losingAndRestoringGraphicsReleasesAndRebuildsTraffic() {
        startRendererTraffic(10, 30_000);
        page.evaluate("""
                () => {
                  window.__contextLoss = document.querySelector('#world-canvas').getContext('webgl2').getExtension('WEBGL_lose_context');
                  window.__contextLoss.loseContext();
                }
                """);
        page.waitForFunction("() => document.querySelector('#graph').dataset.sceneError?.includes('lost')");
        page.evaluate("() => window.__traffic.draws = 0");
        page.evaluate("() => new Promise(resolve => setTimeout(resolve, 150))");
        assertThat(page.evaluate("() => window.__traffic.draws")).isEqualTo(0);
        page.evaluate("() => window.__contextLoss.restoreContext()");
        page.waitForFunction("() => !document.querySelector('#graph').dataset.sceneError && window.__traffic.draws > 0");
        assertThat(pageErrors).isEmpty();
        page.evaluate("() => { window.__traffic.restore(); window.__renderer.dispose(); }");
    }

    private void startRendererTraffic(final double rate, final int lifetime) {
        openProject(deepBranchProject(4));
        // Exercise the renderer's public rate input, not fabricated application execution counters.
        page.evaluate("""
                async settings => {
                  state.world.dispose();
                  const until = performance.now() + settings[1];
                  window.__renderer = new RailixWorld(document.querySelector('#graph'), {
                    linkAppearance: () => ({rate: settings[0], selected: true}),
                    motionActive: () => performance.now() < until
                  });
                  await window.__renderer.refresh();
                }
                """, List.of(rate, lifetime));
        page.waitForFunction("() => document.querySelector('#graph').dataset.trafficAnimated === 'true'");
        page.evaluate("""
                () => new Promise(resolve => requestAnimationFrame(() => {
                  const prototype = WebGL2RenderingContext.prototype;
                  const draw = prototype.drawArrays, upload = prototype.bufferSubData;
                  const probe = window.__traffic = {draws:0,uploads:0,labels:0,vertices:0};
                  const observer = new MutationObserver(records => probe.labels += records.length);
                  observer.observe(document.querySelector('#world-labels'), {childList:true,subtree:true,attributes:true});
                  prototype.drawArrays = function(...args) {
                    if (this.canvas.id === 'world-canvas') { probe.draws++; probe.vertices = args[2]; }
                    return draw.apply(this, args);
                  };
                  prototype.bufferSubData = function(...args) {
                    if (this.canvas.id === 'world-canvas') probe.uploads++;
                    return upload.apply(this, args);
                  };
                  probe.restore = () => { observer.disconnect(); prototype.drawArrays = draw; prototype.bufferSubData = upload; };
                  resolve();
                }))
                """);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.7, 1.0, 1.4, 3.0})
    void renderedBranchConnectionsStayOrthogonalAfterZoom(final double zoom) {
        openProject(deepBranchProject(24));
        page.evaluate("scale => { state.world.zoom(scale); }", zoom);
        awaitScene();

        final Object geometry = page.evaluate("""
                () => new Promise(resolve => {
                  const draw = WebGL2RenderingContext.prototype.drawArrays;
                  WebGL2RenderingContext.prototype.drawArrays = function(...args) {
                    const result = draw.apply(this, args);
                    if (this.canvas.id !== 'world-canvas') return result;
                    WebGL2RenderingContext.prototype.drawArrays = draw;
                    const program = this.getParameter(this.CURRENT_PROGRAM);
                    const position = this.getAttribLocation(program, 'position');
                    const color = this.getAttribLocation(program, 'color');
                    const stride = this.getVertexAttrib(position, this.VERTEX_ATTRIB_ARRAY_STRIDE) / 4;
                    const colorOffset = this.getVertexAttribOffset(color, this.VERTEX_ATTRIB_ARRAY_POINTER) / 4;
                    const vertices = new Float32Array(args[2] * stride);
                    this.getBufferSubData(this.ARRAY_BUFFER, 0, vertices);
                    const rails = [];
                    const point = index => [vertices[index * stride], vertices[index * stride + 1]];
                    const same = (a, b) => a.every((value, index) => Math.abs(value - b[index]) < .001);
                    for (let index = 0; index + 5 < args[2]; index += 3) {
                      const ink = [...vertices.slice(index * stride + colorOffset, index * stride + colorOffset + 3)];
                      if (!same(ink, [92/255,99/255,94/255])
                          || !same(point(index), point(index + 3))
                          || !same(point(index + 2), point(index + 4))) continue;
                      const a = point(index), b = point(index + 1);
                      rails.push(Math.abs(a[0] - b[0]) < .001 || Math.abs(a[1] - b[1]) < .001);
                      index += 3;
                    }
                    resolve({count: rails.length, orthogonal: rails.every(Boolean)});
                    return result;
                  };
                  state.world.repaint();
                })
                """);

        assertThat(geometry).isInstanceOfSatisfying(Map.class, result -> {
            assertThat(((Number) result.get("count")).intValue()).isPositive();
            assertThat(result.get("orthogonal")).isEqualTo(true);
        });
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void appBodyFirstClickSelectsWithoutFocusing() {
        final BoundingBox label = fittedAppLabelWithTriggerSelected();
        final String camera = (String) page.evaluate("() => state.world.query");

        page.mouse().click(label.x - 5, label.y + label.height / 2);

        awaitAppSelection();
        awaitScene();
        assertThat(page.evaluate("() => state.world.query")).isEqualTo(camera);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void appBodyClickWithTwoPixelJitterDoesNotMoveTheCamera() {
        final BoundingBox label = fittedAppLabelWithTriggerSelected();
        final String camera = (String) page.evaluate("() => state.world.query");

        page.mouse().move(label.x - 5, label.y + label.height / 2);
        page.mouse().down();
        page.mouse().move(label.x - 3, label.y + label.height / 2);
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
        final BoundingBox label = fittedAppLabelWithTriggerSelected();
        final BoundingBox graph = page.locator("#graph").boundingBox();
        final String camera = (String) page.evaluate("() => state.world.query");

        page.mouse().move(label.x - 5, label.y + label.height / 2);
        page.mouse().down();
        page.mouse().move(label.x - 5, graph.y - 12);
        page.mouse().up();

        awaitScene();
        final String panned = (String) page.evaluate("() => state.world.query");
        assertThat(panned).isNotEqualTo(camera);
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isEqualTo("command");
        assertThat(page.locator("#graph").getAttribute("class")).doesNotContain("panning");
        page.mouse().move(graph.x + 8, graph.y + 8);
        awaitScene();
        assertThat(page.evaluate("() => state.world.query")).isEqualTo(panned);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void appLabelClickPreservesNativeSelection() {
        final BoundingBox label = fittedAppLabelWithTriggerSelected();
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
    void collapsedAutomaticRegionMatchesRegularStepFootprint() {
        openProject(deepBranchProject(96));
        awaitScene();
        assertThat(page.evaluate("""
                () => state.world.scene.nodes.some(node => node.kind === 'region'
                  && !node.group && !node.expanded && node.count > 8)
                """)).isEqualTo(true);

        assertMatchingFootprints(page.locator("[data-kind='region']:not([data-region-group])").first(),
                page.locator("[data-node-id='filter']"));
        assertThat(pageErrors).isEmpty();
    }

    private BoundingBox fittedAppLabelWithTriggerSelected() {
        openProject(deepBranchProject(4));
        awaitScene();
        page.locator("[data-node-id='command']").click();
        page.waitForFunction("""
                () => !state.editorController && document.querySelector('#inspector').dataset.selection === 'command'
                """);
        awaitScene();
        final BoundingBox label = page.locator("[data-node-id='app']").boundingBox();
        assertThat(label).isNotNull();
        assertThat(page.evaluate("""
                point => document.elementFromPoint(point[0], point[1])?.id
                """, List.of(label.x - 5, label.y + label.height / 2)))
                .as("The App body click must land on the canvas, outside the label button")
                .isEqualTo("world-canvas");
        return label;
    }

    private void awaitAppSelection() {
        page.waitForFunction("""
                () => !state.editorController && document.querySelector('#inspector').dataset.selection === 'app'
                  && document.querySelector('[data-node-id="app"]')?.getAttribute('aria-pressed') === 'true'
                """);
    }

    private static void assertMatchingFootprints(final Locator region, final Locator step) {
        final BoundingBox regionBox = region.boundingBox();
        final BoundingBox stepBox = step.boundingBox();
        assertThat(regionBox).as("Visible collapsed region label").isNotNull();
        assertThat(stepBox).as("Visible ordinary Step label").isNotNull();
        assertThat(stepBox.width).isGreaterThan(38);
        assertThat(stepBox.height).isBetween(18.0, 55.0);
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
        final List<Double> frames = new ArrayList<>();
        final List<Long> heaps = new ArrayList<>();
        final List<Integer> checkpoints = new ArrayList<>();
        final CDPSession cdp = context.newCDPSession(page);
        try {
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
                    collectMeasurements(frames);
                }
            }
            fitWorld();
            collectMeasurements(frames);
            frames.clear();
            final JsonObject baseline = retainedHeap(cdp);
            heaps.add(baseline.get("usedSize").getAsLong());
            checkpoints.add(0);
            if (extendedRetention) recordRetention(cdp, baseline, 0, retentionOutput, false);

            for (int round = 0; round < rounds; round++) {
                focusStation(stations.get(round % stations.size()));
                for (int index = 0; index < 20; index++) {
                    requests.add(navigate(directions.get(index % directions.size())));
                    collectMeasurements(frames);
                }
                fitWorld();
                collectMeasurements(frames);
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

            collectMeasurements(frames);
            assertThat(requests).hasSize(rounds * 20);
            assertThat(frames).isNotEmpty();
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
                      const view = new URLSearchParams(state.world.query);
                      const scale = Number(view.get('scale'));
                      const graph = document.querySelector('#graph');
                      return state.world.scene.nodes.filter(node => node.kind === 'region' && !node.expanded)
                        .map(node => ({...node,
                          screen_x: (node.x + node.width / 2 - Number(view.get('x'))) * scale,
                          screen_y: (node.y + node.height / 2 - Number(view.get('y'))) * scale
                        })).find(node => node.screen_x > 0 && node.screen_x < graph.clientWidth
                          && node.screen_y > 0 && node.screen_y < graph.clientHeight);
                    }
                    """);
            assertThat(region).as("Rendered semantic-zoom aggregate").isNotNull();
            final String aggregateId = String.valueOf(region.get("id"));
            finishScene(page.waitForResponse(CreatorWorldBrowserIT::viewportResponse,
                    () -> page.locator("#graph").click(new Locator.ClickOptions().setPosition(
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
            page.screenshot(new Page.ScreenshotOptions().setPath(expanded));
            focusStation("step-2999");
            page.locator("[data-node-id='step-2999']").waitFor();
            final Object detailScene = sceneSnapshot();
            page.screenshot(new Page.ScreenshotOptions().setPath(detail));
            final Map<String, Object> counts = collectMeasurements(frames);
            assertThat(pageErrors).isEmpty();
            assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(persisted);
            assertThat(creatorMetadata()).isEqualTo(metadata);
            final List<Long> tail = heaps.subList(Math.max(0, heaps.size() - 10), heaps.size());

            final String report = String.format(Locale.ROOT, """
                    RAILIX_WORLD_MEASUREMENTS advisory=true
                    captured_at_epoch_ms=%d
                    project_nodes=6003 viewport=%dx%d browser=%s os=%s java=%s
                    warmup_navigation_requests=12 navigation_rounds=%d measured_navigation_requests=%d measured_webgl_frames=%d
                    viewport_request_p95_ms=%.3f
                    webgl_frame_cpu_submission_p95_ms=%.3f
                    max_scene_glyphs=%s max_route_segments=%s max_labels=%s max_label_dom_elements=%s
                    post_gc_navigation_counts=%s
                    post_gc_used_js_heap_bytes=%s
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
                    Frame timing is the CPU animation callback submitting real WebGL draws, not GPU completion.
                    Heap is CDP Runtime.getHeapUsage usedSize after forced GC, at the same fitted camera after each round.
                    The heap series is observational evidence, not proof of leak freedom or a retained-memory threshold.
                    Percentiles use nearest rank; timings and heap growth have no pass/fail thresholds.
                    """, System.currentTimeMillis(), width, page.viewportSize().height,
                    context.browser().version(), System.getProperty("os.name"), System.getProperty("java.version"),
                    rounds, requests.size(), frames.size(), percentile95(requests), percentile95(frames),
                    counts.get("glyphs"), counts.get("segments"), counts.get("labels"), counts.get("dom"),
                    checkpoints, heaps, heaps.getLast() - heaps.getFirst(),
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
                page.evaluate("() => { window.__railixWorldMeasurements?.restore(); delete window.__railixWorldMeasurements; }");
            } finally {
                try {
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
                  const requestFrame = window.requestAnimationFrame;
                  const draw = WebGL2RenderingContext.prototype.drawArrays;
                  let draws = 0;
                  const probe = window.__railixWorldMeasurements = {
                    frames: [], glyphs: 0, segments: 0, labels: 0, dom: 0,
                    restore() {
                      window.requestAnimationFrame = requestFrame;
                      WebGL2RenderingContext.prototype.drawArrays = draw;
                    }
                  };
                  WebGL2RenderingContext.prototype.drawArrays = function(...args) {
                    const result = draw.apply(this, args);
                    if (this.canvas.id === 'world-canvas') draws++;
                    return result;
                  };
                  window.requestAnimationFrame = callback => requestFrame.call(window, timestamp => {
                    const before = draws;
                    const started = performance.now();
                    try {
                      return callback.call(window, timestamp);
                    } finally {
                      const elapsed = performance.now() - started;
                      if (draws !== before) {
                        probe.frames.push(elapsed);
                        const scene = state.world.scene;
                        const labels = document.querySelector('#world-labels');
                        probe.glyphs = Math.max(probe.glyphs, scene.nodes.length);
                        probe.segments = Math.max(probe.segments,
                          scene.links.reduce((sum, link) => sum + link.points.length - 1, 0));
                        probe.labels = Math.max(probe.labels, labels.childElementCount);
                        probe.dom = Math.max(probe.dom, labels.querySelectorAll('*').length);
                      }
                    }
                  });
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
        final Response response = page.waitForResponse(CreatorWorldBrowserIT::viewportResponse,
                () -> page.evaluate("id => void state.world.focus(id)", id));
        finishScene(response);
        assertThat(page.evaluate("id => state.world.scene.nodes.some(node => node.id === id)", id)).isEqualTo(true);
    }

    private void fitWorld() {
        final Response response = page.waitForResponse(CreatorWorldBrowserIT::viewportResponse,
                () -> page.locator("#zoom-fit").click());
        finishScene(response);
    }

    private static boolean viewportResponse(final Response response) {
        return response.url().contains("/api/scene?") && !response.url().contains("focus=");
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
        assertThat(page.locator("#world-error").isVisible()).isFalse();
        assertThat(page.locator("#world-canvas").count()).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> collectMeasurements(final List<Double> frames) {
        final Map<String, Object> measurements = (Map<String, Object>) page.evaluate("""
                () => {
                  const probe = window.__railixWorldMeasurements;
                  return {frames: probe.frames.splice(0), glyphs: probe.glyphs,
                    segments: probe.segments, labels: probe.labels, dom: probe.dom};
                }
                """);
        ((List<Number>) measurements.get("frames")).stream().map(Number::doubleValue).forEach(frames::add);
        assertThat(((Number) measurements.get("glyphs")).intValue()).isBetween(1, 2_048);
        assertThat(((Number) measurements.get("segments")).intValue()).isBetween(1, 4_096);
        assertThat(((Number) measurements.get("labels")).intValue()).isBetween(1, 256);
        assertThat(((Number) measurements.get("dom")).intValue()).isBetween(1, 1_024);
        return measurements;
    }

    private JsonObject retainedHeap(final CDPSession cdp) {
        page.evaluate("() => { performance.clearResourceTimings(); window.__railixWorldMeasurements.frames.length = 0; }");
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
                    vertex_capacity_bytes: state.world._vertices.byteLength,
                    vertex_used_floats: state.world._vertexLength,
                    graphics_buffer_bytes: state.world._bufferBytes
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

    private static double percentile95(final List<Double> values) {
        final List<Double> sorted = values.stream().sorted().toList();
        return sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
    }
}
