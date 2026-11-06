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

## Branding

The launcher icons, splash screens and the logo on the first-run / error pages are generated from
`branding/logo-source.png` (the same 1024 px logo the web app uses, in `nightlight/frontend/branding/`) by
`python scripts/make-branding.py` (needs [Pillow](https://pypi.org/project/pillow/)). Change the logo by replacing that
file and running the script; the colours of the two native pages are set at the top of `www/index.html` and `www/error.html`.

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
