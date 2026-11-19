package io.embodit.filtercam.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design round 4 (2026-11-18 headset): one camera window, a filter window beside it, the corner thumbnail, the album on the newest photo, permissions one by one. */
class CameraWindowTest {
    // ---- c4-1: the permission card, Spatial Data first

    @Test
    fun spatialDataIsFirstEverywhere() {
        assertEquals(listOf(CamPerm.SPATIAL_DATA, CamPerm.CAMERA), CamPerm.values().toList())
        assertEquals(1, PermCard.step(CamPerm.SPATIAL_DATA))
        assertEquals(2, PermCard.step(CamPerm.CAMERA))
        assertEquals(listOf(CamPerm.SPATIAL_DATA, CamPerm.CAMERA), PermCard.askOrder(CamPerm.ALL, emptySet()))
        assertEquals(listOf(CamPerm.SPATIAL_DATA, CamPerm.CAMERA), CamPerms.prompt(CamPerm.ALL, false, emptySet())!!.missing)
    }

    @Test
    fun onlyTheMissingAreAskedAndTheBlockedAreNot() {
        assertEquals(listOf(CamPerm.CAMERA), PermCard.askOrder(setOf(CamPerm.CAMERA), emptySet()))
        assertEquals(listOf(CamPerm.CAMERA), PermCard.askOrder(CamPerm.ALL, setOf(CamPerm.SPATIAL_DATA)))
        assertEquals(emptyList<CamPerm>(), PermCard.askOrder(emptySet(), emptySet()))
    }

    @Test
    fun chipsSayWhatEachPermissionIsDoing() {
        val all = CamPerm.ALL
        assertEquals(PermChip.OFF, PermCard.chip(CamPerm.SPATIAL_DATA, all, denied = false, blocked = emptySet(), asking = null))
        assertEquals(PermChip.ASKING, PermCard.chip(CamPerm.SPATIAL_DATA, all, false, emptySet(), asking = CamPerm.SPATIAL_DATA))
        assertEquals(PermChip.OFF, PermCard.chip(CamPerm.CAMERA, all, false, emptySet(), asking = CamPerm.SPATIAL_DATA))
        // only CAMERA granted: Spatial Data is the one named, Camera has its tick
        val onlySd = setOf(CamPerm.SPATIAL_DATA)
        assertEquals(PermChip.ON, PermCard.chip(CamPerm.CAMERA, onlySd, denied = true, blocked = onlySd, asking = null))
        assertEquals(PermChip.REFUSED, PermCard.chip(CamPerm.SPATIAL_DATA, onlySd, denied = true, blocked = onlySd, asking = null))
        assertEquals(CamPerm.SPATIAL_DATA, PermCard.named(CamPerms.prompt(onlySd, true, onlySd)!!))
    }

    @Test
    fun mainButtonStartsAsksAgainOrOpensSettings() {
        assertEquals(PermCard.Action.START, PermCard.action(CamPerms.prompt(CamPerm.ALL, false, emptySet())!!))
        assertEquals(PermCard.Action.ASK_AGAIN, PermCard.action(CamPerms.prompt(setOf(CamPerm.SPATIAL_DATA), true, emptySet())!!))
        assertEquals(PermCard.Action.SETTINGS, PermCard.action(CamPerms.prompt(setOf(CamPerm.SPATIAL_DATA), true, setOf(CamPerm.SPATIAL_DATA))!!))
    }

    // ---- c4-2: the read-back session follows one rule

    private fun want(camera: Boolean = true, perms: Boolean = true, hasCamera: Boolean = true, front: Boolean = true, room: Boolean = false, failed: Boolean = false, page: Boolean = true) =
        LensPolicy.want(camera, perms, hasCamera, front, room, failed, page)

    @Test
    fun sessionRunsOnlyForTheCameraWithBothPermissionsInFront() {
        assertEquals(LensPolicy.Want.OPEN, want())
        assertEquals(LensPolicy.Want.CLOSED, want(camera = false))
        assertEquals(LensPolicy.Want.CLOSED, want(perms = false))
        assertEquals("the emulator never builds a camera pipeline", LensPolicy.Want.CLOSED, want(hasCamera = false))
        assertEquals("background (S24) closes it", LensPolicy.Want.CLOSED, want(front = false))
        assertEquals("room mode closes it", LensPolicy.Want.CLOSED, want(room = true))
        assertEquals("S21 keeps it closed until 「再试一次」", LensPolicy.Want.CLOSED, want(failed = true))
        assertEquals("album / settings pause it, the session stays", LensPolicy.Want.PAUSED, want(page = false))
    }

    @Test
    fun backFromTheBackgroundOrTheRoomTheSessionComesBackByItself() {
        assertEquals(LensPolicy.Want.CLOSED, want(front = false))
        assertEquals(LensPolicy.Want.OPEN, want(front = true))
        assertEquals(LensPolicy.Want.CLOSED, want(room = true))
        assertEquals(LensPolicy.Want.OPEN, want(room = false))
    }

    @Test
    fun aLostSessionIsReopenedOnceThenS21() {
        assertTrue(LensPolicy.afterLoss(1))
        assertFalse(LensPolicy.afterLoss(2))
    }

    // ---- c4-3: W2 beside W1, never over it

    @Test
    fun sideWindowShowsOnTheCameraPageOnlyAndNeverOverThePermissionCard() {
        assertTrue(SideWindow.shown(wanted = true, onCameraPage = true, permissionCard = false, inRoom = false, quitting = false))
        assertFalse(SideWindow.shown(wanted = false, onCameraPage = true, permissionCard = false, inRoom = false, quitting = false))
        assertFalse("album / settings fold it", SideWindow.shown(true, onCameraPage = false, permissionCard = false, inRoom = false, quitting = false))
        assertFalse("not during the permission card", SideWindow.shown(true, true, permissionCard = true, inRoom = false, quitting = false))
        assertFalse(SideWindow.shown(true, true, false, inRoom = true, quitting = false))
        assertFalse(SideWindow.shown(true, true, false, false, quitting = true))
    }

    @Test
    fun theTwoWindowsDoNotOverlapFromTheEyeAtEitherScale() {
        for (mm in listOf(1.5f, 1.74f)) {
            for (gap in listOf(0.05f, 0.10f)) {
                val l = WindowLayout(mmPerDp = mm, gapM = gap)
                assertFalse("$mm mm/dp gap $gap", l.overlaps)
                assertTrue("W2 right edge ${l.angleDeg(l.w2RightM)}° stays in the ±35° comfort zone", l.angleDeg(l.w2RightM) < 35f)
            }
        }
        // the viewfinder is bigger than v0.1.5's 0.6 m container (19.5°)
        assertTrue(WindowLayout(1.5f, 0.1f).viewfinderDeg() > 26f)
    }

    // ---- c4-4 / c4-5: the corner thumbnail and the album

    @Test
    fun shutterQueuesUpToThreeShots() {
        assertTrue(ShotQueue.canShoot(saving = 0, flashing = false))
        assertTrue(ShotQueue.canShoot(saving = 2, flashing = false))
        assertFalse(ShotQueue.canShoot(saving = 3, flashing = false))
        assertFalse("not during the flash / hold", ShotQueue.canShoot(saving = 0, flashing = true))
    }

    @Test
    fun albumDeleteShowsTheNextOneOrTheEmptyPage() {
        assertEquals(0, AlbumNav.afterDelete(0, 3))
        assertEquals(1, AlbumNav.afterDelete(1, 3))
        assertEquals("deleting the oldest shows the new oldest", 1, AlbumNav.afterDelete(2, 3))
        assertEquals(0, AlbumNav.afterDelete(0, 1))
        assertEquals(0, AlbumNav.step(0, -1, 5))
        assertEquals(4, AlbumNav.step(4, 1, 5))
        assertEquals(2, AlbumNav.step(1, 1, 5))
    }

    @Test
    fun albumReadsTheFilterFromTheFileName() {
        assertEquals("F04", AlbumNav.filterOf("FilterCam_20261118_213005_F04.jpg"))
        assertEquals("LUT:My Warm", AlbumNav.filterOf("FilterCam_20261118_213005_LUT_My Warm.jpg"))
        assertNull(AlbumNav.filterOf("FilterCam_20261118_213005_raw.jpg"))
        assertNull(AlbumNav.filterOf("IMG_0001.jpg"))
        assertEquals(0, AlbumNav.minutesAgo(1_000_000, 1_030_000))
        assertEquals(12, AlbumNav.minutesAgo(0, 12 * 60_000L + 5))
        assertNull(AlbumNav.minutesAgo(0, 61 * 60_000L))
    }

    @Test
    fun newestFirstOrderingByName() {
        // the store sorts the album by name, descending: the time stamp in the name puts the newest first
        val names = listOf("FilterCam_20261118_090000_F01.jpg", "FilterCam_20261118_213005_F04.jpg", "FilterCam_20261117_235959_F02.jpg")
        assertEquals("FilterCam_20261118_213005_F04.jpg", names.sortedDescending().first())
    }
}
