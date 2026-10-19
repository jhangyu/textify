package us.jhangy.textify

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.util.concurrent.Executors

/**
 * On-device translation LLMs ([MODELS]) on LiteRT-LM GPU, downloaded on demand. The only code that touches LiteRT-LM.
 * Call from the main thread; every callback runs on the main thread. Inference runs serially on one worker thread;
 * at most one engine is loaded (running another model closes it first) and [release] closes it.
 */
object Llm {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    /** Fired on the main thread when a model's gpuFailed changes (download state: [Downloads.onChanged]). Settings sets it while resumed. */
    var onChanged: (() -> Unit)? = null

    private val gpuFailedKeys: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()   // sticky per process

    /** Touched on [worker] only. */
    private var engine: Engine? = null
    private var engineKey: String? = null

    fun downloading(m: ModelSpec): Boolean = Downloads.downloading(m.files)
    fun waitingWifi(m: ModelSpec): Boolean = Downloads.waitingWifi(m.files)
    fun progress(m: ModelSpec): Float = Downloads.progress(m.files)

    /** Keys of the models whose GPU engine failed to start in this process. */
    val gpuFailed: Set<String> get() = gpuFailedKeys.toSet()

    /** Keys of the fully downloaded models. */
    fun downloaded(ctx: Context): Set<String> = MODELS.filter { isDownloaded(ctx, it) }.mapTo(HashSet()) { it.key }


    private fun changed() { main.post { onChanged?.invoke() } }

    /** Model file fully present. */
    fun isDownloaded(ctx: Context, m: ModelSpec): Boolean = Downloads.isDownloaded(ctx, m.files)

    /** Starts or joins the download of [m] (resumable). False = blocked by Wi-Fi-only (waitingWifi set). */
    fun download(ctx: Context, m: ModelSpec): Boolean = Downloads.download(ctx, m.files)

    /** Cancels any download of [m] and deletes its file (and partial file); closes the engine if it runs [m]. */
    fun delete(ctx: Context, m: ModelSpec) {
        val app = ctx.applicationContext
        Downloads.cancel(m.files)                         // bumps the generation first so a running download stops
        worker.execute {                                  // on the worker, so the engine no longer maps the file
            if (engineKey == m.key) closeEngine()
            Downloads.deleteFiles(app, m.files)
            changed()
        }
    }

    /**
     * Translates one paragraph ([source] = ML Kit code, already flattened text) into [target] with [m], serially.
     * [onResult] gets null on any failure. Output is NOT OpenCC-converted.
     */
    fun translate(ctx: Context, m: ModelSpec, text: String, source: String, target: TargetLang, onResult: (String?) -> Unit) {
        val app = ctx.applicationContext
        val prompt = m.prompt(text, target)
        worker.execute {
            val result = try {
                if (prompt == null || m.key in gpuFailedKeys) null else run(app, m, prompt)
            } catch (t: Throwable) {                      // every call must report exactly once, even on Error
                null
            }
            main.post { onResult(result) }
        }
    }

    private fun run(app: Context, m: ModelSpec, prompt: String): String? {
        if (engineKey != m.key) closeEngine()             // one engine at a time: free the other model first
        val e = engine ?: try {
            val file = Downloads.file(app, m.pinned)
            if (!file.isFile) return null
            Engine(EngineConfig(modelPath = file.path, backend = Backend.GPU(), cacheDir = app.cacheDir.path))
                .also { it.initialize() }
        } catch (t: Throwable) {                          // includes UnsatisfiedLinkError when OpenCL is missing
            gpuFailedKeys.add(m.key)
            changed()
            return null
        }
        engine = e
        engineKey = m.key
        return try {
            // A fresh conversation per paragraph keeps paragraphs independent.
            val s = m.sampler
            e.createConversation(ConversationConfig(samplerConfig = SamplerConfig(topK = s.topK, topP = s.topP, temperature = s.temperature))).use { m.clean(it.sendMessage(prompt, m.context).toString()) }
        } catch (t: Throwable) {
            null
        }
    }

    /** Worker thread only. */
    private fun closeEngine() {
        engine?.close()
        engine = null
        engineKey = null
    }

    /** Closes the engine (frees GPU memory). Safe to call repeatedly. */
    fun release() {
        worker.execute { closeEngine() }
    }
}
