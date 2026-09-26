# Ceecept Immerse 2.0 — implementation notes

This is the map between the spatial-audio guidelines (`document_pages_1-75.txt`) and the
code that implements them, plus every place where the implementation deliberately
departs from the document and why.

Everything lives in `app/src/main/java/com/ceecept/music/audio/spatial/`.

## Signal path

```
decoder ─▶ EqualizerProcessor ─▶ DynamicsProcessor ─▶ SpatializerProcessor ─▶ FloatToPcm16 ─▶ AudioTrack
                                                            │
                                                            ▼
                                                   SpatialAudioEngine
   stereo in
      │
      ├─ propagation delay (distance → Doppler, §8.2)
      ├─ mid / side decomposition
      ├─ side split into 6 bands, LR4 crossover tree (§9.2, §12)
      │
      ├─ 13 audio objects  ─ pairwise/VBAP panning (§4) ─▶ 12 virtual speakers (§2.2)
      │     · 1 centre object (mid)                          + bass management → LFE
      │     · 6 band pairs (side ±), width per band (§12.1)  + early reflections (§7.3)
      │
      ├─ fold to 2 channels: parametric HRTF (§6) or amplitude fold-down
      ├─ virtual height EQ when the layout has no height layer (§5.2)
      ├─ 8-line modulated FDN late reverb, T60 from distance + room (§7.3)
      ├─ air absorption low-pass, cutoff from distance (§7.2)
      └─ soft limiter (§11.1) ─▶ 2x-oversampled safety clip (§10.1)
```

## Section-by-section map

| Guideline | Implementation |
|---|---|
| §2.2 Speaker configurations | `SpeakerLayouts.kt` — stereo, 5.1, 7.1.4, binaural, transcribed verbatim |
| §2.3 Audio object definition | `SpatialAudioEngine` object arrays (azimuth/elevation/distance/diffuse) |
| §3.1 Spherical ↔ cartesian | `Geometry.sphericalToCartesian` / `cartesianToSpherical` |
| §3.2 Listener reference frame | `ListenerPose` — position + yaw/pitch/roll rotation matrices |
| §4.1 Adaptive rendering | `Panning.adaptiveRender` (+ `coneFor`, see deviations) |
| §4.2 Vector panning law | `Panning.vectorPanning`, `Panning.blended` |
| §5.1 Height speaker routing | `PairwisePanner` layer crossfade, `Elevation.heightLayerGain` |
| §5.2 Virtual height | `VirtualHeightFilter` — 4 kHz peak + 100 Hz shelf |
| §6 Binaural / HRTF | `BinauralVirtualizer` — ITD, frequency-dependent ILD, pinna coloration |
| §7.1 Inverse square law | `DistanceModel.attenuationDb` |
| §7.2 Air absorption | `DistanceModel.airAbsorptionCutoffHz` + cascaded low-pass in the engine |
| §7.3 Reflections and reverb | `DistanceModel.reverbWet/earlyReflectionDelayMs/decayTimeSec`, 12-tap ER + 8-line FDN |
| §8.1 Keyframe trajectories | `ObjectTrajectory` + `CubicSpline` (natural cubic, azimuth unwrapped) |
| §8.2 Doppler | `Doppler` — ratio/cents maths + propagation-delay implementation |
| §9.2 Crossovers | `LinkwitzRileySplitter` — LR4 tree with all-pass compensation |
| §10.1 Anti-aliasing | `Oversampler2x` — 31-tap polyphase half-band around the saturator |
| §10.2 Gain smoothing | `SmoothedGain`, `SmoothedGainBank` (5 ms), `GainEnvelope.smooth` |
| §11.1 Render pipeline | `SpatialAudioEngine.process` |
| §11.2 Capability detection | `OutputRouteDetector`, `PlaybackCapabilities`, `RenderStrategy.select` |
| §12.1 Per-band spatial params | `SpatialBands.TABLE` — the guideline's six bands, verbatim |
| §14 QA metrics | `tools/spatial-qa/qa.js` |

## Deviations, and the reason for each

1. **Pairwise (VBAP) panning is the primary law.** §4.1 and §4.2 both distribute energy
   to every speaker within a 90° cone. Measured with the Gerzon velocity vector, the
   §4.1 law places a 7.1.4 source up to **19°** away from where it was asked to go, and
   §14.1 requires better than 5°. `PairwisePanner` keeps the guideline's geometry but
   drives only the two speakers bracketing the object, with the tangent law between
   them: measured error is **0.000°** on both 5.1 and 7.1.4. The guideline's two laws
   are still implemented, still used (early reflections, diffusion) and are the
   reference the QA harness compares against.

2. **The 90° assignment cone is widened on sparse layouts.** With speakers 180° apart
   (the guideline's own `stereo` config), a fixed 90° cone silences everything within
   90° of the far speaker — an object 6° left of centre ends up entirely in the left
   channel. `Panning.coneFor` widens the cone to just over the largest gap between
   adjacent speakers; 5.1 and 7.1.4 keep the guideline's 90°.

3. **Parametric HRTF instead of a measured database.** §6.1 convolves CIPIC/MIT-KEMAR
   impulse responses quantised to 15°. Ceecept ships no dataset (licensing, APK size)
   and 15° quantisation steps audibly during panning. The virtualiser builds the three
   mechanisms §6.2 describes — ITD `(head_width/c)·sin(az)`, frequency-dependent ILD as
   a high shelf on the contralateral ear, and pinna/concha coloration — evaluated
   continuously. It is applied to the *fixed* virtual speakers, so HRTF cost is constant
   instead of growing with object count.

4. **Doppler by propagation delay, not by phase vocoder.** §8.2 calls
   `librosa.effects.pitch_shift`, which is offline and latency-heavy. Distance drives a
   fractional delay line instead; the changing delay *is* the Doppler shift, exactly
   `f' = f/(1-v/c)`, with no vocoder smearing and no added latency.

5. **Oversampling only around the non-linearity.** §10.1 suggests 4–8x for the whole
   chain. Every stage here except the output saturator is linear and cannot alias, so
   2x half-band oversampling wraps just the saturator — and the limiter runs *before*
   it, so the saturator only ever sees limited signal. Measured alias suppression at
   0 dBFS: **53 dB** better than clipping at base rate, for ~28 multiply-accumulates
   per sample instead of oversampling everything.

6. **Gain smoothing is a one-pole, not a moving average.** §10.2 convolves an offline
   automation curve with a 5 ms box kernel. The real-time equivalent is a one-pole with
   the same time constant: identical maximum step per sample (0.00416 vs the box
   kernel's 0.00417), no lookahead, no buffer.

7. **Virtual-height EQ is de-pedestalled.** The §5.2 formula is
   `3 + (elevation/30)*6` dB, which colours *every* object by +3 dB at 4 kHz even at ear
   level and breaks the ±2 dB flatness criterion of §14.1. The pedestal is removed so
   0° is exactly 0 dB, and the curve is clamped to the ±9 dB the document describes.

8. **Sample-accurate limiter.** §11.1 computes one gain per block from its peak, which
   modulates level block-to-block (audible pumping). Same -0.1 dBFS ceiling, but with an
   instantaneous-attack / 50 ms-release follower so the gain is continuous.

9. **Objects are derived from the stereo programme.** The guideline assumes authored
   object stems. A music player gets a finished stereo mix, so the renderer decomposes
   it: the mid signal is the centre object, and the side signal is split into the six
   §12.1 bands, each contributing a left/right object pair whose angular offset is that
   band's `spatial_width`. Bass bands therefore collapse to the centre and stay mono
   (the guideline's `diffuse` flag), treble spreads widest.

## QA

`node tools/spatial-qa/qa.js` — a JavaScript port of the numeric kernels, measuring the
§14.1 criteria. Current state: **17/17 pass**.

```
azimuth error · 5.1_surround        max 0.000° (limit 5°)
azimuth error · 7.1.4_immersive     max 0.000° (limit 5°)
panning continuity                  power 1.0000–1.0000, no dead spots
elevation crossfade                 monotonic, 100% height at 60°, power preserved
virtual height neutral at 0°        0.00 dB
crossover flatness (6 bands)        0.000 dB ripple, 30 Hz–20 kHz (limit 2 dB)
oversampler passband                -0.02 dB at 16 kHz
anti-alias (saturator)              53.2 dB better than base-rate clipping
gain smoothing (zipper)             0.00416 max step, 95% in 15 ms
ITD / ILD                           0.583 ms at 90°, 8.5 dB shelf at 45°
distance cues                       monotonic level / cutoff / wet
doppler                             +182 cents at +34.3 m/s
trajectory spline                   knot-exact, C1, 0.2% overshoot
```

## Cost

Per stereo sample at 48 kHz, 7.1.4 + binaural (the heaviest path):

| Stage | Approx. work |
|---|---|
| LR4 band split (side) | 30 biquads |
| Object → speaker routing | 13 objects × 12 gains, smoothed per sample |
| Early reflections | 12 delay taps, 2 speakers each |
| HRTF virtualiser | 12 delay lines + 72 biquads |
| FDN reverb | 8 modulated delay lines + 8 one-poles |
| Oversampled saturator | ~56 MACs (both channels) |
