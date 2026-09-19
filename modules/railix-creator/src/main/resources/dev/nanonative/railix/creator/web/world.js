"use strict";

(() => {
  const MAX_NODES = 2048;
  const MAX_SEGMENTS = 4096;
  const MAX_LABELS = 256;
  const MIN_SCALE = 2 ** -32;
  const MAX_SCALE = 2 ** 32;
  const zoomNumber = new Intl.NumberFormat(undefined, { notation: "compact", maximumSignificantDigits: 3 });
  const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
  const validBounds = value => value && [value.x, value.y, value.width, value.height].every(Number.isFinite)
    && value.width > 0 && value.height > 0;
  const color = value => /^#[\da-f]{6}$/i.test(value || "") ? value : "#53d8d1";

  /** A bounded, camera-relative view of the Creator-owned graph scene. */
  class RailixWorld {
    static appearance = Object.freeze({shape: "rectangle", aspect: 1, roundness: 12});

    constructor(stage, callbacks = {}) {
      this._stage = stage;
      this._plane = stage.querySelector("#world-plane");
      this._parts = new Map();
      this._motionSequence = 0;
      this._reveals = new Set();
      this._labels = stage.querySelector("#world-labels");
      this._callbacks = callbacks;
      this._camera = { x: 0, y: 0, scale: 1 };
      this._size = { width: 1, height: 1 };
      this._scene = null;
      this._labelElements = new Map();
      this._listeners = new AbortController();
      this._frame = 0;
      this._moving = false;
      this._requestTimer = 0;
      this._requestId = 0;
      this._viewVersion = 0;
      this._initialized = false;
      this._disposed = false;
      this._message = stage.querySelector("#world-error");
      this._cursor = stage.querySelector("#world-cursor");
      if (!this._plane || !this._labels) {
        this._fail("The factory view is missing. Reload Creator.");
        return;
      }
      this._factory = document.createElement('div');
      this._factory.className = 'world-factory';
      this._ground = document.createElement('div');
      this._ground.className = 'world-projection';
      this._ground.append(this._factory);
      this._cargoLayer = document.createElement('div');
      this._cargoLayer.className = 'world-cargo';
      this._stationPlane = document.createElement('div');
      this._stationPlane.className = 'world-projection';
      this._stations = document.createElement('div');
      this._stations.className = 'world-factory';
      this._stationPlane.append(this._stations);
      // Independent contexts: ground transport, screen-space parcels, then raised buildings.
      this._plane.append(this._ground, this._cargoLayer, this._stationPlane);
      stage.tabIndex = 0;
      stage.setAttribute("aria-label", "Flow canvas. Scroll to zoom, drag to pan. Use arrow keys to pan, plus and minus to zoom, Home for App start.");
      stage.dataset.renderer = "css";
      this._listen(stage, "wheel", event => this._wheel(event), { passive: false });
      this._listen(stage, "pointerdown", event => this._pointerDown(event));
      this._listen(stage, "pointermove", event => this._pointerMove(event));
      this._listen(stage, "pointerleave", () => {
        if (this._cursor) this._cursor.hidden = true;
        stage.classList.remove("world-aim");
        this._hovered = '';
        this._callbacks.hoverTrigger?.('');
      });
      this._listen(stage, "pointerup", event => this._pointerEnd(event));
      this._listen(stage, "pointercancel", event => this._pointerEnd(event));
      this._listen(stage, "lostpointercapture", event => this._pointerEnd(event));
      this._listen(stage, "click", event => this._click(event));
      this._listen(stage, "dblclick", event => {
        const node = this._eventNode(event);
        if (node && node.kind !== "end") {
          event.preventDefault();
          event.stopPropagation();
          node.kind === 'region' ? this.enter(node.id) : this.focus(node.id);
        }
      });
      this._listen(stage, "keydown", event => this._key(event));
      this._map = stage.querySelector("#world-map");
      if (this._map) this._listen(this._map, "click", event => {
        if (!this._overview) return;
        const rect = this._map.getBoundingClientRect(), bounds = this._mapBounds;
        const view = this._viewport();
        const x = event.detail ? bounds.x + clamp((event.clientX - rect.left) / rect.width, 0, 1) * bounds.width : bounds.x + bounds.width/2;
        const y = event.detail ? bounds.y + clamp((event.clientY - rect.top) / rect.height, 0, 1) * bounds.height : bounds.y + bounds.height/2;
        this._camera.x += x - view.x - view.width/2;
        this._camera.y += y - view.y - view.height/2;
        this._changedCamera();
      });
      if (this._map) this._listen(this._map, "keydown", event => {
        const direction = {ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1]}[event.key];
        if (!direction || !this._overview) return;
        event.preventDefault();
        event.stopPropagation();
        this._camera.x += direction[0] * this._mapBounds.width / 10;
        this._camera.y += direction[1] * this._mapBounds.height / 10;
        this._changedCamera();
      });
      this._listen(window, "resize", () => this._resize());
      this._listen(document, "visibilitychange", () => {
        const moving = Boolean(this._flightFrame);
        this._cancelFlight();
        if (moving || !document.hidden) this._changedCamera(!document.hidden);
        else this.repaint();
      });
      this._resizeObserver = new ResizeObserver(() => this._resize());
      this._resizeObserver.observe(stage);
      this._resize();
    }

    get scene() { return this._scene; }
    get query() { return this._sceneQuery || ""; }

    /** Switch drawing after assets load; scene, camera and event handlers remain shared. */
    async renderer(variant = {}, signal, commit = () => {}) {
      const type = variant.renderer || 'css';
      if (!['css','canvas'].includes(type)) throw Error(`Unsupported world renderer: ${type}.`);
      const assets = type === 'canvas' ? await RailixCanvas.load(variant.atlas, signal) : null;
      if (this._disposed || signal?.aborted) {
        assets?.sprites.forEach(sprite => sprite.bitmap.close());
        signal?.throwIfAborted();return this;
      }
      commit();
      this._surface?.dispose();
      this._surface = assets ? new RailixCanvas(this._plane, assets) : null;
      this._stage.dataset.renderer = type;
      this._batch = null;
      this._resize();
      this._draw();
      return this;
    }

    /** Render one bounded building portrait with the world's materials, without another scene or camera loop. */
    portrait(node, host) {
      if (!host) return this;
      const look = this._appearance(node), aspect = look.aspect ?? RailixWorld.appearance.aspect;
      host.classList.toggle('sprite-portrait',Boolean(this._surface));
      if (this._surface) {this._surface.portrait(node,look,host);return this;}
      const height = 136 / Math.max(1, aspect), width = height * aspect;
      if (host.firstElementChild?.tagName === 'CANVAS') host.replaceChildren();
      const element = host.firstElementChild || host.appendChild(document.createElement('div'));
      element.className = 'machine';
      this._machine({node:{...node,expanded:false}, look:{...look,selected:false},
        box:{x:(136-width)/2,y:(136-height)/2,width,height}, unit:1, element, portrait:true});
      return this;
    }

    /** Fetch the latest scene for this camera; a newer request supersedes it. */
    async refresh() {
      if (this._disposed || this._flightFrame) return this;
      clearTimeout(this._requestTimer);
      this._requestTimer = 0;
      this._request?.abort();
      const controller = new AbortController();
      this._request = controller;
      const requestId = ++this._requestId;
      const viewVersion = this._viewVersion;
      const focus = this._pendingFocus;
      const query = new URLSearchParams({...this._viewport(), scale: this._camera.scale});
      if (focus) query.set("focus", focus);
      if (this._groups?.length) query.set('inside', this._groups.at(-1).id);
      try {
        const response = await fetch(`/api/scene?${query}`, { signal: controller.signal, cache: "no-store" });
        if (!response.ok) throw new Error(`The graph scene could not be loaded (HTTP ${response.status}).`);
        const scene = await response.json();
        if (this._disposed || requestId !== this._requestId || viewVersion !== this._viewVersion) return this;
        this._validate(scene);
        if (focus && (!validBounds(scene.focus) || !Number.isFinite(scene.focus_min_scale) || scene.focus_min_scale < 0)) {
          throw new Error("The selected Step or group is not available in this project.");
        }
        this._revealFrom = this._sceneNodes;
        const geometry = JSON.stringify([scene.revision, scene.nodes, scene.links]);
        this._geometryDirty ||= geometry !== this._sceneGeometry;
        this._sceneGeometry = geometry;
        this._scene = scene;
        this._sceneQuery = query.toString();
        this._sceneNodes = new Map(scene.nodes.map(node => [node.id, node]));
        const entering = this._zoomEntry;
        if (entering && !this._groups?.some(group => group.id === entering.id)
          && scene.nodes.some(node => node.id === entering.id && node.expanded || node.regions?.includes(entering.id))) {
          return this.enter(entering.id);
        }
        this._stage.dataset.sceneNodeCount = String(scene.nodes.length);
        this._stage.dataset.sceneLinkCount = String(scene.links.length);
        this._stage.dataset.sceneRevision = String(scene.revision);
        this._clearError();
        this._callbacks.onScene?.(scene);
        if (!this._initialized || focus) {
          this._pendingFocus = null;
          let fixedScale = this._focusScale;
          this._focusScale = undefined;
          const group = this._groups?.at(-1);
          const entry = group?.id === focus ? scene.entry : null;
          if (entry) {
            group.unit = Math.min(1, entry.station_scale);
            fixedScale = .5 / group.unit;
          }
          if (!this._frameBounds(entry || (focus ? scene.focus : scene.bounds), {fit:!focus,
            minimumScale:focus ? scene.focus_min_scale : 0, fixedScale,
            station:entry || (focus && this._sceneNodes.get(focus))})) return this.refresh();
          return this;
        }
        this._queueDraw();
      } catch (error) {
        if (!controller.signal.aborted && !this._disposed && requestId === this._requestId && viewVersion === this._viewVersion) {
          this._pendingFocus = null;
          this._fail(error.message || "The graph scene could not be loaded.");
        }
      }
      return this;
    }

    /** Reconcile the visible scene once. CSS owns transport motion, without a JavaScript game loop. */
    repaint() {
      this._geometryDirty = true;
      return this._queueDraw();
    }

    _queueDraw() {
      if (!this._frame && !this._disposed) {
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
        if (this._frameBounds(this._scene.bounds, {fit:true})) return this;
      }
      else this._initialized = false;
      this.refresh();
      return this;
    }

    /** Resolve a stable Step or region identifier, including outside the current scene. */
    focus(id, scale) {
      if (!this._disposed && id) {
        if (id === 'app') { this._groups = []; this._zoomEntry = null; this._callbacks.onGroup?.(null); }
        this._cancelFlight();
        this._pendingFocus = id;
        this._focusScale = Number.isFinite(scale) && scale > 0 ? scale : undefined;
        this._viewVersion++;
        this.refresh();
      }
      return this;
    }

    /** Enter a visual region without persisting it or changing application execution. */
    enter(id) {
      const node = this._sceneNodes.get(id) || (this._zoomEntry?.id === id ? this._zoomEntry : null);
      if (!node || node.kind !== 'region' || this._groups?.at(-1)?.id === id) return this;
      const camera = this._zoomEntry?.id === id ? this._zoomEntry.camera : {...this._camera};
      this._zoomEntry = null;
      (this._groups ||= []).push({id, name:node.name, count:node.count, camera});
      this._callbacks.onGroup?.(node);
      return this.focus(id);
    }

    /** Restore the enclosing view; zooming alone never exits an entered group. */
    leave() {
      const group = this._groups?.pop();
      if (!group) return this;
      this._cancelFlight();
      this._zoomEntry = null;
      this._camera = group.camera;
      this._callbacks.onGroup?.(this._groups.at(-1) || null);
      this._changedCamera();
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
      const rect = clientX === undefined && clientY === undefined ? {left:0, top:0} : this._stage.getBoundingClientRect();
      const [x, y] = this._unproject(clientX === undefined ? this._size.width / 2 : clientX - rect.left,
        clientY === undefined ? this._size.height / 2 : clientY - rect.top);
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
      this._mapRequest?.abort();
      clearTimeout(this._mapRetry);
      if (this._cursor) this._cursor.hidden = true;
      this._stage.classList.remove("world-aim");
      if (this._map) { this._map.hidden = true; this._map.querySelector('.map-cells').replaceChildren(); }
      this._disposed = true;
      this._surface?.dispose();
      this._surface = null;
      this._mapElements?.clear();
      this._mapGrid = this._mapNodes = this._mapSelection = this._overview = this._terrainClearance = null;
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
      this._parts.clear();
      this._used?.clear();
      this._plane?.replaceChildren();
      this._factory = this._ground = this._cargoLayer = this._stations = this._stationPlane = null;
      this._batch = null;
      this._sceneGeometry = null;
      this._stage.dataset.trafficAnimated = "false";
      this._scene = null;
      this._sceneNodes = null;
      this._revealFrom = null;
      this._placement = null;
      this._request = null;
      this._callbacks = {};
      return this;
    }

    _listen(target, name, listener, options = {}) {
      target.addEventListener(name, listener, { ...options, signal: this._listeners.signal });
    }

    _cancel() {
      this._cancelFlight();
      this._reveals.forEach(animation => animation.cancel());
      this._reveals.clear();
      this._requestId++;
      this._request?.abort();
      clearTimeout(this._requestTimer);
      clearTimeout(this._motionTimer);
      cancelAnimationFrame(this._frame);
      this._requestTimer = this._frame = 0;
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
      if (scene.inside && (!validBounds(scene.entry) || !Number.isFinite(scene.entry.station_scale) || scene.entry.station_scale <= 0)) {
        throw new Error("The group entry is not available in this scene.");
      }
      const ids = new Set();
      let segments = 0;
      for (const node of scene.nodes) {
        if (typeof node.id !== "string" || ids.has(node.id) || !validBounds(node)
          || !Number.isFinite(node.station_scale) || node.station_scale <= 0) throw new Error("The graph scene contains an invalid station.");
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
      if (this._disposed) return;
      const width = Math.max(1, this._stage.clientWidth);
      const height = Math.max(1, this._stage.clientHeight);
      // CSS owns the camera; its projected matrix also owns picking and viewport queries.
      const theme = getComputedStyle(document.documentElement);
      const dimension = (name, fallback) => {
        const value = Number(theme.getPropertyValue(name));
        return Number.isFinite(value) && value > 0 ? value : fallback;
      };
      this._buildings = theme.getPropertyValue('--world-buildings').trim() === '1';
      this._buildingHeight = dimension('--world-building-height', 112);
      document.documentElement.dataset.architecture = this._buildings ? 'buildings' : 'classic';
      this._mapAspect = dimension('--map-aspect', 1.6);
      this._mapRange = dimension('--map-range', 3);
      this._projection = new DOMMatrixReadOnly(getComputedStyle(this._ground).transform);
      // Bake the three visible cube faces into one CSS paint surface for this camera.
      const m=this._projection, project=([x,y,z])=>[m.m11*x+m.m21*y+m.m31*z,m.m12*x+m.m22*y+m.m32*z];
      const outline=[[-6,-6,12],[6,-6,12],[6,6,12],[6,6,0],[-6,6,0],[-6,-6,0]].map(project);
      const center=project([-6,6,12]), low=[0,1].map(axis=>Math.min(...outline.map(p=>p[axis])));
      const size=[0,1].map(axis=>Math.max(...outline.map(p=>p[axis]))-low[axis]);
      const angle=p=>(Math.atan2(p[0]-center[0],center[1]-p[1])*180/Math.PI+360)%360;
      this._cargoLayer.style.setProperty('--cargo-origin', `${low[0]}px,${low[1]}px`);
      this._cargoLayer.style.setProperty('--cargo-width', `${size[0]}px`);
      this._cargoLayer.style.setProperty('--cargo-height', `${size[1]}px`);
      this._cargoLayer.style.setProperty('--cargo-outline', `polygon(${outline.map(p=>p.map((v,axis)=>(v-low[axis])/size[axis]*100+'%').join(' ')).join(',')})`);
      this._cargoLayer.style.setProperty('--cargo-center', center.map((v,axis)=>(v-low[axis])/size[axis]*100+'%').join(' '));
      this._cargoLayer.style.setProperty('--cargo-angle', `${angle(outline[2])}deg`);
      this._cargoLayer.style.setProperty('--cargo-face-one', `${angle(outline[4])-angle(outline[2])}deg`);
      this._cargoLayer.style.setProperty('--cargo-face-two', `${angle(outline[0])-angle(outline[2])}deg`);
      this.repaint();
      if (width === this._size.width && height === this._size.height) return;
      this._cancelFlight();
      this._size = {width, height};
      this._changedCamera();
    }

    _controlBounds() {
      const stage = this._stage.getBoundingClientRect();
      return [...document.querySelectorAll('#world-hud, #group-navigation:not([hidden]), .inspector:not([hidden])')].map(item => {
        const rect = item.getBoundingClientRect();
        return {x:rect.x-stage.x, y:rect.y-stage.y, width:rect.width, height:rect.height};
      });
    }

    _frameBounds(bounds, {fit = false, minimumScale = 0, fixedScale, station} = {}) {
      const m = this._projection;
      const body = station && station.kind !== 'region' ? this._screenBounds(station) : null;
      const width = body ? body.width / this._camera.scale : Math.abs(m.m11) * bounds.width + Math.abs(m.m21) * bounds.height;
      const height = body ? body.height / this._camera.scale : Math.abs(m.m12) * bounds.width + Math.abs(m.m22) * bounds.height;
      const framing = body ? 2 : 1;
      let frames = [{x:0, y:0, ...this._size}];
      if (station && this._groups?.length) {
        for (const control of this._controlBounds()) frames = frames.flatMap(frame => {
          const left = Math.max(frame.x, control.x), top = Math.max(frame.y, control.y);
          const right = Math.min(frame.x+frame.width, control.x+control.width);
          const bottom = Math.min(frame.y+frame.height, control.y+control.height);
          if (left >= right || top >= bottom) return [frame];
          return [{...frame,width:left-frame.x}, {...frame,x:right,width:frame.x+frame.width-right},
            {...frame,height:top-frame.y}, {...frame,y:bottom,height:frame.y+frame.height-bottom}]
            .filter(candidate => candidate.width > 0 && candidate.height > 0);
        });
      }
      const capacity = frame => Math.min(Math.max(1,frame.width-140)/width, Math.max(1,frame.height-160)/height)/framing;
      const frame = frames.reduce((best,candidate) => capacity(candidate)>capacity(best) ? candidate : best,
        frames[0] || {x:0,y:0,...this._size});
      const scale = clamp(fixedScale ?? Math.max(minimumScale,capacity(frame)), MIN_SCALE, fit ? 1 : MAX_SCALE);
      const [cx, cy] = this._unproject(frame.x+frame.width/2, frame.y+frame.height/2+24);
      const target = {x: bounds.x + bounds.width / 2 - cx / scale,
        y: bounds.y + bounds.height / 2 - cy / scale, scale};
      this._cancelFlight();
      if (this._initialized && !document.hidden && !this._callbacks.reducedMotion?.()) {
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
          if (progress < 1 && !document.hidden && !this._callbacks.reducedMotion?.()) this._flightFrame = requestAnimationFrame(move);
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
      this._reveals.forEach(animation => animation.cancel());
      this._reveals.clear();
      this._viewVersion++;
      this._queueDraw();
      if (refresh && !this._requestTimer && !this._disposed) {
        this._requestTimer = setTimeout(() => this.refresh(), 80);
      }
      const level = this._stage.ownerDocument.getElementById("zoom-level");
      if (level) level.textContent = `${zoomNumber.format(this._camera.scale * (this._groups?.at(-1)?.unit || 1) * 100)}%`;
      this._stage.dataset.sceneScale = String(this._camera.scale);
    }

    _wheel(event) {
      if (event.target.closest(".canvas-tools, [data-world-overlay]")) return;
      event.preventDefault();
      const unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? this._size.height : 1;
      if (event.shiftKey && !event.ctrlKey && !event.metaKey) {
        this._cancelFlight();
        this._panBy(event.deltaX * unit, event.deltaY * unit);
        this._initialized = true;
        this._changedCamera();
      } else {
        const node = event.deltaY < 0 ? this._eventNode(event, 'region') : null;
        this._zoomEntry = node?.kind === 'region' && !node.expanded
          ? {...node, camera:this._zoomEntry?.id === node.id ? this._zoomEntry.camera : {...this._camera}} : null;
        this.zoom(Math.exp(clamp(-event.deltaY * unit * .002, -2, 2)), event.clientX, event.clientY);
      }
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
      if (this._cursor) {
        const active = event.pointerType === "mouse" && !event.target.closest(".canvas-tools, [data-world-overlay]");
        this._cursor.hidden = !active;
        this._stage.classList.toggle("world-aim", active);
        if (active) this._cursor.style.translate = `${event.clientX}px ${event.clientY}px`;
      }
      if (this._pan?.id !== event.pointerId) {
        if (!event.target.closest('#trigger-example')) {
          const hovered = this._eventNode(event, 'trigger');
          const id = hovered?.kind === 'trigger' ? hovered.id : '';
          if (id !== this._hovered) { this._hovered = id; this._callbacks.hoverTrigger?.(id); }
        }
        return;
      }
      const dx = event.clientX - this._pan.x;
      const dy = event.clientY - this._pan.y;
      if ((!dx && !dy) || (!this._pan.dragging && Math.abs(dx) + Math.abs(dy) <= 4)) return;
      this._pan.dragging = true;
      this._pan.x = event.clientX;
      this._pan.y = event.clientY;
      this._panBy(-dx, -dy);
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

    _eventNode(event, kind) {
      if (!this._scene || event.target.closest(".canvas-tools, [data-world-overlay]")) return null;
      // Pointer capture delivers clicks to the canvas, even above a tall roof.
      const surface = this._buildings && document.elementFromPoint(event.clientX, event.clientY)
        ?.closest('.building-volume,.building-field,.building-canopy');
      const id = event.target.closest("[data-world-id]")?.dataset.worldId || surface?.closest('.machine')?.dataset.stationId;
      if (id) return this._scene.nodes.find(node => node.id === id && (!kind || node.kind === kind));
      if (event.target !== this._stage && event.target !== this._labels && !event.target.closest("#world-plane")) return null;
      const rect = this._stage.getBoundingClientRect();
      const x = event.clientX - rect.left, y = event.clientY - rect.top;
      const sprite = this._surface?.hit(x,y,kind);
      if (sprite) return sprite;
      const contains = points => {
        const cross = points.map((a, i) => {
          const b = points[(i + 1) % points.length];
          return (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]);
        });
        return !cross.every(value => Math.abs(value) < .01)
          && (cross.every(value => value >= -.01) || cross.every(value => value <= .01));
      };
      let hit = null;
      for (const node of this._scene.nodes) {
        if (kind && node.kind !== kind) continue;
        const look = this._appearance(node);
        const box = this._screenBounds(node, look);
        if (x < box.x || y < box.y || x > box.x + box.width || y > box.y + box.height) continue;
        const contour = this._contour(this._planeBounds(node, look), node.expanded ? {roundness: 0} : look);
        const depth = node.expanded || node.kind === 'end' ? 0 : (this._buildings ? 18 : 36) * this._stationUnit(node, look);
        const top = contour.map(point => this._view(...point, depth));
        const base = contour.map(point => this._view(...point));
        if ((contains(top) || top.some((a, i) => contains([a, top[(i + 1) % top.length], base[(i + 1) % top.length], base[i]])))
          && (!hit || node.width * node.height < hit.width * hit.height)) hit = node;
      }
      return hit;
    }

    _click(event) {
      if (performance.now() < (this._suppressClickUntil || 0)
        || event.target.closest(".canvas-tools, [data-world-overlay]")) return;
      const node = this._eventNode(event);
      if (!node) {
        this._callbacks.deselect?.();
        return;
      }
      event.stopPropagation();
      if (node.kind === "end") return;
      if (node.kind === "region") {
        if (this._callbacks.selectGroup) this._callbacks.selectGroup(node.group, node.id, event);
        else this.focus(node.id);
      } else this._callbacks.selectNode?.(node.id);
    }

    _key(event) {
      if (event.isComposing || event.defaultPrevented || event.ctrlKey || event.metaKey || event.altKey
        || event.target.isContentEditable || event.target.closest("input, select, textarea, [data-world-overlay]")
        || event.target.closest("button") && !event.target.closest("[data-world-id]")) return;
      if (event.key === " " && event.target.closest("[data-world-id]")) {
        event.stopPropagation();
        return;
      }
      if (event.key === "Escape") {
        this._stage.focus({ preventScroll: true });
        return;
      }
      const distance = 80;
      const directions = { ArrowLeft: [-distance, 0], ArrowRight: [distance, 0], ArrowUp: [0, -distance], ArrowDown: [0, distance] };
      if (directions[event.key]) {
        this._cancelFlight();
        const [x, y] = directions[event.key];
        this._panBy(x, y);
        this._initialized = true;
        this._changedCamera();
      } else if (event.key === "+" || event.key === "=") this.zoom(1.25);
      else if (event.key === "-") this.zoom(.8);
      else if (event.key === "Home") this.focus('app', .5);
      else return;
      event.preventDefault();
      event.stopPropagation();
    }

    _panBy(x, y) {
      const [a, b] = this._unproject(this._size.width / 2 + x, this._size.height / 2 + y);
      this._camera.x += (a - this._size.width / 2) / this._camera.scale;
      this._camera.y += (b - this._size.height / 2) / this._camera.scale;
    }

    _view(x, y, z = 0) {
      const cx = this._size.width / 2, cy = this._size.height / 2;
      const m = this._projection;
      return [cx + m.m11 * (x - cx) + m.m21 * (y - cy) + m.m31 * z,
        cy + m.m12 * (x - cx) + m.m22 * (y - cy) + m.m32 * z];
    }

    _unproject(x, y) {
      const cx = this._size.width / 2, cy = this._size.height / 2;
      const m = this._projection, determinant = m.m11 * m.m22 - m.m12 * m.m21;
      return [cx + (m.m22 * (x - cx) - m.m21 * (y - cy)) / determinant,
        cy + (m.m11 * (y - cy) - m.m12 * (x - cx)) / determinant];
    }

    _viewport() {
      let unit = 0, nearestUnit = this._camera.scale * (this._groups?.at(-1)?.unit || 1), nearest = Infinity;
      for (const node of this._scene?.nodes || []) {
        if (node.expanded || node.kind === 'end') continue;
        const box = this._screenBounds(node);
        const distance = Math.hypot(Math.max(0, -box.x-box.width, box.x-this._size.width),
          Math.max(0, -box.y-box.height, box.y-this._size.height));
        const size = this._stationUnit(node);
        if (!distance) unit = Math.max(unit, size);
        if (distance < nearest) { nearest = distance; nearestUnit = size; }
      }
      unit ||= nearestUnit;
      const m = this._projection;
      // Query the ground beneath visible roofs too, including the foundation's layout overhang.
      const margin = 64 + 40 * unit, height = (this._buildings ? this._buildingHeight : 36) * unit;
      const points = [[-margin, -margin], [this._size.width + margin, -margin],
        [this._size.width + margin, this._size.height + margin], [-margin, this._size.height + margin]]
        .flatMap(([x, y]) => [this._unproject(x, y), this._unproject(x - m.m31 * height, y - m.m32 * height)]);
      const x = Math.min(...points.map(p => p[0])), y = Math.min(...points.map(p => p[1]));
      return {x: this._camera.x + x / this._camera.scale, y: this._camera.y + y / this._camera.scale,
        width: (Math.max(...points.map(p => p[0])) - x) / this._camera.scale,
        height: (Math.max(...points.map(p => p[1])) - y) / this._camera.scale};
    }

    _viewBounds(box, height = 0) {
      const points = [[box.x, box.y], [box.x + box.width, box.y],
        [box.x + box.width, box.y + box.height], [box.x, box.y + box.height]].flatMap(p => [this._view(...p), this._view(...p, height)]);
      const x = Math.min(...points.map(p => p[0])), y = Math.min(...points.map(p => p[1]));
      return {x, y, width: Math.max(...points.map(p => p[0])) - x,
        height: Math.max(...points.map(p => p[1])) - y};
    }

    _appearance(node) {
      const look = this._callbacks.appearance?.(node) || {};
      return this._buildings && !look.shape ? {...look,
        shape: look.symbol === 'branch' ? 'junction' : node.kind === 'region' ? 'hexagon' : 'rectangle'} : look;
    }

    _height(node, look) {
      return node.expanded || node.kind === 'end' ? 0 : (this._buildings ? this._buildingHeight : 36) * this._stationUnit(node, look);
    }

    _screenBounds(node, look = this._appearance(node)) {
      return this._viewBounds(this._planeBounds(node, look), this._height(node, look));
    }

    _project(x, y) {
      return [(x - this._camera.x) * this._camera.scale, (y - this._camera.y) * this._camera.scale];
    }

    _worldKey(point) {
      return point.map((value,axis) => (value/this._camera.scale
        + (axis ? this._camera.y : this._camera.x)).toFixed(6)).join(':');
    }

    _planeBounds(node, look = this._appearance(node)) {
      const [x, y] = this._project(node.x, node.y);
      const width = node.width * this._camera.scale;
      const height = node.height * this._camera.scale;
      if (node.expanded) return { x, y, width, height };
      // Scene extents locate nested content; they do not size a collapsed machine.
      const stationScale = this._stationUnit(node, look);
      const aspect = look.aspect ?? RailixWorld.appearance.aspect;
      const stationHeight = 136 / Math.max(1, aspect) * stationScale;
      const stationWidth = stationHeight * aspect;
      return { x: x + (width - stationWidth) / 2, y: y + (height - stationHeight) / 2,
        width: stationWidth, height: stationHeight };
    }

    _stationUnit(node) {
      // A station owns its footprint. Panning a neighbour into view cannot resize it.
      return this._camera.scale * Math.min(1, node.station_scale);
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
      if (points.length === 2) {
        const horizontal = Math.abs(link.points[1][0]-link.points[0][0]) >= Math.abs(link.points[1][1]-link.points[0][1]);
        const a = points[0], b = points[1];
        const middle = horizontal ? (a[0]+b[0])/2 : (a[1]+b[1])/2;
        points.splice(1, 0, horizontal ? [middle,a[1]] : [a[0],middle], horizontal ? [middle,b[1]] : [b[0],middle]);
      }
      // Keep the layout's branch axis fixed. Only socket contacts follow authored contours.
      if (points.length === 4) {
        const a = points[0], d = points[3];
        const firstHorizontal = link.points.length === 2
          ? points[0][1] === points[1][1] : link.points[0][1] === link.points[1][1];
        const lastHorizontal = link.points.length === 2 ? firstHorizontal : link.points[2][1] === link.points[3][1];
        points[1][firstHorizontal ? 1 : 0] = a[firstHorizontal ? 1 : 0];
        points[2][lastHorizontal ? 1 : 0] = d[lastHorizontal ? 1 : 0];
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
          const key = `${output ? 'from' : 'to'}:${rail.link[output ? 'from' : 'to']}:${points[0]}:${axis}:${direction}`;
          if (!ports.has(key)) ports.set(key, {axis, direction, output, rails: []});
          ports.get(key).rails.push(i);
        }
      });
      for (const port of ports.values()) {
        if (port.rails.length < 2) continue;
        for (const i of port.rails) parents[root(i)] = root(port.rails[0]);
        // A shared port owns one bus, not a separate elbow for every destination.
        const elbows = port.rails.filter(i => rails[i].points.length === 4);
        // Only parallel inlet/outlet legs share a movable bus. Classify the source topology,
        // not projected coordinates whose rounding can change during camera motion.
        if (elbows.some(i => {
          const points = rails[i].link.points;
          const end = port.output ? points.length - 1 : 0;
          const neighbour = port.output ? end - 1 : 1;
          return points[end][1-port.axis] !== points[neighbour][1-port.axis];
        })) continue;
        const middle = elbows.reduce((value, i) => port.direction > 0
          ? Math.min(value, rails[i].points[port.output ? 1 : 2][port.axis])
          : Math.max(value, rails[i].points[port.output ? 1 : 2][port.axis]),
          port.direction > 0 ? Infinity : -Infinity);
        for (const i of port.rails) {
          if (rails[i].points.length === 4) {
            rails[i].points[1][port.axis] = middle;
            rails[i].points[2][port.axis] = middle;
          }
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
      if (look.shape === 'junction') return [[x,y+h*.34],[x+w*.58,y+h*.34],[x+w*.58,y],
        [x+w,y],[x+w,y+h],[x+w*.58,y+h],[x+w*.58,y+h*.66],[x,y+h*.66]];
      if (look.shape === "triangle") return [[x, y], [x + w, y + h / 2], [x, y + h]];
      if (look.shape === "diamond") return [[x + w / 2, y], [x + w, y + h / 2], [x + w / 2, y + h], [x, y + h / 2]];
      if (look.shape === "hexagon") return [[x+w*.25,y],[x+w*.75,y],[x+w,y+h/2],[x+w*.75,y+h],[x+w*.25,y+h],[x,y+h/2]];
      if (["ellipse", "event", "storage"].includes(look.shape)) return Array.from({length: 12}, (_, i) => {
        const angle = i * Math.PI / 6;
        return [x + w / 2 + Math.cos(angle) * w / 2, y + h / 2 + Math.sin(angle) * h / 2];
      });
      const r = Math.min(w, h) * (look.roundness ?? RailixWorld.appearance.roundness) / 100;
      if (!r) return [[x, y], [x + w, y], [x + w, y + h], [x, y + h]];
      const points = [];
      for (const [cx, cy, angle] of [[x + w - r, y + r, -Math.PI / 2], [x + w - r, y + h - r, 0],
        [x + r, y + h - r, Math.PI / 2], [x + r, y + r, Math.PI]]) {
        for (let i = 0; i <= 2; i++) points.push([cx + r * Math.cos(angle + i * Math.PI / 4), cy + r * Math.sin(angle + i * Math.PI / 4)]);
      }
      return points;
    }

    _part(key, name, parent = this._factory) {
      let element = this._parts.get(key);
      if (!element) {
        element = document.createElement("div");
        element.className = name;
        parent.append(element);
        this._parts.set(key, element);
      }
      if (element.parentElement !== parent) parent.append(element);
      this._used.add(key);
      return element;
    }

    _machine({node, look, box, unit = this._stationUnit(node), element, portrait = false}) {
      if (this._surface && !portrait) {
        const ancestor = !this._revealFrom?.has(node.id) && node.regions?.map(id => this._revealFrom?.get(id)).find(part => part && !part.expanded);
        let reveal;
        if (ancestor && !node.expanded && !document.hidden && !this._callbacks.reducedMotion?.()) {
          const origin = this._planeBounds(ancestor);
          const from=this._view(origin.x+origin.width/2,origin.y+origin.height/2), to=this._view(box.x+box.width/2,box.y+box.height/2);
          reveal={dx:from[0]-to[0],dy:from[1]-to[1],start:performance.now()};
        }
        this._surface.station({node,look,box},this._contour(box,node.expanded?{roundness:0}:look),unit,reveal);
        return;
      }
      element ||= this._part(`node:${node.id}`, "machine", node.preview ? this._stationPlane : node.expanded ? this._factory : this._stations);
      const detailed = node.kind !== "end" && Math.min(box.width, box.height) >= 16;
      element.dataset.stationId = node.id;
      element.dataset.kind = node.kind;
      element.dataset.selected = String(Boolean(look.selected));
      element.dataset.changed = String(Boolean(look.changed));
      element.dataset.error = String(Boolean(look.error));
      element.dataset.warning = String(Boolean(look.warning));
      element.dataset.never = String(Boolean(look.never));
      element.dataset.busy = String(Number(look.rate) > 0);
      element.dataset.shape = look.shape || "rectangle";
      element.dataset.coverage = look.coverage || "";
      element.dataset.activity = look.activity || "";
      element.dataset.expanded = String(Boolean(node.expanded));
      element.dataset.preview = String(Boolean(node.preview));
      const building = this._buildings ? node.kind === 'step' && look.symbol === 'branch' ? 'branch' : node.kind : '';
      element.dataset.building = building;
      // Keep material dimensions stable; the compositor scales the mesh with the camera.
      const meshUnit = node.expanded ? 1 : unit;
      const meshBox = {x:0, y:0, width:Number((box.width/meshUnit).toFixed(6)), height:Number((box.height/meshUnit).toFixed(6))};
      const shape = node.expanded ? {roundness: 0} : look;
      const contour = this._contour(meshBox, shape);
      const polygon = `polygon(${contour.map(([x,y]) => `${x/meshBox.width*100}% ${y/meshBox.height*100}%`).join(",")})`;
      element.style.translate = `${box.x}px ${box.y}px`;
      element.style.scale = `${meshUnit} ${meshUnit} ${meshUnit}`;
      element.style.setProperty('--signal', color(look.color));
      const depth = node.expanded || node.kind === 'end' ? 0 : this._buildings ? 18 : 36;
      const signature = `${building}:${polygon}:${meshBox.width}:${meshBox.height}:${node.expanded}:${detailed}:${this._projection.m13}:${this._projection.m23}`;
      if (element.dataset.geometry !== signature) {
        element.style.width = `${meshBox.width}px`;
        element.style.height = `${meshBox.height}px`;
        element.style.setProperty('--unit', 1);
        element.style.setProperty('--depth', `${depth}px`);
        element.style.setProperty('--core-size', `${this._coreSize(meshBox,look,1)}px`);
        // Foundation contours do not resize a building; authored aspect still fits the same cell.
        element.style.setProperty('--body-size', `${Math.min(100, meshBox.width * .7, meshBox.height * .7)}px`);
        element.style.setProperty('--outline', polygon);
        element.replaceChildren();
        const top = document.createElement("div");
        top.className = "machine-top";
        element.append(top);
        if (!node.expanded && detailed) {
          const core = document.createElement("div");
          core.className = "machine-core";
          const light = document.createElement("i");
          light.className = "machine-light";
          core.append(light);
          if (building) {
            // A bounded set of CSS solids, never one decoration per contained Step.
            const slots = building === 'app' ? ['tower','west','east','service']
              : building === 'trigger' ? ['north','south'] : building === 'region' ? ['west','center','east']
              : building === 'branch' ? ['north','south'] : ['processor'];
            for (const slot of slots) {
              const volume = document.createElement('div');
              volume.className = 'building-volume';
              volume.dataset.slot = slot;
              core.append(volume);
            }
            if (building === 'trigger' || building === 'region' || building === 'branch') {
              const surface = document.createElement('div');
              surface.className = building === 'region' ? 'building-canopy' : 'building-field';
              core.append(surface);
            }
          }
          element.append(core);
        }
        if (!node.expanded && detailed) for (let i = 0; i < contour.length; i++) {
          const a = contour[i], b = contour[(i + 1) % contour.length];
          const dx = b[0]-a[0], dy = b[1]-a[1], angle = Math.atan2(dy,dx);
          // The fixed CSS camera cannot see these outward-facing backs.
          if (this._projection.m13 * dy - this._projection.m23 * dx <= 0) continue;
          const side = document.createElement("div");
          side.className = "machine-side";
          side.style.cssText = `width:${Math.hypot(dx,dy)+.15}px;height:${depth}px;transform:translate3d(${a[0]}px,${a[1]}px,0) rotateZ(${angle}rad) rotateX(90deg);--shade:${22+14*(1+Math.cos(angle+.7))}%`;
          element.append(side);
        }
        element.dataset.geometry = signature;
      }
      if (portrait) return;
      const previous = this._revealFrom?.get(node.id);
      const ancestor = !previous && node.regions?.map(id => this._revealFrom?.get(id)).find(part => part && !part.expanded);
      let reveal;
      if (ancestor && !node.expanded && !document.hidden && !this._callbacks.reducedMotion?.()) {
        const origin = this._planeBounds(ancestor);
        reveal = element.animate([{transform:`translate(${(origin.x+origin.width/2-box.x-box.width/2)/meshUnit}px,${(origin.y+origin.height/2-box.y-box.height/2)/meshUnit}px)`},
          {transform:'none'}], {duration:180,easing:'ease-out'});
      }
      if (reveal) {
        this._reveals.add(reveal);
        const release = () => this._reveals.delete(reveal);
        reveal.finished.then(release, release);
      }
    }

    _transport(rails) {
      const time = performance.now() / 1000;
      const view = this._batch.view, lower = this._project(view.x, view.y);
      const upper = this._project(view.x + view.width, view.y + view.height);
      for (const network of this._networks(rails)) {
        const axes = new Map(), junctions = new Map();
        const id = network.map(rail => rail.link.id).sort()[0];
        const unit = Math.min(...network.map(rail=>rail.look.unit));
        for (const rail of network) rail.look.unit = unit;
        for (const {points, look} of network) {
          for (let i = 1; i < points.length; i++) {
            const a = points[i - 1], b = points[i];
            const dx = b[0] - a[0], dy = b[1] - a[1];
            if (Math.hypot(dx, dy) < .01) continue;
            const horizontal = Math.abs(dy) < .01, axis = horizontal ? 0 : 1;
            const key = `${axis}:${this._worldKey(a).split(':')[1-axis]}`;
            if (!axes.has(key)) axes.set(key, []);
            axes.get(key).push({a, b, look, axis, from: Math.min(a[axis], b[axis]), to: Math.max(a[axis], b[axis])});
          }
        }
        // One physical run per collinear interval; shared trunks never stack duplicate belt surfaces.
        for (const [axisKey, intervals] of axes) {
          const ends = [...new Set(intervals.flatMap(item => [item.from, item.to]))].sort((a,b) => a-b);
          for (let i = 1; i < ends.length; i++) {
            const start = ends[i-1], end = ends[i];
            const active = intervals.filter(item => item.from <= start && item.to >= end);
            if (!active.length || end - start < .01) continue;
            const best = active.reduce((a,b) => b.look.selected && !a.look.selected
              || Boolean(b.look.selected) === Boolean(a.look.selected) && (b.look.rate || 0) > (a.look.rate || 0) ? b : a);
            const axis = best.axis, fixed = best.a[1-axis], forward = best.b[axis] >= best.a[axis];
            const a = axis ? [fixed, forward ? start : end] : [forward ? start : end, fixed];
            const b = axis ? [fixed, forward ? end : start] : [forward ? end : start, fixed];
            // Split intervals include through-ports too, not just the endpoints of each branch.
            for (const [point, other] of [[a,b],[b,a]]) {
              const key = this._worldKey(point);
              if (!junctions.has(key)) junctions.set(key, {point,mask:0,look:best.look});
              const junction = junctions.get(key);
              junction.mask |= axis ? other[1]>point[1]?4:1 : other[0]>point[0]?2:8;
              if (best.look.selected && !junction.look.selected
                || Boolean(best.look.selected)===Boolean(junction.look.selected) && (best.look.rate||0)>(junction.look.rate||0))
                junction.look = best.look;
            }
            // Overrun disappears beneath junctions and machine casings, never in an exposed span.
            const overrun = (best.look.power ? 4 : 15) * unit, direction = forward ? 1 : -1;
            const margin = 40 * unit;
            if (a[1-axis] < lower[1-axis]-margin || a[1-axis] > upper[1-axis]+margin) continue;
            const first = Math.max(start-overrun, lower[axis]-margin), last = Math.min(end+overrun, upper[axis]+margin);
            if (last <= first) continue;
            const origin = [...a];
            origin[axis] = forward ? first : last;
            const phase = Number((-((origin[axis]+(axis?this._camera.y:this._camera.x)*this._camera.scale)*direction/unit)%32).toFixed(6));
            if (this._surface) {
              this._moving ||= best.look.rate > 0;
              this._replaying ||= !best.look.power && best.look.replay;
              this._surface.run({origin,length:last-first,unit,angle:Math.atan2(b[1]-a[1],b[0]-a[0]),look:best.look,phase});
              continue;
            }
            const element = this._part(`belt:${id}:${axisKey}:${this._worldKey(a)}:${this._worldKey(b)}`, best.look.power ? "power-run" : "belt-run");
            this._beltStyle(element, best.look, time);
            element.style.left = `${origin[0]}px`;
            element.style.top = `${origin[1]}px`;
            element.style.width = `${Number(((last-first)/unit).toFixed(6))}px`;
            element.style.setProperty('--phase', `${phase}px`);
            element.style.transform = `translateZ(6px) rotateZ(${Math.atan2(b[1]-a[1], b[0]-a[0])}rad)`;
            element.dataset.direction = axis ? forward ? 'south' : 'north' : forward ? 'east' : 'west';
            if (!element.firstChild) {
              const track = document.createElement("div");
              track.className = "belt-track";
              element.append(track);
            }
            if (!best.look.power && !this._buildings && element.children.length === 1) {
              const cargo = document.createElement('div');
              cargo.className = 'belt-cargo';
              element.append(cargo);
            }
            if (this._buildings && element.children.length > 1) element.lastElementChild.remove();
          }
        }
        for (const [key, {point, mask, look}] of junctions) {
          if (mask === 5 || mask === 10 || (mask & (mask-1)) === 0) continue;
          if (this._surface) { this._surface.junction(point,mask,look,unit);continue; }
          const element = this._part(`junction:${id}:${key}`, "belt-junction");
          this._beltStyle(element, look, time);
          element.dataset.ports = String(mask);
          element.style.left = `${point[0]}px`;
          element.style.top = `${point[1]}px`;
          element.style.transform = 'translate(-50%,-50%) translateZ(7px)';
          element.style.borderTopColor = mask & 1 ? "transparent" : "";
          element.style.borderRightColor = mask & 2 ? "transparent" : "";
          element.style.borderBottomColor = mask & 4 ? "transparent" : "";
          element.style.borderLeftColor = mask & 8 ? "transparent" : "";
        }
        if (this._buildings) this._cargo(network, lower, upper, time);
      }
    }

    _cargo(network, lower, upper, time) {
      for (const {link, points, look} of network) {
        if (look.power || !(look.rate > 0 || look.replay) || look.unit < .18) continue;
        const unit = look.unit, margin = 40 * unit;
        const path = points.filter((p,i) => !i || Math.hypot(p[0]-points[i-1][0],p[1]-points[i-1][1]) > .01)
          .map(p=>[...p]);
        if (path.length < 2) continue;
        // Loops close underneath stations, not at each visible elbow. Clip only outside the overscan.
        for (const [index,other] of [[0,1],[path.length-1,path.length-2]]) {
          const a=path[index], b=path[other], length=Math.hypot(a[0]-b[0],a[1]-b[1]);
          for (let axis=0;axis<2;axis++) a[axis] += (a[axis]-b[axis])/length*32*unit;
        }
        const paths = [];
        if (this._surface) {
          const segments=[];let offset=0;
          for(let i=1;i<path.length;i++) {
            const a=path[i-1],b=path[i],axis=Math.abs(b[0]-a[0])>.01?0:1,length=Math.hypot(b[0]-a[0],b[1]-a[1])/unit;
            const min=Math.max(Math.min(a[axis],b[axis]),lower[axis]-margin),max=Math.min(Math.max(a[axis],b[axis]),upper[axis]+margin);
            if(max>min&&a[1-axis]>=lower[1-axis]-margin&&a[1-axis]<=upper[1-axis]+margin) {
              const from=offset+Math.abs((b[axis]>a[axis]?min:max)-a[axis])/unit;
              segments.push({a,b,offset,length,from,to:from+(max-min)/unit});
            }
            offset+=length;
          }
          this._surface.route({id:link.id,look,unit,segments});
          continue;
        }
        let current;
        for (let i=1;i<path.length;i++) {
          const a=path[i-1], b=path[i], axis=Math.abs(b[0]-a[0])>.01 ? 0 : 1;
          const direction=Math.sign(b[axis]-a[axis]), fixed=a[1-axis];
          const start=Math.max(Math.min(a[axis],b[axis]),lower[axis]-margin);
          const end=Math.min(Math.max(a[axis],b[axis]),upper[axis]+margin);
          if (end<=start || fixed<lower[1-axis]-margin || fixed>upper[1-axis]+margin) { current=null; continue; }
          const first=[...a], last=[...b];
          first[axis]=direction>0?start:end; last[axis]=direction>0?end:start;
          if (!current || Math.hypot(current.at(-1)[0]-first[0],current.at(-1)[1]-first[1])>.01) {
            current=[first]; paths.push(current);
          }
          current.push(last);
        }
        paths.forEach((points,index) => {
          const origin=points[0], local=points.map(p=>p.map((v,axis)=>Number(((v-origin[axis])/unit).toFixed(4))));
          const length=local.slice(1).reduce((sum,p,i)=>sum+Math.hypot(p[0]-local[i][0],p[1]-local[i][1]),0);
          const route=this._part(`cargo:${link.id}:${index}`, 'cargo-route', this._cargoLayer);
          this._beltStyle(route,look,time);
          route.style.scale=String(unit);
          route.dataset.linkId=link.id;
          const position=this._view(...origin,6*unit);
          route.style.left=`${position[0]}px`; route.style.top=`${position[1]}px`;
          const duration=Math.ceil(length/32*parseFloat(route.style.getPropertyValue('--period'))*24)/24;
          const count=Math.max(1,Math.floor(length/64));
          route.style.setProperty('--cargo-duration', `${duration}s`);
          if (!route.firstChild) {
            route.append(document.createElement('style'));
            route.style.setProperty('--cargo-animation', `cargo-${this._motionSequence++}`);
          }
          let distance=0;
          const frames=local.map((p,i)=>{
            if (i) distance+=Math.hypot(p[0]-local[i-1][0],p[1]-local[i-1][1]);
            const m=this._projection;
            return `${distance/length*100}%{transform:translate(${m.m11*p[0]+m.m21*p[1]}px,${m.m12*p[0]+m.m22*p[1]}px) translate(var(--cargo-origin));}`;
          }).join('');
          const rule=`@keyframes ${route.style.getPropertyValue('--cargo-animation')}{${frames}}`;
          if (route.firstChild.textContent!==rule) route.firstChild.textContent=rule;
          while (route.children.length>count+1) route.lastElementChild.remove();
          while (route.children.length<count+1) {
            const item=document.createElement('i'); item.className='cargo-item'; route.append(item);
          }
          for (let i=0;i<count;i++) {
            const item=route.children[i+1];
            if (item.dataset.period!==String(duration)) {
              item.style.setProperty('--cargo-delay', `${-Math.floor((time%duration+i*duration/count)*24)/24}s`);
              item.dataset.period=String(duration);
            }
          }
        });
      }
    }

    _beltStyle(element, look, time) {
      const rate = !look.power && Number.isFinite(look.rate) && look.rate > 0 ? look.rate : 0;
      const period = clamp(2.4 / (1 + Math.log10(1 + rate)), .3, 2.4);
      if (element.style.getPropertyValue('--period') !== `${period}s`
        || element.dataset.active !== String(rate>0) || element.dataset.replay !== String(!look.power && Boolean(look.replay)))
        element.style.setProperty('--delay', `${-(time%period)}s`);
      this._moving ||= rate > 0;
      this._replaying ||= !look.power && Boolean(look.replay);
      element.dataset.power = String(Boolean(look.power));
      element.dataset.active = String(rate > 0);
      element.dataset.selected = String(Boolean(look.selected));
      element.dataset.replay = String(!look.power && Boolean(look.replay));
      element.style.setProperty("--unit", 1);
      element.style.scale = `${look.unit} ${look.unit} ${look.unit}`;
      element.style.setProperty("--signal", color(look.color));
      element.style.setProperty("--period", `${period}s`);
      element.style.setProperty("--ticks", Math.max(1, Math.round(period * 24)));
      element.title = look.power ? "Application connection; not a data route" : `${rate ? `${zoomNumber.format(rate)} executions/s` : "No current traffic"}${look.replay ? "; selected example replay" : ""}`;
    }

    _coreSize(box, look, unit, maximum = 60) {
      const factor = look.shape === "triangle" ? 1/3 : look.shape === "diamond" ? .5 : .7;
      return Math.min(maximum * unit, box.width * factor, box.height * factor);
    }

    _symbolBounds(box, node, look) {
      const plane = node.preview ? null : this._planeBounds(node, look);
      const unit = this._stationUnit(node, look);
      const size = Math.min(36 * unit, this._coreSize(plane || box, look, unit) * .6);
      const [x, y] = plane ? this._view(plane.x + plane.width / 2, plane.y + plane.height / 2, 59 * unit)
        : [box.x + box.width / 2, box.y + box.height / 2];
      return {x: x-size/2, y: y-size/2, width: size, height: size};
    }

    _draw() {
      if (this._disposed) return;
      this._used = new Set();
      const view = this._viewport(), batch = this._batch;
      const scale = batch ? this._camera.scale/batch.camera.scale : 1;
      // Rebase across an octave so clipped surfaces and detail stay bounded at extreme zoom.
      let reconcile = Boolean(this._surface) || this._geometryDirty || !batch || view.x < batch.view.x || view.y < batch.view.y
        || view.x+view.width > batch.view.x+batch.view.width || view.y+view.height > batch.view.y+batch.view.height
        || scale < .5 || scale > 2;
      this._geometryDirty = false;
      const unit = this._camera.scale;
      const grid = 64 * 2 ** Math.floor(Math.log2(1 / unit));
      const floor = this._part("floor", "world-floor", this._ground);
      floor.style.setProperty("--grid", `${grid * unit}px`);
      const blend = Math.log2(grid * unit / 32);
      floor.style.setProperty("--grid-alpha", blend * .10);
      floor.style.setProperty("--grid-next-alpha", (1-blend) * .10);
      floor.style.transform = `translate3d(${-(this._camera.x % (grid*2))*unit}px,${-(this._camera.y % (grid*2))*unit}px,-80px)`;
      if (this._scene) {
        const outgoing = new Map();
        for (const link of this._scene.links) outgoing.set(link.from, (outgoing.get(link.from) || 0) + 1);
        const stations = this._scene.nodes.map(node => {
          const look = this._appearance(node);
          return {node, look: node.kind === "step" && !look.symbol && outgoing.get(node.id) > 1 ? {...look, symbol: "branch"} : look,
            box: this._planeBounds(node, look)};
        });
        const byId = new Map(stations.map(item => [item.node.id, item]));
        // A bounded overscan keeps cached casings available while the next scene is loading.
        const margin = Math.max(this._size.width, this._size.height) / 4;
        const visible = stations.map(station => ({...station,
          box:this._viewBounds(station.box, this._height(station.node,station.look))}))
          .filter(({box}) => box.x+box.width >= -32 && box.y+box.height >= -32
            && box.x <= this._size.width+32 && box.y <= this._size.height+32);
        reconcile ||= visible.some(({node}) => {
          const machine = this._parts.get(`node:${node.id}`), box = byId.get(node.id).box;
          return !machine || !node.expanded && node.kind !== 'end'
            && (machine.children.length > 1) !== (Math.min(box.width,box.height) >= 16);
        });
        const dx = batch ? (batch.camera.x-this._camera.x)*unit : 0;
        const dy = batch ? (batch.camera.y-this._camera.y)*unit : 0;
        const rails = reconcile ? this._scene.links.map(link => {
          const look = this._callbacks.linkAppearance?.(link) || {};
          const unit = Math.min(this._camera.scale * (link.station_scale ?? 1), ...[byId.get(link.from),byId.get(link.to)]
            .filter(station=>station && !station.node.expanded).map(station=>this._stationUnit(station.node,station.look)));
          return {link, points: this._linkPoints(link, byId), look: {...look, unit,
            power: byId.get(link.from)?.node.kind === "app", replay: look.selected,
            selected: this._placementLink(link) || look.selected}};
        }) : batch.rails.map(rail => ({...rail, look:{...rail.look,unit:rail.look.unit*scale},
          points:rail.points.map(([x,y]) => [x*scale+dx,y*scale+dy])}));
        if (reconcile) {
          this._surface?.begin(this._size,this._projection,this._view(0,0),getComputedStyle(document.documentElement));
          this._scenery();
          this._batch = {camera:{...this._camera}, rails,
            view:{x:view.x-view.width/4,y:view.y-view.height/4,width:view.width*1.5,height:view.height*1.5}};
          this._factory.style.transform = 'translate3d(0,0,0)';
          this._stations.style.transform = 'translate3d(0,0,0)';
          this._cargoLayer.style.transform = 'none';
          this._moving = false;
          this._replaying = false;
          this._transport(rails);
          const ports = new Set();
          if (!this._surface) for (const {link, points, look} of rails) for (const output of [true, false]) {
            const id = output ? link.from : link.to;
            if (!byId.has(id) || byId.get(id).node.expanded) continue;
            const a = output ? points[0] : points.at(-1);
            const b = output ? points.find(p => p[0] !== a[0] || p[1] !== a[1])
              : points.findLast(p => p[0] !== a[0] || p[1] !== a[1]);
            const key = `port:${id}:${this._worldKey(a)}`;
            if (!b || ports.has(key)) continue;
            ports.add(key);
            const port = this._part(key, "belt-port");
            port.style.cssText = `left:${a[0]}px;top:${a[1]}px;--unit:${look.unit};--signal:${color(look.color)};transform:translateZ(${9*look.unit}px) rotateZ(${Math.atan2(b[1]-a[1],b[0]-a[0])}rad)`;
          }
          for (const station of stations) {
            const box = this._viewBounds(station.box, this._height(station.node,station.look));
            if (box.x+box.width >= -margin && box.y+box.height >= -margin
              && box.x <= this._size.width+margin && box.y <= this._size.height+margin) this._machine(station);
          }
        } else {
          // Move cached layers without rebuilding faces or restarting parcel animations.
          this._factory.style.transform = `translate3d(${dx}px,${dy}px,0) scale3d(${scale},${scale},${scale})`;
          this._stations.style.transform = this._factory.style.transform;
          const origin=this._view(0,0), m=this._projection;
          this._cargoLayer.style.transform = `translate(${m.m11*dx+m.m21*dy+(1-scale)*origin[0]}px,${m.m12*dx+m.m22*dy+(1-scale)*origin[1]}px) scale(${scale})`;
        }
        const placement = this._placementStation(rails);
        if (placement) {
          this._machine(placement);
          visible.push({...placement,box:this._viewBounds(placement.box,this._height(placement.node,placement.look))});
        }
        this._renderLabels(visible, rails, outgoing);
        if (!reconcile) this._scenery();
        let audible = 0;
        for (const {node, look, box} of visible) if (!node.expanded && box.width > 100 && (look.rate > 0 || look.coverage === 'selected')) {
          const distance = Math.hypot(box.x+box.width/2-this._size.width/2, box.y+box.height/2-this._size.height/2);
          audible = Math.max(audible, clamp(1-distance/400, 0, 1) * clamp((box.width-100)/150, 0, 1));
        }
        this._callbacks.onActivity?.(audible);
        if (this._anchor) {
          const box = visible.find(({node}) => node.id === this._anchor.id)?.box;
          const element = this._anchor.element;
          element.hidden = !box;
          if (box) {
            const width = element.offsetWidth, height = element.offsetHeight;
            const x = clamp(box.x+(box.width-width)/2,8,Math.max(8,this._size.width-width-8));
            const controls = this._controlBounds();
            const obstacles = [...visible.filter(item => !item.node.expanded).map(item => item.box), ...controls];
            const candidates = [[x,box.y-height-12], [x,box.y+box.height+12],
              [box.x-width-12,box.y+(box.height-height)/2], [box.x+box.width+12,box.y+(box.height-height)/2]]
              .map(([x,y]) => [clamp(x,8,Math.max(8,this._size.width-width-8)),clamp(y,8,Math.max(8,this._size.height-height-8))]);
            const clear = (position, boxes) => !boxes.some(other => position[0]<other.x+other.width && position[0]+width>other.x
              && position[1]<other.y+other.height && position[1]+height>other.y);
            const position = candidates.find(point => clear(point, obstacles)) || candidates.find(point => clear(point, controls));
            element.hidden = !position;
            if (position) {
              element.style.left = `${position[0]}px`;
              element.style.top = `${position[1]}px`;
            }
          }
        }
      }
      for (const [key, element] of this._parts) if (!this._used.has(key)
        && (reconcile || element.parentElement !== this._factory && element.parentElement !== this._stations
          && element.parentElement !== this._cargoLayer)) {
        element.remove();
        this._parts.delete(key);
      }
      this._syncMotion();
      this._minimap();
      this._revealFrom = null;
    }

    _syncMotion() {
      clearTimeout(this._motionTimer);
      const enabled = !this._disposed && !document.hidden && !this._callbacks.reducedMotion?.();
      const moving = enabled && this._moving && this._callbacks.motionActive?.() !== false;
      if (this._stage.dataset.trafficAnimated !== String(moving)) this._stage.dataset.trafficAnimated = String(moving);
      const replaying = String(enabled && this._replaying);
      const powered = String(enabled && this._callbacks.powered?.() !== false);
      if (this._stage.dataset.replaying !== replaying) this._stage.dataset.replaying = replaying;
      if (this._stage.dataset.powered !== powered) this._stage.dataset.powered = powered;
      if (!enabled || !moving && !this._replaying) this._callbacks.onActivity?.(0);
      this._surface?.animate({enabled,moving,powered:enabled && this._callbacks.powered?.() !== false});
      // Expiry must also stop CSS motion when no new observation or camera update arrives.
      if (moving && this._callbacks.motionActive) this._motionTimer = setTimeout(() => this._syncMotion(), 500);
    }

    _scenery() {
      if (this._overview?.revision !== this._scene.revision) return;
      const seed = String(this._callbacks.seed?.() ?? "railix");
      let hash = 2166136261;
      for (let i = 0; i < seed.length; i++) hash = Math.imul(hash ^ seed.charCodeAt(i), 16777619);
      const size = 640;
      const viewport = this._viewport();
      const districtSize=size*4, districtPixels=districtSize*this._camera.scale;
      const minimumPixels=Math.sqrt(viewport.width*viewport.height*this._camera.scale**2/24);
      if (districtPixels > minimumPixels && districtPixels < 2048) {
        const opacity=clamp(Math.min((districtPixels-minimumPixels)/minimumPixels,(2048-districtPixels)/512),0,1);
        for (let y=Math.floor(viewport.y/districtSize);y<Math.ceil((viewport.y+viewport.height)/districtSize);y++) {
          for (let x=Math.floor(viewport.x/districtSize);x<Math.ceil((viewport.x+viewport.width)/districtSize);x++) {
            const value=(Math.imul(hash^x,2246822507)^Math.imul(y,3266489909))>>>0;
            const element=this._part(`terrain:${x}:${y}`,"world-terrain",this._ground), [px,py]=this._project(x*districtSize,y*districtSize);
            element.dataset.variant=String(value%3);
            element.style.cssText=`left:${px}px;top:${py}px;width:${districtPixels}px;height:${districtPixels}px;opacity:${opacity};--bend:${25+value%40}%`;
          }
        }
      }
      const overlaps = (a,b) => a.x < b.x+b.width && a.x+a.width > b.x
        && a.y < b.y+b.height && a.y+a.height > b.y;
      const vicinity = {x:viewport.x-size,y:viewport.y-size,width:viewport.width+size*2,height:viewport.height+size*2};
      const clearance = this._terrainClearance.filter(box => overlaps(box,vicinity));
      // Thin a fixed world lattice at distance; zoom never reseeds or moves a structure.
      const stride = 2 ** Math.max(0, Math.ceil(Math.log2(Math.sqrt(viewport.width * viewport.height / (size * size * 64)))));
      for (let y = (Math.floor(viewport.y / size / stride)-1) * stride; y <= Math.ceil((viewport.y+viewport.height) / size); y += stride) {
        for (let x = (Math.floor(viewport.x / size / stride)-1) * stride; x <= Math.ceil((viewport.x+viewport.width) / size); x += stride) {
          let value = Math.imul(hash ^ x, 2246822507) ^ Math.imul(y, 3266489909);
          value = (value ^ value >>> 16) >>> 0;
          if (value % 3 === 0) continue;
          const district = (Math.imul(hash ^ Math.floor(x/4),2246822507) ^ Math.imul(Math.floor(y/4),3266489909)) >>> 0;
          const variant = ["ridge", "grove", "basin"][district % 3];
          const unit=this._camera.scale, width=size*unit*.48, height=size*unit*.36;
          const footprint={x:x*size-128,y:y*size-128,width:size*.48+256,height:size*.36+256};
          if (clearance.some(box=>overlaps(box,footprint))) continue;
          const [px, py] = this._project(x*size, y*size);
          const box={x:px,y:py,width,height};
          const low=this._viewBounds(box,-80), high=this._viewBounds(box,-80+96*unit);
          if (Math.max(low.x+low.width,high.x+high.width)<0 || Math.min(low.x,high.x)>this._size.width
            || Math.max(low.y+low.height,high.y+high.height)<0 || Math.min(low.y,high.y)>this._size.height) continue;
          if (this._surface?.scenery(variant,box,unit)) continue;
          const element = this._part(`scenery:${x}:${y}`, "world-scenery", this._ground);
          element.dataset.worldX = String(x*size);
          element.dataset.worldY = String(y*size);
          element.dataset.variant = variant;
          element.style.cssText = `left:${px}px;top:${py}px;width:${width}px;height:${height}px;--unit:${unit}`;
          const count = variant === "basin" ? 0 : variant === "ridge" ? 3 : 5;
          if (element.children.length !== count) element.replaceChildren(...Array.from({length:count},()=>document.createElement("i")));
          [...element.children].forEach((peak,i)=>{
            const w=(variant === "ridge" ? this._buildings ? 72 : 100 : 36)*unit, d=w*.8, h=(variant === "ridge" ? 48+i*12 : 56+i*8)*unit;
            const spacing = this._buildings && variant === 'ridge' ? .3 : .17;
            const row = this._buildings ? .22 : .12+(value>>>i*2)%3*.16;
            peak.style.cssText=`left:${(i*spacing+.04)*width}px;top:${row*height}px;width:${w}px;height:${d}px;--height:${h}px;--slope-y:${Math.hypot(h,d/2)}px;--slope-x:${Math.hypot(h,w/2)}px;--angle-x:${-Math.atan2(h,d/2)}rad;--angle-y:${-Math.atan2(h,w/2)}rad`;
          });
        }
      }
    }

    _minimap() {
      if (!this._map || !this._scene || this._disposed || document.hidden) return;
      const revision = this._scene.revision;
      if (this._mapRevision !== revision) {
        this._mapRequest?.abort();
        clearTimeout(this._mapRetry);
        this._map.hidden = true;
        this._mapRevision = revision;
        const controller = this._mapRequest = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 5_000);
        const query = new URLSearchParams({...this._scene.bounds, scale: MIN_SCALE});
        fetch(`/api/scene?${query}`, {signal: controller.signal, cache: "no-store"}).then(response => {
          if (!response.ok) throw new Error("Minimap unavailable");
          return response.json();
        }).then(scene => {
          if (this._disposed || this._mapRequest !== controller || scene.revision !== this._scene.revision) return;
          this._overview = scene;
          // Reserve the coarse factory layout once per revision. Camera labels and expanded
          // children cannot evict scenery; every nested region stays inside its reserved plot.
          this._terrainClearance = scene.nodes.map(node=>({x:node.x-160,y:node.y-160,width:node.width+320,height:node.height+320}));
          for (const link of scene.links) for (let i=1;i<link.points.length;i++) {
            const a=link.points[i-1],b=link.points[i];
            this._terrainClearance.push({x:Math.min(a[0],b[0])-160,y:Math.min(a[1],b[1])-160,
              width:Math.abs(a[0]-b[0])+320,height:Math.abs(a[1]-b[1])+320});
          }
          this._mapSelection = null;
          this._mapElements = new Map();
          this._map.querySelector('.map-cells').replaceChildren();
          this._minimap();
          this.repaint();
        }).catch(() => {
          if (this._mapRequest !== controller || this._disposed) return;
          this._map.hidden = true;
          this._mapRetry = setTimeout(() => { this._mapRevision = null; this._minimap(); }, 5_000);
        }).finally(() => clearTimeout(timeout));
      }
      if (this._overview?.revision !== revision) return;
      this._map.hidden = false;
      const view = this._viewport(), width = Math.max(view.width,view.height*this._mapAspect)*this._mapRange;
      const height = width/this._mapAspect;
      const bounds = this._mapBounds = {x:view.x+view.width/2-width/2,y:view.y+view.height/2-height/2,width,height};
      this._mapGrid = new Map();
      this._mapNodes = new Map([...this._overview.nodes,...this._scene.nodes].map(node=>[node.id,node]));
      for (const link of [...this._overview.links,...this._scene.links]) this._mapLine(link.points,(cell,axis,ports)=> {
        const mark=this._mapGrid.get(cell) || {kind:this._mapNodes.get(link.from)?.kind === 'app' ? 'power' : 'belt',axis:0};
        mark.axis |= axis;
        mark.ports |= ports;
        this._mapGrid.set(cell,mark);
      });
      for (const node of this._mapNodes.values()) {
        if (node.expanded) continue;
        const cell=this._mapCell(node.x+node.width/2,node.y+node.height/2);
        if (cell >= 0) this._mapGrid.set(cell,{...this._mapGrid.get(cell),kind:node.kind});
      }
      const x = clamp((view.x-bounds.x)/bounds.width*100, 0, 100), y = clamp((view.y-bounds.y)/bounds.height*100, 0, 100);
      const right = clamp((view.x+view.width-bounds.x)/bounds.width*100, 0, 100), bottom = clamp((view.y+view.height-bounds.y)/bounds.height*100, 0, 100);
      this._map.querySelector('.map-camera').style.cssText = `left:${x}%;top:${y}%;width:${right-x}%;height:${bottom-y}%`;
      this._paintMap();
    }

    _mapCell(x,y) {
      const bounds=this._mapBounds;
      if (x<bounds.x || y<bounds.y || x>bounds.x+bounds.width || y>bounds.y+bounds.height) return -1;
      return clamp(Math.floor((y-bounds.y)/bounds.height*20),0,19)*32
        + clamp(Math.floor((x-bounds.x)/bounds.width*32),0,31);
    }

    _mapLine(points,visit) {
      for (let i=1;i<points.length;i++) {
        const bounds=this._mapBounds, from=points[i-1], to=points[i];
        if (Math.max(from[0],to[0])<bounds.x || Math.min(from[0],to[0])>bounds.x+bounds.width
          || Math.max(from[1],to[1])<bounds.y || Math.min(from[1],to[1])>bounds.y+bounds.height) continue;
        const cell=point=>this._mapCell(clamp(point[0],bounds.x,bounds.x+bounds.width),clamp(point[1],bounds.y,bounds.y+bounds.height));
        const a=cell(from), b=cell(to);
        const dx=b%32-a%32, dy=Math.floor(b/32)-Math.floor(a/32), length=Math.max(Math.abs(dx),Math.abs(dy),1);
        const forward=dx>0 ? 2 : dx<0 ? 8 : dy>0 ? 4 : dy<0 ? 1 : 0;
        const reverse=((forward<<2)|(forward>>2))&15;
        for (let n=0;n<=length;n++) visit((Math.floor(a/32)+Math.round(dy*n/length))*32+a%32+Math.round(dx*n/length),
          dx ? 1 : 2, (n<length ? forward : 0)|(n>0 ? reverse : 0));
      }
    }

    _paintMap() {
      const marks=new Map([...this._mapGrid].map(([cell,value])=>[cell,{...value,rank:0,heat:0}]));
      const states=['unknown','measured','uncovered','never','changed','warning','error'];
      const mark=(cell,look)=> {
        if (cell<0) return;
        const value=marks.get(cell) || {kind:'belt',axis:0,rank:0,heat:0};
        const rank=look.error ? 6 : look.warning ? 5 : look.changed ? 4 : look.never ? 3
          : ['uncovered','unreached'].includes(look.coverage) ? 2 : look.activity === 'active' ? 1 : 0;
        value.rank=Math.max(value.rank,rank);
        value.replay ||= look.coverage === 'selected' || look.coverage === 'reached';
        if (look.durationNanos != null) value.heat=Math.max(value.heat,clamp(Math.log10(1+look.durationNanos/1000)/4,0,1));
        marks.set(cell,value);
      };
      // Only measured identities contribute health. Off-screen/unobserved topology stays neutral;
      // a coarsened cell reports that it contains a signal, not that all its Steps share it.
      for (const node of [...this._overview.nodes,...this._scene.nodes]) {
        const owner=this._mapNodes.get(node.id) || (node.regions || []).map(id=>this._mapNodes.get(id)).find(Boolean) || node;
        const look=this._appearance(node);
        mark(this._mapCell(owner.x+owner.width/2,owner.y+owner.height/2),look);
        if (look.selected) this._mapSelection={id:node.id,x:node.x+node.width/2,y:node.y+node.height/2};
      }
      for (const link of this._scene.links) {
        const look=this._callbacks.linkAppearance?.(link) || {};
        this._mapLine(link.points,(cell,axis,ports)=> {mark(cell,look);marks.get(cell).axis |= axis;marks.get(cell).ports |= ports;});
      }
      if (this._mapSelection && this._callbacks.selectedId && this._mapSelection.id !== this._callbacks.selectedId()) this._mapSelection=null;
      if (this._mapSelection) {
        const cell=this._mapCell(this._mapSelection.x,this._mapSelection.y);
        if (cell>=0) marks.set(cell,{...marks.get(cell),selected:true});
      }
      for (const [cell,element] of this._mapElements) if (!marks.has(cell)) {element.remove();this._mapElements.delete(cell);}
      for (const [cell,value] of marks) {
        let element=this._mapElements.get(cell);
        if (!element) {
          element=document.createElement('i');
          element.style.cssText=`left:${cell%32/32*100}%;top:${Math.floor(cell/32)/20*100}%`;
          this._mapElements.set(cell,element);
          this._map.querySelector('.map-cells').append(element);
        }
        const state=states[value.rank || 0], heat=(value.heat || 0).toFixed(2);
        const signature=`${value.kind}:${value.axis}:${value.ports}:${state}:${value.selected}:${value.replay}:${heat}`;
        if (element.dataset.signature === signature) continue;
        Object.assign(element.dataset,{signature,kind:value.kind || 'step',axis:value.axis || 0,ports:value.ports || 0,state,selected:Boolean(value.selected),replay:Boolean(value.replay)});
        ['north','east','south','west'].forEach((side,i)=>element.style.setProperty(`--${side}`, value.ports&(1<<i) ? '50%' : '0%'));
        element.style.setProperty('--heat',heat);
        element.title=`${value.kind || 'Step'}${value.selected ? '; selected' : ''}; ${state === 'unknown' ? 'no current measurement' : 'contains '+state.replace('never','never executed').replace('uncovered','unreached by examples')}${value.heat ? '; blue intensity: sampled time, not utilization' : ''}`;
      }
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
      const box = this._planeBounds(source);
      let x = box.x + box.width + 52, y = box.y + box.height / 2;
      if (route) {
        const {points} = route;
        for (let i = points.length - 1; i > 0; i--) {
          const a = points[i - 1], b = points[i];
          const start = this._view(...a), end = this._view(...b);
          let from = 0, to = 1;
          for (let axis = 0; axis < 2; axis++) {
            const maximum = (axis ? this._size.height : this._size.width) - 32;
            const delta = end[axis] - start[axis];
            if (!delta) { if (start[axis] < 32 || start[axis] > maximum) to = -1; continue; }
            const first = (32-start[axis])/delta, last = (maximum-start[axis])/delta;
            from = Math.max(from, Math.min(first,last));
            to = Math.min(to, Math.max(first,last));
          }
          if (from > to) continue;
          x = a[0] + (b[0]-a[0])*(from+to)/2;
          y = a[1] + (b[1]-a[1])*(from+to)/2;
          break;
        }
      }
      const size = 136 * this._stationUnit(source);
      return {node: {id: "placement", kind: "step", preview: true, station_scale:source.station_scale, name: `Insert ${placement.name}`},
        look: {...RailixWorld.appearance, boundary: "dashed", color: "#126d78"},
        box: {x: x - size / 2, y: y - size / 2, width: size, height: size}};
    }

    _renderLabels(visible, rails, outgoing) {
      const candidates = [];
      const bodies = new Map();
      const cells = (box, visit) => {
        // Labels are screen-local; a zoomed machine can extend far beyond that viewport.
        for (let y = Math.max(0, Math.floor(box.y / 64)); y <= Math.floor(Math.min(this._size.height, box.y + box.height) / 64); y++) {
          for (let x = Math.max(0, Math.floor(box.x / 128)); x <= Math.floor(Math.min(this._size.width, box.x + box.width) / 128); x++) {
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
        let width = expanded ? Math.min(box.width - 20, 260) : Math.min(168, nameWidth);
        const measured = !expanded && box.width >= 48 && box.height >= 36 && look.duration;
        let height = expanded ? 30 : measured ? 54 : 36;
        let x = clamp(box.x + (box.width - width) / 2, 0, Math.max(0, this._size.width - width));
        const places = (expanded ? [[x, box.y+6]] : [[x,box.y+box.height+7], [x,box.y-height-7],
          [box.x-width-10,box.y+(box.height-height)/2], [box.x+box.width+10,box.y+(box.height-height)/2]])
          .filter(([x,y]) => x >= 0 && x+width <= this._size.width && y >= 0 && y+height <= this._size.height
            && !cells({x, y, width, height}, key => bodies.get(key)?.some(other =>
            other.node !== node && x < other.box.x + other.box.width && x + width > other.box.x
            && y < other.box.y + other.box.height && y + height > other.box.y)));
        let iconOnly = !places.length, y;
        if (iconOnly) {
          if (expanded) continue;
          ({x,y,width,height} = this._symbolBounds(box,node,look));
          places.push([x,y]);
        } else [x,y] = places[0];
        if (x < -width || y < -height || x > this._size.width || y > this._size.height) continue;
        candidates.push({ id: node.preview ? "placement" : `node:${node.id}`, node, look, compact, measured, box, x, y, places, width, height, iconOnly,
          rank: node.preview ? 1100 : look.selected ? 1000 : look.error ? 900 : node.kind === "trigger" || node.kind === "app" ? 800 : node.kind === "region" ? 700 : 600 });
      }
      for (const {link, points: route, look} of rails) {
        if (outgoing.get(link.from) < 2 || !link.outcome) continue;
        const label = this._callbacks.linkLabel?.(link);
        if (!label) continue;
        // Combine routing midpoints before the isometric projection so labels use the whole span.
        const points = route.filter((point, i) => !i || i === route.length - 1
          || !(point[0] === route[i-1][0] && point[0] === route[i+1][0]
            || point[1] === route[i-1][1] && point[1] === route[i+1][1])).map(point => this._view(...point));
        const width = Math.min(160, Math.max(56, label.length * 6 + 16));
        const start = points[0];
        const end = points.at(-1);
        if (Math.hypot(end[0] - start[0], end[1] - start[1]) < 40) continue;
        const places = [], onBelt = [];
        for (let i = points.length - 1; i > 0; i--) {
          const a = points[i-1], b = points[i], dx = b[0]-a[0], dy = b[1]-a[1], length = Math.hypot(dx,dy);
          if (length < width) continue;
          onBelt.push([(a[0]+b[0])/2-width/2, (a[1]+b[1])/2-10]);
          const clearance = 12 + 18*look.unit;
          for (const side of [-1,1]) places.push([(a[0]+b[0])/2-width/2-side*dy/length*clearance,
            (a[1]+b[1])/2-10+side*dx/length*clearance]);
        }
        const clear = ([x,y], inline = false) => x>=0 && y>=0 && x+width<=this._size.width && y+20<=this._size.height
          && !cells({x,y,width,height:20},key => bodies.get(key)?.some(({node,box}) =>
            !(inline && (node.id === link.from || node.id === link.to)) && x<box.x+box.width && x+width>box.x
            && y<box.y+box.height && y+20>box.y));
        const available = [...places.filter(point => clear(point)), ...onBelt.filter(point => clear(point, true))];
        if (!available.length) continue;
        const [x,y] = available[0];
        candidates.push({ id: `connection:${link.id}`, text: label, outcome: link.outcome, x, y, places: available, width, height: 20, rank: 750 });
      }
      candidates.sort((a, b) => b.rank - a.rank || a.id.localeCompare(b.id));
      const selected = [];
      for (const candidate of candidates) {
        if (selected.length === MAX_LABELS) break;
        if (candidate.rank < 1000) {
          const position = (candidate.places || (candidate.positions || [candidate.y]).map(y => [candidate.x,y]))
            .find(([x,y]) => !selected.some(other => x < other.x + other.width
              && x + candidate.width > other.x && y < other.y + other.height && y + candidate.height > other.y));
          if (!position) {
            if (!candidate.node || candidate.node.expanded) continue;
            Object.assign(candidate, this._symbolBounds(candidate.box,candidate.node,candidate.look), {iconOnly:true});
          } else [candidate.x, candidate.y] = position;
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
          element.classList.toggle("world-icon-only", Boolean(item.iconOnly));
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
            duration.textContent = item.measured ? `${look.duration} ${node.kind === 'region' ? 'avg sum' : 'avg'}` : '';
            duration.title = node.kind === 'region' ? 'Sum of sampled Step averages, not passage latency.'
              : 'Sampled mean execution time; not utilization or total flow latency.';
          }
          const bounds = this._symbolBounds(item.box, node, look);
          symbol.hidden = Boolean(node.expanded || node.kind === "end" || item.compact || this._buildings && !look.showIcon);
          symbol.style.left = `${bounds.x - item.x}px`;
          symbol.style.top = `${bounds.y - item.y}px`;
          symbol.style.width = `${bounds.width}px`;
          symbol.style.height = `${bounds.height}px`;
          symbol.dataset.symbol = look.symbol || node.kind;
          symbol.title = {string:"Output: String", number:"Output: Number", boolean:"Output: Boolean",
            object:"Output: Object", array:"Output: Array", any:"Output: Any JSON value",
            region:"Grouped Steps", branch:"Routing Step", trigger:"Trigger", app:"Application"}[look.symbol || node.kind] || "Step";
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
            const glyph = {app: "R", string: "Aa", number: "01", boolean: "T/F", object: "{ }", array: "[ ]"}[look.symbol || node.kind] || "";
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
      return selected;
    }
  }

  window.RailixWorld = RailixWorld;
})();
