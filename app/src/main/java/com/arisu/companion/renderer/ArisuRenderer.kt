package com.arisu.companion.renderer

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import com.arisu.companion.animation.AnimationClips
import com.arisu.companion.animation.PetState
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChainFlags
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.gltfio.Animator
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.Utils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Renders the Arisu GLB into a [TextureView] with a fully transparent
 * background (built for floating overlay windows) and drives skeletal
 * animation from [PetState]s.
 *
 * Lifecycle: create -> attach (this object is the TextureView listener) ->
 * loadModel(bytes) -> setPetState(...) -> destroy() when the window closes.
 * Rendering policy adapted from Filament's ModelViewer (Apache-2.0) and
 * simplified for a pet: fixed camera, transparent swapchain, capped FPS,
 * pause when hidden or the screen is off.
 */
class ArisuRenderer(
    val textureView: TextureView,
    private val onAssetReady: (clips: List<String>) -> Unit = {},
    private val onFatal: (String) -> Unit = {},
) : TextureView.SurfaceTextureListener {

    companion object {
        private const val TAG = "ArisuRenderer"
        private const val FOV_DEGREES = 42.0
        private const val CAMERA_DISTANCE = 3.0
        private val ONESHOT_STATES = setOf(
            PetState.GREETING, PetState.SPEAKING, PetState.HAPPY, PetState.PANIC,
        )
    }

    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var scene: com.google.android.filament.Scene? = null
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
    private var clips: AnimationClips? = null

    private var swapChain: com.google.android.filament.SwapChain? = null
    private var surface: Surface? = null

    private val choreographer: Choreographer = Choreographer.getInstance()
    private val frameCallback = Choreographer.FrameCallback { nowNanos -> tick(nowNanos) }
    private var renderLoopRunning = false
    private var lastRenderedNanos = 0L
    private var frameIntervalNanos = 1_000_000_000L / 60L
    private var pausedByPolicy = false // screen off / pet hidden

    private var petState = PetState.IDLE
    private var stateEnteredNanos = 0L
    private var userScale = 1.0f

    private var baseScale = 1f
    private var baseCenter = FloatArray(3)

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
            view = eng.createView()
            view?.scene = scene
        }.onFailure { reportFatal(it) }
        setupCameraAndLights()
        textureView.surfaceTextureListener = this
        if (textureView.isAvailable) {
            textureView.surfaceTexture?.let { onSurfaceTextureAvailable(it, width = textureView.width, height = textureView.height) }
        }
    }

    private fun setupCameraAndLights() {
        val eng = engine ?: return
        try {
            cameraEntity = EntityManager.get().create()
            camera = eng.createCamera(cameraEntity).apply {
                setExposure(16f, 1f / 125f, 100f)
                // The model is centred at the origin and normalized so its largest
                // half-extent is 1. Place the eye far enough for a 42° vertical FOV
                // to frame it: half-height at distance d is d*tan(21°), so d >= 2.6.
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

            // Uniform neutral ambient light (SH band 3, mild white).
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

    /** Loads a GLB from raw bytes. Returns the animation clip names found. */
    fun loadModel(bytes: ByteArray): List<String> {
        val eng = engine ?: return emptyList()
        try {
            val provider = UbershaderProvider(eng)
            materialProvider = provider
            val loader = AssetLoader(eng, provider, EntityManager.get())
            assetLoader = loader
            val resLoader = ResourceLoader(eng, true)
            resourceLoader = resLoader

            val created = loader.createAsset(java.nio.ByteBuffer.wrap(bytes))
                ?: throw IllegalStateException("GLTF asset creation failed")
            asset = created
            resLoader.asyncBeginLoad(created)
            animator = created.instance.animator
            created.releaseSourceData()

            val names = IntRange(0, (animator?.getAnimationCount() ?: 0) - 1)
                .map { animator?.getAnimationName(it).orEmpty() }
            clips = AnimationClips(names)

            val bb = created.boundingBox
            val center = bb.center
            val half = bb.halfExtent
            var maxHalf = 1e-6f
            for (v in half) maxHalf = maxOf(maxHalf, abs(v))
            baseScale = 1f / maxHalf
            baseCenter[0] = center[0]
            baseCenter[1] = center[1]
            baseCenter[2] = center[2]

            applyRootTransform(0f)
            onAssetReady(names)
            return names
        } catch (e: Exception) {
            reportFatal(e)
            return emptyList()
        }
    }

    fun setUserScale(scale: Float) {
        userScale = scale.coerceIn(0.4f, 2.2f)
        applyRootTransform((System.nanoTime() - stateEnteredNanos) / 1e9f)
    }

    // ------------------------------------------------------------ state machine

    fun setPetState(state: PetState) {
        if (state == petState) return
        petState = state
        stateEnteredNanos = System.nanoTime()
        applyRootTransform(0f)
    }

    fun clipNames(): List<String> = clips?.names ?: emptyList()

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
        frameIntervalNanos = 1_000_000_000L / fps.coerceIn(5, 120)
    }

    fun setPausedByPolicy(reason: Boolean) {
        pausedByPolicy = reason
        if (reason) stopLoop() else startLoop()
    }

    private fun startLoop() {
        if (renderLoopRunning || pausedByPolicy || swapChain == null) return
        renderLoopRunning = true
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
        val sc = swapChain ?: return
        val ren = renderer ?: return
        val v = view ?: return
        if (pausedByPolicy) return
        if (nowNanos - lastRenderedNanos < frameIntervalNanos) return
        lastRenderedNanos = nowNanos

        try {
            val resLoader = resourceLoader ?: return
            val currentAsset = asset ?: return
            resLoader.asyncUpdateLoad()
            populateScene(currentAsset)

            val elapsed = (nowNanos - stateEnteredNanos) / 1e9f
            animator?.let { driveAnimation(it, elapsed) }
            applyRootTransform(elapsed)

            if (ren.beginFrame(sc, nowNanos)) {
                ren.render(v)
                ren.endFrame()
            }
        } catch (e: Exception) {
            Log.e(TAG, "render error", e)
            renderLoopRunning = false
        }
    }

    private fun populateScene(currentAsset: FilamentAsset) {
        val sc = scene ?: return
        val readyRenderables = IntArray(64)
        var popped: Int
        while (currentAsset.popRenderables(readyRenderables).let { popped = it; it != 0 }) {
            sc.addEntities(readyRenderables.take(popped).toIntArray())
        }
        val lights = currentAsset.lightEntities
        if (lights.isNotEmpty()) sc.addEntities(lights)
    }

    private fun driveAnimation(anim: Animator, elapsed: Float) {
        val nameList = clipNames()
        if (nameList.isEmpty()) return
        val what = clips ?: return
        val idxCurrent = nameList.indexOf(what.bestFor(petState)).let { if (it < 0) 0 else it }
        val idxIdle = nameList.indexOf(what.bestFor(PetState.IDLE)).let { if (it < 0) 0 else it }
        val duration = safeDuration(anim, idxCurrent)
        val isOneShot = petState in ONESHOT_STATES

        if (!isOneShot || elapsed < duration) {
            val t = if (isOneShot) min(elapsed, duration) else elapsed % duration
            anim.applyAnimation(idxCurrent, t)
        } else {
            val idleDuration = safeDuration(anim, idxIdle)
            if (idxIdle != idxCurrent) {
                anim.applyAnimation(idxIdle, (elapsed - duration) % idleDuration)
            }
            petState = PetState.IDLE
            stateEnteredNanos = System.nanoTime()
        }
        anim.updateBoneMatrices()
    }

    private fun safeDuration(anim: Animator, index: Int): Float {
        val d = anim.getAnimationDuration(index)
        return if (d in 1e-5f..1e5f) d else 1f
    }

    // ------------------------------------------------------------- transforms

    /**
     * Root transform = R(sway) * S(base * userScale) * T(-center): the
     * character is centered on the origin, normalized, and gently sways while
     * THINKING (the GLB has no dedicated thinking clip, so we add a small
     * procedural lean on top of the mapped clip).
     */
    private fun applyRootTransform(elapsed: Float) {
        val eng = engine ?: return
        val currentAsset = asset ?: return
        val swayYaw = if (petState == PetState.THINKING) {
            (sin(elapsed * 1.4f) * 6f).toRadians()
        } else 0f
        val s = baseScale * userScale
        val out = FloatArray(16)
        matIdentity(out)
        matTranslate(out, -baseCenter[0], -baseCenter[1], -baseCenter[2])
        matScale(out, s)
        matRotateY(out, swayYaw)
        try {
            eng.transformManager.setTransform(eng.transformManager.getInstance(currentAsset.root), out)
        } catch (e: Exception) {
            Log.w(TAG, "root transform failed", e)
        }
    }

    private fun Float.toRadians() = this / 180f * kotlin.math.PI.toFloat()

    /** Column-major 4x4 helpers; out = out * op in filamat convention. */
    private fun matIdentity(m: FloatArray) {
        m.fill(0f)
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
    }

    private fun matScale(m: FloatArray, s: Float) {
        for (row in 0 until 4) {
            val base = row * 4
            m[base] *= s; m[base + 1] *= s; m[base + 2] *= s
        }
    }

    private fun matTranslate(m: FloatArray, x: Float, y: Float, z: Float) {
        m[12] += x; m[13] += y; m[14] += z
    }

    private fun matRotateY(m: FloatArray, rad: Float) {
        val c = cos(rad)
        val s2 = sin(rad)
        for (row in 0 until 4) {
            val r4 = row * 4
            val a0 = m[r4]; val a2 = m[r4 + 2]
            m[r4] = c * a0 - s2 * a2
            m[r4 + 2] = s2 * a0 + c * a2
        }
    }

    // ---------------------------------------------------------------- teardown

    fun destroy() {
        stopLoop()
        val eng = engine ?: return
        try {
            asset?.let {
                scene?.removeEntities(it.entities)
                assetLoader?.destroyAsset(it)
            }
            asset = null
            animator = null
            resourceLoader?.destroy()
            assetLoader?.destroy()
            materialProvider?.destroyMaterials()
            materialProvider?.destroy()
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
