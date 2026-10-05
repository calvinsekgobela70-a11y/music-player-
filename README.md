# Ceecept — Offline Music Player for Android

**Ceecept** is an offline-first Android music player with an Apple Music-inspired interface and a
studio-grade DSP engine: a **16-band parametric EQ**, a **3-band compressor with gates** plus a
**lookahead brick-wall limiter**, and **Ceecept Immerse 3.2** — an object-based 3D audio renderer with instrument separation
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

## v2.0.2 true crossfade, lyrics timing and artwork-everywhere pass

This is the first build after the v2.0.1 compatibility checkpoint that contains the requested major feature work:

- **True crossfade/fade deck:** playback service now prepares a second local ExoPlayer deck for the next queued song, fades it in with an equal-power curve, fades the session player out, then hands the official MediaSession back to the next track at the same position. Notification/headset controls remain attached to the session player.
- **DJ Mode uses overlap, not just a filter:** AutoMix still uses BPM/key/energy analysis for queue ordering and cue selection, but now the audible handoff can overlap two decoded songs. An old single-player phrase skip remains only as a fallback if crossfade is disabled.
- **User-tunable fade length:** Settings now exposes Crossfade / DJ deck overlap plus 6/10/14/20 second fade-length choices. DJ mode can stretch the overlap to phrase-sized windows when analysis is available.
- **More accurate lyric timing:** local lyrics now support `.lrc`, enhanced LRC word timestamps, repeated timestamps, `[offset:+/-ms]`, `.srt`, `.vtt`, cue end times, deduping and line-duration estimates. The lyrics page adds per-song sync controls: Later, Earlier and Reset.
- **Artwork on every library page:** playlists, folders, artists, genres, composers, years and detail headers now use real/generative cover art instead of generic icons, so artwork is not limited to Now Playing.
- **Button audit fixes:** non-action informational cards are no longer clickable, shuffle detail buttons no longer accidentally pause after starting playback, and the existing ratings framework is now visible in the Memory page as a 5-star offline rating control.

## v2.0.1 compatibility checkpoint

- Same code as v1.0.20 plus a version/README bump, published as a compatibility checkpoint before the full requested v2 feature pass.

## v1.0.20 v1.0.14 lyrics style, uploaded-app-style lyric scanner and playback crash guard

- Same fixes as v2.0.1, originally published as a compatibility checkpoint before the requested major-version tag.

## v1.0.19 visible lyrics and audible AutoMix follow-up

This build addresses the “nothing changed” feedback directly:

- **Lyrics visibly move even without LRC timestamps:** plain `.txt` lyrics are now automatically time-distributed across the song duration, so the page still scrolls line-by-line like Apple Music while real `.lrc` files keep exact timestamps.
- **Bigger reference-style lyrics:** active lyrics are larger again, with oversized dim upcoming lines so the screen resembles the supplied Apple Music reference more clearly.
- **Audible DJ handoff tail:** AutoMix now preserves the outgoing beat-synced echo tail across ExoPlayer’s next-track flush, so the next song starts under a DJ tail instead of sounding like a hard unchanged skip.

## v1.0.18 lyrics, DJ handoff and crash fix

This build focuses on what should be visible and audible on the phone:

- **Apple Music-style synced lyrics:** the lyrics page now matches the supplied reference more closely: top glass now-playing strip, artwork/title/artist, large glassy bold lyric typography, bright active line, dim oversized surrounding lines, art-derived dark backdrop, smoother line progression, and stable timed-line auto-scroll/seek.
- **DJ Mode handoff:** DJ Mode now performs stronger phrase-window handoffs instead of subtle edge high-pass only. Weak-analysis tracks use a real medium blend fallback, outgoing transitions are longer/stronger with echo/ducking, and the player auto-advances at the transition peak so the next song starts as part of the mix rather than after dead end silence.
- **Recovered ExoPlayer.error(1004):** `SpatializerProcessor` now consumes frame-exact buffers and safely drops partial decoder/flush bytes on EMUI, preventing the `ByteBuffer.put` crash seen on Huawei JNY-LX1.

## v1.0.17 focused fix

This build focuses only on the two regressions reported after v1.0.16:

- **Music-list artwork:** the library rows now ask the repository for real embedded/folder/MediaStore artwork only, while the Compose tile draws its own generated fallback. This prevents generated fallback art from being treated as a successful load and blocking the real album cover from appearing in the song list.
- **Timed lyrics:** the lyrics page now builds timing from only visible lyric lines, ignores blank timestamp lines for active-line matching, uses stable timed-line keys, removes the negative scroll offset that caused jumpy/wrong positioning, and lets timed lyric lines seek to their timestamp.

## v1.0.16 deep-fix pass

This build specifically revisits the issues that remained after v1.0.15:

- **AutoMix / DJ Mode:** analysis is now cancellation-safe and generation-scoped, starts after playback settles, analyzes a smaller near-queue window first, yields much longer between MediaCodec jobs while music is playing, and never caches cancelled fallback analysis. This keeps the full AutoMix feature active while avoiding the Huawei/EMUI decoder/CPU contention that made the app lag or jam when DJ Mode was switched on.
- **Timed lyrics:** lyrics now open as their own full-screen Apple Music-style surface instead of a generic sheet, with centered automatic scrolling, larger active-line typography, art-derived animated background, inline progress scrubbing, like and transport controls.
- **Library artwork:** song rows no longer expose the music-note placeholder while artwork loading/retry is running. They immediately show deterministic generated rounded-square covers and then crossfade to real embedded/folder/MediaStore artwork when available.
- **Notification / lock screen:** Media3 is upgraded to the 1.9.x session stack, MediaSession metadata is richer, the notification channel/provider is named explicitly, artwork bytes are still injected into the current MediaItem, and previous/next player-command button preferences are advertised to controllers for cleaner Huawei/System UI rendering.

## Features

| Area | Details |
|---|---|
| Formats | MP3, WAV, FLAC, OGG/Vorbis, Opus, M4A/AAC, … anything ExoPlayer + your device decodes (incl. 24-bit) |
| Offline | 100% offline: library from MediaStore, no network permission at all |
| Library | Songs / Liked / Playlists / Artists / Albums / Folders / Genres / Composers / Years, instant search, stronger Huawei-safe album-art recovery with retry / same-file / fuzzy-folder / sibling fallbacks plus generated offline covers when files truly have no art, continue listening, playlist creation/deletion, add-to-playlist, folder play, local bookmarks, full rescan, and sort by title/artist/album/date/recent/plays/duration/year |
| Playback | Huawei-hardened background service, stronger Media3 foreground/codec mode, a dedicated MediaStyle notification channel/icon, richer lock-screen/notification metadata with artwork URI plus embedded local artwork bytes, notification + headset/Bluetooth controls, gapless prebuffering, true service-level equal-power crossfade with a second prepared deck, sleep timer, shuffle, repeat, seek, remembered session state, and official Ceecept DJ Mode / Automix |
| Interface | One-page no-scroll Now Playing with separate full-screen Lyrics / Queue / Info / Memory pages, visible offline 5-star ratings in Memory, Apple Music-style adaptive synced lyrics with auto-scrolling timed LRC/SRT/VTT, modern glossy playback controls, Apple Music-like fixed-square artwork across songs/playlists/folders/artists/genres/composers/years, imported MilkDrop preset library with a faster brighter adaptive visualizer/backdrop, Inter typeface (SF-spirited, bundled offline), spring-physics motion and seamless screen transitions |
| Lyrics / Visuals | Uploaded-app-style local lyrics matching from sidecar `.lrc`/`.txt`/`.srt`/`.vtt` files, enhanced LRC word-time cleanup, `[offset:+/-ms]` handling, per-song manual timing offset controls, separate Apple Music-style synced lyrics page with compact track header, auto-scroll/tap-to-seek, and GPU-backed scale/alpha motion, plus 252 imported MilkDrop presets available offline |
| Equalizer | 16 bands (31 Hz – 16 kHz), per-band Q from centre spacing, musical proportional-Q or precision constant-Q, shelf end-bands, subsonic filter, auto headroom, live response graph, and uploaded-app AutoEQ headphone preset import/search |
| Tone / DVC polish | Ceecept clean-room phone-output polish stage: DVC-style headroom, bass/treble shelves, stereo width, crossfeed, short room send and warm drive presets — no protected uploaded-app DSP is linked or copied |
| Dynamics | Phase-corrected 3-band crossover, per-band gate, hybrid RMS/peak soft-knee compressor, auto make-up, program release, live gain-reduction meters and lookahead limiter |
| Instrument separation | Every time-frequency bin of the mix is classified by panning index, inter-channel coherence and harmonic/percussive structure, and assigned to one of **14 streams** — bass, lead vocal, centre, drums (centre/L/R), instruments (L/R), pads (L/R), room (L/R) and overhead air. Each becomes its own object at its **measured** position |
| Per-stream enhancement | Rebuilt bass path (sub shelf + stronger 285 Hz box-cut + controlled missing fundamental), vocal presence/exciter/de-esser, drum transient shaping, louder pads, room/air decorrelation, subtle analogue warmth |
| Clean gain staging | Energy-preserving masks, 300 ms loudness matching, look-ahead limiter with 25 ms hold (**−302 dB THD** on bass vs −31.5 dB for an instantaneous limiter), plus no-op float pass-throughs for inactive/flat EQ, tone, dynamics, spatial and DJ stages to reduce Huawei CPU load without removing capabilities |
| Immerse 3D | **Object-based renderer**: 14 separated objects panned onto a virtual 7.1.4 / 5.1 / stereo rig with pairwise (VBAP) placement, then folded to two channels through a parametric HRTF. Imaging focus uses more dramatic measured panning and stronger localisation cues, height/distance/reverb are modelled, and the rig can be pinned to 7.1.4 |
| Ceecept DJ Mode | Local/offline Automix rebuilt from `AUTOMIX.docx`: non-blocking queue-priority analysis for BPM/tempo stability, beat/downbeat timing, key/Camelot, loudness, low/mid/high energy, intro/outro/last-vocal-safe cue estimates and mixability; upcoming tracks reorder progressively by tempo/key/energy/loudness without freezing playback; transition decision matrix selects long/medium/short/drop/echo/simple styles with phrase-locked cue points, EQ/bass-removal sweeps, vocal-range ducking, -3 dB overlap compensation, beat-synced echo tails and a true two-deck equal-power crossfade for audible seamless handoffs |
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
│   ├── PowerampDspProcessor.kt   # uploaded-app-inspired tone/DVC/stereo/room stage
│   ├── DynamicsProcessor.kt      # 3-band comp + gates + lookahead limiter
│   ├── SpatializerProcessor.kt   # Media3 plumbing for the spatial renderer
│   ├── DjTransitionProcessor.kt  # DJ Mode transition filter/echo processor
│   ├── spatial/                  # Immerse 3.1 — see docs/SPATIAL_ENGINE.md
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
├── data/                         # MediaStore library, Huawei artwork recovery, likes/ratings/bookmarks/playlists/history, lyrics, visualizer presets, AutoEQ import, DJ analysis cache
├── playback/                     # MediaSessionService + MediaController bridge + DJ smart queue ordering
└── ui/                           # Compose: theme, components, screens, nav
```

The signal path is 32-bit float end to end:
`decoder → EQ/input float conversion → uploaded-app tone/DVC → Dynamics/Limiter → Immerse 3D → DJ transitions → AudioTrack`.

## License

MIT — see [LICENSE](LICENSE).
