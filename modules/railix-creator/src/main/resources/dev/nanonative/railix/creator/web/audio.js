"use strict";

/** Creator-only Web Audio player for validated, declarative score files. */
class RailixAudio {
  constructor(panel, onChange = () => {}) {
    this.panel = panel;
    this.onChange = onChange;
    this.listeners = new AbortController();
    this.scores = [];
    this.events = [];
    this.preferences = {effects:true, effects_volume:.35, music_volume:.25, music_enabled:true, music:'', sounds:{}};
    this.queue = [];
    this.catalogGeneration = 0;
    this.musicGeneration = 0;
    this.effectGeneration = 0;
    this.editorVersion = 0;
    this.nearby = 0;
    this.noiseBuffers = new WeakMap();
    this.dirtyEditor = false;
    const on = (target, type, action) => target.addEventListener(type, action, {signal:this.listeners.signal});
    on(panel, 'toggle', event => { if (event.newState === 'open') void this.catalog(); });
    on(panel.querySelector('#audio-effects'), 'change', event => {
      this.preferences.effects = event.target.checked;
      this.change({effects:event.target.checked});
      void this.effects(event.target.checked);
    });
    on(panel.querySelector('#effects-volume'), 'input', event => {
      this.preferences.effects_volume = Number(event.target.value);
      this.change({effects_volume:this.preferences.effects_volume});
      this.near(this.nearby);
    });
    on(panel.querySelector('#effects-volume'), 'change', event => this.change({effects_volume:Number(event.target.value)}));
    on(panel.querySelector('#music-volume'), 'input', event => {
      this.preferences.music_volume = Number(event.target.value);
      this.change({music_volume:this.preferences.music_volume});
      this.setMusicVolume();
    });
    on(panel.querySelector('#music-volume'), 'change', event => this.change({music_volume:Number(event.target.value)}));
    on(panel.querySelector('#music-group'), 'change', event => {
      if (event.target.value === this.preferences.music) return;
      this.preferences.music = event.target.value;
      this.change({music:event.target.value});
      this.queue = [];
      this.stop();
    });
    on(panel.querySelector('#effect-choices > div'), 'change', event => {
      const select = event.target;
      const id = select instanceof HTMLSelectElement ? select.dataset.eventId : '';
      if (!id) return;
      const definition = this.events.find(item => item.id === id);
      if (!definition) return;
      if (select.value === definition.default) delete this.preferences.sounds[id];
      else this.preferences.sounds[id] = select.value;
      this.change({sounds:{...this.preferences.sounds}});
    });
    on(panel.querySelector('#music-play'), 'click', () => this.musicSession ? this.musicPaused ? void this.resumeMusic() : void this.pause() : void this.enableMusic());
    on(panel.querySelector('#music-stop'), 'click', () => this.muteMusic());
    on(panel.querySelector('#music-next'), 'click', () => void this.skipTrack());
    on(panel.querySelector('#sound-file'), 'change', event => this.edit(event.target.value));
    on(panel.querySelector('#sound-score'), 'input', () => { this.dirtyEditor = true; this.editorVersion++; });
    on(panel.querySelector('#sound-id'), 'input', () => { this.dirtyEditor = true; this.editorVersion++; });
    on(panel.querySelector('#sound-instrument'), 'change', event => this.instrument(event.target.value));
    on(panel.querySelector('#sound-save'), 'click', () => void this.save());
    on(panel.querySelector('#sound-preview'), 'click', () => void this.preview());
    on(panel.querySelector('#sound-delete'), 'click', () => void this.remove());
    on(panel.querySelector('#sound-download'), 'click', () => void this.copy());
    on(panel.querySelector('#sound-defaults'), 'click', () => void this.mutate({action:'install-defaults'}));
    on(document, 'visibilitychange', () => { if (document.hidden) this.stopAll(); });
    on(window, 'pagehide', () => this.dispose());
  }

  status(message) { this.panel.querySelector('#audio-status').textContent = message; }
  effectsVolume() { return Number(this.panel.querySelector('#effects-volume').value) * .2; }
  musicVolume() { return Number(this.panel.querySelector('#music-volume').value) * .2; }
  change(values) { this.onChange({...values}); }

  applyPreferences(values = {}) {
    const previousMusic = this.preferences.music;
    const sounds = values.sounds && typeof values.sounds === 'object' && !Array.isArray(values.sounds) ? values.sounds : {};
    this.preferences = {
      effects:values.effects !== false,
      effects_volume:this.volume(values.effects_volume, .35),
      music_volume:this.volume(values.music_volume, .25),
      music_enabled:values.music_enabled !== false,
      music:typeof values.music === 'string' ? this.musicChoice(values.music) : '',
      sounds:Object.fromEntries(Object.entries(sounds)
        .filter(([, key]) => typeof key === 'string')
        .map(([event, key]) => [event, this.scoreKey(key)]))
    };
    this.panel.querySelector('#audio-effects').checked = this.preferences.effects;
    this.panel.querySelector('#effects-volume').value = this.preferences.effects_volume;
    this.panel.querySelector('#music-volume').value = this.preferences.music_volume;
    this.enabled = this.preferences.effects;
    if (!this.enabled) void this.stopEffects();
    this.near(this.nearby);
    this.setMusicVolume();
    this.groups();
    this.effectChoices();
    if (this.enabled && !this.scores.length && !this.request) void this.catalog();
    if (this.musicSession && previousMusic !== this.preferences.music) this.stop();
    if (!this.preferences.music_enabled) this.stop();
  }

  volume(value, fallback) {
    const number = Number(value);
    return Number.isFinite(number) && number >= 0 && number <= 1 ? number : fallback;
  }

  async catalog() {
    this.request?.abort();
    const request = this.request = new AbortController();
    const generation = ++this.catalogGeneration;
    const timeout = setTimeout(() => request.abort(), 5_000);
    try {
      const response = await fetch('/api/sounds', {cache:'no-store', signal:request.signal});
      if (!response.ok) throw Error('Sound library unavailable.');
      const listing = await response.json();
      if (this.disposed || request !== this.request || generation !== this.catalogGeneration) return;
      this.scores = Array.isArray(listing.scores) ? listing.scores : [];
      this.events = Array.isArray(listing.events) ? listing.events : [];
      this.invalid = Array.isArray(listing.invalid) ? listing.invalid : [];
      this.revision = listing.revision;
      const available = new Set(this.scores.filter(score => score.id.startsWith('music/')).map(score => score.key));
      this.queue = this.queue.filter(key => available.has(key));
      this.groups();
      this.effectChoices();
      this.instruments();
      if (!this.dirtyEditor) this.files();
      this.stopAmbient();
      this.near(this.nearby);
      const music = this.music();
      this.panel.querySelector('#music-play').disabled = !music.length;
      this.panel.querySelector('#music-next').disabled = !music.length;
      const unavailable = this.unavailableChoices();
      this.status(`Sound scores ready.${this.invalid.length ? ` ${this.invalid.length} invalid file(s); select one to repair it.` : ''}${unavailable ? ` ${unavailable} saved choice${unavailable === 1 ? ' is' : 's are'} unavailable; embedded fallback in use.` : ''}`);
    } catch (error) {
      if (!this.disposed && request === this.request) this.status(error.name === 'AbortError' ? 'Sound library request timed out.' : error.message);
    } finally { clearTimeout(timeout); }
  }

  groups() {
    const select = this.panel.querySelector('#music-group');
    const selected = this.preferences.music;
    const tracks = this.scores.filter(score => score.id.startsWith('music/') && (!score.builtin || score.id.endsWith('.mml')));
    const groups = [...new Set(tracks.map(score => score.group))].sort();
    const options = [new Option('All tracks', ''),
      ...groups.map(group => new Option(group || 'Ungrouped (music/)', `group:${group}`)),
      ...tracks.map(score => new Option(this.scoreLabel(score), `track:${score.key}`))];
    if (selected && !options.some(option => option.value === selected)) {
      options.push(new Option(`Unavailable: ${selected}`, selected));
    }
    select.replaceChildren(...options);
    select.value = selected;
  }

  effectChoices() {
    const container = this.panel.querySelector('#effect-choices > div');
    const effects = this.scores.filter(score => score.id.startsWith('sounds/'));
    this.events.forEach(event => {
      if (this.preferences.sounds[event.id] === event.default) delete this.preferences.sounds[event.id];
    });
    container.replaceChildren(...this.events.map(event => {
      const label = document.createElement('label');
      label.textContent = event.name || event.id;
      const select = document.createElement('select');
      select.dataset.eventId = event.id;
      const selected = this.preferences.sounds[event.id] || event.default;
      const options = effects.map(score => new Option(this.scoreLabel(score), score.key));
      if (!options.some(option => option.value === selected)) options.push(new Option(`Unavailable: ${selected}`, selected));
      select.replaceChildren(...options);
      select.value = selected;
      label.append(select);
      return label;
    }));
  }

  scoreLabel(score) { return `${score.builtin ? 'Built-in' : 'Local'}: ${score.score?.name || score.id}`; }

  unavailableChoices() {
    let count = this.events.filter(event => this.preferences.sounds[event.id]
      && !this.scores.some(score => score.key === this.preferences.sounds[event.id])).length;
    const music = this.preferences.music;
    if (music.startsWith('track:') && !this.scores.some(score => `track:${score.key}` === music)) count++;
    if (music.startsWith('group:') && !this.scores.some(score => score.id.startsWith('music/')
      && score.group === music.slice('group:'.length))) count++;
    return count;
  }

  files() {
    const select = this.panel.querySelector('#sound-file');
    const selected = this.fileToSelect || select.value;
    this.fileToSelect = null;
    const music = this.editorKind === 'music';
    const entries = [...this.scores, ...this.invalid.map(score => ({...score, key:`invalid:${score.id}`}))]
      .filter(score => score.id.startsWith('music/') === music);
    select.replaceChildren(...entries.map(score => new Option(
      `${score.builtin ? 'Built-in: ' : score.key.startsWith('invalid:') ? 'Invalid: ' : 'Local: '}${score.id}`, score.key
    )), new Option('New score', ''));
    this.edit(entries.some(score => score.key === selected) ? selected : entries[0]?.key || '');
  }

  edit(key) {
    this.editorVersion++;
    this.editorRevision = this.revision;
    this.panel.querySelector('#sound-file').value = key;
    const invalid = key.startsWith('invalid:') ? this.invalid?.find(item => item.id === key.slice('invalid:'.length)) : null;
    const entry = this.scores.find(item => item.key === key);
    const id = invalid?.id || entry?.id || '';
    const group = this.preferences.music.startsWith('group:') ? this.preferences.music.slice(6) : '';
    const newId = this.editorKind === 'music' ? `music/${group ? `${group}/` : ''}new.mml` : 'sounds/new.mml';
    this.panel.querySelector('#sound-id').value = id || newId;
    const score = entry?.score;
    this.panel.querySelector('#sound-score').value = invalid ? invalid.source : score?.content || this.newScore();
    this.dirtyEditor = false;
    this.panel.querySelector('#sound-delete').disabled = !id || entry?.builtin === true;
    if (invalid) this.status(invalid.message);
  }

  newScore() {
    return 'name: New sound\ntempo: 120\nsine .3 .01 .1 | t120 o4 l8 c e g e';
  }

  scoreKey(key) {
    const delimiter = key.indexOf(':');
    if (delimiter < 1) return key;
    const scope = key.slice(0, delimiter + 1), source = key.slice(delimiter + 1);
    if (scope !== 'builtin:' && scope !== 'local:') return key;
    const path = source.startsWith('music/') ? source : source.startsWith('sounds/') ? source : `sounds/${source}`;
    return `${scope}${path.endsWith('.json') ? `${path.slice(0, -5)}.mml` : path}`;
  }

  musicChoice(value) {
    return value.startsWith('track:') ? `track:${this.scoreKey(value.slice(6))}` : value;
  }

  instrument(preset) {
    if (!preset) return;
    const source = this.panel.querySelector('#sound-score');
    source.value = source.value.replace(/^\w+\s+[^|\n]+\|/m, `${preset} |`);
    this.dirtyEditor = true;
    this.editorVersion++;
  }

  instruments() {
    const select = this.panel.querySelector('#sound-instrument');
    const presets = new Map();
    this.scores.forEach(score => score.score?.tracks?.forEach(track => {
      const options = Object.entries(track).filter(([key]) => !['waveform','volume','attack','release','notes'].includes(key))
        .map(([key,value]) => ` ${key}=${value}`).join('');
      const value = `${track.waveform} ${track.volume} ${track.attack} ${track.release}${options}`;
      presets.set(value, `${track.waveform} (${track.volume}/${track.attack}/${track.release})${options}`);
    }));
    select.replaceChildren(new Option('Keep instrument line', ''), ...[...presets.entries()]
      .map(([value, label]) => new Option(label, value)));
  }

  setEditorKind(kind) {
    this.editorKind = kind;
    const host = this.panel.querySelector(`#settings-${kind}`);
    if (host) host.append(this.panel.querySelector('#sound-files'));
    if (!this.dirtyEditor) this.files();
  }

  async mutate(payload) {
    if (this.mutation || this.disposed) return;
    const mutation = this.mutation = new AbortController();
    const version = this.editorVersion;
    const controls = [...this.panel.querySelectorAll('#sound-preview, #sound-save, #sound-delete, #sound-download, #sound-defaults')];
    controls.forEach(button => { button.disabled = true; });
    const timeout = setTimeout(() => mutation.abort(), 5_000);
    try {
      const response = await fetch('/api/sounds', {
        method:'POST',
        headers:{'Content-Type':'application/json', 'X-Railix-Creator-Token':new URLSearchParams(location.hash.slice(1)).get('token') || ''},
        body:JSON.stringify({...payload, revision:['save', 'delete'].includes(payload.action) ? this.editorRevision : this.revision}),
        signal:mutation.signal
      });
      const result = await response.json();
      if (this.disposed) return;
      if (!response.ok) throw Error(result.message || 'Sound change rejected.');
      if (version === this.editorVersion && payload.action !== 'install-defaults') {
        this.dirtyEditor = false;
        this.fileToSelect = payload.action === 'save' ? `local:${payload.id}` : null;
      }
      this.status(result.status.replaceAll('-', ' ') + '.');
      await this.catalog();
      return result;
    } catch (error) {
      if (!this.disposed && mutation === this.mutation) this.status(error.name === 'AbortError' ? 'Sound change timed out.' : error.message);
    } finally {
      clearTimeout(timeout);
      this.mutation = null;
      controls.forEach(button => { button.disabled = false; });
      const selected = this.scores.find(score => score.key === this.panel.querySelector('#sound-file').value);
      this.panel.querySelector('#sound-delete').disabled = !this.panel.querySelector('#sound-file').value || selected?.builtin === true;
    }
  }

  async save() {
    return this.mutate({
        action:'save', id:this.panel.querySelector('#sound-id').value,
        content:this.panel.querySelector('#sound-score').value
    });
  }

  async preview() {
    this.stopPreview();
    const generation = ++this.previewGeneration;
    const controller = this.previewRequest = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 5000);
    let context = null;
    try {
      const response = await fetch('/api/sounds', {method:'POST', headers:{'Content-Type':'application/json', 'X-Railix-Creator-Token':new URLSearchParams(location.hash.slice(1)).get('token') || ''},
        body:JSON.stringify({action:'preview', content:this.panel.querySelector('#sound-score').value}), signal:controller.signal});
      const result = await response.json();
      if (!response.ok) throw Error(result.message || 'MML preview was rejected.');
      if (this.disposed || generation !== this.previewGeneration || controller !== this.previewRequest) return;
      context = new AudioContext();
      await context.resume();
      if (this.disposed || generation !== this.previewGeneration || controller !== this.previewRequest) { await context.close(); return; }
      this.previewContext = context;
      this.previewSession = this.schedule(context, result.score, context.destination, .5, () => this.stopPreview());
      this.status(result.score.name);
    } catch (error) {
      if (context) await context.close();
      if (generation === this.previewGeneration) this.status(error.name === 'AbortError' ? 'MML preview cancelled.' : error.message);
    } finally { clearTimeout(timeout); }
  }

  stopPreview() {
    this.previewGeneration = (this.previewGeneration || 0) + 1;
    this.previewRequest?.abort();
    this.previewRequest = null;
    this.stopSession(this.previewSession);
    this.previewSession = null;
    if (this.previewContext) void this.previewContext.close();
    this.previewContext = null;
  }

  async remove() {
    const selected = this.panel.querySelector('#sound-file').value;
    const id = selected.startsWith('invalid:') ? selected.slice('invalid:'.length) : this.scores.find(score => score.key === selected)?.id;
    if (id && !this.scores.find(score => score.key === selected)?.builtin) await this.mutate({action:'delete', id});
  }

  async copy() {
    const id = this.panel.querySelector('#sound-id').value;
    if (await this.mutate({action:'copy', id, content:this.panel.querySelector('#sound-score').value})) {
      this.status(`Installed ${id} without replacing an existing file.`);
    }
  }

  music() {
    const group = this.panel.querySelector('#music-group').value;
    const tracks = this.scores.filter(score => score.id.startsWith('music/') && (!score.builtin || score.id.endsWith('.mml')));
    const selected = tracks.filter(score => !group
      || group.startsWith('group:') && score.group === group.slice(6) || group === `track:${score.key}`);
    return selected.length ? selected : tracks.filter(score => score.builtin);
  }

  async effects(enabled) {
    this.enabled = enabled;
    if (!enabled) return this.stopEffects();
    const generation = this.effectGeneration;
    try {
      await this.effectContext();
      if (!this.scores.length) await this.catalog();
      if (generation !== this.effectGeneration || this.disposed) return;
      this.near(this.nearby);
      this.action('click');
    } catch (_) {
      if (generation !== this.effectGeneration || this.disposed) return;
      this.panel.querySelector('#audio-effects').checked = false;
      this.enabled = false;
      this.status('Audio is unavailable in this browser.');
    }
  }

  async effectContext() {
    const generation = this.effectGeneration;
    const context = this.context ||= new AudioContext();
    await context.resume();
    if (this.disposed || !this.enabled || generation !== this.effectGeneration || context !== this.context) throw Error('Effects were stopped.');
    return context;
  }

  cue(look, group = false) {
    const state = look.error ? 'error' : look.warning ? 'warning'
      : look.rate > 0 || look.coverage === 'selected' ? 'working'
      : look.coverage === 'uncovered' || look.never ? 'uncovered' : 'idle';
    this.action(group && this.findScore(`group-${state}`) ? `group-${state}` : state);
  }

  action(name) {
    const score = this.findScore(name);
    if (!score || !this.enabled || document.hidden || !this.effectsVolume()) return;
    const generation = this.effectGeneration;
    void this.effectContext().then(context => {
      if (generation !== this.effectGeneration) return;
      this.stopSession(this.effectSession);
      this.effectSession = this.schedule(context, score.score, context.destination, this.effectsVolume(), null);
    }).catch(() => {});
  }

  findScore(name) {
    const event = this.events.find(item => item.id === name);
    const requested = event && (this.preferences.sounds[name] || event.default);
    return this.scores.find(score => score.key === requested) || this.scores.find(score => score.key === event?.default);
  }

  near(amount) {
    clearTimeout(this.ambientStop);
    this.nearby = Math.max(0, Math.min(1, Number(amount) || 0));
    if (!this.enabled || document.hidden || !this.context) return;
    const target = this.nearby * this.effectsVolume() * .08;
    const score = this.findScore('ambient');
    if (!this.ambient && target > 0 && score) {
      const gain = this.context.createGain();
      gain.gain.value = 0;
      gain.connect(this.context.destination);
      this.ambient = {gain, session:this.schedule(this.context, score.score, gain, 1, null, true)};
    }
    if (!this.ambient) return;
    const time = this.context.currentTime;
    this.ambient.gain.gain.cancelScheduledValues(time);
    this.ambient.gain.gain.setTargetAtTime(target, time, .15);
    if (target === 0) this.ambientStop = setTimeout(() => {
      if (!this.nearby || !this.effectsVolume()) this.stopAmbient();
    }, 500);
  }

  stopAmbient() {
    if (!this.ambient) return;
    this.stopSession(this.ambient.session);
    this.ambient.gain.disconnect();
    this.ambient = null;
  }

  async play() {
    const generation = ++this.musicGeneration;
    if (!this.preferences.music_enabled) return;
    if (!this.scores.length) await this.catalog();
    const tracks = this.music();
    if (generation !== this.musicGeneration || !this.preferences.music_enabled || this.disposed || document.hidden || !tracks.length) return;
    this.releaseMusic();
    if (!this.queue.length) {
      this.queue = tracks.map(track => track.key);
      for (let index = this.queue.length - 1; index > 0; index--) {
        const other = Math.floor(Math.random() * (index + 1));
        [this.queue[index], this.queue[other]] = [this.queue[other], this.queue[index]];
      }
      if (this.queue.length > 1 && this.queue.at(-1) === this.lastTrack?.key) this.queue.unshift(this.queue.pop());
    }
    const id = this.queue.pop();
    this.track = this.scores.find(score => score.key === id);
    try {
      const context = this.musicContext = new AudioContext();
      await context.resume();
      if (this.disposed || generation !== this.musicGeneration || context !== this.musicContext) { await context.close(); return; }
      this.musicGain = context.createGain();
      this.musicGain.gain.value = this.musicVolume();
      this.musicGain.connect(context.destination);
      this.musicSession = this.schedule(context, this.track.score, this.musicGain, 1, () => void this.nextTrack());
      this.musicPaused = false;
      this.button('Pause', 'pause');
      this.status(this.track.score.name);
    } catch (error) {
      if (generation === this.musicGeneration && !this.disposed) {
        this.stop();
        this.status(`Music could not start: ${error.message}`);
      }
    }
  }

  async toggleMusic() {
    const music_enabled = !this.preferences.music_enabled;
    this.preferences.music_enabled = music_enabled;
    this.change({music_enabled});
    if (!music_enabled) return this.stop();
    await this.catalog();
    return this.play();
  }

  async enableMusic() {
    if (!this.preferences.music_enabled) {
      this.preferences.music_enabled = true;
      this.change({music_enabled:true});
    }
    return this.play();
  }

  muteMusic() {
    if (this.preferences.music_enabled) {
      this.preferences.music_enabled = false;
      this.change({music_enabled:false});
    }
    this.stop();
  }

  schedule(context, score, output, scale, complete, loop = false) {
    const tracks = score.tracks.map(track => this.createVoice(context, track, output, this.voiceCount(track.notes)));
    // Normalize the actual bounded chord width before output.
    const headroom = 1 / Math.max(1, score.tracks.reduce((sum, track) => sum + track.volume * this.voiceCount(track.notes), 0));
    const duration = Math.max(...score.tracks.map(track => this.trackDuration(track.notes))) * 60 / score.tempo;
    const session = {context, voices:tracks.flatMap(track => track.voices), timer:0};
    const cycle = start => {
      if (session.stopped) return;
      score.tracks.forEach((track, index) => this.scheduleTrack(tracks[index], track, score.tempo, start, scale * headroom));
      session.end = start + duration;
      session.timer = setTimeout(() => loop ? cycle(Math.max(session.end, context.currentTime))
        : complete ? complete() : this.stopSession(session),
      Math.max(10, (session.end - context.currentTime + (loop ? -.04 : .05)) * 1000));
    };
    cycle(context.currentTime + .04);
    return session;
  }

  createVoice(context, track, output, count) {
    return {voices:Array.from({length:count}, () => {
      const noise = track.waveform === 'snare' || track.waveform === 'hat';
      const voice = noise ? context.createBufferSource() : context.createOscillator(), gain = context.createGain();
      let filter;
      if (noise) {
        let buffer = this.noiseBuffers.get(context);
        if (!buffer) {
          buffer = context.createBuffer(1, context.sampleRate, context.sampleRate);
          const samples = buffer.getChannelData(0);
          let seed = 761;
          for (let i=0;i<samples.length;i++) { seed = (Math.imul(seed,1664525)+1013904223)>>>0; samples[i]=seed/2147483648-1; }
          this.noiseBuffers.set(context,buffer);
        }
        voice.buffer = buffer;
        voice.loop = true;
        filter = context.createBiquadFilter();
        filter.type = track.waveform === 'hat' ? 'highpass' : 'bandpass';
        filter.frequency.value = track.waveform === 'hat' ? 7000 : 1800;
        filter.Q.value = .7;
      } else {
        voice.type = track.waveform === 'kick' ? 'sine' : track.waveform;
      }
      if (track.cutoff !== undefined) {
        filter ||= context.createBiquadFilter();
        if (!noise) filter.type = 'lowpass';
        filter.frequency.value = Math.min(track.cutoff, context.sampleRate / 2);
      }
      if (filter) voice.connect(filter).connect(gain);
      else voice.connect(gain);
      gain.gain.value = 0;
      gain.connect(output);
      voice.start();
      return {voice, gain, filter};
    })};
  }

  voiceCount(notes) {
    return Math.max(1, ...notes.map(note => note.repeat ? this.voiceCount(note.notes) : 1 + (note.chord?.length || 0)));
  }

  scheduleTrack(target, track, tempo, start, scale) {
    const beat = 60 / tempo;
    const schedule = (notes, time) => {
      for (const note of notes) {
        if (note.repeat) {
          for (let count = 0; count < note.repeat; count++) time = schedule(note.notes, time);
          continue;
        }
      const duration = note.duration * beat;
      const fadeIn = Math.min(track.attack, duration / 2);
      const fadeOut = Math.min(track.release, duration / 2);
      const primary = target.voices[0];
      primary.gain.gain.setValueAtTime(0, time);
      if (note.note !== null) {
        this.scheduleVoice(primary, track, note.note, time, duration, fadeIn, fadeOut, scale);
        (note.chord || []).forEach((chord, index) => this.scheduleVoice(target.voices[index + 1], track, chord, time, duration, fadeIn, fadeOut, scale));
      }
      primary.gain.gain.linearRampToValueAtTime(0, time + duration);
      time += duration;
      }
      return time;
    };
    schedule(track.notes, start);
  }

  trackDuration(notes) {
    return notes.reduce((total, note) => total + (note.repeat ? Number(note.repeat) * this.trackDuration(note.notes) : note.duration), 0);
  }

  scheduleVoice(target, track, note, time, duration, fadeIn, fadeOut, scale) {
    const frequency = 440 * 2 ** ((note - 69) / 12);
    if (target.voice.frequency) target.voice.frequency.setValueAtTime(frequency, time);
    if (['kick','snare','hat'].includes(track.waveform)) {
      // A hit decays independently of the notated space until the next hit.
      const decay = Math.min(duration*.8, Math.max(.015,track.release));
      const attack = Math.min(fadeIn,.005,decay/2), level = track.volume*scale;
      if (level <= 0) return;
      if (track.waveform === 'kick') {
        target.voice.frequency.setValueAtTime(frequency*3,time);
        target.voice.frequency.exponentialRampToValueAtTime(Math.max(24,frequency*.7),time+decay);
      }
      target.gain.gain.setValueAtTime(0,time);
      target.gain.gain.linearRampToValueAtTime(level,time+attack);
      target.gain.gain.exponentialRampToValueAtTime(level*.00001,time+decay);
      target.gain.gain.setValueAtTime(0,time+Math.min(duration,decay+.001));
      return;
    }
    const level = track.volume * scale, sustain = track.sustain ?? 1;
    const decay = Math.min(track.decay ?? 0, Math.max(0, duration - fadeIn - fadeOut));
    target.gain.gain.setValueAtTime(0, time);
    target.gain.gain.linearRampToValueAtTime(level, time + fadeIn);
    if (decay > 0 && level > 0) target.gain.gain.exponentialRampToValueAtTime(level * Math.max(.00001, sustain), time + fadeIn + decay);
    else if (sustain !== 1) target.gain.gain.setValueAtTime(level * sustain, time + fadeIn);
    target.gain.gain.setValueAtTime(level * sustain, time + duration - fadeOut);
    target.gain.gain.linearRampToValueAtTime(0, time + duration);
  }

  setMusicVolume() {
    if (this.musicGain && this.musicContext) this.musicGain.gain.setTargetAtTime(this.musicVolume(), this.musicContext.currentTime, .05);
  }

  async resumeMusic() {
    if (!this.musicContext || !this.musicSession) return;
    const context = this.musicContext;
    if (!this.preferences.music_enabled) {
      this.preferences.music_enabled = true;
      this.change({music_enabled:true});
    }
    this.musicPaused = false;
    try {
      await context.resume();
      if (this.disposed || context !== this.musicContext || this.musicPaused || !this.musicSession) return;
      this.musicSession.timer = setTimeout(
        () => void this.nextTrack(),
        Math.max(50, (this.musicSession.end - this.musicContext.currentTime + .05) * 1000)
      );
      this.button('Pause', 'pause');
    } catch (_) { if (context === this.musicContext) { this.stop(); this.status('Music is unavailable in this browser.'); } }
  }

  async nextTrack() {
    if (!this.musicSession || this.musicPaused) return;
    await this.skipTrack();
  }

  async skipTrack() {
    this.lastTrack = this.track;
    this.releaseMusic();
    await this.play();
  }

  button(label, symbol) {
    this.panel.querySelector('#music-play span').textContent = label;
    this.panel.querySelector('#music-play .hud-symbol').dataset.symbol = symbol;
  }

  async pause() {
    if (!this.musicContext || !this.musicSession) return;
    const context = this.musicContext;
    clearTimeout(this.musicSession.timer);
    if (this.preferences.music_enabled) {
      this.preferences.music_enabled = false;
      this.change({music_enabled:false});
    }
    this.musicPaused = true;
    this.button('Play', 'play');
    try { await context.suspend(); }
    catch (_) { if (context === this.musicContext) { this.stop(); this.status('Music is unavailable in this browser.'); } }
  }

  stop() {
    this.musicGeneration++;
    this.lastTrack = this.track;
    this.releaseMusic();
    this.track = null;
    this.button('Play', 'play');
  }

  releaseMusic() {
    this.stopSession(this.musicSession);
    this.musicSession = null;
    this.musicPaused = false;
    if (this.musicContext) void this.musicContext.close();
    this.musicContext = this.musicGain = null;
  }

  stopSession(session) {
    if (!session || session.stopped) return;
    session.stopped = true;
    clearTimeout(session.timer);
    session.voices.forEach(voice => this.stopVoice(voice));
  }

  stopVoice(voice) {
    if (!voice) return;
    voice.voice.stop();
    voice.voice.disconnect();
    voice.filter?.disconnect();
    voice.gain.disconnect();
  }

  async stopEffects() {
    this.effectGeneration++;
    clearTimeout(this.ambientStop);
    this.stopSession(this.effectSession);
    this.effectSession = null;
    this.stopAmbient();
    if (this.context) {
      const context = this.context;
      this.context = null;
      await context.close();
    }
  }

  stopAll() { this.stop(); this.stopPreview(); void this.stopEffects(); }

  dispose() {
    if (this.disposed) return;
    this.disposed = true;
    this.request?.abort();
    this.mutation?.abort();
    this.listeners.abort();
    this.stopAll();
    this.scores = this.queue = [];
  }
}
