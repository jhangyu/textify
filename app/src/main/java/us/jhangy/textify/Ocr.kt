package us.jhangy.textify

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

/** On-device OCR. The recognizer for the chosen script runs fully offline. */
object Ocr {
    fun recognize(ctx: Context, bitmap: Bitmap, script: Script, onResult: (String?) -> Unit) {
        val recognizer: TextRecognizer = when (script) {
            Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        }
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { onResult(it.text) }
            .addOnFailureListener { onResult(null) }
            .addOnCompleteListener { recognizer.close() }
    }
}
