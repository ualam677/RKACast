# RKACast

Android app to cast videos to your TV, similar to Web Video Caster, with Stremio and torrent streaming built in.

## Features
- **Cast any link**: paste or share a video URL (mp4, mkv, webm, HLS `.m3u8`, DASH…) to Chromecast / Google TV / Android TV or any DLNA smart TV (Samsung, LG, Sony, Roku/Fire TV with a DLNA app, Kodi, Xbox…).
- **Built-in browser**: detects videos on web pages and casts them, optionally relaying through the phone with the site's headers.
- **Local videos**: cast files from the phone (the gallery, Downloads, or "Share → RKACast").
- **Stremio**: browse catalogs, search, view series episodes, and get streams from your addons (Cinemeta, Torrentio, etc.). Log in to import the addons from your Stremio account. `stremio://` links install addons.
- **Torrents & magnet links**: stream on-device with a built-in libtorrent engine (sequential download, seeking), or use a Stremio streaming server on your network.
- **Subtitles** (SRT, SSA/ASS, VTT): auto-fetch from Stremio subtitle addons (OpenSubtitles) in your preferred language, load any .srt/.vtt URL or file, and switch them mid-playback. SRT is converted to WebVTT automatically for Chromecast. Text size is adjustable.
- **Remote control**: play/pause, seek, ±10/30 s, stop, change subtitles.
- **Play on phone**: built-in ExoPlayer.

## Install (no PC needed)
Open this link on your phone and install the APK (allow "install unknown apps" when asked):

**https://github.com/ualam677/RKACast/releases/latest/download/RKACast.apk**

Every build is signed with the same key, so new versions install over the old one and keep your settings.

## Reporting bugs
Settings → Open debug log → Copy, then paste it into the chat. If the app crashed, it offers a crash report the next time it opens.

## Build locally
```
./gradlew assembleDebug
```
Requires JDK 17 and the Android SDK (API 35).

## Architecture
| Package | Purpose |
|---|---|
| `cast` | `CastManager` (one API for Google Cast and DLNA), `MediaResolver` (URL/magnet/Stremio/local → castable URL), `dlna/` SSDP discovery and AVTransport control |
| `server` | `LocalMediaServer` (NanoHTTPD with Range support): serves local files, torrents, proxied streams and VTT subtitles to the TV. `StreamService` keeps it alive in the background |
| `torrent` | `TorrentEngine` (libtorrent4j) with piece-deadline streaming |
| `stremio` | Addon protocol client, models, addon repository, account sync |
| `browser` | WebView controller with video sniffing |
| `ui` | Jetpack Compose screens |

Only stream content you have the rights to watch.
