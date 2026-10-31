# Clip Captions

An Android app that shows what people say in videos as text, translated into your language, in a floating box over TikTok (or any other app).

It reads the sound the phone is playing, the same audio that goes to the speaker or to AirPods, so it works with headphones in. Everything runs on the phone, and nothing is recorded or uploaded.

There are two ways to caption:

- **Detect it automatically** (default). Whisper (OpenAI's free, open-source speech model, via whisper.cpp) works out the language of each video by itself, from about 99 languages. Captions appear in short bursts a few seconds after the speech.
  - **Accuracy setting:** Fast (31 MB), Better (57 MB) or Best (181 MB). On Automatic, the app times each model once on the phone and uses the most accurate one that keeps captions quick. If captions start falling behind, it steps down to a smaller one by itself.
  - **Speed:** the model loads while Android's sharing question is on screen. Whisper only processes about 15 seconds of its 30-second window (our speech pieces are at most 6 seconds), which roughly halves the work. Speech is sent after about 1.6 seconds instead of waiting longer.
  - **Faster engine on newer phones:** 64-bit phones with ARMv8.2 dot-product instructions (most phones from about 2018 onwards) use a build of Whisper that's roughly twice as fast. Other phones use the standard build.
  - **One language per video:** as soon as Whisper is confident of the language (or two pieces of speech in a row agree), it's told that language for the rest of the video instead of detecting it each time. Detecting costs about twice as much as writing the words down.
  - **Translation:** Whisper writes the words in their own language and Google ML Kit translates them, with the original shown underneath. For languages ML Kit doesn't cover, Whisper translates into English itself.
  - A small speech detector skips music and noise, speech is cut into pieces at natural pauses so words aren't split, and phrases Whisper tends to invent over music ("Thanks for watching") are filtered out.
- **Choose the language yourself.** Vosk writes the words as they're spoken, word by word, for 17 languages (30–90 MB download per language). This is also the fallback on the few phones that can't run automatic detection.

Translation uses Google ML Kit on the phone (about 30 MB per language, downloaded the first time it's needed). After the first downloads, captions work offline.

**Swiping to the next video clears the captions** (TikTok, Instagram Reels, YouTube Shorts and similar). The app notices when a different video starts playing, or a video starts straight after you touch the screen. Pausing a video doesn't clear them, so you can stop to read.

Needs Android 10 or newer.

## Get the app onto your phone

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
4. Open the finished run and download **ClipCaptions-apk** under Artifacts. Unzip it to get `app-debug.apk`, then install it on the phone as in step 5 above.

If the Actions tab shows nothing, the `.github` folder didn't upload: choose **Add file > Create new file**, name it `.github/workflows/build-apk.yml`, and paste in the contents of that file from this folder.

### Updating to a new version

Every build is signed with the same key (`keystore/debug.keystore`), so a new APK installs over the old one and keeps your settings and downloaded languages.

Builds made before this key was added were each signed with a different random key. If installing says **App not installed**, uninstall the old Clip Captions once, then install the new one. After that, updates install normally.

This key is only for test builds. Publishing on Google Play needs a separate private key that isn't stored in the repository.

## Using it

1. Open Clip Captions. Choose **Detect it automatically** (or pick the language yourself) and the language you want to read. Leave **Accuracy** on Automatic unless captions lag (choose Fast) or you want to try a bigger model (Better or Best).
2. Tap **Start captions**. The first time, allow **Display over other apps** and the sound permission (Android lists it as Microphone; the app doesn't use the microphone), then wait for the download.
3. Android asks what to share. Choose **A single app** and pick the app you'll watch (for example TikTok). Your notifications stay visible, because Android hides notification content only while the whole screen is shared. Only the sound is used and nothing is saved, but Android shows its screen-sharing indicator while captions are on.
4. TikTok opens. Play a video. Drag the caption box up or down; tap ✕ (or **Stop** in the notification) to turn captions off.

## Good to know

- Clear speech works best. Loud music under the voice, heavy slang and very fast talking reduce accuracy.
- The first time a new language turns up, its words show untranslated for a few seconds while its translation downloads.
- Choosing the language yourself uses a different, word-by-word engine (Vosk): it's faster but less accurate than automatic detection.
- If the box says no sound is coming through while a video is playing, that app is blocking sound capture on your phone.
- Google Play Protect may warn that the app is from an unknown developer, because you built it yourself. Choose **Install anyway**.
