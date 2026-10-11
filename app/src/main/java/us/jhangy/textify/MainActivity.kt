package us.jhangy.textify

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import us.jhangy.textify.databinding.ActivityMainBinding

/**
 * Entry screen: grabs the two permissions the capture flow needs (overlay +
 * screen-capture consent), lets the user pick the OCR script, then starts the
 * floating bubble service.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val projectionConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK && res.data != null) {
                CaptureService.start(this, res.resultCode, res.data!!)
                Toast.makeText(this, R.string.bubble_started, Toast.LENGTH_SHORT).show()
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, R.string.consent_denied, Toast.LENGTH_SHORT).show()
            }
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.scriptGroup.check(
            when (Prefs.script(this)) {
                Script.CHINESE -> R.id.scriptChinese
                Script.JAPANESE -> R.id.scriptJapanese
                Script.KOREAN -> R.id.scriptKorean
            }
        )
        b.scriptGroup.setOnCheckedChangeListener { _, id ->
            Prefs.setScript(
                this,
                when (id) {
                    R.id.scriptJapanese -> Script.JAPANESE
                    R.id.scriptKorean -> Script.KOREAN
                    else -> Script.CHINESE
                }
            )
        }

        b.startButton.setOnClickListener { ensurePermissionsThenCapture() }
        b.stopButton.setOnClickListener { CaptureService.stop(this) }
    }

    private fun ensurePermissionsThenCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            Toast.makeText(this, R.string.grant_overlay, Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionConsent.launch(mpm.createScreenCaptureIntent())
    }
}
