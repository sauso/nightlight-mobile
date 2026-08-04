# Setting up push notifications

The Nightlight Android app can show a **push notification on your phone** the moment a camera with
motion detection sees movement — even when the app is **closed** or your screen is off. This guide
walks through the whole setup end to end.

It's **optional** and **off by default**. Everything else — live viewing, background listening, and
the in-app **Settings → Recent alerts** list — works with or without it.

> **Screenshots.** The steps below have marked spots — `📷 Screenshot: …` — where a picture helps.
> They're placeholders; drop matching images into `docs/img/` and replace each callout with
> `![caption](img/your-file.png)` if you want them inline. The written steps are complete on their own.

---

## Why there's a setup at all

Nightlight is **self-hosted**, so there is no shared "Nightlight cloud" to route notifications
through. Waking a closed app requires the phone OS's push service — on Android that's **Firebase
Cloud Messaging (FCM)** — so each install uses **its own free Firebase project**. The app you install
from the releases page is **generic**: it has no Firebase project baked in and instead reads the
config from **your own server** at runtime. You do the Firebase setup once.

> **Privacy.** Only a short *"motion detected on \<camera\>"* message passes through Firebase —
> **never your video or audio**, which never leave your own server.
>
> **Android only** for now. iOS needs Apple's APNs, which is on the roadmap.

## What you'll end up with

Two files from a Firebase project you create, sitting in your Nightlight **data directory** (the same
volume as your database, e.g. `/mnt/user/appdata/nightlight`, mounted at `/app/data` in the container):

| File | Purpose | Comes from |
|---|---|---|
| `firebase-service-account.json` | Lets **the server send** notifications | Firebase → Project settings → Service accounts |
| `google-services.json` | Lets **the app connect** to your project | Firebase → the Android app you register |

The paths can be overridden with the `FIREBASE_CREDENTIALS` and `FIREBASE_CLIENT_CONFIG` environment
variables, but the defaults above are simplest.

---

## Step 1 — Create a Firebase project

1. Go to **[console.firebase.google.com](https://console.firebase.google.com)** and sign in with any
   Google account.
2. Click **Add project**, give it any name (e.g. *Nightlight*), and continue.
3. **Google Analytics is not needed** — you can turn it off when prompted.

> 📷 Screenshot: Firebase Console → *Add project* → name entry

## Step 2 — Register the Android app

1. On the project's overview page, click the **Android** icon (**Add app → Android**).
2. For **Android package name**, enter **exactly**:

   ```
   com.sauso.nightlight
   ```

   This must match the app's package or the config won't be accepted — copy it as-is.
3. A nickname is optional. **SHA-1 is not required** for notifications — leave it blank.
4. Click **Register app**, then **Download `google-services.json`**. Keep this file; it's the app's
   half of the config. (You can skip the "Add Firebase SDK" / Gradle steps the wizard shows next —
   the released APK already bundles the SDK.)

> 📷 Screenshot: *Add Android app* form with `com.sauso.nightlight` filled in
>
> 📷 Screenshot: the *Download google-services.json* button

## Step 3 — Get the server credential

1. Open **Project settings** (the gear icon, top-left) → the **Service accounts** tab.
2. Click **Generate new private key** → **Generate key**. A JSON file downloads.
3. This file is a **secret** — it can send notifications as your project. Don't commit it to git or
   share it; store it with the same care as a password.

> 📷 Screenshot: Project settings → *Service accounts* → *Generate new private key*

## Step 4 — Put both files on your server

Copy the two files into your Nightlight **data directory**, named **exactly**:

```
<data dir>/firebase-service-account.json   # the secret key from Step 3
<data dir>/google-services.json            # the file from Step 2
```

On Unraid the data dir is typically `/mnt/user/appdata/nightlight`. Lock down the secret so only the
owner can read it:

```bash
chmod 600 /mnt/user/appdata/nightlight/firebase-service-account.json
```

Then **restart the container** so the server picks up the credential:

```bash
docker restart nightlight
```

Check the logs — you should see:

```
[push] Firebase initialized (project <your-project-id>)
```

If instead you see *"no Firebase credentials … push notifications disabled,"* the service-account
file isn't where the server expects it — re-check the path and filename in Step 4.

Finally, **enable push at the server level**: in the web app go to **Settings → Notifications
(push)** and turn on **"Enable push notifications."** Saving re-checks both files and tells you
exactly what's missing if anything is — so you can just toggle it here after dropping the files in,
without the container restart above.

## Step 5 — Turn it on in the app

1. Install/update the **[Nightlight Android app](https://github.com/sauso/nightlight-mobile/releases/latest)**
   (the standard signed release APK — no per-user build needed) and sign in to your server.
2. Go to **Account → Notifications** and enable **"Send motion alerts to this device."**
3. **Allow** the notification permission when Android asks.

Each device opts in **separately**, so repeat this on every phone that should get alerts.

> If that section says *"Notifications aren't set up on this server,"* the app reached your server but
> couldn't read `google-services.json` — re-check Step 4.
>
> 📷 Screenshot: app → *Account → Notifications* toggle

## Step 6 — Enable motion detection on a camera

Push notifications fire off **motion detection**, so turn it on for at least one camera:

1. **Cameras → edit** a camera → **Motion detection → Enable**.
2. Tune **sensitivity**, **confirm** delay, and **cooldown** to taste (defaults are a fine start).
3. Move in front of that camera. Within a few seconds you should get a **notification**, and the event
   also lands in **Settings → Recent alerts**. Tapping the notification opens the app.

> 📷 Screenshot: a motion-alert notification on the lock screen

---

## Troubleshooting

- **Log says Firebase initialized, but no notification arrives.** Confirm the phone opted in
  (Account → Notifications) *and* granted the OS notification permission, the camera has motion
  detection on, and the app has been opened at least once since enabling (that's when it registers its
  device token with the server).
- **"Notifications aren't set up on this server."** The app can reach the server but
  `google-services.json` is missing or invalid in the data dir. It must be the file from **your**
  Firebase Android app (package `com.sauso.nightlight`).
- **"Push notifications are set up but not enabled on this server."** The files are present but an
  admin hasn't turned push on — enable it under **Settings → Notifications (push)** (the tail of
  Step 4).
- **Notifications stopped after a reinstall.** Uninstalling or clearing the app's data invalidates its
  token; just re-enable in Account → Notifications. The server automatically prunes tokens that
  Firebase reports as unregistered.
- **Battery optimization.** Some Android skins aggressively kill background apps. If notifications are
  unreliable, exclude Nightlight from battery optimization in your system settings.

## See also

- Server-side reference: **[nightlight/docs/notifications.md](https://github.com/sauso/nightlight/blob/main/docs/notifications.md)**
- Report issues on the main repo: **https://github.com/sauso/nightlight**
