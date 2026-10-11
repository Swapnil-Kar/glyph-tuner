<p align="center"><img src="docs/logo.png" width="128" alt="Glyph Tuner logo"></p>

# Glyph Tuner

**Tap. Flip. Tuned.** A free guitar tuner that lives on the Glyph Matrix, the round light on the back of the Nothing Phone (3).
Tap the widget or the quick settings tile, put the phone face down, and tune from the back. No app to open.

## Download

Get the latest `GlyphTuner-1.0.0.apk` from the [Releases](../../releases) page, open it on your phone and allow the install.
Android asks once whether your browser or file manager may install apps; say yes for this one.

**Needs:** a Nothing Phone (3) on Android 14 or newer. It's built for the Phone (3) and hasn't been tested on other phones.

This is the first public release. If something doesn't work, or you have an idea, please [open an issue](../../issues).

## How it looks on the Glyph Matrix

- **The letter** in the middle is the string you plucked: E A D G B e (the thin high string shows as a small **e**).
- **▲** means the string is too low: tighten the peg. **▼** means it's too high: loosen it.
- **The ring** spins while you're off, slows down as you get close, and stops and glows when you're in tune.
- **The dots along the bottom** are your strings, thickest on the left. The blinking one is the string you're tuning; solid means done.
- The phone **clicks** once when a string is done.
- When listening starts, a dot sweeps once around the ring. When it stops, the ring shrinks to a dot and fades.
- While it waits for a note, the ring shimmers with any sound it hears, so you know it's listening.
- Change strings and the old letter's dots fly into the new one. As you get close, the gaps in the spinning ring fill in;
  in tune, a ripple of light goes out from the middle. Go past the target and the arrow flips with a little shake.
  A finished string's dot drops into place with a bounce.
- Light taps as you pass 20, 10 and 5 cents on the way in (switch off in Settings).
- Tune every string and the dots burst outwards (or fireworks, ringing strings, or equalizer bars, a different one each time), a short message scrolls past (GO PLAY, LET IT RING, MAKE MUSIC…), and a heart beats twice. Rare ones too: FIRST OF MANY, TUNE-UP #10, HELLO AGAIN.

## The app screen

The middle of the screen is a live copy of the back of the phone. Below it: the note, how many cents off,
and one word (TIGHTEN, LOOSEN, IN TUNE). The six string buttons fill in as you go; tap one to listen for that
string only (handy in a noisy room), tap AUTO to go back. The first time you open the app, a short tour
explains the microphone and has you try it for real, then offers the quick settings tile so you can skip the app next time. The **?** button opens **How it works**:
five short cards, each with a little film of the back (flip and pluck, the arrow, the ring, the click and the dot, the celebration).

## Using it

1. Open Glyph Tuner. It starts listening straight away (the first time, a short tour runs first).
2. Flip the phone face down. You can lock it.
3. Pluck a string and follow the arrow.
4. That's it: it switches itself off about 30 seconds after every string is in tune. The notification shows which string
   you're on and how many are done, with a Stop button. It also stops after 5 minutes of quiet, and pauses during calls.

Other ways to start and stop: the **Tuner** home screen widget (a bar that shows which strings are done), long-press the app icon (**Start tuner** / **Stop tuner**), the **Tuner** quick settings tile,
or add **Tuner** to your Glyph Toys (Settings → Glyph Interface → Glyph Toys): while the tuner runs, it shows on the back when you press the Glyph Button, and a long press stops it.
Android doesn't let a Glyph Toy switch the microphone on, so start the tuner from the tile, the widget or the app.

## Tunings and settings

- Standard, Drop D, Half-step down, Open G, DADGAD, and Ukulele (G C E A)
- Reference pitch A = 440, 442 or 432 Hz
- Click when a string is done, dim at night (10 pm to 6 am), turn the picture upside down

## Privacy

Glyph Tuner has no internet permission. What the microphone hears stays on the phone and is never saved.

**Why the app has to start it:** Android only lets an app hear the microphone in the background
if it was started from the open app, with a notification showing. A Glyph Toy on its own gets silence.

## How it works

| File | What it does |
|:--|:--|
| `PitchDetector.kt` | Finds the pitch with the YIN method (60–500 Hz), measuring near the fundamental so stiff strings read true |
| `Tuning.kt` | Tunings, reference pitch, matching a pitch to the nearest string, fixing octave mistakes |
| `TunerPrefs.kt` | Settings and the celebration counter |
| `StartActivity.kt`, `TunerTileService.kt`, `TunerWidget.kt` | Shortcuts, the quick settings tile and the home screen widget |
| `TunerState.kt` | Smoothing, in-tune zone, steady arrows, marking strings done, the celebration |
| `TunerArt.kt` | Draws every frame of the Strobe Ring on the 25 × 25 matrix |
| `TunerEngine.kt` | Reads the microphone and draws about 30 frames a second |
| `Haptics.kt` | The click when a string locks, and the swell when all are done |
| `TunerService.kt` | Listening mode (foreground service with a notification) |
| `TunerToyService.kt` | The Tuner Glyph Toy |
| `GlyphLink.kt` | One shared connection to the Glyph Matrix |
| `MainActivity.kt` | The main screen: live mirror of the back, the reading, string buttons, Start |
| `TourActivity.kt` | The first-run tour |
| `GuideActivity.kt`, `GuideDemo.kt` | How it works: the five cards and their films of the back |
| `SettingsActivity.kt` | Settings |
| `MatrixView.kt`, `DotTextView.kt`, `PillSwitch.kt`, `Ui.kt` | The app's look: the dot mirror, dot lettering, switches, colours |

## Building

1. Download `glyph-matrix-sdk-2.0.aar` from Nothing's
   [GlyphMatrix Developer Kit](https://github.com/Nothing-Developer-Programme/GlyphMatrix-Developer-Kit)
   (read and accept its licence) and put it in `app/libs/`. Its licence doesn't allow sharing it, so it isn't in this repository.
2. Open this folder in Android Studio, connect the phone with USB debugging on, press Run.

Build setup follows Nothing's official
[GlyphMatrix Example Project](https://github.com/Nothing-Developer-Programme/GlyphMatrix-Example-Project) (MIT).

## Licence

The Glyph Tuner code is under the [MIT licence](LICENSE). Nothing's Glyph Matrix SDK has its own licence and isn't included here.

Glyph Tuner is an independent project. It isn't made by, endorsed by or affiliated with Nothing Technology.
