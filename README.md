# Nightlight Mobile

Capacitor mobile shell for [Nightlight](https://github.com/sauso/nightlight) —
**Android and iOS**. It displays the Nightlight web app (loaded live from your own
server, whose address you enter in-app on first launch — nothing is baked in) and
adds the native pieces the web app can't do on its own: keeping audio playing while
the screen is off / the app is backgrounded, and runtime server selection.

On Android the first-run screen can also find your server on your home Wi-Fi (**Look for my Nightlight**), so
you usually don't need to type the address. Nothing is saved until you tap Connect. It finds servers on private
IPv4 addresses only (10.x, 172.16–31.x, 192.168.x), and a found server opens at its `http://` address, which is
separate from any https address you use, so you sign in again there. Servers older than the matching Nightlight
release are not found; the typed address always works.

## Two addresses for one server (Android)

The address you set the app up with stays the **saved** address. If the server has Tailscale and its secure address on, the
web app asks the server for that address after sign-in and hands it to the app (`ServerConfig.addEndpoint`), which keeps it
beside the saved one (SharedPreferences key `tailscale_for:<saved address>`). The app checks it first: it must be exactly
`https://<name>.ts.net`, the page asking must be the saved server (or its Tailscale address), and `/api/health` at the new
address must report the same server id as the page's server (the id is saved with the address, and the address is opened at
launch only if the server there still reports it). Forgetting a server (the ✕ on the setup page) removes its Tailscale address; **Change server** keeps it, so on the setup page a remembered server whose home address does not answer (you are on mobile data) is reconnected through its Tailscale address, once that address answers with the id it was saved for (`EndpointRules.canReconnectViaTailscale`). Only the bundled setup page can use that reconnect (a page on a server cannot). The address is dropped when the saved address is next confirmed from the setup screen (a save, not every launch: the launch gate does not read the home address's id) and a different server answers there (`EndpointRules.pairStillValid`). Off Wi-Fi and Ethernet, with a home-network-only saved address (`EndpointRules.isLocalOnlyAddress`) and a Tailscale address saved, the saved address is waited for about 1 second instead of 3 (`EndpointRules.localWaitMs`, `LocalNetwork`). At launch `MainActivity` probes both addresses at once and
opens the saved one if it answers within 3 seconds, else the Tailscale one (`EndpointChooser`); a quick Tailscale answer
never beats a slower home answer. The rules are unit-tested on the JVM (`EndpointRulesTest`, `EndpointChooserTest`,
`EndpointProbeTest`); the plugin glue and the launch gate need a phone (see the PR's checklist). iOS has no launch gate and
no `addEndpoint`, so it is unchanged.

### When you change network (Android)

Leave home with the app open and, when Wi-Fi turns to mobile data, the app moves to the Tailscale address by itself within a few
seconds, with no restart (when a Tailscale address is saved, as above, and the Tailscale app on the phone is connected).
Coming home, it does not move back while you are using it (the Tailscale address keeps working on Wi-Fi). It moves back to the
home address the next time the app comes to the front (opened again, or the screen turned back on), and never while it is in
Picture-in-Picture. Either move reloads the app onto its home screen, so a video you were watching
starts again. Sign-in carries across as described in the next section: moving back home asks you to sign in only if you never
signed in on the home address.

How it works: while the app is in the foreground, `NetworkRoamWatcher` listens for changes to the phone's default network (a new
network, a lost one, or a change between Wi-Fi, mobile data, Ethernet and VPN). It waits about 2 seconds for the network to
settle, then makes the same choice the launch makes (`MainActivity.chooseAddress`). If that picks the other address from the page
on screen, the activity is recreated, which re-runs the launch. Coming to the front runs the same check once. The saved address
is never rewritten. Only a page on one of the two addresses is moved: the setup page and the "Can't reach your server" page are
left alone, since they already offer a retry and your saved servers.

- **Nothing answering yet** (Tailscale still reconnecting, say): it checks again at about 3, 6, 12 and 25 seconds, then waits for
  the next network change or the next time the app comes to the front.
- **No reload loops**: a move needs at least 20 seconds since the app last reloaded itself (after a page failed to load, or a
  move), and at most 3 moves happen in any 5 minutes. That limit lifts by itself as the 5 minutes roll on; a move it holds back
  waits for the next network change or the next time the app comes to the front. The existing reload after a page fails to load
  keeps its 8-second gap, now counted from either kind of reload (`RecreateLog`).
- **Not covered**: the watcher runs only while the app is in the foreground. Listening with the screen off on a network that has
  stopped working is not rescued; the app chooses again when you next bring it to the front. All the numbers above are
  judgements, not measurements.
- **Tested**: the rules are unit-tested on the JVM (`RoamRulesTest`). The watcher and the `MainActivity` glue need a phone (see
  the PR's checklist). iOS is unchanged.

## One sign-in across the two addresses (Android)

The web app keeps its sign-in token in `localStorage`, which is per web origin, so the saved address and the Tailscale address would
each ask. The app keeps a second, encrypted copy (`SessionVault`: AES-256-GCM with a non-exportable Android Keystore key, the file
`nightlight_session`, one entry per saved address, the saved address as the GCM additional data). The web app saves it after sign-in
(`ServerConfig.saveSession`), asks for it on a page with no token (`getSession`) and clears it on sign-out or when the server
refuses that exact token (`clearSession`). The rules are in `SessionRules` and are JVM-tested:
- **A server's id is public, so it proves nothing.** The saved session is handed ONLY to the page that is the saved Tailscale address
  (never the saved address, http or https), and only while the saved address, the Tailscale address and the Tailscale id still equal
  the ones the entry was stamped with, and that page's live `/api/health` id equals them.
- The page cannot choose an id or an address: the entry is keyed and stamped from what the app itself saved, and the stamp keeps the
  Tailscale address only when the web app says its own server just offered that same one (`saveSession({token, tailscale})`,
  `SessionRules.stampFor`): `addEndpoint` can be called by the page at the saved address, so a planted address must never be the one a
  later sign-in is bound to. Every call is refused unless the WebView's real URL has the origin the bridge was built for.
- All vault work runs on one first-in-first-out thread. The cipher makes its own IV (a Keystore key refuses a supplied one); a lost
  key is replaced; a busy keystore deletes nothing.
- `res/xml/data_extraction_rules.xml` (Android 12+: BOTH `<cloud-backup>` and `<device-transfer>`) and `res/xml/backup_rules.xml`
  (Android 6-11) exclude the file; `BackupRulesTest` reads them. `allowBackup` stays on so the saved server list is backed up as before.
- Known limits: the Keystore wrapper and the plugin glue have no JVM test (only the phone check proves them); a person with an
  account on the server who controls a device at the plain-http saved address could store their own token; two separate sign-ins stay
  separate. The web app's own storage (`app_webview`) is not excluded from backup (it holds the existing per-origin token). On an
  old WebView without WebMessageListener, Capacitor's fallback bridge cannot say WHICH FRAME called (`webView.url` is the top page),
  so a child frame under an accepted page could call `saveSession`; Nightlight's own pages use none and its CSP forbids a
  cross-origin one, so this is a conditional gap, stated.

## Branding

The launcher icons, splash screens and the logo on the first-run / error pages are generated from
`branding/logo-source.png` (the same 1024 px logo the web app uses, in `nightlight/frontend/branding/`) by
`python scripts/make-branding.py` (needs [Pillow](https://pypi.org/project/pillow/)). Change the logo by replacing that
file and running the script; the colours of the two native pages are set at the top of `www/index.html` and `www/error.html`.
Those pages use the web app's typefaces, Inter (400, 600) and Nunito (800), as local files in `www/fonts/` (the same
`@fontsource` 5.3.0 files the web app bundles, Latin only; their SIL Open Font licences sit beside them).

## Platforms

- **Android** — signed APK, built in CI and attached to each GitHub release.
- **iOS** — Capacitor `ios/` project. Builds for the iOS Simulator in CI; an unsigned
  device IPA can also be produced for sideloading with a free Apple ID. App
  Store / TestFlight distribution requires an Apple Developer account.

Both platforms share the same web UI (from `nightlight/frontend`) and the same
JS↔native bridge, with the native plugins implemented per platform (Kotlin for
Android, Swift for iOS).

## Push notifications

The app can show a phone notification when a camera sees motion, even while closed. Delivery into the
Nightlight app itself uses **your own Firebase project** (nothing is baked into the APK) — see the
**[Firebase walkthrough](docs/push-notifications.md)**. Alternatively the server can send the same alerts
through **Pushover, ntfy, or Gotify**, which arrive in those providers' own apps (handy on iOS); see the
server-side **[notifications guide](https://github.com/sauso/nightlight/blob/main/docs/notifications.md)**.

For issues, feature requests, and support, please use the main project repo:
**https://github.com/sauso/nightlight**
