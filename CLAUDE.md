# RKACast

Android (Kotlin + Jetpack Compose) casting app. See README.md for features and architecture.

- Single module `:app`, package `com.rkacast`. minSdk 26, target/compile 35, AGP 8.7.3, Kotlin 2.0.21.
- The cloud sandbox has no Android SDK (dl.google.com is blocked), so builds are verified on GitHub Actions (`.github/workflows/android.yml`). Check the run with the GitHub MCP actions tools after pushing.
- Everything the TV must fetch from the phone goes through `server/LocalMediaServer` (port 8765), which adds CORS and DLNA headers.
- Subtitles always go through `/sub/{id}.vtt` (SRT→VTT conversion and CORS for Chromecast).
