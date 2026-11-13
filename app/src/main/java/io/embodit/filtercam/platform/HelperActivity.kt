package io.embodit.filtercam.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import io.embodit.filtercam.app.FcStore

/**
 * A see-through activity that runs one system dialog for the store and finishes: the photo picker (design T8, no
 * READ_MEDIA_IMAGES), the .cube document picker (T9, SAF) and the CAMERA + SPATIAL_DATA request on the first camera
 * shutter (S07). Spatial windows have no activity-result owner of their own, so the dialogs go through here.
 */
class HelperActivity : Activity() {
    private var mode: String? = null

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
            MODE_PERMISSION -> requestPermissions(PERMISSIONS, REQ_PERMISSION)
            else -> finish()
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        FcLog.i("PERMISSION result ${permissions.joinToString()} -> ${grantResults.joinToString()}")
        FcStore.permissionResult(granted)
        finish()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_PHOTO = "photo"
        const val MODE_LUT = "lut"
        const val MODE_PERMISSION = "permission"
        private const val REQ_PHOTO = 1
        private const val REQ_LUT = 2
        private const val REQ_PERMISSION = 3

        /** Design N4 / N16: camera read-back needs both. */
        val PERMISSIONS = arrayOf(android.Manifest.permission.CAMERA, "com.picovr.permission.SPATIAL_DATA")

        fun launch(context: Context, mode: String) {
            context.startActivity(Intent(context, HelperActivity::class.java).putExtra(EXTRA_MODE, mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        fun granted(context: Context): Boolean = PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}
