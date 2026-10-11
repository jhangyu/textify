package us.jhangy.textify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

/**
 * One recognised paragraph (ML Kit TextBlock). [text] = its lines joined with "\n"; [box] and the per-line
 * [lines] (top to bottom) are in screenshot (bitmap) pixels. [vertical] = CJK text set top-to-bottom, columns right-to-left.
 */
data class OcrBlock(val text: String, val box: Rect, val lines: List<Rect>, val vertical: Boolean = false)

internal fun Rect.toBox(): Box = Box(left, top, right, bottom)

private fun Box.toRect(): Rect = Rect(left, top, right, bottom)

/** On-device OCR. Every selected script model runs fully offline, all in parallel on the same image. */
object Ocr {
    /**
     * Calls [onResult] exactly once on the main thread after every recognizer completed; overlapping paragraphs
     * are merged by [mergeOcr] (sorted top, then left). A failing recognizer contributes no paragraphs.
     */
    fun recognize(ctx: Context, bitmap: Bitmap, scripts: Set<Script>, onResult: (List<OcrBlock>) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val image = try {
            InputImage.fromBitmap(bitmap, 0)
        } catch (e: Exception) {                 // e.g. recycled bitmap
            main.post { onResult(emptyList()) }
            return
        }
        if (scripts.isEmpty()) { main.post { onResult(emptyList()) }; return }
        val parts = arrayOfNulls<List<OcrCandidate>>(scripts.size)   // touched on the main thread only
        var pending = scripts.size
        fun done(i: Int, found: List<OcrCandidate>) = main.post {
            parts[i] = found
            if (--pending == 0) {
                onResult(mergeOcr(parts.flatMap { it.orEmpty() })
                    .map { c -> OcrBlock(c.text, c.box.toRect(), c.lines.map { it.toRect() }, c.vertical) })
            }
        }
        scripts.forEachIndexed { i, script ->
            val recognizer = client(script)
            try {
                recognizer.process(image)
                    .addOnSuccessListener { done(i, candidates(it)) }
                    .addOnFailureListener { done(i, emptyList()) }
                    .addOnCompleteListener { recognizer.close() }
            } catch (e: Exception) {             // synchronous throw from process()
                recognizer.close()
                done(i, emptyList())
            }
        }
    }

    private fun client(script: Script): TextRecognizer = when (script) {
        Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    /** Translation spec §1: one candidate per TextBlock; blank or box-less lines dropped; confidence = mean line confidence; orientation by [isVertical]. */
    private fun candidates(result: Text): List<OcrCandidate> = result.textBlocks.mapNotNull { block ->
        val lines = block.lines.filter { it.boundingBox != null && it.text.isNotBlank() }
        if (lines.isEmpty()) return@mapNotNull null
        val boxes = lines.map { it.boundingBox!!.toBox() }
        val box = block.boundingBox?.toBox() ?: Box(
            boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom },
        )
        ocrCandidate(lines.map { it.text }, boxes, box, paragraphConfidence(lines.map { it.confidence }))
    }
}
