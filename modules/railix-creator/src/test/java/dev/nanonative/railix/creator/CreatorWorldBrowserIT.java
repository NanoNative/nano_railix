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

@Timeout(120)
final class CreatorWorldBrowserIT extends RailixCreatorBrowserSupport {
    @Test
    void junctionMarkingsStayPerpendicularToLongTransportRuns() {
        openProject(choiceProject());
        awaitScene();
        final Map<?, ?> result = (Map<?, ?>) page.evaluate("""
                () => new Promise((resolve,reject)=>{
                  const render=PIXI.WebGLRenderer.prototype.render;
                  const timeout=setTimeout(()=>{PIXI.WebGLRenderer.prototype.render=render;reject(new Error('No belt frame'));},2000);
                  PIXI.WebGLRenderer.prototype.render=function(root,...args){
                    const result=render.call(this,root,...args);
                    if(this.canvas.id!=='world-canvas') return result;
                    PIXI.WebGLRenderer.prototype.render=render;clearTimeout(timeout);
                    const v=root.children[0].geometry.getBuffer('aPosition').data;
                    let count=0,skewed=0;
                    for(let i=0;i+54<=v.length;i+=27){
                      if(!v[i+7] || v[i]!==v[i+27] || v[i+1]!==v[i+28]) continue;
                      const a=[v[i],v[i+1]],b=[v[i+9],v[i+10]],c=[v[i+18],v[i+19]],d=[v[i+45],v[i+46]];
                      const dx=(b[0]+c[0]-a[0]-d[0])/2,dy=(b[1]+c[1]-a[1]-d[1])/2,length=Math.hypot(dx,dy);
                      if(length>40){count++;if(Math.abs((a[0]-d[0])*dx+(a[1]-d[1])*dy)/length>.05
                        || Math.abs((b[0]-c[0])*dx+(b[1]-c[1])*dy)/length>.05)skewed++;}
                      i+=27;
                    }
                    resolve({count,skewed});return result;
                  };
                  state.world.repaint();
                })
                """);
        assertThat(((Number) result.get("count")).intValue()).isPositive();
        assertThat(((Number) result.get("skewed")).intValue()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void choiceJunctionHasContinuousSurfaceAtItsBranchAxis() {
        openProject(choiceProject());
        final Object junction = page.evaluate("""
                async () => {
                  state.world.dispose();
                  window.__renderer = new RailixWorld(document.querySelector('#graph'));
                  await window.__renderer.refresh();
                  await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
                  const link=window.__renderer.scene.links.find(link=>link.from==='choice');
                  const query=new URLSearchParams(window.__renderer.query),scale=Number(query.get('scale'));
                  const x=(link.points[1][0]-Number(query.get('x')))*scale;
                  const y=(link.points[1][1]-Number(query.get('y')))*scale;
                  return new Promise((resolve,reject)=>{
                    const render=PIXI.WebGLRenderer.prototype.render;
                    const timeout=setTimeout(()=>{PIXI.WebGLRenderer.prototype.render=render;reject(new Error('No junction frame'));},2000);
                    PIXI.WebGLRenderer.prototype.render=function(root,...args){
                      const result=render.call(this,root,...args);
                      if(this.canvas.id!=='world-canvas') return result;
                      PIXI.WebGLRenderer.prototype.render=render;clearTimeout(timeout);
                      const v=root.children[0].geometry.getBuffer('aPosition').data;
                      let ink=[];
                      for(let i=0;i<v.length;i+=27){
                        const points=[0,9,18].map(j=>[v[i+j],v[i+j+1]]);
                        const cross=points.map((a,j)=>{const b=points[(j+1)%3];return (b[0]-a[0])*(y-a[1])-(b[1]-a[1])*(x-a[0]);});
                        if(cross.every(c=>Math.abs(c)<.001)) continue;
                        if(cross.every(c=>c>=-.001)||cross.every(c=>c<=.001)) ink=[v[i+2],v[i+3],v[i+4]];
                      }
                      resolve({surface:[92/255,99/255,94/255].every((c,i)=>Math.abs(c-ink[i])<.001),ink});return result;
                    };
                    window.__renderer.repaint();
                  });
                }
                """);
        assertThat(junction).isInstanceOfSatisfying(Map.class, value -> assertThat(value.get("surface")).as("Junction surface: %s", value).isEqualTo(true));
        page.evaluate("() => { window.__renderer.dispose(); }");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"rectangle", "ellipse", "triangle", "diamond"})
    void machineSymbolsKeepTheirSizeAndAspectAcrossShapes(final String shape) {
        openProject(fourStepProject());
        page.evaluate("""
                async shape => {
                  state.world.dispose();
                  window.__renderer = new RailixWorld(document.querySelector('#graph'), {
                    appearance: () => ({shape,aspect:2.625,symbol:'number'})
                  });
                  await window.__renderer.refresh();
                  window.__renderer.focus('one');
                }
                """, shape);
        page.waitForFunction("() => Number(new URLSearchParams(window.__renderer.query).get('scale')) >= 1");
        page.waitForFunction("() => document.querySelector('[data-world-id=one] .world-symbol')?.getBoundingClientRect().width >= 30");
        final BoundingBox icon = page.locator("[data-world-id=one] .world-symbol").boundingBox();
        assertThat(icon.width).isCloseTo(icon.height, within(.1));
        assertThat(icon.width).isBetween(30.0, 48.0);
        page.evaluate("() => { window.__renderer.dispose(); }");
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
        page.waitForFunction("() => Number(new URLSearchParams(window.__renderer.query).get('scale')) >= 1");
        final Locator timing = page.locator("[data-world-id=one] .world-duration");
        timing.waitFor();
        assertThat(timing.textContent()).isEqualTo("12 ms avg");
        final double before = timing.boundingBox().y - page.locator("[data-world-id=one]").boundingBox().y;
        page.locator("#graph").press("ArrowRight");
        assertThat(timing.boundingBox().y - page.locator("[data-world-id=one]").boundingBox().y).isCloseTo(before, within(.1));
        assertThat(page.locator(".world-metric").count()).isZero();
        page.evaluate("() => { window.__renderer.dispose(); }");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void measuredDurationDoesNotDrawAnUnlabelledOrangeMeter() {
        openProject(fourStepProject());
        final Number orange = (Number) page.evaluate("""
                async () => {
                  state.world.dispose();
                  window.__renderer = new RailixWorld(document.querySelector('#graph'), {
                    appearance: () => ({duration:'12 ms',heat:1})
                  });
                  await window.__renderer.refresh();
                  return new Promise((resolve,reject) => {
                    const render = PIXI.WebGLRenderer.prototype.render;
                    const timeout = setTimeout(() => {PIXI.WebGLRenderer.prototype.render=render;reject(new Error('No frame'));},2000);
                    PIXI.WebGLRenderer.prototype.render = function(root,...args) {
                      const result = render.call(this,root,...args);
                      if(this.canvas.id!=='world-canvas') return result;
                      PIXI.WebGLRenderer.prototype.render=render;clearTimeout(timeout);
                      const v=root.children[2].geometry.getBuffer('aPosition').data;
                      let count=0;
                      for(let i=0;i<v.length;i+=9) if([164/255,99/255,20/255].every((c,j)=>Math.abs(c-v[i+2+j])<.001)) count++;
                      resolve(count);return result;
                    };
                    window.__renderer.repaint();
                  });
                }
                """);
        assertThat(orange.intValue()).isZero();
        page.evaluate("() => { window.__renderer.dispose(); }");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void applicationPollingDoesNotDuplicateThePendingSelectedStepRead() {
        openProject(fourStepProject());
        awaitScene();
        page.waitForFunction("() => state.application.examples?.state === 'completed'");
        page.locator("[data-world-id=command]").click();
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
    void navigatingKeepsObservationsForUnchangedVisibleStationsWhileTheNextReadIsPending() {
        openProject(fourStepProject());
        awaitScene();
        page.waitForFunction("() => document.querySelector('[data-world-id=one]')?.dataset.activity === 'active'");
        final String before = page.locator("[data-world-id=one]").getAttribute("title");
        page.evaluate("""
                () => {
                  const fetch = window.fetch;
                  window.__releaseObservations = [];
                  window.fetch = async (...args) => {
                    const response = await fetch(...args);
                    if (String(args[0]).startsWith('/api/scene/observations?'))
                      await new Promise(resolve => window.__releaseObservations.push(resolve));
                    return response;
                  };
                  window.__restoreFetch = () => {
                    window.fetch = fetch;
                    window.__releaseObservations.forEach(resolve => resolve());
                  };
                }
                """);
        try {
            final String query = (String) page.evaluate("() => state.world.query");
            page.locator("#graph").press("ArrowRight");
            page.waitForFunction("query => state.world.query !== query", query);
            page.waitForFunction("() => window.__releaseObservations.length > 0");
            assertThat(page.locator("[data-world-id=one]").getAttribute("data-activity")).isEqualTo("active");
            assertThat(page.locator("[data-world-id=one]").getAttribute("title")).isEqualTo(before);
        } finally {
            page.evaluate("() => window.__restoreFetch()");
        }
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"rectangle", "diamond", "ellipse"})
    void conveyorBendsDoNotFoldTheirInnerSurface(final String shape) {
        openProject(deepBranchProject(4));
        page.evaluate("""
                shape => fetch('/api/creator', {method:'POST',headers:mutationHeaders(),body:JSON.stringify({
                  format:2,groups:[{id:'pair',name:'Pair',shape,aspect:2.75}],
                  steps:{filter:{shape,aspect:2.75},'step-0':{group:'pair'},'step-1':{group:'pair'}}
                })})
                """, shape);
        page.reload();
        waitForText("#build-state", "Built");
        awaitScene();
        final Map<?, ?> faces = (Map<?, ?>) page.evaluate("""
                () => new Promise((resolve,reject) => {
                  const render = PIXI.WebGLRenderer.prototype.render;
                  const deadline = setTimeout(() => { PIXI.WebGLRenderer.prototype.render = render;
                    reject(new Error('No conveyor frame')); },2000);
                  PIXI.WebGLRenderer.prototype.render = function(root,...args) {
                    const result = render.call(this,root,...args);
                    if (this.canvas.id !== 'world-canvas') return result;
                    PIXI.WebGLRenderer.prototype.render = render;
                    clearTimeout(deadline);
                    const vertices = root.children[0].geometry.getBuffer('aPosition').data;
                    const folded = [];
                    let count = 0;
                    for(let i=0;i+27<=vertices.length;i+=27) {
                      if (!vertices[i+7]) continue;
                      count++;
                      const cross = (vertices[i+9]-vertices[i])*(vertices[i+19]-vertices[i+1])
                        -(vertices[i+10]-vertices[i+1])*(vertices[i+18]-vertices[i]);
                      if(cross > .01) folded.push([cross,...vertices.slice(i,i+2),...vertices.slice(i+9,i+11),...vertices.slice(i+18,i+20)]);
                    }
                    resolve({folded,count});
                    return result;
                  };
                  state.world.repaint();
                })
                """);
        assertThat(((Number) faces.get("count")).intValue()).isGreaterThan(10);
        assertThat((List<?>) faces.get("folded")).as("No inverted conveyor triangles").isEmpty();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void gridDensityDoesNotFlashAtAZoomLevelBoundary() {
        openProject(fourStepProject());
        awaitScene();
        final List<?> coverage = (List<?>) page.evaluate("""
                async () => {
                  const sample = scale => new Promise((resolve,reject) => {
                    const render = PIXI.WebGLRenderer.prototype.render;
                    const deadline = setTimeout(() => { PIXI.WebGLRenderer.prototype.render = render;
                      reject(new Error('No grid frame')); },2000);
                    PIXI.WebGLRenderer.prototype.render = function(root,...args) {
                      const result = render.call(this,root,...args);
                      if (this.canvas.id !== 'world-canvas') return result;
                      PIXI.WebGLRenderer.prototype.render = render;
                      clearTimeout(deadline);
                      const v = root.children[0].geometry.getBuffer('aPosition').data;
                      let area = 0;
                      for(let i=0;i+27<=v.length;i+=27) if(v[i+5]>0 && v[i+5]<=.1)
                        area += Math.abs((v[i+9]-v[i])*(v[i+19]-v[i+1])
                          -(v[i+10]-v[i+1])*(v[i+18]-v[i]))/2*v[i+5];
                      resolve(area);
                      return result;
                    };
                    state.world.zoom(scale / Number(new URLSearchParams(state.world.query).get('scale')));
                  });
                  await state.world.refresh();
                  const before = await sample(.9999);
                  await state.world.refresh();
                  const after = await sample(1.0001);
                  return [before,after];
                }
                """);
        final double before = ((Number) coverage.getFirst()).doubleValue();
        assertThat(before).isPositive();
        assertThat(((Number) coverage.getLast()).doubleValue()).isCloseTo(before, within(before * .05));
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
        assertThat(page.locator("#graph").getAttribute("data-renderer")).isEqualTo("pixi");
        assertThat(page.evaluate("PIXI.VERSION")).isEqualTo("8.20.1");
        assertThat(page.locator(".world-symbol").count()).isGreaterThan(2);
        assertThat(externalRequests).isEmpty();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void unavailableWebGl2ReportsTheRendererCapabilityError() {
        page.addInitScript("""
                (() => {
                  const getContext = HTMLCanvasElement.prototype.getContext;
                  HTMLCanvasElement.prototype.getContext = function(type, ...args) {
                    return type === 'webgl2' ? null : getContext.call(this, type, ...args);
                  };
                })()
                """);
        page.reload();
        page.locator("#world-error:not([hidden])").waitFor();
        assertThat(page.locator("#world-error").textContent()).contains("WebGL2");
        assertThat(page.locator("#graph").getAttribute("data-renderer")).isNull();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void conveyorsHaveTransportWidthRatherThanDiagramLines() {
        openProject(fourStepProject());
        awaitScene();
        final Map<?, ?> ratio = (Map<?, ?>) page.evaluate("""
                () => new Promise((resolve, reject) => {
                  const render = PIXI.WebGLRenderer.prototype.render;
                  const deadline = setTimeout(() => { PIXI.WebGLRenderer.prototype.render = render;
                    reject(new Error('Timed out waiting for a Pixi frame.')); }, 2_000);
                  PIXI.WebGLRenderer.prototype.render = function(root, ...args) {
                    const result = render.call(this,root,...args);
                    if (this.canvas.id !== 'world-canvas') return result;
                    PIXI.WebGLRenderer.prototype.render = render;
                    clearTimeout(deadline);
                    const a = document.querySelector('[data-world-id="one"] .world-symbol').getBoundingClientRect();
                    const b = document.querySelector('[data-world-id="two"] .world-symbol').getBoundingClientRect();
                    const canvas = this.canvas.getBoundingClientRect();
                    const image = this.extract.pixels({target:root, frame:this.screen, resolution:this.resolution});
                    const x = Math.max(0,Math.min(image.width-1,Math.floor(((a.right+b.left)/2-canvas.left)*this.resolution)));
                    const y = Math.floor(((a.top+a.bottom)/2-canvas.top)*this.resolution);
                    // Icons now occupy a small square; scan beyond the complete belt, not only its center.
                    const height = Math.max(1,Math.floor(a.height*3*this.resolution));
                    let longest=0,run=0;
                    let nonblank=0;
                    for(let row=Math.max(0,y-Math.floor(height/2));row<Math.min(image.height,y+Math.ceil(height/2));row++) {
                      const i=((image.height-1-row)*image.width+x)*4;
                      const bright=image.pixels[i]+image.pixels[i+1]+image.pixels[i+2];
                      if(image.pixels[i+3]>0 && bright>0) nonblank++;
                      run = bright < 400 ? run+1 : 0;
                      longest=Math.max(longest,run);
                    }
                    resolve({ratio:longest/height,nonblank});
                    return result;
                  };
                  state.world.repaint();
                })
                """);
        assertThat(((Number) ratio.get("nonblank")).intValue()).isPositive();
        assertThat(((Number) ratio.get("ratio")).doubleValue()).isGreaterThanOrEqualTo(.25);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void stationaryConveyorChevronsPointForwardAlongTheOutgoingAppBelt() {
        openProject(fourStepProject());
        awaitScene();
        final Map<?, ?> chevron = (Map<?, ?>) page.evaluate("""
                () => new Promise((resolve, reject) => {
                  const renderer = PIXI.WebGLRenderer.prototype;
                  const render = renderer.render;
                  const deadline = setTimeout(() => { renderer.render = render;
                    reject(new Error('Timed out waiting for the Pixi conveyor frame.')); }, 2_000);
                  renderer.render = function(root, ...args) {
                    const result = render.call(this, root, ...args);
                    if (this.canvas.id !== 'world-canvas') return result;
                    renderer.render = render;
                    clearTimeout(deadline);
                    try {
                      const vertices = root.children[0].geometry.getBuffer('aPosition').data;
                      const app = document.querySelector('[data-world-id="app"] .world-symbol').getBoundingClientRect();
                      const command = document.querySelector('[data-world-id="command"] .world-symbol').getBoundingClientRect();
                      const midpoint = [(app.right + command.left) / 2, (app.top + app.bottom) / 2];
                      let belt;
                      for (let i = 0; i + 54 <= vertices.length; i += 27) {
                        if (vertices[i] !== vertices[i+27] || vertices[i+1] !== vertices[i+28]
                          || vertices[i+18] !== vertices[i+36] || vertices[i+19] !== vertices[i+37]) continue;
                        const start = vertices[i + 6], end = vertices[i + 15], spacing = vertices[i + 7];
                        const dx = vertices[i + 9] - vertices[i], dy = vertices[i + 10] - vertices[i + 1];
                        const half = Math.abs(vertices[i + 8]);
                        const unit = half / 19, period = 42 * unit;
                        const peak = period * Math.ceil((start - 21 * unit) / period) + 21 * unit;
                        const distance = Math.hypot((vertices[i] + vertices[i + 9]) / 2 - midpoint[0],
                          (vertices[i + 1] + vertices[i + 10]) / 2 - midpoint[1]);
                        if (spacing < 0 && dx > 12 * unit && Math.abs(dy) < unit && half >= 10 * unit
                          && peak - 3 * unit > start && peak + 3 * unit < end && (!belt || distance < belt.distance)) {
                          belt = {i,start,end,peak,half,unit,distance};
                        }
                      }
                      if (!belt) throw new Error('The outgoing App belt did not submit a rightward stationary segment.');
                      const point = (along, side) => {
                        const t = (along - belt.start) / (belt.end - belt.start), i = belt.i;
                        const plus = [vertices[i] + (vertices[i + 9] - vertices[i]) * t,
                          vertices[i + 1] + (vertices[i + 10] - vertices[i + 1]) * t];
                        const minus = [vertices[i + 45] + (vertices[i + 18] - vertices[i + 45]) * t,
                          vertices[i + 46] + (vertices[i + 19] - vertices[i + 46]) * t];
                        return [(plus[0] + minus[0]) / 2 + (plus[0] - minus[0]) * side / (2 * belt.half),
                          (plus[1] + minus[1]) / 2 + (plus[1] - minus[1]) * side / (2 * belt.half)];
                      };
                      const image = this.extract.pixels({target:root, frame:this.screen, resolution:this.resolution});
                      const brightness = ([x,y]) => {
                        const column = Math.max(0,Math.min(image.width-1,Math.round(x*this.resolution)));
                        const row = Math.max(0,Math.min(image.height-1,Math.round(y*this.resolution)));
                        const index = ((image.height-1-row)*image.width+column)*4;
                        return image.pixels[index] + image.pixels[index+1] + image.pixels[index+2];
                      };
                      // A rightward chevron has its arms behind its tip, never ahead of it.
                      // Average both arms across several rows rather than selecting one subpixel edge.
                      const samples = [-4,-3,-2,2,3,4].map(row => {
                        const side = row * belt.unit, offset = Math.abs(side) * .85;
                        const guide = along => brightness(point(along, side))
                          - brightness(point(along, Math.sign(side) * 10 * belt.unit));
                        return [guide(belt.peak - offset), guide(belt.peak + offset)];
                      });
                      const painted = image.pixels.some((value,index) => index % 4 === 3 && value > 0
                        && image.pixels[index-1] + image.pixels[index-2] + image.pixels[index-3] > 0);
                      resolve({behind:samples.reduce((sum,sample) => sum+sample[0],0),
                        ahead:samples.reduce((sum,sample) => sum+sample[1],0),painted,samples});
                    } catch (error) { reject(error); }
                    return result;
                  };
                  state.world.repaint();
                })
                """);
        assertThat(chevron.get("painted")).isEqualTo(true);
        assertThat(((Number) chevron.get("behind")).doubleValue()).as("Conveyor arm samples: %s", chevron)
                .isGreaterThan(((Number) chevron.get("ahead")).doubleValue());
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void denseBranchStationsHaveSeparateVisibleBodies() {
        openProject(nestedSwitchProject());
        awaitScene();
        final List<?> overlaps = (List<?>) page.evaluate("""
                () => {
                  const bodies = [...document.querySelectorAll('.world-symbol')].map(element => element.getBoundingClientRect());
                  return bodies.flatMap((a, index) => bodies.slice(index + 1)
                    .filter(b => a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom)
                    .map(b => ({first: a.toJSON(), second: b.toJSON()})));
                }
                """);
        assertThat(page.locator(".world-symbol").count()).isGreaterThanOrEqualTo(4);
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
                  state.world.focus('one');
                }
                """);
        page.waitForFunction("() => Number(new URLSearchParams(state.world.query).get('scale')) >= 1");
        awaitScene();
        assertThat(page.evaluate("() => window.__cameraMoved")).isEqualTo(!reduced);
        assertThat(requests).hasSizeBetween(2, 4);
        assertThat(page.locator("#graph").getAttribute("data-camera-moving")).isEqualTo("false");
        page.evaluate("() => window.__cameraObserver.disconnect()");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"zoom", "press", "visibility"})
    void aCanvasGestureCancelsAnInProgressFocusFlight(final String gesture) {
        openProject(fourStepProject());
        awaitScene();
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
        });
        finishScene(response);
        page.waitForFunction("query => state.world.query === query", java.net.URI.create(response.url()).getRawQuery());
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
                  await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                  const x = ((link.points[0][0]+link.points[1][0])/2-Number(query.get('x')))*scale;
                  const y = ((link.points[0][1]+link.points[1][1])/2-Number(query.get('y')))*scale;
                  return new Promise((resolve, reject) => {
                    const renderer = PIXI.WebGLRenderer.prototype;
                    const render = renderer.render;
                    const deadline = setTimeout(() => { renderer.render = render;
                      reject(new Error('Timed out waiting for the Pixi underlay frame.')); }, 2_000);
                    renderer.render = function(root, ...args) {
                      const result = render.call(this, root, ...args);
                      if (this.canvas.id!=='world-canvas') return result;
                      renderer.render = render;
                      clearTimeout(deadline);
                      const vertices = root.children[0].geometry.getBuffer('aPosition').data;
                      let ink = [], spacing = 0;
                      for (let i=0;i<vertices.length;i+=27) {
                        const points = [0,1,2].map(j=>[vertices[i+j*9],vertices[i+j*9+1]]);
                        if (Math.abs((points[1][0]-points[0][0])*(points[2][1]-points[0][1])
                          -(points[1][1]-points[0][1])*(points[2][0]-points[0][0])) < .001) continue;
                        const crosses = points.map((a,j)=>{
                          const b=points[(j+1)%3]; return (b[0]-a[0])*(y-a[1])-(b[1]-a[1])*(x-a[0]);
                        });
                        if (crosses.every(v=>v>=-.001)||crosses.every(v=>v<=.001)) {
                          ink=[vertices[i+2],vertices[i+3],vertices[i+4]]; spacing=vertices[i+7];
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
                settings => new Promise((resolve, reject) => {
                  const renderer = PIXI.WebGLRenderer.prototype;
                  const render = renderer.render;
                  const deadline = setTimeout(() => { renderer.render = render;
                    reject(new Error('Timed out waiting for the Pixi overlay frame.')); }, 2_000);
                  renderer.render = function(root, ...args) {
                    const result = render.call(this, root, ...args);
                    if (this.canvas.id !== 'world-canvas') return result;
                    renderer.render = render;
                    clearTimeout(deadline);
                    const node = window.__renderer.scene.nodes.find(node => node.id === 'one');
                    const query = new URLSearchParams(window.__renderer.query), scale = Number(query.get('scale'));
                    const center = [(node.x+node.width/2-Number(query.get('x')))*scale,
                      (node.y+node.height/2-Number(query.get('y')))*scale];
                    const height = Math.min(136,272/settings[1]), width = height*settings[1];
                    const sprite = root.children[1].children.map(sprite => ({sprite,
                      distance:Math.hypot(sprite.getBounds().x+sprite.getBounds().width/2-center[0],
                        sprite.getBounds().y+sprite.getBounds().height/2-center[1])}))
                      .sort((a,b) => a.distance-b.distance)[0]?.sprite;
                    if (!sprite) return reject(new Error('The focused machine housing was not submitted.'));
                    const spriteBounds = sprite.getBounds();
                    const housing = {width:spriteBounds.width*width/(width+24), height:spriteBounds.height*height/(height+24)};
                    const x = spriteBounds.x+(spriteBounds.width-housing.width)/2;
                    const y = spriteBounds.y+(spriteBounds.height-housing.height)/2;
                    const vertices = root.children[2].geometry.getBuffer('aPosition').data;
                    const points = [];
                    for (let i=0;i<vertices.length;i+=9) {
                      if (![164/255,99/255,20/255].every((v,j)=>Math.abs(v-vertices[i+2+j])<.001)) continue;
                      points.push([(vertices[i]-x)/housing.width,(vertices[i+1]-y)/housing.height]);
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
    @Tag("responsive")
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
        page.locator("#close-inspector").click();
        final String selected = page.locator("#selection-dock .dock-heading strong").textContent();
        page.evaluate("() => { state.world.focus('one'); }");
        page.waitForFunction("() => state.world.scene?.nodes.some(node => node.id === 'one') && Number(new URLSearchParams(state.world.query).get('scale')) >= 1");
        awaitScene();
        final var point = (List<?>) page.evaluate("""
                () => {
                  const node = state.world.scene.nodes.find(node => node.id === 'one');
                  const query = new URLSearchParams(state.world.query);
                  const scale = Number(query.get('scale'));
                  const stage = document.querySelector('#graph').getBoundingClientRect();
                  return [stage.x + (node.x + node.width / 2 - Number(query.get('x'))) * scale,
                    stage.y + (node.y + node.height / 2 - Number(query.get('y'))) * scale,
                    Math.min(scale, 1)];
                }
                """);
        final double x = ((Number) point.get(0)).doubleValue();
        final double y = ((Number) point.get(1)).doubleValue();
        final double scale = ((Number) point.get(2)).doubleValue();
        final double outsideX = x + 69 * scale;
        final double outsideY = y + 69 * scale;
        assertThat(page.evaluate("point => document.elementFromPoint(...point)?.closest('[data-world-id]') === null",
                List.of(outsideX, outsideY))).as("The outline probe must not click the separate text label").isEqualTo(true);
        page.mouse().click(outsideX, outsideY);
        page.waitForFunction("() => !state.editorController");
        assertThat(page.locator("#selection-dock .dock-heading strong").textContent()).isEqualTo(selected);
        page.mouse().click(x, y);
        page.waitForFunction("() => document.querySelector('[data-world-id=one]')?.getAttribute('aria-pressed') === 'true'");
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
        assertThat(page.evaluate("""
                () => {
                  const renderer = window.__renderer._renderer, root = window.__renderer._root;
                  const image = renderer.extract.pixels({target:root, frame:renderer.screen, resolution:renderer.resolution});
                  return image.pixels.some((value,index) => index % 4 === 3 && value > 0
                    && image.pixels[index-1] + image.pixels[index-2] + image.pixels[index-3] > 0);
                }
                """)).isEqualTo(true);
        assertThat(pageErrors).isEmpty();
        page.evaluate("() => { window.__traffic.restore(); window.__renderer.dispose(); }");
    }

    @Test
    void disposingAndRecreatingTheRendererRebuildsTrafficWithoutAnIdleTicker() {
        startRendererTraffic(10, 30_000);
        page.evaluate("""
                async () => {
                  const stage = document.querySelector('#graph');
                  const canvas = document.querySelector('#world-canvas');
                  window.__renderer.dispose();
                  window.__renderer = new RailixWorld(stage, {
                    linkAppearance: () => ({rate:10, selected:true}), motionActive: () => true
                  });
                  await window.__renderer.refresh();
                  window.__replacement = {replaced: canvas !== document.querySelector('#world-canvas'),
                    tickerStopped: !PIXI.Ticker.system.started && !PIXI.Ticker.system.autoStart};
                }
                """);
        page.waitForFunction("() => document.querySelector('#graph').dataset.trafficAnimated === 'true'");
        page.waitForFunction("() => window.__traffic.draws > 0");
        assertThat(page.evaluate("() => window.__replacement.replaced")).isEqualTo(true);
        assertThat(page.evaluate("() => window.__replacement.tickerStopped")).isEqualTo(true);
        assertThat(page.locator("#world-canvas").count()).isEqualTo(1);
        assertThat(pageErrors).isEmpty();
        page.evaluate("() => { window.__traffic.restore(); window.__renderer.dispose(); }");
    }

    @Test
    void visibleMachineTexturesAreReusedBoundedAndReleasedOnDisposal() {
        openProject(deepBranchProject(24));
        final Map<?, ?> before = (Map<?, ?>) page.evaluate("""
                async () => {
                  state.world.dispose();
                  const Texture = PIXI.Texture;
                  const tracking = {next:0, created:[], destroyed:[], faces:[]};
                  PIXI.Texture = class TrackingTexture extends Texture {
                    constructor(options) {
                      super(options);
                      this.__railixTextureId = ++tracking.next;
                      tracking.created.push(this.__railixTextureId);
                      const canvas = options.source.resource;
                      const pixel = canvas.getContext('2d').getImageData(canvas.width/2, canvas.height/2, 1, 1).data;
                      tracking.faces.push([pixel[0],pixel[1],pixel[2]]);
                    }
                    destroy(...args) {
                      tracking.destroyed.push(this.__railixTextureId);
                      return super.destroy(...args);
                    }
                  };
                  window.__textureTracking = {Texture, tracking};
                  window.textureTrackingSnapshot = () => {
                    const created = new Set(tracking.created), destroyed = new Set(tracking.destroyed);
                    return {created:created.size, destroyed:destroyed.size, residents:[...created]
                      .filter(id => !destroyed.has(id)).length,
                      labels:document.querySelector('#world-labels').childElementCount,
                      darkFaces:tracking.faces.every(pixel => pixel[0]+pixel[1]+pixel[2] < 180)};
                  };
                  window.__renderer = new RailixWorld(document.querySelector('#graph'));
                  await window.__renderer.refresh();
                  await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                  return window.textureTrackingSnapshot();
                }
                """);
        page.evaluate("() => { window.__renderer.repaint(); window.__renderer.repaint(); }");
        page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))");
        final Map<?, ?> repainted = (Map<?, ?>) page.evaluate("() => window.textureTrackingSnapshot()");
        page.evaluate("() => { window.__renderer.focus('step-23'); }");
        page.waitForFunction("() => window.__renderer.scene?.nodes.some(node => node.id === 'step-23')");
        page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))");
        final Map<?, ?> focused = (Map<?, ?>) page.evaluate("() => window.textureTrackingSnapshot()");
        page.evaluate("""
                () => {
                  window.__renderer.dispose();
                  PIXI.Texture = window.__textureTracking.Texture;
                  delete window.textureTrackingSnapshot;
                }
                """);
        final Map<?, ?> disposed = (Map<?, ?>) page.evaluate("""
                () => {
                  const tracking = window.__textureTracking.tracking;
                  const created = new Set(tracking.created), destroyed = new Set(tracking.destroyed);
                  return {residents:[...created].filter(id => !destroyed.has(id)).length};
                }
                """);
        assertThat(((Number) before.get("created")).intValue()).isPositive();
        assertThat(before.get("darkFaces")).as("Canvas-baked machine faces before Pixi uploads them").isEqualTo(true);
        assertThat(repainted).isEqualTo(before);
        assertThat(((Number) focused.get("residents")).intValue()).isLessThanOrEqualTo(((Number) focused.get("labels")).intValue());
        assertThat(disposed.get("residents")).isEqualTo(0);
        assertThat(pageErrors).isEmpty();
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
        // Initial fitting publishes a second viewport; measure only after that geometry is rendered.
        page.evaluate("""
                async () => {
                  await window.__renderer.refresh();
                  await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                }
                """);
        page.evaluate("""
                () => {
                  const renderer = PIXI.WebGLRenderer.prototype;
                  const render = renderer.render;
                  const upload = WebGL2RenderingContext.prototype.bufferSubData;
                  const probe = window.__traffic = {draws:0,uploads:0,labels:0,vertices:0};
                  const observer = new MutationObserver(records => probe.labels += records.length);
                  observer.observe(document.querySelector('#world-labels'), {childList:true,subtree:true,attributes:true});
                  renderer.render = function(...args) {
                    if (this.canvas.id === 'world-canvas') {
                      probe.draws++;
                      probe.vertices = Math.max(probe.vertices,
                        ...window.__renderer._meshes.map(mesh => mesh.geometry.getBuffer('aPosition').data.length / 9));
                    }
                    return render.apply(this, args);
                  };
                  WebGL2RenderingContext.prototype.bufferSubData = function(target,...args) {
                    if (this.canvas.id === 'world-canvas' && target === this.ARRAY_BUFFER) probe.uploads++;
                    return upload.call(this, target, ...args);
                  };
                  probe.restore = () => { observer.disconnect(); renderer.render = render; WebGL2RenderingContext.prototype.bufferSubData = upload; };
                }
                """);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.7, 1.0, 1.4, 3.0})
    void renderedBranchConnectionsHaveOrthogonalRunsAndShortRoundedCorners(final double zoom) {
        openProject(deepBranchProject(24));
        page.evaluate("scale => { state.world.zoom(scale); }", zoom);
        awaitScene();

        final Object geometry = page.evaluate("""
                () => new Promise((resolve, reject) => {
                  const renderer = PIXI.WebGLRenderer.prototype;
                  const render = renderer.render;
                  const deadline = setTimeout(() => { renderer.render = render;
                    reject(new Error('Timed out waiting for the Pixi underlay frame.')); }, 2_000);
                  renderer.render = function(root, ...args) {
                    const result = render.call(this, root, ...args);
                    if (this.canvas.id !== 'world-canvas') return result;
                    renderer.render = render;
                    clearTimeout(deadline);
                    const vertices = root.children[0].geometry.getBuffer('aPosition').data;
                    const rails = [];
                    const point = index => [vertices[index * 9], vertices[index * 9 + 1]];
                    const same = (a, b) => a.every((value, index) => Math.abs(value - b[index]) < .001);
                    for (let index = 0; index + 5 < vertices.length / 9; index += 3) {
                      const ink = [vertices[index * 9 + 2], vertices[index * 9 + 3], vertices[index * 9 + 4]];
                      if (!same(ink, [92/255,99/255,94/255])
                          || !same(point(index), point(index + 3))
                          || !same(point(index + 2), point(index + 4))) continue;
                      const a = point(index), b = point(index + 1), c = point(index + 2), d = point(index + 5);
                      const centerA = [(a[0] + d[0]) / 2, (a[1] + d[1]) / 2];
                      const centerB = [(b[0] + c[0]) / 2, (b[1] + c[1]) / 2];
                      rails.push(Math.abs(centerA[0] - centerB[0]) < .001 || Math.abs(centerA[1] - centerB[1]) < .001
                          || Math.hypot(centerA[0]-centerB[0],centerA[1]-centerB[1]) <= 8);
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

    private BoundingBox fittedAppBodyWithTriggerSelected() {
        openProject(deepBranchProject(4));
        awaitScene();
        page.locator("[data-node-id='command']").click();
        page.waitForFunction("""
                () => !state.editorController && document.querySelector('#inspector').dataset.selection === 'command'
                """);
        awaitScene();
        final BoundingBox label = page.locator("[data-node-id='app'] .world-symbol").boundingBox();
        assertThat(label).isNotNull();
        assertThat(page.evaluate("""
                point => document.elementFromPoint(point[0], point[1])?.id
                """, List.of(label.x + label.width / 2, label.y + label.height / 2)))
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
        final BoundingBox regionBox = region.locator(".world-symbol").boundingBox();
        final BoundingBox stepBox = step.locator(".world-symbol").boundingBox();
        assertThat(regionBox).as("Visible collapsed region body content").isNotNull();
        assertThat(stepBox).as("Visible ordinary Step body content").isNotNull();
        assertThat(stepBox.width).isGreaterThan(20);
        assertThat(stepBox.height).isBetween(18.0, 136.0);
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
                    warmup_navigation_requests=12 navigation_rounds=%d measured_navigation_requests=%d measured_pixi_frames=%d
                    viewport_request_p95_ms=%.3f
                    pixi_frame_cpu_submission_p95_ms=%.3f
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
                    Frame timing is the CPU animation callback submitting a real Pixi renderer frame, not GPU completion.
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
                  const renderer = PIXI.WebGLRenderer.prototype;
                  const render = renderer.render;
                  let renders = 0;
                  const probe = window.__railixWorldMeasurements = {
                    frames: [], glyphs: 0, segments: 0, labels: 0, dom: 0,
                    restore() {
                      window.requestAnimationFrame = requestFrame;
                      renderer.render = render;
                    }
                  };
                  renderer.render = function(...args) {
                    if (this.canvas.id === 'world-canvas') renders++;
                    return render.apply(this, args);
                  };
                  window.requestAnimationFrame = callback => requestFrame.call(window, timestamp => {
                    const before = renders;
                    const started = performance.now();
                    try {
                      return callback.call(window, timestamp);
                    } finally {
                      const elapsed = performance.now() - started;
                      if (renders !== before) {
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
                    mesh_vertex_buffer_bytes: state.world._meshes.map(mesh => mesh.geometry.getBuffer('aPosition').data.byteLength),
                    mesh_vertex_counts: state.world._meshes.map(mesh => mesh.geometry.getBuffer('aPosition').data.length / 9),
                    visible_sprites: state.world._stationSprites.size,
                    visible_textures: state.world._stationTextures.size
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
