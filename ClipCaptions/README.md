# Clip Captions

An Android app that shows what people say in videos as text, translated into your language, in a floating box over TikTok (or any other app).

It reads the sound the phone is playing, the same audio that goes to the speaker or to AirPods, so it works with headphones in. Speech recognition (Vosk) and translation (Google ML Kit) both run on the phone. The first time you use a language it downloads that language's files (30–90 MB for speech, about 30 MB for translation). After that it works offline.

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
3. Open the **Actions** tab. The "Build APK" run starts on its own and takes about 5 minutes.
4. Open the finished run and download **ClipCaptions-apk** under Artifacts. Unzip it to get `app-debug.apk`, then install it on the phone as in step 5 above.

If the Actions tab shows nothing, the `.github` folder didn't upload: choose **Add file > Create new file**, name it `.github/workflows/build-apk.yml`, and paste in the contents of that file from this folder.

## Using it

1. Open Clip Captions. Pick the language spoken in the videos and the language you want to read.
2. Tap **Start captions**. The first time, allow **Display over other apps** and the sound permission (Android lists it as Microphone; the app doesn't use the microphone), then wait for the download.
3. Android asks to share or record your screen. Tap **Start**. Only the sound is used and nothing is saved, but Android shows its screen-sharing indicator while captions are on.
4. TikTok opens. Play a video. Drag the caption box up or down; tap ✕ (or **Stop** in the notification) to turn captions off.

## Good to know

- Clear speech works best. Loud music under the voice, heavy slang and very fast talking reduce accuracy.
- If the box says no sound is coming through while a video is playing, that app is blocking sound capture on your phone.
- Google Play Protect may warn that the app is from an unknown developer, because you built it yourself. Choose **Install anyway**.
