# Porting MewZ

MewZ is written for one Android headunit (landscape, 2000×1200 design canvas). This
note is the map for taking it to a normal Android phone/tablet layout or to iPhone.

## What's shared and what isn't

| Layer | Where | Platform-specific? |
|---|---|---|
| Rules: queue, shuffle, loop, Mix refills, previous/next, Liked/History/libraries, title clean-up, album matching, LRC parsing | `core/` | **No.** Plain Kotlin, no Android imports. |
| The outside world: search, audio links, Mix, albums, lyrics, suggestions | interfaces in `core/sources/Sources.kt`, implementations in `data/` | Interfaces no, implementations yes |
| Saving (SharedPreferences JSON) | `data/LibraryStore.kt`, `data/SearchHistory.kt`, `PlayerController.saveState` | Yes |
| Playback: ExoPlayer, disk cache, pre-buffering, media session, audio focus | `player/` | Yes |
| Screens | `ui/` (Compose) | Android (see below) |

The rule that keeps this working: **nothing in `core/` may import `android.*`,
`androidx.*`, `org.json`, Coil or NewPipe.** If new logic doesn't need the platform,
put it in `core` as an immutable value with functions that return a new value
(like `QueueState` and `LibraryBook`) and add a unit test in `app/src/test/`.

## A standard Android layout (phone / tablet)

Nothing in `core`, `data` or `player` changes. The work is in `ui/`:

1. Everything is sized in design pixels (`.u` / `.usp` from `ui/theme/DesignScale.kt`)
   against 2000×1200. For a phone, either give `DesignScale` a portrait canvas
   (e.g. 1080×2340) and build portrait versions of the screens, or switch the screens
   to ordinary `dp`/`sp` with `WindowSizeClass` breakpoints.
2. The glass keyboard (`ui/keyboard/`) exists because headunits have poor system
   keyboards. On a phone use the system keyboard: drop `GlassKeyboard` from
   `HomeScreen` / `NewLibrarySheet` and let `TextField` show the IME.
3. `MainActivity` locks landscape and runs immersive full-screen; relax both.
4. Media notification: on a phone you'll want one. `PlaybackService` is a
   `MediaSessionService`, so it only needs the `POST_NOTIFICATIONS` permission back
   in the manifest (it was removed because the headunit doesn't need it).
5. Keep `applicationId = com.headunit.music` if it should update the headunit build
   in place (changing it loses saved libraries); use a new id for a separate app.

## iPhone

The cleanest route is Kotlin Multiplatform for the logic and SwiftUI for the screens.

### 1. Move `core` into a shared module
1. Add a `shared` KMP module (targets: `androidTarget()`, `iosArm64()`,
   `iosSimulatorArm64()`), with `kotlinx-coroutines-core` in `commonMain`.
2. Move `app/src/main/java/com/headunit/music/core/**` into
   `shared/src/commonMain/kotlin/` unchanged, and `app/src/test/.../core/**` into
   `shared/src/commonTest/kotlin/` (swap JUnit for `kotlin.test`).
3. Make `app` depend on `project(":shared")`. Package names stay the same, so no
   Android code changes.
4. The iOS build produces a framework Swift can import (`QueueState`, `LibraryBook`,
   `SongText`, `AlbumMatching`, the source interfaces…).

### 2. Implement the sources for iOS
Each interface in `core/sources/Sources.kt` needs an iOS version:

| Interface | Android today | iOS |
|---|---|---|
| `SongSearchSource` | YouTube Data API (`YouTubeSearch`) | Same HTTP calls; can be shared in `commonMain` with Ktor + kotlinx.serialization |
| `SuggestionSource` | Google suggest endpoint | Same; shareable with Ktor |
| `LyricsSource` | LRCLIB (`LyricsRepository`) | Same; shareable with Ktor |
| `AudioStreamSource` | NewPipeExtractor (JVM only) | **No drop-in.** NewPipe doesn't run on iOS. Options: a small server you run that resolves stream URLs, or YouTube's IFrame player in a `WKWebView` (the approach tried on the old Hybrid branch) |
| `MixSource`, `AlbumSource` | NewPipeExtractor | Same problem as audio: server-side, or YouTube Music's web endpoints called directly |

Moving search/suggestions/lyrics from `HttpURLConnection` + `org.json` to Ktor +
kotlinx.serialization is the first step that lets those three be shared as-is.

### 3. Playback and saving on iOS
- **Player:** `AVPlayer` / `AVQueuePlayer` takes the place of ExoPlayer. Drive it from
  `QueueState` exactly as `PlayerController` does: call `next()`, `previous(seconds)`,
  `enqueue`, `withMix`…, play `current`, and pre-load `ahead(1)` when it changes.
- **Background audio:** `AVAudioSession` category `.playback`, plus the "Audio"
  background mode.
- **Car / lock screen controls:** `MPNowPlayingInfoCenter` and `MPRemoteCommandCenter`
  (CarPlay is a separate entitlement).
- **Saving:** `LibraryStore` saves `LibraryBook` as JSON; do the same with
  `UserDefaults` or a file (or move the JSON to kotlinx.serialization in `commonMain`).

### 4. Screens
Rebuild in SwiftUI using the screens in `ui/` as the reference (or try Compose
Multiplatform, though the glass blur effects would need platform code). The design
values (sizes, colours, glass layers) are all in the Compose files and
`ui/theme/Styles.kt` / `ui/components/Glass.kt`.

## Legal note
Pulling YouTube audio without the official player isn't allowed by YouTube's terms.
That's fine for a personal headunit build, but an App Store or Play Store release
would need the official IFrame player or a licensed music source.
