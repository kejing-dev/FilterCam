package io.embodit.filtercam.domain

import io.embodit.filtercam.domain.CamPerms.Kind
import io.embodit.filtercam.domain.CameraFlow.Complete
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Profile D58 (2026-11-17 headset): CAMERA granted, SPATIAL_DATA refused with USER_FIXED, and the app only said "camera
 * access is off". Each permission is checked on its own, the missing one is named, and the picture starts on resume.
 */
class CamPermsTest {
    private fun granted(vararg ids: String): (String) -> Boolean = { it in ids }

    @Test
    fun eachPermissionIsCheckedOnItsOwn() {
        assertEquals(CamPerm.ALL, CamPerm.missing(granted()))
        assertEquals(setOf(CamPerm.SPATIAL_DATA), CamPerm.missing(granted(CamPerm.CAMERA.id)))
        assertEquals(setOf(CamPerm.CAMERA), CamPerm.missing(granted(CamPerm.SPATIAL_DATA.id)))
        assertEquals(emptySet<CamPerm>(), CamPerm.missing(granted(CamPerm.CAMERA.id, CamPerm.SPATIAL_DATA.id)))
        assertEquals("com.picovr.permission.SPATIAL_DATA", CamPerm.SPATIAL_DATA.id)
        assertEquals("android.permission.CAMERA", CamPerm.CAMERA.id)
    }

    @Test
    fun bothOnMeansNoCard() = assertNull(CamPerms.prompt(emptySet(), denied = true, blocked = CamPerm.ALL))

    @Test
    fun neverAskedNamesOnlyWhatIsMissing() {
        val onlySpatial = CamPerms.prompt(setOf(CamPerm.SPATIAL_DATA), denied = false, blocked = emptySet())!!
        assertEquals(Kind.NEED, onlySpatial.kind)
        assertEquals(listOf(CamPerm.SPATIAL_DATA), onlySpatial.missing)
        val onlyCamera = CamPerms.prompt(setOf(CamPerm.CAMERA), denied = false, blocked = emptySet())!!
        assertEquals(listOf(CamPerm.CAMERA), onlyCamera.missing)
        // both: camera first, always the same order
        assertEquals(listOf(CamPerm.CAMERA, CamPerm.SPATIAL_DATA), CamPerms.prompt(setOf(CamPerm.SPATIAL_DATA, CamPerm.CAMERA), false, emptySet())!!.missing)
    }

    /** The headset state of 2026-11-17 (apps/filter-cam/qa/headset-perm-2026-11-17.txt). */
    @Test
    fun cameraOnSpatialDataFixedSaysSpatialDataAndSettingsOnly() {
        val p = CamPerms.prompt(setOf(CamPerm.SPATIAL_DATA), denied = true, blocked = setOf(CamPerm.SPATIAL_DATA))!!
        assertEquals(Kind.BLOCKED, p.kind)
        assertEquals(listOf(CamPerm.SPATIAL_DATA), p.missing)
        assertEquals(listOf(CamPerm.SPATIAL_DATA), p.blocked)
        assertFalse("no 「再问一次」: the system will not show the dialog", p.canAskAgain)
        assertFalse(CamPerm.CAMERA in p.missing)
    }

    @Test
    fun refusedButAskableOffersAskAgainForThatOne() {
        val p = CamPerms.prompt(setOf(CamPerm.CAMERA), denied = true, blocked = emptySet())!!
        assertEquals(Kind.DENIED, p.kind)
        assertTrue(p.canAskAgain)
        assertEquals(listOf(CamPerm.CAMERA), p.askable)
    }

    @Test
    fun mixedAsksAgainForTheAskableAndPointsToSettingsForTheBlocked() {
        val p = CamPerms.prompt(CamPerm.ALL, denied = true, blocked = setOf(CamPerm.SPATIAL_DATA))!!
        assertEquals(Kind.DENIED, p.kind)
        assertTrue(p.canAskAgain)
        assertEquals(listOf(CamPerm.CAMERA), p.askable)
        assertEquals(listOf(CamPerm.SPATIAL_DATA), p.blocked)
    }

    @Test
    fun aRefusalWithoutRationaleIsBlockedAGrantIsNot() {
        // USER_FIXED: the system refuses SPATIAL_DATA with no dialog, rationale false; CAMERA was not even asked
        val blocked = CamPerms.blockedAfterAnswer(setOf(CamPerm.SPATIAL_DATA), setOf(CamPerm.SPATIAL_DATA)) { false }
        assertEquals(setOf(CamPerm.SPATIAL_DATA), blocked)
        // first refusal: the system will ask again (rationale true)
        assertEquals(emptySet<CamPerm>(), CamPerms.blockedAfterAnswer(CamPerm.ALL, setOf(CamPerm.CAMERA)) { true })
        // granted ones are never blocked, whatever the rationale says
        assertEquals(emptySet<CamPerm>(), CamPerms.blockedAfterAnswer(CamPerm.ALL, emptySet()) { false })
    }

    // ---- the picture starts on resume once both are on (D58 item 3)

    @Test
    fun lastPermissionOnInSettingsOpensTheViewfinderOnResume() {
        assertEquals(Complete.OPEN, CameraFlow.onPermissionsComplete(wasMissing = true, nowComplete = true, cameraWanted = true, lensOpen = false, onCameraPage = true, inRoom = false))
    }

    @Test
    fun viewfinderOpenWithoutThePermissionGetsANewSession() {
        assertEquals(Complete.REOPEN, CameraFlow.onPermissionsComplete(true, true, cameraWanted = true, lensOpen = true, onCameraPage = true, inRoom = false))
    }

    @Test
    fun nothingStartsWhenStillMissingNotWantedElsewhereOrAlreadyOn() {
        assertEquals(Complete.NONE, CameraFlow.onPermissionsComplete(true, nowComplete = false, cameraWanted = true, lensOpen = false, onCameraPage = true, inRoom = false))
        assertEquals(Complete.NONE, CameraFlow.onPermissionsComplete(true, true, cameraWanted = false, lensOpen = false, onCameraPage = true, inRoom = false))
        assertEquals(Complete.NONE, CameraFlow.onPermissionsComplete(true, true, cameraWanted = true, lensOpen = false, onCameraPage = false, inRoom = false))
        assertEquals(Complete.NONE, CameraFlow.onPermissionsComplete(true, true, cameraWanted = true, lensOpen = false, onCameraPage = true, inRoom = true))
        assertEquals(Complete.NONE, CameraFlow.onPermissionsComplete(wasMissing = false, nowComplete = true, cameraWanted = true, lensOpen = true, onCameraPage = true, inRoom = false))
    }

    /** The whole headset story: start → SPATIAL_DATA fixed → card names it → Settings → back → picture, no restart. */
    @Test
    fun headsetStoryEndsWithThePictureWithoutRestart() {
        var missing = setOf(CamPerm.SPATIAL_DATA)
        var blocked = CamPerms.blockedAfterAnswer(missing, missing) { false }
        var lensOpen = false
        val card = CamPerms.prompt(missing, denied = true, blocked = blocked)!!
        assertEquals(listOf(CamPerm.SPATIAL_DATA), card.missing)
        assertEquals(Kind.BLOCKED, card.kind)
        // the user turns Spatial Data on in Settings and comes back: the resume re-check
        val was = missing.isNotEmpty()
        missing = CamPerm.missing { true }
        blocked = blocked intersect missing
        when (CameraFlow.onPermissionsComplete(was, missing.isEmpty(), cameraWanted = true, lensOpen = lensOpen, onCameraPage = true, inRoom = false)) {
            Complete.OPEN, Complete.REOPEN -> lensOpen = true
            Complete.NONE -> Unit
        }
        assertTrue(lensOpen)
        assertNull(CamPerms.prompt(missing, denied = true, blocked = blocked))
    }
}
