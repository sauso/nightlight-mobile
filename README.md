# Nightlight Mobile

Capacitor mobile shell for [Nightlight](https://github.com/sauso/nightlight) —
**Android and iOS**. It displays the Nightlight web app (loaded live from your own
server, whose address you enter in-app on first launch — nothing is baked in) and
adds the native pieces the web app can't do on its own: keeping audio playing while
the screen is off / the app is backgrounded, and runtime server selection.

## Platforms

- **Android** — signed APK, built in CI and attached to each GitHub release.
- **iOS** — Capacitor `ios/` project. Builds for the iOS Simulator in CI; an unsigned
  device IPA can also be produced for sideloading with a free Apple ID. App
  Store / TestFlight distribution requires an Apple Developer account.

Both platforms share the same web UI (from `nightlight/frontend`) and the same
JS↔native bridge, with the native plugins implemented per platform (Kotlin for
Android, Swift for iOS).

## Push notifications

The app can show a phone notification when a camera sees motion, even while closed. It's optional and
uses **your own Firebase project** (nothing is baked into the APK). Full walkthrough:
**[docs/push-notifications.md](docs/push-notifications.md)**.

For issues, feature requests, and support, please use the main project repo:
**https://github.com/sauso/nightlight**
