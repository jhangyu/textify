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

/** One recognised text line. [box] is in screenshot (bitmap) pixels. */
data class OcrLine(val text: String, val box: Rect)

/** On-device OCR. Every selected script model runs fully offline, all in parallel on the same image. */
object Ocr {
    /**
     * Calls [onResult] exactly once on the main thread after every recognizer completed; overlapping lines
     * are merged by [mergeOcr] (sorted top, then left). A failing recognizer contributes no lines.
     */
    fun recognize(ctx: Context, bitmap: Bitmap, scripts: Set<Script>, onResult: (List<OcrLine>) -> Unit) {
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
                    .map { OcrLine(it.text, Rect(it.left, it.top, it.right, it.bottom)) })
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

    private fun candidates(result: Text): List<OcrCandidate> = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
        val b = line.boundingBox
        if (b == null || line.text.isBlank()) null
        else OcrCandidate(line.text, b.left, b.top, b.right, b.bottom, line.confidence)   // Text.Line.getConfidence(), §12
    }
}
