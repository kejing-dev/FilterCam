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
import io.embodit.filtercam.domain.CamPerm
import io.embodit.filtercam.domain.CamPerms
import io.embodit.filtercam.domain.PermCard

/**
 * A see-through activity that runs one system dialog for the store and finishes: the photo picker (design T8, no
 * READ_MEDIA_IMAGES), the .cube document picker (T9, SAF) and the two camera permissions. Spatial windows have no
 * activity-result owner of their own, so the dialogs go through here.
 *
 * Permissions (design r4 S00a, profile D58): asked one at a time, Spatial Data first, then Camera, only the missing
 * ones; the store is told which one is being asked (the card's 「正在问 1/2」) and, at the end, what is still off and
 * which were refused for good (no rationale after a refusal: "don't ask again", or USER_FIXED refused with no dialog).
 */
class HelperActivity : ComponentActivity() {
    private var mode: String? = null
    private var answered = false

    /** Still to ask, in order; and everything asked in this round. */
    private val queue = ArrayDeque<CamPerm>()
    private val requested = LinkedHashSet<CamPerm>()

    private val askOne =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            FcLog.i("PERMISSION answered ${requested.lastOrNull()?.name} granted=$ok")
            if (queue.isNotEmpty()) askNext() else finishPermissions()
        }

    private fun askNext() {
        val p = queue.removeFirst()
        requested += p
        FcStore.permissionAsking(p)
        FcLog.i("PERMISSION asking ${p.name} (${PermCard.step(p)}/2)")
        askOne.launch(p.id)
    }

    private fun finishPermissions() {
        val missing = missing(this)
        val blocked = CamPerms.blockedAfterAnswer(requested, missing) { shouldShowRequestPermissionRationale(it.id) }
        FcLog.i("PERMISSION result missing=${missing.map { it.name }} blocked=${blocked.map { it.name }}")
        answered = true
        FcStore.permissionResult(missing, blocked)
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
            MODE_PERMISSION -> {
                queue.addAll(CamPerm.values().filter { it in missing(this) })
                if (queue.isEmpty()) finishPermissions() else askNext()
            }
            else -> finish()
        }
    }

    /** Closed before the dialog answered (BACK, the system taking it away): still tell the store what the permission is now. */
    override fun onDestroy() {
        super.onDestroy()
        if (mode == MODE_PERMISSION && !answered && isFinishing) {
            val missing = missing(this)
            FcLog.i("PERMISSION helper closed without an answer: missing=${missing.map { it.name }}")
            answered = true
            FcStore.permissionResult(missing, emptySet())
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

        fun launch(context: Context, mode: String) {
            context.startActivity(Intent(context, HelperActivity::class.java).putExtra(EXTRA_MODE, mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /**
         * 「去设置」: this app's own page in system settings. The permission list itself (MANAGE_APP_PERMISSIONS) needs
         * GRANT_RUNTIME_PERMISSIONS, which apps cannot hold (PermissionController manifest, PICO OS 2026-11-18); on PICO OS
         * the app's page opens on its permission list (headset log 2026-11-17: InstalledAppDetails → AppPermissionGroupsFragment).
         */
        fun openAppSettings(context: Context) {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(i) }.onFailure { FcLog.w("SETTINGS app page failed: $it") }
        }

        /** CAMERA and SPATIAL_DATA, each with its own checkSelfPermission (profile D58). */
        fun missing(context: Context): Set<CamPerm> = CamPerm.missing { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}
