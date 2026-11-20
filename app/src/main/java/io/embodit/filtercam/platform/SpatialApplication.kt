package io.embodit.filtercam.platform

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.filtercam.app.FcStore
import io.embodit.filtercam.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val emulator = Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") || Build.PRODUCT.contains("sdk") || Build.FINGERPRINT.contains("generic")
        FcStore.init(this, Prefs(this), emulator)
        FcStore.lensHost = LensController(this, hasCamera = !emulator)
        FcLog.i("APP start hardware=${Build.HARDWARE} product=${Build.PRODUCT} emulator=$emulator")
        guardBack()
        launch(::mainApp)
    }

    /**
     * D60 补 (QA #6 q6-1): controller B = system BACK. Each window (W1, W2, the room Stage) is its own stub Activity, and
     * BACK in W2 used to finish it (the filter window just vanished). BACK is caught at the earliest layer of every
     * window ([BackKeys]) and goes to [FcStore.onBack]; the OnBackPressedCallback stays as the last fallback.
     */
    private fun guardBack() {
        BackKeys.handler = { where -> FcStore.onBack(where) }
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityCreated(a: Activity, b: Bundle?) {
                    BackKeys.wrap(a)
                    val ca = a as? ComponentActivity
                    if (ca == null) {
                        FcLog.w("BACK guard: ${a.javaClass.simpleName} is not a ComponentActivity")
                        return
                    }
                    ca.onBackPressedDispatcher.addCallback(
                        ca,
                        object : OnBackPressedCallback(true) {
                            override fun handleOnBackPressed() = BackKeys.handler("dispatcher:${a.javaClass.simpleName}")
                        },
                    )
                    FcLog.i("BACK guard on ${a.javaClass.simpleName}")
                }

                override fun onActivityPostCreated(a: Activity, b: Bundle?) {
                    BackKeys.wrap(a)
                    BackKeys.sweep("created")
                }

                override fun onActivityStarted(a: Activity) = BackKeys.sweep("started")

                override fun onActivityResumed(a: Activity) {
                    BackKeys.wrap(a)
                    BackKeys.sweep("resumed")
                }

                override fun onActivityPostResumed(a: Activity) = BackKeys.sweep("post-resumed")

                override fun onActivityPaused(a: Activity) = Unit

                override fun onActivityStopped(a: Activity) = Unit

                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit

                override fun onActivityDestroyed(a: Activity) = Unit
            },
        )
    }
}
