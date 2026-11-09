# Nightlight Mobile

Capacitor mobile shell for [Nightlight](https://github.com/sauso/nightlight) —
**Android and iOS**. It displays the Nightlight web app (loaded live from your own
server, whose address you enter in-app on first launch — nothing is baked in) and
adds the native pieces the web app can't do on its own: keeping audio playing while
the screen is off / the app is backgrounded, and runtime server selection.

On Android and iOS the first-run screen can also find your server on your home Wi-Fi (**Look for my Nightlight**), so
you usually don't need to type the address. Nothing is saved until you tap Connect. It finds servers on private
IPv4 addresses only (10.x, 172.16–31.x, 192.168.x), and a found server opens at its `http://` address, which is
separate from any https address you use, so you sign in again there. Servers older than the matching Nightlight
release are not found; you can type the address instead. On iOS, a typed home-network address also needs Local Network
access; a Tailscale address outside the local network is unaffected. On Android and iOS the saved-servers list shows each
server's **name** (what the server
reports at `/api/health`, bounded by `DiscoveryRules.cleanName`, shown as text only) with its address in smaller text below; the
phone keeps names only for servers on that list (`EndpointRules.namesForKnown`) and learns one when a server is saved, when it
is reconnected through its Tailscale address, and at each start while signed in (`addEndpoint`), so servers saved earlier get
theirs without any action. A server that has sent no name is listed by its address, as before.

## Look for my Nightlight (iOS)

On the setup screen, tap **Look for my Nightlight** to see servers that answer on your Wi-Fi. Tap **Connect** to save
one. The app checks its address again when you tap; a server found on the network is a suggestion, not proof that it
belongs to you.

iOS needs Local Network access. The permission prompt appears the first time the app uses the local network, which may
have happened before you search, so you may not see it again. If access is off, the search shows how to turn it on in
**Settings > Privacy & Security > Local Network**. Typing a local address also needs this permission. A Tailscale
address outside the local network is unaffected.

Search finds private IPv4 addresses only. It finds nothing on IPv6-only networks, or when the Wi-Fi blocks multicast
or isolates devices from one another. The iOS search, its permission prompt and denial message, and discovery across
different home networks still need a test on a phone; the Simulator build alone cannot confirm them.
When nothing is found, the screen shows a one-line technical summary you can share when asking for help: counts and
error codes only, with no address or name.

## If your server's address changes (iOS)

On Wi-Fi, the app makes one background health request when it starts or returns to the foreground. If the saved local
address no longer answers, it searches for the same server for up to 10 seconds. When it finds a different private IPv4
address, a native prompt shows the saved and new addresses, the server name and an eight-character code. The id, name and
code are public identifiers, not proof that the server is yours: check the new address against your server or router
before choosing **Use new address**. **Not now** leaves the saved address alone and quiets that candidate for seven days.
A server saved before this release learns its id on the first start when its saved address still answers. After a move,
sign in once at the new address.

Known limits: another device taking the saved address is not detected before the app opens it; this requires a separate
launch-path check. A saved hostname or `https://` address is not moved by this search. IPv6-only networks, Wi-Fi that
blocks multicast or isolates devices, and servers hidden behind a Docker bridge may not be found. A server with no id
learned before it moves (and no verified Tailscale pair) needs **Look for my Nightlight** on the setup screen. Discovery
offers one card per id, so a false first card can hide a real one for that search. The moved-address prompt and reconnect
still need confirmation on a phone.

## Two addresses for one server (Android)

The address you set the app up with stays the **saved** address. If the server has Tailscale and its secure address on, the
web app asks the server for that address after sign-in and hands it to the app (`ServerConfig.addEndpoint`), which keeps it
beside the saved one (SharedPreferences key `tailscale_for:<saved address>`). The app checks it first: it must be exactly
`https://<name>.ts.net`, the page asking must be the saved server (or its Tailscale address) and really be showing that address, and `/api/health` at the new
address must report the same server id as the page's server (the id is saved with the address, and the address is opened at
launch only if the server there still reports it). Forgetting a server (the ✕ on the setup page) removes its Tailscale address; **Change server** keeps it, so on the setup page a remembered server whose home address does not answer (you are on mobile data) is reconnected through its Tailscale address, once that address answers with the id it was saved for (`EndpointRules.canReconnectViaTailscale`). Only the bundled setup page can use that reconnect (a page on a server cannot). The address is dropped when the saved address is next confirmed from the setup screen (a save, not every launch: the launch gate does not read the home address's id) and a different server answers there (`EndpointRules.pairStillValid`).

**The app asks once before it saves a new secure address.** When the app learns a secure address for the first time, or a
different one, while the page on screen is a plain `http` address, it asks "Use this secure address?" and shows the saved
address the offer came from and the full secure name. Tap **Use it** only if you have just turned on the secure address in your
server's Settings → Remote Access and the name matches; the web app's own "saved" notice follows. Why: a saved secure address
now opens ahead of the saved one, and the server id the app checks is public, so a device that answers at a plain `http` address
(where the network can be faked) could otherwise offer its own address. The details (`EndpointRules.needsConfirmation`,
`PromptGate`):
- **Don't use it** keeps what was saved before: the saved address only, or, when the offer was a change, the secure address
  already saved. The app does not ask about that same address again for **7 days**; then it asks once more. A different
  address is asked about the next time it is offered, once the 10-minute limit below allows.
- The back button and a tap outside do not close the question, so only a tap answers it. If it gets no answer within
  **2 minutes**, or the app closes while it shows, nothing is saved or remembered: the next start asks again. At most one
  question shows at a time, and at most one every **10 minutes** (counted from when one was shown), so a question about a
  different address, or the next start's question, can wait up to 10 minutes.
- Never asked: an address that is already saved, and an offer made from an `https` page (including the secure address itself),
  which the network cannot fake. The app decides this again just before it saves, on the page shown then. Forgetting the server
  (the ✕ on the setup screen; only the setup screen can do it) forgets a decline too.
- The three numbers are judgements, not measurements. Known limits: accepting a hostile address leaves you exposed as before;
  the question cannot prove the name is yours, it only shows it and says when to accept; a phone that already holds a secure
  address is not asked about it again.

**Which address opens: Tailscale first.** When a Tailscale address is saved, the app opens it whenever it answers, and the
saved address only when it does not. (It used to be the other way round. On a phone hotspot with Tailscale on, the plain `http`
saved address still answered through Tailscale, so the app never used the secure address, which two-way talk needs.) At launch
`MainActivity` probes both addresses at once, for at most 3 seconds in all (`EndpointChooser`):
- a Tailscale answer, with the id it was saved for, opens Tailscale whenever it comes within those 3 seconds;
- a saved answer waits a short **grace** for Tailscale, counted from the start: **1 second**, or **0.25 seconds** when the phone
  has no VPN at all (`EndpointRules.tailscaleGraceMs`, `VpnPresent`). A Tailscale failure (refused, no such name, a certificate
  error) ends the grace at once. Both numbers are judgements, not measurements: Tailscale that is on and healthy answers well
  inside a second, and with Tailscale off the saved address should not wait more than a quarter of a second. The VPN reading
  can only shorten the wait (if it cannot be read, the phone counts as having a VPN), so a wrong reading never opens a wrong
  address;
- when neither answers, the setup page opens, as before.

With no Tailscale address saved nothing changes: only the saved address is probed, with no grace. Known limits: a Tailscale
connection that is slow to wake (relayed, or idle for a while) can miss the grace, so that launch opens the saved address and
the app moves to Tailscale the next time it comes to the front (below). At home with Tailscale on, the app now reaches the
server through the server's own Tailscale node instead of directly; the extra load that puts on the server for video has not
been measured, and the app still uses the saved address whenever Tailscale does not answer. If you signed in before the app
kept one sign-in for both addresses (next section), the Tailscale address asks you to sign in once.

The rules are unit-tested on the JVM (`EndpointRulesTest`, `EndpointChooserTest`, `EndpointProbeTest`); the plugin glue, the VPN
reading and the launch gate need a phone (see the PR's checklist). iOS does the same, with the limits listed in
[Two addresses for one server (iOS)](#two-addresses-for-one-server-ios).

### When you change network (Android)

When a Tailscale address is saved (as above) the app keeps you on an address that works, and prefers Tailscale:
- **The page stops answering:** the app moves to the other address within a few seconds, with no restart. Leave home on the
  saved address and, when Wi-Fi turns to mobile data, it moves to Tailscale (when the Tailscale app on the phone is connected);
  switch Tailscale off while its page is open and it moves to the saved address. This happens on any network change and when
  the app comes to the front, even in Picture-in-Picture: a page that no longer answers has nothing left to lose.
- **The page still works:** it is left alone, with one exception. A working page on the saved address moves up to Tailscale the
  next time the app comes to the front (opened again, or the screen turned back on), never while it is in Picture-in-Picture
  and never in the middle of use. A working Tailscale page never moves to the saved address.

Before any move the app looks once more at the page on screen, waiting about 1.5 seconds for an answer, to tell a page that works
from one that has stopped answering; if that look cannot be taken, the page counts as working. Known limits: a page that is merely slower than
that look counts as stopped and moves to the address that has just answered; and the move to Tailscale at a start happens a few
seconds after the app comes to the front (a settle wait plus the checks), so something typed in those first seconds (a sign-in
code, say) can be lost. Either move reloads the app onto its home screen, so a video you were watching starts again. Sign-in
carries across as described in the next section: moving to the saved address asks you to sign in only if you never signed in
there.

How it works: while the app is in the foreground, `NetworkRoamWatcher` listens for changes to the phone's default network (a new
network, a lost one, or a change between Wi-Fi, mobile data, Ethernet and VPN). It waits about 2 seconds for the network to
settle, then makes the same choice the launch makes (`MainActivity.chooseAddress`). If that picks the other address from the page
on screen, it takes the second look at the page on screen and `RoamRules.target` decides; a move recreates the activity, which
re-runs the launch. Coming to the front runs the same check once. The saved address is never rewritten. Only a page on one of
the two addresses is moved: the setup page and the "Can't reach your server" page are left alone, since they already offer a
retry and your saved servers.

- **Nothing answering yet** (Tailscale still reconnecting, say): it checks again at about 3, 6, 12 and 25 seconds, then waits for
  the next network change or the next time the app comes to the front.
- **No reload loops**: a move needs at least 20 seconds since the app last reloaded itself (after a page failed to load, or a
  move), and at most 3 moves happen in any 5 minutes. That limit lifts by itself as the 5 minutes roll on; a move it holds back
  waits for the next network change or the next time the app comes to the front. The existing reload after a page fails to load
  keeps its 8-second gap, now counted from either kind of reload (`RecreateLog`).
- **Not covered**: the watcher runs only while the app is in the foreground. Listening with the screen off on a network that has
  stopped working is not rescued; the app chooses again when you next bring it to the front. All the numbers above are
  judgements, not measurements.
- **Tested**: the rules are unit-tested on the JVM (`RoamRulesTest`, including the second look's time limit and the last
  Picture-in-Picture check; `EndpointRulesTest` for what counts as an answer). The watcher's wiring and the `MainActivity` glue
  need a phone (see the PR's checklist). The iPhone app does the same: see
  [When you change network (iOS)](#when-you-change-network-ios).

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

The iPhone app uses your server's Tailscale address too: it opens that secure address first whenever it answers (with the
Tailscale app on the phone connected), and the address you set it up with when it does not. **Change server** works away from
home, and the saved list shows each server's name. The rules are the Android ones above, ported case for case:
`ServerConfig.addEndpoint` saves the address only if it is exactly `https://<name>.ts.net`, the page asking is the saved server
or its Tailscale address, and both
report the same server id (UserDefaults keys `tailscale_for:<saved address>` and `tailscale_id:<saved address>`, names in
`server_names`). `clear` (Change server) keeps the address; the ✕ on the setup page (`forget`) removes it. `forget` is accepted
only from the bundled setup page (a server's page is refused, "Not available on this page"), because it also clears a
remembered "Don't use it" (below). Only the bundled setup page can reconnect a remembered server through it, and the address is
dropped when another server answers at the home address on the next save from the setup screen.

"The page asking" means both the address the app opened and the origin the web view is showing right now
(`EndpointRules.isTrustedPage`, for `addEndpoint` and `removeEndpoint`; the reconnect also checks the web view is on the bundled
page). Capacitor iOS lets the web view open, inside the app, any address that merely starts with the server's
(`https://server.example.attacker.test` for `https://server.example`), so the address the app opened is not enough on its own.

**The app asks once before it saves a new secure address.** The first time the app learns a secure address from a plain-`http`
address (or the secure address changes), it asks: **Use this secure address?**, showing the address it came from and the full
secure name, with **Use it** / **Don't use it**. Only accept it if you have just turned on the secure address in your server's
Settings → Remote Access and the name matches. Why: the server id the app compares is public, so a device on your network that
answers at a plain-`http` address could offer its own secure address, and since the app now opens the secure address first,
that address would be opened first. The rules (`EndpointRules.needsConfirmation`, `PromptGate`):
- **Asked only** when the offered address differs from the one already saved AND the page is not `https`. "The page" is the one
  the web view has committed: its address must agree, by origin, with the back-forward list's current page, so an address the web
  view shows only because it has started to load another page does not count; a page that cannot be read counts as plain `http`. The same address, offered again at every start, is never asked about; nor is an offer from an `https` page, the secure
  page itself included. Removing the address (`removeEndpoint`) is never asked: it only returns the app to the saved address.
- **Use it** saves the address as before. **Don't use it** saves nothing: the app keeps what it had (the saved address, and the
  secure address it already had if the offer was a change), and it does not ask about that same address again for **7 days**
  (then it asks once more). A different address is asked about, subject to the 10-minute wait below. Forgetting the server (the ✕
  on the setup page) clears the remembered "no".
- **No answer is not a "no".** A prompt that cannot be shown (another screen on top, the app not active), that is not answered
  within **120 seconds**, or that is withdrawn because the app rebuilds its screen, saves nothing and remembers nothing; a later
  start asks again. One prompt at a time, and at most one every **10 minutes** while the app runs: within those 10 minutes an
  offer is refused without asking, whether it is the same address at the next start (a rebuild after a network change, say) or a
  different one. Closing and reopening the app starts the count again. All four numbers are judgements, not measurements.
- **Checked again at the write.** Just before the address is written, the saved server, the page and the "does this need
  asking" decision are all checked again; if the page moved, or the offer needs asking now but was not accepted in a prompt,
  nothing is saved.

At launch, when a Tailscale address is saved, `MainViewController` probes both addresses at once behind the launch screen, under
one 3-second deadline (`EndpointChooser`), and opens **the Tailscale address first**: if it answers with the id it was saved for,
it is opened, whatever the saved address said; otherwise the saved address, if that answers. Why (since 2026-11-09): on a phone
hotspot with Tailscale routing the home network, the saved `http` address answered too, so the old saved-first rule kept the
app on plain `http`, and two-way talk, which needs HTTPS, could not work.

- **The grace.** When the saved address answers first, the app still waits a short grace for the Tailscale one: **1 second**,
  or **0.25 second** when the phone positively has no VPN. A Tailscale address that fails outright (refused, an error, the wrong
  server id) ends the grace at once. Both numbers are judgements, not measurements: Tailscale on and healthy answers in well
  under a second, and with Tailscale off the saved address should not be held back by more than a quarter of a second.
- **The VPN hint.** One `NWPathMonitor` reading at launch: a VPN counts as present when the phone lists a network interface of
  type `.other` (Tailscale's tunnel is not Wi-Fi, mobile data or Ethernet). It can only shorten the grace: no reading counts as a
  VPN, so the full second.
- With no Tailscale address saved, the launch is exactly as before: no probe, no wait. The 3 s and 2.5 s (each probe) are
  carried over from Android as judgements, not measurements.

Known limits on iOS:
- **When neither address answers**, the app opens the saved address and shows the "Can't reach your server" screen, as it always
  has on iOS (Android shows the setup screen instead).
- **A slow Tailscale path opens the saved address.** A Tailscale path that is cold (relayed, or the first request after a long
  idle) can take longer than the grace. That launch then opens the saved address, and the next time the app comes to the front
  it moves to the Tailscale address (see below).
- **At home the app now goes through the server's Tailscale node** (a software node inside the server's container) instead of
  the home address. Not measured for video load on the server. If Tailscale does not answer, the app uses the saved address, so
  home use does not depend on it.
- **The VPN hint is not yet confirmed on a phone.** Apple describes `.other` as "virtual or unknown", so it is a hint, not proof:
  another virtual interface also reads as a VPN, which costs the full 1-second grace and nothing else. If an iPhone does not show
  Tailscale's tunnel as `.other`, a launch with Tailscale on gets the 0.25-second grace, and a slower Tailscale answer loses that
  launch to the saved address. It never opens an address that was not verified: the id check is unchanged.
- **The prompt cannot prove the secure name is yours.** It shows the name and says when to accept it; someone who accepts a
  hostile address is exposed as before. Phones that already hold a secure address are not asked again about it. A page that
  gets your REAL address declined first can keep it away for 7 days at most. Not yet confirmed on a phone: the prompt itself,
  its timeout and its withdrawal during a rebuild.
- **One more sign-in, once.** If you signed in before the app kept one sign-in for both addresses (see
  [One sign-in across the two addresses (iOS)](#one-sign-in-across-the-two-addresses-ios)), the Tailscale address, now the one
  most often open, may ask you to sign in once.
- **Capacitor iOS cannot tell which frame called a plugin method.** "The page asking" is judged from the address the app opened and
  the main frame's current origin, so a child frame inside an accepted page counts as that page. Nightlight's own pages use no
  frames and its CSP forbids a cross-origin one, so this is a conditional gap, as for Android's old WebView.
- **Tested**: the rules (`EndpointRules.swift`, the reply parsing in `EndpointProbe.swift`, `EndpointChooser.swift`) are compiled
  on their own with `swiftc` and run in CI (`ios/Tests`, the first step of `build-ios.yml`), with no network. The plugin glue,
  the launch choice and the network reading need an iPhone. Background audio is unchanged.

### When you change network (iOS)

The iPhone app moves between the two addresses by itself, as the Android app does (when a Tailscale address is saved, as above).
When the page on screen **stops answering**, it moves to the other address within a few seconds, with no restart: leave home with
the app open on the home address and Wi-Fi turns to mobile data, or switch Tailscale off while the app is on the Tailscale
address. When the page on screen **still works**, the only move is up to the Tailscale address, and only the next time the app
comes to the front (opened again, or the phone unlocked with the app in front), a few seconds after it appears, so something
typed in those first seconds can be lost; not while a video is already floating in Picture-in-Picture or while the app is
inactive (Control Center, a call banner). A working Tailscale page is never moved to the home address. Either move reloads the
app onto its home screen, so a video you were watching starts again, and the screen can freeze for up to about 3 seconds while
the app picks the address, as it does when it starts. Sign-in carries across as described in
[One sign-in across the two addresses (iOS)](#one-sign-in-across-the-two-addresses-ios).

How it works: while the app is in the foreground, `NetworkRoamWatcher` watches the phone's network (`NWPathMonitor`). A change is
a different connection state, a different set of network types (Wi-Fi, mobile data, Ethernet, VPN and so on, in use or
available) or a different router, so moving to another Wi-Fi network counts too. It waits about 2 seconds for the network to
settle, then makes the same choice the launch makes (`MainViewController.chooseAnswer`: Tailscale first). If that picks the
other address from the page on screen, the app asks the page's own address once more (for up to 1.5 seconds, with the same id
check for the Tailscale address), because the choice prefers Tailscale and so cannot say on its own whether the page on screen
still works. `RoamRules.target` then decides, and a move rebuilds the main screen, which re-runs the launch choice, the same
rebuild **Connect** and **Change server** use (`AppRestart.rebuildRoot`). Coming back from the background runs the same check
once; pulling down Control Center, a call banner or the app switcher does not. The saved address is never rewritten. Only a
page on one of the two addresses is moved: the setup page and the "Can't reach your server" page are left alone, since they
already offer a retry and your saved servers. With no Tailscale address saved, nothing is probed and nothing changes.

- **Leaving a page that stopped answering happens at once**, in either direction, on a network change or when the app comes to
  the front, even with a video in Picture-in-Picture and even while the app is inactive (the page has already stopped working).
  Known limit: a page that is only slow, taking more than 1.5 seconds to answer that second look, counts as stopped, and the app
  moves to the address that has just answered.
- **Moving up to the Tailscale address waits** until the app comes to the front, and does not happen while a video is already
  floating in Picture-in-Picture or while the app is inactive. If the app cannot tell whether one is floating, it counts as
  floating. Known gap: the floating state is read once, a moment before the reload, and iOS gives no safe way to read it again at
  the last instant, so a video that starts floating in that instant is not caught (unconfirmed on a phone).
- **Nothing answering yet** (Tailscale still reconnecting, say): it checks again at about 3, 6, 12 and 25 seconds, then waits
  for the next network change or the next time the app comes to the front. A page that still works needs no retry: the next
  time the app comes to the front checks again.
- **No reload loops**: a move needs at least 20 seconds since the last move, and at most 3 moves happen in any 5 minutes, for
  both kinds of move. That limit lifts by itself as the 5 minutes roll on; a move it holds back waits for the next network change
  or the next time the app comes to the front. A reload you ask for (Connect, Change server) does not count. The iPhone app has no
  automatic reload after a page fails to load (Android's 8-second gap), so only moves are counted (`RecreateLog`).
- **Not covered**: the watcher runs only while the app is in the foreground. With audio playing in the background (screen off) on
  a network that has stopped working, nothing is rescued until you bring the app to the front. All the numbers above are
  judgements carried over from Android, not measurements.
- **Not yet confirmed on a phone**: how an iPhone reports its network while Tailscale's VPN is on (the app compares the network
  types in use and the ones available, so leaving Wi-Fi should still count), whether a floating video shows as
  `document.pictureInPictureElement` or only as WebKit's own Picture-in-Picture mode (the app checks both), and the VPN hint above.
- **Tested**: the rules (`RoamRules.swift`, the same table as Android's `RoamRulesTest`) run in CI with `swiftc`
  (`ios/Tests`). The watcher, the second look, the lifecycle calls and the rebuild need an iPhone.

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
