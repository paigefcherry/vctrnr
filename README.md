# vctrnr

**Speak, pause, hear yourself back.** A voice-training app for Android, built for a
Galaxy S23 Ultra with Galaxy Buds.

You talk. When you stop for a moment, vctrnr plays back what you just said through your
earbuds, then starts listening again. Tap an earbud (the normal play/pause tap) to start or
stop it, same as the big button on screen. Every take is saved as a WAV file.

## Install

1. Open the latest successful run under **Actions → Build APK** on GitHub and download the
   `vctrnr-apk` artifact (a zip).
2. Unzip it on the phone and open `vctrnr.apk`. Android will ask you to allow installs from
   your browser or file manager the first time.
3. Open vctrnr and allow the microphone.

Every build is signed with the same key (`app/debug.keystore`), so a newer APK installs
over an older one as an update and your settings are kept.

## Using it

- **Start**: tap the big button, or tap an earbud. You'll hear a short rising chime.
- **Talk**, then **pause**. After the pause (1 s by default) your take plays back.
- **Stop**: tap the button or an earbud again (falling chime). If you stop mid-sentence,
  that take is still saved, just not played.
- The meter under the button shows your mic level. The tick on it is the trigger level.
- **Open recordings folder** opens the folder in Samsung My Files.

While vctrnr is open it's the phone's active media app: other audio pauses, and earbud taps
go to vctrnr. The screen stays on while it's open. **Leaving the app turns it off**: home,
recents, back, or pressing the power button. Nothing ever runs in the background.

### Settings

| Setting | What it does |
|---|---|
| Pause before playback | How long a silence ends a take (0.4 to 4 s). Raise it if you're reading longer passages and it cuts you off between sentences. |
| Mic sensitivity | Raise it if quiet or distant speech isn't picked up. Lower it if background noise triggers playbacks. |

## Recordings

```
Internal storage/Recordings/vctrnr/
    2026-11-03 18-42-07/      ← one folder per start→stop session
        take-001.wav
        take-002.wav
```

48 kHz, 16-bit mono WAV: plays in any audio app and opens in Praat or Audacity for pitch
and resonance analysis. Takes are volume-boosted the same way you hear them (quiet takes
get up to +18 dB, nothing gets turned down). Recordings stay put if you uninstall the app.

## Tips for Galaxy Buds

- **Turn off "Voice detect"** (Galaxy Wearable app → *Sound controls* / *Advanced
  features*, depending on model). Otherwise the buds hear you talk, switch to ambient
  mode and turn the volume down, which is exactly when your playback starts.
- **Touch controls on**: single tap must be play/pause (the default).
- **Ambient sound vs. noise cancelling**: ANC makes your own voice sound boomy while you
  speak (the occlusion effect). Ambient sound mode lets you hear yourself more naturally.
- **Where to put the phone**: vctrnr records with the **phone's microphone**, not the buds,
  so keep the phone about 30–50 cm in front of you, bottom edge towards you, and use the
  same distance for every session so takes are comparable.

### Why not record with the earbud microphones?

Using the buds' mics makes Android put them into phone-call mode. Call audio is narrowband
and heavily noise-suppressed in both directions (bad for judging your voice), and in call
mode the tap gesture stops acting as play/pause. The phone mic is recorded with Android's
`VOICE_RECOGNITION` source: no automatic gain or noise suppression, so what you hear is
close to how you actually sound.

## How it works

| Piece | File |
|---|---|
| Speech detection: adaptive noise floor + a pitch-periodicity ("is this a voice?") check, so typing, thumps and fan noise don't trigger playback. Pre-roll so the first syllable isn't clipped. | [`audio/SpeechDetector.kt`](app/src/main/java/app/vctrnr/audio/SpeechDetector.kt) |
| Record → wait for the pause → play loop. The mic is ignored while anything plays, so it never hears itself. | [`audio/TrainerEngine.kt`](app/src/main/java/app/vctrnr/audio/TrainerEngine.kt) |
| One always-running (silent) media stream while open. It keeps vctrnr as the app that receives earbud taps, and keeps the Bluetooth link awake so playback starts instantly with nothing cut off. | [`audio/OutputLine.kt`](app/src/main/java/app/vctrnr/audio/OutputLine.kt) |
| Media session: earbud play/pause → start/stop. | [`media/EarbudControls.kt`](app/src/main/java/app/vctrnr/media/EarbudControls.kt) |
| Saving WAVs via MediaStore, opening the folder. | [`storage/RecordingStore.kt`](app/src/main/java/app/vctrnr/storage/RecordingStore.kt) |
| Lifecycle, audio focus, earbud disconnects. | [`TrainerController.kt`](app/src/main/java/app/vctrnr/TrainerController.kt) |
| UI (Jetpack Compose). | [`ui/TrainerScreen.kt`](app/src/main/java/app/vctrnr/ui/TrainerScreen.kt) |

## Building it yourself

Open the project in Android Studio, or from a terminal with JDK 17+ and the Android SDK:

```sh
./gradlew testDebugUnitTest   # speech detector + WAV tests
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

Toolchain: Gradle 9.6, Android Gradle Plugin 9.5, Kotlin 2.4, Compose BOM 2026.09,
compileSdk/targetSdk 36, minSdk 31.
