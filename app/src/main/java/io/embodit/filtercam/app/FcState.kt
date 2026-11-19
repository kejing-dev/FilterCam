package io.embodit.filtercam.app

import io.embodit.filtercam.domain.CamPerm
import io.embodit.filtercam.domain.CamPerms
import io.embodit.filtercam.domain.StampFormat

enum class Lang { ZH, EN }

/** Picture source (design T4). */
enum class Source(val id: String) {
    SAMPLE("sample"),
    CAMERA("camera"),
    IMPORT("import");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

object Pages {
    const val SHOOT = "shoot"
    const val GALLERY = "gallery"
    const val SETTINGS = io.embodit.filtercam.ui.window.SETTINGS_PAGE
}

object Samples {
    /** Sample photos in assets/samples (design content.inventory.samplePhotos). */
    val all = listOf("street", "desk", "window", "cat", "food", "night")
    const val DEFAULT = "street"
}

/** The viewfinder's read-back session (design r4 container.lifecycle). */
data class LensUi(
    val open: Boolean = false,
    /** The session is being created (the viewfinder shows the last frame / a small spinner). */
    val opening: Boolean = false,
    /** Frames are coming in. */
    val live: Boolean = false,
    /** S25: lost, reopening by itself. */
    val reopening: Boolean = false,
)

/** The last shot in the corner thumbnail (design S06 / S08 / S26): [serial] bumps with every shot (fly-in animation). */
data class ShotThumb(val serial: Int, val uri: String? = null, val failed: Boolean = false)

/** One-line toasts over the viewfinder's lower edge (design S16 / S24 / S25 / S26 / S27 / S28 / S08f / S11 / S01). */
enum class NoticeKind { HINT, LENS_BACK, LENS_LOST, V11, IMPORT_FAILED, LUT_BAD, LUT_ADDED, READ_FAILED, SAVE_FAILED }

data class Notice(val kind: NoticeKind, val arg: String? = null, val serial: Int = 0)

data class RoomUi(val filterId: String, val switchedFrom: String? = null, val recenter: Int = 0)

data class FcState(
    val page: String = Pages.SHOOT,
    /** S29: the filter window's 「更多」 page. */
    val moreOpen: Boolean = false,
    /** W2 not folded by the user (design W2.visibility: W1「滤镜」 or the window's ×). */
    val sideWanted: Boolean = true,
    val lang: Lang = Lang.ZH,
    val filterId: String = "F01",
    /** The card being looked at (design T1: hover previews in the viewfinder). */
    val hoverId: String? = null,
    val strength: Int = 100,
    val stampOn: Boolean = false,
    val stampFormat: StampFormat = StampFormat.SHORT,
    val compare: Boolean = false,
    val timerOn: Boolean = false,
    val source: Source = Source.SAMPLE,
    val sample: String = Samples.DEFAULT,
    val importName: String? = null,
    val lens: LensUi = LensUi(),
    /** S21: the viewfinder could not open (reason in the log). */
    val lensError: String? = null,
    /** S05: 3, 2, 1 on the shutter. */
    val countdown: Int? = null,
    /** S06: white flash (120 ms) + frozen frame (300 ms), then the fly into the thumbnail. */
    val flashing: Boolean = false,
    /** Shots still being written (design S08: up to 3, the ring round the thumbnail). */
    val saving: Int = 0,
    val thumb: ShotThumb? = null,
    /** The album's picture, 0 = newest (design S12: opens on the newest). */
    val albumIndex: Int = 0,
    /** S13: the confirm bar in place of the album's bottom row. */
    val albumDeleteAsk: Boolean = false,
    val muted: Boolean = false,
    /** 0 low, 1 mid, 2 high (design: three-step slider). */
    val volume: Int = 1,
    val saveRaw: Boolean = true,
    val luts: List<String> = emptyList(),
    val notice: Notice? = null,
    val galleryTick: Int = 0,
    val room: RoomUi? = null,
    val quitAsked: Boolean = false,
    val isEmulator: Boolean = false,
    /** CAMERA / SPATIAL_DATA not granted, each checked on its own (profile D58). */
    val missingPerms: Set<CamPerm> = CamPerm.ALL,
    val permissionDenied: Boolean = false,
    /** Refused with "don't ask again" (or USER_FIXED): the dialog will not come back for these, only 「去设置」 helps. */
    val blockedPerms: Set<CamPerm> = emptySet(),
    /** The system dialog asking for this one is up (S00a 「正在问 n/2」). */
    val asking: CamPerm? = null,
    /** S00c: 「去设置」 was pressed, the card waits (1 s poll, resume, focus). */
    val permWaiting: Boolean = false,
    /** Settings 「重看权限说明」: the card shows even with both on (✓ ✓ and 「去拍」). */
    val permReview: Boolean = false,
    /** v1.1 card tapped: the card shakes (design S28). */
    val shakeId: String? = null,
    val shakeSerial: Int = 0,
) {
    /** Both permissions of the camera picture are on. */
    val permissionGranted: Boolean
        get() = missingPerms.isEmpty()

    /** What the permission card says, or null when both are on. */
    val permPrompt: CamPerms.Prompt?
        get() = CamPerms.prompt(missingPerms, permissionDenied, blockedPerms)

    /** The permission card fills the viewfinder (S00 series): the camera is the source and something is missing. */
    val permissionCard: Boolean
        get() = page == Pages.SHOOT && source == Source.CAMERA && (!permissionGranted || permReview)

    /** The filter the viewfinder shows: the looked-at card, else the chosen one. */
    val previewFilter: String
        get() = hoverId ?: filterId

    /** Camera source on a device without a camera (design S22). */
    val noCamera: Boolean
        get() = source == Source.CAMERA && isEmulator && permissionGranted && !lens.live

    /** The design state this screen is in (QA logs; design states[]). */
    val stateId: String
        get() =
            when {
                room != null -> "S14"
                quitAsked -> "S18"
                page == Pages.SETTINGS -> "S17"
                page == Pages.GALLERY && albumDeleteAsk -> "S13"
                page == Pages.GALLERY -> "S12"
                permissionCard && permWaiting -> "S00c"
                permissionCard && asking != null -> "S00a"
                permissionCard && permissionGranted -> "S00"
                permissionCard && (permissionDenied || blockedPerms.isNotEmpty() || missingPerms.size < CamPerm.ALL.size) -> "S00b"
                permissionCard -> "S00"
                lensError != null -> "S21"
                lens.reopening -> "S25"
                countdown != null -> "S05"
                flashing -> "S06"
                saving > 0 -> "S08"
                moreOpen -> "S29"
                compare -> "S20"
                noCamera -> "S22"
                hoverId != null -> "S04"
                source == Source.IMPORT -> "S09"
                source == Source.SAMPLE -> "S01s"
                else -> "S01"
            }
}
