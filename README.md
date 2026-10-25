# MuZ — headunit music player

![Home screen](docs/home_screen_preview.png)

Kotlin + Jetpack Compose, landscape, designed on a 2000×1200 canvas.

## Run it
1. Open this folder in Android Studio (Ladybug or newer) and let Gradle sync.
2. Plug in the headunit (USB debugging on) or use an emulator set to 2000×1200, then press Run.

## Layout system
Everything is measured in *design pixels* on a 2000×1200 canvas (`ui/theme/DesignScale.kt`)
and scaled to the real screen, so it looks the same whatever density the headunit reports.
Use `with(LocalDesignScale.current) { 232.u }` for sizes and `92.usp` for text.

## What's done
- Home screen: gradient background, frosted search pill, "Listen..." title
- Search field (keyboard "Search" key submits), tap anywhere on the pill to focus
- Mic button opens the system voice recognizer and searches what you say
- Full-screen immersive mode, landscape lock, shows up as a music app in headunit launchers

## Repo layout
- `app/` — the Android app
- `docs/design/` — original mockups and graphics
- `docs/demo/` — live web demo of the screens (mirrors the Compose layout, with an event log for debugging)
