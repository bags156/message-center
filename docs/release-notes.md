Message Center 0.1.1 — reaction display and capture clarity.

- Recognize Google Messages' `❤️ to “quoted text”` fallback format, other emoji sequences, and common reaction removal formats.
- Attach reactions to one exact captured original and show reported reactor names when the emoji is tapped.
- If an original is missing or ambiguous, show a clearly labeled quote card with the reaction. The card does not claim to recover the original message, sender, or timestamp.
- Reprocess previously saved notification text with the new reaction renderer; no local-history reset required.
- Read message context directly from notification message bundles, with style recovery as a fallback.
- Clearly identify Android-redacted content instead of presenting a blank conversation title.
- Add **Refresh current notifications** and **Load earlier captured messages**.
- Display reply bodies with an explicit missing-original label when Google Messages supplies no reply reference.
- Keep the existing application ID, signing key, and saved local history for in-place Obtainium updates.

**Limits:** old Google Messages / RCS history cannot be imported, and Android-protected content cannot be recovered. Refresh only reads currently available notifications. This remains a notification-based companion, not a full Google Messages replacement. Attachments, native RCS reaction metadata, and read/delivery receipts may be unavailable. Opening the companion does not mark messages read in Google Messages. No unread badge.

In Obtainium, add `https://github.com/bags156/message-center` and select `message-center.apk`.
