package us.jhangy.textify

import android.content.Context
import android.os.Handler
import android.os.Looper
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
 * Hy-MT2-1.8B on LiteRT-LM GPU (community int8 `.litertlm`, ~1.83 GB, downloaded on demand). The only code that
 * touches LiteRT-LM. Call from the main thread; every callback runs on the main thread. Inference runs serially on
 * one worker thread; the engine is created lazily there and closed by [release].
 */
object HyMt {
    private const val MODEL_NAME = "Hy-MT2-1.8B_int8.litertlm"
    // Pinned revision + exact size: a resumed download can never mix two model versions (a file of any other size is rejected).
    private const val MODEL_REVISION = "1ac9676f3d10c7f64835eb83026e6df2ec343d1f"
    private const val MODEL_SIZE = 1_827_026_224L
    private const val MODEL_URL = "https://huggingface.co/litert-community/Hy-MT2-1.8B/resolve/$MODEL_REVISION/$MODEL_NAME"

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    /** Fired on the main thread when download state/progress or gpuFailed changes. Settings sets it while resumed. */
    var onChanged: (() -> Unit)? = null

    @Volatile private var _downloading = false
    @Volatile private var _waitingWifi = false
    @Volatile private var _progress = 0f
    @Volatile private var _gpuFailed = false
    /** Bumped by [delete]; a download thread of an older generation stops and never publishes its file. */
    @Volatile private var generation = 0

    /** Touched on [worker] only (and its release task). */
    private var engine: Engine? = null

    val downloading: Boolean get() = _downloading
    val waitingWifi: Boolean get() = _waitingWifi
    val progress: Float get() = _progress
    val gpuFailed: Boolean get() = _gpuFailed

    private fun modelFile(ctx: Context) = File(ctx.applicationContext.filesDir, MODEL_NAME)
    private fun partFile(ctx: Context) = File(ctx.applicationContext.filesDir, "$MODEL_NAME.part")

    private fun changed() { main.post { onChanged?.invoke() } }

    /** Model file fully present (the `.part` file is renamed only when complete). */
    fun isDownloaded(ctx: Context): Boolean = modelFile(ctx).isFile

    /** ML Kit language code is in the Hy-MT2 language set. */
    fun supports(mlkitCode: String): Boolean = HyMtRules.supports(mlkitCode)

    /** Starts or joins the download (resumable). False = blocked by Wi-Fi-only (waitingWifi set). */
    fun download(ctx: Context): Boolean {
        val app = ctx.applicationContext
        if (_downloading) return true
        if (isDownloaded(app)) return true
        if (Prefs.wifiOnly(app) && !Translate.isOnWifi(app)) {
            _waitingWifi = true
            changed()
            return false
        }
        _waitingWifi = false
        _downloading = true
        _progress = 0f
        changed()
        val gen = generation
        Thread({ downloadBlocking(app, gen) }, "hymt-download").start()
        return true
    }

    private fun downloadBlocking(app: Context, gen: Int) {
        val part = partFile(app)
        var lastPct = -1
        try {
            if (part.length() > MODEL_SIZE) part.delete()                // corrupt/oversized: restart from 0
            if (part.length() != MODEL_SIZE) {
                var offset = if (part.isFile) part.length() else 0L
                var conn = open(offset)
                if (conn.responseCode == 416) {                           // server rejects the offset: restart from 0
                    conn.disconnect()
                    part.delete()
                    offset = 0L
                    conn = open(0L)
                }
                try {
                    val resumed = conn.responseCode == 206
                    if (conn.responseCode != 200 && !resumed) throw java.io.IOException("HTTP ${conn.responseCode}")
                    var done = if (resumed) offset else 0L               // 200 = server ignored Range: restart the file
                    FileOutputStream(part, resumed).use { out ->
                        conn.inputStream.use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (gen != generation) return             // delete() ran: stop quietly, it owns the files
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val pct = (done * 100 / MODEL_SIZE).toInt().coerceAtMost(100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    _progress = pct / 100f
                                    changed()
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }
            if (gen != generation) return
            if (part.length() != MODEL_SIZE) {                            // wrong size: never publish; next download() starts over
                part.delete()
                throw java.io.IOException("size mismatch")
            }
            if (!part.renameTo(modelFile(app))) throw java.io.IOException("rename failed")
        } catch (e: Exception) {
            // ponytail: no retry/backoff; a short .part stays for the next download() to resume from.
        } finally {
            if (gen == generation) _downloading = false
            changed()
        }
    }

    private fun open(offset: Long): HttpURLConnection {
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
        return conn
    }

    /** Cancels any download and deletes the model (and partial file); releases the engine. */
    fun delete(ctx: Context) {
        val app = ctx.applicationContext
        generation++
        _downloading = false
        _waitingWifi = false
        _progress = 0f
        release()
        worker.execute {                                  // after release(), so the engine no longer maps the file
            modelFile(app).delete()
            partFile(app).delete()
            changed()
        }
    }

    /**
     * Translates one paragraph ([source] = ML Kit code, already flattened text) into [target], serially.
     * [onResult] gets null on any failure. Output is NOT OpenCC-converted.
     */
    fun translate(ctx: Context, text: String, source: String, target: TargetLang, onResult: (String?) -> Unit) {
        val app = ctx.applicationContext
        val prompt = HyMtRules.prompt(text, target)
        worker.execute {
            val result = try {
                if (prompt == null || _gpuFailed) null else run(app, prompt)
            } catch (t: Throwable) {                      // every call must report exactly once, even on Error
                null
            }
            main.post { onResult(result) }
        }
    }

    private fun run(app: Context, prompt: String): String? {
        val e = engine ?: try {
            val file = modelFile(app)
            if (!file.isFile) return null
            Engine(EngineConfig(modelPath = file.path, backend = Backend.GPU(), cacheDir = app.cacheDir.path))
                .also { it.initialize() }
        } catch (t: Throwable) {                          // includes UnsatisfiedLinkError when OpenCL is missing
            _gpuFailed = true
            changed()
            return null
        }
        engine = e
        return try {
            // Sampling from the Hy-MT2 model card (top_k 20, top_p 0.6, temperature 0.7); a fresh conversation per paragraph keeps them independent.
            e.createConversation(ConversationConfig(samplerConfig = SamplerConfig(topK = 20, topP = 0.6, temperature = 0.7)))
                .use { HyMtRules.clean(it.sendMessage(prompt).toString()) }
        } catch (t: Throwable) {
            null
        }
    }

    /** Closes the engine (frees GPU memory). Safe to call repeatedly. */
    fun release() {
        worker.execute {
            engine?.close()
            engine = null
        }
    }
}
