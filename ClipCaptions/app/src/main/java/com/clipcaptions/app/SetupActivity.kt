package com.clipcaptions.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/**
 * The guide shown the first time the app opens: what it does, the two things Android asks you to
 * allow (and why), and what to choose when Android asks what to share. Can be opened again from
 * the main screen.
 */
class SetupActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "settings"
        private const val KEY_DONE = "setup_done"

        fun isDone(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)
    }

    private lateinit var content: LinearLayout
    private var page = 0
    private var overlayButton: Button? = null
    private var soundButton: Button? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        showPage()
    }

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        setContentView(scroll)
        padForSystemBars(scroll, dp(28f), dp(40f))
        page = savedInstanceState?.getInt("page") ?: 0
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page > 0) {
                    page--
                    showPage()
                } else {
                    finishSetup()
                }
            }
        })
        showPage()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", page)
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the "Display over other apps" settings page.
        if (page == 1) showPage()
    }

    private fun showPage() {
        content.removeAllViews()
        content.addView(TextView(this).apply {
            text = "${page + 1} of 3"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        })
        when (page) {
            0 -> welcome()
            1 -> permissions()
            else -> sharing()
        }
    }

    private fun title(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium)
        setPadding(0, dp(12f), 0, dp(12f))
    })

    private fun body(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        setLineSpacing(0f, 1.2f)
        setPadding(0, 0, 0, dp(16f))
    })

    private fun button(text: String, primary: Boolean = true, onClick: () -> Unit): Button {
        val b = if (primary) {
            MaterialButton(this)
        } else {
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        }
        b.text = text
        b.minHeight = dp(52f)
        b.setOnClickListener { onClick() }
        content.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8f)
        })
        return b
    }

    private fun spacer() = content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

    private fun next() {
        page++
        showPage()
    }

    private fun welcome() {
        title("Captions for every video")
        body(
            "Clip Captions shows what people say in TikTok, Reels and Shorts as text, translated into your " +
                "language, in a small box over the video.",
        )
        body("It works with AirPods and headphones, it's free, and nothing is recorded or uploaded.")
        body("Tap ☆ on the caption box to save a phrase you want to learn.")
        spacer()
        button("Next") { next() }
    }

    private fun permissions() {
        title("Two things to allow")
        body("Display over other apps: so the captions can float over TikTok.")
        val overlayOk = Settings.canDrawOverlays(this)
        overlayButton = button(if (overlayOk) "✓ Display over other apps is on" else "Allow display over other apps", primary = !overlayOk) {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }.also { it.isEnabled = !overlayOk }
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(20f)))
        body(
            "Sound: Android calls this Microphone, but Clip Captions only uses the sound your phone is playing. " +
                "Your microphone is never used. It also shows a notification with a Stop button while captions are on.",
        )
        val soundOk = hasSoundPermission()
        soundButton = button(if (soundOk) "✓ Sound is allowed" else "Allow sound", primary = !soundOk) {
            val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
            permissionLauncher.launch(wanted.toTypedArray())
        }.also { it.isEnabled = !soundOk }
        spacer()
        button(if (overlayOk && soundOk) "Next" else "Next (you can allow these later)") { next() }
    }

    private fun sharing() {
        title("When you start captions")
        body(
            "Android asks what to share. Choose “A single app”, then pick TikTok (or whichever app you're " +
                "watching). Your notifications stay visible, and only the sound is used.",
        )
        body(
            "On the next screen, tap Choose languages and tick the languages you watch. They download once, " +
                "so nothing downloads while you're scrolling.",
        )
        body("Captions clear when you swipe to the next video. Drag the box to move it, and tap ✕ to stop.")
        spacer()
        button("Get started") { finishSetup() }
    }

    private fun hasSoundPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun finishSetup() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
        setResult(RESULT_OK)
        finish()
    }
}
