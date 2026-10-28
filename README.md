# Arisu Companion — floating 3D AI pet for Android

A native Android application that renders the provided `Arisu (Maid).glb`
character in a **transparent floating overlay window** above other apps, with a
floating chat panel, BYOK AI providers, local chat history/memory, and
optional push-to-talk voice.

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
├── MainActivity.kt            # six-tab Compose app (Home/AI/Pet/Persona/History/About)
├── ArisuApplication.kt        # AppContainer (small hand-rolled DI)
├── renderer/
│   ├── ArisuRenderer.kt       # Filament engine + transparent swapchain + GLB load/anim loop
│   └── GlbParser.kt           # pure GLB container inspector (tests + diagnostics)
├── animation/PetAnimation.kt  # PetState enum + clip-name strategy mapper
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

## AI providers (BYOK)

OpenAI-compatible `/chat/completions` with `Authorization: Bearer`. Verified
defaults ship for **NVIDIA API** (`https://integrate.api.nvidia.com/v1`) and
**OpenRouter**. **TokenHarbor** and **Custom** ship as editable templates with a
 clearly-stated format note — no API compatibility is claimed beyond the
 OpenAI-compatible request/response surface implemented here. API keys are
 encrypted at rest (AES-256-GCM, key in AndroidKeyStore), never logged, and
 never exported with chat backups.

## Verified build results (this environment)

Built and tested here with JDK 21, Android SDK platform 34 / build-tools 34.0.0,
Gradle 8.7 (no Android device or emulator available):

```
:app:testDebugUnitTest   30 tests, 0 failures, 0 errors, 0 skipped
:app:assembleDebug       BUILD SUCCESSFUL
:app:assembleRelease     BUILD SUCCESSFUL  (R8 shrink + resource shrink + signing)

app/build/outputs/apk/debug/app-debug.apk     90 MB  (debug, all ABIs)
app/build/outputs/apk/release/app-release.apk 37 MB  (release, all ABIs)
```

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

## Legal notes

`Arisu (Maid).glb` is user-provided content owned by its original authors
(Blue Archive). The app loads it but does not redefine its license. The app
code is MIT; libraries are Apache-2.0 (see the About screen).
