# Earphone Wire Android pairing client

This app reads only the active QQ Music MediaSession after the user enables
Notification Listener access. It does not read notification bodies. It uploads
only the latest valid QQ Music playback snapshot to the configured private
Worker.

## Pairing

1. Install the debug APK and enable Notification Listener access for Earphone
   Wire in Android system settings.
2. In **Secure relay pairing**, enter this Worker origin exactly:
   `https://earphone-wire-mcp.andxiaoqie.workers.dev`
3. Enter the Android upload token locally. It is encrypted with an Android
   Keystore AES-GCM key before it is stored, and is never shown again.
4. Use **Test connection** to check the anonymous `/health` endpoint. It does
   not upload media data. Use **Clear pairing** to remove the encrypted token.

The app permits only `INTERNET` in addition to its existing notification
listener service. Cleartext traffic remains disabled. No storage, microphone,
camera, accessibility, or notification-content permission is requested.

## Reporting behavior

- Only `eventId`, `title`, `artist`, `playbackState`, `playerPackage`, and
  `observedAt` are sent to `POST /api/v1/now-playing` over HTTPS.
- Redirects are disabled. Tokens, song data, and request bodies are never
  logged.
- MediaSession callbacks are debounced briefly. A QQ Music session is also
  re-observed and reported every 60 seconds.
- A retry keeps the same event ID; a new observation receives a new UUID. Only
  one pending in-memory snapshot exists.
- `204` clears the pending snapshot, `401` asks for re-pairing, `409` drops the
  superseded snapshot, and temporary failures use a bounded retry backoff.

## Local verification

Use JDK 17 or later:

```powershell
./gradlew.bat testDebugUnitTest
./gradlew.bat lintDebug
./gradlew.bat assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
