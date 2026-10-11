package us.jhangy.textify

import android.content.Context

/** ML Kit script models. Every model also reads Latin/English. */
enum class Script { CHINESE, JAPANESE, KOREAN }

object Prefs {
    private const val FILE = "textify"
    private const val SCRIPTS_KEY = "scripts"   // StringSet of Script names

    /** Selected scripts in enum order; unknown names dropped; empty or missing -> CHINESE. */
    fun scripts(ctx: Context): Set<Script> {
        val names = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getStringSet(SCRIPTS_KEY, null).orEmpty()
        return Script.entries.filterTo(LinkedHashSet()) { it.name in names }.ifEmpty { setOf(Script.CHINESE) }
    }

    fun setScripts(ctx: Context, scripts: Set<Script>) {
        require(scripts.isNotEmpty())
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putStringSet(SCRIPTS_KEY, scripts.mapTo(HashSet()) { it.name }).apply()
    }
}
