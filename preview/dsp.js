/* Ceecept Web Audio engine — port of the Android DSP chain:
   preamp -> 16-band EQ -> 3-band dynamics -> limiter -> Immerse 3D (HRTF). */

export const EQ_FREQS = [31, 62, 125, 250, 400, 630, 1000, 1600, 2500, 4000, 6300, 8000, 10000, 12500, 14000, 16000];
const Z = () => new Array(16).fill(0);

export const EQ_PRESETS = {
  'Flat': Z(),
  'Bass Boost': [7, 6, 5, 3.5, 2, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
  'Deep Sub': [9, 7, 4, 1.5, 0, 0, 0, 0, 0, 0, -1, -1, -1, -1, -1, -1],
  'Treble Boost': [-1, -1, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 6.5, 7],
  'Vocal': [-2, -1.5, -1, 0, 1, 2, 3, 3.5, 3, 2, 1, 0, 0, -1, -1, -1],
  'Acoustic': [2, 1.5, 1, 0, 0, 1, 2, 2, 2, 1.5, 1, 1.5, 2, 2, 2, 2],
  'Electronic': [5, 4, 2.5, 1, 0, -1, 0, 1, 2, 2.5, 3, 3.5, 4, 4, 4.5, 5],
  'Hip-Hop': [6, 5, 3, 1.5, 0, -1, -1, 0, 1, 1.5, 2, 3, 3.5, 4, 4, 4],
  'Rock': [4, 3, 2, 1, 0, -1, -1.5, -1, 0, 1, 2, 3, 3.5, 4, 4, 4.5],
  'Jazz': [3, 2.5, 1.5, 1, 0, 0, 1, 1.5, 2, 2, 1.5, 1, 1.5, 2, 2.5, 3],
  'Classical': [3, 2, 1, 0, 0, 0, -1, -1, -1, 0, 0, 1, 2, 3, 4, 4.5],
  'Pop': [2, 3, 3.5, 2, 0, -1, -1.5, -1, 0, 1, 2, 3, 3, 2.5, 2, 2],
  'Lo-Fi': [3, 2, 1, 0, 0, -1, -1, -2, -2, -3, -4, -5, -6, -7, -8, -9],
  'Podcast': [-6, -5, -3, -1, 1, 2.5, 3.5, 4, 3.5, 2.5, 1, 0, -1, -2, -3, -4],
};

const band = (o = {}) => Object.assign({ gateOn: false, gateDb: -60, thr: -18, ratio: 3, atk: 0.01, rel: 0.12, makeup: 0 }, o);
export const DYN_PRESETS = {
  'Transparent': { xLow: 250, xHigh: 4000, bands: [band({ thr: -14, ratio: 2, atk: 0.012, rel: 0.16, makeup: 1.5 }), band({ thr: -14, ratio: 2, atk: 0.008, rel: 0.14, makeup: 1.5 }), band({ thr: -16, ratio: 2, atk: 0.005, rel: 0.12, makeup: 1 })] },
  'Punchy': { xLow: 180, xHigh: 3600, bands: [band({ thr: -20, ratio: 4, atk: 0.006, rel: 0.11, makeup: 3 }), band({ thr: -16, ratio: 3, atk: 0.008, rel: 0.12, makeup: 2 }), band({ thr: -18, ratio: 3, atk: 0.003, rel: 0.09, makeup: 2 })] },
  'Glue': { xLow: 250, xHigh: 4000, bands: [band({ thr: -10, ratio: 1.7, atk: 0.02, rel: 0.24, makeup: 1 }), band({ thr: -10, ratio: 1.7, atk: 0.015, rel: 0.22, makeup: 1 }), band({ thr: -12, ratio: 1.7, atk: 0.01, rel: 0.2, makeup: 1 })] },
  'Podcast': { xLow: 300, xHigh: 5000, bands: [band({ gateOn: true, gateDb: -55, thr: -24, ratio: 5, atk: 0.005, rel: 0.15, makeup: 5 }), band({ gateOn: true, gateDb: -55, thr: -22, ratio: 4, atk: 0.004, rel: 0.14, makeup: 4 }), band({ gateOn: true, gateDb: -58, thr: -26, ratio: 4, atk: 0.002, rel: 0.12, makeup: 3 })] },
};

export const SPACE_PRESETS = {
  'Off': { enabled: false, strength: 0.8, az: 0, el: 12, dist: 1.6, width: 1, room: 0.55, rev: 0.35, damp: 0.5 },
  'Natural': { enabled: true, strength: 0.65, az: 0, el: 12, dist: 1.6, width: 0.9, room: 0.4, rev: 0.22, damp: 0.45 },
  'Wide Stage': { enabled: true, strength: 0.8, az: 0, el: 12, dist: 1.6, width: 1.35, room: 0.5, rev: 0.28, damp: 0.5 },
  'Concert Hall': { enabled: true, strength: 0.9, az: 0, el: 18, dist: 2.6, width: 1.1, room: 0.85, rev: 0.55, damp: 0.35 },
  'Club': { enabled: true, strength: 0.85, az: 0, el: 12, dist: 1.2, width: 1.2, room: 0.65, rev: 0.4, damp: 0.6 },
  'Cinema': { enabled: true, strength: 1, az: 0, el: 8, dist: 2.2, width: 1.25, room: 0.75, rev: 0.45, damp: 0.45 },
  'Intimate': { enabled: true, strength: 0.55, az: 0, el: 12, dist: 0.8, width: 0.7, room: 0.3, rev: 0.15, damp: 0.55 },
};

const db2lin = (db) => Math.pow(10, db / 20);

export class CeeceptDSP {
  constructor() {
    this.ready = false;
    this.p = {
      eqOn: true, eq: [...Z()], preamp: 0,
      dynOn: true, xLow: 250, xHigh: 4000,
      bands: JSON.parse(JSON.stringify(DYN_PRESETS['Transparent'].bands)),
      limOn: true, limCeil: -1, limRel: 80, dynOut: 0,
      space: { ...SPACE_PRESETS['Wide Stage'] },
    };
    this.irTimer = null;
    try {
      const saved = JSON.parse(localStorage.getItem('ceecept-dsp') || 'null');
      if (saved) this.p = Object.assign(this.p, saved);
    } catch { /* fresh start */ }
  }
  save() { try { localStorage.setItem('ceecept-dsp', JSON.stringify(this.p)); } catch { /* noop */ } }

  async ensure(audioEl) {
    if (this.ready) { if (this.ctx.state === 'suspended') await this.ctx.resume(); return; }
    const Ctx = window.AudioContext || window.webkitAudioContext;
    this.ctx = new Ctx({ latencyHint: 'playback' });
    const ctx = this.ctx, p = this.p;

    this.src = ctx.createMediaElementSource(audioEl);
    this.preamp = ctx.createGain();

    // 16 peaking biquads.
    this.eqNodes = EQ_FREQS.map((f) => {
      const b = ctx.createBiquadFilter();
      b.type = 'peaking'; b.frequency.value = f; b.Q.value = 1.05; b.gain.value = 0;
      return b;
    });

    // Dynamics crossover (12 dB/oct LR-ish pairs).
    this.lp1 = this._lp(p.xLow); this.hp1a = this._hp(p.xLow);
    this.lp2 = this._lp(p.xHigh); this.hp2 = this._hp(p.xHigh);
    this.bandChain = [0, 1, 2].map(() => ({
      shaper: ctx.createWaveShaper(),
      comp: ctx.createDynamicsCompressor(),
      makeup: ctx.createGain(),
    }));
    this.dynSum = ctx.createGain();

    // Limiter worklet.
    await ctx.audioWorklet.addModule('limiter-worklet.js');
    this.limiter = new AudioWorkletNode(ctx, 'ceecept-limiter', { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [2] });

    this.outGain = ctx.createGain();

    // ---- Immerse 3D ----
    this.dry = ctx.createGain();
    this.wet = ctx.createGain();
    this.airLP = ctx.createBiquadFilter(); this.airLP.type = 'lowpass'; this.airLP.frequency.value = 16000;
    this.split = ctx.createChannelSplitter(2);
    this.panL = ctx.createPanner(); this.panR = ctx.createPanner();
    for (const pn of [this.panL, this.panR]) {
      pn.panningModel = 'HRTF'; pn.distanceModel = 'inverse';
      pn.refDistance = 1; pn.rolloffFactor = 1;
    }
    this.chGL = ctx.createGain(); this.chGR = ctx.createGain();
    this.conv = ctx.createConvolver();
    this.revGain = ctx.createGain();
    this.master = ctx.createGain();
    this.analyser = ctx.createAnalyser();
    this.analyser.fftSize = 256; this.analyser.smoothingTimeConstant = 0.75;

    // Wire it up.
    let head = this.src;
    head.connect(this.preamp); head = this.preamp;
    for (const b of this.eqNodes) { head.connect(b); head = b; }
    // crossover
    head.connect(this.lp1); head.connect(this.hp1a); head.connect(this.hp2);
    this.hp1a.connect(this.lp2);
    const feeds = [this.lp1, this.lp2, this.hp2];
    this.bandChain.forEach((ch, i) => {
      feeds[i].connect(ch.shaper); ch.shaper.connect(ch.comp); ch.comp.connect(ch.makeup); ch.makeup.connect(this.dynSum);
    });
    // dynamics bypass path (direct) mixed by dynOn
    this.dynDirect = ctx.createGain(); this.dynWet = ctx.createGain();
    head.connect(this.dynDirect); this.dynSum.connect(this.dynWet);
    this.dynDirect.connect(this.limiter); this.dynWet.connect(this.limiter);
    this.limiter.connect(this.outGain);

    // space
    this.outGain.connect(this.dry); this.dry.connect(this.master);
    this.outGain.connect(this.split);
    this.split.connect(this.chGL, 0); this.split.connect(this.chGR, 1);
    this.chGL.connect(this.panL); this.chGR.connect(this.panR);
    this.panL.connect(this.airLP); this.panR.connect(this.airLP);
    this.airLP.connect(this.wet); this.wet.connect(this.master);
    this.outGain.connect(this.conv); this.conv.connect(this.revGain); this.revGain.connect(this.master);

    this.master.connect(this.analyser); this.analyser.connect(ctx.destination);

    this.applyAll();
    this.ready = true;
  }

  _lp(f) { const n = this.ctx.createBiquadFilter(); n.type = 'lowpass'; n.frequency.value = f; n.Q.value = 0.7071; return n; }
  _hp(f) { const n = this.ctx.createBiquadFilter(); n.type = 'highpass'; n.frequency.value = f; n.Q.value = 0.7071; return n; }

  applyAll() {
    if (!this.ready) return;
    const p = this.p, t = this.ctx.currentTime;
    this.preamp.gain.setTargetAtTime(db2lin(p.eqOn ? p.preamp : 0), t, 0.02);
    this.eqNodes.forEach((b, i) => b.gain.setTargetAtTime(p.eqOn ? p.eq[i] : 0, t, 0.02));
    // Dynamics
    this.lp1.frequency.setTargetAtTime(p.xLow, t, 0.02);
    this.hp1a.frequency.setTargetAtTime(p.xLow, t, 0.02);
    this.lp2.frequency.setTargetAtTime(p.xHigh, t, 0.02);
    this.hp2.frequency.setTargetAtTime(p.xHigh, t, 0.02);
    this.bandChain.forEach((ch, i) => {
      const b = p.bands[i];
      ch.comp.threshold.setTargetAtTime(b.thr, t, 0.02);
      ch.comp.ratio.setTargetAtTime(p.dynOn ? b.ratio : 1, t, 0.02);
      ch.comp.attack.setTargetAtTime(b.atk, t, 0.02);
      ch.comp.release.setTargetAtTime(b.rel, t, 0.02);
      ch.comp.knee.setTargetAtTime(6, t, 0.02);
      ch.makeup.gain.setTargetAtTime(db2lin(p.dynOn ? b.makeup : 0), t, 0.02);
      ch.shaper.curve = (p.dynOn && b.gateOn) ? gateCurve(b.gateDb) : null;
    });
    this.dynWet.gain.setTargetAtTime(p.dynOn ? 1 : 0, t, 0.03);
    this.dynDirect.gain.setTargetAtTime(p.dynOn ? 0 : 1, t, 0.03);
    // Limiter + out
    this.limiter.port.postMessage({ enabled: p.dynOn && p.limOn, ceilingDb: p.limCeil, releaseMs: p.limRel });
    this.outGain.gain.setTargetAtTime(db2lin(p.dynOn ? p.dynOut : 0), t, 0.02);
    this.applySpace();
  }

  applySpace() {
    if (!this.ready) return;
    const s = this.p.space, t = this.ctx.currentTime, on = s.enabled;
    const spread = 30 * s.width;
    this._place(this.panL, s.az - spread, s.el, s.dist, t);
    this._place(this.panR, s.az + spread, s.el, s.dist, t);
    const str = on ? s.strength : 0;
    const dryG = Math.cos(str * Math.PI / 2 * 0.85) * (1.4 / (0.6 + 0.5 * s.dist));
    this.dry.gain.setTargetAtTime(on ? dryG : 1, t, 0.05);
    this.wet.gain.setTargetAtTime(Math.sin(str * Math.PI / 2) * 1.15, t, 0.05);
    const distN = Math.min(1, Math.max(0, (s.dist - 0.5) / 3.5));
    this.airLP.frequency.setTargetAtTime(19000 - distN * 14500, t, 0.05);
    this.revGain.gain.setTargetAtTime(on ? (s.rev * 0.9 + 0.02) * (0.4 + str * 0.8) : 0, t, 0.08);
    // Rebuild impulse when room/damping change (debounced).
    clearTimeout(this.irTimer);
    this.irTimer = setTimeout(() => this.buildIR(), 250);
  }

  _place(pan, azDeg, elDeg, dist, t) {
    const az = azDeg * Math.PI / 180, el = elDeg * Math.PI / 180;
    pan.positionX.setTargetAtTime(Math.sin(az) * Math.cos(el) * dist, t, 0.05);
    pan.positionY.setTargetAtTime(Math.sin(el) * dist, t, 0.05);
    pan.positionZ.setTargetAtTime(-Math.cos(az) * Math.cos(el) * dist, t, 0.05);
  }

  buildIR() {
    if (!this.ready) return;
    const s = this.p.space, sr = this.ctx.sampleRate;
    const t60 = 0.35 + s.room * 3.85;
    const len = Math.floor(Math.min(4.5, t60 * 1.1) * sr);
    const ir = this.ctx.createBuffer(2, len, sr);
    const dampLP = 1 - Math.min(0.92, 0.25 + s.damp * 0.67);
    for (let ch = 0; ch < 2; ch++) {
      const d = ir.getChannelData(ch);
      let lp = 0;
      for (let i = 0; i < len; i++) {
        const t = i / sr;
        const decay = Math.pow(10, (-3 * t) / t60) * (i < 64 ? i / 64 : 1);
        const n = (Math.random() * 2 - 1) * decay;
        lp += dampLP * (n - lp);
        d[i] = lp * 0.5;
      }
    }
    this.conv.buffer = ir;
  }

  // Combined EQ magnitude response (dB) at log-spaced freqs — for the graph.
  eqResponse(steps = 90) {
    if (!this.ready) return new Array(steps + 1).fill(0);
    const freqs = new Float32Array(steps + 1);
    for (let i = 0; i <= steps; i++) freqs[i] = 20 * Math.pow(1000, i / steps);
    const mag = new Float32Array(steps + 1), phase = new Float32Array(steps + 1);
    const total = new Array(steps + 1).fill(0);
    for (const b of this.eqNodes) {
      b.getFrequencyResponse(freqs, mag, phase);
      for (let i = 0; i <= steps; i++) total[i] += 20 * Math.log10(Math.max(mag[i], 1e-6));
    }
    return total;
  }

  bandReduction() {
    if (!this.ready) return [0, 0, 0];
    return this.bandChain.map((ch) => ch.comp.reduction || 0);
  }
}

function gateCurve(gateDb) {
  const N = 1024, curve = new Float32Array(N), thr = db2lin(gateDb);
  for (let i = 0; i < N; i++) {
    const x = i / (N - 1) * 2 - 1, a = Math.abs(x);
    let y;
    if (a < thr) y = thr > 1e-6 ? (a * a) / thr : 0; // 1:2 downward expansion
    else y = a;
    curve[i] = Math.sign(x) * Math.min(y, 1);
  }
  return curve;
}
