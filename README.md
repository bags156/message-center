# Message Center

An experimental Android client for Google Messages, with the paired connection running **on the phone**. No additional server, Matrix account, or Beeper account is required. Google Messages remains the default SMS/RCS app and must stay installed and online.

**0.2.0 is the paired-client release.** It replaces the notification-based approach in 0.1.x. Compiling and offline device tests do not verify Google sign-in, live RCS delivery, complete history, or ColorOS background reliability. Those require testing on a paired phone.

## Install and connect

1. Install the signed `message-center.apk` from the latest release. It updates the previous app using the same package and signing key; no uninstall is needed.
2. Open Message Center and tap **Connect Google Messages**.
3. Sign in to the account selected under **Google Messages → Device pairing**. Sign-in is displayed on Google's own pages inside Android WebView, using its real user agent. If Google blocks that browser, this build cannot complete pairing and does not bypass the block.
4. Approve the matching emoji in Google Messages, then return to Message Center.
5. Allow Message Center notifications. In ColorOS, allow background activity / auto-launch for both apps and check their battery settings.
6. Load conversations with **Refresh**, then open a chat. **Older messages** loads another history page; **Load older conversations** pages the inbox. Archived chats have a separate folder toggle.
7. Test an incoming text with Google Messages notifications disabled. This client has its own connection and notification channel and does not use notification access. Keep Google Messages installed, default, and allowed to run in the background.

For Obtainium, use `https://github.com/bags156/message-center`. Regular releases do not need **Include prereleases**. Public releases do not require a GitHub token. Use signed release assets, not unsigned CI artifacts.

## Implemented in the test build

- Direct on-device pairing and protocol connection through `mautrix-gmessages/pkg/libgm`.
- Native conversation and text composer screens, including a new one-to-one conversation by full phone number.
- Paged conversations/history, cached using Google's actual conversation/message IDs.
- Reactions attached to their actual message IDs. Tap a badge to see participants reported by Google; reply/react actions use the paired protocol.
- An independent foreground connection and incoming-message notifications, including a boot receiver for an enabled saved connection.
- Google-reported send/delivery/read status. Request acceptance is labeled separately from delivery.
- Authenticated encryption of the paired session with Android Keystore, private app storage for synced messages, and disabled cloud backup.

## Current limits

This is an unofficial integration using an undocumented Google protocol. Sign-in can be rejected and protocol changes can require an update or re-pairing. Pairing and sustained background operation have not yet been verified on the user's OnePlus 15 / ColorOS 17.

History paging follows the responses Google supplies; reaching the end is **not proof of a complete phone archive**. SMS fallback reaction text can still be present when Google exposes it as a regular SMS instead of structured reaction metadata. Structured reactions use exact IDs and do not guess matches from quoted text.

Attachments appear as labels; viewing or sending media is not implemented in this test build. New group creation, custom reaction entry, calls, scheduled messages, and an unread launcher number are not implemented. There is no durable offline outbox. Ambiguous send outcomes are not automatically retried.

## Privacy

The app communicates directly with Google using the signed-in account and paired session. It adds no message-processing server. The Google session is powerful account data: it is never written to logs or sent to this repository, and the saved paired session is encrypted with a non-exportable Android Keystore key. WebView sign-in cookies are removed locally after connection is established. Never paste Google cookies or passwords into chat or an issue.

Synced message content is in private app storage protected by Android's device encryption; it is not separately encrypted with the session key. **Forget pairing and synced messages** clears the paired cache. Remove Message Center from Google's **Device pairing** list to revoke it there. Old 0.1.x notification captures remain in a separate unused database when updating; clearing Android app storage removes those too.

## Build and verify

Requires Java 17, Gradle 8.11.1, Android SDK 35, NDK 28.2.13676358, and Go 1.27.3. Dependencies and actions are pinned.

```sh
cd bridge
go test -race ./...
go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
mkdir -p ../app/libs
gomobile bind -target android/arm64,android/amd64 -androidapi 33 -javapkg io.github.bags156.gm -o ../app/libs/messagebridge.aar .
cd ..
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

CI also installs the app on an Android emulator and checks JNI loading, Keystore storage, history/live-update conflict handling, ID deduplication, and activity startup. These are offline checks with synthetic data, not real Google-account tests. See [releasing](docs/RELEASING.md) and [phone testing](docs/PHONE-TEST.md).

## License

AGPL-3.0-or-later. See LICENSE and NOTICES. Release dependency-source archives contain the pinned third-party sources and license notices needed to inspect or rebuild the connection library.
