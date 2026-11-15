# Fold7 test plan (owner, about 20 minutes)

Install the latest APK, then go through the list. For anything that looks wrong, open
**Settings > Report a problem**, describe it in one sentence, keep "Attach the Fold Lab trace"
ticked, and send it to yourself (WhatsApp, Telegram, Gmail or Drive). Then share the file with
Claude in the next session. A screen recording (swipe down > Screen recorder) of the moment is
even better.

Before you start: Settings > Fold Lab > **Clear**, so the trace only holds this test.

## 1. Apply

- [ ] Discover > **Daybreak** (Glass) > Get this wallpaper > Apply. The system preview opens and
      the wallpaper is set on the home screen (and lock screen if you choose both).
- [ ] The app shows it as Active in the Library.

## 2. Lock screen (plays after unlock, like Samsung)

- [ ] Screen off, then on: the lock screen shows the wallpaper waiting in the first pose of its
      motion (glass: the drops gathered on the right of the main screen), not moving yet.
- [ ] On the lock screen nothing moves at all (no waves, drops or drifting): it waits.
- [ ] Unlock **without folding** (phone open, or closed): the animation of the screen in use plays
      right after the lock screen goes. Try fingerprint, face and PIN.
- [ ] Unfold while still on the lock screen: no animation; unlock: the open animation plays.
- [ ] Settings > Play after unlocking off: the animation plays as soon as the screen turns on.

## 2a. Rotation (0.9.1)

- [ ] Open phone, home screen, turn it sideways: the wallpaper fills the whole screen (no grey
      bars, no square). Turn it back. If grey appears, take a screenshot and send a report
      (Settings > Report a problem, keep the Fold Lab trace).

## 2b. Fold with the screen OFF

- [ ] Lock the phone open, fold it, press power: the cover screen plays the fold animation.
- [ ] Lock it folded, unfold it, unlock: the main screen plays the unfold animation.

## 3. Fold with the screen ON (the hard case)

- [ ] On the home screen of the main display, fold the phone: the cover lights up with the
      animation (not a still picture), within about half a second.
- [ ] On the cover home screen, unfold: the main screen plays the unfold animation.
- [ ] Repeat 5 times quickly. Note any time it does not play or stutters.

## 4. Liquid glass and built-in motion (0.9)

- [ ] Glass: closed, the drops sit together on the cover; unfold: they flow apart to the left
      and settle with a slight bounce; fold: they flow back together. Smooth or choppy?
- [ ] The rims look crisp (no blur, no stair steps) on the main screen.
- [ ] Tilt the phone slowly: the drops shift and the light on their rims turns. Tap empty space
      on the home screen: the glass ripples (if your launcher passes taps to the wallpaper).
- [ ] Hold the phone half open (like a laptop): the drops lean towards the fold.
- [ ] A drawn scene from Discover (e.g. Aurora over the peaks): unfold: the mountains and trees
      glide in from the right and rise into place, with nothing dark or bright laid over them;
      fold: the same from the left. No edge or gap shows at the sides.
- [ ] Watch a glass wallpaper for 30 s: the drops drift slowly and never jump.
- [ ] Turn on power saving: the motion stops; turn it off: it resumes.
- [ ] Settings > Reduce motion on: the motion stops.

## 4b. Realistic scenes (0.9.1)

- [ ] Ocean sunset: closed, the sun sits on the horizon; unfold: it rises and its light path on
      the water follows; the waves keep rolling (watch 20 s).
- [ ] Golden hour (Sky): unfold: the clouds part around the sun and drift left.
- [ ] Northern lights: unfold: the lights spread across the sky from the right.
- [ ] Neon rain: unfold: the fogged glass clears from the right; drops run down the glass.
- [ ] Blue planet: unfold: the sun climbs over the planet's edge and daylight spreads.
- [ ] Note any scene that stutters, looks blurry or heats the phone.

## 5. Add your own

- [ ] Add > Photo > pick a photo of a person or pet, keep Auto, Make my wallpaper: the subject
      floats in front and moves against the background when you tilt; unfolding lifts it out.
- [ ] Add > Photo > pick any photo, choose another motion (e.g. Book), make it, apply.
- [ ] In the Library, your photo wallpaper offers the motion picker; a Discover wallpaper does
      not (its motion is built in).
- [ ] Add > Video > pick a short clip, trim it, make it, apply: it loops on both screens with no
      effect laid over it.

## 6. Battery (over one day)

- [ ] Settings > Battery > usage by app: Fold Motion should stay low (under ~3% a day with normal
      use). Note the figure.

## 7. Send the results

Settings > Fold Lab > **Export trace**, plus your notes (smooth / choppy / broken per wallpaper).
