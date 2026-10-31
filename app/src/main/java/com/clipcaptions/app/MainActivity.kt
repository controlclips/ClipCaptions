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
import android.widget.RadioButton
import android.widget.RadioGroup
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

    /** Everything the caption service needs once Android has agreed to share the sound. */
    private data class Launch(
        val auto: Boolean,
        val voskModel: String? = null,
        val whisperModel: String? = null,
        val whisperFallback: String? = null,
        val vadModel: String? = null,
    )

    private lateinit var modeGroup: RadioGroup
    private lateinit var modeAuto: RadioButton
    private lateinit var modeLive: RadioButton
    private lateinit var autoUnavailable: TextView
    private lateinit var sourceSpinner: Spinner
    private lateinit var targetSpinner: Spinner
    private lateinit var speedSection: View
    private lateinit var speedSpinner: Spinner
    private lateinit var startButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    private val io = Executors.newSingleThreadExecutor()
    private var busy = false
    private var pendingLaunch: Launch? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasAudioPermission()) {
            if (isAutoMode()) prepareAuto() else prepareSpeechModel()
        } else {
            fail(
                "Clip Captions needs the Microphone permission. Android uses that permission for capturing " +
                    "app sound too. The app doesn't listen to your microphone.",
            )
        }
    }

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val launch = pendingLaunch
        if (result.resultCode != Activity.RESULT_OK || data == null || launch == null) {
            fail("Captions didn't start because the sharing request was cancelled. Tap Start captions to try again.")
            return@registerForActivityResult
        }
        busy = false
        startCaptionService(result.resultCode, data, launch)
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

        modeGroup = findViewById(R.id.modeGroup)
        modeAuto = findViewById(R.id.modeAuto)
        modeLive = findViewById(R.id.modeLive)
        autoUnavailable = findViewById(R.id.autoUnavailable)
        sourceSpinner = findViewById(R.id.sourceSpinner)
        targetSpinner = findViewById(R.id.targetSpinner)
        speedSection = findViewById(R.id.speedSection)
        speedSpinner = findViewById(R.id.speedSpinner)
        startButton = findViewById(R.id.startButton)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)

        sourceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, Languages.spoken.map { it.name })
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        targetSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, Languages.caption.map { it.name })
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        speedSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, WhisperSpeed.entries.map { it.label })
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val savedSource = prefs.getString("source", "en")
        val savedTarget = prefs.getString("target", "en")
        sourceSpinner.setSelection(Languages.spoken.indexOfFirst { it.code == savedSource }.coerceAtLeast(0))
        targetSpinner.setSelection(Languages.caption.indexOfFirst { it.code == savedTarget }.coerceAtLeast(0))
        speedSpinner.setSelection(WhisperSpeed.entries.indexOf(WhisperSpeed.fromKey(prefs.getString("speed", null))))

        // Automatic detection is the default wherever this phone can run it.
        if (WhisperLib.available) {
            val auto = prefs.getString("mode", CaptionService.MODE_AUTO) == CaptionService.MODE_AUTO
            modeGroup.check(if (auto) R.id.modeAuto else R.id.modeLive)
        } else {
            modeAuto.isEnabled = false
            autoUnavailable.visibility = View.VISIBLE
            modeGroup.check(R.id.modeLive)
        }
        showModeSections()

        val save = {
            prefs.edit()
                .putString("mode", if (isAutoMode()) CaptionService.MODE_AUTO else CaptionService.MODE_LIVE)
                .putString("source", selectedSpoken().code)
                .putString("target", selectedCaption().code)
                .putString("speed", selectedSpeed().key)
                .apply()
            if (!busy && !CaptionService.isRunning) setStatus(describeChoice())
        }
        modeGroup.setOnCheckedChangeListener { _, _ ->
            showModeSections()
            save()
        }
        val onChange = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = save()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        sourceSpinner.onItemSelectedListener = onChange
        targetSpinner.onItemSelectedListener = onChange
        speedSpinner.onItemSelectedListener = onChange

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

    // ---- Start-up: overlay permission → sound permission → downloads → share sound → start ----

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
        when {
            missing.isNotEmpty() -> permissionLauncher.launch(missing.toTypedArray())
            isAutoMode() -> prepareAuto()
            else -> prepareSpeechModel()
        }
    }

    /** Auto mode: the Whisper model(s) for this phone plus the speech detector. */
    private fun prepareAuto() {
        val plan = WhisperModels.plan(this, selectedSpeed())
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        setStatus("Getting automatic detection ready…")
        io.execute {
            try {
                val total = plan.files.size
                plan.files.forEachIndexed { index, item ->
                    if (!WhisperModels.isDownloaded(this, item)) {
                        WhisperModels.ensure(this, item) { percent ->
                            runOnUiThread {
                                progress.isIndeterminate = false
                                progress.progress = percent
                                setStatus("Downloading the ${item.label}, ${index + 1} of $total (first time only)… $percent%")
                            }
                        }
                    }
                }
                val launch = Launch(
                    auto = true,
                    whisperModel = WhisperModels.file(this, plan.start).absolutePath,
                    whisperFallback = plan.fallback?.let { WhisperModels.file(this, it).absolutePath },
                    vadModel = WhisperModels.file(this, WhisperModels.speechDetector).absolutePath,
                )
                runOnUiThread { requestSoundSharing(launch) }
            } catch (e: Exception) {
                runOnUiThread {
                    fail("Couldn't download the automatic detection files: ${e.message}. Check your internet connection and tap Start captions again.")
                }
            }
        }
    }

    /** Live mode: the Vosk model for the chosen language, then the translation files. */
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
        val launch = Launch(auto = false, voskModel = modelPath)
        if (spoken.code == caption.code) {
            requestSoundSharing(launch)
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
                requestSoundSharing(launch)
            }
            .addOnFailureListener { e ->
                translator.close()
                fail("Couldn't download the translation files: ${e.message}. Check your internet connection and tap Start captions again.")
            }
    }

    private fun requestSoundSharing(launch: Launch) {
        pendingLaunch = launch
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

    private fun startCaptionService(resultCode: Int, data: Intent, launch: Launch) {
        val intent = Intent(this, CaptionService::class.java).apply {
            action = CaptionService.ACTION_START
            putExtra(CaptionService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptionService.EXTRA_RESULT_DATA, data)
            putExtra(CaptionService.EXTRA_TARGET, selectedCaption().code)
            if (launch.auto) {
                putExtra(CaptionService.EXTRA_MODE, CaptionService.MODE_AUTO)
                putExtra(CaptionService.EXTRA_WHISPER_MODEL, launch.whisperModel)
                putExtra(CaptionService.EXTRA_WHISPER_FALLBACK, launch.whisperFallback)
                putExtra(CaptionService.EXTRA_VAD_PATH, launch.vadModel)
            } else {
                putExtra(CaptionService.EXTRA_MODE, CaptionService.MODE_LIVE)
                putExtra(CaptionService.EXTRA_MODEL_PATH, launch.voskModel)
                putExtra(CaptionService.EXTRA_SOURCE, selectedSpoken().code)
            }
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

    private fun isAutoMode() = WhisperLib.available && modeGroup.checkedRadioButtonId == R.id.modeAuto

    private fun selectedSpoken() = Languages.spoken[sourceSpinner.selectedItemPosition.coerceAtLeast(0)]

    private fun selectedCaption() = Languages.caption[targetSpinner.selectedItemPosition.coerceAtLeast(0)]

    private fun selectedSpeed() = WhisperSpeed.entries[speedSpinner.selectedItemPosition.coerceAtLeast(0)]

    private fun showModeSections() {
        val auto = isAutoMode()
        sourceSpinner.visibility = if (auto) View.GONE else View.VISIBLE
        speedSection.visibility = if (auto) View.VISIBLE else View.GONE
    }

    private fun describeChoice(): String {
        val caption = selectedCaption()
        if (isAutoMode()) {
            return if (caption.code == "en") {
                "Detects the language of each video and shows the words in English."
            } else {
                "Detects the language of each video and translates it into ${caption.name}."
            }
        }
        val spoken = selectedSpoken()
        return if (spoken.code == caption.code) {
            "Shows ${spoken.name} speech as ${spoken.name} text. Pick another language under “Show the words in” to translate."
        } else {
            "Translates ${spoken.name} speech into ${caption.name}, word by word."
        }
    }

    private fun runningMessage() =
        "Captions are on. Play a TikTok and the words appear in the floating box. Drag the box to move it, or tap ✕ to stop."

    private fun refreshUi() {
        val running = CaptionService.isRunning
        val editable = !busy && !running
        startButton.text = if (running) "Stop captions" else "Start captions"
        startButton.isEnabled = !busy
        modeAuto.isEnabled = editable && WhisperLib.available
        modeLive.isEnabled = editable
        sourceSpinner.isEnabled = editable
        targetSpinner.isEnabled = editable
        speedSpinner.isEnabled = editable
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
