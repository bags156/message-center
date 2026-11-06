# Message Center

A native Android companion for **Google Messages**, with signed APK updates through **Obtainium**.

Requires Android 13 or newer.

**Status: first alpha.** This is a notification-based companion, not an independent RCS client or a complete skin of Google Messages. Device testing is required, particularly on OnePlus / ColorOS.

## What the first version does

- Collects message text exposed in Google Messages notifications into a clean, searchable inbox.
- Replies through a live Google Messages notification reply action, when available. Google Messages chooses RCS or SMS and handles transmission.
- Converts recognized English iPhone fallback reaction texts into attached emoji only when the original message has one exact match in the captured conversation. Ambiguous or unrecognized texts remain visible.
- Opens Google Messages for full conversation history, new messages, media, and features not available through notifications.
- Includes light/dark appearance and an explicitly labeled design preview.

It does **not** import the complete Google Messages RCS database, promise iPhone feature compatibility, verify delivery/read receipts, or mark messages read in Google Messages. No unread icon count is implemented.

## Install with Obtainium

After the first release is published:

1. In Obtainium, choose **Add app**.
2. Enter `https://github.com/bags156/message-center`.
3. Select `message-center.apk` if prompted, then install.
4. Open Message Center and choose **Connect Google Messages**. Allow notification access in Android settings.
5. Keep Google Messages as your default messaging app, with its message notifications enabled.

On Android builds that restrict notification access for sideloaded apps, enable **App info → ⋮ → Allow restricted settings** for Message Center first. Menu wording depends on ColorOS. If capture stops, check the app's background/battery settings.

No APK is published until the signed release workflow succeeds. Use release assets, not unsigned CI artifacts, for installation.

## Privacy

The app has **no internet permission**, advertising SDK, analytics, contact access, SMS permission, accessibility service, or overlay. It only processes notifications from `com.google.android.apps.messaging`. Android's notification-access grant itself is broad; filtering is enforced in app code.

Captured message text is stored in private app storage, with cloud backup disabled. It is retained for up to 90 days or 5,000 messages; conversation views show the most recent 300 captured messages. Clear it from Settings. GitHub contains source and release files, never your message history or the signing private key.

## Build and release

Requires JDK 17, Gradle 8.11.1, and Android SDK 35. Run:

```sh
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
python3 -m unittest discover -s tools -p 'test_*.py' -v
```

See [docs/RELEASING.md](docs/RELEASING.md) for permanent signing and versioning. GitHub Actions runs checks on changes to `main`; the manual workflow publishes signed APKs after those checks pass.
