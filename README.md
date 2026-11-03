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
- With the earbud mic, wait for the **soft tick** (the call is connected), then talk.
- **Pause**. After the pause (1 s by default) your take plays back, then it's listening
  again. (In Full quality mode, wait for the tick before your next take.)
- **Stop**: tap the button / lock-screen player, or from the earbuds (see the table below).
  If you stop mid-sentence, that take is still saved, just not played.
- Lock the phone and put it away whenever you like. It keeps running.

### Open vs. closed

vctrnr runs from the moment you open it until you **close** it: the **Close** button in the
app, **Close** on the notification / lock-screen player, or swiping it away from Recents.
While it's open it's the phone's active media app (other audio pauses, earbud taps go to
vctrnr). Once closed, nothing of it runs and earbud taps go back to your other apps.

### Microphone: earbuds or phone

| | Earbuds · Instant (default) | Earbuds · Full quality | Phone |
|---|---|---|---|
| Hands-free / walking around | ✅ | ✅ | ❌ phone in front of you |
| Recording | Call quality (noise-suppressed) | Call quality | Best (no gain control or noise suppression) |
| Playback | Instant, call quality | Full quality, after a 1–2 s switch | Instant, full quality |
| Stop from the earbuds | **Double-tap** ("hang up"), any time | Tap **during playback** | Tap, any time |

Bluetooth earbuds can only send their mic audio over the **call** channel, and while they're
in call mode their gestures become call controls: a single tap does nothing, a double tap
means "hang up". So:

- **Instant** keeps the buds in call mode for the whole session and registers it with
  Android as a (silent, never dialled) VoIP call. Double-tapping an earbud "hangs up", which
  stops the session. Android may show it like an ongoing call while it runs, and a real
  incoming phone call stops it.
- **Full quality** switches the buds to music mode for each playback and back again. The
  switch itself takes the Bluetooth hardware about a second each way.

If the earbud mic isn't available, vctrnr falls back to the phone mic and tells you.

### Settings

| Setting | What it does |
|---|---|
| Microphone, Earbud playback | See above. Change them while stopped. |
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
| The session-as-a-VoIP-call registration, so "hang up" on the earbuds stops it. | [`telecom/TrainerCall.kt`](app/src/main/java/app/vctrnr/telecom/TrainerCall.kt) |
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
compileSdk 37, targetSdk 36, minSdk 31.
