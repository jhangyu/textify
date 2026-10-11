package us.jhangy.textify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

/** One recognised text line. [box] is in screenshot (bitmap) pixels. */
data class OcrLine(val text: String, val box: Rect)

/** On-device OCR. The recognizer for the chosen script runs fully offline. */
object Ocr {
    /** Calls [onResult] once on the main thread, lines sorted top-to-bottom then left-to-right. On failure the list is empty. */
    fun recognize(ctx: Context, bitmap: Bitmap, script: Script, onResult: (List<OcrLine>) -> Unit) {
        val recognizer: TextRecognizer = when (script) {
            Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        }
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    onResult(
                        result.textBlocks.flatMap { it.lines }
                            .mapNotNull { line -> line.boundingBox?.let { OcrLine(line.text, it) } }
                            .filter { it.text.isNotBlank() }
                            .sortedWith(compareBy({ it.box.top }, { it.box.left }))
                    )
                }
                .addOnFailureListener { onResult(emptyList()) }
                .addOnCompleteListener { recognizer.close() }
        } catch (e: Exception) {
            // e.g. recycled bitmap: InputImage.fromBitmap / process throws synchronously
            recognizer.close()
            onResult(emptyList())
        }
    }
}
