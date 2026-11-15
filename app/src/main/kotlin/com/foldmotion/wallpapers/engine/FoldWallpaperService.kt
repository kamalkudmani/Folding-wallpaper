package com.foldmotion.wallpapers.engine

import android.app.WallpaperColors
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import com.foldmotion.wallpapers.render.LiquidMotion
import com.foldmotion.wallpapers.render.video.VideoFrameSource
import androidx.core.content.ContextCompat
import com.foldmotion.core.device.DeviceProfile
import com.foldmotion.core.device.ScreenClassifier
import com.foldmotion.core.device.ScreenKind
import com.foldmotion.core.motion.FoldStyle
import com.foldmotion.core.fold.FoldCommand
import com.foldmotion.core.fold.FoldEvent
import com.foldmotion.core.fold.TransitionClock
import com.foldmotion.core.fold.FoldState
import com.foldmotion.core.fold.FoldStateReducer
import com.foldmotion.core.fold.MotionPolicy
import com.foldmotion.core.fold.TransitionKind
import com.foldmotion.core.poc.PocEvents
import com.foldmotion.core.scene.GenerationMode
import com.foldmotion.core.scene.GenerationRequest
import kotlinx.coroutines.launch
import com.foldmotion.wallpapers.AppContainer
import com.foldmotion.wallpapers.data.AppSettings
import com.foldmotion.wallpapers.data.LivingMotion
import com.foldmotion.wallpapers.render.FrameState
import com.foldmotion.wallpapers.render.LoadedScene
import com.foldmotion.wallpapers.render.SceneDrawer
import com.foldmotion.wallpapers.render.SceneLoader
import com.foldmotion.wallpapers.telemetry.PocTrace
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * The Fold live wallpaper.
 *
 * All decisions are taken by the pure [FoldStateReducer]; this class only turns Android callbacks
 * into events and executes the commands it gets back. That keeps the platform glue thin and the
 * behaviour unit-tested.
 *
 * Isolation rule (blueprint section 12): nothing here touches the network. Assets are decoded on
 * a background thread; drawing happens on the engine's main thread with a hardware canvas.
 */
class FoldWallpaperService : WallpaperService() {

    private lateinit var container: AppContainer
    private lateinit var hinge: HingeMonitor
    private val decoder: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "fold-decoder") }
    private val engines = LinkedHashSet<FoldEngine>()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.get(this)
        hinge = HingeMonitor(getSystemService(SensorManager::class.java)) { screen -> engines.toList().forEach { it.onPosture(screen) } }
        PocTrace.log("service", "CREATE")
        ensureDefaultPack()
    }

    /**
     * The wallpaper can be chosen from the system list before the app was ever opened. Rather than
     * show a flat colour forever, create one starter pack in the background and hand it to engines.
     */
    private fun ensureDefaultPack() {
        if (container.packStore.list().isNotEmpty()) return
        container.scope.launch {
            try {
                val profile = container.deviceInfo.engineProfile()
                container.generator.generate(
                    GenerationRequest(
                        idempotencyKey = "default-pack",
                        deviceProfileId = profile.id,
                        mode = GenerationMode.RANDOM,
                        ideaId = DEFAULT_IDEA,
                        seed = 20261112L,
                    ),
                    profile,
                ) {}
                PocTrace.log("service", "DEFAULT_PACK_READY")
                mainHandler.post { engines.forEach { it.onPacksChanged() } }
            } catch (e: Exception) {
                PocTrace.log("service", "DEFAULT_PACK_FAILED", e.javaClass.simpleName)
            }
        }
    }

    override fun onDestroy() {
        PocTrace.log("service", "DESTROY")
        decoder.shutdownNow()
        super.onDestroy()
    }

    override fun onCreateEngine(): Engine = FoldEngine()

    inner class FoldEngine : Engine() {

        private val id = "engine#" + COUNTER.incrementAndGet()
        private val handler = Handler(Looper.getMainLooper())
        private val drawer = SceneDrawer()
        private val settings: AppSettings get() = container.settings
        private lateinit var profile: DeviceProfile

        private var state = FoldState()
        private val queue = ArrayDeque<FoldEvent>()
        private var dispatching = false

        private val scenes = HashMap<ScreenKind, LoadedScene>(2)
        private var packId: String? = null
        /** Bumped on every pack change so late decodes for an old pack are dropped. */
        private var loadToken = 0

        private var transitionKind: TransitionKind? = null
        private var transitionStartNanos = 0L
        private var clock: TransitionClock? = null
        private var transitionDurationMs = 0
        private var frameCount = 0
        private var jankFrames = 0
        private var maxGapNanos = 0L
        private var lastFrameNanos = 0L
        private var refreshHz = 60f
        private lateinit var tilt: TiltTracker
        private var ambientRunning = false
        /** Plays the current video pack, if it is one; shared by both screens' scenes. */
        private var video: VideoFrameSource? = null
        private var videoDrawScheduled = false
        private var frameRateVote = -1f
        private val videoListener: () -> Unit = { onVideoFrame() }
        private val videoDraw = Choreographer.FrameCallback {
            videoDrawScheduled = false
            if (state.visible && transitionKind == null) state.surface?.kind?.let { drawStatic(it) }
        }
        private var lastAmbientNanos = 0L
        private var frameScheduled = false
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var displayId = -1
        private var placeholderTop = Color.rgb(11, 34, 51)
        private var placeholderBottom = Color.rgb(25, 120, 125)

        private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                AppSettings.KEY_ACTIVE, AppSettings.KEY_PENDING -> {
                    val next = resolvePackId()
                    if (next != packId) {
                        trace("PACK_SWITCH", PocEvents.detail(PocEvents.FROM to packId, PocEvents.TO to next))
                        switchPack(next)
                    }
                }
                AppSettings.KEY_REDUCED_MOTION, AppSettings.KEY_SWITCH_HOLD, AppSettings.KEY_WAIT_UNLOCK -> {
                    dispatch(FoldEvent.PolicyChanged(now(), currentPolicy()))
                    refreshAmbient()
                    updateVideoPlayback()
                }
                AppSettings.KEY_LIVING, AppSettings.KEY_TILT -> {
                    refreshAmbient()
                    updateVideoPlayback()
                }
                // The pack on screen got new fold animations: drop the decoded scenes and reload.
                AppSettings.KEY_UPDATED -> if (settings.updatedPackId == packId && packId != null) {
                    trace("PACK_RELOAD", PocEvents.detail("pack" to packId))
                    switchPack(packId)
                }
            }
        }

        private val powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                dispatch(FoldEvent.PolicyChanged(now(), currentPolicy()))
                refreshAmbient()
                updateVideoPlayback()
            }
        }

        /**
         * Lock screen: the fold animation waits while it shows and plays when the user unlocks
         * (USER_PRESENT), like Samsung's own wallpapers. Screen on/off only refresh the state.
         */
        private val lockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_USER_PRESENT -> {
                        trace("UNLOCKED", PocEvents.detail(PocEvents.VISIBLE to state.visible))
                        dispatch(FoldEvent.Unlocked(now()))
                    }
                    else -> syncKeyguard()
                }
            }
        }

        /** Tells the state machine whether the lock screen is showing right now. */
        private fun syncKeyguard() {
            val locked = try {
                getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
            } catch (e: Exception) {
                false
            }
            if (locked != state.locked) {
                trace("KEYGUARD", PocEvents.detail("locked" to locked))
                dispatch(FoldEvent.KeyguardChanged(now(), locked))
            }
        }

        private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
            frameScheduled = false
            onFrame(frameTimeNanos)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            profile = container.deviceInfo.engineProfile()
            displayId = try {
                displayContext?.display?.displayId ?: -1
            } catch (e: Exception) {
                -1
            }
            state = FoldState(isPreview = isPreview, policy = currentPolicy())
            settings.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
            ContextCompat.registerReceiver(
                this@FoldWallpaperService, powerReceiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            ContextCompat.registerReceiver(
                this@FoldWallpaperService, lockReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_USER_PRESENT)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            syncKeyguard()
            engines += this
            hinge.acquire()
            tilt = TiltTracker(getSystemService(SensorManager::class.java))
            if (!isPreview) settings.lastLiveEngineAtMs = System.currentTimeMillis()
            packId = resolvePackId()
            readPlaceholderColors()
            trace(
                PocEvents.ENGINE_CREATE,
                PocEvents.detail(PocEvents.PREVIEW to isPreview, PocEvents.DISPLAY to displayId, "profile" to profile.id, "pack" to packId),
            )
        }

        override fun onDestroy() {
            trace(PocEvents.ENGINE_DESTROY)
            engines -= this
            stopFrames()
            settings.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
            try {
                unregisterReceiver(powerReceiver)
            } catch (_: IllegalArgumentException) {
            }
            try {
                unregisterReceiver(lockReceiver)
            } catch (_: IllegalArgumentException) {
            }
            hinge.release()
            releaseScenes()
            super.onDestroy()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            trace(PocEvents.SURFACE_CREATED)
            dispatch(FoldEvent.SurfaceCreated(now()))
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            frameRateVote = -1f
            val kind = ScreenClassifier.classify(profile, width, height) ?: return
            val newDisplay = try {
                displayContext?.display?.displayId ?: -1
            } catch (e: Exception) {
                -1
            }
            if (newDisplay != displayId) {
                trace("DISPLAY_CHANGED", PocEvents.detail(PocEvents.FROM to displayId, PocEvents.TO to newDisplay))
                displayId = newDisplay
            }
            val previousKind = state.lastSurfaceKind
            trace(
                PocEvents.SURFACE_CHANGED,
                PocEvents.detail(
                    PocEvents.W to width, PocEvents.H to height, PocEvents.KIND to kind,
                    "exact" to ScreenClassifier.isExactPanel(profile, kind, width, height), PocEvents.DISPLAY to displayId,
                ),
            )
            if (previousKind != null && previousKind != kind) {
                FoldSession.markPostureChange(id, PocEvents.detail(PocEvents.FROM to previousKind, PocEvents.TO to kind, PocEvents.VIA to "surface"))
            } else {
                FoldSession.inferSwitch(id, kind)
            }
            // A fold may bring up the lock screen on the new panel: know before deciding.
            syncKeyguard()
            dispatch(
                FoldEvent.SurfaceChanged(
                    now(), width, height, kind,
                    sessionLastShown = FoldSession.lastShown,
                    sessionPostureChangedAtMs = FoldSession.postureChangedAtMs,
                ),
            )
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            // Redraw synchronously whatever is current so the system never shows a stale buffer.
            val kind = state.surface?.kind ?: return
            if (transitionKind != null) drawTransitionFrame(System.nanoTime()) else drawStatic(kind)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            trace(PocEvents.SURFACE_DESTROYED)
            dispatch(FoldEvent.SurfaceDestroyed(now()))
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            trace(PocEvents.VISIBILITY, PocEvents.detail(PocEvents.VISIBLE to visible))
            if (visible) state.surface?.kind?.let { FoldSession.inferSwitch(id, it) }
            if (visible) syncKeyguard()
            dispatch(
                FoldEvent.VisibilityChanged(
                    now(), visible,
                    sessionLastShown = FoldSession.lastShown,
                    sessionPostureChangedAtMs = FoldSession.postureChangedAtMs,
                ),
            )
            updateVideoPlayback()
        }

        /** The hinge reached fully open or closed; see FoldEvent.PostureChanged. */
        fun onPosture(screen: ScreenKind) {
            dispatch(FoldEvent.PostureChanged(now(), screen, sessionLastShown = FoldSession.lastShown))
        }

        /** Launchers report taps on empty home-screen space; live liquid ripples there. */
        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: android.os.Bundle?, resultRequested: Boolean): android.os.Bundle? {
            if (action == android.app.WallpaperManager.COMMAND_TAP) onTap(x, y)
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        private fun onTap(x: Int, y: Int) {
            val kind = state.surface?.kind ?: return
            val scene = scenes[kind] ?: return
            val spec = scene.liquid ?: return
            if (!spec.touch || !livingAllowed(scene) || surfaceWidth <= 0 || surfaceHeight <= 0) return
            val (wx, wy) = scene.worldAt(x / surfaceWidth.toFloat(), y / surfaceHeight.toFloat()) ?: return
            scene.liquidMotion.tap(wx, wy, now())
            trace("LIQUID_TAP", PocEvents.detail("x" to x, "y" to y))
        }

        override fun onComputeColors(): WallpaperColors? {
            val manifest = packId?.let { container.packStore.get(it)?.manifest } ?: return null
            return WallpaperColors(
                Color.valueOf(LoadedScene.parseColor(manifest.colors.primary, Color.WHITE)),
                Color.valueOf(LoadedScene.parseColor(manifest.colors.secondary, Color.GRAY)),
                Color.valueOf(LoadedScene.parseColor(manifest.colors.tertiary, Color.DKGRAY)),
            )
        }

        // ------------------------------------------------------------ reducer plumbing

        private fun dispatch(event: FoldEvent) {
            queue.addLast(event)
            if (dispatching) return
            dispatching = true
            try {
                while (queue.isNotEmpty()) {
                    val e = queue.removeFirst()
                    val before = state.phase
                    val result = FoldStateReducer.reduce(state, e)
                    state = result.state
                    if (state.phase != before) {
                        trace("PHASE", PocEvents.detail(PocEvents.FROM to before, PocEvents.TO to state.phase, "on" to e.javaClass.simpleName))
                    }
                    result.commands.forEach(::execute)
                }
            } finally {
                dispatching = false
            }
        }

        private fun execute(command: FoldCommand) {
            when (command) {
                is FoldCommand.LoadScene -> loadScene(command.kind)
                is FoldCommand.DrawPlaceholder -> drawPlaceholder(command.kind)
                is FoldCommand.DrawStatic -> drawStatic(command.kind)
                is FoldCommand.StartTransition -> startTransition(command.kind, command.screen, command.live)
                is FoldCommand.CutTransition -> {
                    trace(PocEvents.TRANSITION_CUT, PocEvents.detail(PocEvents.REASON to command.reason.name))
                    transitionKind = null
                    stopFrames()
                }
                FoldCommand.StopFrames -> stopFrames()
                is FoldCommand.MarkShown -> FoldSession.markShown(command.kind)
            }
        }

        // ------------------------------------------------------------ assets

        private fun resolvePackId(): String? {
            val store = container.packStore
            val pending = settings.pendingPackId?.takeIf { store.get(it) != null }
            if (isPreview && pending != null) return pending
            // Just after "Set wallpaper", the new live engine shows the pack being applied even
            // before the app has confirmed it, so the old pack never flashes up.
            val recentApply = System.currentTimeMillis() - settings.applyStartedAtMs < PENDING_WINDOW_MS
            if (!isPreview && pending != null && recentApply) return pending
            settings.activePackId?.takeIf { store.get(it) != null }?.let { return it }
            // Recovery: the last applied pack that is still intact, then any pack at all.
            settings.applyHistory.firstOrNull { store.get(it.packId) != null }?.let { return it.packId }
            return store.list().firstOrNull()?.id
        }

        /** The store changed (for example the default pack finished); re-resolve if we had nothing. */
        fun onPacksChanged() {
            val next = resolvePackId()
            if (next != packId) {
                trace("PACK_SWITCH", PocEvents.detail(PocEvents.FROM to packId, PocEvents.TO to next, PocEvents.VIA to "store"))
                switchPack(next)
            }
        }

        private fun switchPack(next: String?) {
            packId = next
            loadToken++
            releaseScenes()
            readPlaceholderColors()
            notifyColorsChanged()
            dispatch(FoldEvent.PackChanged(now()))
        }

        private fun readPlaceholderColors() {
            val manifest = packId?.let { container.packStore.get(it)?.manifest } ?: return
            placeholderTop = LoadedScene.parseColor(manifest.colors.background, placeholderTop)
            placeholderBottom = LoadedScene.parseColor(manifest.colors.tertiary, placeholderBottom)
        }

        private fun loadScene(kind: ScreenKind) {
            scenes[kind]?.let {
                dispatch(FoldEvent.SceneReady(now(), kind))
                return
            }
            val pack = packId
            if (pack == null) {
                dispatch(FoldEvent.SceneFailed(now(), kind))
                return
            }
            val token = loadToken
            val dir = container.packStore.dir(pack)
            val longSide = max(surfaceWidth, surfaceHeight)
            val started = SystemClock.elapsedRealtime()
            try {
                decoder.execute {
                    val scene = try {
                        SceneLoader.load(dir, kind, if (isPreview) longSide else 0, gpu = true)
                    } catch (e: OutOfMemoryError) {
                        null
                    }
                    handler.post {
                        if (token != loadToken) {
                            scene?.recycle()
                            return@post
                        }
                        if (scene == null) {
                            trace(PocEvents.SCENE_FAILED, PocEvents.detail(PocEvents.KIND to kind, "pack" to pack))
                            dispatch(FoldEvent.SceneFailed(now(), kind))
                        } else {
                            scenes[kind]?.recycle()
                            scenes[kind] = scene
                            attachVideo(scene)
                            trace(
                                PocEvents.SCENE_READY,
                                PocEvents.detail(PocEvents.KIND to kind, PocEvents.LOAD_MS to SystemClock.elapsedRealtime() - started, "kb" to scene.byteCount / 1024),
                            )
                            dispatch(FoldEvent.SceneReady(now(), kind))
                            preloadOther(kind)
                        }
                    }
                }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                dispatch(FoldEvent.SceneFailed(now(), kind))
            }
        }

        /** Warm the other screen's scene so the fold switch can start animating on its first frame. */
        private fun preloadOther(kind: ScreenKind) {
            if (isPreview || !profile.isFoldable) return
            val other = if (kind == ScreenKind.MAIN) ScreenKind.COVER else ScreenKind.MAIN
            if (scenes.containsKey(other)) return
            val pack = packId ?: return
            val token = loadToken
            val dir = container.packStore.dir(pack)
            try {
                decoder.execute {
                    val scene = try {
                        SceneLoader.load(dir, other, gpu = true)
                    } catch (e: OutOfMemoryError) {
                        null
                    }
                    handler.post {
                        if (token != loadToken || scene == null || scenes.containsKey(other)) {
                            scene?.recycle()
                        } else {
                            scenes[other] = scene
                            attachVideo(scene)
                            trace("SCENE_PRELOADED", PocEvents.detail(PocEvents.KIND to other))
                        }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
            }
        }

        private fun releaseScenes() {
            scenes.values.forEach { it.recycle() }
            scenes.clear()
            video?.let {
                it.removeListener(videoListener)
                it.release()
                trace("VIDEO_RELEASE")
            }
            video = null
            if (videoDrawScheduled) Choreographer.getInstance().removeFrameCallback(videoDraw)
            videoDrawScheduled = false
        }

        // ------------------------------------------------------------ video packs

        /** A video pack's scene arrived: start (or share) its player and hand it the frames. */
        private fun attachVideo(scene: LoadedScene) {
            val spec = scene.video ?: return
            val file = scene.videoFile ?: return
            val source = video ?: try {
                val overscan = (-(scene.layers.firstOrNull()?.first?.left ?: 0f)).coerceIn(0f, 0.25f)
                VideoFrameSource(this@FoldWallpaperService, file, spec, overscan).also {
                    it.addListener(videoListener)
                    video = it
                    trace("VIDEO_ATTACH", PocEvents.detail("fps" to spec.frameRate, "ms" to spec.durationMs))
                }
            } catch (e: Exception) {
                trace("VIDEO_FAILED", e.javaClass.simpleName)
                return
            }
            scene.videoFrames = source
            updateVideoPlayback()
        }

        /** Video plays only while seen and while motion is welcome; otherwise it holds its frame. */
        private fun updateVideoPlayback() {
            val source = video ?: return
            val play = state.visible && settings.livingMotion != LivingMotion.OFF && state.policy.motionAllowed
            source.setPlaying(play)
            voteIdleFrameRate()
        }

        /** A new video frame: draw it on the next vsync, unless a transition is drawing anyway. */
        private fun onVideoFrame() {
            if (!state.visible || transitionKind != null || videoDrawScheduled) return
            val kind = state.surface?.kind ?: return
            if (scenes[kind]?.video == null) return
            videoDrawScheduled = true
            Choreographer.getInstance().postFrameCallback(videoDraw)
        }

        // ------------------------------------------------------------ frame rate

        /**
         * Tells the display what this wallpaper needs: the full refresh rate while a fold animation
         * runs (120 Hz on the Fold7), the clip's own rate while a video plays, so every frame is
         * shown for the same time, and nothing in particular when still.
         */
        private fun voteFrameRate(fps: Float, fixedSource: Boolean) {
            if (fps == frameRateVote) return
            frameRateVote = fps
            try {
                surfaceHolder?.surface?.takeIf { it.isValid }?.setFrameRate(
                    fps,
                    if (fixedSource) Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE else Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                )
            } catch (_: Exception) {
            }
        }

        private fun voteIdleFrameRate() {
            if (transitionKind != null) return
            val kind = state.surface?.kind
            val spec = kind?.let { scenes[it]?.video }
            when {
                // A clip that failed to play shows a still frame: nothing to pace.
                spec != null && state.visible && video?.failed != true -> voteFrameRate(spec.frameRate, fixedSource = true)
                ambientRunning -> voteFrameRate(AMBIENT_FPS_FULL.toFloat(), fixedSource = false)
                else -> voteFrameRate(0f, fixedSource = false)
            }
        }

        /** Called by the service when memory is tight: keep only what is on screen. */
        fun trimMemory() {
            val current = state.surface?.kind
            val drop = scenes.keys.filter { it != current }
            drop.forEach { scenes.remove(it)?.recycle() }
            if (drop.isNotEmpty()) trace("TRIM", PocEvents.detail("dropped" to drop.joinToString(",")))
        }

        // ------------------------------------------------------------ drawing

        private fun withCanvas(block: (Canvas) -> Unit) {
            val holder = surfaceHolder ?: return
            var canvas: Canvas? = null
            try {
                canvas = holder.lockHardwareCanvas()
                if (canvas != null) block(canvas)
            } catch (e: IllegalStateException) {
                // Surface went away between the check and the lock: the reducer will hear about it.
            } catch (e: IllegalArgumentException) {
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        /**
         * The pose between folds: the rest, except on the lock screen of a wallpaper whose
         * picture itself moves (NATURAL), which waits in the first pose of the motion that plays
         * when the phone is unlocked, so unlocking continues it without a jump.
         */
        private fun restProgress(scene: LoadedScene): Float? {
            val natural = scene.entryMotion?.style == FoldStyle.NATURAL.name
            return if (natural && state.locked && state.policy.waitForUnlock && state.policy.motionAllowed) 0f else null
        }

        private fun drawStatic(kind: ScreenKind) {
            val scene = scenes[kind]
            withCanvas { c ->
                if (scene != null) drawer.draw(c, c.width, c.height, scene, restProgress(scene), frameState(scene))
                else drawer.drawPlaceholder(c, c.width, c.height, placeholderTop, placeholderBottom)
            }
            if (scene != null && state.visible && transitionKind == null && livingAllowed(scene)) startAmbient()
        }

        // ------------------------------------------------------------ living motion

        private fun livingAllowed(scene: LoadedScene): Boolean =
            scene.isAlive && settings.livingMotion != LivingMotion.OFF && state.policy.motionAllowed

        private fun frameState(scene: LoadedScene?): FrameState {
            if (scene == null || !livingAllowed(scene)) return FrameState(timeMs = now())
            val subtle = settings.livingMotion == LivingMotion.SUBTLE
            return FrameState(timeMs = now(), ambient = true, tiltX = tilt.x, tiltY = tilt.y, density = if (subtle) 0.6f else 1f)
        }

        private fun ambientIntervalNanos(scene: LoadedScene): Long {
            // Full: 60 fps for smooth drift and twinkle; Subtle saves battery at 30.
            val fps = if (settings.livingMotion == LivingMotion.SUBTLE) AMBIENT_FPS_SUBTLE else maxOf(scene.ambient?.fps ?: 30, AMBIENT_FPS_FULL)
            return 1_000_000_000L / fps.coerceIn(12, AMBIENT_FPS_FULL)
        }

        private fun startAmbient() {
            if (ambientRunning) return
            ambientRunning = true
            voteIdleFrameRate()
            if (settings.tiltParallax && settings.livingMotion == LivingMotion.FULL) tilt.start()
            trace("AMBIENT_START", PocEvents.detail("mode" to settings.livingMotion, "tilt" to settings.tiltParallax))
            scheduleFrame()
        }

        private fun stopAmbient() {
            if (!ambientRunning) return
            ambientRunning = false
            voteIdleFrameRate()
            tilt.stop()
            trace("AMBIENT_STOP")
        }

        /** Settings or power state changed: restart or stop the loop to match. */
        private fun refreshAmbient() {
            if (transitionKind != null) return
            stopAmbient()
            if (!dispatching) stopFrames()
            val kind = state.surface?.kind ?: return
            if (state.visible) drawStatic(kind)
        }

        private fun drawPlaceholder(kind: ScreenKind) {
            // Prefer the other screen's scene over a flat colour: it is the same world.
            val other = scenes[if (kind == ScreenKind.MAIN) ScreenKind.COVER else ScreenKind.MAIN]
            withCanvas { c ->
                if (other != null) drawer.draw(c, c.width, c.height, other, null)
                else drawer.drawPlaceholder(c, c.width, c.height, placeholderTop, placeholderBottom)
            }
        }

        private fun startTransition(kind: TransitionKind, screen: ScreenKind, live: Boolean) {
            val scene = scenes[screen]
            val motion = scene?.entryMotion
            if (scene == null || motion == null) {
                dispatch(FoldEvent.TransitionFinished(now()))
                return
            }
            transitionKind = kind
            transitionDurationMs = motion.durationMs
            transitionStartNanos = System.nanoTime()
            // A switch with the screen on happens under the system's own switch animation: hold
            // the first frame for a moment, and never let a frozen display skip the motion.
            val hold = if (live) state.policy.liveSwitchHoldMs else 0L
            clock = TransitionClock(motion.durationMs, hold).also { it.start(transitionStartNanos) }
            val sincePosture = FoldSession.postureChangedAtMs?.let { now() - it }
            frameCount = 0
            jankFrames = 0
            maxGapNanos = 0L
            lastFrameNanos = transitionStartNanos
            refreshHz = try {
                displayContext?.display?.refreshRate ?: 60f
            } catch (e: Exception) {
                60f
            }.coerceIn(30f, 240f)
            // Ask for the panel's full refresh rate while the fold animation runs.
            voteFrameRate(refreshHz, fixedSource = false)
            trace(
                PocEvents.TRANSITION_START,
                PocEvents.detail(PocEvents.KIND to kind, PocEvents.DURATION_MS to motion.durationMs, PocEvents.SINCE_POSTURE_MS to sincePosture, "live" to live, "holdMs" to hold),
            )
            drawTransitionFrame(transitionStartNanos)
            scheduleFrame()
        }

        private fun onFrame(frameTimeNanos: Long) {
            if (transitionKind == null) {
                onAmbientFrame(frameTimeNanos)
                return
            }
            recordFrame(frameTimeNanos)
            val done = drawTransitionFrame(frameTimeNanos)
            if (done) {
                transitionKind = null
                trace(
                    PocEvents.TRANSITION_END,
                    PocEvents.detail(
                        PocEvents.FRAMES to frameCount,
                        PocEvents.JANK_FRAMES to jankFrames,
                        PocEvents.MAX_GAP_MS to maxGapNanos / 1_000_000,
                        PocEvents.REFRESH_HZ to refreshHz.toInt(),
                        PocEvents.ELAPSED_MS to (frameTimeNanos - transitionStartNanos) / 1_000_000,
                    ),
                )
                dispatch(FoldEvent.TransitionFinished(now()))
                voteIdleFrameRate()
            } else {
                scheduleFrame()
            }
        }

        /** Living motion between folds, capped to the pack's frame rate to save battery. */
        private fun onAmbientFrame(frameTimeNanos: Long) {
            if (!ambientRunning || !state.visible) return stopAmbient()
            val kind = state.surface?.kind ?: return stopAmbient()
            val scene = scenes[kind] ?: return stopAmbient()
            if (!livingAllowed(scene)) return stopAmbient()
            if (frameTimeNanos - lastAmbientNanos >= ambientIntervalNanos(scene) - 2_000_000L) {
                lastAmbientNanos = frameTimeNanos
                tilt.step()
                withCanvas { c -> drawer.draw(c, c.width, c.height, scene, restProgress(scene), frameState(scene)) }
            }
            scheduleFrame()
        }

        /**
         * Frame pacing for the POC: a frame counts as jank when it arrives more than 1.5 refresh
         * intervals after the previous one (blueprint section 24 target: jank under 5%).
         */
        private fun recordFrame(frameTimeNanos: Long) {
            val gap = frameTimeNanos - lastFrameNanos
            lastFrameNanos = frameTimeNanos
            frameCount++
            if (gap > maxGapNanos) maxGapNanos = gap
            if (gap > 1.5 * 1_000_000_000L / refreshHz) jankFrames++
        }

        /** @return true when the last frame has been drawn. */
        private fun drawTransitionFrame(frameTimeNanos: Long): Boolean {
            val kind = state.surface?.kind ?: return true
            val scene = scenes[kind] ?: return true
            val progress = clock?.advance(frameTimeNanos) ?: 1f
            withCanvas { c -> drawer.draw(c, c.width, c.height, scene, progress, frameState(scene)) }
            return progress >= 1f
        }

        private fun scheduleFrame() {
            if (frameScheduled) return
            frameScheduled = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }

        private fun stopFrames() {
            if (frameScheduled) Choreographer.getInstance().removeFrameCallback(frameCallback)
            frameScheduled = false
            stopAmbient()
        }

        // ------------------------------------------------------------ policy and helpers

        private fun currentPolicy(): MotionPolicy {
            val animatorScale = try {
                Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            } catch (e: Exception) {
                1f
            }
            val power = getSystemService(PowerManager::class.java)
            return MotionPolicy(
                reducedMotion = settings.reducedMotion || animatorScale == 0f,
                powerSave = power?.isPowerSaveMode == true,
                liveSwitchHoldMs = settings.liveSwitchHoldMs,
                waitForUnlock = settings.waitForUnlock,
            )
        }

        private fun now() = SystemClock.elapsedRealtime()

        private fun trace(event: String, detail: String = "") = PocTrace.log(id, event, detail)
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Not on UI_HIDDEN: leaving the app must not cost the next fold its preloaded scene.
        if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_BACKGROUND) {
            PocTrace.log("service", "TRIM_MEMORY", level.toString())
            engines.forEach { it.trimMemory() }
        }
    }

    private companion object {
        const val AMBIENT_FPS_FULL = 60
        const val AMBIENT_FPS_SUBTLE = 30
        val COUNTER = AtomicInteger()
        const val PENDING_WINDOW_MS = 10 * 60_000L
        const val DEFAULT_IDEA = "brand_flow"
    }
}
