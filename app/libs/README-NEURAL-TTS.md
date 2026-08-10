# Neural TTS — Sherpa-ONNX runtime + Piper voices

As of v1.14.42 the Neural backend (`NeuralTtsEngine.kt`) is the active production
implementation, not a stub. This folder holds the linked native runtime and the
recovery procedure for clean checkouts where the AAR is missing.

Two backends ship in the app:

- **System** — Android's built-in `TextToSpeech`. Works out of the box.
- **Neural** — Piper / VITS synthesis via Sherpa-ONNX. Ships linked from this folder.

The drop-in voice folder at `/Android/data/com.speedread.rsvp/files/tts_models/`
just works: drop a Piper voice folder in and the **Options → Listening → Voice**
dropdown picks it up on next Options tab open.

## Folder contents

- `sherpa-onnx-<ver>.aar` — the native runtime (one file). Picked up at build time
  by the `fileTree(... "*.aar")` glob in `app/build.gradle.kts`. The version
  number is not referenced anywhere else, so newer Sherpa releases drop in cleanly.
- `README-NEURAL-TTS.md` — this file.

The reference implementation that used to live here as
`NeuralTtsEngine.piper.kt.reference` was promoted into the source tree at
`app/src/main/java/com/speedread/rsvp/tts/NeuralTtsEngine.kt` in v1.14.42, and the
.reference file was deleted. The engine is now first-class.

## Recovering on a clean checkout (AAR missing)

If gradle sync fails with `Unresolved reference: com.k2fsa.sherpa.onnx.*`, the AAR
is missing from this folder. To restore it:

1. Download the latest Android AAR from:
   https://github.com/k2-fsa/sherpa-onnx/releases

   Pick `sherpa-onnx-<version>.aar` (a single AAR — not the per-ABI split zip).

2. Place the AAR in **this folder** (`app/libs/`).

3. Gradle sync. Neural mode is now live again.

The `defaultConfig.ndk.abiFilters` block in `app/build.gradle.kts` already
restricts to `arm64-v8a` + `armeabi-v7a` to keep APK size in check (~30 MB saved
vs all-ABIs). If you need x86_64 emulator support, switch the AVD to an ARM64
image rather than re-adding the x86_64 filter.

## Verifying it works

- **Options → Listening → "TTS Engine"** → pick **Neural**.
- The "Neural voice folder" path appears below the voice dropdown.
- Drop a Piper voice folder (e.g. `en_US-lessac-medium/`) into that path. The
  folder must contain `model.onnx`, `tokens.txt`, and (for Piper)
  `espeak-ng-data/`.
- Return to Options, switch tabs and back, or just reopen Options → the voice
  appears in the dropdown.
- Go to the Reading tab, tap the **speaker** satellite FAB next to the big play
  button, press play. You should hear the document synthesized in the picked
  Neural voice.

## Where to get Piper voices

- Official catalog: https://github.com/rhasspy/piper/blob/master/VOICES.md
- Direct downloads: https://huggingface.co/rhasspy/piper-voices

Each voice ships as an `.onnx` + `.onnx.json` pair, plus the shared
`espeak-ng-data/` tree. Unzip into a named folder under the model directory and
you're good. An in-app voice catalog/downloader is the v2 deferred follow-up
(see Changelog 1.14.0).

## Troubleshooting

- Gradle sync fails with `Unresolved reference: com.k2fsa.sherpa.onnx.*` →
  AAR missing from this folder. See "Recovering on a clean checkout" above.
- App starts but Neural still falls back to System →
  `TtsModelRegistry.voices` is empty — check you dropped a complete voice folder
  (not just the `.onnx` file; `tokens.txt` and `espeak-ng-data/` are also
  required). The registry auto-writes a `README.txt` next to the voice folders
  documenting the layout.
- Synthesis starts but cuts out quickly →
  sample rate mismatch. Check the voice's `*.onnx.json` → `audio.sample_rate`
  field matches what Sherpa returns; logs tag `NeuralTtsEngine` will print the
  detected rate.
