# Ceecept — Offline Music Player for Android

**Ceecept** is an offline-first Android music player with an Apple Music-inspired interface and a
studio-grade DSP engine: a **16-band parametric EQ**, a **3-band compressor with gates** plus a
**lookahead brick-wall limiter**, and **Ceecept Immerse 3.0** — an object-based 3D audio renderer with instrument separation
built to the spatial-audio guidelines in [`document_pages_1-75.txt`](document_pages_1-75.txt).

No account. No ads. No internet permission — your music never leaves your phone.

## Get the APK

- **Tagged releases** (recommended): open the repo's [**Releases**](https://github.com/calvinsekgobela70-a11y/music-player-/releases)
  page and download `Ceecept-vX.Y.Z-debug.apk`, then install it on your phone.
- **Latest CI build**: every push builds an APK automatically — see
  [Actions → Build Ceecept APK](https://github.com/calvinsekgobela70-a11y/music-player-/actions/workflows/build-apk.yml)
  → newest run → **Artifacts → Ceecept-debug-apk**.

> The APK is a debug build, so Android will show the usual "unknown app" prompt on install. Tap
> *Install anyway* — the app requests only audio-file access and requests no network permission.
> When updating from one debug release to another, **uninstall the old version first**
> (each CI build is signed with a fresh debug key, so Android will otherwise block the update
> with an "app not installed" signature error).

## Features

| Area | Details |
|---|---|
| Formats | MP3, WAV, FLAC, OGG/Vorbis, Opus, M4A/AAC, … anything ExoPlayer + your device decodes (incl. 24-bit) |
| Offline | 100% offline: library from MediaStore, no network permission at all |
| Library | Songs / Artists / Albums, instant search, album art, play & shuffle actions |
| Playback | Background service, notification + headset/Bluetooth controls, shuffle, repeat, seek |
| Interface | Apple Music-style dark/light themes, Inter typeface (SF-spirited, bundled offline), spring-physics 60 fps motion, every button with its own press animation, seamless screen transitions |
| Equalizer | 16 parametric bands (31 Hz – 16 kHz), preamp, live response graph, 14 presets |
| Dynamics | Linkwitz-Riley crossover, per-band gate (expander) + soft-knee compressor + makeup, live gain-reduction meters, 5 ms lookahead brick-wall limiter |
| Instrument separation | Every time-frequency bin of the mix is classified by panning index, inter-channel coherence and harmonic/percussive structure, and assigned to one of **14 streams** — bass, lead vocal, centre, drums (centre/L/R), instruments (L/R), pads (L/R), room (L/R) and overhead air. Each becomes its own object at its **measured** position |
| Per-stream enhancement | Missing-fundamental bass synthesis, vocal presence + de-esser, drum transient shaping, pad/room decorrelation — each applied only to the part of the mix it belongs to |
| Clean gain staging | Energy-preserving masks, 300 ms loudness matching, look-ahead limiter with 25 ms hold (**−302 dB THD** on bass vs −31.5 dB for an instantaneous limiter) |
| Immerse 3D | **Object-based renderer**: 13 audio objects panned onto a virtual 7.1.4 / 5.1 / stereo rig with pairwise (VBAP) placement, then folded to two channels through a parametric HRTF. Per-band spatial widths (bass centred and mono, treble widest), height layer with virtual-height EQ fallback, distance model (inverse square + air absorption + reverb ratio), keyframed orbit with real Doppler, automatic output-route detection, 9 presets |
| Spatial QA | `node tools/spatial-qa/qa.js` measures the guideline's §14 criteria — 25/25 pass (0.000° azimuth error, masks sum to 1.000000, separation correlation 0.99+, 53 dB alias suppression) |

## Try it in your browser (preview)

The `preview/` folder is a dependency-free web prototype with the same design and a real Web Audio
implementation of the EQ / dynamics / HRTF chain. Open `preview/index.html` in any browser, drop in
some audio files, and play — fully offline. (It's a demo of the DSP + UX; the real app is the APK.)

## Build it yourself

Requirements: JDK 17 + Android SDK (API 34). Then:

```bash
./gradlew :app:assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio (Koala or newer) and press Run.

## Architecture

```
app/src/main/java/com/ceecept/music/
├── audio/
│   ├── Dsp.kt                    # biquads (incl. shelves/all-pass), delay lines, envelopes
│   ├── EqualizerProcessor.kt     # 16-band parametric EQ (Media3 AudioProcessor)
│   ├── DynamicsProcessor.kt      # 3-band comp + gates + lookahead limiter
│   ├── SpatializerProcessor.kt   # Media3 plumbing for the spatial renderer
│   ├── spatial/                  # Immerse 3.0 — see docs/SPATIAL_ENGINE.md
│   │   ├── Fft.kt                # radix-2 FFT, two-real packing, WOLA windows
│   │   ├── StemSeparator.kt      # 14-stream blind separation of the mix
│   │   ├── StreamEnhancers.kt    # virtual bass, vocal presence, transients, decorrelation
│   │   ├── Geometry.kt           # spherical/cartesian, listener pose        (§3)
│   │   ├── SpeakerLayouts.kt     # stereo / 5.1 / 7.1.4 / binaural, strategy (§2.2, §11.2)
│   │   ├── Panning.kt            # adaptive + vector laws, pairwise VBAP     (§4)
│   │   ├── Elevation.kt          # height routing, virtual-height EQ         (§5)
│   │   ├── Binaural.kt           # parametric HRTF, speaker fold-down        (§6)
│   │   ├── DistanceModel.kt      # inverse square, air absorption, reverb    (§7)
│   │   ├── Trajectory.kt         # cubic-spline keyframes, Doppler           (§8)
│   │   ├── Multiband.kt          # LR4 crossover tree, per-band widths       (§9.2, §12)
│   │   ├── Artifacts.kt          # gain smoothing, oversampler, limiter      (§10, §11.1)
│   │   ├── OutputRoute.kt        # Android playback-capability detection     (§11.2)
│   │   └── SpatialAudioEngine.kt # the render pipeline                       (§11.1)
│   ├── AudioEngine.kt            # state, presets, route detection, DataStore
│   └── CeeceptRenderersFactory.kt# injects DSP chain into ExoPlayer (float)
├── data/                         # MediaStore library + artwork cache
├── playback/                     # MediaSessionService + MediaController bridge
└── ui/                           # Compose: theme, components, screens, nav
```

The signal path is 32-bit float end to end:
`decoder → EQ → Dynamics/Limiter → Immerse 3D → AudioTrack`.

## License

MIT — see [LICENSE](LICENSE).
