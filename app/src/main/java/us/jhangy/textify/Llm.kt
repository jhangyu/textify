package us.jhangy.textify

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.ai.edge.litertlm.ActivationDataType
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * On-device translation LLMs ([MODELS]) on LiteRT-LM GPU, downloaded on demand. The only code that touches LiteRT-LM.
 * Call from the main thread; every callback runs on the main thread. Inference runs serially on one worker thread;
 * at most one engine is loaded (running another model closes it first) and [release] closes it.
 */
object Llm {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    /** Fired on the main thread when any model's download state/progress or gpuFailed changes. Settings sets it while resumed. */
    var onChanged: (() -> Unit)? = null

    private class Slot {
        @Volatile var downloading = false
        @Volatile var waitingWifi = false
        @Volatile var progress = 0f
        @Volatile var gpuFailed = false                   // sticky per process, per model
        /** Bumped by [delete]; a download thread of an older generation stops and never publishes its file. */
        @Volatile var generation = 0
    }

    private val slots = MODELS.associate { it.key to Slot() }
    private fun slot(m: ModelSpec) = slots.getValue(m.key)

    /** Touched on [worker] only. */
    private var engine: Engine? = null
    private var engineKey: String? = null

    fun downloading(m: ModelSpec): Boolean = slot(m).downloading
    fun waitingWifi(m: ModelSpec): Boolean = slot(m).waitingWifi
    fun progress(m: ModelSpec): Float = slot(m).progress

    /** Keys of the models whose GPU engine failed to start in this process. */
    val gpuFailed: Set<String> get() = MODELS.filter { slot(it).gpuFailed }.mapTo(HashSet()) { it.key }

    /** Keys of the fully downloaded models. */
    fun downloaded(ctx: Context): Set<String> = MODELS.filter { isDownloaded(ctx, it) }.mapTo(HashSet()) { it.key }

    private fun modelFile(ctx: Context, m: ModelSpec) = File(ctx.applicationContext.filesDir, m.file)
    private fun partFile(ctx: Context, m: ModelSpec) = File(ctx.applicationContext.filesDir, "${m.file}.part")

    private fun changed() { main.post { onChanged?.invoke() } }

    /** Model file fully present (the `.part` file is renamed only when complete). */
    fun isDownloaded(ctx: Context, m: ModelSpec): Boolean = modelFile(ctx, m).isFile

    /** Starts or joins the download of [m] (resumable). False = blocked by Wi-Fi-only (waitingWifi set). */
    fun download(ctx: Context, m: ModelSpec): Boolean {
        val app = ctx.applicationContext
        val s = slot(m)
        if (s.downloading) return true
        if (isDownloaded(app, m)) return true
        if (Prefs.wifiOnly(app) && !Translate.isOnWifi(app)) {
            s.waitingWifi = true
            changed()
            return false
        }
        s.waitingWifi = false
        s.downloading = true
        s.progress = 0f
        changed()
        val gen = s.generation
        Thread({ downloadBlocking(app, m, gen) }, "llm-download-${m.key}").start()
        return true
    }

    private fun downloadBlocking(app: Context, m: ModelSpec, gen: Int) {
        val s = slot(m)
        val part = partFile(app, m)
        var lastPct = -1
        try {
            if (part.length() > m.size) part.delete()                     // corrupt/oversized: restart from 0
            if (part.length() != m.size) {
                var offset = if (part.isFile) part.length() else 0L
                var conn = open(m, offset)
                if (conn.responseCode == 416) {                           // server rejects the offset: restart from 0
                    conn.disconnect()
                    part.delete()
                    offset = 0L
                    conn = open(m, 0L)
                }
                try {
                    val resumed = conn.responseCode == 206
                    if (conn.responseCode != 200 && !resumed) throw java.io.IOException("HTTP ${conn.responseCode}")
                    var done = if (resumed) offset else 0L               // 200 = server ignored Range: restart the file
                    FileOutputStream(part, resumed).use { out ->
                        conn.inputStream.use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (gen != s.generation) return           // delete() ran: stop quietly, it owns the files
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val pct = (done * 100 / m.size).toInt().coerceAtMost(100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    s.progress = pct / 100f
                                    changed()
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }
            if (gen != s.generation) return
            if (part.length() != m.size) {                                // wrong size: never publish; next download() starts over
                part.delete()
                throw java.io.IOException("size mismatch")
            }
            if (!part.renameTo(modelFile(app, m))) throw java.io.IOException("rename failed")
        } catch (e: Exception) {
            // ponytail: no retry/backoff; a short .part stays for the next download() to resume from.
        } finally {
            if (gen == s.generation) s.downloading = false
            changed()
        }
    }

    private fun open(m: ModelSpec, offset: Long): HttpURLConnection {
        val conn = URL(m.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
        return conn
    }

    /** Cancels any download of [m] and deletes its file (and partial file); closes the engine if it runs [m]. */
    fun delete(ctx: Context, m: ModelSpec) {
        val app = ctx.applicationContext
        val s = slot(m)
        s.generation++
        s.downloading = false
        s.waitingWifi = false
        s.progress = 0f
        worker.execute {                                  // on the worker, so the engine no longer maps the file
            if (engineKey == m.key) closeEngine()
            modelFile(app, m).delete()
            partFile(app, m).delete()
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
                if (prompt == null || slot(m).gpuFailed) null else run(app, m, prompt)
            } catch (t: Throwable) {                      // every call must report exactly once, even on Error
                null
            }
            main.post { onResult(result) }
        }
    }

    private fun run(app: Context, m: ModelSpec, prompt: String): String? {
        if (engineKey != m.key) closeEngine()             // one engine at a time: free the other model first
        val e = engine ?: try {
            val file = modelFile(app, m)
            if (!file.isFile) return null
            val act = if (m.fp32Activations) ActivationDataType.FLOAT32 else null   // null = runtime default
            Engine(EngineConfig(modelPath = file.path, backend = Backend.GPU(), cacheDir = app.cacheDir.path, activationDataType = act))
                .also { it.initialize() }
        } catch (t: Throwable) {                          // includes UnsatisfiedLinkError when OpenCL is missing
            slot(m).gpuFailed = true
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
