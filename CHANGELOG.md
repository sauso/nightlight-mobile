# Changelog

All notable changes to the Nightlight Android app are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning
follows [Semantic Versioning](https://semver.org/) and matches `versionName` in
`android/app/build.gradle` (whose `versionCode` increments on every release). While on
0.x: minor bumps for new features, patch bumps for fixes. History before 0.1.0 exists
only as git history — 0.1.0 is the first tracked release, not the first release.

## [Unreleased]

### Added
- **Android: find your server after its local address changes.** When a saved private IPv4 address stops answering,
  the app searches Wi-Fi for the same server id and asks before saving a new address. Existing saved servers learn
  their id when they next answer. Accepting the move requires one sign-in at the new address.
  Switching to another server now also stops background listening that was running for the old one.
- **iOS: Look for my Nightlight.** The setup screen can find a server on your Wi-Fi and check it again when you tap
  Connect. Search needs Local Network access and finds private IPv4 addresses only. Networks that isolate devices or
  block multicast may find nothing; you can still type the address, but a local address also needs Local Network
  access. A Tailscale address outside the local network is unaffected. The iOS search and permission flow still need
  a test on a phone.
- **iOS: one sign-in for both of your server's addresses.** On an iPhone, the home address and the Tailscale address used to
  ask for a sign-in each. The app now keeps your sign-in in the iPhone's Keychain and hands it to the Tailscale address (only
  that address) the first time you open it there, so signing in once at home is enough, as on Android. It is kept for the
  Tailscale address only if your own server has just offered that same address. Signing out, or a sign-in the server refuses,
  removes it; a wrong password does not. It is never synced through iCloud Keychain and does not move to another phone through
  a backup or a transfer, and the app also clears its saved sign-in on the first start of a new install (iOS keeps Keychain
  items when an app is deleted). The home address never receives it
  (it is usually plain `http`), so signing in on the Tailscale address first still asks once at home.
  Like the Android version, it needs the Nightlight server release that goes with it; with an older server each address asks
  you to sign in, as before.
- **iOS: the app moves to your server's other address when you change network.** Leave home with the iPhone app open and,
  when Wi-Fi turns to mobile data, it now switches to your server's Tailscale address by itself within a few seconds,
  instead of staying on a home address that cannot answer until you close and reopen the app. Coming home, it stays on the
  Tailscale address while that keeps working (see Changed below). A move it does not have to make, from a home address that
  works up to the Tailscale address, waits for the next time you open the app or unlock the phone with the app in front (a few
  seconds after it appears, so something typed in those first seconds can be lost), and never happens while a video is
  already floating in Picture-in-Picture. Each switch reloads the app onto its home screen, so a video you were
  watching starts again, and the screen can pause for up to about 3 seconds while it picks the address. If the network
  keeps dropping in and out, the app switches at most 3 times in 5 minutes. It works while the app is on screen: with audio
  playing in the background on a network that has stopped working, nothing is rescued until you next open the app. Needs
  the Tailscale address the iPhone app learned in 0.9.0; with none saved, nothing changes.

### Changed
- **iOS: the app now opens your server's secure Tailscale address first when it has one, and your saved address if that does
  not answer.** Nothing changes without a secure address. Before, the saved address won whenever it answered, which on a phone
  hotspot with Tailscale on kept the app on the plain `http` address, where two-way talk cannot work. When the saved address
  answers first, the app waits up to 1 second for the secure one (a quarter of a second when the phone has no VPN on). If the
  secure address is slow to wake up, the app opens the saved address and moves up to the secure one the next time you open it.
  When the network changes, the app now leaves whichever address has stopped answering, in either direction, and never moves
  you off a secure address that still works. If you signed in before the app kept one sign-in for both addresses, the secure
  address may ask you to sign in once.
- **iOS: the app asks once before it uses a new secure address.** The first time it learns your server's secure address from
  a plain `http` address, or when that address changes, it asks "Use this secure address?" and shows the name. Only accept it
  if you just turned it on in your server's Settings → Remote Access. If you choose "Don't use it", nothing changes: the app
  keeps the addresses it already had, and does not ask about that address again for 7 days. It asks at most once every 10
  minutes while it is open, so a question can wait for the next time you open the app.
- **Android: the app now opens your server's secure Tailscale address first when it has one, and your saved address if that
  does not answer.** Nothing changes without a secure address. With Tailscale switched off on the phone, opening the saved
  address waits at most about a quarter of a second longer (a second if another VPN is on). If Tailscale is slow to answer
  when the app starts, the app opens the saved address and moves to the secure one the next time you open it. While you use
  the app it only switches address when the page on screen stops answering. If you signed in before the app kept one sign-in
  for both addresses, the secure address asks you to sign in once. The first time the app learns a secure address (or a
  different one) from your server's plain `http` address, it asks you to confirm it and shows the full name: tap Use it only
  if you have just turned on the secure address in your server's Settings → Remote Access and the name matches. Don't use it
  keeps what you had before (your saved address, or the secure address already saved), and the app does not ask about that
  address again for a week. It asks at most once every 10 minutes, so another question can wait up to that long. A server can
  now be forgotten only from the setup screen.

## [0.9.0] - 2026-11-08

### Added
- **iOS: a second address for your server, through Tailscale, and saved servers show their name.** The iPhone app now
  does what the Android app does with your server's Tailscale address. It learns the address by itself after you sign in
  (a short message says so the first time), and when the app starts it tries both addresses at once: it opens the address
  you set it up with if that answers within about 3 seconds (about 1 second when the phone is not on Wi-Fi and that address
  is a home-network one), and the Tailscale address if it does not. You need the Tailscale app on the phone, signed in to
  the same Tailscale network as the server. **Change server** keeps the Tailscale address, so away from home you can tap
  your saved server on the setup screen and the app reconnects through it; the **✕** on a saved server forgets it. The
  saved-servers list shows each server's name, with its address in smaller text underneath. Two things are still Android
  only: one sign-in for both addresses (on an iPhone the Tailscale address asks you to sign in once of its own), and moving
  between the addresses when the network changes (on an iPhone, close and reopen the app). If neither address answers,
  the app shows the "Can't reach your server" screen, as before. With no Tailscale address saved, the app starts exactly
  as before. Like the Android version, it needs a Nightlight server from the release that goes with it.
- **Android: the app moves to your server's other address when you change network.** Leave home with the app open and,
  when Wi-Fi turns to mobile data, the app now switches to your server's Tailscale address by itself within a few
  seconds, instead of staying on a home address that cannot answer until you restart the app. Coming home, it does not
  switch back while you are using it (the Tailscale address keeps working on Wi-Fi): it moves back to the home address
  the next time you open the app or turn the screen back on (a few seconds after it appears, so something typed in those
  first seconds can be lost), and never while it is in Picture-in-Picture. Each switch
  reloads the app onto its home screen, so a video you were watching starts again. If the network keeps dropping in and
  out, the app switches at most 3 times in 5 minutes. It works while the app is on screen: listening with the screen off
  on a network that has stopped working is not rescued until you next open the app. Needs the Tailscale address
  described below. iOS does not have this yet: on an iPhone, close and reopen the app after changing network.
- **Android: saved servers show their name.** On the setup screen, each saved server is now listed by the name your server
  reports, with its address in smaller text underneath, instead of only the address. A server saved earlier gets its name
  the next time you open the app signed in; a server that reports no name is shown by its address, as before.
- **Android: one sign-in for both of your server's addresses.** The home address and the Tailscale address used to ask
  for a sign-in each. The app now keeps your sign-in, encrypted with a key that never leaves the phone's secure
  keystore, and hands it to the Tailscale address (only that address) the first time you open it there, so signing
  in once at home is enough. It is kept for the Tailscale address only if your own server has just offered that same address
  (so an address another page put in the app never receives it). Signing out, or a sign-in the server refuses, removes it; a wrong password does not. It is
  left out of Android's cloud backup and of a phone-to-phone transfer (the web app's own stored sign-in is now left out too, see Changed). The home
  address never receives it (it is usually plain `http`), so signing in on the Tailscale address first still asks once
  at home. Needs the server release that goes with it. iOS does not have this yet: on an iPhone each address asks you to
  sign in.
- **Android: a second address for your server, through Tailscale.** If your Nightlight server has Tailscale and its
  secure address turned on, the app now learns that address by itself after you sign in (a short message says so the
  first time) and keeps it beside the address you set the app up with. When the app starts it tries both at once: it
  opens the address you set it up with if that answers within about 3 seconds, and the Tailscale address if it does
  not, so at home nothing depends on Tailscale and away from home it opens through Tailscale. You need the Tailscale
  app on the phone, signed in to the same Tailscale network as the server. The two addresses are different web
  addresses; see the entry above for signing in once. A session that started on the Tailscale address stays on it while you use
  the app, and moves back to the home address the next time the app comes to the front where the home address answers
  (see the first entry above). Turning Tailscale or the secure address off on the server removes the saved address the next
  time you open the app. **Change server** keeps it, so when you are away from home you can tap your saved server on
  the setup screen and the app reconnects through the Tailscale address (the saved home address cannot answer on mobile
  data); the **✕** on a saved server forgets the Tailscale address too. If a different server later answers at your home
  address, the old Tailscale address is dropped the next time you connect to that home address from the setup screen (not
  at every launch). Needs a Nightlight server from the release that goes with it; with an older server the app
  works as before. iOS now has this too (see the iOS entry above), apart from the one sign-in and the switch when the
  network changes.
- **Android: Look for my Nightlight.** The first-run screen can now find your Nightlight server on your Wi-Fi,
  so you usually don't need to type its address. Tap **Look for my Nightlight**: each server that answers is
  shown with its name, its address and the start of its ID. Nothing is saved or switched until you tap
  **Connect**, and Connect checks again that the address still belongs to the server you picked. It works
  when your phone and the server are on the same network. It does not work across separate networks or VLANs
  unless your router passes these announcements along, on a guest Wi-Fi that keeps devices apart, or for a
  server on Docker's default bridge network. It only finds servers on private IPv4 addresses (10.x,
  172.16–31.x, 192.168.x): a network that uses another range, or IPv6 only, needs the typed address. It needs
  a Nightlight server that announces itself (the first server release after 0.45.0); an older server is
  simply not found. A found server connects at `http://<its address>` over your home network, without
  encryption. That is a different web address from any https address you may already use, so you will be
  asked to sign in again (with your second step, if you use one), and some features, like talking through the
  microphone, may need the https address. Typing an address and the saved-servers list work as before. iOS
  does not have this yet.

### Changed
- **Android: the web app's own stored sign-in is no longer included in Android's backup or phone-to-phone transfer.** The web app keeps
  a sign-in token for each address it has opened in the app's web storage, and until now that folder was copied into Google's
  backup and onto a new phone, where the token worked until it expired. It is now left out, as the encrypted copy already was
  (Android's guidance is to keep credentials out of backups). On a restored or transferred phone you sign in once more, and the
  web app's own small local settings start fresh; your saved servers are still restored, so there is nothing to retype. Phones
  that already took a backup keep the older copy until it is replaced. Not tested on a device: the unit test checks the rules
  files, not Android's restore.
- **Android: the Tailscale fallback opens in about a second when you are not on Wi-Fi.** If the app was set up with a
  home-network address (one starting 10., 172.16 to 172.31 or 192.168, or a name ending in .local), a Tailscale
  address is saved, and the phone is not on Wi-Fi or Ethernet, the app waits about 1 second for the home address
  instead of 3 before it opens through Tailscale (a home address cannot answer on mobile data). A home address that
  does answer in that time still wins. Addresses that are reachable from anywhere (a domain name, say), and
  servers with no Tailscale address saved, keep the full wait.
- **New look to match the Nightlight web app.** The launcher icon, the splash screen (Android and iOS) and the
  app's own first-run and "Can't reach your server" screens now use the new logo (a periwinkle crescent and a
  sleeping baby on navy), the web app's dark colours and its typefaces (Inter and Nunito, bundled with the
  app, so the two screens look like the app with no network needed). The old gold-on-navy artwork and the placeholder
  splash screen are gone. Your phone may keep showing the old launcher icon until the app is reinstalled or
  the launcher refreshes. The pictures are generated from one source image by `scripts/make-branding.py`.

### Fixed
- Fullscreen camera views now hide the Android status and navigation bars so the on-screen buttons
  can be pressed. Swipe from an edge to show the bars temporarily. This needs the matching server/web
  update to call the native plugin; there is no iOS change.

## [0.8.3] - 2026-10-24

### Changed
- **Android: a tapped link that names another server now asks before switching.** A Pushover, ntfy or
  Gotify alert that comes from a different Nightlight server than the one the app is showing no longer
  changes the app's server on its own. The app now hands the request to the Nightlight web app, which
  asks **Switch server?** and only offers servers you have already saved in the app; an address the app
  doesn't know is refused. This pairs with the first Nightlight server release after 0.43.0 (the question
  is asked by the web app the server delivers): on an older server a link that names another server is
  simply ignored, and the app's own (Firebase) alerts only ask once the server is updated too. Nothing
  contacts the named address until you tap Switch. If your saved server can't be reached when you tap a
  link, the request is dropped after two minutes rather than asked about later.

### Fixed
- **Android: a malformed `nightlight:` link no longer closes the app.** A link without the `//` after the
  colon made the app stop, background listening included. It is now ignored.

## [0.8.2] - 2026-08-24

### Fixed
- **An unreachable server no longer strands the app on the browser's raw "webpage not available" error.**
  Previously, if the server became unreachable while the app was already open (Wi-Fi/data dropped, or the
  WebView reloaded on resume with no connection), the WebView fell through to the system error page and the
  only way out was to force-quit. The app now drops back to the connect screen, where your saved server is
  one tap away to retry — or you can switch to a different one.

### Changed
- **The server-setup screen now uses the same gold accent as the rest of the app** (it was a slightly
  paler shade).

## [0.8.1] - 2026-08-16

### Fixed
- **iOS: the committed Swift Package manifest now lists the plugins.** `ios/App/CapApp-SPM/Package.swift`
  had been stale since the platform was scaffolded (zero plugins), so a local `xcodebuild` without a
  preceding `npx cap sync ios` would produce an app missing Filesystem/Share/App/PushNotifications. CI
  regenerates it on every build (so released IPAs were always correct), but the tracked file now matches
  what `cap sync` generates.

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
