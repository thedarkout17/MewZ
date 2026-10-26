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

## iPhone (done: `phone/` + `iosApp/`)

How it's put together, following the plan this section used to describe:

1. **Shared module.** `core/` moved unchanged into `shared/` (Kotlin Multiplatform:
   Android, JVM, iOS). Its tests run on the JVM and on the iPhone simulator (which caught one
   regex the iOS engine rejects, now fixed in `AlbumMatching`). The headunit depends on it.
2. **Sources.** `phone/.../data` implements every interface in `core/sources/Sources.kt` in
   plain Kotlin with Ktor:
   - Search, suggestions, lyrics: the same HTTP calls as the headunit.
   - Audio, Mix, albums: YouTube's InnerTube API called directly (`InnerTube.kt`), the way
     NewPipeExtractor does it today: the **VISIONOS** client for audio (direct links, no JS
     player or PO token), **WEB** "next" for Mixes and album track lists, **WEB_REMIX**
     search for albums. No server.
3. **Playback.** `phone/.../player/PlayerController.kt` is the headunit's controller over
   `QueueState`, with the same API; `AvAudioEngine` (iosMain) plays through an
   `AVQueuePlayer` (next song queued and buffered), with the `.playback` audio session,
   `MPRemoteCommandCenter` and `MPNowPlayingInfoCenter`.
4. **Saving.** `UserDefaults`, same JSON shapes as the headunit.
5. **Screens.** Compose Multiplatform: the headunit's screens ported (`phone/.../ui`), on a
   portrait canvas 1500 design px wide (where the headunit's sizes come out iPhone-sized),
   with the iOS keyboard instead of the glass keyboard, a left-edge swipe for Back, and a
   portrait Now Playing (`PlayerLayout.kt`).

The headunit could use the same InnerTube code for audio (it's plain Kotlin) if
NewPipeExtractor ever falls behind: NewPipe's own dev branch has dropped the ANDROID client
the 0.26.5 release still uses.

## Legal note
Pulling YouTube audio without the official player isn't allowed by YouTube's terms.
That's fine for a personal headunit build, but an App Store or Play Store release
would need the official IFrame player or a licensed music source.
