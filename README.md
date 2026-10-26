# MewZ — headunit music player

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

**Free quota:** 10,000 units per day. Each search costs about 101 units, and so does each extra page of 50 results loaded by scrolling to the bottom, so roughly **99 searches or pages a day**. It resets at midnight Pacific time. If you hit it, the app says so. (Up-next mixes and audio don't use quota.)

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
- Player: **audio only**. Search uses the YouTube Data API; for each song the app finds
  YouTube's audio-only stream (~128 kbps AAC, via NewPipeExtractor) and plays it with
  Media3 ExoPlayer, so no video is downloaded: roughly 60 MB per hour.
  The song playing buffers up to 5 minutes ahead, and once it has, the next song's first
  5 minutes download into an on-disk cache (64 MB, also keeps recently played songs), so skipping
  starts instantly and music keeps going through tunnels and dead zones.
  Keeps playing in the background (a media service), and the steering wheel, Bluetooth and
  the headunit's own media controls work, showing the song's title, artist and artwork.
  Pauses for navigation prompts / calls and when Bluetooth drops.
  Now Playing laid out like CarPlay (720×720 artwork, full-width seek bar, shuffle /
  loop-this-song / add); ⌄ minimizes to a glass dock and returns home.
  Tapping a result plays it followed by its YouTube Mix (like YouTube autoplay, refilled as it
  runs low); songs auto-advance; songs that can't be
  played are skipped. Personal project: pulling audio this way isn't allowed by YouTube's
  terms, and Google could disable the API key if it notices.
- Libraries: scroll down from "Listen..." to big glass squares, two per row: **Liked**,
  **History** (last 50 songs that played for 3+ seconds), your own libraries, and **+** to
  make one (named on the glass keyboard). Opening one lists its songs under its name, which
  shrinks as you scroll; nothing plays until you pick a song, then the library loops and
  Now Playing shows "Name — 3 of 20". On Now Playing, ♡ likes the song and + adds it to one
  of your libraries (which likes it too). Saved on the device.

## Branches
- `main`: the working build (YouTube Data API search + audio-only playback via NewPipeExtractor).
- `cleanup`: the tidy-up below, to be merged into `main` once it's been tested on the headunit.

## Code layout
The app is split in two so the logic can be reused on other platforms (see
[docs/PORTING.md](docs/PORTING.md)):

```
app/src/main/java/com/headunit/music/
  core/        Plain Kotlin, no Android imports: the rules of the app
    model/       Track, Library, Lyrics (+ LRC parser)
    playback/    QueueState (queue, shuffle, loop, Mix), PlayerError
    library/     LibraryBook (Liked, History, your libraries)
    search/      SearchResult, result rows, album matching
    text/        SongText (title/artist clean-up, durations)
    sources/     Interfaces for search, audio, Mix, albums, lyrics, suggestions
  data/        Android/network implementations of those sources, and storage
    youtube/     YouTube Data API search, NewPipe audio/Mix/albums, suggestions
    LibraryStore, SearchHistory, Lyrics (LRCLIB)
  player/      ExoPlayer, the media service and the disk cache
  ui/          Compose screens
    theme/       DesignScale (.u / .usp), shared text styles
    components/  Glass, search bar, collapsing header
    home/ library/ player/ keyboard/
```

`core` holds state as immutable values (`QueueState`, `LibraryBook`); the Android
classes (`PlayerController`, `LibraryStore`) keep the current value in Compose state,
save it, and do the I/O. New logic belongs in `core` whenever it doesn't need Android.

## Tests
Unit tests for `core` live in `app/src/test/`. In Android Studio right-click that folder →
**Run Tests**, or from a terminal:
```
gradlew testDebugUnitTest
```

## Repo layout
- `app/` — the Android app
- `docs/design/` — original mockups and graphics
- `docs/PORTING.md` — how to take the app to a phone layout or iPhone
