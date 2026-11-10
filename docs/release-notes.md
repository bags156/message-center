Message Center 1.4.21.

**Photo with text sending slowly**
- A photo and the text written with it now send as two separate messages: photo first, then text. This avoids a mixed media/text request that can leave Google Messages sending for minutes or deliver only the text.
- The same change applies to videos and other attachments with text. Photo-only sends and photo/video preparation are unchanged.
- If the photo is accepted but the separate text fails, the text is saved with its own status. Retrying it sends only the text, never another copy of the photo. If its outcome is uncertain, check Google Messages before choosing to retry; the app does not automatically resend it.

Regression tests cover separate requests, order, photo-only sends, partial failures and duplicate prevention. Android device checks cover saving and retrying the text separately. These checks use synthetic data; actual delivery speed still needs confirmation on a paired phone.

Install over the existing app; the package and signing key are unchanged. No re-pairing is needed for this change.
