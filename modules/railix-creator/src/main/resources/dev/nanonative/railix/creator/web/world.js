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
  const containsPoint = (points, x, y) => {
    const cross = points.map((a, i) => {
      const b = points[(i + 1) % points.length];
      return (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]);
    });
    return !cross.every(value => Math.abs(value) < .01)
      && (cross.every(value => value >= -.01) || cross.every(value => value <= .01));
  };

  /** A bounded, camera-relative view of the Creator-owned graph scene. */
  class RailixWorld {
    static appearance = Object.freeze({shape: "rectangle", aspect: 2.4, roundness: 6});

    constructor(stage, callbacks = {}) {
      this._stage = stage;
      this._plane = stage.querySelector("#world-plane");
      this._parts = new Map();
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
      this._return = stage.querySelector('#group-return');
      this._touches = new Map();
      if (this._return) this._listen(this._return, 'click', () => this.leave());
      if (!this._plane || !this._labels) {
        this._fail("The factory view is missing. Reload Creator.");
        return;
      }
      this._ground = document.createElement('div');
      this._ground.className = 'world-projection';
      this._plane.append(this._ground);
      this._editOverlay = document.createElement('div');
      this._editOverlay.className = 'world-edit';
      this._editOverlay.dataset.worldOverlay = '';
      this._editOverlay.innerHTML = '<svg class="connection-preview" aria-hidden="true"><path></path></svg><output class="connection-status" role="status" hidden><span>Choose destination</span><button type="button" data-cancel-connection>Cancel</button></output>';
      this._ports = document.querySelector('#inspector-connections nav');
      stage.append(this._editOverlay);
      stage.tabIndex = 0;
      stage.setAttribute("aria-label", "Flow canvas. Scroll to zoom, drag to pan. Use arrow keys to pan, plus and minus to zoom, Home for App start.");
      stage.dataset.renderer = "loading";
      this._listen(stage, "wheel", event => this._wheel(event), { passive: false });
      this._listen(stage, "pointerdown", event => this._pointerDown(event));
      this._listen(stage, "pointermove", event => this._pointerMove(event));
      this._listen(stage, "pointerleave", () => {
        if (this._cursor) this._cursor.hidden = true;
        stage.classList.remove("world-aim");
      });
      this._listen(stage, "pointerup", event => this._pointerEnd(event));
      this._listen(stage, "pointercancel", event => this._pointerEnd(event));
      this._listen(stage, "lostpointercapture", event => this._pointerEnd(event));
      this._listen(stage, "click", event => this._click(event));
      if (this._ports) {
        this._listen(this._ports, 'pointerdown', event => { if (event.pointerType !== 'touch') this._pointerDown(event); });
        this._listen(this._ports, 'click', event => {
          const port = event.target.closest('[data-output-port]');
          if (!port) return;
          this._beginConnection(port.dataset.outputPort);
          this._stage.focus({preventScroll:true});
        });
      }
      this._listen(this._editOverlay.querySelector('[data-cancel-connection]'), 'click', event => {
        event.stopPropagation();
        this.connection(null);
      });
      const chooseOutput = event => {
        if(!event.target.matches('[data-output-chooser]') || !event.target.value) return;
        this._beginConnection(event.target.value);
        event.target.value='';
        this._stage.focus({preventScroll:true});
      };
      this._listen(stage, 'change', chooseOutput);
      if (this._ports) this._listen(this._ports, 'change', chooseOutput);
      this._listen(stage, "dblclick", event => {
        if(this._callbacks.editingBusy?.()) return;
        if (this._groupClick) { this._groupClick = false; event.preventDefault(); return; }
        const node = this._eventNode(event);
        if (node && node.kind !== "end" && node.kind !== 'region') {
          event.preventDefault();
          event.stopPropagation();
          this.focus(node.id);
        }
      });
      this._listen(stage, "keydown", event => this._key(event));
      this._map = stage.querySelector("#world-map");
      if (this._map) this._listen(this._map, "click", event => {
        if (!this._overview) return;
        const rect = (this._map.querySelector('.map-content') || this._map).getBoundingClientRect(), bounds = this._mapBounds;
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
        const view=this._viewport();
        this._camera.x += direction[0] * Math.min(this._mapBounds.width/10,view.width/4);
        this._camera.y += direction[1] * Math.min(this._mapBounds.height/10,view.height/4);
        this._changedCamera();
      });
      this._listen(window, "resize", () => this._resize());
      this._listen(document, "visibilitychange", () => {
        this._touches.clear();
        this._pinch = null;
        this._pan = null;
        if (document.hidden) {
          this._mapRequest?.abort();
          clearTimeout(this._mapRetry);
          this._mapRevision = null;
        } else this._refreshOverview();
        stage.classList.remove('panning');
        const moving = Boolean(this._flightFrame);
        this._cancelFlight();
        if (moving || !document.hidden) this._changedCamera(!document.hidden);
        else this.repaint();
      });
      this._resizeObserver = new ResizeObserver(() => this._resize());
      this._resizeObserver.observe(stage);
      const inspector=stage.ownerDocument.querySelector('#inspector');
      if(inspector)this._resizeObserver.observe(inspector);
      this._resize();
      this._rendererReady = this.renderer().catch(() => this);
    }

    get scene() { return this._scene; }
    get timeScale() { return this._surface?.timeScale || []; }
    get navigation() { return (this._groups || []).map(({id,name,count,group}) => ({id,name,count,group})); }
    get query() { return this._sceneQuery || ""; }
    connection(value) {
      this._connection = value;
      this._drawEditControls();
      return this;
    }

    _beginConnection(from) {
      const separator=from.lastIndexOf('.');
      if (!this._scene?.nodes.some(node=>node.id===from.slice(0,separator))) this.focus(from.slice(0,separator));
      this.connection({from,node:from.slice(0,separator),outcome:from.slice(separator+1)});
      return this;
    }

    _drawEditControls() {
      if (!this._scene || !this._ports) return;
      const id = this._callbacks.selectedId?.();
      const selected = this._callbacks.selectedStation?.();
      const source = this._scene.nodes.find(node => node.id === id && ['step','trigger'].includes(node.kind)) || selected;
      const ports = source ? this._callbacks.outputPorts?.(source) || [] : [];
      const nav = this._ports;
      nav.parentElement.hidden = !ports.length;
      const markup = JSON.stringify(ports);
      if (nav.railixMarkup !== markup) {
        const controls = ports.length > 4 ? (() => {
          const select = document.createElement('select');
          select.dataset.outputChooser = '';
          select.setAttribute('aria-label','Choose output');
          select.append(new Option('Choose output', ''), ...ports.map(port => new Option(port.name,port.id)));
          return [select];
        })() : ports.map(port => {
          const button = document.createElement('button');
          button.type = 'button';
          button.dataset.outputPort = port.id;
          button.setAttribute('aria-label','Connect ' + port.name);
          button.title = 'Connect ' + port.name;
          button.innerHTML = '<i class="hud-symbol" data-symbol="enter" aria-hidden="true"></i>';
          const label = document.createElement('span');
          label.textContent = port.name;
          button.append(label);
          return button;
        });
        nav.replaceChildren(...controls);
        nav.railixMarkup = markup;
      }
      for (const button of nav.querySelectorAll('button'))
        button.setAttribute('aria-pressed',String(this._connection?.from === button.dataset.outputPort));
      const active = this._connection && this._scene.nodes.find(node => node.id === this._connection.node);
      let anchor;
      if (active) {
        const link = this._scene.links.find(link => link.from === active.id && link.outcome === this._connection.outcome);
        if (link) {
          const points = this._linkPoints(link,new Map([[active.id,{node:active,look:this._appearance(active),box:this._planeBounds(active)}]]));
          if (points.length) anchor = this._view(...points[0],18*this._stationUnit(active));
        }
      }
      const preview = this._editOverlay.querySelector('svg'), status = this._editOverlay.querySelector('output');
      preview.toggleAttribute('hidden',!this._connection);
      status.hidden = !this._connection;
      if (!this._connection) return;
      const actual = this._scene.nodes.find(node => node.id === this._connection.node) || this._connection.source;
      if(actual) this._connection.source=actual;
      const start = actual ? this._screenBounds(actual) : null;
      if (!start) { preview.toggleAttribute('hidden',true); return; }
      const a = anchor || [start.x + start.width, start.y + start.height/2];
      const b = this._connection.point || [a[0]+80,a[1]];
      preview.setAttribute('viewBox',`0 0 ${this._size.width} ${this._size.height}`);
      preview.querySelector('path').setAttribute('d',`M${a[0]},${a[1]} C${a[0]+60},${a[1]} ${b[0]-60},${b[1]} ${b[0]},${b[1]}`);
      preview.dataset.target = this._connection.target || '';
    }

    _connectionTarget(event) {
      const node = this._eventNode(event);
      if (node?.kind === 'end') return 'end';
      return node?.kind === 'step' && node.id !== this._connection?.node ? node.id : '';
    }

    _finishConnection(event, dragged) {
      const target = this._connectionTarget(event);
      if (target) { this._callbacks.connect?.(this._connection.from,target); return; }
      if (!dragged && !this._eventNode(event)) {
        this._callbacks.insert?.(this._connection.node,this._connection.outcome);
      }
      this.connection(null);
    }

    related(node) {
      const selected = this._callbacks.selectedId?.();
      if (this._relatedScene !== this._scene || this._relatedId !== selected) {
        this._relatedScene = this._scene; this._relatedId = selected;
        this._relatedTokens = this._scene?.nodes.find(item => item.id === selected)?.shared || [];
      }
      return node.id !== selected && node.shared?.some(a => this._relatedTokens.some(b => a === b || a.startsWith(b + '/') || b.startsWith(a + '/')));
    }

    /** Switch drawing after assets load; scene, camera and event handlers remain shared. */
    async renderer(variant = {}, signal, commit = () => {}) {
      const request = this._rendererRequest = (this._rendererRequest || 0) + 1;
      const type = variant.renderer || 'canvas';
      if (!['canvas','character'].includes(type)) throw Error(`Unsupported world renderer: ${type}.`);
      let assets, next;
      try {
        assets = type === 'canvas' ? await RailixCanvas.load(variant.atlas, signal) : null;
        signal?.throwIfAborted();
        if (this._disposed || request !== this._rendererRequest) {
          assets?.sprites.forEach(sprite => sprite.bitmap.close());
          return this;
        }
        // Initialize a graphics context before discarding the working renderer.
        next = type === 'character' ? new RailixCharacter(this._plane) : new RailixCanvas(this._plane, assets);
      } catch (error) {
        assets?.sprites.forEach(sprite => sprite.bitmap.close());
        if (!this._surface && !this._disposed && request === this._rendererRequest && error.name !== 'AbortError') this._fail(error.message);
        throw error;
      }
      commit();
      this._surface?.dispose();
      this._surface = next;
      this._stage.dataset.renderer = type;
      this._batch = null;
      this._resize();
      this._draw();
      if (this._scene) void this.refresh();
      return this;
    }

    /** Render one bounded building portrait with the world's materials, without another scene or camera loop. */
    portrait(node, host) {
      if (host && this._surface) {
        host.classList.add('sprite-portrait');
        this._surface.portrait(node,this._appearance(node),host);
      }
      return this;
    }

    async refresh() {
      if (this._disposed) return this;
      clearTimeout(this._requestTimer);
      this._requestTimer = 0;
      if (this._flightFrame) return this;
      this._request?.abort();
      const controller = new AbortController();
      this._request = controller;
      const requestId = ++this._requestId;
      if (this._rendererReady) await this._rendererReady;
      if (this._disposed || requestId !== this._requestId || controller.signal.aborted) return this;
      const viewVersion = this._viewVersion;
      let focus = this._pendingFocus;
      const query = new URLSearchParams({...this._viewport(), scale: this._camera.scale});
      if (focus) query.set("focus", focus);
      if (this._groups?.length) query.set('inside', this._groups.at(-1).id);
      try {
        let response = await fetch(`/api/scene?${query}`, { signal: controller.signal, cache: "no-store" });
        if (response.status === 404 && this._groups?.length) {
          if (this._disposed || requestId !== this._requestId || viewVersion !== this._viewVersion) return this;
          const preserveCamera = !focus;
          const group = this._groups.at(-1), selected = this._callbacks.selectedId?.();
          const requested = focus || (this._sceneNodes.get(selected)?.regions?.includes(group.id) ? selected : '');
          focus = requested && requested !== group.id && requested !== 'app' ? requested
            : group.group ? `group:${group.group}` : 'app';
          query.delete('inside');query.set('focus',focus);
          response = await fetch(`/api/scene?${query}`, {signal:controller.signal,cache:'no-store'});
          if (response.status === 404 && focus === `group:${group.group}`) {
            focus = 'app';query.set('focus',focus);
            response = await fetch(`/api/scene?${query}`, {signal:controller.signal,cache:'no-store'});
          }
          if (!response.ok) throw new Error(`The selected section could not be recovered (HTTP ${response.status}).`);
          const recovered = await response.json();
          if (this._disposed || requestId !== this._requestId || viewVersion !== this._viewVersion) return this;
          this._validate(recovered);
          const target = recovered.nodes.find(node => node.id === focus);
          const replacement = recovered.nodes.find(node => node.kind === 'region'
            && (group.group ? node.group === group.group : target?.regions?.includes(node.id))
            && (target?.id === node.id || target?.regions?.includes(node.id) || focus === `group:${group.group}`));
          if (replacement) {
            Object.assign(group,{id:replacement.id,name:replacement.name,count:replacement.count});
            query.set('inside',replacement.id);
            if (preserveCamera) focus = null;
          } else this._groups = [];
          this._callbacks.onGroup?.(this._groups.at(-1) || null, true);
          response = await fetch(`/api/scene?${query}`, {signal:controller.signal,cache:'no-store'});
        }
        if (!response.ok) throw new Error(`The graph scene could not be loaded (HTTP ${response.status}).`);
        let scene = await response.json();
        if (this._disposed || requestId !== this._requestId || viewVersion !== this._viewVersion) return this;
        this._validate(scene);
        if (focus && Array.isArray(scene.location)) {
          const previous = this._groups || [], rootCamera = previous[0]?.camera || {...this._camera};
          const groups = scene.location.map((part,index) => ({...part,
            camera:previous[index]?.id === part.id ? previous[index].camera : index ? null : rootCamera}));
          const scope = groups.at(-1)?.id || '';
          if (scope !== (scene.inside || '')) {
            if (scope) query.set('inside',scope); else query.delete('inside');
            response = await fetch(`/api/scene?${query}`, {signal:controller.signal,cache:'no-store'});
            if (!response.ok) throw Error(`The work area could not be opened (HTTP ${response.status}).`);
            scene = await response.json();
            if (this._disposed || requestId !== this._requestId || viewVersion !== this._viewVersion) return this;
            this._validate(scene);
          }
          this._groups = groups;
          this._callbacks.onGroup?.(this._groups.at(-1) || null,true);
        }
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
        this._refreshOverview();
        const group = this._groups?.at(-1);
        const updatedGroup = group && this._sceneNodes.get(group.id);
        if (updatedGroup?.kind === 'region'
          && (group.name !== updatedGroup.name || group.count !== updatedGroup.count)) {
          group.name = updatedGroup.name;
          group.count = updatedGroup.count;
          this._callbacks.onGroup?.(group, true);
        }
        const entering = this._zoomEntry;
        if (entering && !this._groups?.some(group => group.id === entering.id)
          && scene.nodes.some(node => node.id === entering.id && node.expanded || node.regions?.includes(entering.id))) {
          return this.enter(entering.id);
        }
        this._stage.dataset.sceneNodeCount = String(scene.nodes.length);
        this._stage.dataset.sceneLinkCount = String(scene.links.length);
        this._stage.dataset.sceneRevision = String(scene.revision);
        if (this._surface) this._clearError();
        this._callbacks.onScene?.(scene);
        if (!this._initialized || focus) {
          this._pendingFocus = null;
          let fixedScale = this._focusScale;
          this._focusScale = undefined;
          const group = this._groups?.at(-1);
          const entry = group && (group.id === focus || scene.target?.id === group.id) ? scene.entry : null;
          if (entry) {
            group.unit = Math.min(1, entry.station_scale);
            fixedScale = .5 / group.unit;
          } else if (fixedScale) fixedScale /= group?.unit || 1;
          else if (focus && scene.target?.kind !== 'region') fixedScale = .5 / Math.min(1,scene.target?.station_scale || 1);
          if (!this._frameBounds(entry || (focus ? scene.focus : scene.bounds), {
            maximumScale:focus ? MAX_SCALE : 1/(group?.unit || 1),
            minimumScale:focus ? scene.focus_min_scale : 0, fixedScale,
            station:entry || (focus && this._sceneNodes.get(focus)), flowStart:Boolean(entry || focus === 'app')})) return this.refresh();
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

    /** Reconcile visible geometry. The drawing surface owns its bounded animation loop. */
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
      this._resize();
      this._cancelFlight();
      this._pendingFocus = null;
      this._focusScale = undefined;
      if (this._scene && (this._scene.inside || '') === (this._groups?.at(-1)?.id || '')) {
        if (this._frameBounds(this._scene.scope || this._scene.bounds,
          {maximumScale:1/(this._groups?.at(-1)?.unit || 1)})) return this;
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
      (this._groups ||= []).push({id, group:node.group || '', name:node.name, count:node.count, camera});
      this._callbacks.onGroup?.(node);
      return this.focus(id);
    }

    /** Restore the enclosing view; zooming alone never exits an entered group. */
    leave(levels = 1) {
      if (!Number.isInteger(levels) || levels < 1 || !this._groups?.length) return this;
      const group = this._groups.splice(Math.max(0,this._groups.length-levels))[0];
      if (!group) return this;
      this._cancelFlight();
      this._zoomEntry = null;
      this._callbacks.onGroup?.(this._groups.at(-1) || null);
      if (!group.camera) return this.focus(this._groups.at(-1)?.id || 'app');
      this._camera = group.camera;
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
      this._editOverlay.remove();
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
      this._mapGrid = this._mapNodes = this._mapSelection = this._overview = null;
      this._cancel();
      this._listeners.abort();
      this._resizeObserver?.disconnect();
      if (this._pan && this._stage.hasPointerCapture(this._pan.id)) this._stage.releasePointerCapture(this._pan.id);
      this._stage.classList.remove("panning");
      this._pan = null;
      this._labelElements.clear();
      this._labels?.replaceChildren();
      this._clearError();
      this._parts.clear();
      this._used?.clear();
      this._plane?.replaceChildren();
      this._ground = null;
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
      this._projection = new DOMMatrixReadOnly(getComputedStyle(this._ground).transform);
      this.repaint();
      if (width === this._size.width && height === this._size.height) { this._revealSelection(); return; }
      const focus = this._pendingFocus || (this._flightFrame && this._scene?.target?.id);
      const scale = this._focusScale;
      this._cancelFlight();
      if (this._initialized) {
        for (const camera of [this._camera,...(this._groups || []).map(group=>group.camera).filter(Boolean)]) {
          camera.x += (this._size.width-width)/(2*camera.scale);
          camera.y += (this._size.height-height)/(2*camera.scale);
        }
      }
      this._size = {width, height};
      this._changedCamera();
      if (focus) this.focus(focus, scale);
      else this._revealSelection();
    }

    _revealSelection() {
      if(!this._initialized || this._pendingFocus || this._flightFrame
        || this._stage.ownerDocument.querySelector('#audio-panel')?.hidden===false
        || this._stage.ownerDocument.querySelector('#inspector')?.hidden!==false)return;
      const node=this._sceneNodes?.get(this._callbacks.selectedId?.());
      if(!node || node.kind==='region')return;
      const box=this._screenBounds(node);
      const obscured=box.x<0 || box.y<0 || box.x+box.width>this._size.width || box.y+box.height>this._size.height
        || this._controlBounds().some(control=>box.x<control.x+control.width && box.x+box.width>control.x
          && box.y<control.y+control.height && box.y+box.height>control.y);
      if(obscured)this._frameBounds(node,{fixedScale:this._camera.scale,station:node});
    }

    _controlBounds(includeWorld = true) {
      const stage = this._stage.getBoundingClientRect();
      return [...document.querySelectorAll('#world-hud, #group-return:not([hidden]), #group-navigation:not([hidden]), #example-session:not([hidden]), #example-time-scale:not([hidden]), .inspector:not([hidden])')]
        .filter(item=>includeWorld || item !== this._return).map(item => {
        const rect = item.getBoundingClientRect();
        return {x:rect.x-stage.x, y:rect.y-stage.y, width:rect.width, height:rect.height};
      });
    }

    _frameBounds(bounds, {minimumScale = 0, maximumScale = MAX_SCALE, fixedScale, station, flowStart = false} = {}) {
      const m = this._projection;
      const body = station && station.kind !== 'region' ? this._screenBounds(station) : null;
      const width = body ? body.width / this._camera.scale : Math.abs(m.m11) * bounds.width + Math.abs(m.m21) * bounds.height;
      const height = body ? body.height / this._camera.scale : Math.abs(m.m12) * bounds.width + Math.abs(m.m22) * bounds.height;
      const framing = 1;
      const labelSpace = !fixedScale && !this._scene?.inside && this._size.width <= 650
        && this._scene?.nodes.some(node=>node.summary==='area') ? 80 : 0;
      let frames = [{x:0, y:0, width:this._size.width-labelSpace, height:this._size.height}];
      for (const control of this._controlBounds(false)) frames = frames.flatMap(frame => {
          const left = Math.max(frame.x, control.x), top = Math.max(frame.y, control.y);
          const right = Math.min(frame.x+frame.width, control.x+control.width);
          const bottom = Math.min(frame.y+frame.height, control.y+control.height);
          if (left >= right || top >= bottom) return [frame];
          return [{...frame,width:left-frame.x}, {...frame,x:right,width:frame.x+frame.width-right},
            {...frame,height:top-frame.y}, {...frame,y:bottom,height:frame.y+frame.height-bottom}]
            .filter(candidate => candidate.width > 0 && candidate.height > 0);
        });
      const capacity = frame => Math.min(Math.max(1,frame.width-(fixedScale?16:64))/width,
        Math.max(1,frame.height-(fixedScale?16:128))/height)/framing;
      const frame = frames.reduce((best,candidate) => capacity(candidate)>capacity(best) ? candidate : best,
        frames[0] || {x:0,y:0,...this._size});
      const scale = clamp(Math.max(minimumScale,fixedScale ?? capacity(frame)), MIN_SCALE, Math.min(MAX_SCALE,maximumScale));
      const entryX=flowStart ? Math.max(frame.width*.14,Math.min(frame.width/2,width*scale/2+24)) : frame.width/2;
      const verticalBias=fixedScale ? Math.min(24,Math.max(0,(frame.height-height*scale)/2-8)) : 24;
      const [cx, cy] = this._unproject(frame.x+entryX,
        frame.y+frame.height*(flowStart ? .58 : .5)+verticalBias);
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
      if (event.pointerType === 'touch' && !event.target.closest('[data-world-overlay],input,select,textarea,a')) {
        this._touches.set(event.pointerId, [event.clientX,event.clientY]);
        if (this._touches.size === 2) {
          event.preventDefault();
          const [a,b] = [...this._touches.values()];
          this._pinch = {distance:Math.hypot(a[0]-b[0],a[1]-b[1]),x:(a[0]+b[0])/2,y:(a[1]+b[1])/2};
          this._pan = null;
          this._cancelFlight();
          this._stage.setPointerCapture(event.pointerId);
          return;
        }
      }
      const port = event.target.closest('[data-output-port]');
      if (port && event.button === 0) {
        event.preventDefault();
        this._beginConnection(port.dataset.outputPort);
        this._stage.focus({preventScroll:true});
        this._wireDrag = {id:event.pointerId,x:event.clientX,y:event.clientY,moved:false};
        this._stage.setPointerCapture(event.pointerId);
        return;
      }
      if (this._pan || (event.button !== 0 && event.button !== 1)
        || (event.button === 0 && event.target.closest("button, input, select, textarea, a, [data-world-overlay]")
          && !(event.pointerType === 'touch' && event.target.closest('[data-world-id]')))) return;
      event.preventDefault();
      const moving = Boolean(this._flightFrame);
      this._cancelFlight();
      if (moving) this._changedCamera();
      this._stage.focus({ preventScroll: true });
      this._pan = { id: event.pointerId, x: event.clientX, y: event.clientY, dragging: false };
      this._stage.setPointerCapture(event.pointerId);
    }

    _pointerMove(event) {
      if (this._touches.has(event.pointerId)) this._touches.set(event.pointerId,[event.clientX,event.clientY]);
      if (this._pinch && this._touches.size >= 2) {
        const [a,b] = [...this._touches.values()];
        const distance = Math.hypot(a[0]-b[0],a[1]-b[1]), x=(a[0]+b[0])/2, y=(a[1]+b[1])/2;
        this._panBy(this._pinch.x-x,this._pinch.y-y);
        if (this._pinch.distance > 0 && distance > 0) this.zoom(distance/this._pinch.distance,x,y);
        this._pinch = {distance,x,y};
        this._suppressClickUntil = performance.now()+500;
        return;
      }
      if (this._connection) {
        const rect = this._stage.getBoundingClientRect();
        this._connection.point = [event.clientX-rect.left,event.clientY-rect.top];
        this._connection.target = this._connectionTarget(event);
        if (this._wireDrag && Math.hypot(event.clientX-this._wireDrag.x,event.clientY-this._wireDrag.y)>6) this._wireDrag.moved=true;
        this._drawEditControls();
        if (this._wireDrag) return;
      }
      if (this._cursor) {
        const active = event.pointerType === "mouse" && !event.target.closest(".canvas-tools, [data-world-overlay]");
        this._cursor.hidden = !active;
        this._stage.classList.toggle("world-aim", active);
        if (active) this._cursor.style.translate = `${event.clientX}px ${event.clientY}px`;
      }
      if (this._pan?.id !== event.pointerId) return;
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
      this._touches.delete(event.pointerId);
      if (this._pinch) {
        this._pinch = null;
        this._touches.clear();
        this._pan = null;
        this._suppressClickUntil = performance.now()+500;
        this._stage.classList.remove('panning');
        if (this._stage.hasPointerCapture(event.pointerId)) this._stage.releasePointerCapture(event.pointerId);
        return;
      }
      if (this._wireDrag?.id === event.pointerId) {
        const dragged = this._wireDrag.moved;
        this._wireDrag = null;
        if (this._stage.hasPointerCapture(event.pointerId)) this._stage.releasePointerCapture(event.pointerId);
        if (event.type === 'pointercancel') this.connection(null);
        else if (dragged) this._finishConnection(event,true);
        this._portClickPending = true;
        return;
      }
      if (this._pan?.id !== event.pointerId) return;
      if (this._pan.dragging) this._suppressClickUntil = performance.now() + 250;
      this._pan = null;
      this._stage.classList.remove("panning");
      if (this._stage.hasPointerCapture(event.pointerId)) this._stage.releasePointerCapture(event.pointerId);
    }

    _eventNode(event, kind) {
      if (!this._scene || event.target.closest(".canvas-tools, [data-world-overlay]")) return null;
      const targetId=event.target.closest('[data-world-id]')?.dataset.worldId;
      if (targetId) return this._scene.nodes.find(node => node.id === targetId && (!kind || node.kind === kind));
      // Pointer capture delivers clicks to the canvas, even above a tall roof.
      const pointed=document.elementFromPoint(event.clientX,event.clientY);
      if(pointed?.closest('.canvas-tools,[data-world-overlay],#inspector,#overlay,#audio-panel')) return null;
      const id = pointed?.closest('[data-world-id]')?.dataset.worldId;
      if (id) return this._scene.nodes.find(node => node.id === id && (!kind || node.kind === kind));
      if (event.target !== this._stage && event.target !== this._labels && !event.target.closest("#world-plane")) return null;
      const rect = this._stage.getBoundingClientRect();
      const x = event.clientX - rect.left, y = event.clientY - rect.top;
      const sprite = this._surface?.hit(x,y,kind);
      if (sprite) return sprite;
      let hit = null;
      for (const node of this._scene.nodes) {
        if (kind && node.kind !== kind) continue;
        const look = this._appearance(node);
        const box = this._screenBounds(node, look);
        if (x < box.x || y < box.y || x > box.x + box.width || y > box.y + box.height) continue;
        const contour = this._contour(this._planeBounds(node, look), node.expanded ? {roundness: 0} : look);
        const depth = node.expanded || node.kind === 'end' ? 0 : 18 * this._stationUnit(node, look);
        const top = contour.map(point => this._view(...point, depth));
        const base = contour.map(point => this._view(...point));
        if ((containsPoint(top,x,y) || top.some((a, i) => containsPoint([a, top[(i + 1) % top.length], base[(i + 1) % top.length], base[i]],x,y)))
          && (!hit || node.width * node.height < hit.width * hit.height)) hit = node;
      }
      return hit;
    }

    _click(event) {
      if (event.detail <= 1) this._groupClick = false;
      if(this._callbacks.editingBusy?.()) { this._portClickPending=false; return; }
      if (this._portClickPending && event.detail) { this._portClickPending=false; return; }
      const port = event.target.closest('[data-output-port]');
      if (port && event.detail === 0) {
        this._beginConnection(port.dataset.outputPort);
        this._stage.focus(); return;
      }
      if (performance.now() < (this._suppressClickUntil || 0)
        || event.target.closest(".canvas-tools, [data-world-overlay]")) return;
      if (event.detail > 1 && this._groupClick) return;
      const node = this._eventNode(event);
      if (this._connection) { event.stopPropagation(); this._finishConnection(event,false); return; }
      if (!node || node.expanded && !event.target.closest('[data-world-id], [data-group-region-label]')) {
        const rect = this._stage.getBoundingClientRect();
        const point = [event.clientX-rect.left,event.clientY-rect.top];
        const hits = [];
        const stations=new Map((this._scene?.nodes||[]).map(node=>[node.id,{node,look:this._appearance(node),box:this._planeBounds(node)}]));
        for (const link of this._scene?.links || []) {
          if (link.power) continue;
          const source=stations.get(link.from)?.node;
          if (source && !['step','trigger','region'].includes(source.kind)) continue;
          const unit=this._camera.scale*link.station_scale;
          const rail={link,points:this._linkPoints(link,stations),look:{unit}},bridges=this._bridges(rail);
          const path=this._routePoints(rail),points=path.map(p=>this._view(p[0],p[1],6*unit+(p[2]||0)));
          const under=new Set(bridges.filter(bridge=>bridge.points.slice(1).some((b,index)=>{
            const a=bridge.points[index],p=(v,side)=>this._view(v[0],v[1]+side*15*unit,v[2]+6*unit);
            return containsPoint([p(a,-1),p(b,-1),p(b,1),p(a,1)],...point);
          })).flatMap(bridge=>bridge.under));
          let distance = Infinity;
          for (let index=1;index<points.length;index++) {
            const a=points[index-1],b=points[index],dx=b[0]-a[0],dy=b[1]-a[1];
            const t=clamp(((point[0]-a[0])*dx+(point[1]-a[1])*dy)/(dx*dx+dy*dy||1),0,1);
            const candidate=Math.hypot(point[0]-a[0]-t*dx,point[1]-a[1]-t*dy);
            distance=Math.min(distance,candidate);
          }
          if(distance<12 || under.size)hits.push({link,distance,under});
        }
        const occluded=new Set(hits.flatMap(hit=>[...hit.under]));
        const visible=hits.filter(hit=>!occluded.has(hit.link.id));
        if (visible.length) {
          visible.sort((a,b)=>a.distance-b.distance);
          const arrivals=visible.filter(hit=>hit.distance<=visible[0].distance+.75)
            .flatMap(({link})=>link.outcomes ? link.outcomes.map(outcome=>({...link,outcome})) : [link])
            .filter((link,index,links)=>links.findIndex(other=>other.from===link.from && other.outcome===link.outcome)===index);
          this._callbacks.insert?.(arrivals[0].from,arrivals[0].outcome,arrivals);
          return;
        }
        this._callbacks.deselect?.();
        return;
      }
      event.stopPropagation();
      if (node.kind === "end") return;
      if (node.kind === "region") {
        if (this.navigation.at(-1)?.id !== node.id) { this._groupClick = true; this.enter(node.id); }
        else if (this._callbacks.selectGroup) this._callbacks.selectGroup(node.group, node.id, event);
        else this.focus(node.id);
      } else this._callbacks.selectNode?.(node.id, event);
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
        if (this._connection) { this.connection(null); event.preventDefault(); event.stopPropagation(); }
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
      const margin = 64 + 40 * unit, height = this._buildingHeight * unit;
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
      if (node.summary === 'run') return {...look, shape: 'rectangle', aspect: 2.4, roundness: 0};
      return {...look,shape:look.shape || 'rectangle',aspect:look.aspect ?? 2.4};
    }

    _height(node, look) {
      return node.expanded || node.kind === 'end' ? 0
        : 18 * this._stationUnit(node, look);
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
      if(node.kind==='end') return {x:x+width/2-24*stationScale,y:y+height/2-24*stationScale,
        width:48*stationScale,height:48*stationScale};
      const aspect = look.aspect ?? RailixWorld.appearance.aspect;
      const stationHeight = 224 / Math.max(1, aspect) * stationScale;
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
        const stationScale=Math.min(1,node.station_scale);
        const port = [box.x + box.width/2 + (x-node.x-node.width/2)/(168*stationScale)*box.width,
          box.y + box.height/2 + (y-node.y-node.height/2)/(64*stationScale)*box.height];
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
      // Only terminal contacts follow authored contours. Interior corridors belong to the scene.
      for (const [end, next] of [[0,1],[points.length-1,points.length-2]]) {
        const horizontal = end === 0 ? link.points[0][1] === link.points[1][1]
          : link.points.at(-1)[1] === link.points.at(-2)[1];
        points[next][horizontal ? 1 : 0] = points[end][horizontal ? 1 : 0];
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
          const key = `${output ? 'from' : 'to'}:${rail.link[output ? 'from' : 'to']}:${output ? rail.link.outcome : ''}:${points[0]}:${axis}:${direction}`;
          if (!ports.has(key)) ports.set(key, {axis, direction, output, rails: []});
          ports.get(key).rails.push(i);
        }
      });
      for (const port of ports.values()) {
        if (port.rails.length < 2) continue;
        for (const i of port.rails) parents[root(i)] = root(port.rails[0]);
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

    _part(key, name, parent = this._ground) {
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

    _machine({node, look, box, contacts = [], unit = this._stationUnit(node)}) {
      if (this._surface) {
        const ancestor = !this._revealFrom?.has(node.id) && node.regions?.map(id => this._revealFrom?.get(id)).find(part => part && !part.expanded);
        let reveal;
        if (ancestor && !node.expanded && !document.hidden && !this._callbacks.reducedMotion?.()) {
          const origin = this._planeBounds(ancestor);
          const from=this._view(origin.x+origin.width/2,origin.y+origin.height/2), to=this._view(box.x+box.width/2,box.y+box.height/2);
          reveal={dx:from[0]-to[0],dy:from[1]-to[1],start:performance.now()};
        }
        this._surface.station({node,look,box,contacts},this._contour(box,node.expanded?{roundness:0}:look),unit,reveal);
        return;
      }
    }

    _transport(rails) {
      const view = this._batch.view, lower = this._project(view.x, view.y);
      const upper = this._project(view.x + view.width, view.y + view.height);
      const networks=[...this._networks(rails)];
      for (const network of networks) {
        const axes = new Map(), junctions = new Map();
        const unit = Math.min(...network.map(rail=>rail.look.unit));
        for (const rail of network) rail.look.unit = unit;
        for (const rail of network) {
          const points=this._routePoints(rail),look=rail.look;
          for (let i = 1; i < points.length; i++) {
            const a = points[i - 1], b = points[i];
            if (a[2] || b[2]) continue;
            const dx = b[0] - a[0], dy = b[1] - a[1];
            if (Math.hypot(dx, dy) < .01) continue;
            const horizontal = Math.abs(dy) < .01, axis = horizontal ? 0 : 1;
            const key = `${axis}:${this._worldKey(a).split(':')[1-axis]}`;
            if (!axes.has(key)) axes.set(key, []);
            const cutA=Boolean(points[i-2]?.[2]),cutB=Boolean(points[i+1]?.[2]),forward=b[axis]>a[axis];
            axes.get(key).push({a, b, look, axis, from: Math.min(a[axis], b[axis]), to: Math.max(a[axis], b[axis]),
              cutLow:forward?cutA:cutB,cutHigh:forward?cutB:cutA});
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
            const cutLow=active.some(item=>item.cutLow && Math.abs(item.from-start)<.001);
            const cutHigh=active.some(item=>item.cutHigh && Math.abs(item.to-end)<.001);
            const first = Math.max(start-(cutLow?0:overrun), lower[axis]-margin);
            const last = Math.min(end+(cutHigh?0:overrun), upper[axis]+margin);
            if (last <= first) continue;
            const origin = [...a];
            origin[axis] = forward ? first : last;
            const phase = Number((-((origin[axis]+(axis?this._camera.y:this._camera.x)*this._camera.scale)*direction/unit)%32).toFixed(6));
            this._moving ||= best.look.rate > 0;
            this._replaying ||= !best.look.power && best.look.replay;
            this._surface.run({origin,length:last-first,unit,angle:Math.atan2(b[1]-a[1],b[0]-a[0]),look:best.look,phase});
          }
        }
        for (const [key, {point, mask, look}] of junctions) {
          if (mask === 5 || mask === 10 || (mask & (mask-1)) === 0) continue;
          const phase=-(point[0]+point[1]+(this._camera.x+this._camera.y)*this._camera.scale)/unit;
          this._surface.junction(point,mask,look,unit,phase);
        }
      }
      const decks = new Map();
      for (const rail of rails) for (const bridge of this._bridges(rail)) {
        const key = this._worldKey(bridge.at);
        const previous=decks.get(key);
        if (!previous || rail.look.selected && !previous.rail.look.selected
          || Boolean(rail.look.selected)===Boolean(previous.rail.look.selected) && (rail.look.rate||0)>(previous.rail.look.rate||0))
          decks.set(key,{rail,bridge});
      }
      for (const network of networks) this._cargo(network,lower,upper);
      for (const {bridge} of decks.values()) this._surface.bridge(bridge);
    }

    _bridges(rail) {
      const spans=[];
      for (const bridge of rail.link.bridges || []) {
        const at=this._project(...bridge.at),unit=rail.look.unit;
        const segment=rail.points.slice(1).map((b,index)=>[rail.points[index],b])
          .find(([a,b])=>Math.abs(a[1]-at[1])<.001 && Math.abs(b[1]-at[1])<.001
            && at[0]>Math.min(a[0],b[0]) && at[0]<Math.max(a[0],b[0]));
        if (!segment) continue;
        const half=Math.min(48*unit,at[0]-Math.min(segment[0][0],segment[1][0]),Math.max(segment[0][0],segment[1][0])-at[0]);
        const deck=Math.min(18*unit,half*.45);
        spans.push({segment:segment.flat().join(':'),y:at[1],unit,forward:segment[1][0]>segment[0][0],under:[bridge.under],
          low:at[0]-half,left:at[0]-deck,right:at[0]+deck,high:at[0]+half});
      }
      spans.sort((a,b)=>a.segment.localeCompare(b.segment)||a.low-b.low);
      const merged=[];
      for (const span of spans) {
        const previous=merged.at(-1);
        if (previous?.segment===span.segment && span.low<=previous.high) {
          previous.right=Math.max(previous.right,span.right);previous.high=Math.max(previous.high,span.high);
          previous.under.push(...span.under);
        } else merged.push({...span});
      }
      return merged.map(span=> {
        const {unit,y}=span,at=[(span.left+span.right)/2,y];
        return {at,unit,look:rail.look,under:span.under,phase:-(at[0]+this._camera.x*this._camera.scale)/unit,forward:span.forward,points:
          [[span.low,y,0],[span.left,y,24*unit],[span.right,y,24*unit],[span.high,y,0]]};
      });
    }

    _routePoints(rail) {
      const bridges=this._bridges(rail),path=rail.points;
      return path.flatMap((a,index)=> {
        const b=path[index+1];
        if(!b || a[1]!==b[1])return [[...a]];
        const inserted=bridges.flatMap(bridge=>bridge.points).filter(p=>Math.abs(p[1]-a[1])<.001
          && p[0]>Math.min(a[0],b[0]) && p[0]<Math.max(a[0],b[0]));
        inserted.sort((p,q)=>(p[0]-q[0])*Math.sign(b[0]-a[0]));
        return [[...a],...inserted];
      });
    }

    _cargo(network, lower, upper) {
      for (const {link, points, look} of network) {
        if (look.power || !(look.rate > 0 || look.replay) || look.unit < .18) continue;
        const unit = look.unit, margin = 40 * unit;
        const path = this._routePoints({link,points,look})
          .filter((p,i,array) => !i || Math.hypot(p[0]-array[i-1][0],p[1]-array[i-1][1]) > .01);
        if (path.length < 2) continue;
        // Loops close underneath stations, not at each visible elbow. Clip only outside the overscan.
        for (const [index,other] of [[0,1],[path.length-1,path.length-2]]) {
          const a=path[index], b=path[other], length=Math.hypot(a[0]-b[0],a[1]-b[1]);
          for (let axis=0;axis<2;axis++) a[axis] += (a[axis]-b[axis])/length*32*unit;
        }
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
          this._surface.route({id:link.id,look,unit,points:path,segments});
      }
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
      if (this._disposed || !this._surface) return;
      if (this._return) {
        const entry=this._scene?.inside && this._scene.entry;
        this._return.hidden=!entry;
        if (entry) {
          const box=this._viewBounds(this._planeBounds(entry),18*this._camera.scale*(entry.station_scale||1));
          const parent=this.navigation.at(-2)?.name || this._callbacks.parentName?.() || 'System';
          this._return.querySelector('span').textContent='Back';
          this._return.setAttribute('aria-label',`Return to ${parent}`);
          this._return.title=`Back to ${parent}`;
          const beside=box.x>=112;
          const left=clamp(beside?box.x-104:box.x+box.width/2-44,8,this._size.width-96);
          let top=beside?box.y+box.height/2-22:box.y-60;
          const legend=this._stage.querySelector('#example-time-scale');
          if(legend&&!legend.hidden && left<legend.offsetLeft+legend.offsetWidth && left+88>legend.offsetLeft
            && top<legend.offsetTop+legend.offsetHeight && top+44>legend.offsetTop)top=legend.offsetTop+legend.offsetHeight+8;
          this._return.style.left=`${left}px`;
          this._return.style.top=`${top}px`;
          this._return.hidden=box.y+box.height<0 || box.y>this._size.height || box.x>this._size.width || box.x+box.width<0;
        }
      }
      const controls = this._controlBounds();
      this._drawEditControls();
      this._used = new Set();
      const view = this._viewport(), batch = this._batch;
      const scale = batch ? this._camera.scale/batch.camera.scale : 1;
      // Rebase across an octave so clipped surfaces and detail stay bounded at extreme zoom.
      let reconcile = Boolean(this._surface && !this._surface.camera) || this._geometryDirty || !batch || view.x < batch.view.x || view.y < batch.view.y
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
        if(!this._pan?.dragging && !this._pinch && !this._flightFrame && !this._requestTimer
          && this._surface.timing?.(visible.filter(({node,box})=>!node.expanded&&['step','region'].includes(node.kind)
          && !controls.some(control=>box.x+box.width/2>=control.x && box.x+box.width/2<=control.x+control.width
            && box.y+box.height/2>=control.y && box.y+box.height/2<=control.y+control.height))
          .map(({look})=>look.durationNanos))) {
          reconcile=true;
          this._callbacks.onTiming?.();
        }
        const dx = batch ? (batch.camera.x-this._camera.x)*unit : 0;
        const dy = batch ? (batch.camera.y-this._camera.y)*unit : 0;
        const rails = reconcile ? this._scene.links.map(link => {
          const look = this._callbacks.linkAppearance?.(link) || {};
          const unit = Math.min(this._camera.scale * (link.station_scale ?? 1), ...[byId.get(link.from),byId.get(link.to)]
            .filter(station=>station && !station.node.expanded).map(station=>this._stationUnit(station.node,station.look)));
          return {link, points: this._linkPoints(link, byId), look: {...look, unit,
            power: link.power || byId.get(link.from)?.node.kind === "app", replay: look.selected,
            selected: this._placementLink(link) || look.selected || look.editing}};
        }) : batch.rails.map(rail => ({...rail, look:{...rail.look,unit:rail.look.unit*scale},
          points:rail.points.map(([x,y]) => [x*scale+dx,y*scale+dy])}));
        if (reconcile) {
          this._surface?.begin(this._size,this._projection,this._view(0,0),getComputedStyle(document.documentElement),
            this._camera,this._groups?.at(-1)?.unit || 1);
          this._batch = {camera:{...this._camera}, rails,
            view:{x:view.x-view.width/4,y:view.y-view.height/4,width:view.width*1.5,height:view.height*1.5}};
          this._moving = false;
          this._replaying = false;
          this._transport(rails);
          for (const rail of rails) for (const [id,point,other] of [[rail.link.from,rail.points[0],rail.points[1]],
            [rail.link.to,rail.points.at(-1),rail.points.at(-2)]]) {
            const station=byId.get(id);
            if(station && point && other) (station.contacts ||= []).push({point,other});
          }
          for (const station of stations) {
            const box = this._viewBounds(station.box, this._height(station.node,station.look));
            if (box.x+box.width >= -margin && box.y+box.height >= -margin
              && box.x <= this._size.width+margin && box.y <= this._size.height+margin) this._machine(station);
          }
        } else {
          this._surface?.camera(this._size,this._projection,this._view(0,0),scale,dx,dy);
        }
        const placement = this._placementStation(rails);
        if (placement) {
          this._machine(placement);
          visible.push({...placement,box:this._viewBounds(placement.box,this._height(placement.node,placement.look))});
        }
        this._renderLabels(visible, rails, outgoing, controls);
        let audible = 0;
        for (const {node, look, box} of visible) if (!node.expanded && box.width > 100 && (look.rate > 0 || look.coverage === 'selected')) {
          const distance = Math.hypot(box.x+box.width/2-this._size.width/2, box.y+box.height/2-this._size.height/2);
          audible = Math.max(audible, clamp(1-distance/400, 0, 1) * clamp((box.width-100)/150, 0, 1));
        }
        this._callbacks.onActivity?.(audible);
      }
      for (const [key, element] of this._parts) if (!this._used.has(key)) {
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
      // Expiry stops transport when no new observation or camera update arrives.
      if (moving && this._callbacks.motionActive) this._motionTimer = setTimeout(() => this._syncMotion(), 500);
    }

    _refreshOverview() {
      if (!this._map || !this._scene || this._disposed || document.hidden) return;
      const revision = this._scene.revision, inside=new URLSearchParams(this._sceneQuery).get('inside');
      const context=JSON.stringify([revision,inside]);
      if (this._mapRevision !== context) {
        this._mapRequest?.abort();
        clearTimeout(this._mapRetry);
        this._map.hidden = true;
        this._mapRevision = context;
        const controller = this._mapRequest = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 5_000);
        const query = new URLSearchParams({...this._scene.scope || this._scene.bounds, scale: MIN_SCALE});
        if(inside)query.set('inside',inside);
        fetch(`/api/scene?${query}`, {signal: controller.signal, cache: "no-store"}).then(response => {
          if (!response.ok) throw new Error("Minimap unavailable");
          return response.json();
        }).then(scene => {
          if (this._disposed || document.hidden || this._mapRequest !== controller || scene.revision !== this._scene.revision) return;
          this._overview = scene;
          this._overviewContext=context;
          this._mapSelection = null;
          this._mapElements = new Map();
          this._map.querySelector('.map-cells').replaceChildren();
          this._minimap();
          this.repaint();
        }).catch(() => {
          if (this._mapRequest !== controller || this._disposed || document.hidden) return;
          this._map.hidden = true;
          this._mapRetry = setTimeout(() => { this._mapRevision = null; this._refreshOverview(); }, 5_000);
        }).finally(() => clearTimeout(timeout));
      }
    }

    _minimap() {
      if (!this._map || !this._scene || this._disposed || document.hidden) return;
      const context = JSON.stringify([this._scene.revision, new URLSearchParams(this._sceneQuery).get('inside')]);
      if (this._overviewContext !== context) return;
      this._map.hidden = false;
      const view = this._viewport(), topology=this._overview.scope || this._overview.bounds;
      const width = Math.max(topology.width,topology.height*this._mapAspect)*1.12;
      const height = width/this._mapAspect;
      const bounds = this._mapBounds = {x:topology.x+topology.width/2-width/2,y:topology.y+topology.height/2-height/2,width,height};
      const title=this._map.querySelector('.map-title'),caption=this.navigation.at(-1)?.name || this._callbacks.parentName?.() || 'System';
      if(title && title.textContent!==caption) {
        title.textContent=caption;
        this._map.setAttribute('aria-label',`Overview of ${caption}. Click to travel; arrow keys to pan.`);
      }
      if(this._mapTopology!==this._overview) {
        this._mapTopology=this._overview;
        this._mapGrid = new Map();
        this._mapNodes = new Map(this._overview.nodes.map(node=>[node.id,node]));
        for (const link of this._overview.links) this._mapLine(link.points,(cell,axis,ports)=> {
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
      }
      const x = clamp((view.x-bounds.x)/bounds.width*100, 0, 100), y = clamp((view.y-bounds.y)/bounds.height*100, 0, 100);
      const right = clamp((view.x+view.width-bounds.x)/bounds.width*100, 0, 100), bottom = clamp((view.y+view.height-bounds.y)/bounds.height*100, 0, 100);
      const camera=this._map.querySelector('.map-camera'),style=`left:${x}%;top:${y}%;width:${right-x}%;height:${bottom-y}%`;
      if(camera.getAttribute('style')!==style)camera.setAttribute('style',style);
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
      const marks=new Map([...this._mapGrid].map(([cell,value])=>[cell,{...value,rank:0}]));
      const states=['unknown','measured','uncovered','never','changed','warning','error'];
      const mark=(cell,look)=> {
        if (cell<0) return;
        const value=marks.get(cell) || {kind:'belt',axis:0,rank:0};
        const rank=look.error ? 6 : look.warning ? 5 : look.changed ? 4 : look.never ? 3
          : ['uncovered','unreached'].includes(look.coverage) ? 2 : look.activity === 'active' ? 1 : 0;
        value.rank=Math.max(value.rank,rank);
        value.replay ||= look.coverage === 'selected' || look.coverage === 'reached';
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
        const state=states[value.rank || 0];
        const signature=`${value.kind}:${value.axis}:${value.ports}:${state}:${value.selected}:${value.replay}`;
        if (element.dataset.signature === signature) continue;
        Object.assign(element.dataset,{signature,kind:value.kind || 'step',axis:value.axis || 0,ports:value.ports || 0,state,selected:Boolean(value.selected),replay:Boolean(value.replay)});
        ['north','east','south','west'].forEach((side,i)=>element.style.setProperty(`--${side}`, value.ports&(1<<i) ? '50%' : '0%'));
        element.title=`${value.kind || 'Step'}${value.selected ? '; selected' : ''}; ${state === 'unknown' ? 'no current measurement' : 'contains '+state.replace('never','never executed').replace('uncovered','unreached by examples')}`;
      }
    }

    _placementLink(link) {
      return this._placement && link.from === this._placement.after
        && (!this._placement.outcome || link.outcome === this._placement.outcome || link.outcomes?.includes(this._placement.outcome));
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

    _renderLabels(visible, rails, outgoing, controls) {
      const candidates = [];
      const areas=visible.filter(station=>station.node.summary==='area' && !station.node.expanded).length;
      const landmarks=visible.filter(station=>['app','trigger'].includes(station.node.kind) || outgoing.get(station.node.id)>1).length;
      const labelBudget = Math.min(MAX_LABELS, Math.max(8, visible.filter(station => !station.node.expanded
        && station.box.width >= 48 && station.box.height >= 18).length
          + rails.filter(rail => outgoing.get(rail.link.from) > 1).length, Math.min(16,areas)+landmarks,
        Math.floor(this._size.width * this._size.height / 110000)));
      const bodies = new Map(), tracks = new Map();
      const obscured = (x,y,width,height) => controls.some(box => x < box.x+box.width && x+width > box.x
        && y < box.y+box.height && y+height > box.y);
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
      for (const rail of rails) {
        const points = rail.points.map(point => this._view(...point));
        for (let i = 1; i < points.length; i++) {
          const a = points[i-1], b = points[i], pad = Math.max(3, 9 * rail.look.unit) + 4;
          const track = {a,b,pad};
          cells({x:Math.min(a[0],b[0])-pad,y:Math.min(a[1],b[1])-pad,
            width:Math.abs(b[0]-a[0])+2*pad,height:Math.abs(b[1]-a[1])+2*pad}, key => {
            if (!tracks.has(key)) tracks.set(key,[]);
            tracks.get(key).push(track);
            return false;
          });
        }
      }
      const crossesTrack = (x,y,width,height) => cells({x,y,width,height}, key => tracks.get(key)?.some(({a,b,pad}) => {
        // Clip the segment to the label's padded rectangle, including projected diagonal tracks.
        let from = 0, to = 1;
        for (const [axis,low,high] of [[0,x-pad,x+width+pad],[1,y-pad,y+height+pad]]) {
          const delta = b[axis]-a[axis];
          if (!delta) { if (a[axis]<low || a[axis]>high) return false; continue; }
          const first = (low-a[axis])/delta, last = (high-a[axis])/delta;
          from = Math.max(from,Math.min(first,last));
          to = Math.min(to,Math.max(first,last));
          if (from > to) return false;
        }
        return true;
      }));
      for (const station of visible) {
        const {node, look} = station;
        const box = station.box;
        if (node.kind === 'end' && !look.error && !look.warning) continue;
        if (!look.selected && (box.x+box.width/2<0 || box.x+box.width/2>this._size.width
          || box.y+box.height/2<0 || box.y+box.height/2>this._size.height)) continue;
        const compact = box.width < 18 || box.height < 18;
        if (compact && !look.selected
          && !["app", "trigger", "region"].includes(node.kind)) continue;
        if (box.x + box.width < 0 || box.y + box.height < 0) continue;
        const expanded = node.expanded;
        const nameWidth = Math.max(72, (look.label || node.name).length * 8.2 + 22,
          node.kind === "region" || node.kind === "trigger" ? 80 : 0);
        let width = expanded ? Math.min(box.width - 20, 260) : Math.min(box.width < 136 ? 116 : 196, nameWidth);
        const measured = !expanded && box.width >= 104 && box.height >= 36 && Boolean(look.duration);
        let height = expanded ? 22 : measured ? 46 : look.selected ? 36 : 22;
        let x = clamp(box.x + (box.width - width) / 2, 0, Math.max(0, this._size.width - width));
        const module = !expanded && box.width >= 128 && box.height >= 40;
        if (module) { const inset=Math.min(54*this._stationUnit(node),box.width*.36);x=box.x+inset;width=box.width-inset-12*this._stationUnit(node);height=box.height-8; }
        const edgeArea = !expanded && !module && node.summary === 'area' && box.x+box.width+width+10>this._size.width;
        if (edgeArea) width=Math.min(width,Math.max(48,this._size.width-box.x-box.width*.4-8));
        const places = (edgeArea ? [[this._size.width-width-8,box.y+(box.height-height)/2]] : []).concat(
          module ? [[x,box.y+4]] : (expanded ? [[x, box.y+6]] : [[x,box.y+box.height+7], [x,box.y-height-7],
          [box.x-width-10,box.y+(box.height-height)/2], [box.x+box.width+10,box.y+(box.height-height)/2]])
          .concat(!expanded && node.summary==='area' ? [[x,box.y+(box.height-height)/2]] : []))
          .filter(([x,y]) => x >= 0 && x+width <= this._size.width && y >= 0 && y+height <= this._size.height
            && !obscured(x,y,width,height)
            && (module || !crossesTrack(x,y,width,height))
            && !cells({x, y, width, height}, key => bodies.get(key)?.some(other =>
            other.node !== node && x < other.box.x + other.box.width && x + width > other.box.x
            && y < other.box.y + other.box.height && y + height > other.box.y)));
        let iconOnly = !places.length, y;
        if (iconOnly) {
          if (expanded) continue;
          ({x,y,width,height} = this._symbolBounds(box,node,look));
          if (obscured(x,y,width,height) && !look.selected && !node.preview) continue;
          places.push([x,y]);
        } else [x,y] = places[0];
        if (x < -width || y < -height || x > this._size.width || y > this._size.height) continue;
        const distance = Math.hypot(box.x+box.width/2-this._size.width/2,box.y+box.height/2-this._size.height/2);
        candidates.push({ id: node.preview ? "placement" : `node:${node.id}`, node, look, compact, measured, module, box, x, y, places, width, height, iconOnly,
          rank: node.preview ? 1100 : look.selected ? 1000 : look.error ? 900 : look.warning ? 890
            : look.coverage === 'selected' ? 880-Math.min(30,distance/60)
              : node.summary === 'area' ? 840 : node.kind === "trigger" || node.kind === "app" ? 830 : outgoing.get(node.id) > 1 ? 800
                : node.kind === "region" ? 760-Math.min(50,distance/30) : 600-Math.min(50,distance/30) });
      }
      for (const {link, points: route, look} of rails) {
        if (outgoing.get(link.from) < 2 || !link.outcome) continue;
        const label = this._callbacks.linkLabel?.(link);
        if (!label) continue;
        const target = this._sceneNodes.get(link.to);
        const targetName = target?.name;
        if (targetName && label.trim().toLowerCase() === targetName.trim().toLowerCase()) continue;
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
          && !obscured(x,y,width,20)
          && !cells({x,y,width,height:20},key => bodies.get(key)?.some(({node,box}) =>
            !(inline && (node.id === link.from || node.id === link.to)) && x<box.x+box.width && x+width>box.x
            && y<box.y+box.height && y+20>box.y));
        const available = [...places.filter(point => clear(point)), ...onBelt.filter(point => clear(point, true))];
        if (!available.length) continue;
        const [x,y] = available[0];
        candidates.push({ id: `connection:${link.id}`, text: label, outcome: link.outcome, x, y, places: available, width, height: 20, rank: look.selected ? 875 : 550 });
      }
      candidates.sort((a, b) => b.rank - a.rank || a.id.localeCompare(b.id));
      const selected = [];
      for (const candidate of candidates) {
        if (selected.length === labelBudget) break;
        {
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
      this._labelBoxes = selected.filter(item => !item.iconOnly).map(({id,x,y,width,height}) => ({id,x,y,width,height}));
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
          element.classList.toggle('world-area-label', node.summary === 'area' && !node.expanded);
          if (node.preview) element.className = "world-label world-placement";
          else element.dataset.worldId = node.id;
          element.classList.toggle("world-icon-only", Boolean(item.iconOnly));
          element.classList.toggle('module-label',Boolean(item.module && !item.iconOnly));
          element.classList.toggle('dense-label',Boolean(item.module && !item.iconOnly && item.box.height < 52));
          element.classList.toggle('compact-label',!item.module && !node.expanded);
          element.dataset.kind = node.kind;
          element.dataset.boundary = look.boundary || "solid";
          element.dataset.shape = look.shape || "rectangle";
          element.dataset.coverage = look.coverage || "";
          element.dataset.error = String(Boolean(look.error));
          element.dataset.activity = look.activity || "";
          element.title = [look.label || node.name, look.description || look.detail].filter(Boolean).join('\n');
          if (node.kind === "region") {
            element.dataset.groupRegionLabel = node.id;
            if (node.group) element.dataset.regionGroup = node.group;
            else delete element.dataset.regionGroup;
          } else if (node.kind !== "end" && !node.preview) {
            element.dataset.nodeId = node.id;
            element.dataset.selectNode = node.id;
          }
          if (element.tagName === "BUTTON") element.setAttribute("aria-pressed", String(Boolean(look.selected)));
          element.setAttribute("aria-label", `${look.label || node.name}${look.evidence ? `. ${look.evidence}` : ''}${node.kind === "region" ? `, ${steps}. ${this.navigation.at(-1)?.id === node.id ? 'Click to inspect.' : node.group ? 'Click to enter group.' : node.summary === 'run' ? 'Click to follow this run.' : 'Click to enter area.'}` : ""}${look.error ? ", has errors" : ""}`);
          const parentName=this._groups?.at(-1)?.name;
          const title=look.label || node.name;
          element.children[0].textContent = parentName && title.startsWith(parentName+': ') ? title.slice(parentName.length+2) : title;
          element.children[1].textContent = !look.selected || item.measured ? '' : node.kind === "region" ? look.evidenceLabel || steps
            : node.kind === "trigger" ? `${node.example_count || 0} ${node.example_count === 1 ? "example" : "examples"}` : "";
          element.style.flexDirection = node.expanded ? "row" : "column";
          const symbol = element.children[2];
          let duration = element.querySelector('.world-duration');
          if (item.measured && !duration) { duration = document.createElement('span'); duration.className = 'world-duration'; element.append(duration); }
          if (duration) {
            duration.hidden = !item.measured;
            duration.textContent = item.measured ? `${look.duration}${node.kind === 'region' ? '' : ' avg'}` : '';
            duration.title = node.kind === 'region' ? 'Slowest sampled Step average in this group, not passage latency.'
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
        element.style.textAlign = item.module ? "left" : "center";
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
