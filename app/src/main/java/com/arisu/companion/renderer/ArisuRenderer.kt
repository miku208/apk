package com.arisu.companion.renderer

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import com.arisu.companion.animation.AnimationClips
import com.arisu.companion.animation.PetState
import com.arisu.companion.animation.PetStateMachine
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.SwapChainFlags
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.gltfio.Animator
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/** Live values for the animation debugging overlay. */
data class DebugStats(
    val state: String,
    val clip: String,
    val stateElapsed: Float,
    val frameDeltaMs: Float,
    val fps: Float,
)

/**
 * Renders a GLB into a [TextureView] with a transparent background (built for
 * floating overlay windows) and drives skeletal animation through the single
 * authoritative [PetStateMachine].
 *
 * Lifecycle: create -> attach -> `loadModel(bytes)` -> [setPetState] -> [destroy].
 *
 * Framing/timing policy (all of it was wrong before and is covered by tests):
 *  - animation time advances only by the clamped frame delta, never by a wall
 *    clock, so a dropped frame cannot fast-forward the pose;
 *  - the FPS cap uses an accumulator instead of "now - last < interval", which
 *    used to skip every second vsync and look like 30 fps stutter on a 60 Hz
 *    panel;
 *  - one clip is applied per frame and only a state *change* restarts it;
 *  - transitions cross-fade through `Animator.applyCrossFade` when available;
 *  - the root transform is written after the animation so a clip with root
 *    motion cannot teleport the character;
 *  - pausing (screen off, overlay hidden) resets the delta accumulator so
 *    resuming never jumps.
 */
class ArisuRenderer(
    val textureView: TextureView,
    private val onAssetReady: (List<String>) -> Unit = {},
    private val onFatal: (String) -> Unit = {},
    private val onDebug: (DebugStats) -> Unit = {},
    private val onSettledToIdle: () -> Unit = {},
) : TextureView.SurfaceTextureListener {

    companion object {
        private const val TAG = "ArisuRenderer"
        private const val FOV_DEGREES = 42.0
        private const val CAMERA_DISTANCE = 3.0
        private const val DEBUG_INTERVAL_NANOS = 250_000_000L
        private const val MAX_SPAWNABLE_RENDERABLES = 128
    }

    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var scene: Scene? = null
    private var view: View? = null
    private var camera: Camera? = null
    private var cameraEntity: Int = -1
    private var lightEntity: Int = -1
    private var indirectLight: IndirectLight? = null

    private var assetLoader: AssetLoader? = null
    private var materialProvider: UbershaderProvider? = null
    private var resourceLoader: ResourceLoader? = null
    private var asset: FilamentAsset? = null
    private var animator: Animator? = null
    private var allClipNames: List<String> = emptyList()
    private var clips: AnimationClips? = null
    private var stateMachine: PetStateMachine? = null
    private var overrides: Map<PetState, String> = emptyMap()

    private var swapChain: SwapChain? = null
    private var surface: Surface? = null

    private val choreographer: Choreographer = Choreographer.getInstance()
    private val frameCallback = Choreographer.FrameCallback { nowNanos -> tick(nowNanos) }
    private var renderLoopRunning = false

    private val framePacer = FramePacer()
    private var motionSeconds = 0f
    private var pausedByPolicy = false

    private var fpsWindowStart = 0L
    private var fpsFrames = 0
    private var lastFps = 0f
    private var lastDebugNanos = 0L

    private var animationSpeed = 1f
    private var reducedMotion = false
    private var crossfadeSupported = true
    private var crossfadeSeconds = 0.18f

    @Volatile
    private var pendingState: PetState? = null
    @Volatile
    private var debugEnabled = false

    private var userScale = 1.0f
    private var baseScale = 1f
    private val baseCenter = FloatArray(3)
    private val rootMatrix = FloatArray(16)
    private val popBuffer = IntArray(MAX_SPAWNABLE_RENDERABLES)
    private var sulkBlend = 0f
    private var thinkBlend = 0f

    init {
        Utils.init()
        runCatching {
            val eng = Engine.create()
            engine = eng
            renderer = eng.createRenderer().also {
                it.setClearOptions(
                    Renderer.ClearOptions().apply {
                        clear = true
                        clearColor = FloatArray(4)
                    },
                )
            }
            scene = eng.createScene()
            view = eng.createView().also { it.scene = scene }
        }.onFailure { reportFatal(it) }
        setupCameraAndLights()
        textureView.surfaceTextureListener = this
        if (textureView.isAvailable) {
            textureView.surfaceTexture?.let {
                onSurfaceTextureAvailable(it, textureView.width, textureView.height)
            }
        }
    }

    private fun setupCameraAndLights() {
        val eng = engine ?: return
        try {
            cameraEntity = EntityManager.get().create()
            camera = eng.createCamera(cameraEntity).apply {
                setExposure(16f, 1f / 125f, 100f)
                // The model is centred at the origin and normalized so its
                // largest half-extent is 1. A 42° vertical FOV frames it at 3 m.
                lookAt(
                    0.0, 0.0, CAMERA_DISTANCE,
                    0.0, 0.0, 0.0,
                    0.0, 1.0, 0.0,
                )
            }
            view?.camera = camera
            lightEntity = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(1.0f, 0.98f, 0.95f)
                .intensity(80_000f)
                .direction(0.3f, -1.0f, -0.4f)
                .castShadows(false)
                .build(eng, lightEntity)
            scene?.addEntity(lightEntity)

            val sh = FloatArray(27) { 0.55f }
            indirectLight = IndirectLight.Builder()
                .irradiance(3, sh)
                .intensity(45_000f)
                .build(eng)
            scene?.setIndirectLight(indirectLight)
        } catch (e: Exception) {
            reportFatal(e)
        }
    }

    // ------------------------------------------------------------------ model

    /**
     * Loads a GLB from raw bytes, replacing any previously loaded model.
     * Returns the animation clip names found (empty when the model is static).
     */
    fun loadModel(bytes: ByteArray): List<String> {
        val eng = engine ?: return emptyList()
        unloadAsset()
        return try {
            val provider = UbershaderProvider(eng)
            materialProvider = provider
            val loader = AssetLoader(eng, provider, EntityManager.get())
            assetLoader = loader
            val resLoader = ResourceLoader(eng, true)
            resourceLoader = resLoader

            val created = loader.createAsset(ByteBuffer.wrap(bytes))
                ?: throw IllegalStateException("GLTF asset creation failed")
            asset = created
            resLoader.asyncBeginLoad(created)
            val anim = created.instance.animator
            animator = anim
            created.releaseSourceData()

            // Never filter this list: indices must stay identical to the
            // animator's, otherwise every mapping points at the wrong clip.
            val names = IntRange(0, anim.getAnimationCount() - 1)
                .map { index -> anim.getAnimationName(index).orEmpty() }
            allClipNames = names
            val mapping = AnimationClips(names, overrides)
            clips = mapping
            stateMachine = PetStateMachine(
                clips = mapping,
                durationOf = { index -> anim.getAnimationDuration(index) },
                crossfadeSeconds = if (reducedMotion) 0f else crossfadeSeconds,
            ).also { it.reset(pendingState ?: PetState.IDLE) }

            val bb = created.boundingBox
            val center = bb.center
            val half = bb.halfExtent
            var maxHalf = 1e-6f
            for (value in half) maxHalf = max(maxHalf, abs(value))
            baseScale = 1f / maxHalf
            baseCenter[0] = center[0]
            baseCenter[1] = center[1]
            baseCenter[2] = center[2]

            applyRootTransform(0f)
            if (names.isEmpty()) {
                // Static model: make sure no stale pose from a previous asset
                // is left in the transform manager.
                runCatching { anim.resetBoneMatrices() }
            }
            onAssetReady(names)
            names
        } catch (e: Exception) {
            reportFatal(e)
            emptyList()
        }
    }

    /** Frees the current asset but keeps the engine alive for the next model. */
    fun unloadAsset() {
        runCatching {
            asset?.let { loaded ->
                scene?.removeEntities(loaded.entities)
                assetLoader?.destroyAsset(loaded)
            }
        }
        asset = null
        animator = null
        allClipNames = emptyList()
        clips = null
        stateMachine = null
        runCatching { resourceLoader?.destroy() }
        resourceLoader = null
        runCatching { assetLoader?.destroy() }
        assetLoader = null
        runCatching { materialProvider?.destroyMaterials() }
        runCatching { materialProvider?.destroy() }
        materialProvider = null
    }

    /** Clip names of the loaded asset (indices match the animator). */
    fun animationClipNames(): List<String> = allClipNames

    /** Per-character clip mapping; rebuilds the state machine in place. */
    fun setAnimationOverrides(overrides: Map<PetState, String>) {
        this.overrides = overrides
        val anim = animator ?: return
        val mapping = AnimationClips(allClipNames, overrides)
        clips = mapping
        val current = stateMachine?.state ?: PetState.IDLE
        stateMachine = PetStateMachine(
            clips = mapping,
            durationOf = { index -> anim.getAnimationDuration(index) },
            crossfadeSeconds = if (reducedMotion) 0f else crossfadeSeconds,
        ).also { it.reset(current) }
    }

    fun setUserScale(scale: Float) {
        userScale = scale.coerceIn(0.4f, 2.2f)
    }

    fun setAnimationSpeed(speed: Float) {
        animationSpeed = speed.coerceIn(0.25f, 3f)
    }

    fun setReducedMotion(enabled: Boolean) {
        reducedMotion = enabled
        if (enabled) {
            sulkBlend = 0f
            thinkBlend = 0f
        }
    }

    fun setCrossfadeSeconds(seconds: Float) {
        crossfadeSeconds = seconds.coerceIn(0f, 1f)
    }

    fun setDebugEnabled(enabled: Boolean) {
        debugEnabled = enabled
        if (!enabled) lastDebugNanos = 0L
    }

    // ------------------------------------------------------------ state machine

    /**
     * Requests a behavioural state. Safe to call from any thread; the request is
     * applied on the render thread so the machine is never touched concurrently.
     */
    fun setPetState(state: PetState) {
        pendingState = state
    }

    // ---------------------------------------------------------- surface callbacks

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val eng = engine ?: return
        try {
            surface?.release()
            surface = Surface(st)
            swapChain?.let { eng.destroySwapChain(it) }
            swapChain = eng.createSwapChain(surface!!, SwapChainFlags.CONFIG_TRANSPARENT)
            onSurfaceResized(width, height)
            startLoop()
        } catch (e: Exception) {
            reportFatal(e)
        }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        onSurfaceResized(width, height)
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        stopLoop()
        engine?.let { eng -> swapChain?.let { eng.destroySwapChain(it) } }
        swapChain = null
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    private fun onSurfaceResized(width: Int, height: Int) {
        view?.viewport = Viewport(0, 0, width, height)
        camera?.setProjection(
            FOV_DEGREES,
            if (height > 0) width.toDouble() / height.toDouble() else 1.0,
            0.05,
            1000.0,
            Camera.Fov.VERTICAL,
        )
    }

    // --------------------------------------------------------------- render loop

    fun setFpsLimit(fps: Int) {
        framePacer.setIntervalNanos(1_000_000_000L / fps.coerceIn(5, 120).toLong())
    }

    /** Stops rendering and animation while the pet is hidden or the screen is off. */
    fun setPausedByPolicy(reason: Boolean) {
        if (reason == pausedByPolicy) return
        pausedByPolicy = reason
        if (reason) {
            stopLoop()
        } else {
            resetFrameTiming()
            startLoop()
        }
    }

    private fun resetFrameTiming() {
        framePacer.reset()
        fpsWindowStart = 0L
        fpsFrames = 0
    }

    private fun startLoop() {
        if (renderLoopRunning || pausedByPolicy || swapChain == null) return
        renderLoopRunning = true
        resetFrameTiming()
        choreographer.postFrameCallback(frameCallback)
    }

    private fun stopLoop() {
        if (!renderLoopRunning) return
        renderLoopRunning = false
        choreographer.removeFrameCallback(frameCallback)
    }

    private fun tick(nowNanos: Long) {
        if (!renderLoopRunning) return
        choreographer.postFrameCallback(frameCallback)
        if (pausedByPolicy) return
        val sc = swapChain ?: return
        val ren = renderer ?: return
        val v = view ?: return

        // FramePacer keeps the remainder between vsyncs (the old strict
        // comparison silently halved the frame rate) and clamps long stalls so
        // the animation cannot be fast-forwarded by a GC pause or focus change.
        val frameNanos = framePacer.onFrame(nowNanos) ?: return
        val dt = (frameNanos / 1_000_000_000f).coerceAtMost(0.25f)

        try {
            resourceLoader?.asyncUpdateLoad()
            asset?.let { populateScene(it) }
            val anim = animator
            val machine = stateMachine
            if (anim != null && machine != null) {
                pendingState?.let { wanted ->
                    pendingState = null
                    machine.request(wanted)
                }
                val frame = machine.advance(dt * animationSpeed)
                motionSeconds += dt * animationSpeed
                applyFrame(anim, frame)
                if (frame.settledToIdle) onSettledToIdle()
            }
            applyRootTransform(dt)
            if (ren.beginFrame(sc, nowNanos)) {
                ren.render(v)
                ren.endFrame()
            }
            publishDebug(nowNanos, dt)
        } catch (e: Exception) {
            Log.e(TAG, "render error", e)
            renderLoopRunning = false
        }
    }

    private fun populateScene(currentAsset: FilamentAsset) {
        val sc = scene ?: return
        var popped: Int
        while (true) {
            popped = currentAsset.popRenderables(popBuffer)
            if (popped == 0) break
            sc.addEntities(popBuffer)
        }
        val lights = currentAsset.lightEntities
        if (lights.isNotEmpty()) sc.addEntities(lights)
    }

    private fun applyFrame(anim: Animator, frame: PetStateMachine.Frame) {
        if (frame.clipIndex < 0) return // no animation: keep the bind/last pose
        anim.applyAnimation(frame.clipIndex, frame.clipTime)
        if (crossfadeSupported && frame.previousIndex >= 0 && frame.previousIndex != frame.clipIndex &&
            frame.crossfadeAlpha < 1f
        ) {
            try {
                // Documented order: applyAnimation(current) -> applyCrossFade(previous) -> updateBoneMatrices.
                anim.applyCrossFade(frame.previousIndex, frame.previousTime, frame.crossfadeAlpha)
            } catch (t: Throwable) {
                // Some assets/skins may not support blending; fall back once and
                // remember it instead of throwing every frame.
                crossfadeSupported = false
                Log.w(TAG, "cross-fade unsupported for this asset; using hard switches", t)
            }
        }
        anim.updateBoneMatrices()
    }

    private fun publishDebug(nowNanos: Long, dt: Float) {
        if (!debugEnabled) return
        fpsFrames++
        if (fpsWindowStart == 0L) fpsWindowStart = nowNanos
        val window = nowNanos - fpsWindowStart
        if (window >= 500_000_000L) {
            lastFps = fpsFrames * 1_000_000_000f / window
            fpsFrames = 0
            fpsWindowStart = nowNanos
        }
        if (lastDebugNanos != 0L && nowNanos - lastDebugNanos < DEBUG_INTERVAL_NANOS) return
        lastDebugNanos = nowNanos
        val machine = stateMachine ?: return
        onDebug(
            DebugStats(
                state = machine.state.name,
                clip = clips?.nameFor(machine.state) ?: "—",
                stateElapsed = machine.elapsed,
                frameDeltaMs = dt * 1000f,
                fps = lastFps,
            ),
        )
    }

    // ------------------------------------------------------------- transforms

    /**
     * Writes the root transform: `R_y(yaw) * S(scale) * T(-center)`.
     *
     * Built explicitly because chaining the old helper calls composed the
     * translation, scale and rotation in the wrong order (the model was
     * translated by an unscaled centre). Procedural motion (thinking sway,
     * sulking turn-away) is eased and disabled with Reduced Motion.
     */
    private fun applyRootTransform(dt: Float) {
        val eng = engine ?: return
        val currentAsset = asset ?: return
        val machine = stateMachine
        val state = machine?.state ?: PetState.IDLE

        val wantSulk = if (machine != null && state == PetState.SULKING && !reducedMotion) 1f else 0f
        val wantThink = if (machine != null && state == PetState.THINKING && !reducedMotion) 1f else 0f
        sulkBlend = ease(sulkBlend, wantSulk, dt, 6f)
        thinkBlend = ease(thinkBlend, wantThink, dt, 4f)

        val sway = if (thinkBlend > 0.001f) sin(motionSeconds * 1.4f) * 6f * thinkBlend else 0f
        val yawDegrees = sway - 13f * sulkBlend
        val yaw = (yawDegrees * (PI / 180.0)).toFloat()
        val s = baseScale * userScale
        val c = cos(yaw)
        val sn = sin(yaw)
        val cx = baseCenter[0]
        val cy = baseCenter[1]
        val cz = baseCenter[2]

        // Translation = R * (-s * center), plus a small sulk drop.
        val tx = -s * (c * cx + sn * cz)
        val ty = -s * cy - 0.02f * sulkBlend
        val tz = -s * (sn * cx - c * cz)

        rootMatrix.fill(0f)
        rootMatrix[0] = c * s
        rootMatrix[2] = -sn * s
        rootMatrix[5] = s
        rootMatrix[8] = sn * s
        rootMatrix[10] = c * s
        rootMatrix[12] = tx
        rootMatrix[13] = ty
        rootMatrix[14] = tz
        rootMatrix[15] = 1f

        try {
            val tm = eng.transformManager
            tm.setTransform(tm.getInstance(currentAsset.root), rootMatrix)
        } catch (e: Exception) {
            Log.w(TAG, "root transform failed", e)
        }
    }

    /** Frame-rate independent smoothing towards [target]. */
    private fun ease(current: Float, target: Float, dt: Float, rate: Float): Float {
        if (dt <= 0f) return current
        val blend = 1f - exp(-rate * dt)
        return current + (target - current) * blend
    }

    // ---------------------------------------------------------------- teardown

    fun destroy() {
        stopLoop()
        val eng = engine ?: return
        try {
            unloadAsset()
            swapChain?.let { eng.destroySwapChain(it) }
            swapChain = null
            surface?.release()
            surface = null
            lightEntity.takeIf { it >= 0 }?.let { eng.destroyEntity(it) }
            scene?.let { eng.destroyScene(it) }
            view?.let { eng.destroyView(it) }
            cameraEntity.takeIf { it >= 0 }?.let {
                eng.destroyCameraComponent(it)
                EntityManager.get().destroy(it)
            }
            renderer?.let { eng.destroyRenderer(it) }
            eng.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroy", e)
        }
        engine = null
    }

    private fun reportFatal(e: Throwable) {
        Log.e(TAG, "fatal renderer error", e)
        renderLoopRunning = false
        onFatal(e.message ?: e.javaClass.simpleName)
    }
}
