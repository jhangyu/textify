package us.jhangy.textify

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.Executors

/**
 * On-device translation engine (translation spec §2): the only code that calls ML Kit translation and
 * language identification. Main thread only; OpenCC runs on [openCcThread] and posts back.
 */
object Translate {
    private val main = Handler(Looper.getMainLooper())
    private val openCcThread = Executors.newSingleThreadExecutor()
    private val inFlight = HashMap<String, Task<Void>>()
    private val waiting = LinkedHashSet<String>()

    /** Fired on the main thread whenever [downloading] or [waitingWifi] changes; Settings sets it while resumed. */
    var onModelsChanged: (() -> Unit)? = null

    /** ML Kit codes whose model download is running, process-wide (the session screen and Settings both read it). */
    val downloading: Set<String> get() = inFlight.keys.toSet()

    /** ML Kit codes that needed a download while Wi-Fi-only was on and Wi-Fi was absent (spec §2). */
    val waitingWifi: Set<String> get() = waiting.toSet()

    /** Every ML Kit translation language code. */
    fun languages(): List<String> = TranslateLanguage.getAllLanguages()

    /** The active network is Wi-Fi (needs ACCESS_NETWORK_STATE). */
    fun isOnWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Starts (or joins) the model download for ML Kit code [lang] through RemoteModelManager, so it survives the
     * session screen closing. Returns null without queuing anything when Wi-Fi-only is on and Wi-Fi is absent;
     * [lang] is then listed in [waitingWifi] until a later download of it starts.
     */
    fun download(ctx: Context, lang: String): Task<Void>? {
        inFlight[lang]?.let { return it }
        val wifiOnly = Prefs.wifiOnly(ctx)
        if (wifiOnly && !isOnWifi(ctx)) {
            if (waiting.add(lang)) changed()
            return null
        }
        waiting.remove(lang)
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        val task = RemoteModelManager.getInstance().download(model(lang), conditions)
        inFlight[lang] = task
        task.addOnCompleteListener { inFlight.remove(lang); changed() }   // §5: failure also leaves the set
        changed()
        return task
    }

    /** Deletes the downloaded model of [lang] (no confirmation, final pick); [onDone] runs on success or failure. */
    fun delete(lang: String, onDone: () -> Unit) {
        RemoteModelManager.getInstance().deleteDownloadedModel(model(lang)).addOnCompleteListener { onDone() }
    }

    /** Downloaded model codes as ML Kit reports them now (spec §4: nothing stored separately); empty on failure. */
    fun downloaded(onResult: (Set<String>) -> Unit) {
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models -> onResult(models.mapTo(HashSet()) { it.language }) }
            .addOnFailureListener { onResult(emptySet()) }
    }

    /** Translates [paragraphs] (paragraph index -> text) into [target]; [onState] runs on the main thread until [TranslateRun.close]. */
    fun start(ctx: Context, paragraphs: Map<Int, String>, target: TargetLang, onState: (Int, ParagraphState) -> Unit): TranslateRun =
        TranslateRun(ctx.applicationContext, target, onState).also { it.begin(paragraphs) }

    internal fun model(lang: String): TranslateRemoteModel = TranslateRemoteModel.Builder(lang).build()

    /** OpenCC off the main thread (first use loads the dictionaries); [onResult] runs on the main thread. */
    internal fun openCc(text: String, onResult: (String) -> Unit) {
        openCcThread.execute {
            val out = OpenCc.toTaiwan(text)
            main.post { onResult(out) }
        }
    }

    private fun changed() {
        onModelsChanged?.invoke()
    }
}

/** One translate press (spec §2). Results after [close] are dropped (spec §5); downloads keep running. */
class TranslateRun internal constructor(
    private val app: Context,
    private val target: TargetLang,
    private val onState: (Int, ParagraphState) -> Unit,
) {
    private var closed = false
    private val identifier = LanguageIdentification.getClient()
    private var identifierOpen = true
    private val translators = ArrayList<Translator>()

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
        val groups = LinkedHashMap<String, MutableList<Int>>()   // §2 step 3: one model check + translator per source
        for ((i, tag) in detected) {
            val text = texts.getValue(i)
            when (val r = route(tag, TranslateLanguage.fromLanguageTag(tag), target)) {
                Route.NotNeeded -> report(i, ParagraphState.NotNeeded)
                Route.Unsupported -> report(i, ParagraphState.Unsupported)
                Route.OpenCcOnly -> Translate.openCc(text) { report(i, openCcOnlyResult(text, it)) }
                is Route.MlKit -> groups.getOrPut(r.source) { ArrayList() } += i
            }
        }
        for ((source, idx) in groups) translateGroup(source, idx, texts)
    }

    private fun translateGroup(source: String, idx: List<Int>, texts: Map<Int, String>) {
        whenModelsReady(listOf(source, target.mlkit).distinct(), idx) {
            idx.forEach { report(it, ParagraphState.Translating) }
            val translator = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target.mlkit).build(),
            )
            translators += translator
            for (i in idx) {
                translator.translate(flatten(texts.getValue(i), source)).addOnCompleteListener { t ->
                    if (!t.isSuccessful) {
                        report(i, ParagraphState.Failed)          // §5: only this paragraph fails
                        return@addOnCompleteListener
                    }
                    val out: String = t.result
                    if (target.openCc) Translate.openCc(out) { report(i, ParagraphState.Done(it, source)) }
                    else report(i, ParagraphState.Done(out, source))
                }
            }
        }
    }

    /** Checks every model in [langs]; downloads the missing ones; reports Downloading / WaitingWifi / Failed for [idx]; then [ready]. */
    private fun whenModelsReady(langs: List<String>, idx: List<Int>, ready: () -> Unit) {
        val manager = RemoteModelManager.getInstance()
        val missing = ArrayList<String>()
        var checked = 0
        for (lang in langs) {
            manager.isModelDownloaded(Translate.model(lang)).addOnCompleteListener { t ->
                if (!(t.isSuccessful && t.result == true)) missing += lang
                if (++checked < langs.size || closed) return@addOnCompleteListener
                if (missing.isEmpty()) {
                    ready()
                    return@addOnCompleteListener
                }
                val tasks = missing.map { Translate.download(app, it) }
                if (tasks.any { it == null }) {
                    idx.forEach { report(it, ParagraphState.WaitingWifi) }   // §2: nothing queued; next press retries
                    return@addOnCompleteListener
                }
                idx.forEach { report(it, ParagraphState.Downloading) }
                Tasks.whenAll(tasks.filterNotNull()).addOnCompleteListener { all ->
                    if (closed) return@addOnCompleteListener
                    if (all.isSuccessful) ready() else idx.forEach { report(it, ParagraphState.Failed) }   // §5
                }
            }
        }
    }

    private fun report(i: Int, state: ParagraphState) {
        if (!closed) onState(i, state)
    }

    private fun closeIdentifier() {
        if (!identifierOpen) return
        identifierOpen = false
        identifier.close()
    }

    /** Session ended: drop late results, free the identifier and translators (spec §2 lifecycle). */
    fun close() {
        if (closed) return
        closed = true
        closeIdentifier()
        translators.forEach { it.close() }
        translators.clear()
    }
}
