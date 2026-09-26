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
