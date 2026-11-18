package io.embodit.filtercam.domain

/**
 * The two permissions behind the camera picture (profile D58, 2026-11-17 headset): CAMERA was on, SPATIAL_DATA had been
 * refused with "don't ask again" (USER_FIXED), and the app only ever said "camera access is off". So each one is checked
 * on its own, the missing one is named, and a refused-for-good one says where to find it in Settings.
 *
 * Settings names: "Spatial Data" in English (PermissionController `string/spatial_data` on the 6.1 oversea emulator image,
 * 2026-11-18; the headset's dialog said "Access Spatial Data"); a Chinese 6.3 system asks for 「空间数据」, so the Chinese
 * text names both.
 */
enum class CamPerm(val id: String) {
    CAMERA("android.permission.CAMERA"),
    SPATIAL_DATA("com.picovr.permission.SPATIAL_DATA"),
    ;

    companion object {
        val ALL: Set<CamPerm> = values().toSet()

        /** Each permission checked on its own (D58: never only "all granted"). */
        fun missing(isGranted: (String) -> Boolean): Set<CamPerm> = values().filterNot { isGranted(it.id) }.toSet()

        fun of(id: String): CamPerm? = values().firstOrNull { it.id == id }
    }
}

object CamPerms {
    /** What the card (and the viewfinder's placeholder) says. */
    enum class Kind {
        /** Never refused: 「授权」 shows the system dialog. */
        NEED,
        /** Refused, the system still asks: 「去设置」 + 「再问一次」. */
        DENIED,
        /** Every missing one is "don't ask again": only 「去设置」 helps; say where to look. */
        BLOCKED,
    }

    data class Prompt(val kind: Kind, val missing: List<CamPerm>, val blocked: List<CamPerm>) {
        /** 「再问一次」: something is still left for the system dialog. */
        val canAskAgain: Boolean
            get() = kind != Kind.BLOCKED

        /** The missing ones the dialog can still ask for. */
        val askable: List<CamPerm>
            get() = missing - blocked.toSet()
    }

    /** Null when both are granted. Order is fixed (camera first) so the text does not jump around. */
    fun prompt(missing: Set<CamPerm>, denied: Boolean, blocked: Set<CamPerm>): Prompt? {
        if (missing.isEmpty()) return null
        val m = CamPerm.values().filter { it in missing }
        val b = m.filter { it in blocked }
        val kind =
            when {
                b.size == m.size -> Kind.BLOCKED
                denied || b.isNotEmpty() -> Kind.DENIED
                else -> Kind.NEED
            }
        return Prompt(kind, m, b)
    }

    /**
     * After a dialog answer: [requested] were asked, [stillMissing] is what the system reports now, [rationale] whether it
     * would still show its dialog for a permission. A refused one with no rationale is "don't ask again" (or USER_FIXED,
     * which the system refuses without showing anything).
     */
    fun blockedAfterAnswer(requested: Set<CamPerm>, stillMissing: Set<CamPerm>, rationale: (CamPerm) -> Boolean): Set<CamPerm> =
        requested.filter { it in stillMissing && !rationale(it) }.toSet()
}
