package io.embodit.filtercam.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** D60 补 / QA #6 q6-1: BACK (controller B) folds W2, reopens a closed W1, keeps the room, and never quits. */
class BackPolicyTest {
    private fun d(quitting: Boolean = false, inRoom: Boolean = false, sideOpen: Boolean = false, homeOpen: Boolean = true) =
        BackPolicy.decide(quitting, inRoom, sideOpen, homeOpen)

    @Test fun filterWindowOpenFoldsIt() = assertEquals(BackAction.CLOSE_SIDE, d(sideOpen = true))

    @Test fun cameraWindowClosedOpensItAgain() {
        assertEquals(BackAction.REOPEN_HOME, d(homeOpen = false))
        // W1 closed with W2 still up: the camera window comes back first (W2 follows it by its own rule)
        assertEquals(BackAction.REOPEN_HOME, d(homeOpen = false, sideOpen = true))
    }

    @Test fun cameraWindowAloneEatsIt() = assertEquals(BackAction.EAT, d())

    @Test fun roomModeStays() {
        assertEquals(BackAction.STAY_IN_ROOM, d(inRoom = true, homeOpen = false))
        assertEquals(BackAction.STAY_IN_ROOM, d(inRoom = true, sideOpen = true))
    }

    @Test fun quittingDoesNothing() = assertEquals(BackAction.IGNORE, d(quitting = true, homeOpen = false, sideOpen = true))

    /** Three presses in a row from W2 open: fold, then eaten, eaten; no state ever leads to leaving the app. */
    @Test fun threePressesNeverLeave() {
        var sideOpen = true
        val seen = (1..3).map { val a = d(sideOpen = sideOpen); if (a == BackAction.CLOSE_SIDE) sideOpen = false; a }
        assertEquals(listOf(BackAction.CLOSE_SIDE, BackAction.EAT, BackAction.EAT), seen)
        for (q in listOf(false, true)) for (r in listOf(false, true)) for (s in listOf(false, true)) for (h in listOf(false, true)) {
            val a = d(q, r, s, h)
            assertFalse("$q $r $s $h -> $a", a.name.contains("QUIT") || a.name.contains("FINISH"))
        }
    }
}
