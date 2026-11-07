# Clip Captions

An Android app that shows what people say in videos as text, translated into your language, in a floating box over TikTok (or any other app).

It reads the sound the phone is playing, the same audio that goes to the speaker or to AirPods, so it works with headphones in. Everything runs on the phone, and nothing is recorded or uploaded.

There are three ways to caption, all free and all on the phone:

- **Google, word by word** (recommended). Uses the Google speech recognition built into the phone (Android 13 or newer, on phones that include Google's on-device recogniser). It's free, writes the words as they're spoken, and the sound never leaves the phone. Under **Languages I watch**, tick every language you watch: each one's speech pack and translation pack are downloaded once, straight away, so captions never wait for a download while you scroll. With more than one language ticked, the app spots each video's language itself from its first couple of seconds of speech, using Whisper's smallest model on a short window (a fraction of a second, one-off 31 MB download), and tells Google. Only the speech is used (a small speech detector leaves out music and background noise), and it decides after about 1.2 seconds when it's sure, or waits for a little more speech when it isn't. If the video isn't in the language Google was listening for, Google starts that video again in the right language. Google's own detection, used only if the spotter can't run, can take several seconds. Swiping to the next video doesn't restart the recogniser (which would cost up to a second): the box clears and the old video's leftover words are dropped, so the new video's words appear as soon as they're spoken.
  - **Speed:** words show the moment they're heard and their translation replaces them when it's ready. When someone talks without pausing, the sentence is shown as short lines that are each translated once, so translating doesn't slow down as the sentence grows. When a sentence ends, the translation already on screen is kept rather than redone, and sound heard while Google restarts between sentences is kept and passed on, so first words aren't lost.
  - **Speed readout:** after captions stop, the main screen shows how long words took to appear after people spoke, and how long the translation took after that, as measured on your phone.
  - Safety check: if a phone's recogniser can't take app sound, Android has it use the microphone instead. The app checks within a couple of seconds that the recogniser really is reading the video's sound; if not, it stops straight away, shows nothing it heard, and switches to Whisper. "Check this phone again" retries later.
- **Whisper, any language detected automatically.** Whisper (OpenAI's free, open-source speech model, via whisper.cpp) works out the language of each video by itself, from about 99 languages. Captions appear in short bursts a few seconds after the speech.
  - **Languages I watch:** tick the languages you watch and their translations download once, straight away, before captions start. A language you didn't tick still works: if you read English, Whisper translates it into English itself straight away while the better translation downloads quietly in the background; otherwise its words show as heard until then.
  - **Accuracy setting:** Fast (31 MB), Better (57 MB) or Best (181 MB). On Automatic, the app times each model once on the phone and uses the most accurate one that keeps captions quick. If captions start falling behind, it steps down to a smaller one by itself.
  - **Speed:** the model loads while Android's sharing question is on screen. Whisper only processes about 15 seconds of its 30-second window (our speech pieces are at most 6 seconds), which roughly halves the work. Speech is sent after about 1.6 seconds instead of waiting longer.
  - **Faster engine on newer phones:** 64-bit phones with ARMv8.2 dot-product instructions (most phones from about 2018 onwards) use a build of Whisper that's roughly twice as fast. Other phones use the standard build.
  - **Quick language spotting:** each video's language is spotted with the small model on a short window, so the main model only has to write the words down. Speech is sent in short pieces (at most 3.5 seconds, and 2 seconds for a video's first words), so captions come a few words at a time instead of a long wait and then a wall of text, and each piece only uses as much of Whisper's window as it needs. If you've ticked languages under **Languages I watch**, the spotter chooses between those (and the one you read), unless another language is clearly being spoken.
  - **One language per video:** as soon as Whisper is confident of the language (or two pieces of speech in a row agree), it's told that language for the rest of the video instead of detecting it each time. Detecting costs about twice as much as writing the words down.
  - **Translation:** Whisper writes the words in their own language and Google ML Kit translates them, with the original shown underneath. For languages ML Kit doesn't cover, Whisper translates into English itself.
  - A small speech detector skips music and noise, speech is cut into pieces at natural pauses so words aren't split, and phrases Whisper tends to invent over music ("Thanks for watching") are filtered out.
- **Choose the language yourself.** Vosk writes the words as they're spoken, word by word, for 17 languages (30–90 MB download per language). This is also the fallback on the few phones that can't run automatic detection.

Translation uses Google ML Kit on the phone (about 30 MB per language). Every download happens once, before captions start, never while a video is playing. Captions never stop to wait for one, and the languages you watch are loaded into memory before the first video so the first caption is translated at full speed. After the first downloads, captions work offline.

**Swiping to the next video clears the captions** and the new video's words show as soon as they're recognised (the old video's half-finished sentence is dropped, without hiding a new sentence; Whisper stops working on the old video straight away) (TikTok, Instagram Reels, YouTube Shorts and similar). The app notices when a different video starts playing, or a video starts straight after you touch the screen. Pausing a video doesn't clear them, so you can stop to read.

Needs Android 10 or newer.

## Get the app onto your phone

The version number is shown under the app's name on its main screen, so you can check which build is on the phone.

You need to build the APK once. Pick one route.

### Route A: Android Studio (Windows, Mac or Linux)

1. Install Android Studio from developer.android.com/studio.
2. Unzip this folder. In Android Studio choose **File > Open** and select the `ClipCaptions` folder.
3. Wait for the "Gradle sync" to finish (the first time takes a few minutes while it downloads what it needs). Accept any prompt to install a missing SDK.
4. Choose **Build > Build App Bundle(s) / APK(s) > Build APK(s)**. When it finishes, click **locate** in the pop-up. The file is `app/build/outputs/apk/debug/app-debug.apk`.
5. Copy `app-debug.apk` to your phone (USB, Google Drive, email to yourself), tap it, and allow your browser or file app to install unknown apps when Android asks.

Shortcut: with the phone plugged in by USB and **USB debugging** turned on (Settings > About phone > tap Build number 7 times, then Settings > System > Developer options), press the green Run button in Android Studio and it installs straight onto the phone.

### Route B: GitHub builds it for you (no software to install)

1. Create a free GitHub account and a new **private** repository.
2. Click **uploading an existing file** and drag in everything inside the unzipped `ClipCaptions` folder, including the hidden `.github` folder. Commit.
3. Open the **Actions** tab. The "Build APK" run starts on its own and takes about 15–20 minutes (most of that is compiling Whisper, twice for 64-bit phones: the standard and the faster build).
4. On your phone, open the repository's **Releases** page (`github.com/<you>/<repository>/releases/latest`, signed in to GitHub in the phone's browser) and tap **ClipCaptions-5.2.apk** under Assets. Open the downloaded file to install it. No zip, no Google Drive.

The workflow file must be at the top of the repository, in `.github/workflows/`. If the Actions tab shows nothing, choose **Add file > Create new file**, name it `.github/workflows/build-apk.yml`, and paste in the contents of that file from this folder. The workflow finds the app by itself (in a `ClipCaptions` folder or at the top) and always builds the newest copy; the run's log says which version it built. The Releases page keeps the five newest builds.

### Updating to a new version

Every build is signed with the same key (`keystore/debug.keystore`), so a new APK installs over the old one and keeps your settings and downloaded languages.

Builds made before this key was added were each signed with a different random key. If installing says **App not installed**, uninstall the old Clip Captions once, then install the new one. After that, updates install normally.

This key is only for test builds. Publishing on Google Play needs a separate private key that isn't stored in the repository.

## Using it

The app's main screen calls the three options **Instant** (Google's on-phone speech recognition, recommended), **Any language** (Whisper) and **Basic** (Vosk).

1. Open Clip Captions. Choose **Google, word by word**, **Whisper**, or **Vosk**. For Google or Whisper, tap **Choose languages** and tick every language you watch (they download once, there and then). Choose the language you want to read. Leave **Accuracy** on Automatic unless captions lag (choose Fast) or you want to try a bigger model (Better or Best).
2. Tap **Start captions**. The first time, allow **Display over other apps** and the sound permission (Android lists it as Microphone; the app doesn't use the microphone), then wait for the download.
3. Android asks what to share. Choose **A single app** and pick the app you'll watch (for example TikTok). Your notifications stay visible, because Android hides notification content only while the whole screen is shared. Only the sound is used and nothing is saved, but Android shows its screen-sharing indicator while captions are on.
4. TikTok opens. Play a video. Drag the caption box up or down; tap ✕ (or **Stop** in the notification) to turn captions off.

## Also in the app

- **First-time guide:** three short steps the first time the app opens (what it does, the two things Android asks you to allow and why, and what to choose when sharing). **Setup guide** on the main screen opens it again.
- **My phrases:** tap ☆ on the caption box to save the words on screen with their translation. **My phrases** on the main screen lists them; tap one to copy or delete it, or share them all (to notes or a flashcard app).
- **Battery saving:** after a minute with nothing playing, listening pauses and starts again the moment sound comes back. After 20 minutes with nothing playing, captions turn themselves off.
- **Report a problem:** opens the share sheet with a report to send: a space to describe what happened, then the phone model, Android version, app settings, speed readout, recent problems and the last crash (if any). Nothing is sent unless you send it. Set `Diagnostics.SUPPORT_EMAIL` to fill in the address automatically.

## Good to know

- **Caption size** (Small, Medium, Large) is in the app, and the caption box remembers where you last dragged it.
- Clear speech works best. Loud music under the voice, heavy slang and very fast talking reduce accuracy.
- A language you didn't tick under **Languages I watch** shows untranslated (or, in Whisper mode with English captions, translated by Whisper) until its translation has downloaded in the background. The caption box's label says "translation downloading" meanwhile.
- Choosing the language yourself uses a different, word-by-word engine (Vosk): it's faster but less accurate than automatic detection.
- If the box says no sound is coming through while a video is playing, that app is blocking sound capture on your phone.
- Google Play Protect may warn that the app is from an unknown developer, because you built it yourself. Choose **Install anyway**.
