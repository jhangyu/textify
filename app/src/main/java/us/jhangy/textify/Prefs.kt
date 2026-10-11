package us.jhangy.textify

import android.content.Context

/** Which ML Kit script model to run. The Chinese model also covers Latin/English. */
enum class Script { CHINESE, JAPANESE, KOREAN }

object Prefs {
    private const val FILE = "textify"
    private const val KEY_SCRIPT = "script"

    fun script(ctx: Context): Script {
        val name = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_SCRIPT, Script.CHINESE.name)!!
        return runCatching { Script.valueOf(name) }.getOrDefault(Script.CHINESE)
    }

    fun setScript(ctx: Context, script: Script) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_SCRIPT, script.name).apply()
    }
}
