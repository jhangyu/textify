package us.jhangy.textify

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * manga-ocr (kha-white/manga-ocr-base, onnx-community int8 export) on onnxruntime: re-reads one crop of a Japanese
 * bubble as a single line of text in reading order. [encoder]/[decoder]/[vocab] = the downloaded model files; the
 * sessions open on first [read] and live until [close]. Everything runs on one background thread ([post]).
 */
class MangaOcr(private val encoder: File, private val decoder: File, private val vocab: File) {
    private val thread = Executors.newSingleThreadExecutor { Thread(it, "manga-ocr") }
    private var sessions: Pair<OrtSession, OrtSession>? = null
    private var words: List<String> = emptyList()
    private var broken = false                // opening failed once: every later read fails fast

    /** Runs [task] on the inference thread; false once [close]d. */
    fun post(task: () -> Unit): Boolean = try {
        thread.execute(task)
        true
    } catch (e: RejectedExecutionException) {
        false
    }

    /** Closes the sessions after any queued read, then stops the thread. */
    fun close() {
        post { sessions?.let { it.first.close(); it.second.close() }; sessions = null }
        thread.shutdown()
    }

    /**
     * Text of one crop ([argb] row-major, [w]×[h]); null = model missing or broken, or an empty / looping /
     * unfinished read. Inference thread only (tests: any single thread).
     */
    fun read(argb: IntArray, w: Int, h: Int): String? = try {
        val (enc, dec) = open()
        val env = OrtEnvironment.getEnvironment()
        val x = OnnxTensor.createTensor(env, FloatBuffer.wrap(mangaPixels(argb, w, h)), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong()))
        x.use {
            enc.run(mapOf("pixel_values" to x)).use { e ->
                val hidden = e.get(0) as OnnxTensor
                greedyDecode { ids ->
                    OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { t ->
                        dec.run(mapOf("input_ids" to t, "encoder_hidden_states" to hidden)).use { r ->
                            val logits = (r.get(0) as OnnxTensor).floatBuffer   // [1, ids, vocab]
                            val v = logits.capacity() / ids.size
                            argmax(logits, (ids.size - 1) * v, v)
                        }
                    }
                }?.let { mangaText(it, words) }?.ifEmpty { null }
            }
        }
    } catch (e: Throwable) {                  // ponytail: any failure (incl. native load errors) = keep the ML Kit text
        Log.w(TAG, "manga-ocr read failed", e)
        null
    }

    private fun open(): Pair<OrtSession, OrtSession> {
        sessions?.let { return it }
        check(!broken) { "model failed to open" }
        broken = true
        val env = OrtEnvironment.getEnvironment()
        val enc = env.createSession(encoder.path)
        val dec = try { env.createSession(decoder.path) } catch (e: Exception) { enc.close(); throw e }
        words = vocab.readLines()
        broken = false
        return (enc to dec).also { sessions = it }
    }

    private companion object {
        const val TAG = "MangaOcr"
    }
}

private const val SIZE = 224                // encoder input (preprocessor_config.json)
private const val START = 2L                // decoder_start_token_id ([CLS])
private const val EOS = 3                   // eos_token_id ([SEP]); ids 0..3 = PAD UNK CLS SEP
private const val MAX_TOKENS = 300          // generation_config.json max_length, start token included
private const val REPEAT_MIN = 8            // a loop = the last ≥ 8 tokens repeat one cycle
private const val MAX_CYCLE = 16

private fun argmax(b: FloatBuffer, from: Int, n: Int): Int {
    var best = 0
    for (i in 1 until n) if (b.get(from + i) > b.get(from + best)) best = i
    return best
}

/**
 * Greedy decode: [next] = the most likely token after the ids so far (start token first). Returns the tokens before
 * EOS, or null when they loop ([looping]) or hit [MAX_TOKENS] without EOS.
 */
internal fun greedyDecode(next: (LongArray) -> Int): List<Int>? {
    val ids = arrayListOf(START)
    val out = ArrayList<Int>()
    while (ids.size < MAX_TOKENS) {
        val n = next(ids.toLongArray())
        if (n == EOS) return out
        ids += n.toLong()
        out += n
        if (looping(out)) return null
    }
    return null
}

/**
 * True when the last max([REPEAT_MIN], 2·p) tokens repeat one cycle of length p (1..[MAX_CYCLE]), e.g. 8 equal
 * tokens or the same 6 tokens twice. Greedy manga-ocr can fall into such loops on badly cropped text.
 * ponytail: a real text repeating a phrase that often (ドキドキドキドキ) counts as a loop and keeps the ML Kit text.
 */
internal fun looping(t: List<Int>): Boolean {
    for (p in 1..MAX_CYCLE) {
        val span = max(REPEAT_MIN, 2 * p)
        if (t.size < span) return false
        if ((t.size - span + p until t.size).all { t[it] == t[it - p] }) return true
    }
    return false
}

private val DOTS = Regex("[.．・]{2,}")

/** Token ids -> text, as manga_ocr's post-processing: specials dropped, "##" pieces joined, no whitespace, then [mangaNormalize]. */
internal fun mangaText(ids: List<Int>, vocab: List<String>): String =
    mangaNormalize(ids.filter { it > EOS && it < vocab.size }.joinToString("") { vocab[it].removePrefix("##") })

/** No whitespace; a run of 2+ dots (．．．, ..., ・・・) -> "…"; other ASCII -> full width ("!" -> "！"), as manga_ocr does. */
internal fun mangaNormalize(s: String): String =
    DOTS.replace(s.filterNot { it.isWhitespace() }, "…").map { if (it in '!'..'~') it + 0xFEE0 else it }.joinToString("")

/**
 * Encoder input from an ARGB crop: grey (PIL "L" weights), resized to 224×224 ignoring the aspect ratio with a
 * triangle (bilinear) filter widened when shrinking, as PIL does, scaled to [-1, 1], copied to 3 channels (NCHW).
 */
internal fun mangaPixels(argb: IntArray, w: Int, h: Int): FloatArray {
    val grey = FloatArray(w * h) { i ->
        val c = argb[i]
        (((c shr 16 and 0xFF) * 19595 + (c shr 8 and 0xFF) * 38470 + (c and 0xFF) * 7471 + 0x8000) shr 16).toFloat()
    }
    val xs = taps(w, SIZE)
    val ys = taps(h, SIZE)
    val rows = FloatArray(h * SIZE)           // horizontal pass
    for (y in 0 until h) for (x in 0 until SIZE) {
        val (from, wt) = xs[x]
        var s = 0f
        for (k in wt.indices) s += grey[y * w + from + k] * wt[k]
        rows[y * SIZE + x] = s
    }
    val plane = SIZE * SIZE
    val out = FloatArray(3 * plane)
    for (y in 0 until SIZE) for (x in 0 until SIZE) {
        val (from, wt) = ys[y]
        var s = 0f
        for (k in wt.indices) s += rows[(from + k) * SIZE + x] * wt[k]
        val v = s.coerceIn(0f, 255f) / 127.5f - 1f
        out[y * SIZE + x] = v
        out[plane + y * SIZE + x] = v
        out[2 * plane + y * SIZE + x] = v
    }
    return out
}

/** Per output pixel: first input index and normalised triangle-filter weights (support scaled by the shrink factor). */
private fun taps(inSize: Int, outSize: Int): List<Pair<Int, FloatArray>> {
    val scale = inSize.toFloat() / outSize
    val support = max(1f, scale)
    return List(outSize) { i ->
        val centre = (i + 0.5f) * scale
        val lo = max(0, floor(centre - support).toInt())
        val hi = min(inSize, ceil(centre + support).toInt())
        val wt = FloatArray(hi - lo) { k -> max(0f, 1f - abs(lo + k + 0.5f - centre) / support) }
        val sum = wt.sum()
        if (sum > 0f) for (k in wt.indices) wt[k] /= sum
        lo to wt
    }
}
