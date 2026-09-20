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
    @ParameterizedTest
    @CsvSource({"hq,1280", "canvas,320", "classic,390"})
    void footerZoomAndRoundHomeStayReachable(final String variant, final int width) throws IOException {
        page.setViewportSize(width, 800);
        page.locator("#open-settings").click();
        if (variant.equals("classic")) page.locator("#theme-select").selectOption("classic/theme.css");
        else page.locator("#theme-variant").selectOption(variant);
        page.waitForFunction("variant => !state.themeError && document.querySelector('#graph').dataset.renderer === (variant==='canvas'?'canvas':'css') && (variant!=='classic' || document.documentElement.dataset.architecture==='classic')", variant);
        page.keyboard().press("Escape");

        assertThat(page.locator(".status-rail .canvas-tools #zoom-in, .status-rail .canvas-tools #zoom-out, .status-rail .canvas-tools #zoom-level").count()).isEqualTo(3);
        assertThat(page.locator(".topbar #toggle-music + #zoom-fit").count()).isEqualTo(1);
        final var home = page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Home").setExact(true));
        final BoundingBox homeBounds = home.boundingBox();
        assertThat(home.textContent()).isEmpty();
        assertThat(homeBounds.width).isCloseTo(homeBounds.height, within(.1));
        assertThat(home.evaluate("element => getComputedStyle(element).borderRadius")).isEqualTo("50%");
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
    void nativeSceneryReplacesCssFacesAtTheSameWorldLocations() throws IOException {
        final String variant="canvas";
        openProject(fourStepProject());
        page.evaluate("() => {state.creator.created_at=1;state.settings.reduced_motion=true;state.world.fit();}");
        awaitScene();
        page.waitForFunction("() => document.querySelector('.world-scenery') !== null");
        final String project = Files.readString(directory.resolve("project.json"));
        final Object plots = page.locator(".world-scenery").evaluateAll("""
                elements => {
                  const stage=document.querySelector('#graph').getBoundingClientRect();
                  return elements.map(element=>{
                    const marker=document.createElement('span');element.append(marker);
                    try {
                      for(const x of [50,25,75])for(const y of [50,25,75]){
                        marker.style.cssText=`position:absolute;left:${x}%;top:${y}%;width:0;height:0`;
                        const point=marker.getBoundingClientRect(),px=point.x-stage.x,py=point.y-stage.y;
                        if(px>4&&py>4&&px<stage.width-4&&py<stage.height-4)return {x:px-3,y:py-3,width:6,height:6};
                      }
                    }finally{marker.remove();}
                    return null;
                  }).filter(Boolean);
                }
                """);
        assertThat((List<?>) plots).isNotEmpty();
        page.evaluate("async variant=>{state.settings.theme_variant=variant;await applyTheme();}",variant);
        page.waitForFunction("() => document.querySelector('.world-raster') !== null");
        assertThat(page.locator(".world-scenery").count()).as("Scenery uses the same cached atlas as machines, not duplicate CSS faces").isZero();
        assertThat(page.evaluate("""
                plots => {
                  const canvas=document.querySelector('.world-raster'),r=canvas.width/canvas.clientWidth;
                  return plots.every(box=>{
                    const pixels=canvas.getContext('2d').getImageData(Math.floor(box.x*r),Math.floor(box.y*r),Math.ceil(box.width*r),Math.ceil(box.height*r)).data;
                    let visible=0;for(let i=3;i<pixels.length;i+=4)if(pixels[i]>20)visible++;
                    return visible>pixels.length/4*.03;
                  });
                }
                """,plots)).as("Native scenery occupies the same cleared world plots as the CSS variant").isEqualTo(true);
        final String source=(String)page.locator(".world-raster").evaluate("canvas=>canvas.toDataURL()");
        final Path screenshots=Files.createDirectories(Path.of("target/renderer-scenery"));
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve(variant+".png")));
        page.evaluate("async()=>{state.world.zoom(1.4);await state.world.refresh();state.world.fit();await state.world.refresh();}");
        awaitScene();
        assertThat(page.locator(".world-raster").getAttribute("data-scenery")).isNotEqualTo("0");
        page.evaluate("async()=>{state.settings.theme_variant='hq';await applyTheme();}");
        assertThat(page.locator(".world-scenery").count()).isPositive();
        page.evaluate("async variant=>{state.settings.theme_variant=variant;await applyTheme();}",variant);
        assertThat(page.locator(".world-raster").evaluate("canvas=>canvas.toDataURL()")).isEqualTo(source);
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(project);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"hq,1280", "canvas,1280", "hq,390", "canvas,390"})
    void doubleClickingGroupLabelEntersWithoutInspectorInterference(final String variant, final int width) {
        page.setViewportSize(width, width < 560 ? 720 : 800);
        openProject(deepBranchProject(128));
        page.locator("#open-settings").click();
        page.locator("#theme-variant").selectOption(variant);
        page.waitForFunction("variant => state.settings.theme_variant === variant && !state.themeError && document.querySelector('#graph').dataset.renderer === (variant==='canvas'?'canvas':'css')",variant);
        page.keyboard().press("Escape");
        page.locator("[data-group-region-label]").first().dblclick();
        page.waitForFunction("() => new URLSearchParams(state.world.query).has('inside')", null,
                new Page.WaitForFunctionOptions().setTimeout(5000));
        page.waitForFunction("() => document.querySelector('#zoom-level').textContent === '50%'");
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        page.locator("#leave-group").click();
        page.waitForFunction("() => !new URLSearchParams(state.world.query).has('inside')");
        page.locator("[data-group-region-label]").first().click();
        page.locator("[data-inspector-mode=groups]").dblclick(new Locator.DblclickOptions().setTimeout(5000));
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        assertThat(page.evaluate("() => state.inspectorMode")).isEqualTo("groups");
        assertThat(page.evaluate("() => new URLSearchParams(state.world.query).has('inside')")).isEqualTo(false);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"hq", "canvas"})
    void themeVariantsKeepGroupEntryZoomAndHomeBehavior(final String variant) throws IOException {
        openProject(deepBranchProject(128));
        page.locator("#open-settings").click();
        page.locator("#theme-variant").selectOption(variant);
        page.waitForFunction("variant => state.settings.theme_variant === variant && !state.themeError && document.querySelector('#graph').dataset.renderer === (variant.startsWith('canvas')?'canvas':'css')",variant);
        page.keyboard().press("Escape");
        page.locator("[data-group-region-label]").first().click();
        page.locator("#enter-region").click();
        page.waitForFunction("() => new URLSearchParams(state.world.query).has('inside') && document.querySelector('#graph').dataset.cameraMoving !== 'true'");
        page.waitForFunction("() => document.querySelector('#zoom-level').textContent === '50%'");
        final String inside=(String)page.evaluate("() => new URLSearchParams(state.world.query).get('inside')");
        assertThat(page.locator("[data-select-node=step-0]").isVisible()).isTrue();
        final Path images=Files.createDirectories(Path.of("target/renderer-navigation"));
        page.screenshot(new Page.ScreenshotOptions().setPath(images.resolve(variant+"-50.png")));
        page.locator("#zoom-in").click();
        page.waitForFunction("() => document.querySelector('#zoom-level').textContent === '70%'");
        page.screenshot(new Page.ScreenshotOptions().setPath(images.resolve(variant+"-70.png")));
        if (variant.startsWith("canvas")) assertThat(((Number) page.locator(".world-raster").evaluate("""
                canvas => {
                  const rgba=canvas.getContext('2d').getImageData(0,0,canvas.width,canvas.height).data;
                  let opaque=0;for(let i=3;i<rgba.length;i+=4)if(rgba[i]>200)opaque++;
                  return opaque/(canvas.width*canvas.height);
                }
                """)).doubleValue()).as("A group outline must not paint a giant solid stripe across the viewport").isLessThan(.2);
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
                  const pixels=[];for(let i=0;i<rgba.length;i+=4)if(rgba[i]===212&&rgba[i+1]===255&&rgba[i+2]===236)pixels.push(i/4);
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
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "railix.sprite.export", matches = "true")
    void exportThemeSpritesFromTheSharedBuildingPortrait() throws IOException {
        page.setViewportSize(1280, 900);
        openProject(fourStepProject());
        page.evaluate("async()=>{state.world.zoom(.08);await state.world.refresh();}");
        page.waitForFunction("() => new Set([...document.querySelectorAll('.world-scenery')].map(element=>element.dataset.variant)).size===3");
        page.evaluate("""
                () => {
                  window.sceneryTemplates=new Map([...document.querySelectorAll('.world-scenery')].map(element=>[element.dataset.variant,element.cloneNode(true)]));
                  state.world.dispose();
                  window.authoringWorld=new RailixWorld(document.querySelector('#graph'),{
                    appearance:node=>({symbol:node.id==='branch'?'branch':'',rate:10,color:'#53d8d1'})
                  });
                  const style=document.createElement('style');style.textContent=`
                    html,body,#graph {background:transparent!important;color-scheme:light}
                    #world-plane,#world-labels,#world-hud,.topbar {visibility:hidden}
                    #sprite-authoring .machine::before,#sprite-authoring .machine::after,
                    #sprite-authoring .machine-top,#sprite-authoring .machine-side,
                    #sprite-authoring .machine-light {visibility:hidden}
                    #sprite-authoring {position:fixed;left:340px;top:350px;width:136px;height:136px;
                      transform:var(--world-camera) scale3d(2,2,2);transform-origin:0 0;transform-style:preserve-3d}
                    #sprite-authoring>div {position:relative;width:136px;height:136px;transform-style:preserve-3d}
                  `;document.head.append(style);
                  const author=document.createElement('div');author.id='sprite-authoring';
                  author.append(document.createElement('div'));document.querySelector('#graph').append(author);
                  document.querySelector('#graph').dataset.trafficAnimated='true';
                }
                """);
        final Path output = Files.createDirectories(Path.of(System.getProperty("railix.sprite.output", "target/theme-sprites")));
        final JsonObject sprites = new JsonObject();
        for (final String role : List.of("app", "trigger", "step", "region", "branch", "scenery-ridge", "scenery-grove", "scenery-basin")) {
            final var geometry = (Map<String, Number>) page.evaluate("""
                    role=>{
                      const host=document.querySelector('#sprite-authoring>div');
                      const scenery=role.startsWith('scenery-');
                      let width=136,height=136;
                      if(scenery){
                        const element=sceneryTemplates.get(role.slice(8)).cloneNode(true),unit=parseFloat(element.style.getPropertyValue('--unit'));
                        for(const part of [element,...element.children])part.style.cssText=part.style.cssText.replace(/(-?[\\d.]+)px/g,(_,value)=>Number(value)/unit+'px');
                        element.style.setProperty('--unit','1');element.style.left=element.style.top='0';element.style.transform='none';
                        width=parseFloat(element.style.width);height=parseFloat(element.style.height);host.replaceChildren(element);
                      }else authoringWorld.portrait({id:role,kind:role==='branch'?'step':role,station_scale:1},host);
                      const marker=document.createElement('i');marker.style.cssText=`position:absolute;left:${scenery?0:68}px;top:${scenery?0:68}px;width:0;height:0`;
                      host.append(marker);const anchor=marker.getBoundingClientRect();marker.remove();
                      const m=new DOMMatrix(getComputedStyle(document.querySelector('#sprite-authoring')).transform);
                      const points=(scenery?[-8,width+16]:[-68,68]).flatMap(x=>(scenery?[-8,height+16]:[-68,68]).flatMap(y=>[0,112].map(z=>
                        [m.m11*x+m.m21*y+m.m31*z,m.m12*x+m.m22*y+m.m32*z])));
                      const x=Math.floor(anchor.x+Math.min(...points.map(p=>p[0])))-3;
                      const y=Math.floor(anchor.y+Math.min(...points.map(p=>p[1])))-3;
                      return {x,y,width:Math.ceil(anchor.x+Math.max(...points.map(p=>p[0]))-x)+3,
                        height:Math.ceil(anchor.y+Math.max(...points.map(p=>p[1]))-y)+3,
                        anchorX:anchor.x-x,anchorY:anchor.y-y};
                    }
                    """, role);
            final int count = role.equals("branch") ? 8 : 1;
            java.awt.image.BufferedImage strip = null;
            for (int frame = 0; frame < count; frame++) {
                page.locator("#sprite-authoring").evaluate("(element,time)=>element.getAnimations({subtree:true}).forEach(a=>{a.pause();a.currentTime=time;})", frame * 2400.0 / Math.max(1, count - 1));
                final byte[] bytes = page.screenshot(new Page.ScreenshotOptions().setOmitBackground(true).setClip(
                        geometry.get("x").doubleValue(), geometry.get("y").doubleValue(),
                        geometry.get("width").doubleValue(), geometry.get("height").doubleValue()));
                final var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
                if (strip == null) strip = new java.awt.image.BufferedImage(image.getWidth() * count, image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                final var graphics = strip.createGraphics();
                try { graphics.drawImage(image, frame * image.getWidth(), 0, null); } finally { graphics.dispose(); }
            }
            javax.imageio.ImageIO.write(strip, "png", output.resolve(role + ".png").toFile());
            final JsonObject sprite = new JsonObject();
            sprite.addProperty("file", role + ".png");
            sprite.addProperty("frames", count);
            sprite.addProperty("scale", 2);
            sprite.addProperty("anchorX", geometry.get("anchorX"));
            sprite.addProperty("anchorY", geometry.get("anchorY"));
            sprite.addProperty("period", 4800);
            sprites.add(role, sprite);
        }
        final JsonObject atlas = new JsonObject();
        atlas.addProperty("version", 1);
        atlas.add("sprites", sprites);
        Files.writeString(output.resolve("atlas.json"), atlas.toString());
        page.evaluate("() => authoringWorld.dispose()");
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
        final Path output=Files.createDirectories(Path.of("target/renderer-comparison"));
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
                            stations:canvas?Number(canvas.dataset.stations):document.querySelectorAll('#world-plane .machine').length,
                            scenery:canvas?Number(canvas.dataset.scenery):document.querySelectorAll('.world-scenery').length,
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
    void parcelsFollowTheWholeConnectionAcrossCorners() {
        startRendererTraffic(10, 30_000, true);
        final Locator routes = page.locator(".cargo-route");
        assertThat(routes.count()).as("One continuous parcel path, not a loop at every belt segment").isPositive();
        assertThat(routes.evaluateAll("""
                  routes => routes.some(route => route.firstElementChild.sheet.cssRules[0].cssRules.length >= 3)
                """)).isEqualTo(true);
        assertThat(routes.evaluateAll("""
                routes => routes.every(route => {
                  const item=route.querySelector('.cargo-item'), style=getComputedStyle(item);
                  return style.animationPlayState === 'running' && style.transformStyle === 'flat'
                    && !route.closest('.world-projection')
                    && style.backgroundImage.startsWith('conic-gradient(')
                    && getComputedStyle(item,'::before').content === 'none';
                })
                """)).isEqualTo(true);
        final Locator item = routes.first().locator(".cargo-item").first();
        final Object before = item.evaluate("item=>getComputedStyle(item).transform");
        page.waitForFunction("before=>getComputedStyle(document.querySelector('.cargo-route .cargo-item')).transform!==before", before);
        page.evaluate("() => { window.__parcels=[...document.querySelectorAll('.cargo-item')]; window.__renderer.zoom(1.02); }");
        page.evaluate("() => new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))");
        assertThat(page.evaluate("() => window.__parcels.every(item=>item.isConnected)"))
                .as("Panning and small zoom changes do not restart parcel lifetimes").isEqualTo(true);
        assertThat(pageErrors).isEmpty();
        page.evaluate("() => window.__renderer.dispose()");
    }

    @Test
    void projectedParcelsStayOnTheirBeltsAcrossCameraBatches() {
        startRendererTraffic(10, 30_000, true);
        page.evaluate("() => window.__renderer.focus('step-0', 1)");
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving === 'false'");
        assertThat((List<?>) page.evaluate("""
                async () => {
                  const failures=[];
                  for (const zoom of [1,1.2,.75,2.2]) {
                    window.__renderer.zoom(zoom);
                    document.querySelector('#graph').dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true}));
                    await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
                    const lines=[...document.querySelectorAll('.belt-run')].map(run=>[0,100].map(left=>{
                      const probe=document.createElement('i');
                      probe.style.cssText=`position:absolute;left:${left}%;top:50%;width:0;height:0`;
                      run.append(probe); const box=probe.getBoundingClientRect(); probe.remove(); return [box.x,box.y];
                    }));
                    const viewport=document.querySelector('#graph').getBoundingClientRect();
                    const casings=[...document.querySelectorAll('.machine')].map(machine=>machine.getBoundingClientRect());
                    const scale=new DOMMatrix(getComputedStyle(document.querySelector('.world-cargo')).transform).m11;
                    const parcels=[...document.querySelectorAll('.cargo-item')];
                    const animations=parcels.flatMap(item=>item.getAnimations());
                    animations.forEach(animation=>animation.pause());
                    let checked=0;
                    // Sample a complete cycle instead of an instant when every parcel may be inside a machine.
                    for (let phase=0;phase<16;phase++) {
                      animations.forEach(animation=>{
                        const timing=animation.effect.getTiming();
                        animation.currentTime=timing.delay+Number(timing.duration)*phase/16;
                      });
                      for (const item of parcels) {
                        const route=item.parentElement, style=getComputedStyle(route);
                        const unit=parseFloat(style.scale)*scale, origin=style.getPropertyValue('--cargo-origin').split(',').map(parseFloat);
                        const rect=item.getBoundingClientRect(), p=[rect.x-origin[0]*unit,rect.y-origin[1]*unit];
                        if (p[0]<viewport.x || p[0]>viewport.right || p[1]<viewport.y || p[1]>viewport.bottom) continue;
                        if (casings.some(box=>p[0]>=box.x && p[0]<=box.right && p[1]>=box.y && p[1]<=box.bottom)) continue;
                        checked++;
                        const distance=Math.min(...lines.map(([a,b])=>{
                          const dx=b[0]-a[0],dy=b[1]-a[1],t=Math.max(0,Math.min(1,((p[0]-a[0])*dx+(p[1]-a[1])*dy)/(dx*dx+dy*dy)));
                          return Math.hypot(p[0]-a[0]-t*dx,p[1]-a[1]-t*dy);
                        }));
                        if (distance>1) failures.push({zoom,phase,distance});
                      }
                    }
                    if (!checked) failures.push({zoom,error:'No exposed parcel tested'});
                  }
                  return failures;
                }
                """)).as("Screen-space parcel feet remain on the projected physical conveyors").isEmpty();
        assertThat(pageErrors).isEmpty();
        page.evaluate("() => window.__renderer.dispose()");
    }

    @Test
    void buildingFrameDoesNotShrinkWithItsFoundationShape() {
        openProject(choiceProject());
        page.evaluate("""
                async () => {
                  state.world.dispose(); window.foundation = 'rectangle';
                  window.__renderer = new RailixWorld(document.querySelector('#graph'), {
                    appearance: node => ({shape:window.foundation, symbol:node.id === 'choice' ? 'branch' : ''})
                  });
                  await window.__renderer.refresh(); window.__renderer.focus('choice', 1);
                }
                """);
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving === 'false'");
        final List<Object> dimensions = new ArrayList<>();
        for (final String shape : List.of("rectangle", "circle", "triangle", "diamond", "hexagon", "junction")) {
            page.evaluate("""
                    async shape => {
                      window.foundation = shape; window.__renderer.repaint();
                      await new Promise(requestAnimationFrame);
                    }
                    """, shape);
            dimensions.add(page.locator(".machine[data-station-id=choice]").evaluate("""
                    machine => {
                      const core = getComputedStyle(machine.querySelector('.machine-core'));
                      return [machine.style.width, machine.style.height, core.width, core.height];
                    }
                    """));
        }
        assertThat(dimensions).as("Different silhouettes use the same logical building frame").containsOnly(dimensions.getFirst());
        assertThat(pageErrors).isEmpty();
        page.evaluate("() => window.__renderer.dispose()");
    }

    @Test
    void portalSharesTheBuildingEnvelopeAndBranchHasAnArticulatedTongue() {
        openProject(choiceProject());
        page.evaluate("() => state.world.focus('command', 1)");
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving === 'false'");
        assertThat(page.locator(".machine[data-building=trigger]").evaluate("""
                machine => {
                  const base=getComputedStyle(machine), core=getComputedStyle(machine.querySelector('.machine-core'));
                  const size=parseFloat(base.getPropertyValue('--body-size'));
                  const upright=getComputedStyle(machine.querySelector('.building-volume'));
                  return Math.abs(parseFloat(core.width)-size)<.1 && Math.abs(parseFloat(core.height)-size)<.1
                    && new DOMMatrix(upright.transform).m43 <= size*.7;
                }
                """)).as("The portal shares the other buildings' body size and proportional height").isEqualTo(true);
        page.evaluate("() => state.world.focus('choice', 1)");
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving === 'false'");
        final Locator branch = page.locator(".machine[data-building=branch]");
        assertThat(branch.getAttribute("data-shape")).isEqualTo("junction");
        assertThat(branch.evaluate("machine=>getComputedStyle(machine.querySelector('.machine-top')).clipPath.match(/%/g).length"))
                .as("The T has eight corners, including the two shoulder recesses").isEqualTo(16);
        assertThat(branch.locator(".building-field").evaluate("field=>getComputedStyle(field,'::before').transform"))
                .as("The switch tongue has an extruded return face").isNotEqualTo("none");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void foundryTransportShowsReusableSolidCargoAndRaisedConveyorWalls() throws Exception {
        startRendererTraffic(10, 30_000, true);
        page.evaluate("() => window.__renderer.focus('step-0', 1)");
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving === 'false'");
        final Locator cargo = page.locator(".cargo-route[data-replay=true] .cargo-item");
        assertThat(cargo.count()).as("Recognizable parcels, not flat repeating marks").isPositive();
        final Object position = cargo.first().evaluate("element=>getComputedStyle(element).transform");
        page.waitForFunction("before=>getComputedStyle(document.querySelector('.cargo-item')).transform!==before", position);
        assertThat(cargo.evaluateAll("""
                items => items.every(item => {
                  const top=getComputedStyle(item);
                  return top.transformStyle === 'flat' && top.clipPath.startsWith('polygon(')
                    && top.backgroundImage.startsWith('conic-gradient(')
                    && getComputedStyle(item,'::before').content === 'none';
                })
                """)).isEqualTo(true);
        assertThat(page.locator(".belt-run").evaluateAll("""
                runs => runs.every(run => {
                  const wall=getComputedStyle(run,'::before');
                  return wall.content !== 'none' && wall.transform !== 'none';
                })
                """)).as("Conveyors retain raised return walls").isEqualTo(true);
        page.evaluate("() => { window.__cargo=[...document.querySelectorAll('.cargo-item')]; window.__renderer.zoom(1.01); }");
        page.evaluate("() => new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))");
        assertThat(page.evaluate("() => window.__cargo.every(item=>item.isConnected)"))
                .as("Camera-only navigation reuses the parcels").isEqualTo(true);
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target", "screenshots"))
                .resolve("foundry-transport.png")));
        page.evaluate("() => window.__renderer.focus('step-0', 3)");
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving === 'false'");
        page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target", "screenshots", "foundry-transport-detail.png")));
        assertThat(cargo.count()).as("Only the visible path owns parcel objects").isLessThan(100);
        page.evaluate("() => window.__renderer.dispose()");
    }

    @Test
    void buildingFamiliesReplaceIconPlatesAndTheirRaisedFacesRemainSelectable() throws Exception {
        openProject(choiceProject());
        for (final var entry : Map.of("app", "app", "command", "trigger", "choice", "branch").entrySet()) {
            page.evaluate("id => state.world.focus(id, 1)", entry.getKey());
            page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving === 'false'");
            awaitScene();
            final Locator machine = page.locator(".machine[data-station-id='" + entry.getKey() + "']");
            assertThat(machine.getAttribute("data-building")).isEqualTo(entry.getValue());
            final Locator roof = machine.locator(".building-volume").first();
            assertThat(roof.isVisible()).isTrue();
            assertThat(page.locator("[data-world-id='" + entry.getKey() + "'] .world-symbol").isVisible()).isFalse();
            final BoundingBox bounds = roof.boundingBox();
            page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target", "screenshots"))
                    .resolve("foundry-" + entry.getValue() + ".png")));
            page.mouse().click(bounds.x+bounds.width/2, bounds.y+bounds.height/2);
            page.waitForFunction("id => state.selection.id === id && !document.querySelector('#inspector').hidden", entry.getKey());
            assertThat(page.locator("#inspector").isVisible()).isTrue();
            page.locator("#close-inspector").click();
            final double width = roof.boundingBox().width;
            page.evaluate("() => state.world.zoom(1.2)");
            page.evaluate("() => new Promise(resolve => requestAnimationFrame(()=>requestAnimationFrame(resolve)))");
            assertThat(roof.boundingBox().width).isCloseTo(width*1.2, within(.03));
        }
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void groupCanopyUsesBoundedSymbolicContents() throws Exception {
        openProject(deepBranchProject(128));
        page.locator("[data-group-region-label]").first().click();
        page.locator("#close-inspector").click();
        final Locator group = page.locator(".machine[data-building=region]:not([data-expanded=true])").first();
        assertThat(group.locator(".building-volume").count()).isEqualTo(3);
        assertThat(group.locator(".building-canopy").isVisible()).isTrue();
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target", "screenshots"))
                .resolve("foundry-group.png")));
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void cameraNavigationReusesFactoryMaterials() {
        startRendererTraffic(10, 30_000);
        page.evaluate("() => window.__renderer.focus('step-0', 1)");
        page.waitForFunction("() => Number(document.querySelector('#graph').dataset.sceneScale) === 1 && document.querySelector('#graph').dataset.cameraMoving === 'false'");
        page.locator("#world-map").waitFor();
        page.evaluate("async () => { await window.__renderer.refresh(); await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))); }");
        final var pending = new ArrayList<com.microsoft.playwright.Route>();
        page.route("**/api/scene?*", pending::add);
        try {
            final JsonObject observation = JsonParser.parseString((String) page.evaluate("""
                    async () => {
                      const machine = document.querySelector('.machine[data-station-id=step-0]');
                      const before = machine.getBoundingClientRect();
                      const changes = [];
                      const watch = new MutationObserver(records => changes.push(...records));
                      watch.observe(document.querySelector('#world-plane'), {attributes:true,attributeFilter:['style'],subtree:true});
                      window.__renderer.zoom(1.02);
                      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                      watch.disconnect();
                      return JSON.stringify({ratio:machine.getBoundingClientRect().width/before.width,
                        materials:changes.filter(record => record.target.matches('.machine,.machine-side,.machine-core,.belt-run,.belt-junction,.belt-port')).length});
                    }
                    """)).getAsJsonObject();
            assertThat(observation.get("ratio").getAsDouble()).isCloseTo(1.02, within(.001));
            assertThat(observation.get("materials").getAsInt())
                    .as("Camera-only frames move the factory, not every material").isZero();
        } finally {
            page.unroute("**/api/scene?*");
            pending.forEach(com.microsoft.playwright.Route::resume);
            page.evaluate("() => window.__renderer.dispose()");
        }
    }

    @Test
    void zoomScalesDetailedMachinesWithTheCamera() {
        openProject(fourStepProject());
        page.evaluate("() => state.world.focus('one')");
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving === 'false'");
        awaitScene();
        final Locator machine = page.locator(".machine[data-station-id=one]");
        final double before = machine.boundingBox().width;
        final Locator side = machine.locator(".machine-side").first();
        final double sideHeight = side.boundingBox().height;
        final double coreOffset = machine.boundingBox().y - machine.locator(".machine-core").boundingBox().y;
        page.evaluate("() => state.world.zoom(1.4)");
        page.evaluate("() => new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))");
        assertThat(machine.boundingBox().width)
                .isCloseTo(before * 1.4, within(.01));
        assertThat(side.boundingBox().height).as("Camera scaling includes the casing's vertical depth")
                .isCloseTo(sideHeight * 1.4, within(.02));
        assertThat(machine.boundingBox().y - machine.locator(".machine-core").boundingBox().y)
                .as("The core and its symbol keep the same elevation relative to the casing")
                .isCloseTo(coreOffset * 1.4, within(.02));
        page.evaluate("async () => { await state.world.refresh(); await new Promise(requestAnimationFrame); }");
        assertThat(machine.boundingBox().width).as("Scene refresh retains the camera's rendered scale")
                .isCloseTo(before * 1.4, within(.01));
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
        final BoundingBox before = page.locator(".machine[data-station-id=one] .machine-core").boundingBox();
        page.evaluate("() => state.world.zoom(1.1)");
        page.locator("#graph").press("ArrowRight");
        page.evaluate("() => new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))");
        final BoundingBox moved = page.locator(".machine[data-station-id=one] .machine-core").boundingBox();
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
    void clickingARegionOpensItsInspectorWithLocalEnterAction() {
        openProject(deepBranchProject(128));
        page.locator("[data-group-region-label]").first().click();
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        assertThat(page.locator("#inspector #enter-region").count()).isEqualTo(1);
        assertThat(page.locator("#clear-selection, #world-hud #enter-region, #open-inspector").count()).isZero();
    }

    @Test
    void poweredIdleMachinesAnimateWithoutRepaintingTheirMaterials() {
        openProject(fourStepProject());
        page.waitForFunction("() => document.querySelector('#graph').dataset.powered === 'true'");
        page.waitForFunction("() => [...document.querySelectorAll('.machine')].some(machine => machine.getAnimations({subtree:true}).some(animation => animation.playState === 'running'))");
        final var animatedProperties = (List<?>) page.evaluate("""
                () => document.querySelectorAll('.machine').values().flatMap(machine =>
                  machine.getAnimations({subtree:true}).filter(animation => animation.playState === 'running')
                    .flatMap(animation => animation.effect.getKeyframes().flatMap(frame => Object.keys(frame))))
                  .filter(key => !['offset','computedOffset','easing','composite'].includes(key)).toArray()
                """);
        assertThat(animatedProperties).isNotEmpty().allMatch(property -> List.of("opacity", "transform").contains(property));
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void fixedCameraDoesNotAllocateHiddenCasingFaces() {
        openProject(fourStepProject());
        assertThat(page.locator(".machine-side").count()).isPositive();
        assertThat(page.locator(".machine-side").evaluateAll("""
                sides => {
                  const camera=new DOMMatrixReadOnly(getComputedStyle(document.querySelector('.world-projection')).transform);
                  return sides.every(side => camera.multiply(new DOMMatrixReadOnly(getComputedStyle(side).transform)).m33 > 0);
                }
                """)).as("The fixed-view factory omits back faces instead of allocating invisible 3D surfaces").isEqualTo(true);
    }

    @Test
    void cargoHasRaisedFacesAndLongBeltsOnlyPaintTheCameraVicinity() {
        startRendererTraffic(10, 10_000);
        final var belt = page.locator(".belt-run[data-active=true]").first();
        final var center = belt.boundingBox();
        page.evaluate("point => window.__renderer.zoom(32, ...point)", List.of(center.x + center.width / 2, center.y + center.height / 2));
        page.evaluate("() => new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))");
        final var cargo = page.locator(".cargo-route .cargo-item").first();
        assertThat(cargo.count()).isEqualTo(1);
        assertThat(cargo.evaluate("element=>getComputedStyle(element).backgroundImage")).asString().contains("conic-gradient");
        assertThat(cargo.evaluate("element=>getComputedStyle(element).clipPath")).asString().startsWith("polygon(");
        assertThat(page.locator(".belt-run").evaluateAll("""
                belts => belts.every(belt => {
                  const box=belt.getBoundingClientRect();
                  return Math.hypot(box.width,box.height) < 4*Math.hypot(innerWidth,innerHeight);
                })
                """)).isEqualTo(true);
        page.evaluate("() => window.__renderer.dispose()");
    }

    @Test
    void navigationPreservesContinuousBeltSurfacesAndTheirAnimations() {
        startRendererTraffic(10, 30_000, true);
        final Object material = page.locator(".belt-track").first().evaluate("track=>getComputedStyle(track,'::after').backgroundSize");
        page.evaluate("() => { window.__belts = [...document.querySelectorAll('.belt-run')]; window.__renderer.zoom(1.005); }");
        page.evaluate("() => new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))");
        assertThat(page.evaluate("() => window.__belts.length > 0 && window.__belts.every(belt=>belt.isConnected)"))
                .as("A camera change must not tear down unchanged conveyor surfaces").isEqualTo(true);
        assertThat(page.locator(".belt-track").first().evaluate("track=>getComputedStyle(track,'::after').backgroundSize"))
                .as("Camera transforms reuse belt materials rather than resizing and repainting their textures")
                .isEqualTo(material);
        page.evaluate("() => window.__renderer.dispose()");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void repeatedCameraRefreshesKeepChoiceConveyorsConnected() {
        openProject(choiceProject());
        awaitScene();
        final Object geometry = page.evaluate("""
                () => {
                  window.__beltGeometry = () => [...document.querySelectorAll('.belt-run')].map(run => {
                    const unit=Number(run.style.getPropertyValue('--unit'));
                    const matrix=new DOMMatrixReadOnly(getComputedStyle(run).transform);
                    return [Number((parseFloat(run.style.width)/unit).toFixed(3)),
                      Number(matrix.m11.toFixed(3)), Number(matrix.m12.toFixed(3))].join(':');
                  }).sort();
                  return window.__beltGeometry();
                }
                """);
        for (int i = 0; i < 8; i++) {
            page.evaluate("""
                    async factor => {
                      state.world.zoom(factor);
                      await state.world.refresh();
                      await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
                    }
                    """, i % 2 == 0 ? 1.005 : 1 / 1.005);
            assertThat(page.evaluate("() => window.__beltGeometry()"))
                    .as("A settled scene refresh cannot change physical conveyor runs").isEqualTo(geometry);
        }
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void lateralMultiBranchBeltsReachEveryDestinationAfterZoom() {
        final JsonObject project = JsonParser.parseString(nestedSwitchProject()).getAsJsonObject();
        final var links = project.getAsJsonArray("links");
        for (final var value : links.deepCopy()) {
            final var link = value.getAsJsonObject();
            if (!link.get("from").getAsString().startsWith("switch.")) continue;
            final String id = "result-" + link.get("from").getAsString().substring(7);
            project.getAsJsonArray("nodes").add(JsonParser.parseString("{\"id\":\"" + id
                    + "\",\"use\":\"railix.field-manipulation\",\"inputs\":{}}"));
            for (final var original : links) if (original.equals(value)) original.getAsJsonObject().addProperty("to", id);
            links.add(JsonParser.parseString("{\"from\":\"" + id + ".next\",\"to\":\"end\"}"));
        }
        openProject(project.toString());
        page.evaluate("() => state.world.focus('switch', 1)");
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving === 'false'");
        awaitScene();
        for (int i = 0; i < 12; i++) {
            page.evaluate("""
                    async factor => {
                      state.world.zoom(factor);
                      await state.world.refresh();
                      await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
                    }
                    """, i % 2 == 0 ? 1.25 : .8);
            final List<?> missing = (List<?>) page.evaluate("""
                    () => {
                      const query=new URLSearchParams(state.world.query), scale=Number(query.get('scale'));
                      const camera=[Number(query.get('x')),Number(query.get('y'))];
                      // Use a rendered station to locate the scene coordinate origin, not private renderer state.
                      const node=state.world.scene.nodes.find(node=>document.querySelector(`.machine[data-station-id="${node.id}"]`));
                      const machine=document.querySelector(`.machine[data-station-id="${node.id}"]`);
                      const origin=getComputedStyle(machine).translate.split(' ').map(parseFloat);
                      const meshScale=parseFloat(getComputedStyle(machine).scale);
                      const batch=new DOMMatrixReadOnly(getComputedStyle(machine.parentElement).transform);
                      const center=batch.transformPoint({x:origin[0]+parseFloat(machine.style.width)*meshScale/2,
                        y:origin[1]+parseFloat(machine.style.height)*meshScale/2});
                      const cx=node.x+node.width/2, cy=node.y+node.height/2;
                      camera[0]=cx-center.x/scale;
                      camera[1]=cy-center.y/scale;
                      const runs=[...document.querySelectorAll('.belt-run')];
                      return state.world.scene.links.filter(link=>link.from==='switch').filter(link=>{
                        const point=link.points[2].map((value,axis)=>((value+link.points[3][axis])/2-camera[axis])*scale);
                        const graph=document.querySelector('#graph'), projection=new DOMMatrixReadOnly(getComputedStyle(document.querySelector('.world-projection')).transform);
                        const x=point[0]-graph.clientWidth/2,y=point[1]-graph.clientHeight/2;
                        const sx=graph.clientWidth/2+projection.m11*x+projection.m21*y;
                        const sy=graph.clientHeight/2+projection.m12*x+projection.m22*y;
                        if(sx<0 || sy<0 || sx>graph.clientWidth || sy>graph.clientHeight) return false;
                        const local=batch.inverse().transformPoint({x:point[0],y:point[1]});
                        return !runs.some(run=>{
                          const style=getComputedStyle(run), matrix=new DOMMatrixReadOnly(style.transform);
                          const dx=local.x-parseFloat(run.style.left),dy=local.y-parseFloat(run.style.top);
                          const along=dx*matrix.m11+dy*matrix.m12, across=-dx*matrix.m12+dy*matrix.m11;
                          const unit=parseFloat(style.scale);
                          return Math.abs(matrix.m12)<.001 && Math.abs(across)<15*unit+.02
                            && along>=-.02 && along<=parseFloat(run.style.width)*unit+.02;
                        });
                      }).map(link=>link.outcome);
                    }
                    """);
            assertThat(missing).as("Every authored outlet must keep its horizontal destination leg").isEmpty();
        }
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void longGroupNavigationReportsCostsWithViewportCulling() {
        openProject(deepBranchProject(128));
        page.locator("[data-group-region-label]").first().click();
        page.locator("#enter-region").click();
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
                        machines:document.querySelectorAll('.machine:not([data-expanded=true])').length,
                        dom:document.querySelectorAll('#world-plane *').length};
                    }
                    """);
            System.out.printf("RAILIX_CHAIN_128_VIEWPORT frames=%s before=%s after=%s%n", frames, before, cdp.send("Performance.getMetrics"));
            assertThat(page.locator(".machine[data-kind=step]:not([data-expanded=true])").count()).isBetween(2, 127);
            assertThat(page.evaluate("() => state.world.scene.nodes.filter(node => node.kind === 'step').length")).isNotEqualTo(128);
        } finally {
            cdp.send("Performance.disable");
            cdp.detach();
        }
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void inspectorKeepsNavigationInItsHeaderAndRemovesDuplicateGroupSummary() {
        openProject(deepBranchProject(128));
        page.locator("[data-group-region-label]").first().click();
        assertThat(page.locator(".dock-heading small").textContent()).isEqualTo("Group");
        assertThat(page.locator(".selection-portrait .machine[data-building=region]").count()).isEqualTo(1);
        assertThat(page.locator("#selection-overview").textContent()).contains("128 Steps");
        assertThat(page.locator("#inspector-content").textContent()).doesNotContain("This section is not saved");
        assertThat(page.locator(".inspector-chrome #enter-region").count()).isEqualTo(1);
        assertThat(page.locator(".inspector-chrome #dock-focus").count()).isEqualTo(1);
        assertThat(page.locator(".inspector-chrome #enter-region").textContent()).isEmpty();
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
        assertThat(page.locator(".dock-heading small").textContent()).startsWith("Step");
        assertThat(page.locator(".selection-portrait .machine[data-station-id=one]").count()).isEqualTo(1);
        assertThat(page.locator(".selection-portrait").boundingBox().width).isBetween(60.0, 140.0);
        assertThat(page.locator(".inspector-chrome #dock-focus").getAttribute("aria-label")).isEqualTo("Focus");
        assertThat(page.locator("#inspector-footer #delete-step").getAttribute("aria-label")).startsWith("Delete ");
        assertThat(page.locator("#inspector-footer #delete-step").textContent()).isEmpty();
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
        assertThat(page.locator(".dock-heading small").textContent()).isEqualTo(type);
        assertThat(page.locator(".dock-heading h2").textContent()).isNotBlank();
        assertThat(page.locator(".selection-portrait .machine").getAttribute("data-building")).isEqualTo(building);
        assertThat(page.locator(".selection-portrait .machine").getAttribute("data-station-id")).isEqualTo(id);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void enteredAutomaticGroupStartsAtItsFirstStepWithLocalHalfScale() throws IOException {
        openProject(deepBranchProject(128));
        final String parentZoom = page.locator("#zoom-level").textContent();
        page.locator("[data-group-region-label]").first().click();
        page.locator("#enter-region").click();
        page.waitForFunction("() => new URLSearchParams(state.world.query).has('inside')");
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving !== 'true'");
        page.evaluate("() => new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))");
        page.waitForFunction("() => document.getAnimations().every(animation => animation.playState !== 'running' || animation.effect.getComputedTiming().iterations === Infinity)");
        assertThat(page.locator("#zoom-level").textContent()).isEqualTo("50%");
        assertThat(page.locator("[data-select-node=step-0]").isVisible()).isTrue();
        final double width = page.locator(".machine[data-station-id=step-0]").boundingBox().width;
        page.locator("#zoom-in").click();
        page.evaluate("() => new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))");
        assertThat(page.locator("#zoom-level").textContent()).isEqualTo("70%");
        assertThat(page.locator(".machine[data-station-id=step-0]").boundingBox().width)
                .isCloseTo(width * 1.4, org.assertj.core.data.Offset.offset(.5));
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target", "screenshots"))
                .resolve("world-128-entry.png")));
        final String inside = (String) page.evaluate("() => new URLSearchParams(state.world.query).get('inside')");
        page.locator("#zoom-out").click();
        page.locator("#zoom-out").click();
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving !== 'true'");
        page.evaluate("() => state.world.refresh()");
        assertThat(page.evaluate("() => new URLSearchParams(state.world.query).get('inside')")).isEqualTo(inside);
        assertThat(page.locator("#leave-group").isVisible()).isTrue();
        page.locator("#leave-group").click();
        page.waitForFunction("() => !new URLSearchParams(state.world.query).has('inside')");
        assertThat(page.locator("#zoom-level").textContent()).isEqualTo(parentZoom);
        assertThat(page.locator("#world-map").isVisible()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void hoveringTriggerOffersExamplesWithoutChangingSelection() {
        openProject(choiceProject());
        page.locator("#zoom-fit").click();
        awaitScene();
        final String selected = (String) page.evaluate("() => state.selection.id");
        page.locator("[data-select-node=command]").hover();
        page.locator("#dock-example").waitFor();
        assertThat(page.evaluate("() => state.selection.id")).isEqualTo(selected);
        page.locator("#dock-example").focus();
        // A native popup can leave the page's hover tree while its select still owns focus.
        page.mouse().move(1, 1);
        page.waitForTimeout(900);
        assertThat(page.locator("#dock-example").isVisible()).isTrue();
        page.locator("#dock-example").selectOption("-1");
        page.waitForFunction("() => document.querySelector('#graph').dataset.replaying === 'false'");
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        page.locator("#graph").focus();
        page.waitForFunction("() => document.querySelector('#trigger-example').hidden");
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
        page.waitForFunction("() => document.querySelector('.machine[data-station-id=one]')");
        final String width = page.locator(".machine[data-station-id=one]").evaluate("element=>element.style.width").toString();
        page.evaluate("() => { window.__aspect=2.625; window.__renderer.repaint(); }");
        page.evaluate("() => new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))");
        assertThat(page.locator(".machine[data-station-id=one]").evaluate("element=>element.style.width")).isEqualTo(width);
        page.evaluate("() => window.__renderer.dispose()");
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"zoom", "pan"})
    void cameraNavigationCannotAbruptlyResizeExistingStations(final String gesture) {
        openProject(deepBranchProject(128));
        awaitScene();
        page.evaluate("""
                () => state.world.focus(state.world.scene.nodes.find(node=>node.kind==='region' && node.count===128).id)
                """);
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving === 'false'");
        awaitScene();
        final String sizes = """
                () => Object.fromEntries([...document.querySelectorAll('.machine:not([data-expanded=true])')]
                  .map(element=>[element.dataset.stationId,element.getBoundingClientRect().width]))
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
                      const pairs=[...document.querySelectorAll('.machine:not([data-expanded=true])')]
                        .filter(element=>before[element.dataset.stationId])
                        .map(element=>({id:element.dataset.stationId,ratio:element.getBoundingClientRect().width/before[element.dataset.stationId]}));
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
    void selectedJunctionRimsMatchTheSelectedBelt() {
        openProject(choiceProject());
        selectWorldNode("command");
        page.locator("#dock-example").selectOption("0");
        page.evaluate("() => state.world.fit()");
        awaitScene();
        page.waitForFunction("() => document.querySelector('.belt-junction[data-selected=true]')");
        assertThat(page.locator(".belt-junction[data-selected=true]").evaluateAll("""
                junctions => junctions.every(junction => {
                  const style=getComputedStyle(junction);
                  const selected=getComputedStyle(document.querySelector('.belt-run[data-selected=true]')).borderTopColor;
                  return ['Top','Right','Bottom','Left'].every(side =>
                    style[`border${side}Color`] === 'rgba(0, 0, 0, 0)' || style[`border${side}Color`] === selected);
                })
                """)).isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void changingAMachineShapeKeepsItsBranchAxisInPlace() {
        openProject(choiceProject());
        page.evaluate("""
                async () => {
                  state.world.dispose();
                  window.__aspect=1;
                  window.__renderer=new RailixWorld(document.querySelector('#graph'), {
                    appearance:node=>({aspect:node.id==='choice'?window.__aspect:1})
                  });
                  await window.__renderer.refresh();
                  await window.__renderer.refresh();
                }
                """);
        final var junctions = page.locator(".belt-junction");
        junctions.first().waitFor();
        final Object positions = junctions.evaluateAll("items=>items.map(item=>[item.style.left,item.style.top])");
        page.evaluate("() => { window.__aspect=2.625; window.__renderer.repaint(); }");
        page.evaluate("() => new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))");
        assertThat(junctions.evaluateAll("items=>items.map(item=>[item.style.left,item.style.top])")).isEqualTo(positions);
        page.evaluate("() => window.__renderer.dispose()");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void collinearBeltSectionsKeepOneContinuousTreadPhase() {
        openProject(choiceProject());
        awaitScene();
        final List<?> discontinuities = (List<?>) page.evaluate("""
                () => {
                  const runs=[...document.querySelectorAll('.belt-run')].map(run=>{
                    const matrix=new DOMMatrixReadOnly(getComputedStyle(run).transform);
                    const axis=Math.abs(matrix.m11)>.5?0:1, direction=Math.sign(axis?matrix.m12:matrix.m11);
                    const coordinates=[parseFloat(run.style.left),parseFloat(run.style.top)];
                    const track=getComputedStyle(run.querySelector('.belt-track'),'::before');
                    return {axis,direction,fixed:coordinates[1-axis],start:coordinates[axis]*direction,
                      pitch:32*parseFloat(getComputedStyle(run).scale),
                      phase:parseFloat(track.backgroundPositionX)*parseFloat(getComputedStyle(run).scale)};
                  });
                  let comparisons=0;
                  const failures=runs.flatMap((a,i)=>runs.slice(i+1).filter(b=>a.axis===b.axis
                    && a.direction===b.direction && Math.abs(a.fixed-b.fixed)<.01 && Math.abs(a.pitch-b.pitch)<.01)
                    .flatMap(b=>{
                      comparisons++;
                      const remainder=((a.start+a.phase-b.start-b.phase)%a.pitch+a.pitch)%a.pitch;
                      return Math.min(remainder,a.pitch-remainder)<.02?[]:[{a,b,remainder}];
                    }));
                  return comparisons?failures:['No split transport was exercised'];
                }
                """);
        assertThat(discontinuities).isEmpty();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void cargoIsPaintedAboveDirectionMarkingsAndSocketsDoNotCoverTheTrack() {
        startRendererTraffic(10, 10_000, true);
        assertThat(page.locator(".belt-run[data-replay=true] .belt-track").first().evaluate("""
                track => {
                  const surface=getComputedStyle(track,'::after'),tread=getComputedStyle(track,'::before');
                  const route=document.querySelector('.cargo-route'), cargo=getComputedStyle(route.querySelector('.cargo-item'));
                  return cargo.backgroundImage !== 'none'
                    && track.closest('.world-projection').nextElementSibling === route.parentElement
                    && surface.transform===tread.transform;
                }
                """)).isEqualTo(true);
        assertThat(page.locator(".belt-port").evaluateAll("""
                ports => ports.every(port => getComputedStyle(port).backgroundColor === 'rgba(0, 0, 0, 0)'
                  && getComputedStyle(port,'::after').content === 'none')
                """)).isEqualTo(true);
        page.evaluate("() => window.__renderer.dispose()");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void factoryUsesCssSurfacesWithoutGraphicsRuntime() {
        openProject(fourStepProject());
        awaitScene();
        assertThat(page.locator("#graph canvas").count()).isZero();
        assertThat(page.evaluate("() => typeof window.PIXI")).isEqualTo("undefined");
        assertThat(page.locator("#world-plane .machine-top").count()).isPositive();
        assertThat(page.evaluate("() => getComputedStyle(document.querySelector('#world-plane')).transformStyle"))
                .isEqualTo("flat");
        assertThat(page.locator(".world-projection").evaluateAll("layers=>layers.length===2 && layers.every(layer=>getComputedStyle(layer).transformStyle==='preserve-3d')"))
                .isEqualTo(true);
        assertThat(page.locator("#world-plane .machine-side").count()).isPositive();
        assertThat(page.locator("#world-plane .belt-run").count()).isPositive();
        assertThat(page.locator("#world-plane .belt-port").count()).isPositive();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void junctionMarkingsStayPerpendicularToLongTransportRuns() {
        openProject(choiceProject());
        awaitScene();
        assertThat(page.locator("#world-plane .belt-run .belt-track").count()).isPositive();
        assertThat(page.locator("#world-plane .belt-track").evaluateAll("""
                tracks => tracks.every(track => {
                  const tread = getComputedStyle(track, '::before');
                  return tread.backgroundImage.includes('repeating-linear-gradient(90deg')
                    && getComputedStyle(track).transform === 'none';
                })
                """)).isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void choiceLateralElbowsHaveContinuousSurfacesWithoutAnExternalSplitter() {
        openProject(choiceProject());
        awaitScene();
        assertThat(page.locator("#world-plane .belt-junction").count()).isGreaterThanOrEqualTo(2);
        assertThat(page.locator("#world-plane .belt-junction").evaluateAll("""
                junctions => junctions.every(junction => {
                  const style = getComputedStyle(junction), mask = Number(junction.dataset.ports);
                  return ['Top','Right','Bottom','Left'].every((side,i) =>
                    style[`border${side}Color`].endsWith(', 0)') === Boolean(mask & 1<<i))
                    && style.backgroundColor !== 'rgba(0, 0, 0, 0)';
                })
                """)).isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"rectangle", "ellipse", "triangle", "diamond", "hexagon", "event", "storage", "subsystem"})
    void machineSymbolsStaySquareAndInsideProjectedShapes(final String shape) {
        openProject(fourStepProject());
        // Icon plates belong to the retained Classic theme, not the building family.
        page.evaluate("() => document.documentElement.style.setProperty('--world-buildings', '0')");
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
        page.waitForFunction("() => document.querySelector('[data-world-id=one] .world-symbol')?.getBoundingClientRect().width > 0");
        final BoundingBox icon = page.locator("[data-world-id=one] .world-symbol").boundingBox();
        final BoundingBox surface = page.locator("#world-plane .machine[data-station-id=one] .machine-top").boundingBox();
        assertThat(icon.width).isCloseTo(icon.height, within(.1));
        assertThat(icon.width).isGreaterThanOrEqualTo(16);
        assertThat(icon.x).isGreaterThanOrEqualTo(surface.x);
        assertThat(icon.y).isGreaterThanOrEqualTo(surface.y - surface.height / 2);
        assertThat(icon.x + icon.width).isLessThanOrEqualTo(surface.x + surface.width);
        assertThat(icon.y + icon.height).isLessThanOrEqualTo(surface.y + surface.height);
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
    void measuredDurationAppearsAsLabelledTextOutsideTheMachine() {
        openProject(fourStepProject());
        page.evaluate("""
                async () => {
                  state.world.dispose();
                  window.__renderer = new RailixWorld(document.querySelector('#graph'), {appearance: () => ({duration:'12 ms'})});
                  await window.__renderer.refresh(); window.__renderer.focus('one');
                }
                """);
        page.locator("[data-world-id=one] .world-duration").waitFor();
        assertThat(page.locator("[data-world-id=one] .world-duration").textContent()).contains("12 ms", "avg");
        final BoundingBox text = page.locator("[data-world-id=one] .world-duration").boundingBox();
        final BoundingBox machine = page.locator(".machine[data-station-id=one] .machine-top").boundingBox();
        assertThat(text.y >= machine.y + machine.height || text.y + text.height <= machine.y).isTrue();
        page.evaluate("() => window.__renderer.dispose()");
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
        assertThat(page.locator("#world-plane .belt-run").count()).isGreaterThan(10);
        assertThat(page.locator("#world-plane .belt-run .belt-track").count()).isEqualTo(page.locator("#world-plane .belt-run").count());
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void gridKeepsItsDominantSpacingAcrossAZoomBoundary() {
        openProject(fourStepProject());
        awaitScene();
        page.evaluate("() => state.world.zoom(.9999 / Number(new URLSearchParams(state.world.query).get('scale')))");
        awaitScene();
        final double before = ((Number) page.evaluate("() => parseFloat(getComputedStyle(document.querySelector('.world-floor')).getPropertyValue('--grid'))")).doubleValue();
        page.evaluate("() => state.world.zoom(1.0001 / Number(new URLSearchParams(state.world.query).get('scale')))");
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
        assertThat(page.locator("#graph").getAttribute("data-renderer")).isEqualTo("css");
        assertThat(page.evaluate("() => typeof window.PIXI")).isEqualTo("undefined");
        assertThat(page.locator(".building-volume").count()).isGreaterThan(2);
        assertThat(externalRequests).isEmpty();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void cssRendererWorksWhenCanvasContextsAreUnavailable() {
        page.addInitScript("""
                (() => {
                  HTMLCanvasElement.prototype.getContext = function(type, ...args) {
                    throw new Error('Canvas must not be used by the CSS renderer.');
                  };
                })()
                """);
        page.reload();
        awaitScene();
        assertThat(page.locator("#graph").getAttribute("data-renderer")).isEqualTo("css");
        assertThat(page.locator("#world-plane .machine").count()).isPositive();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void conveyorsHaveTransportWidthRatherThanDiagramLines() {
        openProject(fourStepProject());
        awaitScene();
        assertThat(page.locator("#world-plane .belt-run").evaluateAll("runs => runs.every(run => run.getBoundingClientRect().height >= 12)")).isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void stationaryConveyorChevronsPointForwardAlongTheOutgoingAppBelt() {
        openProject(fourStepProject());
        awaitScene();
        assertThat(page.locator("#world-plane .belt-run[data-active=false] .belt-track").evaluateAll("tracks => tracks.length > 0 && tracks.every(track => { const image = getComputedStyle(track, '::after').backgroundImage; return image.includes('linear-gradient(45deg') && image.includes('linear-gradient(-45deg'); })")).isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void denseBranchStationsHaveSeparateVisibleBodies() {
        openProject(nestedSwitchProject());
        awaitScene();
        final List<?> overlaps = (List<?>) page.evaluate("""
                () => {
                  const bodies = [...document.querySelectorAll('.machine-core')].map(element => element.getBoundingClientRect());
                  return bodies.flatMap((a, index) => bodies.slice(index + 1)
                    .filter(b => a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom)
                    .map(b => ({first: a.toJSON(), second: b.toJSON()})));
                }
                """);
        assertThat(page.locator(".machine-core").count()).isGreaterThanOrEqualTo(4);
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
        finishScene(response);
        awaitScene();
        final BoundingBox body = page.locator("#world-plane .machine[data-station-id=command] .machine-top").boundingBox();
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
        assertThat(page.locator("#world-plane .belt-run[data-active='true']").count()).isPositive();
        assertThat(page.locator(".belt-run[data-active=true] .belt-track").first().evaluate(
                "element => getComputedStyle(element, '::after').backgroundImage").toString())
                .contains("linear-gradient(45deg", "linear-gradient(-45deg");
        assertThat(page.locator("#world-plane .belt-run[data-selected='" + highlight + "']").count()).isPositive();
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
        assertThat(page.locator("#world-plane .machine[data-station-id='one'][data-changed='true'] .machine-top").count()).isEqualTo(1);
        assertThat(page.locator("#world-plane .machine[data-station-id='one'] .machine-top").evaluate("""
                element => getComputedStyle(element).clipPath.startsWith('polygon(')
                  && getComputedStyle(element, '::after').backgroundImage.includes('repeating-linear-gradient')
                """)).isEqualTo(true);
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
        final var point = (List<?>) page.evaluate("""
                () => {
                  const body = document.querySelector("#world-plane .machine[data-station-id='one'] .machine-top")
                    .getBoundingClientRect();
                  return [body.x + body.width / 2, body.y + body.height / 2, Math.min(body.width, body.height) / 2];
                }
                """);
        final double x = ((Number) point.get(0)).doubleValue();
        final double y = ((Number) point.get(1)).doubleValue();
        final BoundingBox outline = page.locator("#world-plane .machine[data-station-id=one] .machine-top").boundingBox();
        final double outsideX = outline.x + 1;
        final double outsideY = outline.y + 1;
        assertThat(page.evaluate("point => document.elementFromPoint(...point)?.closest('[data-world-id]') === null",
                List.of(outsideX, outsideY))).as("The outline probe must not click the separate text label").isEqualTo(true);
        page.mouse().click(outsideX, outsideY);
        page.waitForFunction("() => !state.editorController");
        assertThat(page.locator(".machine[data-station-id=one][data-selected=true]").count()).isZero();
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
                  window.__animation = document.getAnimations().find(animation => animation.playState === 'running');
                  window.__time = window.__animation.currentTime; }
                """);
        page.waitForFunction("() => window.__animation.currentTime > window.__time + 100");
        assertThat(page.evaluate("() => window.__parts.every(element => element.isConnected) && window.__parts.length === document.querySelectorAll('#world-plane *, #world-labels *').length")).isEqualTo(true);
        assertThat(page.locator("#world-plane .belt-run[data-active='true']").count()).isPositive();
        System.out.println("FOUNDRY_TRAFFIC rate=" + rate + " " + page.evaluate("""
                async () => {
                  const frames=[]; let previous=await new Promise(requestAnimationFrame);
                  for (let i=0;i<60;i++) {
                    const now=await new Promise(requestAnimationFrame); frames.push(now-previous); previous=now;
                  }
                  frames.sort((a,b)=>a-b);
                  return {p95:frames[56],max:frames[59],cargo:document.querySelectorAll('.cargo-item').length,
                    elements:document.querySelectorAll('#world-plane *').length};
                }
                """));
        assertThat(pageErrors).isEmpty();
        page.evaluate("() => window.__renderer.dispose()");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expiredObservationsStopTrafficButNotSelectedExampleReplay(final boolean replay) {
        startRendererTraffic(10, 1_000, replay);
        page.waitForFunction("() => document.querySelector('#graph').dataset.trafficAnimated === 'false'");
        assertThat(page.locator("#world-plane .belt-run[data-active='true'] .belt-track").evaluateAll("tracks => tracks.every(track => getComputedStyle(track, '::before').animationPlayState === 'running')")).isEqualTo(replay);
        assertThat(page.locator(".cargo-item").evaluateAll("items=>items.every(item=>getComputedStyle(item).animationPlayState === 'running')"))
                .isEqualTo(replay);
        page.evaluate("() => window.__renderer.dispose()");
    }

    @Test
    void browserMotionPreferenceDoesNotHideTraffic() {
        startRendererTraffic(10, 10_000);
        page.emulateMedia(new Page.EmulateMediaOptions().setReducedMotion(com.microsoft.playwright.options.ReducedMotion.REDUCE));
        assertThat(page.locator("#graph").getAttribute("data-traffic-animated")).isEqualTo("true");
        page.emulateMedia(new Page.EmulateMediaOptions().setReducedMotion(com.microsoft.playwright.options.ReducedMotion.NO_PREFERENCE));
        page.waitForFunction("() => document.querySelector('#graph').dataset.trafficAnimated === 'true'");
        page.evaluate("() => window.__renderer.dispose()");
    }

    @Test
    void disposingTrafficReleasesItsCssParts() {
        startRendererTraffic(10, 10_000);
        page.evaluate("() => window.__renderer.dispose()");
        assertThat(page.locator("#world-plane *").count()).isZero();
        assertThat(page.locator("#graph").getAttribute("data-traffic-animated")).isEqualTo("false");
    }

    @Test
    void disposedRendererDoesNotRetainItsDetachedScene() {
        startRendererTraffic(10, 10_000, true);
        page.evaluate("""
                () => {
                  window.__releasedParcel = new WeakRef(document.querySelector('.cargo-item'));
                  window.__releasedMachine = new WeakRef(document.querySelector('.machine'));
                  window.__renderer.dispose();
                }
                """);
        page.evaluate("() => new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))");
        final CDPSession cdp = context.newCDPSession(page);
        try {
            cdp.send("HeapProfiler.collectGarbage");
            assertThat(page.evaluate("() => !window.__releasedParcel.deref() && !window.__releasedMachine.deref()"))
                    .as("Keeping the disposed public renderer alive must not keep its old CSS scene alive").isEqualTo(true);
        } finally {
            cdp.detach();
        }
    }

    @Test
    void disposingAndRecreatingTheRendererRebuildsCssTraffic() {
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
        assertThat(page.locator("#world-plane .belt-run[data-active='true']").count()).isPositive();
        assertThat(page.evaluate("() => typeof window.PIXI")).isEqualTo("undefined");
        assertThat(pageErrors).isEmpty();
        page.evaluate("() => window.__renderer.dispose()");
    }

    @Test
    void visibleMachineCssPartsStayBoundedAndReleaseOnDisposal() {
        openProject(deepBranchProject(24));
        awaitScene();
        assertThat(page.locator("#world-plane .machine").count()).isLessThanOrEqualTo(256);
        assertThat(page.locator("#world-plane .machine .machine-top").count()).isPositive();
        page.evaluate("() => state.world.dispose()");
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
        // Initial fitting publishes a second viewport; inspect only the settled CSS state.
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

        assertThat(page.locator("#world-plane .belt-run").count()).isPositive();
        assertThat(page.locator("#world-plane .belt-run").evaluateAll("runs => runs.every(run => { const m = new DOMMatrixReadOnly(getComputedStyle(run).transform); return Math.min(Math.abs(m.m11), Math.abs(m.m12)) < .001 && parseFloat(run.style.width) > 0; })")).isEqualTo(true);
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

        assertMatchingFootprints(page.locator("#world-labels [data-kind='region']:not([data-region-group])").first(),
                page.locator("[data-node-id='filter']"));
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void automaticRegionKeepsItsBoundaryWhenEntered() {
        openProject(deepBranchProject(128));
        awaitScene();
        final String region = (String) page.evaluate("""
                () => state.world.scene.nodes.find(node => node.kind === 'region' && node.count === 128).id
                """);
        page.evaluate("id => state.world.focus(id)", region);
        page.waitForFunction("id => state.world.scene.nodes.some(node => node.id === id && node.expanded)", region);
        assertThat(page.locator(".machine[data-station-id='" + region + "'][data-expanded='true']").count()).isEqualTo(1);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void enteringAGroupRevealsItsChildrenWithABoundedSpatialTransition() {
        openProject(deepBranchProject(128));
        awaitScene();
        page.evaluate("""
                () => {
                  window.revealFrames = [];
                  window.watchReveal = true;
                  const record = () => {
                    for (const machine of document.querySelectorAll('.machine')) {
                      for (const animation of machine.getAnimations()) {
                        if (animation instanceof CSSAnimation) continue;
                        window.revealFrames.push({duration:animation.effect.getTiming().duration,
                          frames:animation.effect.getKeyframes()});
                      }
                    }
                    if (window.watchReveal) requestAnimationFrame(record);
                  };
                  requestAnimationFrame(record);
                  state.world.focus(state.world.scene.nodes.find(node => node.kind === 'region' && node.count === 128).id);
                }
                """);
        try {
            page.waitForFunction("() => window.revealFrames.length > 0");
            assertThat(page.evaluate("""
                    () => window.revealFrames.every(animation => animation.duration > 0 && animation.duration <= 250
                      && animation.frames.every(frame => frame.transform && !('opacity' in frame)))
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
                () => state.world.focus(state.world.scene.nodes.find(node => node.kind === 'region' && node.count === 128).id)
                """);
        page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving === 'false'");
        awaitScene();
        for (int index = 0; index < 6; index++) {
            page.locator("#zoom-in").click();
            awaitScene();
            assertThat(((Number) page.evaluate("""
                    () => Math.min(...[...document.querySelectorAll('.machine:not([data-expanded="true"])')]
                      .map(element => parseFloat(element.style.width)))
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
        final BoundingBox body = page.locator("#world-plane .machine[data-station-id='app'] .machine-top").boundingBox();
        assertThat(body).isNotNull();
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
        final BoundingBox regionBox = page.locator("#world-plane .machine[data-station-id='"
                + region.getAttribute("data-world-id") + "'] .machine-top").boundingBox();
        final BoundingBox stepBox = page.locator("#world-plane .machine[data-station-id='"
                + step.getAttribute("data-world-id") + "'] .machine-top").boundingBox();
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
            assertThat(page.locator(".machine[data-expanded=false]").evaluateAll("""
                    elements => {
                      const boxes = elements.map(element => ({id:element.dataset.stationId,
                        x:parseFloat(element.style.left),y:parseFloat(element.style.top),
                        w:parseFloat(element.style.width),h:parseFloat(element.style.height)}));
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
                () -> page.evaluate("() => state.world.fit()"));
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
                  const stations=[...plane.querySelectorAll('.machine:not([data-preview=true])')].map(item=>item.dataset.stationId);
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
                    visible_machines: document.querySelectorAll('#world-plane .machine').length,
                    visible_belts: document.querySelectorAll('#world-plane .belt-run').length,
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

    private static double percentile95(final List<Double> values) {
        final List<Double> sorted = values.stream().sorted().toList();
        return sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
    }
}
