package io.embodit.filtercam.app

import io.embodit.filtercam.domain.CamPerm
import io.embodit.filtercam.domain.CamPerms
import io.embodit.filtercam.domain.StampFormat

enum class Lang { ZH, EN }

/** Picture source (design T4). CAMERA exists only while the viewfinder is open (design R2-2). */
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
    const val FILTERS = "filters"
    const val GALLERY = "gallery"
    const val SETTINGS = io.embodit.filtercam.ui.window.SETTINGS_PAGE
}

object Samples {
    /** Sample photos in assets/samples (design content.inventory.samplePhotos). */
    val all = listOf("street", "desk", "window", "cat", "food", "night")
    const val DEFAULT = "street"
}

/** What the shot card (P4) shows (design S06 / S08n / S08f / S26 / S13). */
enum class CardKind { SAVED, VIEW_ONLY, READ_FAILED, SAVE_FAILED, ITEM }

data class ShotCard(
    val kind: CardKind,
    val uri: String? = null,
    val rawUri: String? = null,
    val filterId: String,
    val w: Int = 0,
    val h: Int = 0,
    val rawSaved: Boolean = false,
    /** Opened from the gallery (S13): 「删掉」 goes back to the gallery, not to the camera page. */
    val fromGallery: Boolean = false,
    val deleteAsked: Boolean = false,
    /** Bumps with every new card so the window reloads its picture. */
    val serial: Int = 0,
)

/** Cards drawn over the main window's page (design P6). */
enum class Overlay { GUIDE, PERMISSION }

data class LensUi(
    val open: Boolean = false,
    /** Waiting for the spot window / the session (button shows 「正在打开…」, design T3 ≤ 3 s). */
    val opening: Boolean = false,
    /** The picture is up (two runs done). */
    val live: Boolean = false,
    /** S07r: destroyed and created again after the permission. */
    val reopening: Boolean = false,
    /** S14: closing before room mode (「正在收起取景窗…」). */
    val closing: Boolean = false,
)

/** One-line notices under the camera page's top row (design S16 / S24 / S25 / S27 / S28 / S11). */
enum class NoticeKind { LENS_CLOSED_SAMPLE, RESUME, LENS_LOST, V11, IMPORT_FAILED, LUT_BAD, LUT_ADDED }

data class Notice(val kind: NoticeKind, val arg: String? = null, val serial: Int = 0)

data class RoomUi(val filterId: String, val switchedFrom: String? = null, val recenter: Int = 0)

data class FcState(
    val page: String = Pages.SHOOT,
    /** S29: the camera page's 「更多」 drawer (design round 3, B31: the main screen keeps 7 controls). */
    val moreOpen: Boolean = false,
    val lang: Lang = Lang.ZH,
    val filterId: String = "F01",
    /** The card being looked at (design T1: hover previews on the sample photo). */
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
    /** S21: the viewfinder could not open (reason line). */
    val lensError: String? = null,
    /** S05: 3, 2, 1 on the shutter. */
    val countdown: Int? = null,
    /** S08: reading the camera frame back (spinner). */
    val reading: Boolean = false,
    /** S06: white flash + frozen frame for 0.8 s. */
    val flashing: Boolean = false,
    val card: ShotCard? = null,
    val overlay: Overlay? = null,
    val muted: Boolean = false,
    /** 0 low, 1 mid, 2 high (design: three-step slider). */
    val volume: Int = 1,
    val saveRaw: Boolean = true,
    val luts: List<String> = emptyList(),
    val notice: Notice? = null,
    val galleryTick: Int = 0,
    val lastShotUri: String? = null,
    val room: RoomUi? = null,
    val quitAsked: Boolean = false,
    val isEmulator: Boolean = false,
    /** CAMERA / SPATIAL_DATA not granted, each checked on its own (profile D58). */
    val missingPerms: Set<CamPerm> = CamPerm.ALL,
    val permissionDenied: Boolean = false,
    /** Refused with "don't ask again" (or USER_FIXED): the dialog will not come back for these, only 「去设置」 helps. */
    val blockedPerms: Set<CamPerm> = emptySet(),
    /** v1.1 card tapped: the card shakes (design S28). */
    val shakeId: String? = null,
    val shakeSerial: Int = 0,
) {
    /** Both permissions of the camera picture are on. */
    val permissionGranted: Boolean
        get() = missingPerms.isEmpty()

    /** Every missing permission is refused for good. */
    val permissionBlocked: Boolean
        get() = CamPerms.prompt(missingPerms, permissionDenied, blockedPerms)?.kind == CamPerms.Kind.BLOCKED

    /** What the permission card says, or null when both are on. */
    val permPrompt: CamPerms.Prompt?
        get() = CamPerms.prompt(missingPerms, permissionDenied, blockedPerms)

    /** The filter the preview box shows: the looked-at card, else the chosen one. */
    val previewFilter: String
        get() = hoverId ?: filterId

    /** Camera source on a device without a camera (design S22). */
    val noCamera: Boolean
        get() = source == Source.CAMERA && isEmulator

    /** The design state this screen is in (QA logs; design states[]). */
    val stateId: String
        get() =
            when {
                room != null -> "S14"
                overlay == Overlay.GUIDE -> "S00"
                overlay == Overlay.PERMISSION -> "S07"
                lens.reopening -> "S07r"
                quitAsked -> "S18"
                card?.deleteAsked == true -> "S13"
                card?.kind == CardKind.VIEW_ONLY -> "S08n"
                card?.kind == CardKind.READ_FAILED -> "S08f"
                card?.kind == CardKind.SAVE_FAILED -> "S26"
                card?.kind == CardKind.ITEM -> "S13"
                card?.kind == CardKind.SAVED -> "S06"
                reading -> "S08"
                countdown != null -> "S05"
                lensError != null -> "S21"
                page == Pages.SETTINGS -> "S17"
                page == Pages.GALLERY -> "S12"
                page == Pages.FILTERS -> "S10"
                compare -> "S20"
                noCamera -> "S22"
                hoverId != null -> "S04"
                source == Source.CAMERA -> "S03"
                source == Source.IMPORT -> "S09"
                moreOpen -> "S29"
                lens.open -> "S02"
                else -> "S01"
            }
}
