package us.jhangy.textify

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.google.mlkit.nl.languageid.LanguageIdentification
import java.util.concurrent.Executors

/**
 * On-device translation (translation spec §2): language identification (ML Kit), routing, and the active LLM
 * ([Llm]) plus OpenCC. Main thread only; OpenCC runs on [openCcThread] and posts back.
 */
object Translate {
    private val main = Handler(Looper.getMainLooper())
    private val openCcThread = Executors.newSingleThreadExecutor()

    /**
     * The device traffic runs over Wi-Fi (needs ACCESS_NETWORK_STATE). Behind a VPN the active network is the VPN
     * and its transports do not reliably include Wi-Fi, so then look for a connected non-VPN Wi-Fi network instead.
     */
    @Suppress("DEPRECATION")   // allNetworks: deprecated in API 31 but still lists the app-visible networks; no replacement lists them
    fun isOnWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val active = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (active.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return false   // VPN over cellular lists both
        if (active.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true
        if (!active.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        return cm.allNetworks.any {
            val c = cm.getNetworkCapabilities(it)
            c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
    }

    /** Translates [paragraphs] (paragraph index -> text) into [target]; [onState] runs on the main thread until [TranslateRun.close]. */
    fun start(ctx: Context, paragraphs: Map<Int, String>, target: TargetLang, onState: (Int, ParagraphState) -> Unit): TranslateRun =
        TranslateRun(ctx.applicationContext, target, onState).also { it.begin(paragraphs) }

    /** OpenCC off the main thread (first use loads the dictionaries); [onResult] runs on the main thread. */
    internal fun openCc(text: String, onResult: (String) -> Unit) {
        openCcThread.execute {
            val out = OpenCc.toTaiwan(text)
            main.post { onResult(out) }
        }
    }
}

/** One translate press (spec §2). Results after [close] are dropped (spec §5); a started model download keeps running. */
class TranslateRun internal constructor(
    private val app: Context,
    private val target: TargetLang,
    private var onState: ((Int, ParagraphState) -> Unit)?,   // cleared by close() so late callbacks do not pin the Activity
) {
    private var closed = false
    private val identifier = LanguageIdentification.getClient()
    private var identifierOpen = true

    internal fun begin(paragraphs: Map<Int, String>) {
        paragraphs.keys.forEach { report(it, ParagraphState.Translating) }
        if (paragraphs.isEmpty()) {
            closeIdentifier()
            return
        }
        val detected = HashMap<Int, String>()
        for ((i, text) in paragraphs) {
            identifier.identifyLanguage(flatten(text, null)).addOnCompleteListener { t ->
                detected[i] = if (t.isSuccessful) t.result else UNDETERMINED   // §5: identification failure = undetermined
                if (detected.size == paragraphs.size) {
                    closeIdentifier()
                    dispatch(paragraphs, detected)
                }
            }
        }
    }

    private fun dispatch(texts: Map<Int, String>, detected: Map<Int, String>) {
        if (closed) return
        val model = Prefs.activeModel(app)
        val downloaded = Llm.downloaded(app)
        var modelState: ParagraphState? = null   // Downloading / WaitingWifi, decided once per run
        for ((i, tag) in detected) {
            val text = texts.getValue(i)
            when (val r = route(tag, target, model, downloaded)) {
                Route.NotNeeded -> report(i, ParagraphState.NotNeeded)
                Route.Unsupported -> report(i, ParagraphState.Unsupported)
                Route.OpenCcOnly -> Translate.openCc(text) { report(i, openCcOnlyResult(text, it)) }
                Route.NeedsModel -> {
                    val state = modelState ?: (if (Llm.download(app, model)) ParagraphState.Downloading else ParagraphState.WaitingWifi)
                        .also { modelState = it }
                    report(i, state)
                }
                is Route.Llm -> translateLlm(model, i, r.source, text)
            }
        }
    }

    /** One paragraph through the LLM; a null result (GPU start or inference failure) is Failed, there is no fallback engine. */
    private fun translateLlm(model: ModelSpec, i: Int, source: String, text: String) {
        Llm.translate(app, model, flatten(text, source), source, target) { out ->
            when {
                out == null -> report(i, ParagraphState.Failed)
                target.openCc -> Translate.openCc(out) { report(i, ParagraphState.Done(it, source)) }
                else -> report(i, ParagraphState.Done(out, source))
            }
        }
    }

    private fun report(i: Int, state: ParagraphState) {
        if (!closed) onState?.invoke(i, state)
    }

    private fun closeIdentifier() {
        if (!identifierOpen) return
        identifierOpen = false
        identifier.close()
    }

    /** Session ended: drop late results and free the identifier (spec §2 lifecycle). */
    fun close() {
        if (closed) return
        closed = true
        onState = null
        closeIdentifier()
    }
}
