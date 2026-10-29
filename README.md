# Arisu Companion — floating 3D AI pet for Android

A native Android application that renders a 3D character in a **transparent
floating overlay window** above other apps, with a floating chat panel, BYOK AI
providers (Google AI Studio / Gemini), local chat
history/memory, and optional push-to-talk voice.

Version 0.2 adds a **Pet Library** (import your own `.glb` / `.gltf`+ZIP
characters, live preview, rename/delete, per-character animation mapping and
personality), a rewritten **animation state machine** that removes the stutter
and random-clip bugs of 0.1, an **idle "sulking" behaviour**, and **native
Gemini** support. Arisu stays the bundled default character.

- **Technology:** Kotlin, Jetpack Compose + Material 3, Filament/gltfio 3D
  rendering (real-time GLB skeletal animation), Room/SQLite, OkHttp (streaming
  SSE), Kotlin Coroutines/Flow, Android Keystore-backed credential storage,
  Gradle Kotlin DSL. No WebView 3D anywhere.

## Build

Requirements: JDK 17, Android SDK (platform **34**, build-tools **34.0.0**).
Recommended: Android Studio Ladybug+.

```bash
# from a machine with the SDK, e.g. ANDROID_HOME set or local.properties:
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest # pure-JVM unit tests
```

### Release build

`assembleRelease` runs R8 (shrinking, **no obfuscation**) plus resource
shrinking, and signs with `keystore.properties` + `app/arisu-release.jks`.

- The keystore in this tree is a **locally generated self-signed development
  key**, so the APK is installable straight away. Before publishing anywhere,
  replace both files with your own keystore and change the passwords in
  `keystore.properties`; `.gitignore` already excludes both.
- If `keystore.properties` is absent the release build still runs but the APK
  comes out unsigned.
- Obfuscation stays off deliberately: `-dontobfuscate` in
  `app/proguard-rules.pro`. The app never reflects on its own class names (the
  only reflection targets framework classes in `overlay/OverlayTouchRegion.kt`),
  so shrinking gives most of the size win, and keeping original names makes the
  APK verifiable without a device. Flip that line to enable renaming.
- `app/proguard-rules.pro` also keeps kotlinx.serialization Companions,
  Room's generated implementation, and the manifest entry points; Filament
  ships its own JNI keep rules inside its AARs.

The character model must exist at `app/src/main/assets/Arisu (Maid).glb`
(it ships in this source tree — copied from the user-provided file, unmodified).

`local.properties` (gitignored normally) holds `sdk.dir=/path/to/android-sdk`.

## Architecture

```
app/src/main/java/com/arisu/companion/
├── MainActivity.kt            # seven-tab Compose app (Home/AI/Pet/Model/Persona/History/About)
├── ArisuApplication.kt        # AppContainer (small hand-rolled DI)
├── renderer/
│   ├── ArisuRenderer.kt       # Filament engine + transparent swapchain + GLB load/anim loop
│   ├── FramePacer.kt          # testable frame pacing (FPS cap + stall clamping)
│   └── GlbParser.kt           # pure GLB container inspector (tests + diagnostics)
├── animation/
│   ├── PetAnimation.kt        # PetState enum + keyword/override clip mapper
│   └── PetStateMachine.kt     # the single authoritative animation state machine
├── pet/
│   ├── PetProfile.kt          # per-character config + personality
│   ├── PetLibrary.kt          # private storage, import/rename/delete/active
│   ├── PetModelInspector.kt   # pure GLB/glTF-ZIP validation
│   └── GltfAssetPacker.kt     # pure .gltf + side-cars -> single .glb packer
├── behavior/
│   ├── IdleTimer.kt           # monotonic idle latch + quiet hours
│   └── IdleMessages.kt        # local idle lines (never an API call)
├── overlay/
│   ├── FloatingPetService.kt  # foreground service; hosts pet window + chat window
│   ├── OverlayGeometry.kt     # pure math: clamping, snap, panel geometry
│   ├── OverlayUiOwner.kt      # LifecycleOwner plumbing for Compose-in-overlay
│   └── BootCompletedReceiver.kt
├── ai/
│   ├── ProviderProfile.kt     # BYOK profiles + provider catalog
│   ├── SseParser.kt           # incremental OpenAI-compatible SSE parser
│   ├── AiClient.kt            # OkHttp calls with cancel/timeouts/retries
│   ├── ChatEngine.kt          # context building, streaming state machine, persistence
│   ├── EmotionTagFilter.kt    # hides [[emotion:...]] tags, maps them to PetState
│   └── MemoryExtractor.kt     # proposes memories (user approves before storing)
├── chat/FloatingChatPanel.kt  # the floating chat Compose panel
├── database/AppDatabase.kt    # Room: chat_messages + memories
├── security/CryptoStore.kt    # AES/GCM; key lives in AndroidKeyStore
├── settings/AppStateStore.kt  # SharedPreferences-backed flows
└── voice/VoiceController.kt   # SpeechRecognizer + TextToSpeech (optional)
```

### Animation mapping (verified against the actual GLB)

The supplied asset contains 32 clips. The mapper uses keyword scoring so the
application never *claims* an animation exists:

| State    | Clip used (this asset) | Notes |
|----------|------------------------|-------|
| Idle     | `Cafe_Idle`            | falls back `Formation_Idle`/`Normal_Idle`/`ST_Idle` |
| Greeting | `Normal_Callsign`      | falls back to idle |
| Thinking | `Formation_Pickup`     | no thinking clip exists; a subtle procedural lean is layered on top |
| Speaking | `Normal_Callsign`      | no talking clip exists in the asset |
| Happy    | `Victory_Start`        | |
| Panic    | `Vital_Panic`          | |
| Tap      | `Cafe_Reaction`        | |

Materials, textures (all embedded), the 4 skeletons and the 215-node scene
graph are used as-is; the model is only normalized (centered/scaled) at load.

## AI provider (BYOK)

**Google AI Studio** is the only provider: the native Gemini API
(`https://generativelanguage.googleapis.com/v1beta`, `?key=…`, never a Bearer
header). Each user pastes their own key from
<https://aistudio.google.com/apikey>; the app never ships or asks for a
developer key. Default chat model `gemini-3.1-flash-lite`, optional image model
`gemini-3.1-flash-image`, plus manual model-ID entry and a Test Connection
button. Keys are encrypted at rest (AES-256-GCM, key in AndroidKeyStore), never
logged, and never exported with chat backups. Profiles saved by older builds
that pointed at other endpoints are dropped on upgrade and the selection moves
to AI Studio automatically. The OpenAI-compatible transport (`AiClient`,
`SseParser`) is still in the tree but is no longer offered in the UI.

## Verified build results (this environment)

Built and tested here with JDK 21, Android SDK platform 34 / build-tools 34.0.0,
Gradle 8.7 (no Android device or emulator available):

```
:app:testReleaseUnitTest  77 tests, 0 failures, 0 errors, 4 skipped (opt-in live tests)
:app:assembleDebug       BUILD SUCCESSFUL
:app:assembleRelease     BUILD SUCCESSFUL  (R8 shrink + resource shrink + signing)

app/build/outputs/apk/debug/app-debug.apk     90 MB  (debug, all ABIs)
app/build/outputs/apk/release/app-release.apk 37 MB  (release, all ABIs)
```

### Live Gemini check (real key, real endpoint)

`ai/GeminiLiveTest` drives the shipping `GeminiClient`/`GeminiProtocol` against
`generativelanguage.googleapis.com`. It is skipped unless the switches below are
exported, so normal builds stay offline:

```bash
GEMINI_LIVE_TESTS=1 GEMINI_API_KEY=... ./tools/build-limited.sh testReleaseUnitTest
GEMINI_LIVE_TESTS=1 GEMINI_IMAGE_TEST=1 GEMINI_API_KEY=...   # adds the image model
```

Verified 29 Oct 2026 with a personal AI Studio key:

| Check | Result |
|-------|--------|
| `Test Connection` (non-streaming, `testConnection`) | ok — "Tersambung ke gemini-3.1-flash-lite" |
| Streaming chat (`?alt=sse`, `x-goog-api-key` header) | text streamed, key absent from the URL |
| Unknown model ID | HTTP 404 mapped to "Model tidak ditemukan…" |
| Invalid key | HTTP 400 `API_KEY_INVALID` → "API key Gemini tidak valid…" |
| `gemini-3.1-flash-lite` (default) | answered in ~0.9 s |
| `gemini-3.5-flash-lite` | answered in ~1.6 s |
| `gemini-3.6-flash` | answered in ~1.2 s |
| `gemini-3.8-flash` | **503 UNAVAILABLE / hangs**: server reported "currently experiencing high demand" (reproduced with curl, so not the client) |
| `gemini-3.1-pro-preview` | HTTP 429 — no free-tier quota for Pro models |
| Image model `gemini-3.1-flash-image` | **untested live**: free tier answers HTTP 429 with `limit: 0`, billing required. Parsing of `inlineData` is covered by `GeminiProtocolTest`. |

The model sweep fails the build only when a model rejects the request for a
reason other than quota (429/403), overload (503) or a stall, so a real
regression is still caught while account limits stay visible in the log.

## What changed in 0.2 — root causes of the animation bugs

These were found by reading the 0.1 renderer, not guessed:

1. **Stutter (the big one).** The loop did `if (now - lastRendered <
   frameInterval) return` with `frameInterval = 1e9/60 = 16 666 666 ns` while a
   60 Hz Choreographer vsync is `16 666 666 ns` — identical in theory, but any
   ns of jitter skipped the frame, and skipping reset nothing, so every second
   vsync was dropped: an alternating 60/30 fps pattern. Fixed by
   `FramePacer` (accumulator + 1 % tolerance) and covered by
   `FramePacerTest`.
2. **Jerky/teleporting poses.** Animation time was `System.nanoTime() -
   stateEnteredNanos`, i.e. wall-clock driven. One dropped frame (GC, window
   focus change, screen on) fast-forwarded the clip by the whole gap. The
   state machine now advances only by the clamped frame delta (`<= 250 ms`),
   so time in the pet never jumps.
3. **Random "strange movements".** `driveAnimation` re-resolved
   `bestFor(state)` every frame and did `indexOf(...).let { if (it < 0) 0 }`:
   an unresolved clip silently played **clip 0**, which for this asset is
   `Cafe_Reaction` (an attack pose). It also restarted the clip whenever the
   resolution changed. Now the clip is resolved once per state change and a
   missing clip keeps the current pose instead of substituting clip 0.
4. **Inconsistent transitions.** There was no cross-fade at all, and the
   one-shot → idle switch happened inside the render callback, mutating state
   nobody else could observe. `PetStateMachine` owns the transition and the
   renderer applies `applyAnimation → applyCrossFade → updateBoneMatrices`
   (the order Filament documents), falling back to hard switches if an asset
   cannot blend.
5. **Thread race.** `ChatEngine.petStateFlow` was collected on
   `Dispatchers.Default` while Choreographer ran the renderer on the main
   thread, so `setPetState` could mutate the animator mid-frame. State
   requests are now `@Volatile` and applied on the render thread; the service
   collects on `Dispatchers.Main`.
6. **Root transform.** The old code composed translate/scale/rotate with
   helpers that applied the translation *unscaled* (the model was offset by
   an un-normalised centre). The matrix is now built explicitly as
   `R(yaw) · S(scale) · T(-centre)` and re-applied after the animation each
   frame, so a clip with root motion cannot teleport the character.
7. **Focus/background handling.** Resuming after the overlay regained focus
   kept the stale timestamp, so the first frame advanced the clip by the whole
   pause. Pausing now resets the pacer.

## Idle behaviour (sulking)

After `idleTimeoutSeconds` (default **60 s**) without a tap, drag, chat message,
settings visit or screen-on, the pet enters `SULKING`: it turns away ~13° and
drops slightly (eased, so no pop), plays the character's own sulk clip when one
exists, and shows a small speech bubble with a **local** line ("Arisu dicuekin
lagi..."). The timer is a latched monotonic timer, so one idle period produces
exactly one reaction and never one per tick. Quiet hours, screen-off and
"reduced motion" suppress it. No API call, no notification.

## Gemini (Google AI Studio)

Add a profile under **AI → Google Gemini**, paste a key from
`https://aistudio.google.com/apikey` (stored with AES/GCM + Android Keystore)
and press **Test connection**. Implemented against the documented API
(`POST {base}/models/{model}:streamGenerateContent?alt=sse`, `x-goog-api-key`,
`contents[].role` = `user`/`model`, `systemInstruction`,
`generationConfig.{temperature,maxOutputTokens,responseModalities}`).
Defaults: `gemini-3.1-flash-lite`, image model `gemini-3.1-flash-image`.
Sending `/img <prompt>` uses the image model; inline `inlineData` image parts
are decoded, saved to app-private storage and shown in the chat bubble instead
of being mistaken for text.

Release APK checks run here, all static:

- `apksigner verify --print-certs` — signed with the local development key
  (SHA-256 `ca5cf99a…32604`).
- `aapt2 dump badging` — `com.arisu.companion`, versionCode 1, versionName
  0.1.0, targetSdk 34, all expected permissions declared.
- `unzip -t` — archive intact.
- Every production class was checked present in `classes.dex` (MainActivity,
  FloatingPetService, ArisuRenderer, ChatEngine, AiClient, CryptoStore,
  ProviderProfile, FloatingChatPanel, SseParser, VoiceController,
  OverlayTouchRegion, BootCompletedReceiver, `AppDatabase_Impl`, …).
  `OverlayGeometry` is the one class R8 removed — all its pure helpers were
  inlined into callers, and the dex contains **zero** references to it.

Size split of the 37 MB release: 11 MB GLB asset, ~28 MB of Filament/gltfio
native libraries (7 MB per ABI × 4 ABIs), the rest is code and resources.

The 90 MB debug APK is large because the debug build is not minified and packs
Filament/gltfio native libraries for every ABI (`arm64-v8a`, `armeabi-v7a`,
`x86`, `x86_64`) plus the 11 MB GLB with embedded 22 textures — the release
build above shrinks the code side of the same contents down to 37 MB.

Asset inspection of the shipped model (measured, not assumed): glTF 2.0,
11 MB, **32 animation clips**, 20 meshes, 24 materials, 22 embedded images,
4 skins, 215 nodes.

## Tests

`app/src/test` covers, on the JVM:
- **GLB loading** (`GlbParserTest`) — runs against the real 10.5 MB asset,
  verifies header/version/chunks and asserts well-known clips exist
  (`Cafe_Idle`, `Cafe_Walk`,…).
- **Animation clip discovery/mapping** (`AnimationMapperTest`) — keyword map
  and fallback strategy for every state, plus emotion-tag mapping.
- **Streaming response parsing** (`SseParserTest`) — data-line deltas,
  `[DONE]`, comments, non-streaming bodies, error body extraction, and the
  chunk-split emotion tag filter.
- **Provider configuration** (`ProviderProfileTest`).
- **Position persistence math** (`OverlayGeometryTest`) — clamping, default
  placement, snap-to-edge, drag detection, chat panel bounds, and the
  character touch region.
- **API key storage** (`CryptoStoreTest`) — AES/GCM round-trip, tamper and
  wrong-key rejection (Device Keystore itself requires an Android JVM).
- **Memory proposals** (`MemoryExtractorTest`).
- **State machine** (`PetStateMachineTest`) — one-shot settling, cross-fade
  alpha, dt clamping, missing clips, per-character overrides.
- **Frame pacing** (`FramePacerTest`) — the 60 Hz stutter regression, the
  30 fps cap, stall clamping, out-of-order timestamps.
- **Model import** (`PetModelInspectorTest`) — GLB passthrough, glTF+ZIP
  packing (buffer rebasing, data-URI textures, URL-encoded names), missing
  assets, ZIP path traversal, mesh-less files, and the real bundled asset.
- **Gemini protocol** (`GeminiProtocolTest`) — endpoints, request shape,
  role mapping, SSE text/image parts, safety blocks, quota/API-key/billing
  error mapping.
- **Idle behaviour** (`IdleTimerTest`) — fires once per idle period, reset on
  interaction, cancel on screen-off, quiet-hours wrap, local messages.

What the tests do *not* cover, because they need a device: overlay
attach/detach against the real WindowManager, Filament/EGL rendering, Room on
the device filesystem, AndroidKeyStore, SpeechRecognizer/TTS, notification
actions, service lifecycle under the OS, and permission-denial flows. Those
paths compile and are wired, but are not hardware-verified.

## Known limitations (honest list)

- **No emulator/device ran in this environment**: the Android SDK was installed
  and both `assembleDebug` and `assembleRelease` ran here, but there is no
  device/emulator (no KVM) — so
  overlay behavior, Filament rendering, TTS/STT, Room migrations and
  notification actions on real hardware are **implemented but not
  device-verified**. The renderer has defensive error handling and logs.
- Overlay windows are below the IME on some OEM builds; the chat panel sits
  high on screen by default and can be dragged/expanded to stay visible.
- Auto-start after reboot is best-effort; several OEMs block it. The app never
  uses AccessibilityService to bypass any restriction.
- Mouth/eye morph targets imported from the game are not driven (the GLB
  exposes mouth meshes as separate geometry states, not blendshapes).
- Voice mode is plain Android TTS; no licensed anime character voice is used
  or claimed.
- **Touch passthrough** uses the framework-internal
  `ViewTreeObserver.OnComputeInternalInsetsListener` (reached reflectively in
  `overlay/OverlayTouchRegion.kt`, since it is not in the public SDK). Where a
  build blocks it the app logs a warning and the pet window accepts touches
  across its full square instead of the character band — taps outside the
  character are still *not* forwarded in that fallback case.
- The camera is placed at a fixed distance from the normalized model
  (`CAMERA_DISTANCE = 3.0`, 42° vertical FOV). If a different GLB with an
  unusual bounding box is swapped in, that constant may need retuning; it is
  not auto-fitted per frame.
- `testConnection` sends a single tiny completion ("ping") and bills a few
  tokens per attempt.
- **Imported models are not device-verified.** The GLB/glTF inspection, ZIP
  repacking and clip discovery are unit-tested on the JVM, and the renderer
  reports load failures through `onFatal`, but "this specific model renders
  correctly on Filament" can only be confirmed on a device.
- Cross-fades rely on `Animator.applyCrossFade`. If an imported asset's rig
  cannot blend, the renderer logs it once and falls back to hard clip switches.
- The Pet Library preview opens a **second** Filament engine while the floating
  pet is running. It is destroyed when the preview closes, but on very low-RAM
  devices the preview can be the first thing to be killed.
- DB schema v2 (`chat_messages.images`) ships with a hand-written Room
  migration (`ALTER TABLE … DEFAULT ''`). It is exercised by construction only;
  no device/emulator ran it here.
- `gemini-3.1-flash-lite` / `gemini-3.1-flash-image` were the documented model
  IDs at implementation time (checked October 2026); model availability is a
  Google-side decision and the field stays editable for that reason.

## Legal notes

`Arisu (Maid).glb` is user-provided content owned by its original authors
(Blue Archive). The app loads it but does not redefine its license. The app
code is MIT; libraries are Apache-2.0 (see the About screen).
