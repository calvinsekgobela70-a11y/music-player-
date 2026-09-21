/* Ceecept lookahead brick-wall limiter (AudioWorklet). */
class CeeceptLimiter extends AudioWorkletProcessor {
  constructor() {
    super();
    this.sr = sampleRate;
    this.maxLook = Math.floor(0.012 * sampleRate) + 64;
    this.buf = [new Float32Array(this.maxLook), new Float32Array(this.maxLook)];
    this.w = 0;
    this.look = Math.floor(0.005 * sampleRate);
    this.ceil = Math.pow(10, -1 / 20);
    this.relCoef = 1 - Math.exp(-1 / (0.080 * sampleRate));
    this.atkCoef = 1 - Math.exp(-1 / (0.00005 * sampleRate));
    this.env = 0;
    this.gain = 1;
    this.enabled = true;
    this.gr = 0;
    this.port.onmessage = (e) => {
      const m = e.data || {};
      if (m.ceilingDb !== undefined) this.ceil = Math.pow(10, m.ceilingDb / 20);
      if (m.releaseMs !== undefined) this.relCoef = 1 - Math.exp(-1 / ((m.releaseMs / 1000) * sampleRate));
      if (m.enabled !== undefined) this.enabled = !!m.enabled;
    };
  }
  process(inputs, outputs) {
    const inp = inputs[0], out = outputs[0];
    if (!inp || !inp.length) return true;
    const nCh = Math.min(inp.length, out.length, 2);
    const N = inp[0].length;
    for (let i = 0; i < N; i++) {
      let peak = 0;
      for (let c = 0; c < nCh; c++) {
        const a = Math.abs(inp[c][i]);
        if (a > peak) peak = a;
      }
      const coef = peak > this.env ? this.atkCoef : this.relCoef;
      this.env += coef * (peak - this.env);
      let target = 1;
      if (this.enabled && this.env > this.ceil && this.env > 1e-6) target = this.ceil / this.env;
      const gCoef = target < this.gain ? 0.5 : this.relCoef * 0.25 + 0.001;
      this.gain += gCoef * (target - this.gain);
      for (let c = 0; c < nCh; c++) {
        const b = this.buf[c];
        b[this.w] = inp[c] ? inp[c][i] : 0;
        let r = this.w - this.look;
        if (r < 0) r += this.maxLook;
        out[c][i] = b[r] * this.gain;
      }
      this.w++;
      if (this.w >= this.maxLook) this.w = 0;
    }
    this.gr = 20 * Math.log10(Math.max(this.gain, 1e-6));
    return true;
  }
}
registerProcessor('ceecept-limiter', CeeceptLimiter);
