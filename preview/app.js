import { CeeceptDSP, EQ_FREQS, EQ_PRESETS, DYN_PRESETS, SPACE_PRESETS } from './dsp.js';

const $ = (id) => document.getElementById(id);
const dsp = new CeeceptDSP();
const audio = new Audio();
audio.preload = 'auto';

// ---------------- state ----------------
const tracks = [];
let index = -1, shuffle = false, repeat = 0; // 0 off, 1 all, 2 one
let scrubbing = false;

const fmtT = (s) => {
  if (!isFinite(s) || s < 0) return '0:00';
  s = Math.floor(s);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
};
const toast = (msg) => {
  const t = $('toast');
  t.textContent = msg; t.classList.remove('hidden');
  clearTimeout(t._h); t._h = setTimeout(() => t.classList.add('hidden'), 2200);
};
const paintFill = (el) => {
  const pct = ((el.value - el.min) / (el.max - el.min)) * 100;
  el.style.setProperty('--fill', pct + '%');
};
document.querySelectorAll('input[type="range"]').forEach((el) => {
  el.addEventListener('input', () => paintFill(el));
  paintFill(el);
});

// ---------------- tabs ----------------
const TITLES = { library: 'Library', studio: 'Studio', settings: 'Settings' };
document.querySelectorAll('.tab').forEach((b) => b.addEventListener('click', () => {
  document.querySelectorAll('.tab').forEach((x) => x.classList.remove('active'));
  b.classList.add('active');
  document.querySelectorAll('.view').forEach((v) => v.classList.remove('active'));
  $('view-' + b.dataset.tab).classList.add('active');
  $('pageTitle').textContent = TITLES[b.dataset.tab];
  if (b.dataset.tab === 'studio') requestAnimationFrame(() => { drawEqGraph(); drawPad(); });
}));
document.querySelectorAll('.seg-tab').forEach((b) => b.addEventListener('click', () => {
  document.querySelectorAll('.seg-tab').forEach((x) => x.classList.remove('active'));
  b.classList.add('active');
  document.querySelectorAll('.stab').forEach((v) => v.classList.remove('active'));
  $('stab-' + b.dataset.stab).classList.add('active');
  requestAnimationFrame(() => { drawEqGraph(); drawPad(); });
}));

// ---------------- library ----------------
function parseName(file) {
  const base = file.name.replace(/\.[a-z0-9]+$/i, '');
  const m = base.split(' - ');
  return m.length > 1
    ? { title: m.slice(1).join(' - ').trim(), artist: m[0].trim() }
    : { title: base, artist: 'Unknown Artist' };
}
function artFor(title) {
  let h = 0;
  for (const c of title) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  const hues = [h % 360, (h * 7 + 120) % 360];
  return `linear-gradient(135deg, hsl(${hues[0]} 65% 45%), hsl(${hues[1]} 65% 32%))`;
}
function addFiles(files) {
  let added = 0;
  for (const f of files) {
    if (!/audio|\.(mp3|wav|flac|ogg|oga|opus|m4a|aac|wma|aiff?)$/i.test(f.type + ' ' + f.name)) continue;
    const { title, artist } = parseName(f);
    const url = URL.createObjectURL(f);
    const tr = { title, artist, url, name: f.name, dur: 0 };
    tracks.push(tr); added++;
    const probe = new Audio();
    probe.preload = 'metadata'; probe.src = url;
    probe.onloadedmetadata = () => { tr.dur = probe.duration; renderList(); };
  }
  if (added) { renderList(); toast(`${added} track${added > 1 ? 's' : ''} added — tap to play`); }
  else toast('No audio files found');
}
function renderList() {
  const q = $('search').value.trim().toLowerCase();
  const list = $('trackList');
  list.innerHTML = '';
  const vis = tracks.map((t, i) => ({ t, i })).filter(({ t }) =>
    !q || t.title.toLowerCase().includes(q) || t.artist.toLowerCase().includes(q));
  $('libCount').textContent = tracks.length ? `${tracks.length} song${tracks.length > 1 ? 's' : ''} · stored locally only` : '';
  $('dropzone').style.display = tracks.length ? 'none' : 'block';
  for (const { t, i } of vis) {
    const li = document.createElement('li');
    if (i === index) li.className = 'playing';
    li.innerHTML = `<div class="t-art" style="background:${artFor(t.title)}">${t.title[0]?.toUpperCase() || '♪'}</div>
      <div class="t-meta"><div class="t-title"></div><div class="t-sub"></div></div>
      ${i === index ? '<div class="eqbars"><i></i><i></i><i></i><i></i></div>' : ''}
      <div class="t-dur">${t.dur ? fmtT(t.dur) : ''}</div>`;
    li.querySelector('.t-title').textContent = t.title;
    li.querySelector('.t-sub').textContent = t.artist;
    li.addEventListener('click', () => playIndex(i));
    list.appendChild(li);
  }
}
$('search').addEventListener('input', renderList);
$('pickBtn').addEventListener('click', (e) => { e.stopPropagation(); $('fileInput').click(); });
$('dropzone').addEventListener('click', () => $('fileInput').click());
$('fileInput').addEventListener('change', (e) => { addFiles(e.target.files); e.target.value = ''; });
for (const ev of ['dragover', 'dragenter']) $('dropzone').addEventListener(ev, (e) => { e.preventDefault(); $('dropzone').classList.add('over'); });
for (const ev of ['dragleave', 'drop']) $('dropzone').addEventListener(ev, (e) => { e.preventDefault(); $('dropzone').classList.remove('over'); });
$('dropzone').addEventListener('drop', (e) => addFiles(e.dataTransfer.files));

// ---------------- transport ----------------
async function playIndex(i) {
  if (!tracks.length) return;
  index = ((i % tracks.length) + tracks.length) % tracks.length;
  const t = tracks[index];
  await dsp.ensure(audio);
  $('sigCtx').textContent = `${dsp.ctx.sampleRate} Hz`;
  if (audio.src !== t.url) audio.src = t.url;
  try { await audio.play(); } catch { toast('Tap play to start audio'); }
  syncNowPlaying();
}
function toggle() {
  if (index < 0) { if (tracks.length) playIndex(0); else toast('Add some audio files first'); return; }
  if (audio.paused) { dsp.ensure(audio).then(() => audio.play()); } else audio.pause();
}
function next(auto = false) {
  if (!tracks.length) return;
  if (shuffle && tracks.length > 1 && (auto || true)) {
    let n = index;
    while (n === index) n = Math.floor(Math.random() * tracks.length);
    playIndex(n);
  } else if (index < tracks.length - 1) playIndex(index + 1);
  else if (repeat === 1 || !auto) playIndex(0);
}
function prev() {
  if (audio.currentTime > 3) { audio.currentTime = 0; return; }
  playIndex(index - 1);
}
audio.addEventListener('play', syncNowPlaying);
audio.addEventListener('pause', syncNowPlaying);
audio.addEventListener('ended', () => {
  if (repeat === 2) { audio.currentTime = 0; audio.play(); }
  else if (index < tracks.length - 1 || repeat === 1) next(true);
});
audio.addEventListener('timeupdate', () => {
  if (!scrubbing && audio.duration) {
    $('seek').value = (audio.currentTime / audio.duration) * 1000;
    paintFill($('seek'));
  }
  $('tCur').textContent = fmtT(audio.currentTime);
  $('tEnd').textContent = fmtT(audio.duration);
  const tr = tracks[index];
  if (tr && audio.duration && !tr.dur) { tr.dur = audio.duration; renderList(); }
  if (audio.duration) $('miniProg').style.width = (audio.currentTime / audio.duration * 100) + '%';
});
function syncNowPlaying() {
  const t = tracks[index];
  const playing = !audio.paused && index >= 0;
  $('miniplayer').classList.toggle('hidden', !t);
  if (t) {
    $('miniTitle').textContent = t.title; $('miniArtist').textContent = t.artist;
    $('miniArt').style.background = artFor(t.title);
    $('miniArt').textContent = t.title[0]?.toUpperCase() || '♪';
    $('ovTitle').textContent = t.title; $('ovArtist').textContent = t.artist;
    $('ovArt').style.background = artFor(t.title);
    $('ovArt').textContent = t.title[0]?.toUpperCase() || '♪';
  }
  $('miniPlay').textContent = playing ? '⏸' : '▶';
  $('cPlay').textContent = playing ? '⏸' : '▶';
  renderList();
}
$('miniPlay').addEventListener('click', (e) => { e.stopPropagation(); toggle(); });
$('miniNext').addEventListener('click', (e) => { e.stopPropagation(); next(); });
$('cPlay').addEventListener('click', toggle);
$('cNext').addEventListener('click', () => next());
$('cPrev').addEventListener('click', prev);
$('cShuf').addEventListener('click', (e) => { shuffle = !shuffle; e.target.classList.toggle('on', shuffle); });
$('cRep').addEventListener('click', (e) => {
  repeat = (repeat + 1) % 3;
  e.target.classList.toggle('on', repeat > 0);
  e.target.textContent = repeat === 2 ? '🔂' : '↻';
});
$('seek').addEventListener('input', (e) => { scrubbing = true; $('tCur').textContent = fmtT(e.target.value / 1000 * (audio.duration || 0)); });
$('seek').addEventListener('change', (e) => { if (audio.duration) audio.currentTime = e.target.value / 1000 * audio.duration; scrubbing = false; });
$('miniplayer').addEventListener('click', () => { $('overlay').classList.remove('hidden'); startSpectrum(); });
$('ovClose').addEventListener('click', () => { $('overlay').classList.add('hidden'); stopSpectrum(); });
$('ovStudio').addEventListener('click', () => {
  $('overlay').classList.add('hidden'); stopSpectrum();
  document.querySelector('.tab[data-tab="studio"]').click();
});

// ---------------- spectrum ----------------
let specOn = false;
function startSpectrum() { if (!specOn) { specOn = true; requestAnimationFrame(drawSpectrum); } }
function stopSpectrum() { specOn = false; }
function drawSpectrum() {
  if (!specOn) return;
  const c = $('spectrum'), ctx = c.getContext('2d');
  const dpr = Math.min(2, devicePixelRatio || 1);
  if (c.width !== c.clientWidth * dpr) { c.width = c.clientWidth * dpr; c.height = c.clientHeight * dpr; }
  ctx.clearRect(0, 0, c.width, c.height);
  if (dsp.ready && !audio.paused) {
    const data = new Uint8Array(dsp.analyser.frequencyBinCount);
    dsp.analyser.getByteFrequencyData(data);
    const n = 48, bw = c.width / n;
    for (let i = 0; i < n; i++) {
      const v = data[Math.floor(i / n * data.length * 0.7)] / 255;
      const h = v * c.height * 0.55;
      const g = ctx.createLinearGradient(0, c.height - h, 0, c.height);
      g.addColorStop(0, '#7C5CFF'); g.addColorStop(1, '#FA2D48');
      ctx.fillStyle = g;
      ctx.beginPath(); ctx.roundRect(i * bw + 2, c.height - h, bw - 4, h, 4); ctx.fill();
    }
  }
  requestAnimationFrame(drawSpectrum);
}

// ---------------- EQ UI ----------------
function chipRow(el, names, current, onPick) {
  el.innerHTML = '';
  for (const n of names) {
    const b = document.createElement('button');
    b.className = 'chip' + (n === current ? ' active' : '');
    b.textContent = n;
    b.addEventListener('click', () => { onPick(n); chipRow(el, names, n, onPick); });
    el.appendChild(b);
  }
}
function fmtDb(v, d = 1) { return `${v >= 0 ? '+' : ''}${v.toFixed(d)} dB`; }
function fmtFreq(f) { return f >= 1000 ? `${+(f / 1000).toFixed(1)}k` : `${Math.round(f)}`; }

function buildEq() {
  const wrap = $('eqBands');
  wrap.innerHTML = '';
  dsp.p.eq.forEach((g, i) => {
    const row = document.createElement('div');
    row.innerHTML = `<div class="slider-row"><span>${fmtFreq(EQ_FREQS[i])}</span><b></b></div>`;
    const val = row.querySelector('b');
    const input = document.createElement('input');
    input.type = 'range'; input.min = -12; input.max = 12; input.step = 0.1; input.value = g;
    val.textContent = fmtDb(g);
    input.addEventListener('input', () => {
      dsp.p.eq[i] = +input.value; val.textContent = fmtDb(+input.value);
      dsp.applyAll(); dsp.save(); drawEqGraph(); paintFill(input);
      chipRow($('eqChips'), Object.keys(EQ_PRESETS), 'Custom', applyEqPreset);
    });
    paintFill(input);
    row.appendChild(input);
    wrap.appendChild(row);
  });
}
function applyEqPreset(n) {
  dsp.p.eq = [...(EQ_PRESETS[n] || EQ_PRESETS['Flat'])];
  dsp.applyAll(); dsp.save(); buildEq(); drawEqGraph();
}
function drawEqGraph() {
  const c = $('eqGraph'); if (!c.clientWidth) return;
  const dpr = Math.min(2, devicePixelRatio || 1);
  c.width = c.clientWidth * dpr; c.height = 190 * dpr;
  const ctx = c.getContext('2d'); ctx.scale(dpr, dpr);
  const W = c.clientWidth, H = 190, pad = 14;
  const dark = document.documentElement.dataset.theme === 'dark';
  const grid = dark ? '#2C2C3A' : '#E2E2E8';
  const xFor = (f) => pad + (Math.log(f / 20) / Math.log(1000)) * (W - 2 * pad);
  const yFor = (db) => H / 2 - (db / 15) * (H / 2 - pad / 2);
  ctx.lineWidth = 1;
  ctx.strokeStyle = grid;
  for (const f of EQ_FREQS) {
    ctx.beginPath(); ctx.moveTo(xFor(f), 10); ctx.lineTo(xFor(f), H - 10); ctx.stroke();
  }
  ctx.lineWidth = 1.5;
  ctx.beginPath(); ctx.moveTo(pad, yFor(0)); ctx.lineTo(W - pad, yFor(0)); ctx.stroke();
  const resp = dsp.ready ? dsp.eqResponse(90) : analyticEq(90);
  ctx.beginPath();
  for (let i = 0; i <= 90; i++) {
    const f = 20 * Math.pow(1000, i / 90);
    const x = xFor(f), y = yFor(Math.max(-15, Math.min(15, resp[i])));
    i ? ctx.lineTo(x, y) : ctx.moveTo(x, y);
  }
  ctx.strokeStyle = '#FA2D48'; ctx.lineWidth = 3; ctx.lineJoin = 'round'; ctx.lineCap = 'round'; ctx.stroke();
  ctx.lineTo(W - pad, yFor(0)); ctx.lineTo(pad, yFor(0)); ctx.closePath();
  const g = ctx.createLinearGradient(0, 0, 0, H);
  g.addColorStop(0, 'rgba(250,45,72,.35)'); g.addColorStop(1, 'rgba(250,45,72,.02)');
  ctx.fillStyle = g; ctx.fill();
}
// Analytic peaking-EQ response (used before audio starts).
function analyticEq(steps) {
  const out = new Array(steps + 1).fill(0), sr = 48000, Q = 1.05;
  const gains = dsp.p.eqOn ? dsp.p.eq : new Array(16).fill(0);
  const pre = dsp.p.eqOn ? dsp.p.preamp : 0;
  for (let i = 0; i <= steps; i++) {
    const f = 20 * Math.pow(1000, i / steps);
    let total = pre;
    gains.forEach((G, b) => {
      if (!G) return;
      const A = Math.pow(10, G / 40), w = 2 * Math.PI * EQ_FREQS[b] / sr;
      const alpha = Math.sin(w) / (2 * Q), cw = Math.cos(w);
      const a0 = 1 + alpha / A;
      const b0 = (1 + alpha * A) / a0, b1 = (-2 * cw) / a0, b2 = (1 - alpha * A) / a0;
      const a1 = (-2 * cw) / a0, a2 = (1 - alpha / A) / a0;
      const W = 2 * Math.PI * f / sr, c = Math.cos(W), s = Math.sin(W);
      const c2 = Math.cos(2 * W), s2 = Math.sin(2 * W);
      const nR = b0 + b1 * c + b2 * c2, nI = -(b1 * s + b2 * s2);
      const dR = 1 + a1 * c + a2 * c2, dI = -(a1 * s + a2 * s2);
      total += 20 * Math.log10(Math.sqrt(nR * nR + nI * nI) / Math.max(1e-9, Math.sqrt(dR * dR + dI * dI)));
    });
    out[i] = total;
  }
  return out;
}

// ---------------- dynamics UI ----------------
const BAND_NAMES = ['LOW band', 'MID band', 'HIGH band'];
function buildDyn() {
  const wrap = $('dynBands');
  wrap.innerHTML = '';
  dsp.p.bands.forEach((b, i) => {
    const card = document.createElement('div');
    card.className = 'band-card';
    card.innerHTML = `<h4>${BAND_NAMES[i]}</h4>
      <div class="band-gate"><span>Gate <small style="color:var(--on-variant)">(noise-floor expander)</small></span>
      <label class="switch"><input type="checkbox" ${b.gateOn ? 'checked' : ''}><span></span></label></div>`;
    card.querySelector('input').addEventListener('change', (e) => {
      b.gateOn = e.target.checked; dsp.applyAll(); dsp.save();
    });
    const rows = [
      ['Gate threshold', b.gateDb, -80, -20, 1, (v) => `${Math.round(v)} dB`, (v) => b.gateDb = v],
      ['Threshold', b.thr, -60, 0, 0.5, (v) => `${v.toFixed(1)} dB`, (v) => b.thr = v],
      ['Ratio', b.ratio, 1, 20, 0.1, (v) => `${v.toFixed(1)} : 1`, (v) => b.ratio = v],
      ['Attack', b.atk * 1000, 0.1, 100, 0, (v) => `${v.toFixed(1)} ms`, (v) => b.atk = v / 1000, true],
      ['Release', b.rel * 1000, 10, 1000, 0, (v) => `${Math.round(v)} ms`, (v) => b.rel = v / 1000, true],
      ['Makeup', b.makeup, 0, 24, 0.1, (v) => `+${v.toFixed(1)} dB`, (v) => b.makeup = v],
    ];
    for (const [label, val, lo, hi, step, fmt, set, log] of rows) {
      const r = document.createElement('div');
      r.innerHTML = `<div class="slider-row"><span>${label}</span><b></b></div>`;
      const out = r.querySelector('b');
      const inp = document.createElement('input');
      inp.type = 'range'; inp.min = 0; inp.max = 1000;
      inp.value = log ? 1000 * Math.log(val / lo) / Math.log(hi / lo) : ((val - lo) / (hi - lo)) * 1000;
      out.textContent = fmt(val);
      inp.addEventListener('input', () => {
        const f = inp.value / 1000;
        const v = log ? lo * Math.pow(hi / lo, f) : lo + (hi - lo) * f;
        set(v); out.textContent = fmt(v);
        dsp.applyAll(); dsp.save(); paintFill(inp);
      });
      paintFill(inp);
      r.appendChild(inp);
      card.appendChild(r);
    }
    wrap.appendChild(card);
  });
}
function applyDynPreset(n) {
  const pr = DYN_PRESETS[n];
  dsp.p.xLow = pr.xLow; dsp.p.xHigh = pr.xHigh;
  dsp.p.bands = JSON.parse(JSON.stringify(pr.bands));
  dsp.applyAll(); dsp.save(); syncDynUI();
}
function logSlider(id, valId, lo, hi, get, set, fmt) {
  const el = $(id);
  el.min = 0; el.max = 1000;
  el.value = 1000 * Math.log(get() / lo) / Math.log(hi / lo);
  $(valId).textContent = fmt(get());
  el.oninput = () => {
    const v = lo * Math.pow(hi / lo, el.value / 1000);
    set(v); $(valId).textContent = fmt(v);
    dsp.applyAll(); dsp.save(); paintFill(el);
  };
  paintFill(el);
}
function linSlider(id, valId, get, set, fmt) {
  const el = $(id);
  el.value = get();
  $(valId).textContent = fmt(get());
  el.oninput = () => { set(+el.value); $(valId).textContent = fmt(+el.value); dsp.applyAll(); dsp.save(); paintFill(el); };
  paintFill(el);
}
function syncDynUI() {
  logSlider('xLow', 'xLowVal', 80, 800, () => dsp.p.xLow, (v) => dsp.p.xLow = v, (v) => `${Math.round(v)} Hz`);
  logSlider('xHigh', 'xHighVal', 1200, 12000, () => dsp.p.xHigh, (v) => dsp.p.xHigh = v,
    (v) => v >= 1000 ? `${(v / 1000).toFixed(1)} kHz` : `${Math.round(v)} Hz`);
  buildDyn();
}
setInterval(() => {
  if (!document.querySelector('#stab-dyn.active')) return;
  const [l, m, h] = dsp.bandReduction();
  const set = (bar, txt, v) => {
    $(bar).style.width = Math.min(100, (-v / 24) * 100) + '%';
    $(txt).textContent = v < -0.05 ? `${v.toFixed(1)} dB` : '0.0 dB';
  };
  set('mLowBar', 'mLow', l); set('mMidBar', 'mMid', m); set('mHighBar', 'mHigh', h);
  let lgr = 0;
  if (dsp.ready && dsp.p.dynOn && dsp.p.limOn) {
    const td = new Float32Array(dsp.analyser.fftSize);
    dsp.analyser.getFloatTimeDomainData(td);
    let peak = 0;
    for (const v of td) peak = Math.max(peak, Math.abs(v));
    lgr = Math.min(0, dsp.p.limCeil - 20 * Math.log10(Math.max(peak, 1e-6)));
    if (!isFinite(lgr)) lgr = 0;
  }
  set('mLimBar', 'mLim', lgr);
}, 120);

// ---------------- space UI ----------------
function syncSpaceUI() {
  const s = dsp.p.space;
  $('spaceOn').checked = s.enabled;
  const set = (id, v) => { $(id).value = v; paintFill($(id)); };
  set('spStrength', s.strength); set('spAz', s.az); set('spEl', s.el);
  set('spDist', s.dist); set('spWidth', s.width);
  set('spRoom', s.room); set('spRev', s.rev); set('spDamp', s.damp);
  spaceLabels();
  chipRow($('spaceChips'), Object.keys(SPACE_PRESETS), currentSpacePreset(), applySpacePreset);
}
function currentSpacePreset() {
  for (const [n, pr] of Object.entries(SPACE_PRESETS))
    if (JSON.stringify(pr) === JSON.stringify(dsp.p.space)) return n;
  return 'Custom';
}
function spaceLabels() {
  const s = dsp.p.space;
  $('spStrengthVal').textContent = `${Math.round(s.strength * 100)}%`;
  $('spAzVal').textContent = `${Math.round(s.az)}°`;
  $('spElVal').textContent = `${Math.round(s.el)}°`;
  $('spDistVal').textContent = `${s.dist.toFixed(1)} m`;
  $('spWidthVal').textContent = `${Math.round(s.width * 100)}%`;
  $('spRoomVal').textContent = `${Math.round(s.room * 100)}%`;
  $('spRevVal').textContent = `${Math.round(s.rev * 100)}%`;
  $('spDampVal').textContent = `${Math.round(s.damp * 100)}%`;
  $('spaceCoords').textContent = `Azimuth ${Math.round(s.az)}° · Elevation ${Math.round(s.el)}°`;
}
function applySpacePreset(n) {
  dsp.p.space = { ...SPACE_PRESETS[n] };
  dsp.applySpace(); dsp.save(); syncSpaceUI(); drawPad();
}
function bindSpace() {
  const s = () => dsp.p.space;
  const upd = () => { dsp.applySpace(); dsp.save(); spaceLabels(); drawPad(); };
  $('spaceOn').onchange = (e) => { s().enabled = e.target.checked; upd(); syncSpaceUI(); };
  const B = (id, k) => { $(id).oninput = (e) => { s()[k] = +e.target.value; upd(); paintFill(e.target); }; };
  B('spStrength', 'strength'); B('spAz', 'az'); B('spEl', 'el');
  B('spDist', 'dist'); B('spWidth', 'width');
  B('spRoom', 'room'); B('spRev', 'rev'); B('spDamp', 'damp');
}
function drawPad() {
  const c = $('spacePad'); if (!c || !c.clientWidth) return;
  const dpr = Math.min(2, devicePixelRatio || 1);
  c.width = c.clientWidth * dpr; c.height = c.clientWidth / 1.5 * dpr;
  const ctx = c.getContext('2d'); ctx.scale(dpr, dpr);
  const W = c.clientWidth, H = c.clientWidth / 1.5;
  const dark = document.documentElement.dataset.theme === 'dark';
  const grid = dark ? '#2C2C3A' : '#E2E2E8';
  const fg = dark ? '#F5F5F7' : '#1D1D1F';
  const el0 = H * (90 / 130);
  ctx.strokeStyle = grid; ctx.lineWidth = 1;
  ctx.beginPath(); ctx.moveTo(W / 2, 0); ctx.lineTo(W / 2, H); ctx.stroke();
  ctx.beginPath(); ctx.moveTo(0, el0); ctx.lineTo(W, el0); ctx.stroke();
  // head
  ctx.strokeStyle = fg; ctx.lineWidth = 2;
  ctx.beginPath(); ctx.arc(W / 2, el0, 26, 0, 7); ctx.stroke();
  ctx.lineWidth = 3; ctx.lineCap = 'round';
  ctx.beginPath(); ctx.moveTo(W / 2, el0 - 26); ctx.lineTo(W / 2, el0 - 38); ctx.stroke();
  ctx.fillStyle = dark ? '#A7A7B8' : '#6E6E73';
  ctx.font = '600 10px system-ui'; ctx.textAlign = 'center';
  ctx.fillText('FRONT', W / 2, 20);
  // source
  const { az, el } = dsp.p.space;
  const sx = ((az + 180) / 360) * W, sy = ((90 - el) / 130) * H;
  ctx.strokeStyle = 'rgba(250,45,72,.5)'; ctx.lineWidth = 1.5;
  ctx.beginPath(); ctx.moveTo(W / 2, el0); ctx.lineTo(sx, sy); ctx.stroke();
  ctx.fillStyle = 'rgba(250,45,72,.25)';
  ctx.beginPath(); ctx.arc(sx, sy, 22, 0, 7); ctx.fill();
  ctx.fillStyle = '#FA2D48';
  ctx.beginPath(); ctx.arc(sx, sy, 10, 0, 7); ctx.fill();
  ctx.fillStyle = '#fff';
  ctx.beginPath(); ctx.arc(sx, sy, 4, 0, 7); ctx.fill();
}
function bindPad() {
  const c = $('spacePad');
  let drag = false;
  const set = (e) => {
    const r = c.getBoundingClientRect();
    const az = Math.max(-180, Math.min(180, ((e.clientX - r.left) / r.width) * 360 - 180));
    const el = Math.max(-40, Math.min(90, 90 - ((e.clientY - r.top) / r.height) * 130));
    dsp.p.space.az = az; dsp.p.space.el = el;
    $('spAz').value = az; $('spEl').value = el;
    paintFill($('spAz')); paintFill($('spEl'));
    dsp.applySpace(); dsp.save(); spaceLabels(); drawPad();
  };
  c.addEventListener('pointerdown', (e) => { drag = true; c.setPointerCapture(e.pointerId); set(e); });
  c.addEventListener('pointermove', (e) => drag && set(e));
  c.addEventListener('pointerup', () => drag = false);
}

// ---------------- settings ----------------
function bindSettings() {
  const saved = localStorage.getItem('ceecept-theme') || 'dark';
  document.documentElement.dataset.theme = saved;
  document.querySelectorAll('[data-theme-pick]').forEach((b) => {
    b.classList.toggle('active', b.dataset.themePick === saved);
    b.addEventListener('click', () => {
      document.documentElement.dataset.theme = b.dataset.themePick;
      localStorage.setItem('ceecept-theme', b.dataset.themePick);
      document.querySelectorAll('[data-theme-pick]').forEach((x) => x.classList.toggle('active', x === b));
      drawEqGraph(); drawPad();
    });
  });
  $('resetStudio').addEventListener('click', () => {
    localStorage.removeItem('ceecept-dsp');
    location.reload();
  });
}

// ---------------- init ----------------
function init() {
  renderList();
  buildEq();
  chipRow($('eqChips'), Object.keys(EQ_PRESETS), 'Flat', applyEqPreset);
  $('eqOn').checked = dsp.p.eqOn;
  $('eqOn').onchange = (e) => { dsp.p.eqOn = e.target.checked; dsp.applyAll(); dsp.save(); drawEqGraph(); };
  linSlider('preamp', 'preampVal', () => dsp.p.preamp, (v) => dsp.p.preamp = v, (v) => fmtDb(v));

  $('dynOn').checked = dsp.p.dynOn;
  $('dynOn').onchange = (e) => { dsp.p.dynOn = e.target.checked; dsp.applyAll(); dsp.save(); };
  chipRow($('dynChips'), Object.keys(DYN_PRESETS), 'Transparent', (n) => {
    applyDynPreset(n);
    chipRow($('dynChips'), Object.keys(DYN_PRESETS), n, applyDynPreset);
  });
  $('limOn').checked = dsp.p.limOn;
  $('limOn').onchange = (e) => { dsp.p.limOn = e.target.checked; dsp.applyAll(); dsp.save(); };
  linSlider('limCeil', 'limCeilVal', () => dsp.p.limCeil, (v) => dsp.p.limCeil = v, (v) => `${v.toFixed(1)} dB`);
  logSlider('limRel', 'limRelVal', 10, 500, () => dsp.p.limRel, (v) => dsp.p.limRel = v, (v) => `${Math.round(v)} ms`);
  linSlider('dynOut', 'dynOutVal', () => dsp.p.dynOut, (v) => dsp.p.dynOut = v, (v) => fmtDb(v));
  syncDynUI();

  syncSpaceUI();
  bindSpace(); bindPad(); bindSettings();
  requestAnimationFrame(() => { drawEqGraph(); drawPad(); });
  window.addEventListener('resize', () => { drawEqGraph(); drawPad(); });
}
init();
