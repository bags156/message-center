Message Center 1.0.1.

**Genmoji and iPhone stickers**
- Custom emoji made on an iPhone (Genmoji) and iPhone stickers now show up. iPhones send them as a special HEIC picture with several sizes and a see-through layer, which Android's own image decoder can't read (Google Messages shows "Can't load image" for them). Message Center now reads these files itself, using your phone's video decoder.
- See-through pictures (Genmoji, stickers, transparent PNGs) sit on the chat like a big emoji instead of inside a photo frame.
- Other HEIC photos Android refuses (tiled, rotated, or cropped ones) are decoded the same way as a last resort.

**Fixes**
- **Unread reminders** now count from when a message reached Message Center (or when you pinned the chat or marked it unread), not from when it was sent. A message that arrived late, after the app had been closed or your phone was offline, no longer triggers a reminder early.
- **Photos that won't open** keep showing their preview instead of a black screen, and the message now says what the downloaded file actually is. A broken download is thrown away so opening it again fetches a fresh copy. Photos that Android's newer decoder rejects are tried with the older one.
- Fixed a rare crash when two parts of the app opened the message database at the same moment on first launch.

Install over 1.0.0; the package and signing key are unchanged.

1.0.0 added Promotions, Unsubscribe, smart taps, quick replies, keyword alerts, busy auto-reply, and search filters; see the 1.0.0 release for details.
