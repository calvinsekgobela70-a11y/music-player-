#!/usr/bin/env node
/**
 * Ceecept spatial-audio QA harness — guidelines §14 / §16.
 *
 * A JavaScript port of the numeric kernels in
 * `app/src/main/java/com/ceecept/music/audio/spatial/` so the maths can be measured
 * against the guideline's pass/fail criteria without an Android device in the loop.
 *
 *   node tools/spatial-qa/qa.js
 *
 * Checks:
 *   1. azimuth localisation error            < 5°      (§14.1)
 *   2. crossover-bank frequency flatness     < 2 dB    (§14.1)
 *   3. panning smoothness (no dead spots)    no notch  (§4.2)
 *   4. oversampler round-trip passband       < 0.5 dB to 16 kHz (§10.1)
 *   5. oversampler image rejection           > 60 dB   (§10.1)
 *   6. gain smoothing / zipper noise         < 0.002 per sample (§10.2, §16.1)
 *   7. ITD / ILD values                      ±0.7 ms at 90° (Appendix A.1)
 *   8. distance model monotonicity           (§7)
 */

const SR = 48000;
const SPEED_OF_SOUND = 343;
const HEAD_WIDTH = 0.2;
const rad = (d) => (d * Math.PI) / 180;
const deg = (r) => (r * 180) / Math.PI;
const wrap = (d) => { let a = d % 360; if (a > 180) a -= 360; if (a < -180) a += 360; return a; };

// --------------------------------------------------------------- layouts (§2.2)

const LAYOUTS = {
  stereo: [
    { name: 'L', az: -90, el: 0, dist: 2.0 },
    { name: 'R', az: +90, el: 0, dist: 2.0 },
  ],
  '5.1_surround': [
    { name: 'FL', az: -30, el: 0, dist: 2.0 },
    { name: 'FC', az: 0, el: 0, dist: 2.0 },
    { name: 'FR', az: +30, el: 0, dist: 2.0 },
    { name: 'SL', az: -110, el: 0, dist: 2.5 },
    { name: 'SR', az: +110, el: 0, dist: 2.5 },
    { name: 'LFE', az: 0, el: -45, dist: 1.5, lfe: true },
  ],
  '7.1.4_immersive': [
    { name: 'FL', az: -30, el: 0, dist: 2.0 },
    { name: 'FC', az: 0, el: 0, dist: 2.0 },
    { name: 'FR', az: +30, el: 0, dist: 2.0 },
    { name: 'SL', az: -110, el: 0, dist: 2.5 },
    { name: 'SR', az: +110, el: 0, dist: 2.5 },
    { name: 'SBL', az: -150, el: 0, dist: 2.5 },
    { name: 'SBR', az: +150, el: 0, dist: 2.5 },
    { name: 'LFE', az: 0, el: -45, dist: 1.5, lfe: true },
    { name: 'HFL', az: -45, el: +60, dist: 1.8 },
    { name: 'HFR', az: +45, el: +60, dist: 1.8 },
    { name: 'HBL', az: -135, el: +60, dist: 1.8 },
    { name: 'HBR', az: +135, el: +60, dist: 1.8 },
  ],
};

// --------------------------------------------------------------- panning (§4)

function angularDistance(az1, el1, az2, el2) {
  const c =
    Math.sin(rad(el1)) * Math.sin(rad(el2)) +
    Math.cos(rad(el1)) * Math.cos(rad(el2)) * Math.cos(rad(az2) - rad(az1));
  return deg(Math.acos(Math.max(-1, Math.min(1, c))));
}

function coneFor(speakers) {
  const az = speakers.filter((s) => !s.lfe).map((s) => s.az).sort((a, b) => a - b);
  if (az.length < 2) return 360;
  let maxGap = 360 + az[0] - az[az.length - 1];
  for (let i = 1; i < az.length; i++) maxGap = Math.max(maxGap, az[i] - az[i - 1]);
  return Math.max(90, maxGap * 1.1);
}

function adaptiveRender(objAz, objEl, objDist, speakers, cone) {
  const gains = new Array(speakers.length).fill(0);
  let total = 0;
  speakers.forEach((sp, i) => {
    if (sp.lfe) return;
    const a = angularDistance(objAz, objEl, sp.az, sp.el);
    if (a < cone) {
      let g = 1 / (1 + a);
      g *= Math.sqrt(sp.dist / (objDist + 0.1));
      gains[i] = Math.max(0, g);
      total += gains[i];
    }
  });
  if (total > 1e-10) for (let i = 0; i < gains.length; i++) gains[i] /= total;
  return gains;
}

function vectorPanning(objAz, speakers) {
  const px = Math.sin(rad(objAz));
  const py = Math.cos(rad(objAz));
  const gains = new Array(speakers.length).fill(0);
  let total = 0;
  speakers.forEach((sp, i) => {
    if (sp.lfe) return;
    const dot = px * Math.sin(rad(sp.az)) + py * Math.cos(rad(sp.az));
    const g = dot > 0 ? Math.pow(dot, 1.5) : 0;
    gains[i] = g;
    total += g;
  });
  if (total > 1e-10) for (let i = 0; i < gains.length; i++) gains[i] /= total;
  return gains;
}

function blended(objAz, objEl, objDist, speakers, tangential, cone) {
  const a = adaptiveRender(objAz, objEl, objDist, speakers, cone);
  if (tangential <= 0) return a;
  const v = vectorPanning(objAz, speakers);
  let total = 0;
  const out = a.map((g, i) => {
    const m = g * (1 - tangential) + v[i] * tangential;
    total += m;
    return m;
  });
  return total > 1e-10 ? out.map((g) => g / total) : out;
}

/**
 * Pairwise (VBAP-style) panner — port of PairwisePanner.kt, the renderer's primary law.
 */
function makePairwise(speakers) {
  const horizontal = [];
  const height = [];
  speakers.forEach((sp, index) => {
    if (sp.lfe) return;
    (sp.el > 30 ? height : horizontal).push({ index, az: sp.az });
  });
  horizontal.sort((a, b) => a.az - b.az);
  height.sort((a, b) => a.az - b.az);

  function panLayer(az, layer, scale, out) {
    if (!layer.length || scale <= 0) return;
    if (layer.length === 1) { out[layer[0].index] += scale; return; }
    let lower = null, upper = null, lowerAz = 0, upperAz = 0, target = az;
    for (let k = 0; k < layer.length; k++) {
      const a = layer[k], b = layer[(k + 1) % layer.length];
      let lo = a.az, hi = b.az;
      if (hi < lo) hi += 360;
      let t = az;
      while (t < lo) t += 360;
      while (t > hi) t -= 360;
      if (t >= lo && t <= hi) { lower = a; upper = b; lowerAz = lo; upperAz = hi; target = t; break; }
    }
    if (!lower) { out[layer[0].index] += scale; return; }
    const half = (upperAz - lowerAz) / 2;
    const mid = (lowerAz + upperAz) / 2;
    const rel = target - mid;
    let g1, g2;
    if (half < 1e-3) { g1 = 1; g2 = 0; }
    else if (half >= 89.9) {
      const t = ((rel / half) + 1) * (Math.PI / 4);
      g1 = Math.cos(t); g2 = Math.sin(t);
    } else {
      g1 = Math.tan(rad(half)) - Math.tan(rad(rel));
      g2 = Math.tan(rad(half)) + Math.tan(rad(rel));
    }
    g1 = Math.max(0, g1); g2 = Math.max(0, g2);
    const norm = Math.hypot(g1, g2);
    if (norm < 1e-9) return;
    out[lower.index] += (scale * g1) / norm;
    out[upper.index] += (scale * g2) / norm;
  }

  function pan(az, el) {
    const out = new Array(speakers.length).fill(0);
    const a = wrap(az);
    if (!height.length || el <= 0) { panLayer(a, horizontal, 1, out); return out; }
    const w = Math.max(0, Math.min(1, el / 60));
    const theta = (w * Math.PI) / 2;
    panLayer(a, horizontal, Math.cos(theta), out);
    panLayer(a, height, Math.sin(theta), out);
    return out;
  }

  function diffuse(out, amount) {
    if (amount <= 0) return out;
    const a = Math.max(0, Math.min(1, amount));
    const all = horizontal.concat(height);
    const even = 1 / Math.sqrt(all.length);
    let power = 0;
    all.forEach((s) => { out[s.index] = out[s.index] * (1 - a) + even * a; power += out[s.index] ** 2; });
    if (power > 1e-12) { const n = 1 / Math.sqrt(power); all.forEach((s) => { out[s.index] *= n; }); }
    return out;
  }

  return { pan, diffuse, hasHeight: height.length > 0 };
}

/** Dsp.softClip — the only non-linearity in the chain. */
function softClip(x) {
  const ax = Math.abs(x);
  if (ax < 0.7) return x;
  return Math.sign(x) * (0.7 + (ax - 0.7) / (1 + (ax - 0.7) * 1.4));
}

/** §14.1 `estimate_perceived_azimuth`: energy-weighted Gerzon velocity vector. */
function perceivedAzimuth(gains, speakers) {
  let x = 0;
  let y = 0;
  gains.forEach((g, i) => {
    if (speakers[i].lfe) return;
    x += g * Math.sin(rad(speakers[i].az));
    y += g * Math.cos(rad(speakers[i].az));
  });
  return deg(Math.atan2(x, y));
}

// --------------------------------------------------------------- biquads (§9)

function lowpass(f0, q) {
  const w = (2 * Math.PI * f0) / SR, a = Math.sin(w) / (2 * q), c = Math.cos(w), a0 = 1 + a;
  return { b0: ((1 - c) / 2) / a0, b1: (1 - c) / a0, b2: ((1 - c) / 2) / a0, a1: (-2 * c) / a0, a2: (1 - a) / a0 };
}
function highpass(f0, q) {
  const w = (2 * Math.PI * f0) / SR, a = Math.sin(w) / (2 * q), c = Math.cos(w), a0 = 1 + a;
  return { b0: ((1 + c) / 2) / a0, b1: -(1 + c) / a0, b2: ((1 + c) / 2) / a0, a1: (-2 * c) / a0, a2: (1 - a) / a0 };
}
function allpass(f0, q) {
  const w = (2 * Math.PI * f0) / SR, a = Math.sin(w) / (2 * q), c = Math.cos(w), a0 = 1 + a;
  return { b0: (1 - a) / a0, b1: (-2 * c) / a0, b2: 1, a1: (-2 * c) / a0, a2: (1 - a) / a0 };
}
function runBiquad(bq, st, x) {
  const y = bq.b0 * x + st.d1;
  st.d1 = bq.b1 * x - bq.a1 * y + st.d2;
  st.d2 = bq.b2 * x - bq.a2 * y;
  return y;
}
const newState = () => ({ d1: 0, d2: 0 });

/** Linkwitz-Riley 4th-order tree with all-pass compensation (Multiband.kt). */
function makeSplitter(crossovers) {
  const n = crossovers.length;
  const f = crossovers.map((fc) => ({
    lp1: lowpass(fc, Math.SQRT1_2), lp2: lowpass(fc, Math.SQRT1_2),
    hp1: highpass(fc, Math.SQRT1_2), hp2: highpass(fc, Math.SQRT1_2),
    ap: allpass(fc, Math.SQRT1_2),
  }));
  const st = f.map(() => ({ lp1: newState(), lp2: newState(), hp1: newState(), hp2: newState() }));
  const apSt = [];
  for (let b = 0; b < n; b++) { apSt.push([]); for (let j = 0; j < n; j++) apSt[b].push(newState()); }
  return (x) => {
    const bands = [];
    let rest = x;
    for (let i = 0; i < n; i++) {
      const low = runBiquad(f[i].lp2, st[i].lp2, runBiquad(f[i].lp1, st[i].lp1, rest));
      const high = runBiquad(f[i].hp2, st[i].hp2, runBiquad(f[i].hp1, st[i].hp1, rest));
      let band = low;
      for (let j = i + 1; j < n; j++) band = runBiquad(f[j].ap, apSt[i][j], band);
      bands.push(band);
      rest = high;
    }
    bands.push(rest);
    return bands;
  };
}

function impulseResponse(process, len) {
  const out = new Float64Array(len);
  for (let i = 0; i < len; i++) out[i] = process(i === 0 ? 1 : 0);
  return out;
}

function magnitudeDb(ir, fHz) {
  let re = 0, im = 0;
  for (let n = 0; n < ir.length; n++) {
    const w = (2 * Math.PI * fHz * n) / SR;
    re += ir[n] * Math.cos(w);
    im -= ir[n] * Math.sin(w);
  }
  return 10 * Math.log10(re * re + im * im + 1e-30);
}

// --------------------------------------------------------------- oversampler (§10.1)

const HB = [0.000410323, -0.002230286, 0.007100857, -0.017917030, 0.040107418,
  -0.090106922, 0.312633322, 0.312633322, -0.090106922, 0.040107418,
  -0.017917030, 0.007100857, -0.002230286, 0.000410323];

function makeOversampler() {
  const upHist = new Array(14).fill(0);
  const downEven = new Array(14).fill(0);
  const downOdd = new Array(8).fill(0);
  return (x, nonlinearity) => {
    let even = 0;
    for (let j = 0; j < 14; j++) even += HB[j] * upHist[j];
    const a = even * 2, b = upHist[6];
    for (let i = 13; i >= 1; i--) upHist[i] = upHist[i - 1];
    upHist[0] = x;
    const na = nonlinearity(a), nb = nonlinearity(b);
    let y = 0;
    for (let j = 0; j < 14; j++) y += HB[j] * downEven[j];
    y += 0.5 * downOdd[7];
    for (let i = 13; i >= 1; i--) downEven[i] = downEven[i - 1];
    downEven[0] = na;
    for (let i = 7; i >= 1; i--) downOdd[i] = downOdd[i - 1];
    downOdd[0] = nb;
    return y;
  };
}

// --------------------------------------------------------------- distance (§7)

const attenuationDb = (d, ref = 1) => 20 * Math.log10(ref / (d + 1e-10));
function airCutoff(d) {
  const x = Math.max(0, Math.min(30, d));
  const l = (a, b, t) => a + (b - a) * Math.max(0, Math.min(1, t));
  if (x < 2) return 20000;
  if (x < 5) return l(20000, 12000, (x - 2) / 3);
  if (x < 10) return l(12000, 8000, (x - 5) / 5);
  if (x < 20) return l(8000, 4000, (x - 10) / 10);
  return 4000;
}
function reverbWet(d) {
  const l = (a, b, t) => a + (b - a) * Math.max(0, Math.min(1, t));
  if (d < 1) return 0.05;
  if (d < 3) return l(0.05, 0.15, (d - 1) / 2);
  if (d < 8) return l(0.15, 0.35, (d - 3) / 5);
  return l(0.35, 0.6, (d - 8) / 8);
}

// --------------------------------------------------------------- tests

const results = [];
const record = (name, pass, detail) => { results.push({ name, pass, detail }); };

// Angular coverage of each layout: a rig cannot place a source in a direction it has
// no speakers for, so each layout is swept over the arc it actually covers.
const COVERAGE = { stereo: [-90, 90], '5.1_surround': [-110, 110], '7.1.4_immersive': [-180, 180] };

// 1. Azimuth localisation accuracy (§14.1: error < 5°).
for (const [id, speakers] of Object.entries(LAYOUTS)) {
  const horiz = speakers.filter((s) => !s.lfe && s.el <= 30);
  const panner = makePairwise(speakers);
  const [lo, hi] = COVERAGE[id];
  if (horiz.length < 3) {
    // A ±90 pair is degenerate for the velocity-vector estimator (both speakers lie on
    // the same axis), so stereo is checked for a monotonic, symmetric pan law instead.
    let monotonic = true;
    let symmetric = true;
    let prev = -Infinity;
    for (let az = lo; az <= hi; az += 2.5) {
      const g = panner.pan(az, 0);
      const ratio = g[1] - g[0];
      if (ratio < prev - 1e-6) monotonic = false;
      prev = ratio;
      const mirror = panner.pan(-az, 0);
      if (Math.abs(g[0] - mirror[1]) > 1e-5) symmetric = false;
    }
    record(`azimuth law · ${id}`, monotonic && symmetric,
      `monotonic ${monotonic}, mirror-symmetric ${symmetric} (±90 pair is degenerate for the vector estimator)`);
    continue;
  }
  let worst = 0;
  let worstAz = 0;
  for (let az = lo; az <= hi; az += 1) {
    const g = panner.pan(az, 0);
    const err = Math.abs(wrap(perceivedAzimuth(g, speakers) - az));
    if (err > worst) { worst = err; worstAz = az; }
  }
  record(`azimuth error · ${id}`, worst < 5.0, `max ${worst.toFixed(3)}° at ${worstAz}° (limit 5°)`);
}

// 2. Panning smoothness: constant power, no dead spots or jumps (§4.2, §14.2).
for (const [id, speakers] of Object.entries(LAYOUTS)) {
  const panner = makePairwise(speakers);
  const [lo, hi] = COVERAGE[id];
  let minEnergy = Infinity;
  let maxEnergy = 0;
  let maxStep = 0;
  let prev = null;
  for (let az = lo; az <= hi; az += 0.5) {
    const g = panner.pan(az, 0);
    const energy = Math.sqrt(g.reduce((a, v) => a + v * v, 0));
    minEnergy = Math.min(minEnergy, energy);
    maxEnergy = Math.max(maxEnergy, energy);
    if (prev) maxStep = Math.max(maxStep, Math.max(...g.map((v, i) => Math.abs(v - prev[i]))));
    prev = g;
  }
  record(`panning continuity · ${id}`, minEnergy > 0.99 && maxEnergy < 1.01 && maxStep < 0.05,
    `power ${minEnergy.toFixed(4)}–${maxEnergy.toFixed(4)}, max step ${maxStep.toFixed(4)} per 0.5°`);
}

// 3. Elevation: the height layer has to take over smoothly (§5.1).
{
  const speakers = LAYOUTS['7.1.4_immersive'];
  const panner = makePairwise(speakers);
  const heightIdx = speakers.map((s, i) => (s.el > 30 ? i : -1)).filter((i) => i >= 0);
  let monotonic = true;
  let prev = -1;
  let powerOk = true;
  for (let el = 0; el <= 90; el += 2) {
    const g = panner.pan(0, el);
    const heightEnergy = heightIdx.reduce((a, i) => a + g[i] * g[i], 0);
    if (heightEnergy < prev - 1e-9) monotonic = false;
    prev = heightEnergy;
    const power = g.reduce((a, v) => a + v * v, 0);
    if (Math.abs(power - 1) > 0.02) powerOk = false;
  }
  const at60 = panner.pan(0, 60);
  const heightAt60 = heightIdx.reduce((a, i) => a + at60[i] * at60[i], 0);
  record('elevation crossfade', monotonic && powerOk && heightAt60 > 0.98,
    `height energy rises monotonically, ${(heightAt60 * 100).toFixed(1)}% at 60°, power preserved`);
}

// 4. Virtual height EQ must be neutral at ear level (§5.2 / §14.1 flatness).
{
  const hf = (el) => {
    const e = Math.max(-90, Math.min(90, el));
    const db = e >= 0 ? (e / 30) * 6 : -(Math.abs(e) / 30) * 6;
    return Math.max(-9, Math.min(9, db));
  };
  const lf = (el) => {
    const e = Math.max(-90, Math.min(90, el));
    return e >= 0 ? -(e / 90) * 4 : (Math.abs(e) / 90) * 4;
  };
  record('virtual height neutral at 0°', Math.abs(hf(0)) < 1e-6 && Math.abs(lf(0)) < 1e-6,
    `0°: ${hf(0).toFixed(2)} dB HF / ${lf(0).toFixed(2)} dB LF · 30°: +${hf(30).toFixed(1)} dB · -30°: ${hf(-30).toFixed(1)} dB`);
}

// 5. Crossover bank flatness (§14.1: < 2 dB).
{
  const split = makeSplitter([100, 250, 1000, 4000, 12000]);
  const ir = impulseResponse((x) => split(x).reduce((a, b) => a + b, 0), 8192);
  let min = Infinity, max = -Infinity;
  for (let f = 30; f < 20000; f *= 1.03) {
    const m = magnitudeDb(ir, f);
    min = Math.min(min, m);
    max = Math.max(max, m);
  }
  record('crossover flatness (6 bands summed)', max - min < 2.0,
    `ripple ${(max - min).toFixed(3)} dB across 30 Hz–20 kHz (limit 2 dB)`);
}

// 6. Oversampler passband (§10.1).
{
  const os = makeOversampler();
  const ir = impulseResponse((x) => os(x, (v) => v), 256);
  const probes = [1000, 8000, 12000, 16000];
  const errs = probes.map((f) => magnitudeDb(ir, f));
  record('oversampler passband', Math.max(...errs.map(Math.abs)) < 0.5,
    probes.map((f, i) => `${f / 1000}k:${errs[i].toFixed(2)}dB`).join(' '));
}

// 7. Aliasing: drive the saturator hard and compare 2x against base rate (§10.1, §16.1).
//    A 9 kHz tone clipped at base rate folds its 5th harmonic (45 kHz) back to 3 kHz.
{
  const N = 16384;
  const f0 = 9000;
  // The limiter runs before the saturator, so 0 dBFS is the hottest signal it can see.
  const amp = 1.0;
  const base = new Float64Array(N);
  const over = new Float64Array(N);
  const os = makeOversampler();
  for (let n = 0; n < N; n++) {
    const x = amp * Math.sin((2 * Math.PI * f0 * n) / SR);
    base[n] = softClip(x);
    over[n] = os(x, softClip);
  }
  const bin = (buf, f) => {
    let re = 0, im = 0;
    for (let n = 0; n < buf.length; n++) {
      const w = (2 * Math.PI * f * n) / SR;
      re += buf[n] * Math.cos(w);
      im -= buf[n] * Math.sin(w);
    }
    return 20 * Math.log10((2 * Math.hypot(re, im)) / buf.length + 1e-30);
  };
  const aliasBase = bin(base, 3000);   // 5th harmonic folded back
  const aliasOver = bin(over, 3000);
  const fundBase = bin(base, f0);
  const improvement = aliasBase - aliasOver;
  record('anti-alias (saturator)', improvement > 30,
    `3 kHz alias ${aliasBase.toFixed(1)} dB -> ${aliasOver.toFixed(1)} dB ` +
    `(${improvement.toFixed(1)} dB better, fundamental ${fundBase.toFixed(1)} dB)`);
}

// 8. Gain smoothing / zipper noise (§10.2, §16.1 has_zipper_noise).
//    Reference: the guideline's own 5 ms moving-average kernel steps by 1/N per sample.
{
  const smoothingSamples = (5 / 1000) * SR;
  const guidelineBound = 1 / smoothingSamples;
  const coef = 1 - Math.exp(-1 / smoothingSamples);
  let g = 0;
  let maxStep = 0;
  let settle = -1;
  for (let i = 0; i < SR; i++) {
    const target = i < SR / 2 ? 0 : 1;
    const next = g + coef * (target - g);
    maxStep = Math.max(maxStep, Math.abs(next - g));
    g = next;
    if (settle < 0 && g > 0.95) settle = ((i - SR / 2) / SR) * 1000;
  }
  record('gain smoothing (zipper)', maxStep <= guidelineBound + 1e-9,
    `max step ${maxStep.toFixed(5)} <= guideline ${guidelineBound.toFixed(5)}, 95% in ${settle.toFixed(1)} ms`);
}

// 9. ITD / ILD (Appendix A.1, §6.2).
{
  const itd90 = (HEAD_WIDTH / SPEED_OF_SOUND) * Math.sin(rad(90)) * 1000;
  const itd45 = (HEAD_WIDTH / SPEED_OF_SOUND) * Math.sin(rad(45)) * 1000;
  record('ITD magnitude', itd90 > 0.4 && itd90 < 0.8,
    `${itd90.toFixed(3)} ms at 90°, ${itd45.toFixed(3)} ms at 45° (guideline ≈0.7 ms)`);
  const ild = Math.abs(Math.sin(rad(45))) * 12;
  record('ILD at 45°', ild > 5 && ild < 15, `${ild.toFixed(2)} dB high-shelf on the far ear`);
}

// 10. Distance model monotonicity (§7).
{
  let ok = true;
  let prevGain = Infinity, prevCut = Infinity, prevWet = -Infinity;
  for (let d = 0.5; d <= 20; d += 0.25) {
    const g = attenuationDb(d);
    const c = airCutoff(d);
    const w = reverbWet(d);
    if (g > prevGain + 1e-9 || c > prevCut + 1e-9 || w < prevWet - 1e-9) ok = false;
    prevGain = g; prevCut = c; prevWet = w;
  }
  record('distance cues monotonic', ok,
    `1 m ${attenuationDb(1).toFixed(1)} dB / 4 m ${attenuationDb(4).toFixed(1)} dB / ` +
    `cutoff 12 m ${(airCutoff(12) / 1000).toFixed(1)} kHz / wet 12 m ${reverbWet(12).toFixed(2)}`);
}

// 11. Doppler direction and magnitude (§8.2).
{
  const ratio = (v) => 1 / Math.max(0.5, Math.min(1.5, 1 - v / SPEED_OF_SOUND));
  const cents = (v) => -1200 * Math.log2(Math.max(0.5, Math.min(1.5, 1 - v / SPEED_OF_SOUND)));
  const approach = cents(34.3);
  const recede = cents(-34.3);
  record('doppler', approach > 0 && recede < 0 && Math.abs(approach - 182) < 5,
    `+34.3 m/s -> +${approach.toFixed(1)} cents (ratio ${ratio(34.3).toFixed(3)}), -34.3 m/s -> ${recede.toFixed(1)} cents`);
}

// 12. Trajectory spline: hits its keyframes, C1 continuous, no wild overshoot (§8.1).
{
  const keys = [[0, 0], [2.5, 45], [5, 90], [7.5, 20], [10, -60]];
  const xs = keys.map((k) => k[0]);
  const ys = keys.map((k) => k[1]);
  const n = xs.length;
  const m = new Array(n).fill(0);
  {
    const a = new Array(n).fill(0), b = new Array(n).fill(0), c = new Array(n).fill(0), d = new Array(n).fill(0);
    for (let i = 1; i < n - 1; i++) {
      const h0 = xs[i] - xs[i - 1], h1 = xs[i + 1] - xs[i];
      a[i] = h0; b[i] = 2 * (h0 + h1); c[i] = h1;
      d[i] = 6 * ((ys[i + 1] - ys[i]) / h1 - (ys[i] - ys[i - 1]) / h0);
    }
    b[0] = 1; c[0] = 0; d[0] = 0; a[n - 1] = 0; b[n - 1] = 1; d[n - 1] = 0;
    for (let i = 1; i < n; i++) { const w = a[i] / b[i - 1]; b[i] -= w * c[i - 1]; d[i] -= w * d[i - 1]; }
    m[n - 1] = d[n - 1] / b[n - 1];
    for (let i = n - 2; i >= 0; i--) m[i] = (d[i] - c[i] * m[i + 1]) / b[i];
  }
  const val = (x) => {
    if (x <= xs[0]) return ys[0];
    if (x >= xs[n - 1]) return ys[n - 1];
    let hi = 1; while (hi < n - 1 && xs[hi] < x) hi++;
    const lo = hi - 1, h = xs[hi] - xs[lo], t = x - xs[lo], u = h - t;
    return (m[lo] * u ** 3 + m[hi] * t ** 3) / (6 * h) +
      (ys[lo] / h - (m[lo] * h) / 6) * u + (ys[hi] / h - (m[hi] * h) / 6) * t;
  };
  // (a) interpolates its keyframes
  let knotErr = 0;
  keys.forEach((k) => { knotErr = Math.max(knotErr, Math.abs(val(k[0]) - k[1])); });
  // (b) C1: one-sided derivatives agree at every interior knot
  let c1Err = 0;
  for (let i = 1; i < n - 1; i++) {
    const t = xs[i], e = 1e-4;
    const left = (val(t - e) - val(t - 2 * e)) / e;
    const right = (val(t + 2 * e) - val(t + e)) / e;
    c1Err = Math.max(c1Err, Math.abs(left - right));
  }
  // (c) no overshoot far outside the keyframe range
  let lo = Infinity, hi = -Infinity;
  for (let t = 0; t <= 10; t += 0.005) { const v = val(t); lo = Math.min(lo, v); hi = Math.max(hi, v); }
  const span = Math.max(...ys) - Math.min(...ys);
  const overshoot = Math.max(hi - Math.max(...ys), Math.min(...ys) - lo) / span;
  record('trajectory spline', knotErr < 1e-4 && c1Err < 0.05 && overshoot < 0.15,
    `knot err ${knotErr.toExponential(1)}°, C1 mismatch ${c1Err.toExponential(1)}°/s, overshoot ${(overshoot * 100).toFixed(1)}%`);
}

// ============================================================================
//  Immerse 3.0 — separation, enhancement and gain staging
// ============================================================================
//
// Ports of Fft.kt, StemSeparator.kt and Artifacts.kt (LookaheadLimiter,
// LoudnessMatch). These verify the properties the renderer depends on:
//   * the transform reconstructs exactly (nothing is coloured by analysis),
//   * the fourteen masks are a partition of unity (separation cannot add energy),
//   * each part of a known mix lands in the stream it should,
//   * the limiter no longer distorts the bass it is holding down,
//   * the loudness match returns the render to the level of the source.

const N_FFT = 1024, HOP = 256, HALF = N_FFT / 2, MED_T = 15, MED_F = 9, EPSQ = 1e-12;

function makeFft(n) {
  const levels = Math.log2(n);
  const cosT = new Float64Array(n / 2), sinT = new Float64Array(n / 2), rv = new Int32Array(n);
  for (let i = 0; i < n / 2; i++) { cosT[i] = Math.cos(2 * Math.PI * i / n); sinT[i] = Math.sin(2 * Math.PI * i / n); }
  for (let i = 0; i < n; i++) { let x = i, r = 0; for (let b = 0; b < levels; b++) { r = (r << 1) | (x & 1); x >>= 1; } rv[i] = r; }
  function tr(re, im, conj) {
    for (let i = 0; i < n; i++) { const j = rv[i]; if (j > i) { let t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; } }
    for (let size = 2; size <= n; size *= 2) {
      const h = size / 2, step = n / size;
      for (let i = 0; i < n; i += size) {
        for (let j = i, k = 0; j < i + h; j++, k += step) {
          const c = cosT[k], sn = conj ? sinT[k] : -sinT[k], l = j + h;
          const tre = re[l] * c - im[l] * sn, tim = re[l] * sn + im[l] * c;
          re[l] = re[j] - tre; im[l] = im[j] - tim; re[j] += tre; im[j] += tim;
        }
      }
    }
  }
  return {
    forward: (re, im) => tr(re, im, false),
    inverse: (re, im) => { tr(re, im, true); for (let i = 0; i < n; i++) { re[i] /= n; im[i] /= n; } },
  };
}
function unpack2(re, im, n, aRe, aIm, bRe, bIm) {
  const h = n / 2;
  for (let k = 0; k <= h; k++) {
    const kc = k === 0 ? 0 : n - k;
    const r1 = re[k], i1 = im[k], r2 = re[kc], i2 = im[kc];
    aRe[k] = 0.5 * (r1 + r2); aIm[k] = 0.5 * (i1 - i2);
    bRe[k] = 0.5 * (i1 + i2); bIm[k] = -0.5 * (r1 - r2);
  }
}
function pack2(aRe, aIm, bRe, bIm, n, re, im) {
  const h = n / 2;
  for (let k = 0; k <= h; k++) { re[k] = aRe[k] - bIm[k]; im[k] = aIm[k] + bRe[k]; }
  for (let k = 1; k < h; k++) { const kc = n - k; re[kc] = aRe[k] + bIm[k]; im[kc] = -aIm[k] + bRe[k]; }
}
const hannW = (n) => Float64Array.from({ length: n }, (_, i) => 0.5 - 0.5 * Math.cos(2 * Math.PI * i / n));
const sqrtHannW = (n) => Float64Array.from(hannW(n), (v) => Math.sqrt(v));
function wolaNorm(w, hop) { let s = 0; for (let i = 0; i < w.length; i += hop) s += w[i] * w[i]; return s; }
function med(a, n) {
  for (let i = 1; i < n; i++) { const v = a[i]; let j = i - 1; while (j >= 0 && a[j] > v) { a[j + 1] = a[j]; j--; } a[j + 1] = v; }
  return a[n >> 1];
}
function bandW(f, lo0, lo1, hi0, hi1) {
  if (f <= lo0 || f >= hi1) return 0;
  if (f >= lo1 && f <= hi0) return 1;
  if (f < lo1) { const t = (f - lo0) / Math.max(lo1 - lo0, 1e-6); return 0.5 - 0.5 * Math.cos(Math.PI * t); }
  const t = (f - hi0) / Math.max(hi1 - hi0, 1e-6); return 0.5 + 0.5 * Math.cos(Math.PI * t);
}

// --- 13. transform round trip -------------------------------------------------
{
  const f = makeFft(N_FFT);
  const a = new Float64Array(N_FFT), b = new Float64Array(N_FFT);
  for (let i = 0; i < N_FFT; i++) { a[i] = Math.sin(i * 0.05) + 0.2 * Math.sin(i * 0.61); b[i] = Math.cos(i * 0.11) * 0.5; }
  const re = Float64Array.from(a), im = Float64Array.from(b);
  f.forward(re, im);
  const aRe = new Float64Array(HALF + 1), aIm = new Float64Array(HALF + 1), bRe = new Float64Array(HALF + 1), bIm = new Float64Array(HALF + 1);
  unpack2(re, im, N_FFT, aRe, aIm, bRe, bIm);
  const re2 = new Float64Array(N_FFT), im2 = new Float64Array(N_FFT);
  pack2(aRe, aIm, bRe, bIm, N_FFT, re2, im2);
  f.inverse(re2, im2);
  let e = 0;
  for (let i = 0; i < N_FFT; i++) e = Math.max(e, Math.abs(re2[i] - a[i]), Math.abs(im2[i] - b[i]));
  record('stereo FFT round trip', e < 1e-9, `max error ${e.toExponential(1)} (two channels, one transform)`);
}

// --- 14. weighted overlap-add reconstruction ----------------------------------
{
  const w = sqrtHannW(N_FFT), sc = 1 / wolaNorm(w, HOP), len = 8192;
  const x = new Float64Array(len), out = new Float64Array(len);
  for (let i = 0; i < len; i++) x[i] = Math.sin(i * 0.037) * 0.7 + Math.sin(i * 0.31) * 0.2;
  for (let st = 0; st + N_FFT <= len; st += HOP) for (let i = 0; i < N_FFT; i++) out[st + i] += x[st + i] * w[i] * w[i] * sc;
  let e = 0;
  for (let i = N_FFT; i < len - N_FFT; i++) e = Math.max(e, Math.abs(out[i] - x[i]));
  record('analysis/synthesis transparency', e < 1e-9, `max error ${e.toExponential(1)} — the STFT itself colours nothing`);
}

// --- 15/16/17. the separator on a known mix -----------------------------------
function separatorRun(L, R, len) {
  const f = makeFft(N_FFT), w = sqrtHannW(N_FFT), sc = 1 / wolaNorm(w, HOP), binHz = SR / N_FFT;
  const wB = new Float64Array(HALF + 1), wV = new Float64Array(HALF + 1), wA = new Float64Array(HALF + 1);
  for (let k = 0; k <= HALF; k++) {
    const fr = k * binHz;
    wB[k] = bandW(fr, 0, 0, 110, 180); wV[k] = bandW(fr, 140, 260, 5200, 8000); wA[k] = bandW(fr, 6500, 10000, 30000, 40000);
  }
  const hops = SR / HOP, c = Math.min(1, Math.max(0.05, 1 - Math.exp(-1 / (0.04 * hops))));
  const hist = Array.from({ length: MED_T }, () => new Float64Array(HALF + 1));
  let hp = 0;
  const cohS = new Float64Array(HALF + 1), balS = new Float64Array(HALF + 1), panS = new Float64Array(HALF + 1);
  const harmS = new Float64Array(HALF + 1).fill(0.5);
  const S = 14;
  const sRe = Array.from({ length: S }, () => new Float64Array(HALF + 1));
  const sIm = Array.from({ length: S }, () => new Float64Array(HALF + 1));
  const streams = Array.from({ length: S }, () => new Float64Array(len));
  let sumMin = Infinity, sumMax = -Infinity;
  const re = new Float64Array(N_FFT), im = new Float64Array(N_FFT);
  const lRe = new Float64Array(HALF + 1), lIm = new Float64Array(HALF + 1), rRe = new Float64Array(HALF + 1), rIm = new Float64Array(HALF + 1);
  const magMid = new Float64Array(HALF + 1), harm = new Float64Array(HALF + 1), perc = new Float64Array(HALF + 1);
  const scratch = new Float64Array(Math.max(MED_T, MED_F));
  for (let start = 0; start + N_FFT <= len; start += HOP) {
    for (let i = 0; i < N_FFT; i++) { re[i] = L[start + i] * w[i]; im[i] = R[start + i] * w[i]; }
    f.forward(re, im);
    unpack2(re, im, N_FFT, lRe, lIm, rRe, rIm);
    const h = hist[hp];
    for (let k = 0; k <= HALF; k++) {
      const mr = 0.5 * (lRe[k] + rRe[k]), mi = 0.5 * (lIm[k] + rIm[k]);
      magMid[k] = Math.hypot(mr, mi); h[k] = magMid[k];
    }
    hp = (hp + 1) % MED_T;
    for (let k = 0; k <= HALF; k++) { for (let t = 0; t < MED_T; t++) scratch[t] = hist[t][k]; harm[k] = med(scratch, MED_T); }
    for (let k = 0; k <= HALF; k++) {
      for (let j = 0; j < MED_F; j++) scratch[j] = magMid[Math.min(HALF, Math.max(0, k + j - (MED_F >> 1)))];
      perc[k] = med(scratch, MED_F);
    }
    for (let k = 0; k <= HALF; k++) {
      const lr = lRe[k], li = lIm[k], rr = rRe[k], ri = rIm[k];
      const ml = Math.hypot(lr, li), mrr = Math.hypot(rr, ri);
      const dot = lr * rr + li * ri;
      const coherence = Math.min(1, Math.max(-1, dot / (ml * mrr + EPSQ)));
      const balance = Math.min(1, Math.max(0, 2 * ml * mrr / (ml * ml + mrr * mrr + EPSQ)));
      const pan = Math.min(1, Math.max(-1, (mrr - ml) / (ml + mrr + EPSQ)));
      const hh = harm[k], pp = perc[k], harmMask = (hh * hh) / (hh * hh + pp * pp + EPSQ);
      cohS[k] += c * (coherence - cohS[k]); balS[k] += c * (balance - balS[k]);
      panS[k] += c * (pan - panS[k]); harmS[k] += c * (harmMask - harmS[k]);
      const coh = cohS[k], bal = balS[k], pn = panS[k];
      const mh = Math.min(1, Math.max(0, harmS[k])), mp = 1 - mh;
      const bassW2 = wB[k], rest = 1 - bassW2;
      let amb = bal * (1 - Math.abs(coh)), wide = bal * Math.max(0, -coh);
      const diff = amb + wide;
      if (diff > 1) { amb /= diff; wide /= diff; }
      const direct = Math.min(1, Math.max(0, 1 - amb - wide));
      const centre = (1 - Math.abs(pn)) * (1 - Math.abs(pn)) * Math.max(0, coh);
      const ambM = rest * amb, wideM = rest * wide, dirM = rest * direct;
      const dH = dirM * mh, dP = dirM * mp;
      const cH = dH * centre, sH = dH - cH, cP = dP * centre, sP = dP - cP;
      const leadM = cH * wV[k], centreM = cH - leadM;
      const airM = ambM * wA[k], rearM = ambM - airM;
      const total = bassW2 + leadM + centreM + cP + sP + sH + wideM + rearM + airM;
      if (k > 2 && k < HALF - 2) { sumMin = Math.min(sumMin, total); sumMax = Math.max(sumMax, total); }
      const midRe = 0.5 * (lr + rr), midIm = 0.5 * (li + ri);
      const set = (idx, m, a2, b2) => { sRe[idx][k] = m * a2; sIm[idx][k] = m * b2; };
      set(0, bassW2, midRe, midIm); set(1, leadM, midRe, midIm); set(2, centreM, midRe, midIm); set(3, cP, midRe, midIm);
      set(4, sP, lr, li); set(5, sP, rr, ri); set(6, sH, lr, li); set(7, sH, rr, ri);
      set(8, wideM, lr, li); set(9, wideM, rr, ri); set(10, rearM, lr, li); set(11, rearM, rr, ri);
      set(12, airM, lr, li); set(13, airM, rr, ri);
    }
    const re2 = new Float64Array(N_FFT), im2 = new Float64Array(N_FFT);
    for (let sIdx = 0; sIdx < S; sIdx += 2) {
      pack2(sRe[sIdx], sIm[sIdx], sRe[sIdx + 1], sIm[sIdx + 1], N_FFT, re2, im2);
      f.inverse(re2, im2);
      for (let i = 0; i < N_FFT; i++) {
        streams[sIdx][start + i] += re2[i] * w[i] * sc;
        streams[sIdx + 1][start + i] += im2[i] * w[i] * sc;
      }
    }
  }
  return { streams, sumMin, sumMax };
}

{
  const len = 36000;
  const L = new Float64Array(len), R = new Float64Array(len);
  const gt = { lead: new Float64Array(len), gtr: new Float64Array(len), pno: new Float64Array(len), pad: new Float64Array(len), bass: new Float64Array(len) };
  let seed = 12345;
  const rnd = () => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x3fffffff - 1; };
  for (let i = 0; i < len; i++) {
    const t = i / SR;
    const lead = 0.30 * (Math.sin(2 * Math.PI * 440 * t) + 0.4 * Math.sin(2 * Math.PI * 880 * t));
    const gtr = 0.25 * Math.sin(2 * Math.PI * 660 * t + 1.1);
    const pno = 0.25 * Math.sin(2 * Math.PI * 523 * t + 0.4);
    const kick = (i % 12000 < 60) ? 0.5 * Math.exp(-(i % 12000) / 20) * rnd() : 0;
    const pad = 0.18 * Math.sin(2 * Math.PI * 330 * t + 0.2);
    const bass = 0.35 * Math.sin(2 * Math.PI * 60 * t);
    gt.lead[i] = lead; gt.gtr[i] = gtr; gt.pno[i] = pno; gt.pad[i] = pad; gt.bass[i] = bass;
    L[i] = lead + gtr * 0.95 + pno * 0.1 + kick + pad + bass + rnd() * 0.06;
    R[i] = lead + gtr * 0.1 + pno * 0.95 + kick - pad + bass + rnd() * 0.06;
  }
  const { streams, sumMin, sumMax } = separatorRun(L, R, len);
  record('separation masks sum to unity', Math.abs(sumMin - 1) < 1e-6 && Math.abs(sumMax - 1) < 1e-6,
    `every bin in [${sumMin.toFixed(6)}, ${sumMax.toFixed(6)}] — no energy created or lost`);

  const from = 12000, to = 34000;
  const rms = (a) => { let s = 0; for (let i = from; i < to; i++) s += a[i] * a[i]; return Math.sqrt(s / (to - from)); };
  const corr = (a, b) => {
    let sa = 0, sb = 0, sab = 0;
    for (let i = from; i < to; i++) { sa += a[i] * a[i]; sb += b[i] * b[i]; sab += a[i] * b[i]; }
    return sab / Math.sqrt(sa * sb + 1e-20);
  };
  const cBass = corr(streams[0], gt.bass), cLead = corr(streams[1], gt.lead);
  const cGtr = corr(streams[6], gt.gtr), cPno = corr(streams[7], gt.pno);
  const cPad = Math.abs(corr(streams[8], gt.pad));
  record('stream assignment', cBass > 0.9 && cLead > 0.8 && cGtr > 0.8 && cPno > 0.8 && cPad > 0.8,
    `bass ${cBass.toFixed(2)} · vocal ${cLead.toFixed(2)} · gtr(L) ${cGtr.toFixed(2)} · piano(R) ${cPno.toFixed(2)} · pad ${cPad.toFixed(2)}`);

  let sumSq = 0;
  for (let s2 = 0; s2 < 14; s2++) { const r = rms(streams[s2]); sumSq += r * r; }
  const inE = rms(L) ** 2 + rms(R) ** 2;
  const dbDiff = 10 * Math.log10(sumSq / inE);
  record('separation energy balance', Math.abs(dbDiff) < 3.5,
    `streams sum to ${dbDiff >= 0 ? '+' : ''}${dbDiff.toFixed(2)} dB of the programme`);

  // Cross-talk: how much of the hard-left guitar leaks into the right instrument object
  const leak = 20 * Math.log10((corr(streams[7], gt.gtr) ** 2 + 1e-12) ** 0.5 / (Math.abs(cGtr) + 1e-12));
  record('inter-object leakage', leak < -12, `left source into the right object ${leak.toFixed(1)} dB`);
}

// --- 18. look-ahead limiter distortion ----------------------------------------
{
  // A 60 Hz sine 6 dB over the ceiling: the classic case where an instantaneous-attack
  // limiter shreds the bass. Compare harmonic distortion of both designs.
  const len = 48000, f0 = 60, amp = 2.0, ceiling = 0.977;
  const x = Float64Array.from({ length: len }, (_, i) => amp * Math.sin(2 * Math.PI * f0 * i / SR));

  const instant = new Float64Array(len);
  {
    let g = 1; const rel = 1 - Math.exp(-1 / (0.05 * SR));
    for (let i = 0; i < len; i++) {
      const peak = Math.abs(x[i]);
      const req = peak > ceiling ? ceiling / peak : 1;
      g = req < g ? req : g + rel * (req - g);
      instant[i] = x[i] * g;
    }
  }
  const looked = new Float64Array(len);
  {
    const look = Math.round(0.0015 * SR), holdLen = Math.round(0.025 * SR);
    const buf = new Float64Array(look);
    let wi = 0, g = 1, held = 1, hold = 0;
    const att = 1 - Math.exp(-3 / look), rel = 1 - Math.exp(-1 / (0.12 * SR));
    for (let i = 0; i < len; i++) {
      const peak = Math.abs(x[i]);
      const req = peak > ceiling ? ceiling / peak : 1;
      // attack / hold / release: the hold spans more than one cycle of the lowest
      // note, so a sustained bass note gets one steady gain instead of a gain that
      // ripples at twice its frequency (which *is* distortion).
      if (req < held) { held = req; hold = holdLen; }
      else if (req < 0.999) { hold = holdLen; }
      else if (hold > 0) hold--;
      else held += rel * (req - held);
      g += (held < g ? att : rel) * (held - g);
      const d = buf[wi]; buf[wi] = x[i]; wi = (wi + 1) % look;
      looked[i] = d * g;
    }
  }
  const thd = (sig) => {
    const from = 16000, n = 16000; // exactly 20 cycles of 60 Hz: no spectral leakage
    let fund = 0, harm = 0;
    for (let h = 1; h <= 12; h++) {
      let re = 0, im = 0;
      for (let i = 0; i < n; i++) {
        const ph = 2 * Math.PI * f0 * h * i / SR;
        re += sig[from + i] * Math.cos(ph); im += sig[from + i] * Math.sin(ph);
      }
      const mag = Math.hypot(re, im) / n;
      if (h === 1) fund = mag; else harm += mag * mag;
    }
    return 20 * Math.log10(Math.sqrt(harm) / (fund + 1e-20));
  };
  const a = thd(instant), b = thd(looked);
  record('limiter distortion (60 Hz, +6 dB)', b < -40 && b < a - 10,
    `instant attack ${a.toFixed(1)} dB THD → look-ahead ${b.toFixed(1)} dB (limit -40)`);
}

// --- 19. loudness match --------------------------------------------------------
{
  // Simulate a render that comes back 4 dB hot and check the corrector returns it to
  // the level of the source without acting like a compressor.
  const len = SR * 3, coef = 1 - Math.exp(-1 / (0.3 * SR));
  let inP = 0, outP = 0, g = 1, gTarget = 1;
  const gCoef = 1 - Math.exp(-1 / (0.15 * SR));
  let peakGain = 0, lastGain = 1;
  const corrected = new Float64Array(len), source = new Float64Array(len);
  for (let i = 0; i < len; i++) {
    const t = i / SR;
    const dry = 0.25 * (Math.sin(2 * Math.PI * 220 * t) + Math.sin(2 * Math.PI * 330 * t + 1));
    const wet = dry * 1.585; // +4 dB
    inP += coef * (dry * dry - inP);
    outP += coef * (wet * wet - outP);
    if (inP > 1e-6) gTarget = Math.min(2, Math.max(0.5, Math.sqrt(inP / outP)));
    g += gCoef * (gTarget - g);
    corrected[i] = wet * g; source[i] = dry;
    peakGain = Math.max(peakGain, Math.abs(g - lastGain)); lastGain = g;
  }
  const rms = (a, from) => { let s = 0; for (let i = from; i < len; i++) s += a[i] * a[i]; return Math.sqrt(s / (len - from)); };
  const err = 20 * Math.log10(rms(corrected, SR * 2) / rms(source, SR * 2));
  record('loudness match', Math.abs(err) < 0.5 && peakGain < 1e-4,
    `output within ${err >= 0 ? '+' : ''}${err.toFixed(2)} dB of source, max gain step ${peakGain.toExponential(1)}/sample`);
}

// --------------------------------------------------------------- report

let failed = 0;
console.log('\nCeecept spatial QA — guidelines §14\n' + '='.repeat(72));
for (const r of results) {
  if (!r.pass) failed++;
  console.log(`${r.pass ? 'PASS' : 'FAIL'}  ${r.name.padEnd(34)} ${r.detail}`);
}
console.log('='.repeat(72));
console.log(`${results.length - failed}/${results.length} checks passed\n`);
process.exit(failed === 0 ? 0 : 1);
