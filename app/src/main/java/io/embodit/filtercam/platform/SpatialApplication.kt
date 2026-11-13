package io.embodit.filtercam.platform

import android.app.Application
import android.os.Build
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
        launch(::mainApp)
    }
}
