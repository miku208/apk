package com.arisu.companion.overlay

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import com.arisu.companion.MainActivity
import com.arisu.companion.R
import com.arisu.companion.animation.PetState
import com.arisu.companion.behavior.IdleMessages
import com.arisu.companion.behavior.IdleTimer
import com.arisu.companion.chat.FloatingChatPanel
import com.arisu.companion.renderer.ArisuRenderer
import com.arisu.companion.renderer.DebugStats
import com.arisu.companion.ui.ArisuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the floating 3D companion alive. Hosts two overlay windows:
 *  1. the square pet window (transparent GLB rendering + gestures), and
 *  2. the floating chat panel window (Compose).
 *
 * Touch safety: the pet window is FLAG_NOT_TOUCH_MODAL and its touch handler
 * only consumes touches that land near the character's circular body, so apps
 * underneath stay interactive. No AccessibilityService tricks anywhere.
 */
class FloatingPetService : Service() {

    private lateinit var container: com.arisu.companion.AppContainer
    private lateinit var windowManager: WindowManager

    private var petWindowParams: WindowManager.LayoutParams? = null
    private var petRoot: FrameLayout? = null
    private var petView: TextureView? = null
    private var renderer: ArisuRenderer? = null

    private var chatOwner: OverlayUiOwner? = null
    private var chatRoot: FrameLayout? = null
    private var chatOpen = false
    private var chatExpanded = false
    private var petHiddenByUser = false

    private val handler = Handler(Looper.getMainLooper())
    private val idleTimer = IdleTimer()
    private var screenOn = true
    private var sulking = false
    private var sulkCount = 0
    private var loadedPetId: String? = null

    private var bubbleView: TextView? = null
    private var bubbleHide: Runnable? = null
    private var debugView: TextView? = null

    private val idleTick = object : Runnable {
        override fun run() {
            handler.postDelayed(this, IDLE_TICK_MS)
            evaluateIdle()
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    renderer?.setPausedByPolicy(true)
                    hideBubble()
                    idleTimer.cancel(SystemClock.elapsedRealtime())
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    renderer?.setPausedByPolicy(false)
                    idleTimer.start(SystemClock.elapsedRealtime())
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as? com.arisu.companion.ArisuApplication
        container = app?.container ?: com.arisu.companion.AppContainer(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission missing; service idles until granted")
        }
        startInForeground()
        registerReceiver(
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
        )
        addPetWindow()
        observeChatEngine()
        observeSettings()
        handler.postDelayed(idleTick, IDLE_TICK_MS)
        idleTimer.start(SystemClock.elapsedRealtime())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> hidePet()
            ACTION_SHOW -> showPet()
            ACTION_STOP -> stopSelfGracefully()
            ACTION_TOGGLE_CHAT -> if (chatOpen) closeChat() else openChat()
            ACTION_OPEN_SETTINGS -> {
                val i = Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            }
        }
        return START_STICKY
    }

    private fun observeChatEngine() {
        // Collect on the main thread: the renderer and the state machine must
        // only ever be touched from the thread that runs Choreographer.
        container.applicationScope.launch {
            container.chatEngine.petStateFlow.collect { state ->
                withContext(Dispatchers.Main) {
                    if (petHiddenByUser) return@withContext
                    renderer?.setPetState(state)
                    markInteraction(reaction = false)
                }
            }
        }
        container.applicationScope.launch {
            container.appState.petScale.collect { scale ->
                withContext(Dispatchers.Main) { renderer?.setUserScale(scale) }
            }
        }
        container.applicationScope.launch {
            container.appState.fpsLimit.collect { fps ->
                withContext(Dispatchers.Main) { renderer?.setFpsLimit(fps) }
            }
        }
    }

    /** Behaviour settings and the active character, applied reactively. */
    private fun observeSettings() {
        container.applicationScope.launch {
            container.appState.animationSpeed.collect {
                withContext(Dispatchers.Main) { applyBehaviorSettings() }
            }
        }
        container.applicationScope.launch {
            container.appState.reducedMotion.collect {
                withContext(Dispatchers.Main) { applyBehaviorSettings() }
            }
        }
        container.applicationScope.launch {
            container.appState.debugOverlay.collect { enabled ->
                withContext(Dispatchers.Main) { applyDebugOverlay(enabled) }
            }
        }
        container.applicationScope.launch {
            container.appState.idleTimeoutSeconds.collect { seconds ->
                idleTimer.setTimeoutMillis(seconds * 1000L)
            }
        }
        // Collect the whole profile, not just the id: editing one character's
        // animation mapping or behaviour must reach the running pet too.
        container.applicationScope.launch {
            container.petLibrary.active.collect {
                withContext(Dispatchers.Main) { applyActivePet() }
            }
        }
    }

    // ------------------------------------------------------------ pet window

    private fun addPetWindow() {
        if (!android.provider.Settings.canDrawOverlays(this)) return
        if (petRoot != null) return

        val sizePx = petSizePx()
        val metrics = resources.displayMetrics
        val (defaultX, defaultY) = OverlayGeometry.defaultPosition(sizePx, metrics.widthPixels, metrics.heightPixels)
        val saved = container.appState

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (saved.petX.value >= 0) saved.petX.value.toInt() else defaultX
            y = if (saved.petY.value >= 0) saved.petY.value.toInt() else defaultY
        }
        petWindowParams = params

        val root = FrameLayout(this)
        val texture = TextureView(this)
        root.addView(
            texture,
            FrameLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            ),
        )
        petView = texture
        petRoot = root

        val gesture = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    return if (container.appState.tapOpensChat.value) {
                        openChat(); true
                    } else {
                        renderer?.setPetState(PetState.GREETING); true
                    }
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    renderer?.setPetState(PetState.HAPPY)
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    showPetMenu(root, e.rawX.toInt(), e.rawY.toInt())
                }
            },
        )

        texture.setOnTouchListener { v, event ->
            handlePetTouch(event, gesture, v.width)
            true
        }

        windowManager.addView(root, params)

        // Restrict the overlay's touchable area to the character itself: the
        // WindowManager routes touches outside this region to the app below,
        // which a `return false` from the listener above cannot achieve.
        if (!OverlayTouchRegion.apply(root)) {
            Log.w(TAG, "pet window handles its full square bounds")
        }

        createRenderer()
    }

    private fun handlePetTouch(event: MotionEvent, gesture: GestureDetector, sizePx: Int) {
        try {
            gesture.onTouchEvent(event)
        } catch (_: Exception) {
        }
        // Renderer is not used for orbit; drag is handled here.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                markInteraction()
                dragStartX = petWindowParams?.x ?: 0
                dragStartY = petWindowParams?.y ?: 0
                dragStartTouchX = event.rawX
                dragStartTouchY = event.rawY
                dragStarted = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - dragStartTouchX
                val dy = event.rawY - dragStartTouchY
                if (!dragStarted && OverlayGeometry.isDrag(dx.toInt(), dy.toInt(), viewSlop())) {
                    dragStarted = true
                    renderer?.setPetState(PetState.GREETING)
                }
                if (dragStarted && petWindowParams != null) {
                    val metrics = resources.displayMetrics
                    val (x, y) = OverlayGeometry.clamp(
                        dragStartX + dx.toInt(),
                        dragStartY + dy.toInt(),
                        sizePx,
                        metrics.widthPixels,
                        metrics.heightPixels,
                    )
                    petWindowParams?.let {
                        it.x = x
                        it.y = y
                        windowManager.updateViewLayout(petRoot, it)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragStarted && petWindowParams != null) {
                    val metrics = resources.displayMetrics
                    petWindowParams?.let { p ->
                        if (container.appState.snapToEdge.value) {
                            p.x = OverlayGeometry.snapToEdge(p.x, sizePx, metrics.widthPixels, SNAP_THRESHOLD_PX)
                            windowManager.updateViewLayout(petRoot, p)
                        }
                        container.appState.savePetPosition(p.x.toFloat(), p.y.toFloat())
                    }
                }
                dragStarted = false
            }
        }
    }

    private var dragStartX = 0
    private var dragStartY = 0
    private var dragStartTouchX = 0f
    private var dragStartTouchY = 0f
    private var dragStarted = false

    private fun viewSlop(): Int =
        android.view.ViewConfiguration.get(this).scaledTouchSlop

    private fun createRenderer() {
        val texture = petView ?: return
        if (renderer != null) return
        val r = ArisuRenderer(
            textureView = texture,
            onAssetReady = { clips ->
                Log.i(TAG, "model clips (${clips.size}): ${clips.joinToString()}")
            },
            onFatal = { msg -> Log.e(TAG, "renderer fatal: $msg") },
            onDebug = { stats -> debugView?.text = formatDebug(stats) },
            onSettledToIdle = { sulking = false },
        )
        renderer = r
        r.setFpsLimit(container.appState.fpsLimit.value)
        r.setUserScale(container.appState.petScale.value)
        loadedPetId = null
        applyActivePet()
        applyBehaviorSettings()
        applyDebugOverlay(container.appState.debugOverlay.value)
    }

    /** Loads whichever character is active in the library (bundled Arisu by default). */
    private fun applyActivePet() {
        val r = renderer ?: return
        val pet = container.petLibrary.active.value
        if (loadedPetId == pet.id) {
            // Same model: only the mapping/behaviour may have changed.
            r.setAnimationOverrides(pet.overridesAsStates())
            applyBehaviorSettings()
            return
        }
        val bytes = container.petLibrary.modelBytes(pet)
        if (bytes == null) {
            Log.e(TAG, "model bytes missing for ${pet.id} (${pet.modelFile})")
            return
        }
        loadedPetId = pet.id
        r.setAnimationOverrides(pet.overridesAsStates())
        applyBehaviorSettings()
        r.loadModel(bytes)
        r.setPetState(PetState.GREETING)
        hideBubble()
        sulking = false
        idleTimer.start(SystemClock.elapsedRealtime())
    }

    private fun applyBehaviorSettings() {
        val pet = container.petLibrary.active.value
        val globalSpeed = container.appState.animationSpeed.value
        renderer?.setAnimationSpeed(pet.animationSpeed * globalSpeed)
        renderer?.setReducedMotion(container.appState.reducedMotion.value)
    }

    private fun destroyRenderer() {
        renderer?.destroy()
        renderer = null
        loadedPetId = null
        hideBubble()
        removeDebugView()
        petView?.let { it.surfaceTextureListener = null }
    }

    private fun petSizePx(): Int =
        (resources.displayMetrics.density * BASE_PET_DP * container.appState.petScale.value).toInt()
            .coerceIn(140.dpToPx(), 420.dpToPx())

    private fun resizePet(newScale: Float) {
        container.appState.savePetScale(newScale)
        val params = petWindowParams ?: return
        val sizePx = petSizePx()
        params.width = sizePx
        params.height = sizePx
        val metrics = resources.displayMetrics
        val (x, y) = OverlayGeometry.clamp(params.x, params.y, sizePx, metrics.widthPixels, metrics.heightPixels)
        params.x = x
        params.y = y
        windowManager.updateViewLayout(petRoot, params)
        container.appState.savePetPosition(x.toFloat(), y.toFloat())
    }

    // ------------------------------------------------------------- chat window

    fun openChat() {
        if (chatOpen || !android.provider.Settings.canDrawOverlays(this)) return
        markInteraction()
        chatOpen = true
        val metrics = resources.displayMetrics
        val (x, y, w, h) = OverlayGeometry.chatPanelBounds(
            metrics.widthPixels, metrics.heightPixels, metrics.density, chatExpanded,
        )
        val params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

        val owner = OverlayUiOwner()
        chatOwner = owner
        val root = FrameLayout(this).apply {
            setOnTouchListener { _, _ -> false } // pass touches that miss the panel
        }
        val compose = ComposeView(this).apply {
            setContent {
                ArisuTheme {
                    FloatingChatPanel(
                        engine = container.chatEngine,
                        voice = container.voiceController,
                        expanded = chatExpanded,
                        onToggleExpand = {
                            chatExpanded = !chatExpanded
                            applyChatExpanded()
                        },
                        onClose = { closeChat() },
                        onComposerFocus = { setChatWindowFocusable(true) },
                        onComposerUnfocus = { setChatWindowFocusable(false) },
                    )
                }
            }
        }
        root.addView(
            compose,
            FrameLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            ),
        )
        owner.install(root)

        chatRoot = root
        windowManager.addView(root, params)
        renderer?.setPetState(PetState.GREETING)
    }

    private fun applyChatExpanded() {
        val root = chatRoot ?: return
        val metrics = resources.displayMetrics
        val (x, y, w, h) = OverlayGeometry.chatPanelBounds(
            metrics.widthPixels, metrics.heightPixels, metrics.density, chatExpanded,
        )
        (root.layoutParams as? WindowManager.LayoutParams)?.apply {
            this.x = x
            this.y = y
            width = w
            height = h
            windowManager.updateViewLayout(root, this)
        }
    }

    private fun setChatWindowFocusable(focusable: Boolean) {
        val root = chatRoot ?: return
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return
        val wasFocusable = (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0
        val wantFocusable = focusable
        if (wasFocusable == wantFocusable) return
        if (wantFocusable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        windowManager.updateViewLayout(root, params)
    }

    fun closeChat() {
        val root = chatRoot ?: return
        chatOpen = false
        chatOwner?.uninstall(root)
        windowManager.removeView(root)
        chatRoot = null
        chatOwner = null
        renderer?.setPetState(PetState.IDLE)
    }

    // -------------------------------------------------------------- hide/show

    private fun hidePet() {
        petHiddenByUser = true
        closeChat()
        destroyRenderer()
        petRoot?.let { windowManager.removeView(it) }
        petRoot = null
        petWindowParams = null
        updateNotification()
    }

    private fun showPet() {
        petHiddenByUser = false
        addPetWindow()
        updateNotification()
    }

    private fun stopSelfGracefully() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------ menu

    private fun showPetMenu(anchor: View, rawX: Int, rawY: Int) {
        val popupView = LayoutInflater.from(this).inflate(R.layout.view_pet_menu, null, false)
        val popup = PopupWindow(
            popupView,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            false,
        ).apply { isOutsideTouchable = true }

        popupView.findViewById<View>(R.id.menu_bigger).setOnClickListener {
            resizePet((container.appState.petScale.value + 0.2f).coerceAtMost(2.2f))
        }
        popupView.findViewById<View>(R.id.menu_smaller).setOnClickListener {
            resizePet((container.appState.petScale.value - 0.2f).coerceAtLeast(0.4f))
        }
        popupView.findViewById<View>(R.id.menu_reset).setOnClickListener {
            container.appState.resetPetPosition()
            val metrics = resources.displayMetrics
            val size = petSizePx()
            val (x, y) = OverlayGeometry.defaultPosition(size, metrics.widthPixels, metrics.heightPixels)
            petWindowParams?.let {
                it.x = x; it.y = y
                petRoot?.let { r -> windowManager.updateViewLayout(r, it) }
                container.appState.savePetPosition(x.toFloat(), y.toFloat())
            }
        }
        popupView.findViewById<View>(R.id.menu_open_settings).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            popup.dismiss()
        }
        popupView.findViewById<View>(R.id.menu_hide).setOnClickListener {
            popup.dismiss()
            hidePet()
        }
        popupView.findViewById<View>(R.id.menu_close_chat).setOnClickListener {
            popup.dismiss()
            if (chatOpen) closeChat() else openChat()
        }
        popup.showAsDropDown(anchor, -60, 0)
    }

    // ---------------------------------------------------------- notifications

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(id: Int, label: String, intent: Intent): Notification.Action {
            val pi = PendingIntent.getService(
                this, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Action.Builder(null, label, pi).build()
        }
        val notificationsApi = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val builder = Notification.Builder(this, FloatingPetService.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pet_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openApp)
            .addAction(action(11, getString(R.string.notif_action_hide), newIntent(ACTION_HIDE)))
            .addAction(
                action(
                    12,
                    if (petHiddenByUser) getString(R.string.notif_action_show) else getString(R.string.notif_action_stop),
                    newIntent(if (petHiddenByUser) ACTION_SHOW else ACTION_STOP),
                ),
            )
        return builder.build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun newIntent(action: String): Intent = Intent(this, FloatingPetService::class.java).apply {
        this.action = action
    }

    // ------------------------------------------------------------------ misc

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val root = petRoot
        val params = petWindowParams ?: return
        if (root != null) {
            val metrics = resources.displayMetrics
            val sizePx = petSizePx()
            params.width = sizePx
            params.height = sizePx
            val (x, y) = OverlayGeometry.clamp(params.x, params.y, sizePx, metrics.widthPixels, metrics.heightPixels)
            params.x = x
            params.y = y
            windowManager.updateViewLayout(root, params)
            container.appState.savePetPosition(x.toFloat(), y.toFloat())
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideBubble()
        removeDebugView()
        runCatching {
            unregisterReceiver(screenStateReceiver)
        }
        closeChat()
        destroyRenderer()
        petRoot?.let {
            windowManager.removeView(it)
        }
        petRoot = null
        petWindowParams = null
        super.onDestroy()
    }

    // --------------------------------------------------------- idle behaviour

    /**
     * Runs once a second. Uses a monotonic clock and a latched timer, so a sulk
     * happens once per idle period and never on a wall-clock jump.
     */
    private fun evaluateIdle() {
        val store = container.appState
        idleTimer.setTimeoutMillis(store.idleTimeoutSeconds.value * 1000L)
        if (!screenOn || petHiddenByUser || renderer == null) return
        if (!store.sulkingEnabled.value) return
        if (container.chatEngine.busy.value) {
            // An in-flight reply counts as interaction; never sulk mid-answer.
            idleTimer.onInteraction(SystemClock.elapsedRealtime())
            return
        }
        if (store.isQuietNow()) return
        if (idleTimer.due(SystemClock.elapsedRealtime())) showSulk()
    }

    private fun showSulk() {
        val pet = container.petLibrary.active.value
        sulking = true
        sulkCount++
        renderer?.setPetState(PetState.SULKING)
        if (container.appState.idleSpeechBubble.value) {
            showBubble(IdleMessages.pick(pet.displayName, pet.personality.idleMessages, sulkCount))
        }
    }

    /** Any touch, drag, chat, settings visit or screen-on resets the idle countdown. */
    private fun markInteraction(reaction: Boolean = true) {
        idleTimer.onInteraction(SystemClock.elapsedRealtime())
        hideBubble()
        if (sulking) {
            sulking = false
            // Leaving SULKING goes through a reaction so the turn-away eases out
            // instead of snapping back to the idle pose.
            if (reaction) renderer?.setPetState(PetState.REACTION)
        }
    }

    // ------------------------------------------------------------ speech bubble

    private fun showBubble(text: String) {
        if (!android.provider.Settings.canDrawOverlays(this)) return
        hideBubble()
        val view = TextView(this).apply {
            this.text = text
            setTextColor(0xFF20212A.toInt())
            textSize = 13f
            maxWidth = dp(230)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xFFF7F3FF.toInt())
                setStroke(dp(1).coerceAtLeast(1), 0x66B39DDB.toInt())
            }
        }
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        runCatching { view.measure(spec, spec) }
        val width = view.measuredWidth.coerceAtLeast(dp(80))
        val height = view.measuredHeight.coerceAtLeast(dp(30))
        val metrics = resources.displayMetrics
        val size = petWindowParams?.width ?: petSizePx()
        val petX = petWindowParams?.x ?: 0
        val petY = petWindowParams?.y ?: 0
        val x = (petX + size / 2 - width / 2).coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
        val y = (petY - height - dp(8)).coerceAtLeast(0)
        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        val added = runCatching { windowManager.addView(view, params) }.isSuccess
        if (!added) return
        bubbleView = view
        val hide = Runnable { hideBubble() }
        bubbleHide = hide
        handler.postDelayed(hide, BUBBLE_VISIBLE_MS)
    }

    private fun hideBubble() {
        bubbleHide?.let { handler.removeCallbacks(it) }
        bubbleHide = null
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
    }

    // --------------------------------------------------------- debug overlay

    private fun applyDebugOverlay(enabled: Boolean) {
        renderer?.setDebugEnabled(enabled)
        if (!enabled) {
            removeDebugView()
            return
        }
        if (debugView != null) return
        val view = TextView(this).apply {
            setTextColor(0xFF7CFFB2.toInt())
            textSize = 9f
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(6), dp(4), dp(6), dp(4))
            text = "anim: menunggu frame..."
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = dp(8)
            y = dp(8)
        }
        if (runCatching { windowManager.addView(view, params) }.isSuccess) debugView = view
    }

    private fun removeDebugView() {
        debugView?.let { runCatching { windowManager.removeView(it) } }
        debugView = null
    }

    private fun formatDebug(stats: DebugStats): String = buildString {
        append("state  ").append(stats.state).append('\n')
        append("clip   ").append(stats.clip).append('\n')
        append("anim   ").append("%.2fs".format(stats.stateElapsed)).append('\n')
        append("delta  ").append("%.1fms".format(stats.frameDeltaMs)).append('\n')
        append("fps    ").append("%.1f".format(stats.fps))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "FloatPetService"
        private const val BASE_PET_DP = 300
        private const val SNAP_THRESHOLD_PX = 56
        private const val NOTIFICATION_ID = 2001
        private const val IDLE_TICK_MS = 1_000L
        private const val BUBBLE_VISIBLE_MS = 6_000L
        const val NOTIFICATION_CHANNEL_ID = "floating_pet"
        const val ACTION_HIDE = "com.arisu.companion.action.HIDE_PET"
        const val ACTION_SHOW = "com.arisu.companion.action.SHOW_PET"
        const val ACTION_STOP = "com.arisu.companion.action.STOP_PET"
        const val ACTION_TOGGLE_CHAT = "com.arisu.companion.action.TOGGLE_CHAT"
        const val ACTION_OPEN_SETTINGS = "com.arisu.companion.action.OPEN_SETTINGS"

        fun intent(context: Context, action: String): Intent =
            Intent(context, FloatingPetService::class.java).apply { this.action = action }
    }
}
