# MuZ — headunit music player

![Home screen](docs/home_screen_preview.png)

Kotlin + Jetpack Compose, landscape, designed on a 2000×1200 canvas.

## Run it
1. Open this folder in Android Studio (Ladybug or newer) and let Gradle sync.
2. Plug in the headunit (USB debugging on) or use an emulator set to 2000×1200, then press Run.

## Set up YouTube search (one time, ~5 minutes)
Search uses the official **YouTube Data API v3**, which needs a free API key.

1. Go to <https://console.cloud.google.com/>, create a project (any name).
2. **APIs & Services → Library** → search "YouTube Data API v3" → **Enable**.
3. **APIs & Services → Credentials → Create credentials → API key**. Copy it.
4. Recommended: edit the key → **API restrictions → Restrict key → YouTube Data API v3**.
5. Open `local.properties` in the project root (Android Studio creates it; it is git-ignored, so the key never reaches GitHub) and add:
   ```
   youtube.apiKey=AIza...your key...
   ```
6. Rebuild and run.

**Free quota:** 10,000 units per day. Each search costs about 101 units, so roughly **99 searches a day**. It resets at midnight Pacific time. If you hit it, the app says so.

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
- YouTube song search: music videos that allow embedding, with durations; titles cleaned up
  ("Artist - Song (Official Video)" → song + artist); loading, empty and error states
- Recent searches (saved on the device, scrollable, × to remove)
- Player: songs play through YouTube's own player (IFrame API in a WebView), **hidden**:
  you see the artwork and hear the song. The player is kept tiny behind the artwork, so
  YouTube picks its lowest video quality (~144p) and data use stays near audio-only
  (~100 MB/hour). Two hidden players take turns: while one plays, the other loads the
  next song muted and holds it at 0:00, so skipping starts instantly.
  Now Playing laid out like CarPlay (720×720 artwork, full-width seek bar, shuffle /
  loop-this-song / add); ⌄ minimizes to a glass dock and returns home.
  Tapping a result queues the rest of that list; songs auto-advance; songs YouTube
  won't play outside YouTube are skipped automatically.

## Branches
- `main`: this version (YouTube Data API search + hidden YouTube players).
- `savepoint/youtube-api`: the version with the visible embedded player, kept as a fixed restore point.
- `experiment/audio-only`: NewPipeExtractor search + true audio-only playback with ExoPlayer.

## Repo layout
- `app/` — the Android app
- `docs/design/` — original mockups and graphics
- `docs/demo/` — live web demo of the screens (mirrors the Compose layout, with an event log for debugging)
