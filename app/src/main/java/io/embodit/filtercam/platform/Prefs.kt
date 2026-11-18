package io.embodit.filtercam.platform

import android.content.Context
import android.content.SharedPreferences
import io.embodit.filtercam.app.FcState
import io.embodit.filtercam.app.Lang
import io.embodit.filtercam.app.Samples
import io.embodit.filtercam.domain.CamPerm
import io.embodit.filtercam.domain.StampFormat
import java.util.Locale

/**
 * Local settings (design persistence.local): last filter / strength / sample / stamp on and format / timer / language /
 * mute and volume / tips seen / read-back permission refused / save originals. Camera and imported sources are not
 * kept: the app always starts on a sample (design S01).
 */
class Prefs(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("filter_cam", Context.MODE_PRIVATE)

    fun restore(base: FcState): FcState =
        base.copy(
            lang = lang(),
            filterId = p.getString("filter", base.filterId) ?: base.filterId,
            strength = p.getInt("strength", base.strength),
            stampOn = p.getBoolean("stampOn", base.stampOn),
            stampFormat = StampFormat.of(p.getString("stampFormat", null)),
            timerOn = p.getBoolean("timer", false),
            sample = p.getString("sample", Samples.DEFAULT)?.takeIf { it in Samples.all } ?: Samples.DEFAULT,
            muted = p.getBoolean("muted", false),
            volume = p.getInt("volume", 1).coerceIn(0, 2),
            saveRaw = p.getBoolean("saveRaw", true),
            permissionDenied = p.getBoolean("permissionDenied", false),
            blockedPerms = p.getStringSet("blockedPerms", null).orEmpty().mapNotNull { CamPerm.of(it) }.toSet(),
        )

    fun save(s: FcState) =
        p.edit()
            .putString("lang", if (s.lang == Lang.EN) "en" else "zh")
            .putString("filter", s.filterId)
            .putInt("strength", s.strength)
            .putBoolean("stampOn", s.stampOn)
            .putString("stampFormat", s.stampFormat.id)
            .putBoolean("timer", s.timerOn)
            .putString("sample", s.sample)
            .putBoolean("muted", s.muted)
            .putInt("volume", s.volume)
            .putBoolean("saveRaw", s.saveRaw)
            .putBoolean("permissionDenied", s.permissionDenied)
            .putStringSet("blockedPerms", s.blockedPerms.map { it.id }.toSet())
            .apply()

    fun lang(): Lang =
        when (p.getString("lang", null)) {
            "en" -> Lang.EN
            "zh" -> Lang.ZH
            else -> if (Locale.getDefault().language == "zh") Lang.ZH else Lang.EN
        }

    fun guideSeen() = p.getBoolean("guideSeen", false)

    fun setGuideSeen(seen: Boolean) = p.edit().putBoolean("guideSeen", seen).apply()
}
