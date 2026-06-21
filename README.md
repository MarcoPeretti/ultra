# ANCS Bridge — iPhone notifications on a Wear OS watch

A minimal **Wear OS** app that shows iPhone notifications on a Samsung Galaxy Watch
Ultra — a watch Samsung does **not** officially support on iOS.

## Status

- ✅ **Incoming calls** — caller name shows on the watch and it **vibrates like a
  phone** (repeating buzz) until the call is answered or dropped. Verified on a
  Galaxy Watch Ultra.
- ✅ **Runs in the background** — the bridge auto-starts on app open and after a
  reboot; no need to tap anything. iOS keeps the bonded connection alive on its own.
- ✅ **All live notification categories** are forwarded and shown.
- ⬜ **TODO: WhatsApp / message notifications.** The bridge already posts every live
  ANCS notification it receives, and calls prove the pipeline works — but in testing
  iOS did not *emit* an ANCS event for incoming WhatsApp messages (no event reached
  the watch at all). Strongly suspected to be an iPhone-side **Focus/Do-Not-Disturb**
  or per-app notification setting that silences messaging apps while letting calls
  through. Needs to be re-tested with all Focus modes off and WhatsApp lock-screen
  notifications enabled; if messages then arrive as *Modified* updates rather than
  *Added*, add handling for `EVENT_MODIFIED` in `AncsGattClient.handleNotificationSource`.

## Why there is no iPhone app

iOS does not let any app read other apps' notifications (no Android-style
`NotificationListenerService`). So an iPhone "bridge app" *cannot* capture your
calls/WhatsApp and forward them — that path is a dead end.

Instead the watch speaks **ANCS** (Apple Notification Center Service): a Bluetooth
LE service that iOS exposes to any **bonded** accessory. iOS pushes notifications —
including caller ID and any app like WhatsApp — straight to the watch at the OS
level, with no iPhone app required. This is exactly how Garmin/Fitbit (and the
Merge/Bridge apps) work. The watch is the ANCS "Notification Consumer".

## How it works

```
 Galaxy Watch Ultra (this app)                    iPhone
 ─────────────────────────────                    ──────
 1. advertises as connectable BLE peripheral (solicits ANCS)
 2.                          ◄── connect + bond ── (one-time, via LightBlue — see below)
 3. acts as GATT *client*  ──► discovers ANCS on the iPhone (GATT server)
 4.                          ◄── Notification Source notify ── incoming call (cat 1)
 5. writes Get Notification Attributes ──► Control Point
 6.                          ◄── Data Source ── caller title/number
 7. shows a watch notification
```

Code map (`app/src/main/java/dev/marco/ancsbridge/`):

| File | Responsibility |
|------|----------------|
| `AncsProtocol.kt` | Pure encoder/decoder: UUIDs, packet + TLV parsing, command builder. No Android deps → unit-tested. |
| `AncsGattClient.kt` | GATT client: discover ANCS, subscribe, fetch attributes, reassemble Data Source fragments, skip the pre-existing backlog. |
| `AncsService.kt` | Foreground service: advertise, GATT server (for bonding), bond receiver, post watch notifications, ring-vibrate on calls. |
| `BootReceiver.kt` | Restarts the bridge after a reboot (background operation). |
| `AncsState.kt` | Shared `StateFlow` state for the UI. |
| `MainActivity.kt` | Wear Compose UI: status + live log; auto-starts the service on open. |
| `app/src/test/.../AncsProtocolTest.kt` | JVM unit tests for the protocol (no hardware needed). |

## Build & test

Requires JDK 17 (use the one bundled with Android Studio). The repo includes a
Gradle wrapper.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# Run the protocol unit tests (no device needed)
./gradlew :app:testDebugUnitTest

# Build the debug APK
./gradlew :app:assembleDebug
```

`local.properties` points Gradle at your Android SDK; it is git-ignored — recreate
it if you clone fresh:

```
sdk.dir=/Users/<you>/Library/Android/sdk
```

The easiest workflow is to **open the folder in Android Studio** and let it sync.

## Deploy to the Galaxy Watch Ultra

1. On the watch: Settings ▸ About ▸ tap *Software version* 7× to unlock Developer
   options, then enable **ADB debugging** and **Debug over Wi-Fi**.
2. Pair the watch to ADB:
   ```bash
   ~/Library/Android/sdk/platform-tools/adb pair <watch-ip>:<pair-port>   # code shown on watch
   ~/Library/Android/sdk/platform-tools/adb connect <watch-ip>:<port>
   ```
3. Install + run from Android Studio (the watch appears as a target), or:
   ```bash
   ./gradlew :app:installDebug
   ```
4. On first launch, grant the Bluetooth + notification permissions. The bridge then
   **auto-starts** (and restarts on reboot) — no need to tap Start.
5. To survive Samsung's aggressive power management, set
   **Settings ▸ Apps ▸ ANCS Bridge ▸ Battery → Unrestricted** on the watch.

## Pair the iPhone (one-time) — bond via LightBlue

"Bonded" means a **BLE bond**, not a Classic Bluetooth pairing. Key facts:

- The watch advertises connectable **and solicits the ANCS service UUID** — the
  ANCS-spec signal that says "connect to me, I want your notifications."
- **iOS is always the side that connects** (the watch can't dial the iPhone).
- **iOS Settings ▸ Bluetooth does NOT list generic BLE peripherals**, so you can't
  start pairing there. You need *something* on the iPhone to initiate the connection
  once. A free BLE scanner app does this perfectly — **LightBlue** (verified working
  on a Galaxy Watch Ultra; nRF Connect did not surface the pairing the same way).

**Once the bond exists, iOS maintains the ANCS connection by itself — no app, not
even LightBlue, needs to stay connected.** LightBlue is only a one-time matchmaker.

Steps (one-time):
1. With the watch showing **"Advertising"**, install **LightBlue** on the iPhone
   from the App Store and open it.
2. **Scan** and find the watch — it shows its Bluetooth name and lists *Solicited
   Services: ANCS* (`7905F431…`). Tap **Connect**.
3. iOS shows a **pairing prompt** (and a notification-access prompt) — accept both.
   The watch advances Advertising → Connected → Bonded → **Ready ✓**.
4. **Quit LightBlue** (swipe it out of the app switcher). Do *not* leave it
   connected — while it holds the link it causes connect/disconnect flapping. With it
   closed, iOS keeps the bonded connection alive on its own.

> Why not just keep an iOS companion app? You don't need one — iOS reconnects to the
> bonded, ANCS-soliciting watch automatically. A companion app would only be a more
> polished replacement for the one-time LightBlue step.

## End-to-end test

1. Watch UI should progress: Advertising → Connected → Bonded → **Ready ✓**.
2. Call the iPhone from another phone → an **Incoming call** with the caller name
   appears on the watch within a second or two, and the watch **vibrates repeatedly**
   until the call is answered or stops; the notification clears when the call ends.
3. Lock the watch / wait a few minutes → calls still arrive (the foreground service
   keeps the BLE link alive).

## Known limitations (personal-hack scope)

- **Replies are impossible** — iOS forbids third-party accessories from sending
  message responses.
- **Wear OS background limits** — the foreground service + boot receiver keep it
  running, but Samsung's power management is aggressive; set the app's battery usage
  to *Unrestricted*. You may still occasionally need to reopen the app.
- **Message forwarding (WhatsApp)** — see the TODO under [Status](#status); calls
  work, but message notifications may be suppressed by an iPhone-side Focus/DND or
  per-app setting before they ever reach the bridge.
- **BLE peripheral support** — relies on the watch being able to advertise + act as
  GATT server. The Galaxy Watch Ultra supports this (Merge does the same); the UI
  logs a clear message if a device cannot advertise.

## EU-only alternative (not used here)

You're in the EU, so iOS 26.3's official **Settings ▸ Notifications ▸ Notification
Forwarding** (DMA-driven) can forward full notification content to one third-party
wearable. Its developer API is undocumented as of writing; ANCS is used here because
it's fully specified and region-independent. Revisit once this app works if you want
richer/selective forwarding.
