package io.embodit.filtercam.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import io.embodit.filtercam.domain.ShutterSound

/**
 * The app's short sounds (design sounds.list): three shutter sets mapped by filter, the filter tick, the save chime, the
 * countdown beep and the delete blip. No looping or ambient sound. One stream per sound at a time (profile D51: a new
 * play stops the last one of the same kind); volume = the settings step, muted = silent (design T15).
 */
class SoundBank(context: Context) {
    enum class Cue(val res: String) {
        SHUTTER_MECH("shutter_mech"),
        SHUTTER_ELEC("shutter_elec"),
        SHUTTER_INSTANT("shutter_instant"),
        TICK("tick"),
        CHIME("chime"),
        BEEP("beep"),
        DELETE("delete"),
    }

    private val pool =
        SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build()
    private val ids = HashMap<Cue, Int>()
    private val streams = HashMap<Cue, Int>()

    init {
        for (c in Cue.entries) {
            val id = context.resources.getIdentifier(c.res, "raw", context.packageName)
            if (id != 0) ids[c] = pool.load(context, id, 1) else FcLog.w("AUDIO missing res/raw/${c.res}")
        }
    }

    fun play(cue: Cue, muted: Boolean, volumeStep: Int) {
        if (muted) {
            FcLog.i("AUDIO muted ${cue.name}")
            return
        }
        val id = ids[cue] ?: return
        streams[cue]?.let { pool.stop(it) }
        val v = VOLUMES[volumeStep.coerceIn(0, 2)]
        streams[cue] = pool.play(id, v, v, 1, 0, 1f)
        FcLog.i("AUDIO play ${cue.name} v=$v")
    }

    fun release() = pool.release()

    companion object {
        /** Low / mid / high (design: default low-ish, peaks under -1 dBFS at high). */
        val VOLUMES = floatArrayOf(0.45f, 0.75f, 1f)

        fun shutter(s: ShutterSound) =
            when (s) {
                ShutterSound.MECHANICAL -> Cue.SHUTTER_MECH
                ShutterSound.ELECTRONIC -> Cue.SHUTTER_ELEC
                ShutterSound.INSTANT -> Cue.SHUTTER_INSTANT
            }
    }
}
