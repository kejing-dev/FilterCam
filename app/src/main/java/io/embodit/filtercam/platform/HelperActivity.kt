package io.embodit.filtercam.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.embodit.filtercam.app.FcStore

/**
 * A see-through activity that runs one system dialog for the store and finishes: the photo picker (design T8, no
 * READ_MEDIA_IMAGES), the .cube document picker (T9, SAF) and the CAMERA + SPATIAL_DATA request (ActivityResultContracts) when the camera
 * source is picked or on the first camera shutter (S07). Spatial windows have no activity-result owner of their own, so
 * the dialogs go through here.
 *
 * CAMERA + SPATIAL_DATA (2026-11-14 headset: the app never asked, the user got no dialog): the viewfinder's own passthrough
 * in Secure Mode needs no permission; reading the camera frame back (the shutter) needs CAMERA per the SDK docs and
 * CAMERA + SPATIAL_DATA per the 透写台 headset test (design container.permissions), so both are asked together.
 */
class HelperActivity : ComponentActivity() {
    private var mode: String? = null
    private var answered = false

    private val askCamera =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = PERMISSIONS.all { result[it] == true || checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            // refused with "don't ask again" (or by policy): the system will not show the dialog again, only Settings helps
            val canAskAgain = granted || PERMISSIONS.any { shouldShowRequestPermissionRationale(it) }
            FcLog.i("PERMISSION result $result granted=$granted canAskAgain=$canAskAgain")
            answered = true
            FcStore.permissionResult(granted, canAskAgain)
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE)
        if (savedInstanceState != null) return
        FcLog.i("HELPER $mode")
        when (mode) {
            MODE_PHOTO -> {
                val pick =
                    if (runCatching { MediaStore.getPickImagesMaxLimit() }.isSuccess) Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
                    else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
                start(pick, REQ_PHOTO)
            }
            MODE_LUT -> start(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_LUT)
            MODE_PERMISSION -> askCamera.launch(PERMISSIONS)
            else -> finish()
        }
    }

    /** Closed before the dialog answered (BACK, the system taking it away): still tell the store what the permission is now. */
    override fun onDestroy() {
        super.onDestroy()
        if (mode == MODE_PERMISSION && !answered && isFinishing) {
            val granted = granted(this)
            FcLog.i("PERMISSION helper closed without an answer: granted=$granted")
            answered = true
            FcStore.permissionResult(granted, canAskAgain = true)
        }
    }

    private fun start(intent: Intent, req: Int) {
        runCatching { startActivityForResult(intent, req) }.onFailure {
            FcLog.w("HELPER no activity for $mode: $it")
            deliver(null)
            finish()
        }
    }

    @Deprecated("platform API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        deliver(if (resultCode == RESULT_OK) data?.data else null)
        finish()
    }

    private fun deliver(uri: android.net.Uri?) {
        when (mode) {
            MODE_PHOTO -> FcStore.photoPicked(uri)
            MODE_LUT -> FcStore.lutPicked(uri)
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_PHOTO = "photo"
        const val MODE_LUT = "lut"
        const val MODE_PERMISSION = "permission"
        private const val REQ_PHOTO = 1
        private const val REQ_LUT = 2

        /** Design N4 / N16: reading the camera frame back (see the class note). */
        val PERMISSIONS = arrayOf(android.Manifest.permission.CAMERA, "com.picovr.permission.SPATIAL_DATA")

        fun launch(context: Context, mode: String) {
            context.startActivity(Intent(context, HelperActivity::class.java).putExtra(EXTRA_MODE, mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /** 「去设置」: this app's page in system settings (permissions are one tap from there). */
        fun openAppSettings(context: Context) {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(i) }.onFailure { FcLog.w("SETTINGS app page failed: $it") }
        }

        fun granted(context: Context): Boolean = PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}
