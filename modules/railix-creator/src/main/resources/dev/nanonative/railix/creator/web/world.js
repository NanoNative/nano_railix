"use strict";

(() => {
  const MAX_NODES = 2048;
  const MAX_SEGMENTS = 4096;
  const MAX_LABELS = 256;
  const MIN_SCALE = 2 ** -32;
  const MAX_SCALE = 2 ** 32;
  const zoomNumber = new Intl.NumberFormat(undefined, { notation: "compact", maximumSignificantDigits: 3 });
  const palette = {
    paper: [243 / 255, 240 / 255, 232 / 255, 1],
    panel: [1, 253 / 255, 248 / 255, 1],
    ink: [23 / 255, 27 / 255, 26 / 255, 1],
    rail: [92 / 255, 99 / 255, 94 / 255, 1],
    line: [149 / 255, 143 / 255, 131 / 255, 1],
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
      this._bufferBytes = 0;
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
        this._buffer = this._array = this._program = null;
        if (this._initializeGraphics()) this.refresh();
      });
      if (!this._initializeGraphics()) return;
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
      this._listen(document, "visibilitychange", () => this.repaint());
      this._listen(this._reducedMotion, "change", () => this.repaint());
      this._resizeObserver = new ResizeObserver(() => this._resize());
      this._resizeObserver.observe(stage);
      this._resize();
    }

    get scene() { return this._scene; }
    get query() { return this._sceneQuery || ""; }
    get viewVersion() { return this._viewVersion; }

    /** Fetch the latest scene for this camera; a newer request supersedes it. */
    async refresh() {
      if (this._disposed || this._lost || !this._gl) return this;
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
          this._frameBounds(focus ? scene.focus : scene.bounds, !focus, focus ? scene.focus_min_scale : 0);
          return this.refresh();
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
      this._pendingFocus = null;
      if (this._scene) this._frameBounds(this._scene.bounds, true);
      else this._initialized = false;
      this.refresh();
      return this;
    }

    /** Resolve a stable Step or region identifier, including outside the current scene. */
    focus(id) {
      if (!this._disposed && id) {
        this._pendingFocus = id;
        this._viewVersion++;
        this.refresh();
      }
      return this;
    }

    /** Cancel an unfinished focus request while retaining the current camera. */
    cancelFocus() {
      if (!this._disposed && this._pendingFocus) {
        this._pendingFocus = null;
        this._viewVersion++;
        this.refresh();
      }
      return this;
    }

    zoom(factor, clientX, clientY) {
      if (this._disposed || !Number.isFinite(factor) || factor <= 0) return this;
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
      this._labels?.replaceChildren();
      this._clearError();
      this._releaseGraphics();
      if (this._canvas) this._canvas.width = this._canvas.height = 1;
      this._gl = null;
      this._scene = null;
      this._sceneNodes = null;
      this._request = null;
      this._callbacks = {};
      this._vertices = new Float32Array(0);
      return this;
    }

    _listen(target, name, listener, options = {}) {
      target.addEventListener(name, listener, { ...options, signal: this._listeners.signal });
    }

    _cancel() {
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
      const dpr = window.devicePixelRatio || 1;
      if (width === this._size.width && height === this._size.height && dpr === this._dpr) return;
      this._size = { width, height };
      this._dpr = dpr;
      const maximum = this._gl.getParameter(this._gl.MAX_RENDERBUFFER_SIZE);
      this._canvas.width = Math.min(maximum, Math.round(width * dpr));
      this._canvas.height = Math.min(maximum, Math.round(height * dpr));
      this._changedCamera();
    }

    _frameBounds(bounds, fit = false, minimumScale = 0) {
      const scale = clamp(Math.max(minimumScale, Math.min(Math.max(1, this._size.width - 96) / bounds.width,
        Math.max(1, this._size.height - 96) / bounds.height)), MIN_SCALE, fit ? 1 : MAX_SCALE);
      this._camera = {
        x: bounds.x + bounds.width / 2 - this._size.width / scale / 2,
        y: bounds.y + bounds.height / 2 - this._size.height / scale / 2, scale
      };
      this._initialized = true;
      this._changedCamera();
    }

    _changedCamera() {
      this._pendingFocus = null;
      this._viewVersion++;
      this.repaint();
      if (!this._requestTimer && !this._disposed && !this._lost) {
        this._requestTimer = setTimeout(() => this.refresh(), 80);
      }
      const level = this._stage.querySelector("#zoom-level");
      if (level) level.textContent = `${zoomNumber.format(this._camera.scale * 100)}%`;
      this._stage.dataset.sceneScale = String(this._camera.scale);
    }

    _wheel(event) {
      if (event.target.closest(".canvas-tools")) return;
      event.preventDefault();
      const unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? this._size.height : 1;
      if (event.shiftKey && !event.ctrlKey && !event.metaKey) {
        this._camera.x += event.deltaX * unit / this._camera.scale;
        this._camera.y += event.deltaY * unit / this._camera.scale;
        this._initialized = true;
        this._changedCamera();
      } else this.zoom(Math.exp(clamp(-event.deltaY * unit * .002, -2, 2)), event.clientX, event.clientY);
    }

    _pointerDown(event) {
      if (this._pan || (event.button !== 0 && event.button !== 1)
        || (event.button === 0 && event.target.closest("button, input, select, textarea, a"))) return;
      event.preventDefault();
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
      if (!this._scene || event.target.closest(".canvas-tools")) return null;
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
        const contour = this._contour(box, node.expanded ? {} : look);
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
        if (node.group) this._callbacks.selectGroup?.(node.group, node.id);
        else this.focus(node.id);
      } else this._callbacks.selectNode?.(node.id);
    }

    _key(event) {
      if (event.target.closest("button") && !event.target.closest("[data-world-id]")) return;
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

    _initializeGraphics() {
      try {
        const gl = this._canvas.getContext("webgl2", { alpha: false, antialias: true, depth: false, stencil: false, powerPreference: "low-power" });
        if (!gl) throw new Error("This browser cannot open the WebGL2 flow canvas. Enable hardware acceleration or use a WebGL2-capable browser.");
        this._gl = gl;
        this._bufferBytes = 0;
        this._program = gl.createProgram();
        const shaders = [];
        try {
          for (const [type, source] of [
            [gl.VERTEX_SHADER, `#version 300 es
              in vec2 position; in vec4 color; in vec2 traffic; uniform vec2 viewport; out vec4 ink; out vec2 rail;
              void main() { gl_Position = vec4(position / viewport * vec2(2., -2.) + vec2(-1., 1.), 0., 1.); ink = color; rail = traffic; }`],
            [gl.FRAGMENT_SHADER, `#version 300 es
              precision highp float; in vec4 ink; in vec2 rail; uniform float travel; out vec4 pixel;
              void main() {
                pixel = ink;
                if (rail.y > 0. && travel >= 0.) {
                  float pulse = 1. - smoothstep(3., 5., mod(rail.x - travel, rail.y));
                  pixel.rgb = mix(ink.rgb, vec3(.91, .88, .74), pulse * .85);
                }
              }`]
          ]) {
            const shader = gl.createShader(type);
            gl.shaderSource(shader, source);
            gl.compileShader(shader);
            if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
              gl.deleteShader(shader);
              throw new Error("The browser could not compile the flow canvas shader.");
            }
            gl.attachShader(this._program, shader);
            shaders.push(shader);
          }
          gl.linkProgram(this._program);
          if (!gl.getProgramParameter(this._program, gl.LINK_STATUS)) throw new Error("The browser could not initialize the flow canvas shader.");
        } finally {
          for (const shader of shaders) {
            gl.detachShader(this._program, shader);
            gl.deleteShader(shader);
          }
        }
        this._array = gl.createVertexArray();
        this._buffer = gl.createBuffer();
        gl.bindVertexArray(this._array);
        gl.bindBuffer(gl.ARRAY_BUFFER, this._buffer);
        for (const [name, size, offset] of [["position", 2, 0], ["color", 4, 8], ["traffic", 2, 24]]) {
          const location = gl.getAttribLocation(this._program, name);
          gl.enableVertexAttribArray(location);
          gl.vertexAttribPointer(location, size, gl.FLOAT, false, 32, offset);
        }
        this._viewport = gl.getUniformLocation(this._program, "viewport");
        this._travel = gl.getUniformLocation(this._program, "travel");
        gl.enable(gl.BLEND);
        gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
        this._clearError();
        return true;
      } catch (error) {
        this._releaseGraphics();
        this._fail(error.message);
        this._gl = null;
        return false;
      }
    }

    _releaseGraphics() {
      if (!this._gl) return;
      if (this._buffer) this._gl.deleteBuffer(this._buffer);
      if (this._array) this._gl.deleteVertexArray(this._array);
      if (this._program) this._gl.deleteProgram(this._program);
      this._buffer = this._array = this._program = null;
    }

    _project(x, y) {
      return [(x - this._camera.x) * this._camera.scale, (y - this._camera.y) * this._camera.scale];
    }

    _screenBounds(node, look = this._callbacks.appearance?.(node) || {}) {
      const [x, y] = this._project(node.x, node.y);
      const width = node.width * this._camera.scale;
      const height = node.height * this._camera.scale;
      if (node.expanded) return { x, y, width, height };
      const stationScale = Math.min(this._camera.scale, 1, width / 168, height / 64);
      const aspect = look.aspect ?? 2.625;
      const stationHeight = Math.min(64, 168 / aspect) * stationScale;
      const stationWidth = stationHeight * aspect;
      return { x: x + (width - stationWidth) / 2, y: y + (height - stationHeight) / 2,
        width: stationWidth, height: stationHeight };
    }

    _linkPoints(link) {
      const points = link.points.map(point => this._project(...point));
      for (const [id, index] of [[link.from, 0], [link.to, points.length - 1]]) {
        const node = this._sceneNodes.get(id);
        if (!node) continue;
        const look = this._callbacks.appearance?.(node) || {};
        const box = this._screenBounds(node, look);
        const [x, y] = link.points[index];
        const port = [box.x + (x - node.x) / node.width * box.width,
          box.y + (y - node.y) / node.height * box.height];
        const cx = box.x + box.width / 2, cy = box.y + box.height / 2;
        const dx = port[0] - cx, dy = port[1] - cy;
        const contour = this._contour(box, node.expanded ? {} : look);
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
      return points;
    }

    _contour(box, look) {
      const {x, y, width: w, height: h} = box;
      if (look.shape === "triangle") return [[x, y], [x + w, y + h / 2], [x, y + h]];
      if (look.shape === "diamond") return [[x + w / 2, y], [x + w, y + h / 2], [x + w / 2, y + h], [x, y + h / 2]];
      if (look.shape === "ellipse") return Array.from({length: 32}, (_, i) => {
        const angle = i * Math.PI / 16;
        return [x + w / 2 + Math.cos(angle) * w / 2, y + h / 2 + Math.sin(angle) * h / 2];
      });
      const r = Math.min(w, h) * (look.roundness || 0) / 100;
      if (!r) return [[x, y], [x + w, y], [x + w, y + h], [x, y + h]];
      const points = [];
      for (const [cx, cy, angle] of [[x + w - r, y + r, -Math.PI / 2], [x + w - r, y + h - r, 0],
        [x + r, y + h - r, Math.PI / 2], [x + r, y + r, Math.PI]]) {
        for (let i = 0; i <= 6; i++) points.push([cx + r * Math.cos(angle + i * Math.PI / 12), cy + r * Math.sin(angle + i * Math.PI / 12)]);
      }
      return points;
    }

    _triangle(ax, ay, bx, by, cx, cy, ink, alongA = 0, alongB = 0, alongC = 0, spacing = 0) {
      if (this._vertexLength + 24 > this._vertices.length) {
        const next = new Float32Array(this._vertices.length * 2);
        next.set(this._vertices);
        this._vertices = next;
      }
      this._vertex(ax, ay, ink, alongA, spacing);
      this._vertex(bx, by, ink, alongB, spacing);
      this._vertex(cx, cy, ink, alongC, spacing);
    }

    _vertex(x, y, ink, along, spacing) {
      let offset = this._vertexLength;
      this._vertices[offset++] = x;
      this._vertices[offset++] = y;
      this._vertices[offset++] = ink[0];
      this._vertices[offset++] = ink[1];
      this._vertices[offset++] = ink[2];
      this._vertices[offset++] = ink[3];
      this._vertices[offset++] = along;
      this._vertices[offset++] = spacing;
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

    _line(ax, ay, bx, by, width, ink, spacing = 0, distance = 0) {
      const dx = bx - ax;
      const dy = by - ay;
      const length = Math.hypot(dx, dy);
      if (!length) return 0;
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
      const nx = -dy / length * width / 2;
      const ny = dx / length * width / 2;
      const along = spacing ? (distance + start * length) % spacing : 0;
      const until = along + (end - start) * length;
      this._triangle(x1 + nx, y1 + ny, x2 + nx, y2 + ny, x2 - nx, y2 - ny, ink, along, until, until, spacing);
      this._triangle(x1 + nx, y1 + ny, x2 - nx, y2 - ny, x1 - nx, y1 - ny, ink, along, until, along, spacing);
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

    _station(node, look, box) {
      const accent = look.error ? palette.red : look.selected || look.coverage === "selected" ? palette.teal : color(look.color);
      const shaped = !node.expanded && ((look.shape && look.shape !== "rectangle") || look.roundness > 0);
      if (shaped) {
        const points = this._contour(box, look);
        const cx = box.x + box.width / 2, cy = box.y + box.height / 2;
        for (let i = 0; i < points.length; i++) {
          const a = points[i], b = points[(i + 1) % points.length];
          this._triangle(cx, cy, ...a, ...b, palette.panel);
        }
        const dash = look.boundary === "dotted" ? 2 : look.boundary === "dashed" ? 8 : 0;
        let distance = 0;
        for (let i = 0; i < points.length; i++) {
          const a = points[i], b = points[(i + 1) % points.length];
          const length = Math.hypot(b[0] - a[0], b[1] - a[1]);
          if (!dash) this._line(...a, ...b, look.selected ? 3 : 1.5, accent);
          else for (let offset = -(distance % (dash + 5)); offset < length; offset += dash + 5) {
            const start = Math.max(0, offset) / length, end = Math.min(length, offset + dash) / length;
            if (end > start) this._line(a[0] + (b[0] - a[0]) * start, a[1] + (b[1] - a[1]) * start,
              a[0] + (b[0] - a[0]) * end, a[1] + (b[1] - a[1]) * end, look.selected ? 3 : 1.5, accent);
          }
          distance += length;
        }
      } else {
        this._rect(box.x, box.y, box.width, box.height, node.expanded ? [...accent.slice(0, 3), .035] : palette.panel);
        this._outline(box, look.selected ? 3 : node.expanded ? 1 : 1.5,
          node.expanded && !look.selected && !look.error && !look.color ? palette.line : accent, look.boundary);
      }
      box = this._contentBounds(box, node, look);
      const { x, y, width, height } = box;
      if (!node.expanded) this._rect(x + 5, y + 5, Math.min(4, width - 10), height - 10, accent);
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
      if (look.meter > 0 && width > 16) this._rect(x + 8, y + height - 4,
        (width - 16) * clamp(look.meter, 0, 1), 2, accent);
      if (!node.expanded && look.heat !== undefined && width > 16) {
        const heat = clamp(look.heat, 0, 1);
        this._rect(x + 8, y + height - 8, width - 16, 2,
          [(.25 + heat * .55), (.52 - heat * .2), (.53 - heat * .35), 1]);
      }
    }

    _contentBounds(box, node, look) {
      if (node.expanded) return box;
      if (!look.shape || look.shape === "rectangle") {
        const inset = Math.min(box.width, box.height) * (look.roundness || 0) / 100 * (1 - Math.SQRT1_2);
        return {x: box.x + inset, y: box.y + inset, width: box.width - 2 * inset, height: box.height - 2 * inset};
      }
      const factor = look.shape === "ellipse" ? .7 : look.shape === "triangle" ? 1 / 3 : .45;
      return {x: box.x + box.width * (1 - factor) / 2, y: box.y + box.height * (1 - factor) / 2,
        width: box.width * factor, height: box.height * factor};
    }

    _draw() {
      const gl = this._gl;
      if (!gl || this._disposed || this._lost) return;
      this._vertexLength = 0;
      this._moving = false;
      const grid = 32 * 2 ** Math.floor(Math.log2(1 / this._camera.scale));
      const spacing = grid * this._camera.scale;
      const gridInk = [...palette.ink.slice(0, 3), .045];
      for (let x = -((this._camera.x % grid) * this._camera.scale); x < this._size.width; x += spacing) this._rect(x, 0, 1, this._size.height, gridInk);
      for (let y = -((this._camera.y % grid) * this._camera.scale); y < this._size.height; y += spacing) this._rect(0, y, this._size.width, 1, gridInk);
      if (this._scene) {
        const stations = this._scene.nodes.map(node => {
          const look = this._callbacks.appearance?.(node) || {};
          return {node, look, box: this._screenBounds(node, look)};
        });
        const visible = stations.filter(({ box }) => box.x + box.width >= 0 && box.y + box.height >= 0 && box.x <= this._size.width && box.y <= this._size.height);
        for (const { node, look, box } of visible.filter(item => item.node.expanded).sort((a, b) => b.box.width * b.box.height - a.box.width * a.box.height)) this._station(node, look, box);
        // Shared trunks retain active traffic, with the selected Example painted last.
        const rails = this._scene.links.map(link => ({link, look: this._callbacks.linkAppearance?.(link) || {}}));
        rails.sort((a, b) => Number(Boolean(a.look.selected)) - Number(Boolean(b.look.selected))
          || (a.look.rate || 0) - (b.look.rate || 0));
        for (const {link, look} of rails) {
          const ink = look.selected ? palette.teal : look.color ? color(look.color) : palette.rail;
          const points = this._linkPoints(link);
          const spacing = Number.isFinite(look.rate) && look.rate > 0 ? 128 / (1 + Math.min(6, Math.log10(1 + look.rate))) : 0;
          const beforeVertices = this._vertexLength;
          let distance = 0;
          for (let index = 1; index < points.length; index++) distance += this._line(...points[index - 1], ...points[index], look.width || 3, ink, spacing, distance);
          this._moving ||= spacing > 0 && this._vertexLength > beforeVertices;
          const end = points.at(-1);
          const before = points.at(-2);
          const length = Math.hypot(end[0] - before[0], end[1] - before[1]);
          if (length > 10 && end[0] >= -10 && end[1] >= -10 && end[0] <= this._size.width + 10 && end[1] <= this._size.height + 10) {
            const dx = (end[0] - before[0]) / length;
            const dy = (end[1] - before[1]) / length;
            this._triangle(end[0], end[1], end[0] - dx * 9 - dy * 4, end[1] - dy * 9 + dx * 4,
              end[0] - dx * 9 + dy * 4, end[1] - dy * 9 - dx * 4, ink);
          }
        }
        for (const { node, look, box } of visible) if (!node.expanded) this._station(node, look, box);
        this._renderLabels(visible);
      }
      gl.bindVertexArray(this._array);
      gl.bindBuffer(gl.ARRAY_BUFFER, this._buffer);
      if (this._vertices.byteLength !== this._bufferBytes) {
        gl.bufferData(gl.ARRAY_BUFFER, this._vertices.byteLength, gl.DYNAMIC_DRAW);
        this._bufferBytes = this._vertices.byteLength;
      }
      gl.bufferSubData(gl.ARRAY_BUFFER, 0, this._vertices.subarray(0, this._vertexLength));
      this._animate(performance.now(), true);
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
      const gl = this._gl;
      gl.viewport(0, 0, this._canvas.width, this._canvas.height);
      gl.clearColor(...palette.paper);
      gl.clear(gl.COLOR_BUFFER_BIT);
      gl.useProgram(this._program);
      gl.bindVertexArray(this._array);
      gl.uniform2f(this._viewport, this._size.width, this._size.height);
      gl.uniform1f(this._travel, moving ? time * .048 : -1);
      gl.drawArrays(gl.TRIANGLES, 0, this._vertexLength / 8);
      const animated = String(moving);
      if (this._stage.dataset.trafficAnimated !== animated) this._stage.dataset.trafficAnimated = animated;
      if (moving) this._motionFrame = requestAnimationFrame(next => this._animate(next));
    }

    _renderLabels(visible) {
      const candidates = [];
      for (const station of visible) {
        const {node, look} = station;
        const box = this._contentBounds(station.box, node, look);
        const compact = box.width < 38 || box.height < 18 || (look.shape && look.shape !== "rectangle" && box.width < 100);
        if ((station.box.width < 38 || station.box.height < 18) && !look.selected
          && !["app", "trigger", "region"].includes(node.kind)) continue;
        if (box.x + box.width < 0 || box.y + box.height < 0) continue;
        const expanded = node.expanded;
        const width = compact ? Math.min(120, Math.max(48, (look.label || node.name).length * 6 + 12))
          : Math.min(box.width - 20, expanded ? 260 : 320);
        const height = compact ? 28 : Math.min(box.height, expanded ? 30 : 56);
        const x = compact ? clamp(box.x + (box.width - width) / 2, 0, this._size.width - width)
          : box.x + (box.width - width) / 2;
        const y = compact ? Math.max(0, box.y - height - 4) : box.y + (expanded ? 6 : (box.height - height) / 2);
        if (x < -width || y < -height || x > this._size.width || y > this._size.height) continue;
        candidates.push({ id: `node:${node.id}`, node, look, compact, x, y, width, height,
          rank: look.selected ? 1000 : look.error ? 900 : node.kind === "trigger" || node.kind === "app" ? 800 : node.kind === "region" ? 700 : 600 });
      }
      const outgoing = new Map();
      for (const link of this._scene.links) outgoing.set(link.from, (outgoing.get(link.from) || 0) + 1);
      for (const link of this._scene.links) {
        if (outgoing.get(link.from) < 2 || !link.outcome) continue;
        const label = this._callbacks.linkLabel?.(link);
        if (!label) continue;
        const width = Math.min(160, Math.max(56, label.length * 6 + 16));
        const points = this._linkPoints(link);
        const start = points[0];
        const end = points.at(-1);
        if (Math.hypot(end[0] - start[0], end[1] - start[1]) < 40) continue;
        const x = end[0] >= start[0] ? end[0] - width - 8 : end[0] + 8;
        const y = end[1] > start[1] ? end[1] + 4 : end[1] - 24;
        if (x < 0 || y < 0 || x + width > this._size.width || y + 20 > this._size.height) continue;
        candidates.push({ id: `connection:${link.id}`, text: label, outcome: link.outcome, x, y, width, height: 20, rank: 500 });
      }
      candidates.sort((a, b) => b.rank - a.rank || a.id.localeCompare(b.id));
      const selected = [];
      for (const candidate of candidates) {
        if (selected.length === MAX_LABELS) break;
        if (candidate.rank < 1000 && selected.some(other => candidate.x < other.x + other.width && candidate.x + candidate.width > other.x
          && candidate.y < other.y + other.height && candidate.y + candidate.height > other.y)) continue;
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
          element = document.createElement(item.node && item.node.kind !== "end" ? "button" : "span");
          if (element.tagName === "BUTTON") element.type = "button";
          const name = document.createElement("strong");
          const detail = document.createElement("small");
          detail.className = "world-detail";
          element.append(name, detail);
          this._labelElements.set(item.id, element);
        }
        const { node, look } = item;
        if (node) {
          const steps = `${node.count} ${node.count === 1 ? "Step" : "Steps"}`;
          element.className = `${node.kind === "region" ? "world-region-label" : `world-label node ${node.kind}-node`}${look.selected ? " selected" : ""}${look.changed ? " changed" : ""}${look.error ? " issue-error" : ""}${look.coverage === "selected" || look.coverage === "covered" ? " example-reached" : look.coverage === "uncovered" ? " example-uncovered" : ""}`;
          element.dataset.worldId = node.id;
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
          } else if (node.kind !== "end") {
            element.dataset.nodeId = node.id;
            element.dataset.selectNode = node.id;
          }
          if (element.tagName === "BUTTON") element.setAttribute("aria-pressed", String(Boolean(look.selected)));
          element.setAttribute("aria-label", `${look.label || node.name}${node.kind === "region" ? `, ${steps}. Double-click to zoom in.` : ""}${look.error ? ", has errors" : ""}`);
          element.children[0].textContent = look.label || node.name;
          element.children[1].textContent = item.compact ? "" : look.detail || (node.kind === "region" ? steps : "");
          element.style.flexDirection = node.expanded ? "row" : "column";
          let icon = element.querySelector("img");
          if (look.iconUrl) {
            if (!icon) {
              icon = document.createElement("img");
              icon.className = "flow-icon world-icon";
              icon.alt = "";
              icon.width = icon.height = 20;
              icon.style.position = "absolute";
              icon.style.left = "2px";
              icon.style.top = "calc(50% - 10px)";
              element.append(icon);
            }
            if (icon.getAttribute("src") !== look.iconUrl) icon.src = look.iconUrl;
          } else icon?.remove();
          element.style.paddingLeft = look.iconUrl ? "26px" : "";
        } else {
          element.className = "world-link-label";
          element.dataset.branchOutcome = item.outcome;
          element.title = item.text;
          element.children[0].textContent = item.text;
          element.children[1].textContent = "";
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
