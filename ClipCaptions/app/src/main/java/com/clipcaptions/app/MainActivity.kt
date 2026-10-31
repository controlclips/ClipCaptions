package com.clipcaptions.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var sourceSpinner: Spinner
    private lateinit var targetSpinner: Spinner
    private lateinit var startButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    private val io = Executors.newSingleThreadExecutor()
    private var busy = false
    private var pendingModelPath: String? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasAudioPermission()) {
            prepareSpeechModel()
        } else {
            fail(
                "Clip Captions needs the Microphone permission. Android uses that permission for capturing " +
                    "app sound too. The app doesn't listen to your microphone.",
            )
        }
    }

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val modelPath = pendingModelPath
        if (result.resultCode != Activity.RESULT_OK || data == null || modelPath == null) {
            fail("Captions didn't start because the sharing request was cancelled. Tap Start captions to try again.")
            return@registerForActivityResult
        }
        busy = false
        startCaptionService(result.resultCode, data, modelPath)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        sourceSpinner = findViewById(R.id.sourceSpinner)
        targetSpinner = findViewById(R.id.targetSpinner)
        startButton = findViewById(R.id.startButton)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)

        sourceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, Languages.spoken.map { it.name })
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        targetSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, Languages.caption.map { it.name })
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val savedSource = prefs.getString("source", "en")
        val savedTarget = prefs.getString("target", "en")
        sourceSpinner.setSelection(Languages.spoken.indexOfFirst { it.code == savedSource }.coerceAtLeast(0))
        targetSpinner.setSelection(Languages.caption.indexOfFirst { it.code == savedTarget }.coerceAtLeast(0))

        val onChange = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit()
                    .putString("source", selectedSpoken().code)
                    .putString("target", selectedCaption().code)
                    .apply()
                if (!busy && !CaptionService.isRunning) setStatus(describeChoice())
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        sourceSpinner.onItemSelectedListener = onChange
        targetSpinner.onItemSelectedListener = onChange

        startButton.setOnClickListener {
            if (CaptionService.isRunning) stopCaptions() else startCaptions()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        if (!busy) setStatus(if (CaptionService.isRunning) runningMessage() else describeChoice())
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ---- Start-up steps: overlay permission → sound permission → speech model → translation → share sound ----

    private fun startCaptions() {
        if (!Settings.canDrawOverlays(this)) {
            setStatus("Turn on “Allow display over other apps” for Clip Captions, then come back and tap Start captions.")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        busy = true
        refreshUi()
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) prepareSpeechModel() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun prepareSpeechModel() {
        val spoken = selectedSpoken()
        val caption = selectedCaption()
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        setStatus("Getting the ${spoken.name} speech model ready…")
        io.execute {
            try {
                val modelDir = VoskModels.installed(this, spoken.voskModel)
                    ?: VoskModels.download(this, spoken.voskModel) { percent ->
                        runOnUiThread {
                            progress.isIndeterminate = false
                            progress.progress = percent
                            setStatus("Downloading the ${spoken.name} speech model (first time only)… $percent%")
                        }
                    }
                runOnUiThread { prepareTranslation(spoken, caption, modelDir.absolutePath) }
            } catch (e: Exception) {
                runOnUiThread {
                    fail("Couldn't download the ${spoken.name} speech model: ${e.message}. Check your internet connection and tap Start captions again.")
                }
            }
        }
    }

    private fun prepareTranslation(spoken: SpokenLanguage, caption: CaptionLanguage, modelPath: String) {
        if (spoken.code == caption.code) {
            requestSoundSharing(modelPath)
            return
        }
        val from = TranslateLanguage.fromLanguageTag(spoken.code)
        val to = TranslateLanguage.fromLanguageTag(caption.code)
        if (from == null || to == null) {
            fail("Translating ${spoken.name} into ${caption.name} isn't available.")
            return
        }
        progress.isIndeterminate = true
        setStatus("Getting ${spoken.name} → ${caption.name} translation ready (first time only)…")
        val translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build(),
        )
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translator.close()
                requestSoundSharing(modelPath)
            }
            .addOnFailureListener { e ->
                translator.close()
                fail("Couldn't download the translation files: ${e.message}. Check your internet connection and tap Start captions again.")
            }
    }

    private fun requestSoundSharing(modelPath: String) {
        pendingModelPath = modelPath
        progress.visibility = View.GONE
        setStatus("Android will now ask to share your screen. Tap Start. Only the sound is used.")
        val manager = getSystemService(MediaProjectionManager::class.java)
        val request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        captureLauncher.launch(request)
    }

    private fun startCaptionService(resultCode: Int, data: Intent, modelPath: String) {
        val intent = Intent(this, CaptionService::class.java).apply {
            action = CaptionService.ACTION_START
            putExtra(CaptionService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptionService.EXTRA_RESULT_DATA, data)
            putExtra(CaptionService.EXTRA_MODEL_PATH, modelPath)
            putExtra(CaptionService.EXTRA_SOURCE, selectedSpoken().code)
            putExtra(CaptionService.EXTRA_TARGET, selectedCaption().code)
        }
        ContextCompat.startForegroundService(this, intent)
        CaptionService.isRunning = true
        refreshUi()
        setStatus(runningMessage())
        openTikTok()
    }

    private fun stopCaptions() {
        startService(Intent(this, CaptionService::class.java).setAction(CaptionService.ACTION_STOP))
        CaptionService.isRunning = false
        refreshUi()
        setStatus("Captions are off.")
    }

    private fun openTikTok() {
        val launch = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
            .firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
        if (launch != null) startActivity(launch)
    }

    // ---- Helpers ----

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun selectedSpoken() = Languages.spoken[sourceSpinner.selectedItemPosition.coerceAtLeast(0)]

    private fun selectedCaption() = Languages.caption[targetSpinner.selectedItemPosition.coerceAtLeast(0)]

    private fun describeChoice(): String {
        val spoken = selectedSpoken()
        val caption = selectedCaption()
        return if (spoken.code == caption.code) {
            "Shows ${spoken.name} speech as ${spoken.name} text. Pick another language under “Show the words in” to translate."
        } else {
            "Translates ${spoken.name} speech into ${caption.name}."
        }
    }

    private fun runningMessage() =
        "Captions are on. Play a TikTok and the words appear in the floating box. Drag the box to move it, or tap ✕ to stop."

    private fun refreshUi() {
        val running = CaptionService.isRunning
        startButton.text = if (running) "Stop captions" else "Start captions"
        startButton.isEnabled = !busy
        sourceSpinner.isEnabled = !busy && !running
        targetSpinner.isEnabled = !busy && !running
    }

    private fun setStatus(message: String) {
        status.text = message
    }

    private fun fail(message: String) {
        busy = false
        progress.visibility = View.GONE
        setStatus(message)
        refreshUi()
    }
}
