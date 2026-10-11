package us.jhangy.textify

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import us.jhangy.textify.databinding.ActivityResultBinding

/** Transparent sheet showing the recognized text as selectable, copyable content. */
class ResultActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityResultBinding.inflate(layoutInflater)
        setContentView(b.root)

        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        b.resultText.text = text

        b.copyButton.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("textify", text))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            finish()
        }
        b.closeButton.setOnClickListener { finish() }
        b.root.setOnClickListener { finish() }
    }

    companion object {
        private const val EXTRA_TEXT = "text"
        fun show(ctx: Context, text: String) {
            ctx.startActivity(
                Intent(ctx, ResultActivity::class.java)
                    .putExtra(EXTRA_TEXT, text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
