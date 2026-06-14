package dev.po4yka.frameport

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.po4yka.frameport.camera.data.AppStartupInitializer
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class FrameportApplication : Application() {
    /**
     * Injected by Hilt after [onCreate] super call. Runs background startup tasks
     * (LMK exit-reason scan) off the main thread with failure isolation.
     * Called at the END of [onCreate] so the Hilt graph is fully assembled first.
     * See [AppStartupInitializer] and G9 in CLAUDE.md.
     */
    @Inject
    lateinit var appStartupInitializer: AppStartupInitializer

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // Must be called AFTER super.onCreate() so Hilt has completed member injection.
        appStartupInitializer.initialize()
    }
}
