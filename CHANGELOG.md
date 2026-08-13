# Changelog

All notable changes to the Nightlight Android app are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning
follows [Semantic Versioning](https://semver.org/) and matches `versionName` in
`android/app/build.gradle` (whose `versionCode` increments on every release). While on
0.x: minor bumps for new features, patch bumps for fixes. History before 0.1.0 exists
only as git history — 0.1.0 is the first tracked release, not the first release.

## [Unreleased]

## [0.8.0] - 2026-08-14

### Added
- **File download to the Downloads folder.** A native `Download` plugin saves exported files (e.g.
  the diagnostics bundle from Settings → Logs) straight into the phone's public Downloads folder via
  MediaStore — no permission needed on Android 10+ — so they're easy to then attach to a GitHub
  issue. Falls back to the share sheet (`@capacitor/filesystem` + `@capacitor/share`) on older
  devices where a direct download isn't allowed. The WebView can't do a browser-style download at all.

### Fixed
- **The hardware back button and the OS edge back-gesture now go back a screen instead of exiting
  the app.** Added the `@capacitor/app` plugin so the back event is dispatched to the web app, which
  steps back through the in-app history (Settings → Camera → Live) via react-router and only leaves
  the app from the first screen. (An earlier native-only attempt couldn't work: Android's WebView
  doesn't track the single-page hash-router's history, so it always fell through to exit.)

## [0.7.2] - 2026-08-11

### Fixed
- **Firebase notifications show the crescent-moon icon, not a white circle.** The status-bar small
  icon for background FCM alerts wasn't registered, so Android fell back to the app icon and rendered
  its opaque shape as a plain white blob. It now uses the proper monochrome crescent-moon silhouette.
- **Switching servers now works after opening from a *Firebase* notification too.** 0.7.1 fixed this
  for Pushover deep links but not Firebase: an FCM notification tap was re-delivered by the push
  plugin on every internal restart (it keys off the notification's message id, which the launch
  intent kept carrying), so after tapping an alert and then changing servers the app looped back to
  the alert's server. Both kinds of launch intent are now consumed once, so changing servers behaves
  normally regardless of how the app was opened.

## [0.7.1] - 2026-08-11

### Fixed
- **Switching servers works after opening from a deep link.** When the app was cold-started by
  tapping an alert's deep link, trying to change servers afterwards just reloaded the same server
  instead of going to the setup screen (the launch deep link was being reprocessed on every internal
  restart). The deep link is now consumed once, so switching servers behaves normally. (Closing and
  reopening the app was the previous workaround.)

## [0.7.0] - 2026-08-10

### Added
- **Alerts open the correct server (multi-server deep links).** If you point the app at more than one
  Nightlight server, tapping an alert now switches the app to **the server that sent it** before
  showing the nursery — a production alert tapped while the app was last on staging now opens
  production, not staging. Works for both a tapped Pushover notification (native `nightlight://…`
  deep link carrying `?server=`) and a Firebase alert (server in the payload); if the tapped server
  matches the one already loaded, nothing switches. Requires the matching server change (nightlight
  server app) that stamps each alert with its own address.

## [0.6.0] - 2026-08-09

### Added
- **Deep-link support (`nightlight://`).** Tapping a Pushover motion-alert notification now opens the
  Nightlight app (Android and iOS), bringing it straight to the front. Pairs with Pushover
  notifications added in the nightlight server app.

## [0.5.0] - 2026-08-04

### Fixed
- **An offline/stopped server now drops you to the setup screen — not a blank screen or a dead error
  page.** At launch the app checks the saved server is reachable (a quick health check, short
  timeout); if it isn't, it loads the bundled **setup screen** — a local page where the native bridge
  works, so **Connect** and your **saved servers** actually respond — instead of hanging the WebView
  on a long connection timeout, or Capacitor's error page (into which the bridge isn't injected, so
  its "Try again" / "Use a different server" buttons could never work). That broken error page is
  removed. Also removed Firebase's auto-init provider (Firebase is configured at runtime now).

### Added
- **Push notifications.** The app can now receive **motion/detection alerts** from your server while
  backgrounded or closed (via FCM). Because Nightlight is self-hosted, the app ships with **no
  Firebase project baked in** — it initializes Firebase at runtime from **your own server's** config,
  so every install uses its own Firebase project (the released APK is generic). With notifications
  enabled it asks for permission on sign-in and registers the device; tapping an alert opens the app.
  Requires the server configured with Firebase credentials (see the app's Notifications docs). Android
  only for now.

## [0.4.3] - 2026-08-03

### Added
- **Microphone permission for two-way audio (talk-back).** The app now requests mic access so the
  talk button can capture it — Android via `RECORD_AUDIO`, iOS via `NSMicrophoneUsageDescription`
  (WKWebView blocks the mic entirely without it). Prompted the first time you press talk. Note:
  talk-back needs the app pointed at the server over **HTTPS** (WebViews only allow microphone access
  in a secure context) — camera *viewing* still works over plain HTTP.

## [0.4.2] - 2026-08-02

### Fixed
- The background-listening notification now shows a proper crescent-moon-and-star icon in the
  status bar instead of a plain white circle. Android renders the status-bar icon as a solid
  silhouette from its alpha, so the full-colour launcher icon came through as a featureless blob;
  a dedicated monochrome icon fixes it.

## [0.4.1] - 2026-07-27

### Added
- **Pause/Resume** on the background-listening notification, next to Stop. It mutes/unmutes
  the stream audio (via the web app) rather than dropping the connection, so resuming is
  instant. The notification's button and text toggle between Pause and Resume.

### Fixed
- The on-video overlay buttons (mute / settings / fullscreen) are now hidden while a camera
  is floating in Picture-in-Picture — the app reports PiP enter/leave to the web app so it
  can clear them from the tiny window. Pairs with nightlight 0.4.6.

## [0.4.0] - 2026-07-27

### Added
- Picture-in-Picture support. Tapping the PiP button on a camera now floats the app into a
  small always-on-top window (previously the button did nothing on Android — the web
  `<video>` PiP API isn't supported in the WebView, so it now uses Android's native
  Activity PiP via a new `Pip` plugin). It also enters PiP automatically when you leave the
  app while the live view is showing, so a camera keeps floating while you use other apps.
  Android floats the whole app window rather than a single tile — an OS limitation, so
  whatever camera view is on screen is what floats. Pairs with the web app's routing added
  in nightlight 0.4.2.

## [0.3.0] - 2026-07-23

### Added
- The app now remembers every server it has successfully connected to. The setup
  screen lists them as one-tap choices (with the option to forget one), and
  "Change server" returns to that list rather than a blank slate — switching
  between servers no longer means retyping addresses.

## [0.2.0] - 2026-07-23

### Added
- Plain-http and IP-address servers are now supported (e.g. `192.168.1.50:4000`),
  for installs exposed directly on the LAN without a reverse proxy/SSL in front. A
  bare address tries https first and falls back to http; an explicit scheme is
  respected as typed.

### Changed
- Cleartext traffic re-enabled at the Android level to allow the above. The setup
  flow still prefers https whenever the server answers on it.

## [0.1.3] - 2026-07-23

### Changed
- Setup and error screens now use the real app icon and the same typography as
  the web app's login screen, instead of a moon emoji.

## [0.1.2] - 2026-07-23

### Fixed
- First-run setup screen rendering as a wall of garbled text: the page was
  accidentally saved UTF-16 encoded (inherited from the placeholder file it
  replaced), which the WebView read as UTF-8 and displayed as raw bytes instead
  of parsing. Re-encoded as UTF-8.

## [0.1.1] - 2026-07-23

### Fixed
- Background listening dying after ~15-30 minutes with the screen off (observed as
  both camera streams dropping simultaneously with the server perfectly healthy):
  - The wifi lock used a mode (`WIFI_MODE_FULL_LOW_LATENCY`) that Android only
    honors while the app is foregrounded with the screen on — silently useless for
    background listening. Replaced with the mode that actually holds wifi awake
    from the background.
  - Doze ignores wake locks and cuts network for apps not exempt from battery
    optimization. The app now asks for the exemption (system consent dialog) the
    first time background listening starts; declining is respected and never
    re-prompted.

## [0.1.0] - 2026-07-23

### Added
- First-launch server setup (like the Home Assistant app): the APK no longer has a
  server address baked in. A bundled setup screen asks for it once, verifies a
  Nightlight server actually answers there before saving, and every later launch
  connects straight to it.
- "Can't reach your server" screen when the saved server is unreachable at launch,
  with retry and switch-server options — replaces the dead WebView error page.
- `ServerConfig` Capacitor plugin (get/save/clear/restart) backing both of the above
  and the web app's new "Change server" menu item (nightlight 0.1.0).

### Changed
- Cleartext (http://) traffic disabled in the WebView — server addresses must be
  HTTPS.

### Notes
- Updating from a pre-0.1.0 install shows the setup screen once (the previously
  hardcoded address is not migrated).

[Unreleased]: https://github.com/sauso/nightlight-mobile/compare/v0.6.0...HEAD
[0.6.0]: https://github.com/sauso/nightlight-mobile/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/sauso/nightlight-mobile/compare/v0.4.3...v0.5.0
[0.4.3]: https://github.com/sauso/nightlight-mobile/compare/v0.4.2...v0.4.3
[0.4.2]: https://github.com/sauso/nightlight-mobile/compare/v0.4.1...v0.4.2
[0.4.1]: https://github.com/sauso/nightlight-mobile/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/sauso/nightlight-mobile/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/sauso/nightlight-mobile/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/sauso/nightlight-mobile/compare/v0.1.3...v0.2.0
[0.1.3]: https://github.com/sauso/nightlight-mobile/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/sauso/nightlight-mobile/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/sauso/nightlight-mobile/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/sauso/nightlight-mobile/releases/tag/v0.1.0
