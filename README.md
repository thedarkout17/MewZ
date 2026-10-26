# MuZ — headunit music player

![Home screen](docs/home_screen_preview.png)

Kotlin + Jetpack Compose, landscape, designed on a 2000×1200 canvas.

## Run it
1. Open this folder in Android Studio (Ladybug or newer) and let Gradle sync.
2. Plug in the headunit (USB debugging on) or use an emulator set to 2000×1200, then press Run.

## How YouTube is used
Search and playback go through **[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)**
(the library behind the NewPipe app). No API key, no daily search limit.

- **Search** reads YouTube's own search results; titles and durations come back in one request.
- **Playback is audio only.** For each song the app finds YouTube's audio-only stream
  (~128 kbps AAC) and plays it with Media3 ExoPlayer, so no video is downloaded:
  roughly **60–100 MB per hour** instead of 300–550 MB.
- **Pre-buffering:** when a song starts, the next one's audio link is looked up and its
  first ~5 seconds (128 KB) are downloaded into an on-disk cache, so skipping starts instantly.
  Everything played goes through the same cache (64 MB, least recently played dropped first),
  so replaying a recent song costs no data. Stream links expire after a few
  hours; the player fetches a fresh one and carries on if that happens.

**Things to know**
- This is a personal project. Pulling audio this way isn't allowed by YouTube's terms,
  whether or not the app is public or commercial.
- When YouTube changes its pages, search or playback can stop working until
  NewPipeExtractor is updated (usually within days). Bump the version in
  `app/build.gradle.kts` to pick up fixes; the app shows "YouTube changed something" when this happens.
- Heavy use from one connection can make YouTube ask for a captcha for a while;
  the app shows "YouTube is limiting requests" and recovers on its own.

## Layout system
Everything is measured in *design pixels* on a 2000×1200 canvas (`ui/theme/DesignScale.kt`)
and scaled to the real screen, so it looks the same whatever density the headunit reports.
Use `with(LocalDesignScale.current) { 232.u }` for sizes and `92.usp` for text.

## What's done
- Home screen: gradient background, frosted search pill, "Listen..." title
- Search field (keyboard "Search" key submits), tap anywhere on the pill to focus
- Mic button opens the system voice recognizer and searches what you say
- Full-screen immersive mode, landscape lock, shows up as a music app in headunit launchers
- Glass in-app keyboard (no system keyboard), suggestions as you type
- YouTube song search (NewPipeExtractor, no API key), with durations; titles cleaned up
  ("Artist - Song (Official Video)" → song + artist, featured artists moved to the artist line);
  loading, empty and error states
- Recent searches (saved on the device, scrollable, × to remove)
- Player: audio-only playback (ExoPlayer). Now Playing laid out like CarPlay: 720×720
  artwork cut from the centre of the HD thumbnail, artwork-blur backdrop, full-width seek bar,
  shuffle / loop-this-song / add. ⌄ minimizes to a glass dock and returns to the home screen.
  Tapping a result queues the rest of that list; songs auto-advance; songs that can't be
  played are skipped. Pauses for navigation prompts and when Bluetooth drops.

## Repo layout
- `app/` — the Android app
- `docs/design/` — original mockups and graphics
- `docs/demo/` — live web demo of the screens (mirrors the Compose layout, with an event log for debugging)
