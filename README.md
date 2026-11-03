# vctrnr

**Speak, pause, hear yourself back.** A hands-free voice-training app for Android, built for
a Galaxy S23 Ultra with Galaxy Buds.

You talk. When you stop for a moment, vctrnr plays back what you just said through your
earbuds, then starts listening again. It works with the phone in your pocket: tap an earbud
(the normal play/pause tap) to start or stop it. Every take is saved as a WAV file.

## Install

1. Open the latest successful run under **Actions → Build APK** on GitHub and download the
   `vctrnr-apk` artifact (a zip).
2. Unzip it on the phone and open `vctrnr.apk`. Android will ask you to allow installs from
   your browser or file manager the first time.
3. Open vctrnr and allow the microphone and notifications.
4. Recommended: **Settings → Apps → vctrnr → Battery → Unrestricted**, so One UI never
   pauses it while the screen is off.

Every build is signed with the same key (`app/debug.keystore`), so a newer APK installs
over an older one as an update and your settings are kept.

## Using it

- **Start**: tap the big button, tap an earbud, or press ▶ on the lock-screen player.
  You'll hear a short rising chime.
- With the earbud mic, wait for the **soft tick**, then talk.
- **Pause**. After the pause (1 s by default) your take plays back. Wait for the tick again
  before the next take.
- **Stop**: tap again (falling chime). If you stop mid-sentence, that take is still saved,
  just not played.
- Lock the phone and put it away whenever you like. It keeps running.

### Open vs. closed

vctrnr runs from the moment you open it until you **close** it: the **Close** button in the
app, **Close** on the notification / lock-screen player, or swiping it away from Recents.
While it's open it's the phone's active media app (other audio pauses, earbud taps go to
vctrnr). Once closed, nothing of it runs and earbud taps go back to your other apps.

### Microphone: earbuds or phone

| | Earbuds (default) | Phone |
|---|---|---|
| Hands-free / walking around | ✅ | ❌ phone in front of you |
| Recording quality | Call quality: noise-suppressed, fine for pitch, less faithful for timbre/resonance | Best: no gain control or noise suppression |
| Playback quality | Full quality | Full quality |
| Delay before playback | Pause + ~1–2 s while the buds switch modes | Pause only |

Bluetooth earbuds can only send their mic audio over the **call** channel. So in earbud mode
vctrnr listens with the buds in call mode, flips them back to music mode to play your take
at full quality, then flips back to call mode and ticks when it's listening again. If the
earbud mic isn't available, it falls back to the phone mic and tells you.

**Heads-up:** while the buds are in call mode (i.e. while it's listening), a single tap may
not reach vctrnr. That depends on the Buds' firmware and needs testing on yours. Tapping
while a take is **playing back** always works, and so does the lock-screen player.

### Settings

| Setting | What it does |
|---|---|
| Microphone | See above. Change it while stopped. |
| Pause before playback | How long a silence ends a take (0.4 to 4 s). Raise it if you're reading longer passages and it cuts you off between sentences. |
| Mic sensitivity | Raise it if quiet speech isn't picked up. Lower it if background noise triggers playbacks. |

The meter under the button shows your mic level. The tick on it is the trigger level.

## Recordings

```
Internal storage/Recordings/vctrnr/
    2026-11-03 18-42-07/      ← one folder per start→stop session
        take-001.wav
        take-002.wav
```

**Open recordings folder** opens it in Samsung My Files. 48 kHz, 16-bit mono WAV: plays in
any audio app and opens in Praat or Audacity for pitch and resonance analysis. Takes are
saved the way you hear them (quiet takes get up to +18 dB, nothing gets turned down) and
stay put if you uninstall the app.

## Tips for Galaxy Buds

- **Touch controls on**: single tap must be play/pause (the default).
- **Phone-mic mode: turn off "Voice detect"** (Galaxy Wearable app). Otherwise the buds hear
  you talk, switch to ambient mode and turn the volume down right as playback starts.
- **Ambient sound vs. noise cancelling**: ANC makes your own voice sound boomy while you
  speak (the occlusion effect). Ambient sound lets you hear yourself more naturally.
- **Battery**: vctrnr keeps a silent audio stream open (that's what keeps earbud taps
  pointed at it and stops playbacks from losing their first syllable). Close it when you're
  done rather than leaving it open all day.

## How it works

| Piece | File |
|---|---|
| Speech detection: adaptive noise floor + a pitch-periodicity ("is this a voice?") check, so footsteps, thumps and wind noise don't trigger playback. Pre-roll so the first syllable isn't clipped. | [`audio/SpeechDetector.kt`](app/src/main/java/app/vctrnr/audio/SpeechDetector.kt) |
| Record → wait for the pause → play loop, including the earbud call-mode ↔ music-mode flip. The mic is ignored while anything plays, so it never hears itself. | [`audio/TrainerEngine.kt`](app/src/main/java/app/vctrnr/audio/TrainerEngine.kt) |
| Switching the buds between call mode and music mode. | [`audio/EarbudMic.kt`](app/src/main/java/app/vctrnr/audio/EarbudMic.kt) |
| The always-running (silent) media stream that plays takes and chimes. | [`audio/OutputLine.kt`](app/src/main/java/app/vctrnr/audio/OutputLine.kt) |
| Media session: earbud play/pause → start/stop, lock-screen player, Close. | [`media/EarbudControls.kt`](app/src/main/java/app/vctrnr/media/EarbudControls.kt) |
| Foreground service: keeps it running with the screen off until you close it. | [`TrainerService.kt`](app/src/main/java/app/vctrnr/TrainerService.kt) |
| Lifecycle, audio focus, earbud disconnects. | [`TrainerController.kt`](app/src/main/java/app/vctrnr/TrainerController.kt) |
| Saving WAVs via MediaStore, opening the folder. | [`storage/RecordingStore.kt`](app/src/main/java/app/vctrnr/storage/RecordingStore.kt) |
| UI (Jetpack Compose). | [`ui/TrainerScreen.kt`](app/src/main/java/app/vctrnr/ui/TrainerScreen.kt) |

## Building it yourself

Open the project in Android Studio, or from a terminal with JDK 17+ and the Android SDK:

```sh
./gradlew testDebugUnitTest   # speech detector + audio helper tests
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

Toolchain: Gradle 9.6, Android Gradle Plugin 9.5, Kotlin 2.4, Compose BOM 2026.09,
compileSdk/targetSdk 36, minSdk 31.
