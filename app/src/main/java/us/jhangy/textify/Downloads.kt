package us.jhangy.textify

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * One file of a Hugging Face repo, pinned: [revision] + exact [size] mean a resumed download can never mix two versions
 * (any other size is rejected). Pure (no Android), so it runs on the JVM test runner.
 */
class PinnedFile(val repo: String, val file: String, val revision: String, val size: Long) {
    val url: String get() = "https://huggingface.co/$repo/resolve/$revision/$file"

    /** Path under the app files dir; one subdir per repo so equal file names of different repos never clash. */
    val relPath: String get() = "$repo/$file"

    /** A fully downloaded file has exactly [size] bytes. */
    fun complete(length: Long): Boolean = length == size
}

/** The three files manga-ocr needs: ONNX encoder + decoder (int8) and the tokenizer vocabulary. */
class MangaOcrFiles(val encoder: PinnedFile, val decoder: PinnedFile, val vocab: PinnedFile) {
    val all: List<PinnedFile> get() = listOf(encoder, decoder, vocab)
}

/** Same files as the desktop bench (scripts/tmp/ocrbench/hybrid/run.py). Both repos are apache-2.0. */
val MANGA_OCR = MangaOcrFiles(
    encoder = PinnedFile("onnx-community/manga-ocr-base-ONNX", "onnx/encoder_model_int8.onnx", "f9023406bb2f6b17df67bc4a327c56ecd20611f0", 86_967_767L),
    decoder = PinnedFile("onnx-community/manga-ocr-base-ONNX", "onnx/decoder_model_int8.onnx", "f9023406bb2f6b17df67bc4a327c56ecd20611f0", 29_627_936L),
    vocab = PinnedFile("kha-white/manga-ocr-base", "vocab.txt", "aa6573bd10b0d446cbf622e29c3e084914df9741", 24_072L),
)

/**
 * The one downloader: resumable pinned-file downloads, grouped (an LLM = 1 file, manga-ocr = 3 files; progress over the
 * group's total bytes). Call from the main thread; [onChanged] runs on the main thread.
 */
object Downloads {
    private val main = Handler(Looper.getMainLooper())

    /** Fired on the main thread when any group's state/progress changes. Settings sets it while resumed. */
    var onChanged: (() -> Unit)? = null

    private class Slot {
        @Volatile var downloading = false
        @Volatile var waitingWifi = false
        @Volatile var progress = 0f
        /** Bumped by [delete]; a download thread of an older generation stops and never publishes its files. */
        @Volatile var generation = 0
    }

    private val slots = ConcurrentHashMap<String, Slot>()
    private fun slot(files: List<PinnedFile>) = slots.getOrPut(files.joinToString("|") { it.url }) { Slot() }

    private fun changed() { main.post { onChanged?.invoke() } }

    fun file(ctx: Context, f: PinnedFile): File = File(ctx.applicationContext.filesDir, f.relPath)
    private fun part(ctx: Context, f: PinnedFile) = File(ctx.applicationContext.filesDir, "${f.relPath}.part")

    fun downloading(files: List<PinnedFile>): Boolean = slot(files).downloading
    fun waitingWifi(files: List<PinnedFile>): Boolean = slot(files).waitingWifi
    fun progress(files: List<PinnedFile>): Float = slot(files).progress

    /** All files present (a `.part` file is renamed only when complete). */
    fun isDownloaded(ctx: Context, files: List<PinnedFile>): Boolean = files.all { file(ctx, it).isFile }

    /** Starts or joins the download of [files] (resumable). False = blocked by Wi-Fi-only (waitingWifi set). */
    fun download(ctx: Context, files: List<PinnedFile>): Boolean {
        val app = ctx.applicationContext
        val s = slot(files)
        if (s.downloading) return true
        if (isDownloaded(app, files)) return true
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
        Thread({ downloadBlocking(app, files, s, gen) }, "download-${files.first().file}").start()
        return true
    }

    private fun downloadBlocking(app: Context, files: List<PinnedFile>, s: Slot, gen: Int) {
        val total = files.sumOf { it.size }
        var base = 0L                                                       // bytes of the files already finished
        var lastPct = -1
        fun report(done: Long) {
            val pct = (done * 100 / total).toInt().coerceAtMost(100)
            if (pct != lastPct) {
                lastPct = pct
                s.progress = pct / 100f
                changed()
            }
        }
        try {
            for (f in files) {
                if (file(app, f).isFile) { base += f.size; continue }
                val part = part(app, f)
                part.parentFile?.mkdirs()
                if (part.length() > f.size) part.delete()                   // corrupt/oversized: restart from 0
                if (!f.complete(part.length())) {
                    var offset = if (part.isFile) part.length() else 0L
                    var conn = open(f, offset)
                    if (conn.responseCode == 416) {                         // server rejects the offset: restart from 0
                        conn.disconnect()
                        part.delete()
                        offset = 0L
                        conn = open(f, 0L)
                    }
                    try {
                        val resumed = conn.responseCode == 206
                        if (conn.responseCode != 200 && !resumed) throw java.io.IOException("HTTP ${conn.responseCode}")
                        var done = if (resumed) offset else 0L              // 200 = server ignored Range: restart the file
                        FileOutputStream(part, resumed).use { out ->
                            conn.inputStream.use { input ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    if (gen != s.generation) return         // delete() ran: stop quietly, it owns the files
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    report(base + done)
                                }
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                }
                if (gen != s.generation) return
                if (!f.complete(part.length())) {                           // wrong size: never publish; next download() starts over
                    part.delete()
                    throw java.io.IOException("size mismatch")
                }
                if (!part.renameTo(file(app, f))) throw java.io.IOException("rename failed")
                base += f.size
            }
        } catch (e: Exception) {
            // ponytail: no retry/backoff; a short .part stays for the next download() to resume from.
        } finally {
            if (gen == s.generation) s.downloading = false
            changed()
        }
    }

    private fun open(f: PinnedFile, offset: Long): HttpURLConnection {
        val conn = URL(f.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
        return conn
    }

    /** Cancels any download of [files] and deletes them (and partial files). Caller closes any engine mapping them first. */
    fun delete(ctx: Context, files: List<PinnedFile>) {
        val s = slot(files)
        s.generation++
        s.downloading = false
        s.waitingWifi = false
        s.progress = 0f
        files.forEach { file(ctx, it).delete(); part(ctx, it).delete() }
        changed()
    }
}
