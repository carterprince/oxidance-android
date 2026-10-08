# Oxidance for Android

An Android port of [Oxidance](../oxidance), the YouTube Music player. Search
YouTube Music, keep a library of liked songs and playlists, listen offline, and
sync with Oxidance on your computer through your own WebDAV server.

Oxidance is an unofficial client and is not affiliated with YouTube or Google.

## Features

- **Search:** suggestions as you type, then full results that load as you
  scroll. Artist pages show the artist's photo, biography, and top songs. Album
  pages show the album's artwork and tracks.
- **Library:** liked songs and playlists, stored in the same `library.json`
  format as the desktop app.
- **Offline listening:** liked and playlist songs download in their original
  audio format, with album art, named like the desktop's files
  (`Artist - Title [id].webm`).
- **Playback:** a mini player above the tabs. Tap it or swipe up to open the
  full player: enlarged album art, seek bar, shuffle, queue, and a 0.5×–2× speed
  control. Pitch changes with speed, as on the desktop: there is no pitch
  correction. Swipe down to close the player, and swipe the art sideways to skip.
  Playback continues in the background, with media notification, lock screen,
  and headset controls.
- **Sync:** connect a WebDAV server (for example `rclone serve webdav`) in
  Settings. The library merges with other devices, and songs are shared through
  the server's `songs/` folder. The desktop app reads and writes the same files.

## Build and install

Requires JDK 17 or later and the Android SDK (platform 37). Point
`local.properties` at the SDK, then:

```sh
./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key, so it installs directly. To use
it, allow installs from unknown sources, or install it with `adb`.

## Tests

```sh
./gradlew testDebugUnitTest            # library, parsing, sync format, and speed/pitch
./gradlew testDebugUnitTest -Plive     # also YouTube Music and a local rclone server
```

The live tests need network access and `rclone`. `LiveInteropTest` checks a
round trip with the desktop app through a running server
(`-PdavUrl=http://127.0.0.1:PORT`).

## How it works

- `core/`: plain Kotlin, tested on the JVM. It holds the library and three-way
  merge, the YouTube Music client, WebDAV, sync, and downloads, ported from the
  desktop's Rust modules with the same formats and rules.
- `core/Streams.kt`: [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
  resolves audio streams. It solves YouTube's JavaScript challenges itself, so
  the phone doesn't need yt-dlp or a JavaScript runtime. Audio is fetched in
  bounded byte ranges, because YouTube throttles open-ended requests to about
  twice real time.
- `playback/`: a Media3 `MediaSessionService` running ExoPlayer. Songs resolve
  when they load: the saved file if there is one, otherwise a fresh stream URL.
  Failed streams retry with new URLs and resume where they stopped.
- `ui/`: Jetpack Compose with Material 3, using dynamic color on Android 12 and
  later.

Songs are saved in the app's Music folder
(`Android/data/io.github.oxidance/files/Music`), so they are removed if the app
is uninstalled. The sync password is encrypted with a key held in the Android
Keystore.

## License note

NewPipeExtractor is GPL-3.0, so distributed builds of this app are covered by
the GPL.
