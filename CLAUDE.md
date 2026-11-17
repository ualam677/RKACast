# RKACast

Android (Kotlin + Jetpack Compose) casting app. See README.md for features and architecture.

- Single module `:app`, package `com.rkacast`. minSdk 26, target/compile 35, AGP 8.7.3, Kotlin 2.0.21.
- The cloud sandbox has no Android SDK (dl.google.com is blocked), so builds are verified on GitHub Actions (`.github/workflows/android.yml`). Check the run with the GitHub MCP actions tools after pushing.
- Everything the TV must fetch from the phone goes through `server/LocalMediaServer` (port 8765), which adds CORS and DLNA headers.
- Subtitles always go through `/sub/{id}.vtt` (SRT→VTT conversion and CORS for Chromecast).
- CI (`.github/workflows/android.yml`) on every push to the dev branch: unit tests (`app/src/test`), debug APK, GitHub Release `build-N` (stable link `releases/latest/download/RKACast.apk`), then an emulator job that screenshots every tab (portrait + landscape) and force-pushes them, plus UI dumps and logcat, to the `ci-screenshots` branch. Read them with `git fetch origin ci-screenshots && git archive FETCH_HEAD | tar -x -C <dir>`.
- Debug builds accept `am start -n com.rkacast/.MainActivity --es tab REMOTE` (used by `.github/scripts/screens.sh`).
- APKs are signed with the committed `app/rkacast.jks` (password `rkacast`) so builds install over each other. The repo is public, so this key isn't secret; move it to an Actions secret before distributing.
- Users report bugs by pasting Settings → Open debug log (`data/DebugLog.kt`, secrets redacted). Keep logging calls non-throwing.
- Keep JVM-tested code free of `android.*` (e.g. use `java.util.Base64`).
