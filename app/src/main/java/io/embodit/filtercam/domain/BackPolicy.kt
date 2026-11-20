package io.embodit.filtercam.domain

/**
 * What BACK / controller B does (profile D60 补, QA #6 q6-1). It never quits and never sends the app to the background:
 *  - quitting or swapping the camera window (「叫回面前」): nothing, the windows are already on their way;
 *  - room mode (Full Space Stage, the camera window stepped aside, P5 is the control): nothing, the room stays;
 *    「回去拍照」 on P5 is the way out (it waits for a level head, D9);
 *  - the camera window W1 closed: it opens again (W2 follows it by its own rule, beside W1);
 *  - the filter window W2 open: it folds, the camera window shows the viewfinder (拍照 page);
 *  - otherwise (W1 open, W2 folded): eaten, logged.
 */
enum class BackAction { IGNORE, STAY_IN_ROOM, CLOSE_SIDE, REOPEN_HOME, EAT }

object BackPolicy {
    fun decide(quitting: Boolean, inRoom: Boolean, sideOpen: Boolean, homeOpen: Boolean): BackAction =
        when {
            quitting -> BackAction.IGNORE
            inRoom -> BackAction.STAY_IN_ROOM
            !homeOpen -> BackAction.REOPEN_HOME
            sideOpen -> BackAction.CLOSE_SIDE
            else -> BackAction.EAT
        }
}
