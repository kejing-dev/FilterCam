package io.embodit.filtercam.domain

/**
 * The viewfinder around the CAMERA dialog (QA #3 q3-1, 2026-11-15 headset): on PICO OS the system dialog pushes the main
 * window to the background (ON_STOP); the background used to close the viewfinder (S24) and nothing opened it again, so
 * after 「允许」 the user was back on the camera page with no picture. The rules, kept pure so they are unit tested:
 *  - our own dialog is not "the user left": the viewfinder stays while it is up;
 *  - after the answer the viewfinder comes back on the camera page at once if the window is in front, else when it is;
 *  - no new session while the dialog is up (QA #4 q4-1, headset 2/2): the spot window cannot take focus then, and a
 *    session created without it lands in the middle of the main window and never moves; it is created after the answer.
 */
object CameraFlow {
    enum class Reopen { NONE, NOW, ON_RESUME }

    /** The camera source was picked: open the viewfinder now only if it is closed and no CAMERA dialog is about to show. */
    fun openWithSource(lensOpen: Boolean, willAsk: Boolean): Boolean = !lensOpen && !willAsk

    /** The main window stopped: close the viewfinder (S24) unless it is our own CAMERA dialog in front. */
    fun closeOnStop(lensOpen: Boolean, askingCamera: Boolean): Boolean = lensOpen && !askingCamera

    /** The CAMERA dialog answered (either way: refused shows the card in the viewfinder's place, allowed shows the room). */
    fun afterAnswer(lensOpen: Boolean, windowResumed: Boolean, onCameraPage: Boolean, inRoom: Boolean): Reopen =
        when {
            lensOpen || !onCameraPage || inRoom -> Reopen.NONE
            windowResumed -> Reopen.NOW
            else -> Reopen.ON_RESUME
        }

    /** The main window resumed: open the viewfinder that [afterAnswer] left pending. */
    fun onResume(pending: Boolean, lensOpen: Boolean, onCameraPage: Boolean, inRoom: Boolean): Boolean = pending && !lensOpen && onCameraPage && !inRoom

    enum class Complete { NONE, OPEN, REOPEN }

    /**
     * Profile D58 (2026-11-17 headset: the user turned the camera on in Settings and came back to no picture): the last
     * missing permission turned on while away (Settings, the resume re-check). If the camera was wanted, the picture
     * starts right there: open the viewfinder, or create its session again if it was made without the permission.
     */
    fun onPermissionsComplete(wasMissing: Boolean, nowComplete: Boolean, cameraWanted: Boolean, lensOpen: Boolean, onCameraPage: Boolean, inRoom: Boolean): Complete =
        when {
            !wasMissing || !nowComplete || !cameraWanted || !onCameraPage || inRoom -> Complete.NONE
            lensOpen -> Complete.REOPEN
            else -> Complete.OPEN
        }
}
