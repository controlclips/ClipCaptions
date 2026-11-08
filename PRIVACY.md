# Privacy

**Short version:** Clip Captions works on your phone. It doesn't record, store or upload what you
watch, it has no accounts, adverts, analytics or tracking, and the people who make it can't see
anything you do with it.

## What it listens to

When you start captions, Android asks what to share. Clip Captions uses only the **sound** of the
app you choose (TikTok, say). That sound is turned into words and translated **on your phone**, then
thrown away. It is never saved or sent anywhere.

- **Instant** uses Android's on-device speech recogniser (made by Google), which runs on the phone.
- **Any language** (Whisper) and **Basic** (Vosk) run entirely inside the app.
- **Translation** runs inside the app (Mozilla's Firefox translator, or Google ML Kit for a few
  languages), on the phone.

Android lists the sound permission as "Microphone" because that's the permission for capturing
sound. Clip Captions doesn't use the microphone.

## What goes over the internet

Only downloads of the speech and translation files for the languages you choose, once each, before
captions start. They come from:

- this project's GitHub page (translation models), or Mozilla's servers if that copy can't be reached
- Hugging Face (Whisper's files)
- alphacephei.com (Vosk's files)
- Google, through Android (speech packs and ML Kit translation packs)

As with any download, those servers see your phone's internet address. Nothing about you or what you
watch is sent with it.

## What's kept on your phone

- Your settings and the languages you've downloaded.
- **My phrases:** the captions you save with ☆. They stay on the phone unless you share them.
- A short note of recent problems and the last crash, so a problem report can include them. It's only
  sent if you choose to send a report.

Uninstalling the app deletes all of it.

## Problem reports

**Report a problem** fills in a report: your phone model, Android version, the app's settings and
any recent errors. Nothing personal is included and nothing is sent automatically: you choose to
post it on the project's GitHub page (where reports are public) or share it another way.

## Questions

Open an issue on the project's GitHub page: https://github.com/controlclips/ClipCaptions/issues
