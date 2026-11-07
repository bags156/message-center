Real photos instead of blocks.

- **Fixed blocky photos.** For some photos Google puts a tiny placeholder image (a few pixels wide) inside the message and keeps the real photo on your phone. Since 0.7.2 the app treated that placeholder as the photo: it was stretched into big blocks and the real photo was never asked for. Now the placeholder is only a preview (shown blurred, not blocky), your phone is asked for the real photo, and it replaces the preview when it arrives.
- Photos that are still loading say "Loading full photo…" at the bottom. If your phone doesn't send one, the photo says so; tap it for the reason and Retry.
- Photos already shown as blocks are fixed too: the bad copies are no longer used.

Install over 0.8.2; the package and signing key are unchanged.
