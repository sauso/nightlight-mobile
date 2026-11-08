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
release are not found; the typed address always works. On Android and iOS the saved-servers list shows each server's **name** (what the server
reports at `/api/health`, bounded by `DiscoveryRules.cleanName`, shown as text only) with its address in smaller text below; the
phone keeps names only for servers on that list (`EndpointRules.namesForKnown`) and learns one when a server is saved, when it
is reconnected through its Tailscale address, and at each start while signed in (`addEndpoint`), so servers saved earlier get
theirs without any action. A server that has sent no name is listed by its address, as before.

## Two addresses for one server (Android)

The address you set the app up with stays the **saved** address. If the server has Tailscale and its secure address on, the
web app asks the server for that address after sign-in and hands it to the app (`ServerConfig.addEndpoint`), which keeps it
beside the saved one (SharedPreferences key `tailscale_for:<saved address>`). The app checks it first: it must be exactly
`https://<name>.ts.net`, the page asking must be the saved server (or its Tailscale address), and `/api/health` at the new
address must report the same server id as the page's server (the id is saved with the address, and the address is opened at
launch only if the server there still reports it). Forgetting a server (the ✕ on the setup page) removes its Tailscale address; **Change server** keeps it, so on the setup page a remembered server whose home address does not answer (you are on mobile data) is reconnected through its Tailscale address, once that address answers with the id it was saved for (`EndpointRules.canReconnectViaTailscale`). Only the bundled setup page can use that reconnect (a page on a server cannot). The address is dropped when the saved address is next confirmed from the setup screen (a save, not every launch: the launch gate does not read the home address's id) and a different server answers there (`EndpointRules.pairStillValid`). Off Wi-Fi and Ethernet, with a home-network-only saved address (`EndpointRules.isLocalOnlyAddress`) and a Tailscale address saved, the saved address is waited for about 1 second instead of 3 (`EndpointRules.localWaitMs`, `LocalNetwork`). At launch `MainActivity` probes both addresses at once and
opens the saved one if it answers within 3 seconds, else the Tailscale one (`EndpointChooser`); a quick Tailscale answer
never beats a slower home answer. The rules are unit-tested on the JVM (`EndpointRulesTest`, `EndpointChooserTest`,
`EndpointProbeTest`); the plugin glue and the launch gate need a phone (see the PR's checklist). iOS does the same, with the
limits listed in [Two addresses for one server (iOS)](#two-addresses-for-one-server-ios).

### When you change network (Android)

Leave home with the app open and, when Wi-Fi turns to mobile data, the app moves to the Tailscale address by itself within a few
seconds, with no restart (when a Tailscale address is saved, as above, and the Tailscale app on the phone is connected).
Coming home, it does not move back while you are using it (the Tailscale address keeps working on Wi-Fi). It moves back to the
home address the next time the app comes to the front (opened again, or the screen turned back on), and never while it is in
Picture-in-Picture. It does so a few seconds after the app comes to the front (a settle wait plus the check), so something typed
in those first seconds (a sign-in code, say) can be lost: a known limit. Either move reloads the app onto its home screen, so a video you were watching
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
  the PR's checklist). iOS does not do this yet: after changing network, restart the app.

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
  (Android 6-11) exclude the file AND the web app's own storage (`app_webview`, which holds the web sign-in token of each address);
  `BackupRulesTest` reads them. A restored or transferred phone therefore asks for one sign-in again (and loses the web app's small
  local settings); `allowBackup` stays on so the saved server list (SharedPreferences) is backed up as before, so nothing is retyped.
- Known limits: the Keystore wrapper and the plugin glue have no JVM test (only the phone check proves them); a person with an
  account on the server who controls a device at the plain-http saved address could store their own token; two separate sign-ins stay
  separate. On an old WebView without WebMessageListener, Capacitor's fallback bridge cannot say WHICH FRAME called (`webView.url` is the top page),
  so a child frame under an accepted page could call `saveSession`; Nightlight's own pages use none and its CSP forbids a
  cross-origin one, so this is a conditional gap, stated.

The iPhone app does the same, keeping the copy in the iOS Keychain: see
[One sign-in across the two addresses (iOS)](#one-sign-in-across-the-two-addresses-ios).

## Two addresses for one server (iOS)

The iPhone app uses your server's Tailscale address too: at home it opens the address you set it up with, and away from home
(with the Tailscale app on the phone connected) it opens through Tailscale. **Change server** works away from home, and the saved
list shows each server's name. The rules are the Android ones above, ported case for case: `ServerConfig.addEndpoint` saves the
address only if it is exactly `https://<name>.ts.net`, the page asking is the saved server or its Tailscale address, and both
report the same server id (UserDefaults keys `tailscale_for:<saved address>` and `tailscale_id:<saved address>`, names in
`server_names`). `clear` (Change server) keeps the address; the ✕ on the setup page (`forget`) removes it. Only the bundled setup
page can reconnect a remembered server through it, and the address is dropped when another server answers at the home address on
the next save from the setup screen.

"The page asking" means both the address the app opened and the origin the web view is showing right now
(`EndpointRules.isTrustedPage`, for `addEndpoint` and `removeEndpoint`; the reconnect also checks the web view is on the bundled
page). Capacitor iOS lets the web view open, inside the app, any address that merely starts with the server's
(`https://server.example.attacker.test` for `https://server.example`), so the address the app opened is not enough on its own.

At launch, when a Tailscale address is saved, `MainViewController` probes both addresses at once behind the launch screen and
opens the saved one if it answers within 3 seconds, else the Tailscale one if it answers with the id it was saved for
(`EndpointChooser`); a quick Tailscale answer never beats a slower home answer. Off Wi-Fi and Ethernet (one `NWPathMonitor`
reading at launch; no reading counts as on Wi-Fi), a home-network-only saved address gets about 1 second instead of 3. With no
Tailscale address saved, the launch is exactly as before: no probe, no wait. The 3 s, 2.5 s (each probe) and 1 s are carried over
from Android as judgements, not measurements.

Known limits on iOS:
- **No switch when the network changes.** The address is chosen when the app starts. After leaving home or coming back, close the
  app and open it again to change address.
- **When neither address answers**, the app opens the saved address and shows the "Can't reach your server" screen, as it always
  has on iOS (Android shows the setup screen instead).
- **Capacitor iOS cannot tell which frame called a plugin method.** "The page asking" is judged from the address the app opened and
  the main frame's current origin, so a child frame inside an accepted page counts as that page. Nightlight's own pages use no
  frames and its CSP forbids a cross-origin one, so this is a conditional gap, as for Android's old WebView.
- **Tested**: the rules (`EndpointRules.swift`, the reply parsing in `EndpointProbe.swift`, `EndpointChooser.swift`) are compiled
  on their own with `swiftc` and run in CI (`ios/Tests`, the first step of `build-ios.yml`), with no network. The plugin glue,
  the launch choice and the network reading need an iPhone. Background audio is unchanged.

## One sign-in across the two addresses (iOS)

Sign in once at home, and the iPhone app opens the Tailscale address already signed in, as the Android app does. The two
addresses then share that one sign-in, so signing out on either ends it on both (the server stops accepting it) and removes the
saved copy. The web app's calls are the same three (`ServerConfig.saveSession`, `getSession`, `clearSession`), and so are the
rules, ported case for case from Android
(`SessionRules.swift`, `SessionVault.swift`; see the Android section above for why each one exists): the saved sign-in is handed
ONLY to the page that is the saved Tailscale address, only while the saved address, the Tailscale address and its id are the
ones it was saved under and that page's live `/api/health` id still equals them, and the Tailscale address is bound to it only
when the web app says its own server has just offered that same address. Every call is refused unless the web view is really
showing the origin the app opened. What is different on an iPhone:

- **Where it is kept.** In the iOS Keychain, one item per saved address (service `com.sauso.nightlight.session`). The item is
  readable once the phone has been unlocked after it started, so a page that reloads with the screen locked (audio playing) can
  still sign in. It is never synced through iCloud Keychain, and it is marked "this device only", which Apple documents as not
  moving to a new phone through a backup or a transfer. Android's extra encryption layer (a key in the Android Keystore) has no
  iOS counterpart: the Keychain item is the protection. The app does not change how iOS backs up the web view's own storage,
  where each address keeps its own sign-in as before.
- **Reinstalling starts signed out.** iOS keeps Keychain items when an app is deleted, so the first start of a new install
  removes any saved sign-in under that service before a page can ask for one. A marker in the app's settings (which deleting
  the app does clear) records that this has run. It is written only after the removal finished, so a removal that failed runs
  again at the next start; until it has finished, the sign-in calls answer "no session" and do not save, and they try it
  again themselves (so an earlier install's sign-in can never be read back in the same launch). The first start after updating
  to this version runs it once too, with nothing to remove. **Not yet confirmed on a phone:** deleting an app also clears its
  settings, so a reinstall looks signed out either way and a phone test cannot tell this removal from that; the device log
  says how many items it removed.
- **A locked or busy Keychain changes nothing.** If the Keychain cannot be read right now, the page gets no saved sign-in this
  time and nothing is deleted. The same goes for a sign-out the Keychain refuses (not expected once the phone has been unlocked
  since it started): the copy stays, and the device log shows the Keychain's status code (never the token or the address).
- **Moving between the addresses when the network changes is still Android only.** After leaving home or coming back, close
  the app and open it again.
- **Known limits.** As on Android: someone with an account on your server who controls a device at a plain-http saved address
  could store their own sign-in; two separate sign-ins stay separate. The frame limit above applies. One more, iOS only: the
  page is checked again on the main thread just before the sign-in is handed over, but Capacitor sends the reply as a separate
  step, so a page that navigated in during that last instant would still receive it. The page checks for the sign-in calls use
  the page the web view has finished loading (its back-forward list), not the address of a page that is still loading, which
  `WKWebView.url` can already show; if WebKit behaves otherwise than assumed, the result is "sign in twice, as before", never a
  sign-in handed to the wrong page. This is also unconfirmed on a phone.
- **Tested**: the rules, the vault and the Keychain status handling (`KeychainLogic.swift`, against a fake Keychain that refuses
  a duplicate add as the real one does) run in CI with `swiftc`. The Keychain calls themselves (`KeychainSessionStore.swift`),
  the plugin glue and the clean-up after a reinstall need an iPhone.

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
