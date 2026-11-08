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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        /** A Whisper language code (such as "es") to add to the languages you watch, from the caption box. */
        const val EXTRA_ADD_LANGUAGE = "addLanguage"
    }

    // A language to add once the phone's languages are known, and whether to open the language list then.
    private var pendingAdd: String? = null
    private var pendingChoose = false

    /** Everything the caption service needs once Android has agreed to share the sound. */
    private data class Launch(
        val auto: Boolean,
        val google: Boolean = false,
        val googleLanguage: String? = null,
        val googleSwitch: List<String> = emptyList(),
        // Languages you watch (translation codes), so their translators can be warmed up before the first video.
        val watched: List<String> = emptyList(),
        val voskModel: String? = null,
        val whisperModels: List<String> = emptyList(),
        val vadModel: String? = null,
        val automaticSpeed: Boolean = true,
        // Whisper's small model, for spotting each video's language quickly.
        val spotterModel: String? = null,
    ) {
        fun whisperRequest() = WhisperEngine.Request(whisperModels, vadModel, automaticSpeed)
    }

    private lateinit var modeGroup: RadioGroup
    private lateinit var modeGoogle: RadioButton
    private lateinit var modeAuto: RadioButton
    private lateinit var modeLive: RadioButton
    private lateinit var autoUnavailable: TextView
    private lateinit var sourceSpinner: Spinner
    private lateinit var sourceLabel: TextView
    private lateinit var targetSpinner: Spinner
    private lateinit var speedSection: View
    private lateinit var speedSpinner: Spinner
    private lateinit var googleStatus: TextView
    private lateinit var googleRecheck: Button
    private lateinit var watchSection: View
    private lateinit var watchLanguages: TextView
    private lateinit var watchChoose: Button
    private lateinit var watchNote: TextView
    private lateinit var sizeGroup: MaterialButtonToggleGroup
    private lateinit var watchChips: ChipGroup
    private lateinit var startButton: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var speedText: TextView
    private lateinit var phrasesCount: TextView

    private val io = Executors.newSingleThreadExecutor()
    private var busy = false
    private var pendingLaunch: Launch? = null
    private var googleSupport: GoogleSpeech.Support? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasAudioPermission()) {
            when {
                isGoogleMode() -> prepareGoogle()
                isAutoMode() -> prepareAuto()
                else -> prepareSpeechModel()
            }
        } else {
            fail(
                "Clip Captions needs the sound permission (Android calls it Microphone). " +
                    "Only your phone's own sound is used, never the microphone.",
            )
        }
    }

    private val setupLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Straight after the guide: choose the languages you watch, so captions don't start out
        // listening for the wrong one.
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (prefs.getString("google_languages", null) == null && prefs.getString("auto_languages", null) == null) {
            openLanguageList()
        }
    }

    /** Opens the language list for the chosen way of listening (once Instant has been checked). */
    private fun openLanguageList() {
        when {
            googleSupport == null -> pendingChoose = true
            isGoogleMode() -> chooseGoogleLanguages()
            isAutoMode() -> chooseAutoLanguages()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeAddRequest(intent)
    }

    /** "Tap to add Spanish" in the caption box opens the app with that language. */
    private fun takeAddRequest(intent: Intent?) {
        val code = intent?.getStringExtra(EXTRA_ADD_LANGUAGE) ?: return
        intent.removeExtra(EXTRA_ADD_LANGUAGE)
        if (googleSupport == null) pendingAdd = code else addWatchedLanguage(code)
    }

    /** Adds a language (a Whisper code) to the languages Instant listens for, and downloads it. */
    private fun addWatchedLanguage(code: String) {
        val name = Languages.nameFor(code)
        val support = googleSupport as? GoogleSpeech.Support.Ready
        if (support == null) {
            setStatus("Instant captions aren't available on this phone, so $name can't be added. Try Any language instead.")
            return
        }
        val matches = googleCatalogue(support).filter { LanguagePick.whisperCode(GoogleSpeech.codeOf(it)) == code }
        if (matches.isEmpty()) {
            setStatus("Instant captions can't do $name on this phone. Any language can: choose it under How to listen.")
            return
        }
        // Prefer a version already on the phone, then one for the phone's own country.
        val country = java.util.Locale.getDefault().country
        val tag = matches.firstOrNull { it in support.installed }
            ?: matches.firstOrNull { java.util.Locale.forLanguageTag(it).country == country }
            ?: matches.first()
        val chosen = (chosenGoogleLanguages() + tag).distinct()
        saveChosenGoogleLanguages(chosen)
        modeGroup.check(R.id.modeGoogle)
        showGoogleLanguages()
        val running = CaptionService.isRunning
        busy = true
        refreshUi()
        downloadGoogleLanguages(chosen) {
            val finish = {
                busy = false
                progress.visibility = View.GONE
                refreshUi()
                setStatus(
                    if (running) {
                        "$name is added. Stop captions and start them again to use it."
                    } else {
                        "$name is added. Tap Start captions."
                    },
                )
            }
            if (WhisperLib.available) downloadSpotter { finish() } else finish()
        }
    }

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val launch = pendingLaunch
        if (result.resultCode != Activity.RESULT_OK || data == null || launch == null) {
            if (launch?.auto == true) WhisperEngine.discardPreload()
            fail("Sharing was cancelled, so captions didn't start. Tap Start captions to try again.")
            return@registerForActivityResult
        }
        busy = false
        startCaptionService(result.resultCode, data, launch)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // The dark header runs up behind the status bar, so the status bar icons are always light.
        val root = findViewById<View>(R.id.root)
        val hero = findViewById<View>(R.id.hero)
        val heroTop = hero.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, 0, bars.right, bars.bottom)
            hero.setPadding(hero.paddingLeft, bars.top + heroTop, hero.paddingRight, hero.paddingBottom)
            insets
        }
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        // The sample video's glow stays inside its rounded corners.
        (findViewById<View>(R.id.heroClip)).clipToOutline = true

        modeGroup = findViewById(R.id.modeGroup)
        modeGoogle = findViewById(R.id.modeGoogle)
        modeAuto = findViewById(R.id.modeAuto)
        modeLive = findViewById(R.id.modeLive)
        autoUnavailable = findViewById(R.id.autoUnavailable)
        sourceSpinner = findViewById(R.id.sourceSpinner)
        sourceLabel = findViewById(R.id.sourceLabel)
        targetSpinner = findViewById(R.id.targetSpinner)
        speedSection = findViewById(R.id.speedSection)
        speedSpinner = findViewById(R.id.speedSpinner)
        googleStatus = findViewById(R.id.googleStatus)
        googleRecheck = findViewById(R.id.googleRecheck)
        watchSection = findViewById(R.id.watchSection)
        watchLanguages = findViewById(R.id.watchLanguages)
        watchChoose = findViewById(R.id.watchChoose)
        watchNote = findViewById(R.id.watchNote)
        watchChoose.setOnClickListener { if (isGoogleMode()) chooseGoogleLanguages() else chooseAutoLanguages() }
        findViewById<TextView>(R.id.versionText).text = getString(R.string.version_label, appVersion())
        sizeGroup = findViewById(R.id.sizeGroup)
        watchChips = findViewById(R.id.watchChips)
        startButton = findViewById(R.id.startButton)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        speedText = findViewById(R.id.speedText)
        phrasesCount = findViewById(R.id.phrasesCount)
        findViewById<View>(R.id.phrasesRow).setOnClickListener { startActivity(Intent(this, PhrasesActivity::class.java)) }
        modeGoogle.text = optionText(getString(R.string.mode_google_title), getString(R.string.mode_google_desc), getString(R.string.recommended))
        modeAuto.text = optionText(getString(R.string.mode_auto_title), getString(R.string.mode_auto_desc), null)
        modeLive.text = optionText(getString(R.string.mode_live_title), getString(R.string.mode_live_desc), null)
        findViewById<Button>(R.id.setupButton).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        findViewById<Button>(R.id.reportButton).setOnClickListener { sendReport() }

        sourceSpinner.adapter = ArrayAdapter(this, R.layout.item_spinner, Languages.spoken.map { it.name })
            .also { it.setDropDownViewResource(R.layout.item_spinner_dropdown) }
        targetSpinner.adapter = ArrayAdapter(this, R.layout.item_spinner, Languages.caption.map { it.name })
            .also { it.setDropDownViewResource(R.layout.item_spinner_dropdown) }
        speedSpinner.adapter = ArrayAdapter(this, R.layout.item_spinner, WhisperSpeed.entries.map { it.label })
            .also { it.setDropDownViewResource(R.layout.item_spinner_dropdown) }

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val savedSource = prefs.getString("source", "en")
        val savedTarget = prefs.getString("target", "en")
        sourceSpinner.setSelection(Languages.spoken.indexOfFirst { it.code == savedSource }.coerceAtLeast(0))
        targetSpinner.setSelection(Languages.caption.indexOfFirst { it.code == savedTarget }.coerceAtLeast(0))
        speedSpinner.setSelection(WhisperSpeed.entries.indexOf(WhisperSpeed.fromKey(prefs.getString("speed", null))))
        sizeGroup.check(sizeButtonFor(CaptionSize.fromKey(prefs.getString("caption_size", null))))

        // Google is the default once the phone is found to support it (checked just below).
        val savedMode = prefs.getString("mode", null)
        if (!WhisperLib.available) {
            modeAuto.isEnabled = false
            autoUnavailable.visibility = View.VISIBLE
        }
        modeGoogle.isEnabled = false
        modeGroup.check(
            when {
                savedMode == CaptionService.MODE_LIVE || !WhisperLib.available -> R.id.modeLive
                else -> R.id.modeAuto
            },
        )
        showModeSections()
        checkGoogle(preferGoogle = savedMode == null || savedMode == CaptionService.MODE_GOOGLE)
        googleRecheck.setOnClickListener {
            GoogleSpeech.forgetAppSoundCheck(this)
            checkGoogle(preferGoogle = true)
        }

        val save = {
            // A new accuracy choice means Automatic should time the models again.
            val previousSpeed = prefs.getString("speed", null)
            if (previousSpeed != null && previousSpeed != selectedSpeed().key) WhisperEngine.forgetSpeedTests(this)
            val mode = when {
                isGoogleMode() -> CaptionService.MODE_GOOGLE
                isAutoMode() -> CaptionService.MODE_AUTO
                else -> CaptionService.MODE_LIVE
            }
            prefs.edit()
                .putString("mode", mode)
                .putString("caption_size", selectedSize().key)
                .putString("source", selectedSpoken().code)
                .putString("target", selectedCaption().code)
                .putString("speed", selectedSpeed().key)
                .apply()
            if (!busy && !CaptionService.isRunning) setStatus(describeChoice())
            if (isGoogleMode()) showGoogleLanguages()
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
        sizeGroup.addOnButtonCheckedListener { _, _, isChecked -> if (isChecked) save() }

        startButton.setOnClickListener {
            if (CaptionService.isRunning) stopCaptions() else startCaptions()
        }

        // The first time: a short guide to what it does and what Android will ask.
        if (savedInstanceState == null && !SetupActivity.isDone(this)) {
            setupLauncher.launch(Intent(this, SetupActivity::class.java))
        }
        if (savedInstanceState == null) takeAddRequest(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        if (!busy) setStatus(if (CaptionService.isRunning) runningMessage() else describeChoice())
        if (Diagnostics.takeStoppedForBattery(this)) {
            CaptionService.isRunning = false
            refreshUi()
            setStatus(
                "Captions turned off after 20 minutes with nothing playing, to save battery. " +
                    "Tap Start captions to turn them on again.",
            )
        }
        showSpeed()
        val saved = PhraseBook.count(this)
        phrasesCount.text = if (saved == 0) "" else saved.toString()
    }

    /** An option card's text: its name in bold (with an optional badge), then a line saying what it does. */
    private fun optionText(title: String, description: String, badge: String?): CharSequence {
        val text = SpannableStringBuilder()
        val start = text.length
        text.append(title)
        text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(ForegroundColorSpan(getColor(R.color.cc_ink)), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (badge != null) {
            text.append("  ")
            val b = text.length
            text.append(" $badge ")
            text.setSpan(PillSpan(getColor(R.color.cc_amber), getColor(R.color.cc_night)), b, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text.append("\n")
        val d = text.length
        text.append(description)
        text.setSpan(ForegroundColorSpan(getColor(R.color.cc_muted)), d, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(AbsoluteSizeSpan(14, true), d, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text
    }

    private fun sizeButtonFor(size: CaptionSize) = when (size) {
        CaptionSize.SMALL -> R.id.sizeSmall
        CaptionSize.MEDIUM -> R.id.sizeMedium
        CaptionSize.LARGE -> R.id.sizeLarge
    }

    private fun selectedSize(): CaptionSize = when (sizeGroup.checkedButtonId) {
        R.id.sizeSmall -> CaptionSize.SMALL
        R.id.sizeLarge -> CaptionSize.LARGE
        else -> CaptionSize.MEDIUM
    }

    /** The languages you watch, as chips. */
    private fun showWatchChips(names: List<String>) {
        watchChips.removeAllViews()
        for (name in names) {
            val chip = Chip(this).apply {
                text = name
                isClickable = false
                isCheckable = false
                typeface = resources.getFont(R.font.atkinson)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(R.color.cc_signal))
                chipBackgroundColor = ColorStateList.valueOf(getColor(R.color.cc_tint))
                chipStrokeWidth = 0f
            }
            watchChips.addView(chip)
        }
        watchChips.visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Opens the share sheet with a problem report: a space to describe it, then details about the phone and app. */
    private fun sendReport() {
        val mode = when {
            isGoogleMode() -> "Instant"
            isAutoMode() -> "Any language (accuracy: ${selectedSpeed().label})"
            else -> "Basic (${selectedSpoken().name})"
        }
        val settings = mutableListOf(
            "Mode: $mode",
            "Translate into: ${selectedCaption().name}",
            "Languages I watch: ${watchedNames.ifEmpty { "-" }}",
            "Instant captions: ${googleStatus.text}",
        )
        if (speedText.visibility == View.VISIBLE) settings += speedText.text.toString()
        val report = Diagnostics.report(this, settings)
        android.app.AlertDialog.Builder(this)
            .setTitle("Report a problem")
            .setMessage(
                "Your report opens on Clip Captions' GitHub page, filled in and ready to post. Add what " +
                    "happened, then post it. Reports there are public, and posting needs a free GitHub " +
                    "account. It holds your phone model, settings and any recent errors, nothing personal.",
            )
            .setPositiveButton("Open GitHub") { _, _ -> openIssue(report) }
            .setNeutralButton("Share instead") { _, _ -> shareReport(report) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openIssue(report: String) {
        // Long crash details would make the web address too long for some browsers: keep the start.
        val body = if (report.length > 5000) report.take(5000) + "\n…" else report
        val url = Uri.parse(Diagnostics.ISSUES_URL).buildUpon()
            .appendQueryParameter("title", "Problem: ")
            .appendQueryParameter("body", body)
            .build()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url))
        } catch (e: android.content.ActivityNotFoundException) {
            shareReport(report)
        }
    }

    private fun shareReport(report: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Clip Captions problem report")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(send, "Send the report"))
    }

    /** How quick captions were last time, measured on this phone, so delays can be pinned down. */
    private fun showSpeed() {
        val prefs = getSharedPreferences(CaptionService.SPEED_PREFS, MODE_PRIVATE)
        val words = prefs.getLong("words_ms", -1L)
        if (CaptionService.isRunning || words < 0) {
            speedText.visibility = View.GONE
            return
        }
        val translation = prefs.getLong("translation_ms", -1L)
        fun seconds(ms: Long) = String.format(java.util.Locale.UK, "%.1f s", ms / 1000.0)
        val text = StringBuilder("Speed last time: words appeared ${seconds(words)} after people spoke")
        if (translation >= 0) text.append(", translations ${seconds(translation)} after that")
        text.append('.')
        speedText.text = text
        speedText.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ---- Start-up: overlay permission → sound permission → downloads → share sound → start ----

    private fun startCaptions() {
        if (!Settings.canDrawOverlays(this)) {
            setStatus("Turn on “Display over other apps” for Clip Captions, then come back and tap Start captions.")
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
            isGoogleMode() -> prepareGoogle()
            isAutoMode() -> prepareAuto()
            else -> prepareSpeechModel()
        }
    }

    // ---- Google mode ----

    /** Finds out whether this phone has Google's on-phone recogniser, and lists its languages. */
    private fun checkGoogle(preferGoogle: Boolean) {
        googleStatus.text = getString(R.string.google_checking)
        googleRecheck.visibility = View.GONE
        GoogleSpeech.checkSupport(this) { support ->
            googleSupport = support
            when (support) {
                is GoogleSpeech.Support.Unavailable -> {
                    modeGoogle.isEnabled = false
                    googleStatus.text = "Instant captions aren't available on this phone. ${support.reason}"
                    googleRecheck.visibility = View.VISIBLE
                    if (modeGroup.checkedRadioButtonId == R.id.modeGoogle) {
                        modeGroup.check(if (WhisperLib.available) R.id.modeAuto else R.id.modeLive)
                    }
                }
                is GoogleSpeech.Support.Ready -> {
                    modeGoogle.isEnabled = !busy && !CaptionService.isRunning
                    googleStatus.text = "Instant captions work on this phone."
                    if (preferGoogle) modeGroup.check(R.id.modeGoogle)
                }
            }
            showModeSections()
            styleOptions()
            if (!busy && !CaptionService.isRunning) setStatus(describeChoice())
            pendingAdd?.let {
                pendingAdd = null
                addWatchedLanguage(it)
            }
            if (pendingChoose) {
                pendingChoose = false
                openLanguageList()
            }
        }
    }

    /** Every language Google can recognise on this phone, downloaded ones first. */
    private fun googleCatalogue(support: GoogleSpeech.Support.Ready): List<String> {
        val all = (support.installed.sortedBy { GoogleSpeech.displayName(it) } +
            support.downloadable.sortedBy { GoogleSpeech.displayName(it) }).distinct()
        // The phone didn't list its languages: offer the phone's own language.
        return all.ifEmpty { listOf(java.util.Locale.getDefault().toLanguageTag()) }
    }

    /** The languages you watch, as saved; the first is the one Google starts listening for. */
    private fun chosenGoogleLanguages(): List<String> {
        val support = googleSupport as? GoogleSpeech.Support.Ready ?: return emptyList()
        val catalogue = googleCatalogue(support)
        val saved = getSharedPreferences("settings", MODE_PRIVATE).getString("google_languages", null)
            ?.split(",")?.filter { it in catalogue }.orEmpty()
        if (saved.isNotEmpty()) return saved
        // Nothing chosen yet: start with the phone's own language.
        val own = java.util.Locale.getDefault()
        return listOfNotNull(
            catalogue.firstOrNull { it == own.toLanguageTag() }
                ?: catalogue.firstOrNull { GoogleSpeech.codeOf(it) == own.language }
                ?: catalogue.firstOrNull(),
        )
    }

    private fun saveChosenGoogleLanguages(tags: List<String>) {
        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("google_languages", tags.joinToString(",")).apply()
    }

    private fun showGoogleLanguages() {
        val chosen = chosenGoogleLanguages()
        showWatchChips(chosen.map { GoogleSpeech.displayName(it) })
        watchedNames = chosen.joinToString(", ") { GoogleSpeech.displayName(it) }
        val target = selectedCaption().code
        val onlyTarget = chosen.isNotEmpty() && chosen.all { GoogleSpeech.codeOf(it) == target }
        setWatchNote(
            warning = onlyTarget,
            note = when {
                chosen.isEmpty() -> "None yet. Tap Choose languages."
                onlyTarget ->
                    "Only ${selectedCaption().name} is ticked, so videos in other languages come out as nonsense. " +
                        "Tap Change and tick the languages your videos are in."
                chosen.size == 1 || WhisperLib.available || Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> null
                else -> "Switching between them needs Android 14, so it listens for ${GoogleSpeech.displayName(chosen.first())}."
            },
        )
    }

    private var watchedNames = ""

    /** A short note under the language chips, or nothing. */
    private fun setWatchNote(note: String?, warning: Boolean = false) {
        watchLanguages.text = note ?: ""
        watchLanguages.visibility = if (note == null) View.GONE else View.VISIBLE
        watchLanguages.setTextColor(getColor(if (warning) R.color.cc_warn else R.color.cc_muted))
        watchLanguages.setTypeface(resources.getFont(R.font.atkinson), if (warning) Typeface.BOLD else Typeface.NORMAL)
    }

    /** Lets you tick every language you watch, then downloads them once. */
    private fun chooseGoogleLanguages() {
        val support = googleSupport as? GoogleSpeech.Support.Ready ?: return
        val catalogue = googleCatalogue(support)
        val chosen = chosenGoogleLanguages().toMutableList()
        val labels = catalogue.map { tag ->
            val name = GoogleSpeech.displayName(tag)
            if (tag in support.installed || GoogleSpeech.alreadyFetched(this, tag)) name else "$name (download)"
        }.toTypedArray()
        val checked = BooleanArray(catalogue.size) { catalogue[it] in chosen }
        android.app.AlertDialog.Builder(this)
            .setTitle("Languages I watch")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val tag = catalogue[which]
                if (isChecked) {
                    if (tag !in chosen) chosen += tag
                } else {
                    chosen -= tag
                }
            }
            .setPositiveButton("Done") { _, _ ->
                if (chosen.isEmpty()) return@setPositiveButton
                saveChosenGoogleLanguages(chosen)
                showGoogleLanguages()
                // Download everything now, so captions never wait for it while you're scrolling.
                busy = true
                refreshUi()
                downloadGoogleLanguages(chosen) {
                    val finish = {
                        busy = false
                        progress.visibility = View.GONE
                        refreshUi()
                    }
                    if (WhisperLib.available) {
                        // Keep the languages' download result on screen once the spotter is done.
                        val result = status.text.toString()
                        downloadSpotter {
                            finish()
                            if (WhisperModels.isDownloaded(this, WhisperModels.fast)) setStatus(result)
                        }
                    } else {
                        finish()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Downloads, once, Google's speech pack for each language and its translation pack. Anything
     * already on the phone is skipped, so this is instant after the first time.
     */
    private fun downloadGoogleLanguages(tags: List<String>, done: () -> Unit) {
        val support = googleSupport as? GoogleSpeech.Support.Ready
        val toFetch = tags.filter { support == null || it !in support.installed }
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        val problems = ArrayList<String>()
        GoogleSpeech.downloadAll(this, toFetch, { tag, index, total ->
            setStatus("Downloading ${GoogleSpeech.displayName(tag)}, ${index + 1} of $total (first time only)…")
        }) { failedSpeech ->
            problems += failedSpeech.map { GoogleSpeech.displayName(it) }
            downloadTranslations(tags.map { GoogleSpeech.codeOf(it) }) { failedTranslation ->
                problems += failedTranslation.map { "${Languages.nameFor(it)} translation" }
                setStatus(
                    if (problems.isEmpty()) {
                        "Your languages are on the phone. Tap Start captions."
                    } else {
                        "Downloaded, except: ${problems.joinToString(", ")}. Check your connection and choose the languages again to retry."
                    },
                )
                done()
            }
        }
    }

    /**
     * Downloads, once, the translation packs for [codes] and for the caption language. Packs already
     * on the phone are skipped, so this is instant after the first time. [done] gets the ones that failed.
     */
    private fun downloadTranslations(codes: List<String>, done: (List<String>) -> Unit) {
        val target = selectedCaption().code
        val wanted = (codes + target).distinct()
        // Nothing to translate if every language you watch is the one you read.
        if (wanted.all { it == target }) {
            done(emptyList())
            return
        }
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        // Mozilla's translator first (much better), for every language it covers. ML Kit's packs only
        // for the rest, or if Mozilla's download fails.
        val sources = codes.distinct().filter { it != target }
        val mozilla = sources.filter { MozModels.covers(it, target) }
        io.execute {
            val failed = ArrayList<String>()
            val total = mozilla.sumOf { MozModels.missing(this, MozModels.route(it, target).orEmpty()) }
            var finished = 0
            for (code in mozilla) {
                val legs = MozModels.route(code, target).orEmpty()
                val count = MozModels.missing(this, legs)
                val before = finished
                try {
                    MozModels.download(this, legs) { index, _, percent ->
                        runOnUiThread {
                            progress.isIndeterminate = false
                            progress.progress = percent
                            setStatus(
                                "Downloading the ${Languages.nameFor(code)} translation, ${before + index + 1} of $total " +
                                    "(first time only)… $percent%",
                            )
                        }
                    }
                } catch (e: Exception) {
                    failed += code
                }
                finished += count
            }
            runOnUiThread {
                val packs = (sources - mozilla.toSet() + failed).distinct()
                if (packs.isEmpty()) {
                    done(emptyList())
                    return@runOnUiThread
                }
                progress.isIndeterminate = true
                TranslationPacks.download(this, packs + target, { code, index, count ->
                    setStatus("Downloading the ${Languages.nameFor(code)} translation, ${index + 1} of $count (first time only)…")
                }, done)
            }
        }
    }

    /**
     * Loads Mozilla's translations for the languages you watch while Android's sharing question is on
     * screen, so the first caption is translated at full speed.
     */
    private fun preloadTranslations(watched: List<String>) {
        val target = selectedCaption().code
        val legs = watched.distinct().mapNotNull { MozModels.readyRoute(this, it, target) }.flatten()
        MozEngines.preload(this, legs)
    }

    // ---- Whisper mode: the languages you watch ----

    /** Every language the phone can translate (Google ML Kit), by name. */
    private fun translatableLanguages(): List<String> =
        TranslateLanguage.getAllLanguages().sortedBy { Languages.nameFor(it) }

    /** The languages you've ticked for Whisper mode. Nothing chosen yet: the phone's own language. */
    private fun chosenAutoLanguages(): List<String> {
        val all = TranslateLanguage.getAllLanguages().toSet()
        val saved = getSharedPreferences("settings", MODE_PRIVATE).getString("auto_languages", null)
        if (saved != null) return saved.split(",").filter { it in all }
        val own = java.util.Locale.getDefault().language
        return if (own in all) listOf(own) else emptyList()
    }

    private fun showAutoLanguages() {
        val chosen = chosenAutoLanguages()
        showWatchChips(chosen.map { Languages.nameFor(it) })
        watchedNames = chosen.joinToString(", ") { Languages.nameFor(it) }
        setWatchNote(if (chosen.isEmpty()) "None yet. Any language still works." else null)
    }

    /** Lets you tick the languages you watch, then downloads their translations once. */
    private fun chooseAutoLanguages() {
        val catalogue = translatableLanguages()
        val chosen = chosenAutoLanguages().toMutableList()
        val onPhone = TranslationPacks.known(this) + MozModels.readyAppCodes(this, selectedCaption().code)
        val labels = catalogue.map { code ->
            val name = Languages.nameFor(code)
            if (code == "en" || code in onPhone) name else "$name (download)"
        }.toTypedArray()
        val checked = BooleanArray(catalogue.size) { catalogue[it] in chosen }
        android.app.AlertDialog.Builder(this)
            .setTitle("Languages I watch")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val code = catalogue[which]
                if (isChecked) {
                    if (code !in chosen) chosen += code
                } else {
                    chosen -= code
                }
            }
            .setPositiveButton("Done") { _, _ ->
                getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putString("auto_languages", chosen.joinToString(",")).apply()
                showAutoLanguages()
                busy = true
                refreshUi()
                downloadTranslations(chosen) { failed ->
                    busy = false
                    progress.visibility = View.GONE
                    refreshUi()
                    setStatus(
                        if (failed.isEmpty()) {
                            "Your languages are on the phone. Tap Start captions."
                        } else {
                            "Downloaded, except the ${failed.joinToString(", ") { Languages.nameFor(it) }} translation. " +
                                "Check your connection and choose the languages again to retry."
                        },
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Google mode: make sure your languages are downloaded (instant after the first time), then share the sound. */
    private fun prepareGoogle() {
        if (googleSupport !is GoogleSpeech.Support.Ready) {
            fail("Instant captions aren't available on this phone. Choose another option.")
            return
        }
        val chosen = chosenGoogleLanguages()
        if (chosen.isEmpty()) {
            fail("Tap Choose languages and tick the languages you watch first.")
            return
        }
        downloadGoogleLanguages(chosen) {
            if (WhisperLib.available) downloadSpotter { startGoogle(chosen) } else startGoogle(chosen)
        }
    }

    /**
     * With several languages, the app spots each video's language itself (far quicker than Google's
     * own detection), using Whisper's small model. Downloaded once, about 31 MB. If it can't be
     * downloaded, captions still start and Google detects the language instead.
     */
    private fun downloadSpotter(done: () -> Unit) {
        val item = WhisperModels.fast
        val detector = WhisperModels.speechDetector
        if (WhisperModels.isDownloaded(this, item) && WhisperModels.isDownloaded(this, detector)) {
            done()
            return
        }
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        setStatus("Downloading the language detector (31 MB, first time only)…")
        io.execute {
            val ok = try {
                WhisperModels.ensure(this, item) { percent ->
                    runOnUiThread {
                        progress.isIndeterminate = false
                        progress.progress = percent
                        setStatus("Downloading the language detector (31 MB, first time only)… $percent%")
                    }
                }
                // Under 1 MB: tells speech from music and noise, so spotting isn't fooled by them.
                WhisperModels.ensure(this, detector) {}
                true
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                if (!ok) setStatus("Couldn't download the language detector, so working out each video's language will be slower.")
                done()
            }
        }
    }

    private fun startGoogle(chosen: List<String>) {
        // Only languages that are really on the phone (or that the phone has accepted to download).
        val installed = (googleSupport as? GoogleSpeech.Support.Ready)?.installed.orEmpty()
        val usable = chosen.filter { it in installed || GoogleSpeech.alreadyFetched(this, it) }.ifEmpty { chosen }
        val primary = usable.first()
        val switchTo = if (usable.size > 1) usable else emptyList()

        // If Whisper's files are already on the phone, pass them along as a backup.
        val plan = WhisperModels.plan(this, selectedSpeed())
        val backupReady = WhisperLib.available && plan.downloads.all { WhisperModels.isDownloaded(this, it) }
        val launch = Launch(
            auto = false,
            google = true,
            googleLanguage = primary,
            googleSwitch = switchTo,
            watched = usable.map { GoogleSpeech.codeOf(it) },
            spotterModel = spotterIfDownloaded(),
            whisperModels = if (backupReady) plan.models.map { WhisperModels.file(this, it).absolutePath } else emptyList(),
            vadModel = if (backupReady) WhisperModels.file(this, WhisperModels.speechDetector).absolutePath else null,
            automaticSpeed = selectedSpeed() == WhisperSpeed.AUTOMATIC,
        )
        requestSoundSharing(launch)
    }

    /** Auto mode: the Whisper model(s) for this phone plus the speech detector. */
    private fun prepareAuto() {
        val plan = WhisperModels.plan(this, selectedSpeed())
        val watched = chosenAutoLanguages()
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        setStatus("Getting ready…")
        io.execute {
            try {
                val total = plan.downloads.size
                plan.downloads.forEachIndexed { index, item ->
                    if (!WhisperModels.isDownloaded(this, item)) {
                        WhisperModels.ensure(this, item) { percent ->
                            runOnUiThread {
                                progress.isIndeterminate = false
                                progress.progress = percent
                                setStatus("Downloading, ${index + 1} of $total (first time only)… $percent%")
                            }
                        }
                    }
                }
                val launch = Launch(
                    auto = true,
                    watched = watched,
                    spotterModel = spotterIfDownloaded(),
                    whisperModels = plan.models.map { WhisperModels.file(this, it).absolutePath },
                    vadModel = WhisperModels.file(this, WhisperModels.speechDetector).absolutePath,
                    automaticSpeed = selectedSpeed() == WhisperSpeed.AUTOMATIC,
                )
                // Load the speech model while the translations are checked and Android's sharing question is on screen.
                WhisperEngine.preload(this, launch.whisperRequest())
                runOnUiThread {
                    // The translations for your languages, before captions start (instant once they're on the phone).
                    downloadTranslations(launch.watched) { requestSoundSharing(launch) }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    fail("The download didn't finish. Check your internet connection and tap Start captions again.")
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
        setStatus("Getting ${spoken.name} ready…")
        io.execute {
            try {
                val modelDir = VoskModels.installed(this, spoken.voskModel)
                    ?: VoskModels.download(this, spoken.voskModel) { percent ->
                        runOnUiThread {
                            progress.isIndeterminate = false
                            progress.progress = percent
                            setStatus("Downloading ${spoken.name} (first time only)… $percent%")
                        }
                    }
                runOnUiThread { prepareTranslation(spoken, caption, modelDir.absolutePath) }
            } catch (e: Exception) {
                runOnUiThread {
                    fail("The ${spoken.name} download didn't finish. Check your internet connection and tap Start captions again.")
                }
            }
        }
    }

    private fun prepareTranslation(spoken: SpokenLanguage, caption: CaptionLanguage, modelPath: String) {
        val launch = Launch(auto = false, voskModel = modelPath, watched = listOf(spoken.code))
        if (spoken.code == caption.code) {
            requestSoundSharing(launch)
            return
        }
        val mlkit = TranslateLanguage.fromLanguageTag(spoken.code) != null && TranslateLanguage.fromLanguageTag(caption.code) != null
        if (!mlkit && !MozModels.covers(spoken.code, caption.code)) {
            fail("Translating ${spoken.name} into ${caption.name} isn't available.")
            return
        }
        // Instant once it's on the phone.
        downloadTranslations(listOf(spoken.code)) { failed ->
            if (failed.isEmpty()) {
                requestSoundSharing(launch)
            } else {
                fail("The translation download didn't finish. Check your internet connection and tap Start captions again.")
            }
        }
    }

    private fun requestSoundSharing(launch: Launch) {
        pendingLaunch = launch
        preloadTranslations(launch.watched)
        progress.visibility = View.GONE
        setStatus(
            "Android will now ask what to share. Choose “A single app” and pick the app you'll watch, such as " +
                "TikTok, so your notifications stay visible. Only the sound is used.",
        )
        val manager = getSystemService(MediaProjectionManager::class.java)
        // Let the user share a single app or the whole screen. Sharing one app keeps notifications visible,
        // because Android hides notification content while the whole screen is shared.
        val request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
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
            putStringArrayListExtra(CaptionService.EXTRA_WATCHED, ArrayList(launch.watched))
            launch.spotterModel?.let { putExtra(CaptionService.EXTRA_SPOTTER_MODEL, it) }
            if (WhisperModels.isDownloaded(this@MainActivity, WhisperModels.speechDetector)) {
                putExtra(
                    CaptionService.EXTRA_SPOTTER_VAD,
                    WhisperModels.file(this@MainActivity, WhisperModels.speechDetector).absolutePath,
                )
            }
            if (launch.auto) {
                putStringArrayListExtra(
                    CaptionService.EXTRA_WATCHED_WHISPER,
                    ArrayList(launch.watched.map { LanguagePick.whisperCode(it) }),
                )
            }
            if (launch.google) {
                putExtra(CaptionService.EXTRA_MODE, CaptionService.MODE_GOOGLE)
                putExtra(CaptionService.EXTRA_GOOGLE_LANGUAGE, launch.googleLanguage)
                putStringArrayListExtra(CaptionService.EXTRA_GOOGLE_SWITCH, ArrayList(launch.googleSwitch))
                if (launch.whisperModels.isNotEmpty()) {
                    putExtra(CaptionService.EXTRA_WHISPER_MODELS, launch.whisperModels.toTypedArray())
                    putExtra(CaptionService.EXTRA_VAD_PATH, launch.vadModel)
                    putExtra(CaptionService.EXTRA_AUTOMATIC_SPEED, launch.automaticSpeed)
                }
            } else if (launch.auto) {
                putExtra(CaptionService.EXTRA_MODE, CaptionService.MODE_AUTO)
                putExtra(CaptionService.EXTRA_WHISPER_MODELS, launch.whisperModels.toTypedArray())
                putExtra(CaptionService.EXTRA_VAD_PATH, launch.vadModel)
                putExtra(CaptionService.EXTRA_AUTOMATIC_SPEED, launch.automaticSpeed)
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
        // After sharing a single app, Android has already opened it. After sharing the whole screen,
        // the user is back here, so open TikTok for them.
        window.decorView.postDelayed({
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) openTikTok()
        }, 700L)
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

    private fun isGoogleMode() = modeGroup.checkedRadioButtonId == R.id.modeGoogle && googleSupport is GoogleSpeech.Support.Ready

    private fun isAutoMode() = WhisperLib.available && modeGroup.checkedRadioButtonId == R.id.modeAuto

    private fun selectedSpoken() = Languages.spoken[sourceSpinner.selectedItemPosition.coerceAtLeast(0)]

    private fun selectedCaption() = Languages.caption[targetSpinner.selectedItemPosition.coerceAtLeast(0)]

    private fun selectedSpeed() = WhisperSpeed.entries[speedSpinner.selectedItemPosition.coerceAtLeast(0)]

    private fun showModeSections() {
        val google = isGoogleMode()
        val auto = isAutoMode()
        watchSection.visibility = if (google || auto) View.VISIBLE else View.GONE
        if (google) {
            watchNote.setText(R.string.google_note)
            showGoogleLanguages()
        } else if (auto) {
            watchNote.setText(R.string.auto_note)
            showAutoLanguages()
        }
        sourceSpinner.visibility = if (google || auto) View.GONE else View.VISIBLE
        sourceLabel.visibility = sourceSpinner.visibility
        speedSection.visibility = if (auto) View.VISIBLE else View.GONE
    }

    private fun describeChoice(): String {
        val caption = selectedCaption()
        if (isGoogleMode()) {
            return "Words appear as people speak, translated into ${caption.name}."
        }
        if (isAutoMode()) {
            return "Works out each video's language and translates it into ${caption.name}."
        }
        val spoken = selectedSpoken()
        return if (spoken.code == caption.code) {
            "Shows ${spoken.name} as ${spoken.name} text. Choose another language under “Translate into” to translate it."
        } else {
            "Translates ${spoken.name} into ${caption.name} as people speak."
        }
    }

    private fun runningMessage() =
        "Captions are on. Play a video and the words appear in the floating box. Swipe and they clear. " +
            "Drag the box to move it, or tap ✕ to stop."

    private fun refreshUi() {
        val running = CaptionService.isRunning
        val editable = !busy && !running
        startButton.text = if (running) "Stop captions" else "Start captions"
        startButton.isEnabled = !busy
        // Amber to start; a quieter outlined button once captions are on.
        if (running) {
            startButton.backgroundTintList = ColorStateList.valueOf(0x26FFFFFF)
            startButton.setTextColor(0xFFFFFFFF.toInt())
        } else {
            startButton.backgroundTintList = ColorStateList.valueOf(getColor(R.color.cc_amber))
            startButton.setTextColor(getColor(R.color.cc_night))
        }
        startButton.alpha = if (busy) 0.6f else 1f
        modeGoogle.isEnabled = editable && googleSupport is GoogleSpeech.Support.Ready
        modeAuto.isEnabled = editable && WhisperLib.available
        modeLive.isEnabled = editable
        watchChoose.isEnabled = editable
        for (i in 0 until sizeGroup.childCount) sizeGroup.getChildAt(i).isEnabled = editable
        sourceSpinner.isEnabled = editable
        targetSpinner.isEnabled = editable
        speedSpinner.isEnabled = editable
        styleOptions()
    }

    /** Options that can't be chosen right now are faded. */
    private fun styleOptions() {
        for (option in listOf(modeGoogle, modeAuto, modeLive)) option.alpha = if (option.isEnabled) 1f else 0.5f
    }

    private fun spotterIfDownloaded(): String? =
        if (WhisperLib.available && WhisperModels.isDownloaded(this, WhisperModels.fast)) {
            WhisperModels.file(this, WhisperModels.fast).absolutePath
        } else {
            null
        }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun setStatus(message: String) {
        status.text = message
    }

    private fun fail(message: String) {
        Diagnostics.note(this, message)
        busy = false
        progress.visibility = View.GONE
        setStatus(message)
        refreshUi()
    }
}
