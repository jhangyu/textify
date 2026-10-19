package us.jhangy.textify

import android.content.Context

/** ML Kit script models. Every model also reads Latin/English. */
enum class Script { CHINESE, JAPANESE, KOREAN }

object Prefs {
    private const val FILE = "textify"
    private const val SCRIPTS_KEY = "scripts"                 // StringSet of Script names
    private const val TARGET_KEY = "translate_target"         // TargetLang.key
    private const val WIFI_ONLY_KEY = "translate_wifi_only"
    private const val ACTIVE_MODEL_KEY = "llm_active"         // ModelSpec.key

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Selected scripts in enum order; unknown names dropped; empty or missing -> CHINESE. */
    fun scripts(ctx: Context): Set<Script> {
        val names = prefs(ctx).getStringSet(SCRIPTS_KEY, null).orEmpty()
        return Script.entries.filterTo(LinkedHashSet()) { it.name in names }.ifEmpty { setOf(Script.CHINESE) }
    }

    fun setScripts(ctx: Context, scripts: Set<Script>) {
        require(scripts.isNotEmpty())
        prefs(ctx).edit().putStringSet(SCRIPTS_KEY, scripts.mapTo(HashSet()) { it.name }).apply()
    }

    /** Translation target (translation spec §4); default 繁體中文. */
    fun target(ctx: Context): TargetLang = TargetLang.of(prefs(ctx).getString(TARGET_KEY, null)?.takeIf(TargetLang.keys::contains) ?: TargetLang.DEFAULT)

    fun setTarget(ctx: Context, key: String) {
        prefs(ctx).edit().putString(TARGET_KEY, key).apply()
    }

    /** 只用 Wi-Fi 下載 (spec §4); default on. */
    fun wifiOnly(ctx: Context): Boolean = prefs(ctx).getBoolean(WIFI_ONLY_KEY, true)

    fun setWifiOnly(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(WIFI_ONLY_KEY, on).apply()
    }

    /** Active translation LLM (the only one that translates); default the first of [MODELS]. */
    fun activeModel(ctx: Context): ModelSpec = modelOf(prefs(ctx).getString(ACTIVE_MODEL_KEY, null))

    fun setActiveModel(ctx: Context, key: String) {
        prefs(ctx).edit().putString(ACTIVE_MODEL_KEY, key).apply()
    }
}
