# ANCS Bridge — iPhone notifications on a Wear OS watch

A minimal **Wear OS** app that shows iPhone notifications (incoming calls now,
WhatsApp next) on a Samsung Galaxy Watch Ultra — a watch Samsung does **not**
officially support on iOS.

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
 1. advertises as connectable BLE peripheral
 2.                          ◄── connect + bond ── (you pair from iOS Settings)
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
| `AncsGattClient.kt` | GATT client: discover ANCS, subscribe, fetch attributes, reassemble Data Source fragments. |
| `AncsService.kt` | Foreground service: advertise, GATT server (for bonding), bond receiver, post watch notifications. |
| `AncsState.kt` | Shared `StateFlow` state for the UI. |
| `MainActivity.kt` | Wear Compose UI: status, Start/Stop, last notification. |
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
4. On first launch, grant the Bluetooth + notification permissions, then tap
   **Start**.

## Pair the iPhone (one-time) — the tricky bit

"Bonded" means a **BLE bond**, not a Classic Bluetooth pairing. The flow:

- The watch advertises connectable **and solicits the ANCS service UUID** — the
  ANCS-spec signal that tells iOS "connect to me, I want your notifications."
- **iOS is always the side that connects** (the watch can't dial the iPhone; iOS
  doesn't advertise ANCS to arbitrary devices).
- The **bond is triggered the moment the watch subscribes to ANCS** (an
  encryption-required characteristic): iOS shows a pair prompt, then
  **"Allow [device] to access notifications."** Accept both → ANCS appears.

Steps:
1. With the watch showing **"Advertising"**, open **Settings ▸ Bluetooth** on the
   iPhone and look under *Other Devices* for the watch; tap it.
2. Accept the pairing prompt **and** the notification-access prompt.
3. Watch should go Connected → Bonded → **Ready ✓**.

**If the watch never appears in iOS Settings** (generic GATT peripherals don't
always surface there, despite the solicitation): the robust fallback — and what
Merge/Bridge actually ship — is a **~30-line iOS CoreBluetooth helper app** whose
only job is to *scan for and `connect()` to the watch*. It reads no notifications
(ANCS still delivers those at the OS level), so it stays within iOS's rules; it just
deterministically initiates the connection that triggers bonding. Treat this as
plan B if Settings-based bonding proves flaky on the Ultra.

## End-to-end test

1. Watch UI should progress: Advertising → Connected → Bonded → **Ready ✓**.
2. Call the iPhone from another phone → an **Incoming call** with caller
   name/number appears on the watch within a second or two; it clears when the call
   ends.
3. Lock the watch / wait a few minutes → calls still arrive (the foreground service
   keeps the BLE link alive).

## Add WhatsApp

ANCS already delivers every category, so WhatsApp is a filter change, not new
plumbing. In `AncsGattClient.handleNotificationSource`, the social category is
already accepted (`event.isSocial`, CategoryID 4) and the Message attribute is
already requested. To restrict to WhatsApp specifically, match
`AncsNotification.appId == "net.whatsapp.WhatsApp"` before posting.

## Known limitations (personal-hack scope)

- **Replies are impossible** — iOS forbids third-party accessories from sending
  message responses.
- **Wear OS background limits** — the foreground service mitigates but is not as
  bulletproof as a commercial app; you may occasionally need to reopen the app.
- **BLE peripheral support** — relies on the watch being able to advertise + act as
  GATT server. The Galaxy Watch Ultra supports this (Merge does the same); the UI
  logs a clear message if a device cannot advertise.

## EU-only alternative (not used here)

You're in the EU, so iOS 26.3's official **Settings ▸ Notifications ▸ Notification
Forwarding** (DMA-driven) can forward full notification content to one third-party
wearable. Its developer API is undocumented as of writing; ANCS is used here because
it's fully specified and region-independent. Revisit once this app works if you want
richer/selective forwarding.
