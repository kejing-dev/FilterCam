package io.embodit.filtercam.domain

import io.embodit.filtercam.domain.CameraFlow.Reopen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** QA #3 q3-1: refuse → allow → the picture is there, without restarting or leaving the page. */
class CameraFlowTest {
    /** The few facts the store keeps, driven the way the headset delivers events (dialog → ON_STOP → answer → ON_RESUME). */
    private class Sim {
        var lensOpen = true
        var asking = false
        var resumed = true
        var pending = false

        fun ask() {
            asking = true
        }

        fun stop() {
            resumed = false
            if (CameraFlow.closeOnStop(lensOpen, asking)) lensOpen = false
        }

        fun answer() {
            asking = false
            when (CameraFlow.afterAnswer(lensOpen, resumed, onCameraPage = true, inRoom = false)) {
                Reopen.NOW -> lensOpen = true
                Reopen.ON_RESUME -> pending = true
                Reopen.NONE -> Unit
            }
        }

        fun resume() {
            resumed = true
            if (CameraFlow.onResume(pending, lensOpen, onCameraPage = true, inRoom = false)) lensOpen = true
            pending = false
        }
    }

    @Test fun refuseThenAllowEndsWithTheViewfinderOpen() {
        val s = Sim()
        // first dialog: refused (the window goes to the background and back)
        s.ask(); s.stop(); s.answer(); s.resume()
        assertTrue("viewfinder after the refusal", s.lensOpen)
        // 「再问一次」 → allowed
        s.ask(); s.stop(); s.answer(); s.resume()
        assertTrue("viewfinder after 允许", s.lensOpen)
    }

    /**
     * QA #4 q4-1: on a first grant the session must be created only after the answer with the window in front (the spot
     * window has focus then, so the container lands in its slot right of the main window, not over it).
     */
    @Test fun firstGrantCreatesTheSessionOnlyAfterTheAnswerWithTheWindowInFront() {
        var lensOpen = false
        var dialogUp = false
        var resumed = true
        var pending = false
        val sessions = ArrayList<Pair<Boolean, Boolean>>() // (dialog up, window in front) at each session creation
        fun open() {
            lensOpen = true
            sessions += dialogUp to resumed
        }
        // cold start on the headset, camera picked, CAMERA not granted yet → the dialog comes
        if (CameraFlow.openWithSource(lensOpen, willAsk = true)) open()
        dialogUp = true
        resumed = false
        if (CameraFlow.closeOnStop(lensOpen, askingCamera = true)) lensOpen = false
        dialogUp = false // 允许
        when (CameraFlow.afterAnswer(lensOpen, resumed, onCameraPage = true, inRoom = false)) {
            Reopen.NOW -> open()
            Reopen.ON_RESUME -> pending = true
            Reopen.NONE -> Unit
        }
        resumed = true
        if (CameraFlow.onResume(pending, lensOpen, onCameraPage = true, inRoom = false)) open()
        assertTrue("viewfinder open after 允许", lensOpen)
        assertEquals(listOf(false to true), sessions) // exactly one session, dialog gone, window in front
        // already granted (cold start): no dialog → opens with the source at once
        assertTrue(CameraFlow.openWithSource(lensOpen = false, willAsk = false))
        assertFalse(CameraFlow.openWithSource(lensOpen = true, willAsk = false))
    }

    @Test fun ownDialogDoesNotCloseTheViewfinderButLeavingDoes() {
        assertFalse(CameraFlow.closeOnStop(lensOpen = true, askingCamera = true))
        assertTrue(CameraFlow.closeOnStop(lensOpen = true, askingCamera = false))
        assertFalse(CameraFlow.closeOnStop(lensOpen = false, askingCamera = false))
    }

    @Test fun viewfinderClosedAnywayComesBackOnResume() {
        // a system that closes the window's children regardless: the answer arrives while stopped → opens on resume
        assertEquals(Reopen.ON_RESUME, CameraFlow.afterAnswer(lensOpen = false, windowResumed = false, onCameraPage = true, inRoom = false))
        assertTrue(CameraFlow.onResume(pending = true, lensOpen = false, onCameraPage = true, inRoom = false))
        // in front already → at once
        assertEquals(Reopen.NOW, CameraFlow.afterAnswer(lensOpen = false, windowResumed = true, onCameraPage = true, inRoom = false))
    }

    @Test fun noReopenOffTheCameraPageOrInRoomMode() {
        assertEquals(Reopen.NONE, CameraFlow.afterAnswer(lensOpen = false, windowResumed = true, onCameraPage = false, inRoom = false))
        assertEquals(Reopen.NONE, CameraFlow.afterAnswer(lensOpen = false, windowResumed = true, onCameraPage = true, inRoom = true))
        assertEquals(Reopen.NONE, CameraFlow.afterAnswer(lensOpen = true, windowResumed = true, onCameraPage = true, inRoom = false))
        assertFalse(CameraFlow.onResume(pending = false, lensOpen = false, onCameraPage = true, inRoom = false))
    }
}
