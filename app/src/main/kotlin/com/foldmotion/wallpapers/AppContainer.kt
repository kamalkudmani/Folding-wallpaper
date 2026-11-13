package com.foldmotion.wallpapers

import android.app.Application
import android.content.Context
import com.foldmotion.core.pack.FoldPackValidator
import com.foldmotion.wallpapers.apply.ApplyController
import com.foldmotion.wallpapers.data.AppSettings
import com.foldmotion.wallpapers.device.DeviceInfo
import com.foldmotion.wallpapers.generator.GenerationController
import com.foldmotion.wallpapers.generator.LocalPackGenerator
import com.foldmotion.wallpapers.pack.PackStore
import com.foldmotion.wallpapers.telemetry.PocTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-written dependency container. Every collaborator is created here and passed in through
 * constructors, so each one can be replaced by a fake in tests (blueprint section 12).
 */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    /** Outlives activities: a generation keeps running while the user folds the phone or leaves. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = AppSettings(app)
    val deviceInfo = DeviceInfo(app, settings)
    val validator = FoldPackValidator()
    val packStore = PackStore(app, settings)
    val generator = LocalPackGenerator(packStore, validator)
    val generation = GenerationController(generator, scope)
    val apply = ApplyController(app, settings, packStore)

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context).also { instance = it }
            }
    }
}

class FoldMotionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PocTrace.init(this)
        val container = AppContainer.get(this)
        container.packStore.cleanTemporary()
        container.apply.reconcileOnStart()
    }
}
