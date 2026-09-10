"use strict";

(() => {
  // Native controls own input. Pixi's housekeeping runs with our frames, not an idle game loop.
  PIXI.extensions.remove(PIXI.EventSystem);
  PIXI.Ticker.system.autoStart = false;
  PIXI.Ticker.system.stop();
  const MAX_NODES = 2048;
  const MAX_SEGMENTS = 4096;
  const MAX_LABELS = 256;
  const MIN_SCALE = 2 ** -32;
  const MAX_SCALE = 2 ** 32;
  const zoomNumber = new Intl.NumberFormat(undefined, { notation: "compact", maximumSignificantDigits: 3 });
  const palette = {
    paper: [193 / 255, 196 / 255, 189 / 255, 1],
    panel: [224 / 255, 226 / 255, 215 / 255, 1],
    metal: [.43, .49, .49, 1],
    chassis: [.16, .20, .21, 1],
    light: [.89, .94, .91, 1],
    signal: [.35, .81, .44, 1],
    cyan: [.26, .92, .94, 1],
    shadow: [.08, .12, .13, .22],
    ink: [23 / 255, 27 / 255, 26 / 255, 1],
    rail: [92 / 255, 99 / 255, 94 / 255, 1],
    line: [177 / 255, 189 / 255, 183 / 255, 1],
    teal: [18 / 255, 109 / 255, 120 / 255, 1],
    red: [174 / 255, 47 / 255, 47 / 255, 1],
    amber: [164 / 255, 99 / 255, 20 / 255, 1]
  };
  const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
  const validBounds = value => value && [value.x, value.y, value.width, value.height].every(Number.isFinite)
    && value.width > 0 && value.height > 0;
  const color = value => /^#[\da-f]{6}$/i.test(value || "")
    ? [1, 3, 5].map(index => parseInt(value.slice(index, index + 2), 16) / 255).concat(1) : palette.ink;

  /** A bounded, camera-relative view of the Creator-owned graph scene. */
  class RailixWorld {
    static appearance = Object.freeze({shape: "rectangle", aspect: 1, roundness: 12});

    constructor(stage, callbacks = {}) {
      this._stage = stage;
      this._canvas = stage.querySelector("#world-canvas");
      this._labels = stage.querySelector("#world-labels");
      this._callbacks = callbacks;
      this._camera = { x: 0, y: 0, scale: 1 };
      this._size = { width: 1, height: 1 };
      this._scene = null;
      this._labelElements = new Map();
      this._listeners = new AbortController();
      this._vertices = new Float32Array(65536);
      this._vertexLength = 0;
      this._stationSprites = new Map();
      this._stationTextures = new Map();
      this._frame = 0;
      this._motionFrame = 0;
      this._moving = false;
      this._reducedMotion = matchMedia("(prefers-reduced-motion: reduce)");
      this._requestTimer = 0;
      this._requestId = 0;
      this._viewVersion = 0;
      this._initialized = false;
      this._disposed = false;
      this._lost = false;
      this._message = stage.querySelector("#world-error");
      if (!this._canvas || !this._labels) {
        this._fail("The graph canvas is missing. Reload Creator.");
        return;
      }
      stage.tabIndex = 0;
      stage.setAttribute("aria-label", "Flow canvas. Scroll to zoom, drag to pan. Use arrow keys to pan, plus and minus to zoom, Home to fit.");
      this._listen(this._canvas, "webglcontextlost", event => {
        event.preventDefault();
        this._lost = true;
        this._cancel();
        this._fail("The graphics context was lost. Waiting for the browser to restore it.");
      });
      this._listen(this._canvas, "webglcontextrestored", () => {
        this._lost = false;
        this._clearError();
        this.refresh();
      });
      this._ready = this._initializeGraphics();
      this._listen(stage, "wheel", event => this._wheel(event), { passive: false });
      this._listen(stage, "pointerdown", event => this._pointerDown(event));
      this._listen(stage, "pointermove", event => this._pointerMove(event));
      this._listen(stage, "pointerup", event => this._pointerEnd(event));
      this._listen(stage, "pointercancel", event => this._pointerEnd(event));
      this._listen(stage, "lostpointercapture", event => this._pointerEnd(event));
      this._listen(stage, "click", event => this._click(event));
      this._listen(stage, "dblclick", event => {
        const node = this._eventNode(event);
        if (node && node.kind !== "end") {
          event.preventDefault();
          event.stopPropagation();
          this.focus(node.id);
        }
      });
      this._listen(stage, "keydown", event => this._key(event));
      this._listen(window, "resize", () => this._resize());
      this._listen(document, "visibilitychange", () => {
        const moving = Boolean(this._flightFrame);
        this._cancelFlight();
        if (moving || !document.hidden) this._changedCamera(!document.hidden);
        else this.repaint();
      });
      this._listen(this._reducedMotion, "change", () => {
        const moving = Boolean(this._flightFrame);
        this._cancelFlight();
        if (moving) this._changedCamera();
        else this.repaint();
      });
      this._resizeObserver = new ResizeObserver(() => this._resize());
      this._resizeObserver.observe(stage);
      this._resize();
    }

    get scene() { return this._scene; }
    get query() { return this._sceneQuery || ""; }

    /** Fetch the latest scene for this camera; a newer request supersedes it. */
    async refresh() {
      if (!this._gl) await this._ready;
      if (this._disposed || this._lost || !this._gl || this._flightFrame) return this;
      clearTimeout(this._requestTimer);
      this._requestTimer = 0;
      this._request?.abort();
      const controller = new AbortController();
      this._request = controller;
      const requestId = ++this._requestId;
      const viewVersion = this._viewVersion;
      const focus = this._pendingFocus;
      const camera = this._camera;
      const query = new URLSearchParams({
        x: camera.x, y: camera.y,
        width: this._size.width / camera.scale, height: this._size.height / camera.scale,
        scale: camera.scale
      });
      if (focus) query.set("focus", focus);
      try {
        const response = await fetch(`/api/scene?${query}`, { signal: controller.signal, cache: "no-store" });
        if (!response.ok) throw new Error(`The graph scene could not be loaded (HTTP ${response.status}).`);
        const scene = await response.json();
        if (this._disposed || requestId !== this._requestId || viewVersion !== this._viewVersion) return this;
        this._validate(scene);
        if (focus && (!validBounds(scene.focus) || !Number.isFinite(scene.focus_min_scale) || scene.focus_min_scale < 0)) {
          throw new Error("The selected Step or group is not available in this project.");
        }
        this._scene = scene;
        this._sceneQuery = query.toString();
        this._sceneNodes = new Map(scene.nodes.map(node => [node.id, node]));
        this._stage.dataset.sceneNodeCount = String(scene.nodes.length);
        this._stage.dataset.sceneLinkCount = String(scene.links.length);
        this._stage.dataset.sceneRevision = String(scene.revision);
        this._clearError();
        this._callbacks.onScene?.(scene);
        if (!this._initialized || focus) {
          this._pendingFocus = null;
          if (!this._frameBounds(focus ? scene.focus : scene.bounds, !focus, focus ? scene.focus_min_scale : 0)) return this.refresh();
          return this;
        }
        this.repaint();
      } catch (error) {
        if (!controller.signal.aborted && !this._disposed && requestId === this._requestId && viewVersion === this._viewVersion) {
          this._pendingFocus = null;
          this._fail(error.message || "The graph scene could not be loaded.");
        }
      }
      return this;
    }

    /** Rebuild appearance once; only fresh, nonzero traffic schedules motion frames. */
    repaint() {
      cancelAnimationFrame(this._motionFrame);
      this._motionFrame = 0;
      if (!this._frame && !this._disposed && !this._lost && this._gl) {
        this._frame = requestAnimationFrame(() => {
          this._frame = 0;
          this._draw();
        });
      }
      return this;
    }

    fit() {
      if (this._disposed) return this;
      this._cancelFlight();
      this._pendingFocus = null;
      if (this._scene) {
        if (this._frameBounds(this._scene.bounds, true)) return this;
      }
      else this._initialized = false;
      this.refresh();
      return this;
    }

    /** Resolve a stable Step or region identifier, including outside the current scene. */
    focus(id) {
      if (!this._disposed && id) {
        this._cancelFlight();
        this._pendingFocus = id;
        this._viewVersion++;
        this.refresh();
      }
      return this;
    }

    /** Cancel an unfinished focus request while retaining the current camera. */
    cancelFocus() {
      if (!this._disposed && (this._pendingFocus || this._flightFrame)) {
        this._cancelFlight();
        this._pendingFocus = null;
        this._viewVersion++;
        this.refresh();
      }
      return this;
    }

    /** Show the prospective insertion connection. Null clears it; nothing is persisted or executed. */
    preview(placement) {
      if (this._disposed || this._placement === placement) return this;
      this._placement = placement;
      return this.repaint();
    }

    /** Attach an existing DOM control to a visible station; an empty id hides it without replacing its content. */
    anchor(id, element) {
      if (this._disposed || (id ? this._anchor?.id === id && this._anchor.element === element
        : !this._anchor && element.hidden)) return this;
      if (this._anchor) this._anchor.element.hidden = true;
      this._anchor = id ? {id,element} : null;
      element.hidden = !id;
      return this.repaint();
    }

    zoom(factor, clientX, clientY) {
      if (this._disposed || !Number.isFinite(factor) || factor <= 0) return this;
      this._cancelFlight();
      const rect = this._stage.getBoundingClientRect();
      const x = clientX === undefined ? this._size.width / 2 : clientX - rect.left;
      const y = clientY === undefined ? this._size.height / 2 : clientY - rect.top;
      const camera = this._camera;
      const scale = clamp(camera.scale * factor, MIN_SCALE, MAX_SCALE);
      camera.x += x / camera.scale - x / scale;
      camera.y += y / camera.scale - y / scale;
      camera.scale = scale;
      this._initialized = true;
      this._changedCamera();
      return this;
    }

    dispose() {
      if (this._disposed) return this;
      this._disposed = true;
      this._cancel();
      this._listeners.abort();
      this._resizeObserver?.disconnect();
      if (this._pan && this._stage.hasPointerCapture(this._pan.id)) this._stage.releasePointerCapture(this._pan.id);
      this._stage.classList.remove("panning");
      this._pan = null;
      this._labelElements.clear();
      if (this._anchor) this._anchor.element.hidden = true;
      this._anchor = null;
      this._labels?.replaceChildren();
      this._clearError();
      this._releaseGraphics();
      if (this._canvas?.isConnected) this._canvas.replaceWith(this._canvas.cloneNode(false));
      if (this._canvas) this._canvas.width = this._canvas.height = 1;
      this._gl = null;
      this._scene = null;
      this._sceneNodes = null;
      this._placement = null;
      this._request = null;
      this._callbacks = {};
      this._vertices = new Float32Array(0);
      return this;
    }

    _listen(target, name, listener, options = {}) {
      target.addEventListener(name, listener, { ...options, signal: this._listeners.signal });
    }

    _cancel() {
      this._cancelFlight();
      this._requestId++;
      this._request?.abort();
      clearTimeout(this._requestTimer);
      cancelAnimationFrame(this._frame);
      cancelAnimationFrame(this._motionFrame);
      this._requestTimer = this._frame = this._motionFrame = 0;
    }

    _fail(message) {
      if (this._message) {
        this._message.textContent = message;
        this._message.hidden = false;
      }
      this._stage.dataset.sceneError = message;
      this._callbacks.onError?.(message);
    }

    _clearError() {
      if (this._message) {
        this._message.hidden = true;
        this._message.textContent = "";
      }
      delete this._stage.dataset.sceneError;
    }

    _validate(scene) {
      if (!validBounds(scene.bounds) || !Array.isArray(scene.nodes) || !Array.isArray(scene.links)
        || scene.nodes.length > MAX_NODES || scene.links.length > MAX_SEGMENTS) {
        throw new Error("The graph scene is invalid or exceeds its display budget.");
      }
      const ids = new Set();
      let segments = 0;
      for (const node of scene.nodes) {
        if (typeof node.id !== "string" || ids.has(node.id) || !validBounds(node)) throw new Error("The graph scene contains an invalid station.");
        ids.add(node.id);
      }
      for (const link of scene.links) {
        if (!Array.isArray(link.points) || link.points.length < 2
          || link.points.some(point => !Array.isArray(point) || point.length !== 2 || !point.every(Number.isFinite))) {
          throw new Error("The graph scene contains an invalid connection.");
        }
        segments += link.points.length - 1;
      }
      if (segments > MAX_SEGMENTS) throw new Error("The graph scene exceeds its connection budget.");
    }

    _resize() {
      if (this._disposed || !this._gl) return;
      const width = Math.max(1, this._stage.clientWidth);
      const height = Math.max(1, this._stage.clientHeight);
      const maximum = this._gl.getParameter(this._gl.MAX_RENDERBUFFER_SIZE);
      const dpr = Math.min(window.devicePixelRatio || 1, 2, maximum / width, maximum / height);
      if (width === this._size.width && height === this._size.height && dpr === this._dpr) return;
      this._cancelFlight();
      this._size = { width, height };
      this._dpr = dpr;
      this._renderer.resize(width, height, dpr);
      this._changedCamera();
    }

    _frameBounds(bounds, fit = false, minimumScale = 0) {
      const stage = this._stage.getBoundingClientRect();
      const header = document.querySelector(".topbar")?.getBoundingClientRect();
      const dock = document.querySelector("#selection-dock")?.getBoundingClientRect();
      const top = header?.height ? clamp(header.bottom - stage.top, 0, this._size.height) : 0;
      const bottom = dock?.height ? clamp(dock.top - stage.top, top, this._size.height) : this._size.height;
      const scale = clamp(Math.max(minimumScale, Math.min(Math.max(1, this._size.width - 96) / bounds.width,
        Math.max(1, bottom - top - 96) / bounds.height)), MIN_SCALE, fit ? 1 : MAX_SCALE);
      const target = {
        x: bounds.x + bounds.width / 2 - this._size.width / scale / 2,
        y: bounds.y + bounds.height / 2 - (top + bottom) / scale / 2, scale
      };
      this._cancelFlight();
      if (this._initialized && !this._reducedMotion.matches && !document.hidden) {
        this._viewVersion++;
        this._request?.abort();
        const from = this._camera, started = performance.now();
        clearTimeout(this._requestTimer);
        this._requestTimer = 0;
        this._stage.dataset.cameraMoving = "true";
        const move = time => {
          const progress = clamp((time - started) / 180, 0, 1);
          const fraction = 1 - (1 - progress) ** 3;
          this._camera = {x: from.x + (target.x - from.x) * fraction,
            y: from.y + (target.y - from.y) * fraction,
            scale: Math.exp(Math.log(from.scale) + Math.log(target.scale / from.scale) * fraction)};
          this._changedCamera(false);
          if (progress < 1 && !document.hidden && !this._reducedMotion.matches) this._flightFrame = requestAnimationFrame(move);
          else {
            this._camera = target;
            this._cancelFlight();
            this._changedCamera();
            void this.refresh();
          }
        };
        this._flightFrame = requestAnimationFrame(move);
        return true;
      }
      this._camera = target;
      this._initialized = true;
      this._changedCamera();
      return false;
    }

    _cancelFlight() {
      cancelAnimationFrame(this._flightFrame);
      this._flightFrame = 0;
      this._stage.dataset.cameraMoving = "false";
    }

    _changedCamera(refresh = true) {
      this._pendingFocus = null;
      this._viewVersion++;
      this.repaint();
      if (refresh && !this._requestTimer && !this._disposed && !this._lost) {
        this._requestTimer = setTimeout(() => this.refresh(), 80);
      }
      const level = this._stage.querySelector("#zoom-level");
      if (level) level.textContent = `${zoomNumber.format(this._camera.scale * 100)}%`;
      this._stage.dataset.sceneScale = String(this._camera.scale);
    }

    _wheel(event) {
      if (event.target.closest(".canvas-tools, [data-world-overlay]")) return;
      event.preventDefault();
      const unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? this._size.height : 1;
      if (event.shiftKey && !event.ctrlKey && !event.metaKey) {
        this._cancelFlight();
        this._camera.x += event.deltaX * unit / this._camera.scale;
        this._camera.y += event.deltaY * unit / this._camera.scale;
        this._initialized = true;
        this._changedCamera();
      } else this.zoom(Math.exp(clamp(-event.deltaY * unit * .002, -2, 2)), event.clientX, event.clientY);
    }

    _pointerDown(event) {
      if (this._pan || (event.button !== 0 && event.button !== 1)
        || (event.button === 0 && event.target.closest("button, input, select, textarea, a, [data-world-overlay]"))) return;
      event.preventDefault();
      const moving = Boolean(this._flightFrame);
      this._cancelFlight();
      if (moving) this._changedCamera();
      this._stage.focus({ preventScroll: true });
      this._pan = { id: event.pointerId, x: event.clientX, y: event.clientY, dragging: false };
      this._stage.setPointerCapture(event.pointerId);
    }

    _pointerMove(event) {
      if (this._pan?.id !== event.pointerId) return;
      const dx = event.clientX - this._pan.x;
      const dy = event.clientY - this._pan.y;
      if ((!dx && !dy) || (!this._pan.dragging && Math.abs(dx) + Math.abs(dy) <= 4)) return;
      this._pan.dragging = true;
      this._pan.x = event.clientX;
      this._pan.y = event.clientY;
      this._camera.x -= dx / this._camera.scale;
      this._camera.y -= dy / this._camera.scale;
      this._initialized = true;
      this._stage.classList.add("panning");
      this._changedCamera();
    }

    _pointerEnd(event) {
      if (this._pan?.id !== event.pointerId) return;
      if (this._pan.dragging) this._suppressClickUntil = performance.now() + 250;
      this._pan = null;
      this._stage.classList.remove("panning");
      if (this._stage.hasPointerCapture(event.pointerId)) this._stage.releasePointerCapture(event.pointerId);
    }

    _eventNode(event) {
      if (!this._scene || event.target.closest(".canvas-tools, [data-world-overlay]")) return null;
      const id = event.target.closest("[data-world-id]")?.dataset.worldId;
      if (id) return this._scene.nodes.find(node => node.id === id);
      if (event.target !== this._canvas && event.target !== this._stage && event.target !== this._labels) return null;
      const rect = this._stage.getBoundingClientRect();
      const x = event.clientX - rect.left;
      const y = event.clientY - rect.top;
      let hit = null;
      for (const node of this._scene.nodes) {
        const look = this._callbacks.appearance?.(node) || {};
        const box = this._screenBounds(node, look);
        if (x < box.x || y < box.y || x > box.x + box.width || y > box.y + box.height) continue;
        const contour = this._contour(box, node.expanded ? {roundness: 0} : look);
        if (contour.every((a, index) => {
          const b = contour[(index + 1) % contour.length];
          return (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]) >= -.01;
        })
          && (!hit || node.width * node.height < hit.width * hit.height)) hit = node;
      }
      return hit;
    }

    _click(event) {
      const node = this._eventNode(event);
      if (!node) return;
      event.stopPropagation();
      if (performance.now() < (this._suppressClickUntil || 0) || node.kind === "end") return;
      if (node.kind === "region") {
        if (this._callbacks.selectGroup) this._callbacks.selectGroup(node.group, node.id);
        else this.focus(node.id);
      } else this._callbacks.selectNode?.(node.id);
    }

    _key(event) {
      if (event.target.closest("input, select, textarea, [data-world-overlay]")
        || event.target.closest("button") && !event.target.closest("[data-world-id]")) return;
      if ((event.key === "Enter" || event.key === " ") && event.target.closest("[data-world-id]")) {
        event.stopPropagation();
        return;
      }
      if (event.key === "Escape") {
        this._stage.focus({ preventScroll: true });
        return;
      }
      const distance = 80 / this._camera.scale;
      const directions = { ArrowLeft: [-distance, 0], ArrowRight: [distance, 0], ArrowUp: [0, -distance], ArrowDown: [0, distance] };
      if (directions[event.key]) {
        this._cancelFlight();
        const [x, y] = directions[event.key];
        this._camera.x += x;
        this._camera.y += y;
        this._initialized = true;
        this._changedCamera();
      } else if (event.key === "+" || event.key === "=") this.zoom(1.25);
      else if (event.key === "-") this.zoom(.8);
      else if (event.key === "Home") this.fit();
      else return;
      event.preventDefault();
      event.stopPropagation();
    }

    async _initializeGraphics() {
      let renderer;
      try {
        const context = this._canvas.getContext("webgl2", {alpha:false,antialias:true,powerPreference:"low-power"});
        if (!context) throw new Error("This browser cannot open the WebGL2 flow canvas. Enable hardware acceleration or use a WebGL2-capable browser.");
        renderer = new PIXI.WebGLRenderer();
        await renderer.init({canvas: this._canvas, context, antialias: true, background: palette.paper,
          backgroundAlpha: 1, powerPreference: "low-power", preferWebGLVersion: 2,
          autoDensity: true, resolution: Math.min(devicePixelRatio || 1, 2)});
        if (this._disposed) { renderer.destroy(false); return; }
        this._renderer = renderer;
        this._gl = renderer.gl;
        this._root = new PIXI.Container({eventMode: "none"});
        this._machines = new PIXI.Container({eventMode: "none"});
        this._shader = PIXI.Shader.from({gl: {
          vertex: `#version 300 es
              in vec2 aPosition; in vec4 color; in vec3 traffic; uniform vec2 viewport; out vec4 ink; out vec3 rail;
              void main() { gl_Position = vec4(aPosition / viewport * vec2(2., -2.) + vec2(-1., 1.), 0., 1.); ink = color; rail = traffic; }`,
          fragment: `#version 300 es
              precision highp float; in vec4 ink; in vec3 rail; uniform float travel; uniform float unit; out vec4 pixel;
              void main() {
                pixel = ink;
                if (rail.y != 0.) {
                  bool moving = rail.y > 0. && travel >= 0.;
                  float pitch = abs(rail.y);
                  float u = max(unit, .001);
                  float seam = abs(mod(rail.x, 12.*u) - 6.*u);
                  pixel.rgb = mix(ink.rgb * .55, vec3(.38,.43,.44), 1.-smoothstep(.3*u,1.1*u,seam));
                  float arrow = abs(mod(rail.x+abs(rail.z)*.85, 42.*u)-21.*u);
                  float guide = (1.-smoothstep(.5*u,1.4*u,arrow)) * (1.-smoothstep(4.*u,6.*u,abs(rail.z)));
                  pixel.rgb = mix(pixel.rgb, ink.rgb+.18, guide*.6);
                  if (moving) {
                    vec2 q = abs(vec2(mod(rail.x-travel,pitch)-pitch*.5,rail.z))-vec2(4.5,5.5)*u;
                    float edge = length(max(q,0.))+min(max(q.x,q.y),0.)-1.3*u;
                    float chip = 1.-smoothstep(-.6*u,.6*u,edge);
                    float rim = 1.-smoothstep(-2.*u,-.8*u,edge);
                    vec3 body = mix(vec3(.77,.91,.92),vec3(.30,.63,.68),rim);
                    body += .08 * clamp(-rail.z/(6.*u),-1.,1.);
                    pixel.rgb = mix(pixel.rgb,body,chip);
                  }
                }
                pixel.rgb *= pixel.a;
              }`
        }, resources: {world: {
          viewport: {value: new Float32Array([1,1]), type: "vec2<f32>"},
          travel: {value: -1, type: "f32"}, unit: {value: 1, type: "f32"}
        }}});
        this._meshes = [0,1].map(() => {
          const buffer = new PIXI.Buffer({data: new Float32Array(27), shrinkToFit: false,
            usage: PIXI.BufferUsage.VERTEX | PIXI.BufferUsage.COPY_DST});
          const geometry = new PIXI.Geometry({attributes: {
            aPosition: {buffer, format:"float32x2", stride:36, offset:0},
            color: {buffer, format:"float32x4", stride:36, offset:8},
            traffic: {buffer, format:"float32x3", stride:36, offset:24}
          }, topology:"triangle-list"});
          return new PIXI.Mesh({geometry, shader:this._shader});
        });
        this._root.addChild(this._meshes[0],this._machines,this._meshes[1]);
        this._stage.dataset.renderer = "pixi";
        this._clearError();
        this._resize();
        this.refresh();
      } catch (error) {
        if (renderer && !this._renderer) renderer.destroy(false);
        this._releaseGraphics();
        if (!this._disposed) this._fail(error.message || "The graphics renderer could not start.");
      }
    }

    _releaseGraphics() {
      this._meshes?.forEach(mesh => mesh.geometry?.destroy(true));
      this._root?.destroy({children:true});
      this._shader?.destroy(true);
      for (const texture of this._stationTextures.values()) texture.destroy(true);
      this._stationTextures.clear();
      this._stationSprites.clear();
      this._renderer?.destroy(false);
      this._root = this._machines = this._meshes = this._shader = this._renderer = null;
      this._gl = null;
    }

    _project(x, y) {
      return [(x - this._camera.x) * this._camera.scale, (y - this._camera.y) * this._camera.scale];
    }

    _screenBounds(node, look = this._callbacks.appearance?.(node) || {}) {
      const [x, y] = this._project(node.x, node.y);
      const width = node.width * this._camera.scale;
      const height = node.height * this._camera.scale;
      if (node.expanded) return { x, y, width, height };
      // Scene extents locate nested content; they do not size a collapsed machine.
      const stationScale = this._stationScale ?? Math.min(this._camera.scale, 1);
      const aspect = look.aspect ?? RailixWorld.appearance.aspect;
      const stationHeight = Math.min(136, 272 / aspect) * stationScale;
      const stationWidth = stationHeight * aspect;
      return { x: x + (width - stationWidth) / 2, y: y + (height - stationHeight) / 2,
        width: stationWidth, height: stationHeight };
    }

    _fitStations(stations) {
      const compact = stations.filter(({node,box}) => !node.expanded && box.x+box.width >= 0
        && box.y+box.height >= 0 && box.x <= this._size.width && box.y <= this._size.height).map(({box}) => {
        // Include the chassis, sockets and selection brackets in the collision footprint.
        const padding = 32 * this._stationScale;
        return {x: box.x + box.width / 2, y: box.y + box.height / 2,
          width: box.width + 2 * padding, height: box.height + 2 * padding};
      });
      if (compact.length < 2) return 1;
      const cellWidth = Math.max(...compact.map(box => box.width));
      const cellHeight = Math.max(...compact.map(box => box.height));
      const cells = new Map();
      let factor = 1;
      for (const box of compact) {
        const x = Math.floor(box.x / cellWidth), y = Math.floor(box.y / cellHeight);
        for (let row = y - 1; row <= y + 1; row++) for (let column = x - 1; column <= x + 1; column++) {
          for (const other of cells.get(`${column}:${row}`) || []) {
            const dx = Math.abs(box.x - other.x), dy = Math.abs(box.y - other.y);
            if (!dx && !dy) {
              this._fail("The scene contains coincident collapsed stations; a non-overlapping footprint cannot be drawn.");
              return 0;
            }
            factor = Math.min(factor, Math.max(2 * dx / (box.width + other.width),
              2 * dy / (box.height + other.height)));
          }
        }
        const key = `${x}:${y}`;
        if (!cells.has(key)) cells.set(key, []);
        cells.get(key).push(box);
      }
      return factor;
    }

    _linkPoints(link, stations) {
      const points = link.points.map(point => this._project(...point));
      for (const [id, index] of [[link.from, 0], [link.to, points.length - 1]]) {
        const station = stations.get(id);
        if (!station) continue;
        const {node, look, box} = station;
        const [x, y] = link.points[index];
        const port = [box.x + (x - node.x) / node.width * box.width,
          box.y + (y - node.y) / node.height * box.height];
        const cx = box.x + box.width / 2, cy = box.y + box.height / 2;
        const dx = port[0] - cx, dy = port[1] - cy;
        const contour = this._contour(box, node.expanded ? {roundness: 0} : look);
        let distance = 1;
        for (let i = 0; i < contour.length; i++) {
          const a = contour[i], b = contour[(i + 1) % contour.length];
          const ex = b[0] - a[0], ey = b[1] - a[1];
          const cross = dx * ey - dy * ex;
          if (!cross) continue;
          const t = ((a[0] - cx) * ey - (a[1] - cy) * ex) / cross;
          const u = ((a[0] - cx) * dy - (a[1] - cy) * dx) / cross;
          if (t >= 0 && u >= 0 && u <= 1) distance = Math.min(distance, t);
        }
        points[index] = [cx + dx * distance, cy + dy * distance];
      }
      // Re-anchor the orthogonal elbows too: authored shapes can extend beyond the scene's ports.
      if (points.length === 4) {
        const a = points[0], d = points[3];
        const horizontal = link.points[0][1] === link.points[1][1];
        const middle = horizontal ? (a[0]+d[0])/2 : (a[1]+d[1])/2;
        points[1] = horizontal ? [middle,a[1]] : [a[0],middle];
        points[2] = horizontal ? [middle,d[1]] : [d[0],middle];
      }
      return points;
    }

    _networks(rails) {
      const parents = rails.map((_, i) => i), ports = new Map();
      const root = i => {
        while (parents[i] !== i) { parents[i] = parents[parents[i]]; i = parents[i]; }
        return i;
      };
      rails.forEach((rail, i) => {
        for (const output of [true, false]) {
          const points = output ? rail.points : [...rail.points].reverse();
          const axis = points[0][1] === points[1][1] ? 0 : 1;
          const direction = Math.sign(points.at(-1)[axis] - points[0][axis]);
          const key = `${output ? 'from' : 'to'}:${rail.link[output ? 'from' : 'to']}:${axis}:${direction}`;
          if (!ports.has(key)) ports.set(key, {axis, direction, rails: []});
          ports.get(key).rails.push(i);
        }
      });
      for (const port of ports.values()) {
        if (port.rails.length < 2) continue;
        // A shared port owns one bus, not a separate elbow for every destination.
        const middle = port.rails.reduce((value, i) => port.direction > 0
          ? Math.min(value, rails[i].points[1][port.axis]) : Math.max(value, rails[i].points[1][port.axis]),
          port.direction > 0 ? Infinity : -Infinity);
        for (const i of port.rails) {
          parents[root(i)] = root(port.rails[0]);
          rails[i].points[1][port.axis] = middle;
          rails[i].points[2][port.axis] = middle;
        }
      }
      const networks = new Map();
      rails.forEach((rail, i) => {
        const id = root(i);
        if (!networks.has(id)) networks.set(id, []);
        networks.get(id).push(rail);
      });
      return networks.values();
    }

    _contour(box, look) {
      const {x, y, width: w, height: h} = box;
      if (look.shape === "triangle") return [[x, y], [x + w, y + h / 2], [x, y + h]];
      if (look.shape === "diamond") return [[x + w / 2, y], [x + w, y + h / 2], [x + w / 2, y + h], [x, y + h / 2]];
      if (look.shape === "ellipse") return Array.from({length: 32}, (_, i) => {
        const angle = i * Math.PI / 16;
        return [x + w / 2 + Math.cos(angle) * w / 2, y + h / 2 + Math.sin(angle) * h / 2];
      });
      const r = Math.min(w, h) * (look.roundness ?? RailixWorld.appearance.roundness) / 100;
      if (!r) return [[x, y], [x + w, y], [x + w, y + h], [x, y + h]];
      const points = [];
      for (const [cx, cy, angle] of [[x + w - r, y + r, -Math.PI / 2], [x + w - r, y + h - r, 0],
        [x + r, y + h - r, Math.PI / 2], [x + r, y + r, Math.PI]]) {
        for (let i = 0; i <= 6; i++) points.push([cx + r * Math.cos(angle + i * Math.PI / 12), cy + r * Math.sin(angle + i * Math.PI / 12)]);
      }
      return points;
    }

    _triangle(ax, ay, bx, by, cx, cy, ink, alongA = 0, alongB = 0, alongC = 0, spacing = 0,
      acrossA = 0, acrossB = 0, acrossC = 0) {
      if (this._vertexLength + 27 > this._vertices.length) {
        const next = new Float32Array(this._vertices.length * 2);
        next.set(this._vertices);
        this._vertices = next;
      }
      this._vertex(ax, ay, ink, alongA, spacing, acrossA);
      this._vertex(bx, by, ink, alongB, spacing, acrossB);
      this._vertex(cx, cy, ink, alongC, spacing, acrossC);
    }

    _vertex(x, y, ink, along, spacing, across) {
      let offset = this._vertexLength;
      this._vertices[offset++] = x;
      this._vertices[offset++] = y;
      this._vertices[offset++] = ink[0];
      this._vertices[offset++] = ink[1];
      this._vertices[offset++] = ink[2];
      this._vertices[offset++] = ink[3];
      this._vertices[offset++] = along;
      this._vertices[offset++] = spacing;
      this._vertices[offset++] = across;
      this._vertexLength = offset;
    }

    _rect(x, y, width, height, ink) {
      const right = Math.min(this._size.width + 8, x + width);
      const bottom = Math.min(this._size.height + 8, y + height);
      x = Math.max(-8, x);
      y = Math.max(-8, y);
      if (right <= x || bottom <= y) return;
      this._triangle(x, y, right, y, right, bottom, ink);
      this._triangle(x, y, right, bottom, x, bottom, ink);
    }

    _line(ax, ay, bx, by, width, ink, spacing = 0, distance = 0, firstNormal, lastNormal) {
      const dx = bx - ax;
      const dy = by - ay;
      const length = Math.hypot(dx, dy);
      if (!length || !width) return length;
      let start = 0;
      let end = 1;
      for (const [p, q] of [[-dx, ax + width], [dx, this._size.width + width - ax], [-dy, ay + width], [dy, this._size.height + width - ay]]) {
        if (!p) { if (q < 0) return length; }
        else if (p < 0) start = Math.max(start, q / p);
        else end = Math.min(end, q / p);
      }
      if (start > end) return length;
      const x1 = ax + start * dx;
      const y1 = ay + start * dy;
      const x2 = ax + end * dx;
      const y2 = ay + end * dy;
      const normalA = firstNormal || [-dy/length,dx/length];
      const normalB = lastNormal || normalA;
      const nx1 = (normalA[0]+(normalB[0]-normalA[0])*start)*width/2;
      const ny1 = (normalA[1]+(normalB[1]-normalA[1])*start)*width/2;
      const nx2 = (normalA[0]+(normalB[0]-normalA[0])*end)*width/2;
      const ny2 = (normalA[1]+(normalB[1]-normalA[1])*end)*width/2;
      const along = spacing ? distance + start * length : 0;
      const until = along + (end - start) * length;
      this._triangle(x1 + nx1, y1 + ny1, x2 + nx2, y2 + ny2, x2 - nx2, y2 - ny2,
        ink, along, until, until, spacing, width / 2, width / 2, -width / 2);
      this._triangle(x1 + nx1, y1 + ny1, x2 - nx2, y2 - ny2, x1 - nx1, y1 - ny1,
        ink, along, until, along, spacing, width / 2, -width / 2, -width / 2);
      return length;
    }

    _outline(box, width, ink, boundary) {
      for (const [x, y, length, horizontal] of [
        [box.x, box.y, box.width, true], [box.x, box.y + box.height - width, box.width, true],
        [box.x, box.y, box.height, false], [box.x + box.width - width, box.y, box.height, false]
      ]) {
        const dash = boundary === "dotted" ? 2 : boundary === "dashed" ? 8 : 0;
        if (!dash) this._rect(x, y, horizontal ? length : width, horizontal ? width : length, ink);
        else {
          const origin = horizontal ? x : y;
          const limit = Math.min(length, (horizontal ? this._size.width : this._size.height) - origin);
          for (let offset = Math.max(0, Math.floor(-origin / (dash + 5)) * (dash + 5)); offset < limit; offset += dash + 5) {
            this._rect(x + (horizontal ? offset : 0), y + (horizontal ? 0 : offset),
              horizontal ? Math.min(dash, length - offset) : width, horizontal ? width : Math.min(dash, length - offset), ink);
          }
        }
      }
    }

    // Bake only visible housing variants. Lighting is static; traffic never rebuilds textures.
    _housing(node, look, box) {
      const shape = look.shape || RailixWorld.appearance.shape;
      const aspect = look.aspect ?? RailixWorld.appearance.aspect;
      const roundness = look.roundness ?? RailixWorld.appearance.roundness;
      const height = Math.min(136,272/aspect), width = height*aspect, margin = 12;
      const resolution = Math.min(2,2**Math.ceil(Math.log2(box.height/height*this._dpr)));
      const key = `${shape}:${aspect}:${roundness}:${resolution}`;
      let texture = this._stationTextures.get(key);
      if (!texture) {
        const canvas = document.createElement("canvas");
        canvas.width = Math.ceil((width+margin*2)*resolution);
        canvas.height = Math.ceil((height+margin*2)*resolution);
        const ctx = canvas.getContext("2d");
        ctx.scale(resolution,resolution);
        const surface = (inset, offset, stops, shadow = false) => {
          const bounds = {x:margin+inset,y:margin+inset+offset,
            width:width-inset*2,height:height-inset*2};
          const points = this._contour(bounds,{shape,roundness});
          const gradient = ctx.createLinearGradient(0,bounds.y,width*.4,bounds.y+bounds.height);
          stops.forEach(([at,ink]) => gradient.addColorStop(at,ink));
          ctx.save();
          if (shadow) { ctx.shadowColor = "#18232980"; ctx.shadowBlur = 12; ctx.shadowOffsetY = 6; }
          ctx.beginPath();
          points.forEach(([x,y],index) => index ? ctx.lineTo(x,y) : ctx.moveTo(x,y));
          ctx.closePath();
          ctx.fillStyle = gradient;
          ctx.fill();
          ctx.restore();
        };
        surface(0,6,[[0,"#303e44"],[.65,"#1c282c"],[1,"#11191d"]],true);
        surface(0,0,[[0,"#abb9b9"],[.4,"#4c5e63"],[1,"#19272d"]]);
        surface(2,0,[[0,"#e2eae5"],[.08,"#99a9a8"],[.7,"#415155"],[1,"#243238"]]);
        surface(7,0,[[0,"#ffffff"],[.55,"#e4e9dd"],[1,"#929d96"]]);
        surface(10,-1,[[0,"#f9fbef"],[.45,"#e3e9dd"],[.9,"#cdd6c8"],[1,"#e1e8da"]]);
        const face = this._symbolBounds({x:margin,y:margin,width,height},node,{shape,roundness},1);
        const dial = face.width/2 + 7;
        if (dial > 8) {
          const cx = face.x+face.width/2, cy = face.y+face.height/2;
          const ring = ctx.createLinearGradient(cx,cy-dial,cx,cy+dial);
          ring.addColorStop(0,"#17282d"); ring.addColorStop(.8,"#52676a"); ring.addColorStop(1,"#f8fff4");
          ctx.fillStyle = ring;
          ctx.beginPath(); ctx.roundRect(cx-dial,cy-dial,dial*2,dial*2,dial*.28); ctx.fill();
          const screen = ctx.createRadialGradient(cx-dial*.25,cy-dial*.4,0,cx,cy,dial*1.7);
          screen.addColorStop(0,"#304c51"); screen.addColorStop(1,"#0f1d23");
          ctx.fillStyle = screen;
          ctx.beginPath(); ctx.roundRect(cx-dial+3,cy-dial+3,dial*2-6,dial*2-6,dial*.2); ctx.fill();
        }
        texture = new PIXI.Texture({source:new PIXI.CanvasSource({resource:canvas,resolution})});
        this._stationTextures.set(key,texture);
      }
      let sprite = this._stationSprites.get(node.id);
      if (!sprite) {
        sprite = new PIXI.Sprite({texture,eventMode:"none"});
        this._stationSprites.set(node.id,sprite);
        this._machines.addChild(sprite);
      } else sprite.texture = texture;
      const scale = box.height/height;
      sprite.tint = look.coverage === "uncovered" ? 0x91a0a2 : 0xffffff;
      sprite.position.set(box.x-margin*scale,box.y-margin*scale);
      sprite.scale.set(scale);
      this._usedStations.add(node.id);
    }

    _station(node, look, box) {
      if (!box.width || !box.height) return;
      const accent = look.error ? palette.red : look.selected || look.coverage === "selected" ? palette.teal
        : look.color ? color(look.color) : palette.line;
      const unit = Math.min(1, box.width / 100, box.height / 100);
      if (!node.expanded && Math.min(box.width, box.height) < 24) {
        this._surface(box, look, look.error ? palette.red : palette.chassis);
        this._surface({x:box.x+unit*8,y:box.y+unit*8,width:box.width-unit*16,height:box.height-unit*16},
          look, look.selected ? palette.cyan : look.coverage === "uncovered" ? palette.metal : palette.panel);
        return;
      }
      if (node.expanded) {
        this._rect(box.x, box.y, box.width, box.height, [...accent.slice(0, 3), .035]);
        this._outline(box, look.selected ? 3 : 1, accent, look.boundary);
      } else {
        const inset = amount => ({x: box.x + amount * unit, y: box.y + amount * unit,
          width: box.width - 2 * amount * unit, height: box.height - 2 * amount * unit});
        this._housing(node,look,box);
        const rim = this._contour(inset(6), look);
        const dash = look.boundary === "dotted" ? 2 : look.boundary === "dashed" ? 8 : 0;
        let distance = 0;
        for (let i = 0; i < rim.length; i++) {
          const a = rim[i], b = rim[(i + 1) % rim.length];
          const length = Math.hypot(b[0] - a[0], b[1] - a[1]);
          if (!dash) this._line(...a, ...b, 1.3 * unit, look.color || look.error ? accent : palette.light);
          else for (let offset = -(distance % (dash + 5)); offset < length; offset += dash + 5) {
            const start = Math.max(0, offset) / length, end = Math.min(length, offset + dash) / length;
            if (end > start) this._line(a[0] + (b[0] - a[0]) * start, a[1] + (b[1] - a[1]) * start,
              a[0] + (b[0] - a[0]) * end, a[1] + (b[1] - a[1]) * end, unit, accent);
          }
          distance += length;
        }
        if (look.selected) {
          for (const [x, y, dx, dy] of [[box.x-5*unit, box.y-5*unit, 1, 1],
            [box.x+box.width+5*unit,box.y-5*unit,-1,1],
            [box.x-5*unit,box.y+box.height+5*unit,1,-1],
            [box.x+box.width+5*unit,box.y+box.height+5*unit,-1,-1]]) {
            this._line(x,y,x+12*unit*dx,y,2*unit,palette.cyan);
            this._line(x,y,x,y+12*unit*dy,2*unit,palette.cyan);
          }
        }
      }
      const symbol = this._symbolBounds(box, node, look);
      box = this._contentBounds(box, node, look);
      const { x, y, width, height } = box;
      if (node.expanded) {
        const span = Math.min(14, width / 4, height / 4);
        for (const [cx, cy, dx, dy] of [[x, y, 1, 1], [x + width, y, -1, 1],
          [x, y + height, 1, -1], [x + width, y + height, -1, -1]]) {
          this._line(cx, cy, cx + dx * span, cy, 3, accent);
          this._line(cx, cy, cx, cy + dy * span, 3, accent);
        }
      } else if (width >= 16 && height >= 16) {
        this._stationFace(node, look, symbol);
        // Fasteners and status lamps belong to the common housing, not individual Step types.
        if (width > 28 && height > 28) {
          for (const left of [.16,.80]) for (const top of [.16,.80]) {
            this._rect(x+width*left,y+height*top,4*unit,4*unit,palette.chassis);
            this._rect(x+width*left,y+height*top,3*unit,unit,palette.light);
          }
          this._rect(x+width*.34,y+height*.07,width*.32,height*.08,palette.chassis);
          this._rect(x+width*.38,y+height*.085,width*.24,height*.035,
            look.error ? palette.red : look.rate > 0 ? palette.signal : palette.metal);
        }
      }
      if (!node.expanded && width > 20 && height > 20 && look.coverage) {
        this._rect(x + width - 9, y + 5, 4, 4,
          look.coverage === "uncovered" ? palette.line : palette.teal);
      }
      if (look.coverage === "uncovered") {
        for (let offset = 12; offset < Math.min(width - 8, 60); offset += 8) this._rect(x + offset, y + height - 5, 3, 2, palette.line);
      }
      if (look.changed) {
        const span = Math.min(32, width - 8);
        for (let offset = 0; offset < span; offset += 8) {
          const run = Math.min(8, span - offset);
          this._line(x + width - 4 - span + offset, y + 3,
            x + width - 4 - span + offset + run, y + 3 + Math.min(run, Math.max(0, height - 6)), 2, palette.amber);
        }
      }
    }

    _surface(box, look, ink) {
      const points = this._contour(box, look);
      for (let i = 0; i < points.length; i++) this._triangle(box.x + box.width / 2, box.y + box.height / 2,
        ...points[i], ...points[(i + 1) % points.length], ink);
    }

    _belt(points, width, ink, spacing = 0, junctions = false) {
      // Only the shader advances carriers. Geometry changes with the viewport, never with requests.
      const normals = points.slice(1).map((b,i) => {
        const a = points[i], length = Math.hypot(b[0]-a[0],b[1]-a[1]);
        return length ? [-(b[1]-a[1])/length,(b[0]-a[0])/length] : [0,0];
      });
      const joins = points.map((_,i) => {
        const a = normals[Math.max(0,i-1)], b = normals[Math.min(i,normals.length-1)];
        const divisor = Math.max(.5,1+a[0]*b[0]+a[1]*b[1]);
        return [(a[0]+b[0])/divisor,(a[1]+b[1])/divisor];
      });
      let distance = 0;
      if (junctions) for (const [x,y] of points.slice(1,-1)) this._rect(x-width/2,y-width/2,width,width,ink);
      for (let i = 1; i < points.length; i++) {
        distance += this._line(...points[i-1], ...points[i], width, ink, spacing, distance,
          junctions ? undefined : joins[i-1], junctions ? undefined : joins[i]);
      }
    }

    _railPath(points) {
      points = points.filter((point,i) => !i || point[0] !== points[i-1][0] || point[1] !== points[i-1][1]);
      // A shallow offset needs a sweeping S, not two elbows tighter than the belt's half-width.
      if (points.length === 4 && Math.hypot(points[2][0]-points[1][0],points[2][1]-points[1][1]) < 64*this._stationScale) {
        return Array.from({length:17},(_,i) => {
          const t = i/16, s = 1-t;
          return [0,1].map(axis => s*s*s*points[0][axis]+3*s*s*t*points[1][axis]
            +3*s*t*t*points[2][axis]+t*t*t*points[3][axis]);
        });
      }
      const path = [points[0]];
      for (let i = 1; i < points.length-1; i++) {
        const a = points[i-1], b = points[i], c = points[i+1];
        const before = Math.hypot(b[0]-a[0],b[1]-a[1]), after = Math.hypot(c[0]-b[0],c[1]-b[1]);
        if (!before || !after) { path.push(b); continue; }
        const dx = (b[0]-a[0])/before, dy = (b[1]-a[1])/before;
        const ex = (c[0]-b[0])/after, ey = (c[1]-b[1])/after;
        if (Math.abs(dx*ex+dy*ey) > .001) { path.push(b); continue; }
        const radius = Math.min(32*this._stationScale,before/(i===1 ? 1 : 2),after/(i===points.length-2 ? 1 : 2));
        const cx = b[0]-dx*radius+ex*radius, cy = b[1]-dy*radius+ey*radius;
        const start = Math.atan2(-ey,-ex), turn = Math.sign(dx*ey-dy*ex)*Math.PI/2;
        for (let part = 0; part <= 8; part++) {
          const angle = start+turn*part/8;
          path.push([cx+radius*Math.cos(angle),cy+radius*Math.sin(angle)]);
        }
      }
      path.push(points.at(-1));
      // Trigonometric endpoints can differ by a fraction of a pixel and invert a join normal.
      return path.filter((point,i) => !i || Math.hypot(point[0]-path[i-1][0],point[1]-path[i-1][1]) > 1e-6);
    }

    _stationFace(node, look, box) {
      const {x, y, width: w, height: h} = box;
      const ink = look.error ? palette.red : look.coverage === "uncovered" ? palette.metal : palette.light;
      const stroke = Math.min(2, w / 16, h / 16);
      const line = (ax, ay, bx, by, width = stroke, tone = ink) =>
        this._line(x + ax * w, y + ay * h, x + bx * w, y + by * h, width, tone);
      const rect = (left, top, width, height, tone = ink) =>
        this._rect(x + left * w, y + top * h, width * w, height * h, tone);
      if (look.iconUrl) {
        line(.12, .2, .88, .2, stroke, palette.line);
        line(.12, .8, .88, .8, stroke, palette.line);
      } else if (node.kind === "app") {
        for (const left of [.24, .55]) for (const top of [.24, .55]) rect(left, top, .21, .21);
        line(.2, .5, .8, .5, stroke, palette.rail);
      } else if (node.kind === "trigger") {
        rect(.18, .24, .07, .52);
        this._triangle(x + .35 * w, y + .24 * h, x + .76 * w, y + .5 * h,
          x + .35 * w, y + .76 * h, ink);
      } else if (node.kind === "region") {
        line(.2, .65, .2, .2);
        line(.2, .2, .65, .2);
        this._outline({x: x + .34 * w, y: y + .34 * h, width: .46 * w, height: .46 * h}, stroke, ink);
        rect(.44, .44, .08, .08);
        rect(.62, .62, .08, .08);
      } else if (node.kind === "end") {
        line(.2, .5, .48, .5);
        rect(.48, .25, .09, .5);
        rect(.66, .25, .05, .5);
      } else if (look.symbol === "branch") {
        line(.16, .5, .43, .5);
        line(.43, .5, .73, .26);
        line(.43, .5, .73, .74);
        rect(.69, .2, .12, .12);
        rect(.69, .68, .12, .12);
      } else {
        line(.14, .2, .86, .2, stroke, palette.line);
        line(.14, .8, .86, .8, stroke, palette.line);
        line(.08, .5, .26, .5);
        line(.74, .5, .92, .5);
        this._outline({x: x + .26 * w, y: y + .3 * h, width: .48 * w, height: .4 * h}, stroke, ink);
        if (!look.symbol || look.symbol === "step") {
          rect(.38, .42, .08, .16);
          rect(.54, .42, .08, .16);
        }
      }
    }

    _socket(point, neighbor, output, ink) {
      const dx = neighbor[0] - point[0], dy = neighbor[1] - point[1];
      const length = Math.hypot(dx, dy);
      const scale = this._stationScale;
      if (!length || scale < .25) return;
      const reach = 7 * scale;
      const ax = point[0] - dx / length * reach, ay = point[1] - dy / length * reach;
      const bx = point[0] + dx / length * reach, by = point[1] + dy / length * reach;
      this._line(ax, ay, bx, by, 52 * scale, palette.chassis);
      this._line(ax, ay, bx, by, 48 * scale, palette.metal);
      this._line(ax, ay, bx, by, 42 * scale, palette.chassis);
      this._line(point[0], point[1], bx, by, 38 * scale, ink);
      if (output) this._line(ax, ay, point[0], point[1], 38 * scale, ink);
    }

    _contentBounds(box, node, look) {
      if (node.expanded) return box;
      if (!look.shape || look.shape === "rectangle") {
        const inset = Math.min(box.width, box.height) * (look.roundness ?? RailixWorld.appearance.roundness) / 100 * (1 - Math.SQRT1_2);
        return {x: box.x + inset, y: box.y + inset, width: box.width - 2 * inset, height: box.height - 2 * inset};
      }
      const factor = look.shape === "ellipse" ? .7 : look.shape === "triangle" ? 1 / 3 : .45;
      return {x: box.x + box.width * (1 - factor) / 2, y: box.y + box.height * (1 - factor) / 2,
        width: box.width * factor, height: box.height * factor};
    }

    _symbolBounds(box, node, look, scale = this._stationScale) {
      const safe = this._contentBounds(box, node, look);
      const size = Math.min(36 * scale, safe.width, safe.height);
      return {x: box.x + (box.width-size)/2, y: box.y + (box.height-size)/2, width: size, height: size};
    }

    _draw() {
      const gl = this._gl;
      if (!gl || this._disposed || this._lost) return;
      this._vertexLength = 0;
      this._underlayLength = 0;
      this._usedStations = new Set();
      this._moving = false;
      const grid = 64 * 2 ** Math.floor(Math.log2(1 / this._camera.scale));
      const spacing = grid * this._camera.scale;
      const blend = Math.log2(spacing/32);
      for (const [size,opacity] of [[grid,blend],[grid*2,1-blend]]) {
        const ink = [...palette.ink.slice(0,3),.09*opacity];
        const step = size*this._camera.scale;
        for (let x = -((this._camera.x % size) * this._camera.scale); x < this._size.width; x += step) this._rect(x,0,1,this._size.height,ink);
        for (let y = -((this._camera.y % size) * this._camera.scale); y < this._size.height; y += step) this._rect(0,y,this._size.width,1,ink);
      }
      if (this._scene) {
        const outgoing = new Map();
        for (const link of this._scene.links) outgoing.set(link.from, (outgoing.get(link.from) || 0) + 1);
        this._stationScale = Math.min(this._camera.scale, 1);
        const stations = this._scene.nodes.map(node => {
          const look = this._callbacks.appearance?.(node) || {};
          return {node, look: node.kind === "step" && !look.symbol && outgoing.get(node.id) > 1 ? {...look, symbol: "branch"} : look,
            box: this._screenBounds(node, look)};
        });
        this._stationScale *= this._fitStations(stations);
        for (const station of stations) if (!station.node.expanded) station.box = this._screenBounds(station.node, station.look);
        const stationById = new Map(stations.map(station => [station.node.id, station]));
        const rails = this._scene.links.map(link => {
          const look = this._callbacks.linkAppearance?.(link) || {};
          return {link, points: this._linkPoints(link, stationById), look: this._placementLink(link) ? {...look, selected: true} : look};
        });
        const visible = stations.filter(({ box }) => box.x + box.width >= 0 && box.y + box.height >= 0 && box.x <= this._size.width && box.y <= this._size.height);
        for (const { node, look, box } of visible.filter(item => item.node.expanded).sort((a, b) => b.box.width * b.box.height - a.box.width * a.box.height)) this._station(node, look, box);
        // Shared trunks retain active traffic, with the selected Example painted last.
        rails.sort((a, b) => Number(Boolean(a.look.selected)) - Number(Boolean(b.look.selected))
          || (a.look.rate || 0) - (b.look.rate || 0));
        for (const network of this._networks(rails)) {
          const unit = this._stationScale;
          const paths = network.map(({points,look}) => ({look,
            path: network.length > 1 ? points.filter((p,i) => !i || p[0]!==points[i-1][0] || p[1]!==points[i-1][1]) : this._railPath(points),
            spacing: (Number.isFinite(look.rate) && look.rate > 0
              ? Math.max(28,128/(1+Math.min(6,Math.log10(1+look.rate)))) : -24)*unit}));
          // Finish the common casing before the transport surface: no seams inside a T junction.
          // Separate networks keep their casing at crossings; geometry alone never creates a join.
          for (let layer = 0; layer < 5; layer++) for (const {path,look,spacing} of paths) {
            const ink = [palette.shadow,palette.chassis,palette.metal,look.selected?palette.cyan:palette.line,
              look.selected?palette.teal:look.color?color(look.color):palette.rail][layer];
            const beforeVertices = this._vertexLength;
            this._belt(layer ? path : path.map(([x,y])=>[x+unit,y+4*unit]), [50,48,45,42,38][layer]*unit,
              ink, layer === 4 ? spacing : 0, network.length > 1);
            this._moving ||= layer === 4 && spacing > 0 && this._vertexLength > beforeVertices;
          }
        }
        this._underlayLength = this._vertexLength;
        for (const { node, look, box } of visible) if (!node.expanded) this._station(node, look, box);
        const sockets = new Set();
        for (const {link, look, points} of [...rails].reverse()) {
          const ink = look.selected ? palette.teal : look.color ? color(look.color) : palette.rail;
          for (const [id, output] of [[link.from, true], [link.to, false]]) {
            const station = stationById.get(id);
            if (!station) continue;
            const point = output ? points[0] : points.at(-1);
            const neighbor = output ? points.find(other => other[0] !== point[0] || other[1] !== point[1])
              : points.findLast(other => other[0] !== point[0] || other[1] !== point[1]);
            const key = `${id}:${output}:${point}`;
            if (neighbor && !sockets.has(key)) { this._socket(point, neighbor, output, ink); sockets.add(key); }
          }
        }
        const placement = this._placementStation(rails);
        if (placement) {
          this._station(placement.node, placement.look, placement.box);
          visible.push(placement);
        }
        this._renderLabels(visible, rails, outgoing);
        if (this._anchor) {
          const box = visible.find(({node}) => node.id === this._anchor.id)?.box;
          const element = this._anchor.element;
          element.hidden = !box;
          if (box) {
            element.style.left = `${clamp(box.x+box.width/2-element.offsetWidth/2,8,Math.max(8,this._size.width-element.offsetWidth-8))}px`;
            element.style.top = `${Math.max(104,box.y-element.offsetHeight-12)}px`;
          }
        }
      }
      for (const [mesh,start,end] of [[this._meshes[0],0,this._underlayLength],
        [this._meshes[1],this._underlayLength,this._vertexLength]]) {
        mesh.visible = end > start;
        if (mesh.visible) mesh.geometry.getBuffer("aPosition").data = this._vertices.subarray(start,end);
      }
      const textures = new Set();
      for (const [id,sprite] of this._stationSprites) {
        if (!this._usedStations.has(id)) { sprite.destroy(); this._stationSprites.delete(id); }
        else textures.add(sprite.texture);
      }
      for (const [key,texture] of this._stationTextures) if (!textures.has(texture)) {
        texture.destroy(true);
        this._stationTextures.delete(key);
      }
      this._animate(performance.now(), true);
    }

    _placementLink(link) {
      return this._placement && link.from === this._placement.after
        && (!this._placement.outcome || link.outcome === this._placement.outcome);
    }

    _placementStation(rails) {
      const placement = this._placement;
      if (!placement) return null;
      const source = this._sceneNodes.get(placement.after);
      if (!source) return null;
      const route = rails.find(({link}) => this._placementLink(link));
      const box = this._screenBounds(source);
      let x = box.x + box.width + 52, y = box.y + box.height / 2;
      if (route) {
        const {points} = route;
        for (let i = points.length - 1; i > 0; i--) {
          const a = points[i - 1], b = points[i];
          const left = Math.max(32, Math.min(a[0], b[0])), right = Math.min(this._size.width - 32, Math.max(a[0], b[0]));
          const top = Math.max(32, Math.min(a[1], b[1])), bottom = Math.min(this._size.height - 32, Math.max(a[1], b[1]));
          if (left > right || top > bottom) continue;
          x = (left + right) / 2;
          y = (top + bottom) / 2;
          break;
        }
      }
      const size = 136 * this._stationScale;
      return {node: {id: "placement", kind: "step", preview: true, name: `Insert ${placement.name}`},
        look: {...RailixWorld.appearance, boundary: "dashed", color: "#126d78"},
        box: {x: x - size / 2, y: y - size / 2, width: size, height: size}};
    }

    _animate(time, force = false) {
      this._motionFrame = 0;
      if (this._disposed || this._lost || !this._gl) return;
      const moving = this._moving && !document.hidden && !this._reducedMotion.matches
        && this._callbacks.motionActive?.() !== false;
      if (moving && !force && time - (this._paintedAt || 0) < 1000 / 30) {
        this._motionFrame = requestAnimationFrame(next => this._animate(next));
        return;
      }
      this._paintedAt = time;
      const uniforms = this._shader.resources.world.uniforms;
      uniforms.viewport[0] = this._size.width;
      uniforms.viewport[1] = this._size.height;
      uniforms.travel = moving ? time * .048 * this._stationScale : -1;
      uniforms.unit = this._stationScale || 1;
      PIXI.Ticker.system.update(time);
      this._renderer.render(this._root);
      const animated = String(moving);
      if (this._stage.dataset.trafficAnimated !== animated) this._stage.dataset.trafficAnimated = animated;
      if (moving) this._motionFrame = requestAnimationFrame(next => this._animate(next));
    }

    _renderLabels(visible, rails, outgoing) {
      const candidates = [];
      const bodies = new Map();
      const cells = (box, visit) => {
        for (let y = Math.floor(box.y / 64); y <= Math.floor((box.y + box.height) / 64); y++) {
          for (let x = Math.floor(box.x / 128); x <= Math.floor((box.x + box.width) / 128); x++) {
            if (visit(`${x}:${y}`)) return true;
          }
        }
        return false;
      };
      for (const station of visible) if (!station.node.expanded) cells(station.box, key => {
        if (!bodies.has(key)) bodies.set(key, []);
        bodies.get(key).push(station);
        return false;
      });
      for (const station of visible) {
        const {node, look} = station;
        const box = station.box;
        const compact = box.width < 18 || box.height < 18;
        if ((station.box.width < 18 || station.box.height < 18) && !look.selected
          && !["app", "trigger", "region"].includes(node.kind)) continue;
        if (box.x + box.width < 0 || box.y + box.height < 0) continue;
        const expanded = node.expanded;
        const nameWidth = Math.max(48, (look.label || node.name).length * 7 + 12,
          node.kind === "region" || node.kind === "trigger" ? 80 : 0);
        const width = expanded ? Math.min(box.width - 20, 260) : Math.min(168, nameWidth);
        const measured = !expanded && box.width >= 48 && box.height >= 36 && look.duration;
        const height = expanded ? 30 : measured ? 54 : 36;
        const x = clamp(box.x + (box.width - width) / 2, 0, Math.max(0, this._size.width - width));
        const positions = expanded ? [box.y + 6] : [box.y + box.height + 7, box.y - height - 7].filter(y =>
          y >= 0 && y + height <= this._size.height && !cells({x, y, width, height}, key => bodies.get(key)?.some(other =>
            other.node !== node && x < other.box.x + other.box.width && x + width > other.box.x
            && y < other.box.y + other.box.height && y + height > other.box.y)));
        const y = positions[0];
        if (y === undefined) continue;
        if (x < -width || y < -height || x > this._size.width || y > this._size.height) continue;
        candidates.push({ id: node.preview ? "placement" : `node:${node.id}`, node, look, compact, measured, box, x, y, positions, width, height,
          rank: node.preview ? 1100 : look.selected ? 1000 : look.error ? 900 : node.kind === "trigger" || node.kind === "app" ? 800 : node.kind === "region" ? 700 : 600 });
      }
      for (const {link, points} of rails) {
        if (outgoing.get(link.from) < 2 || !link.outcome) continue;
        const label = this._callbacks.linkLabel?.(link);
        if (!label) continue;
        const width = Math.min(160, Math.max(56, label.length * 6 + 16));
        const start = points[0];
        const end = points.at(-1);
        if (Math.hypot(end[0] - start[0], end[1] - start[1]) < 40) continue;
        const tail = points.at(-2), horizontal = tail[1] === end[1], clearance = 28*this._stationScale;
        const places = horizontal ? [[(tail[0]+end[0]-width)/2,end[1]-clearance-22],
          [end[0]>=start[0] ? tail[0]-width-clearance : tail[0]+clearance,end[1]-10]]
          : [[end[0]+clearance,(tail[1]+end[1])/2-10]];
        const position = places.find(([x,y]) => x>=0 && y>=0 && x+width<=this._size.width && y+20<=this._size.height
          && !cells({x,y,width,height:20},key => bodies.get(key)?.some(({box}) => x<box.x+box.width && x+width>box.x
            && y<box.y+box.height && y+20>box.y)));
        if (!position) continue;
        const [x,y] = position;
        candidates.push({ id: `connection:${link.id}`, text: label, outcome: link.outcome, x, y, width, height: 20, rank: 500 });
      }
      candidates.sort((a, b) => b.rank - a.rank || a.id.localeCompare(b.id));
      const selected = [];
      for (const candidate of candidates) {
        if (selected.length === MAX_LABELS) break;
        if (candidate.rank < 1000) {
          const y = (candidate.positions || [candidate.y]).find(y => !selected.some(other => candidate.x < other.x + other.width
            && candidate.x + candidate.width > other.x && y < other.y + other.height && y + candidate.height > other.y));
          if (y === undefined) continue;
          candidate.y = y;
        }
        selected.push(candidate);
      }
      const present = new Set(selected.map(item => item.id));
      for (const [id, element] of this._labelElements) {
        if (!present.has(id)) {
          if (element.contains(document.activeElement)) this._stage.focus({ preventScroll: true });
          element.remove();
          this._labelElements.delete(id);
        }
      }
      selected.sort((a, b) => a.y - b.y || a.x - b.x || a.id.localeCompare(b.id));
      let previous = null;
      for (const item of selected) {
        let element = this._labelElements.get(item.id);
        if (!element) {
          element = document.createElement(item.node && item.node.kind !== "end" && !item.node.preview ? "button" : "span");
          if (element.tagName === "BUTTON") element.type = "button";
          const name = document.createElement("strong");
          const detail = document.createElement("small");
          detail.className = "world-detail";
          const symbol = document.createElement("span");
          symbol.className = "world-symbol";
          symbol.setAttribute("aria-hidden", "true");
          element.append(name, detail, symbol);
          this._labelElements.set(item.id, element);
        }
        const { node, look } = item;
        if (node) {
          const steps = `${node.count} ${node.count === 1 ? "Step" : "Steps"}`;
          element.className = `${node.kind === "region" ? "world-region-label" : `world-label node ${node.kind}-node`}${look.selected ? " selected" : ""}${look.changed ? " changed" : ""}${look.error ? " issue-error" : ""}${look.coverage === "selected" || look.coverage === "covered" ? " example-reached" : look.coverage === "uncovered" ? " example-uncovered" : ""}`;
          if (node.preview) element.className = "world-label world-placement";
          else element.dataset.worldId = node.id;
          element.dataset.kind = node.kind;
          element.dataset.boundary = look.boundary || "solid";
          element.dataset.shape = look.shape || "rectangle";
          element.dataset.coverage = look.coverage || "";
          element.dataset.error = String(Boolean(look.error));
          element.dataset.activity = look.activity || "";
          element.title = look.description || look.detail || "";
          if (node.kind === "region") {
            element.dataset.groupRegionLabel = node.id;
            if (node.group) element.dataset.regionGroup = node.group;
            else delete element.dataset.regionGroup;
          } else if (node.kind !== "end" && !node.preview) {
            element.dataset.nodeId = node.id;
            element.dataset.selectNode = node.id;
          }
          if (element.tagName === "BUTTON") element.setAttribute("aria-pressed", String(Boolean(look.selected)));
          element.setAttribute("aria-label", `${look.label || node.name}${node.kind === "region" ? `, ${steps}. Double-click to zoom in.` : ""}${look.error ? ", has errors" : ""}`);
          element.children[0].textContent = look.label || node.name;
          element.children[1].textContent = node.kind === "region" ? steps
            : node.kind === "trigger" ? `${node.example_count || 0} ${node.example_count === 1 ? "example" : "examples"}` : "";
          element.style.flexDirection = node.expanded ? "row" : "column";
          const symbol = element.children[2];
          let duration = element.querySelector('.world-duration');
          if (item.measured && !duration) { duration = document.createElement('span'); duration.className = 'world-duration'; element.append(duration); }
          if (duration) {
            duration.hidden = !item.measured;
            duration.textContent = item.measured ? `${look.duration} ${node.kind === 'region' ? 'Step avg' : 'avg'}` : '';
            duration.title = 'Sampled mean execution time; not utilization or total flow latency.';
          }
          const bounds = this._symbolBounds(item.box, node, look);
          symbol.hidden = Boolean(node.expanded || item.compact);
          symbol.style.left = `${bounds.x - item.x}px`;
          symbol.style.top = `${bounds.y - item.y}px`;
          symbol.style.width = `${bounds.width}px`;
          symbol.style.height = `${bounds.height}px`;
          symbol.dataset.symbol = look.symbol || node.kind;
          let icon = symbol.querySelector("img");
          if (look.iconUrl) {
            if (!icon) {
              symbol.replaceChildren();
              icon = document.createElement("img");
              icon.className = "flow-icon world-icon";
              icon.alt = "";
              icon.width = icon.height = 24;
              symbol.append(icon);
            }
            if (icon.getAttribute("src") !== look.iconUrl) icon.src = look.iconUrl;
          } else {
            const glyph = {string: "Aa", number: "01", boolean: "T/F", object: "{ }", array: "[ ]"}[look.symbol] || "";
            if (symbol.textContent !== glyph || icon) symbol.textContent = glyph;
            symbol.style.fontSize = `${Math.min(18, bounds.height * .55, bounds.width / (Math.max(1, glyph.length) * .65))}px`;
          }
        } else {
          element.className = "world-link-label";
          if (item.outcome) element.dataset.branchOutcome = item.outcome;
          element.title = item.text;
          element.children[0].textContent = item.text;
          element.children[1].textContent = "";
          element.children[2].hidden = true;
        }
        element.style.left = `${item.x}px`;
        element.style.top = `${item.y}px`;
        element.style.width = `${item.width}px`;
        element.style.height = `${item.height}px`;
        element.style.textAlign = "center";
        element.style.justifyContent = "center";
        const expected = previous ? previous.nextSibling : this._labels.firstChild;
        if (element !== expected) this._labels.insertBefore(element, expected);
        previous = element;
      }
      this._stage.dataset.sceneLabelCount = String(selected.length);
    }
  }

  window.RailixWorld = RailixWorld;
})();
