# Message Center

An experimental Android client for Google Messages, with the paired connection running **on the phone**. No additional server, Matrix account, or Beeper account is required. Google Messages remains the default SMS/RCS app and must stay installed and online.

**Current release: 1.5.1. The paired-client architecture began in 0.2.0.** It replaces the notification-based approach in 0.1.x. Compiling and offline device tests do not verify Google sign-in, live RCS delivery, complete history, or ColorOS background reliability. Those require testing on a paired phone.

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
- Reactions attached to their actual message IDs. Tap a badge to see participants reported by Google; long-press a message to reply or react.
- Pinned chats (Google's pins plus local pin/unpin), per-chat color themes, and per-chat read receipts.
- Contact photos, unread state, time separators, and delivery ticks in a Material-style inbox and chat.
- An independent foreground connection and incoming-message notifications, including a boot receiver for an enabled saved connection.
- Google-reported send/delivery/read status. Request acceptance is labeled separately from delivery.
- Authenticated encryption of the paired session with Android Keystore, private app storage for synced messages, and disabled cloud backup.
- Photos and videos are made a sensible size before sending: videos are re-encoded to H.264 at up to 1080p and 30 frames a second for RCS (HDR is converted so colours stay right), or squeezed under 1 MB for MMS.
- Attachments and their accompanying text are submitted as separate messages, photo first, to avoid the paired protocol's mixed media/text stall. If only the text fails, it can be retried without resending the photo; uncertain outcomes wait for you to check Google Messages.
- Details → Shared: the chat's photos & videos, links and files, each in its own tab, newest first under month headings. Hold any item to jump to it in the chat, copy or share it.
- Message effects: when an iPhone sends Balloons, Confetti, Slam, Invisible Ink and the rest, RCS only delivers a "(sent with …)" note. The chat plays the effect and hides the note (tap "Replay" to see it again); "Happy birthday", "Congratulations" and "Happy New Year" play theirs too. RCS can't carry an effect, so none are sent.

## Sending and follow-ups

Photos prepare locally while you type. Select Fast, Standard or Full quality next to the attachment strip; the prepared size appears before sending. Full keeps supported originals, while MMS limits and format conversions still apply.

Attachments are saved outside cache in a private, persistent outbox. The foreground service processes them independently of the app window and resumes queued work when connected. Upload/submission interruptions with an uncertain outcome wait for review to prevent duplicates. Text accompanying an attachment is a separate request; retrying only the text never resubmits its photo. Settings → Saved attachments opens waiting work. The chat shows actual preparation, upload and phone/delivery stages.

Needs a reply uses the latest meaningful incoming or successfully sent outgoing message in each synced conversation. Reading alone does not remove a follow-up. A sent answer, marking handled, or snoozing it changes the list locally; a newer incoming message can bring it back.

Picture text search uses the bundled ML Kit Latin-script model on the phone. It indexes new downloaded pictures, with a lightweight backfill on launch and a manual scan of older downloaded pictures in Settings. Inbox and in-chat search merge text and picture matches. Long-press a photo message to read/copy its recognized text. Indexing does not download remote pictures, and deleting messages removes them from search. Recognition can miss or misread unclear text.

Group chats use a stable collage of participant photos or initials, a group marker and an additional-member count. The group design appears in the inbox and in Android bubble/shortcut icons, with every face inside the adaptive icon’s visible area. Your own and hidden participants are excluded.

## Current limits

This is an unofficial integration using an undocumented Google protocol. Sign-in can be rejected and protocol changes can require an update or re-pairing. Pairing and sustained background operation have not yet been verified on the user's OnePlus 15 / ColorOS 17.

History paging follows the responses Google supplies; reaching the end is **not proof of a complete phone archive**. SMS fallback reaction text can still be present when Google exposes it as a regular SMS instead of structured reaction metadata. Structured reactions use exact IDs and do not guess matches from quoted text.

Media viewing/sending, voice messages, new groups, scheduled text sending, and a durable offline text outbox are implemented. An unread launcher number remains outside the agreed scope. Ambiguous message-send outcomes are not automatically retried.

Reply navigation uses provider message IDs and exact identity aliases, with saved references retained across read/delivery updates. Tap an unlinked reply to refresh its source page. Native Google Messages may have a quote that its paired connection does not expose; when no usable reference is supplied, you can choose the actual earlier message and save that link locally. The app does not infer the original from nearby messages or similar text.

Read receipts are off per chat unless enabled. Enable Google's own **RCS chats → Send read receipts** setting too. Viewed-message read requests are persisted and retried; chat details show queued, failed/retrying, setting-blocked, and Google-accepted status. An accepted request does not prove the other phone displayed a receipt. SMS/MMS has no sender read receipts.

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
