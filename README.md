# Ceecept — Offline Music Player for Android

**Ceecept** is an offline-first Android music player with an Apple Music-inspired interface and a
studio-grade DSP engine: a **16-band parametric EQ**, a **3-band compressor with gates** plus a
**lookahead brick-wall limiter**, and **Ceecept Immerse** — HRTF-based 3D audio for headphones.

No account. No ads. No internet permission — your music never leaves your phone.

## Get the APK

- **Tagged releases** (recommended): open the repo's [**Releases**](https://github.com/calvinsekgobela70-a11y/music-player-/releases)
  page and download `Ceecept-vX.Y.Z-debug.apk`, then install it on your phone.
- **Latest CI build**: every push builds an APK automatically — see
  [Actions → Build Ceecept APK](https://github.com/calvinsekgobela70-a11y/music-player-/actions/workflows/build-apk.yml)
  → newest run → **Artifacts → Ceecept-debug-apk**.

> The APK is a debug build, so Android will show the usual "unknown app" prompt on install. Tap
> *Install anyway* — the app requests only audio-file access and requests no network permission.

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
| Immerse 3D | HRTF virtual speakers (Woodworth ITD + ILD shadowing + pinna filtering), 12-tap early reflections, 8-line modulated FDN reverb, draggable 3D sound pad (azimuth/elevation), distance/width/room controls, 7 presets |

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
│   ├── Dsp.kt                    # biquads, delay lines, envelopes, dB math
│   ├── EqualizerProcessor.kt     # 16-band parametric EQ (Media3 AudioProcessor)
│   ├── DynamicsProcessor.kt      # 3-band comp + gates + lookahead limiter
│   ├── SpatializerProcessor.kt   # HRTF 3D: ITD/ILD/pinna + early + FDN late
│   ├── AudioEngine.kt            # state, presets, DataStore persistence
│   └── CeeceptRenderersFactory.kt# injects DSP chain into ExoPlayer (float)
├── data/                         # MediaStore library + artwork cache
├── playback/                     # MediaSessionService + MediaController bridge
└── ui/                           # Compose: theme, components, screens, nav
```

The signal path is 32-bit float end to end:
`decoder → EQ → Dynamics/Limiter → Immerse 3D → AudioTrack`.

## License

MIT — see [LICENSE](LICENSE).
