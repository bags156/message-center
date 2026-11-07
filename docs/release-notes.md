Reply references and outgoing read requests.

- Preserve an original-message reference when a later read/delivery update omits it. Read/delivery-only updates also retain the saved reply text.
- Keep message references carried in the protocol's reply metadata on both live updates and history pages, and resolve exact IDs/aliases within the same conversation.
- Tapping an unlinked reply refreshes its source page instead of blaming the sender's phone. If Google's paired data still supplies no usable reference, **Choose original** lets you select the actual earlier message and keep that link on this phone. Connection details can be copied without message text, names, numbers, credentials, or message IDs. Native Google Messages can have information the paired connection does not expose; automatic recovery is not guaranteed for that case.
- Read requests for chats you enable are now saved when you view a message, including offline. They retry after failures and reconnection. An older request cannot clear a newer viewed message or an explicit retry. The app no longer silently drops failed read requests.
- Chat details show whether a read request is queued, retrying, blocked by Google's setting, or accepted by Google, with a retry/refresh action. **Accepted by Google is not confirmation that the other phone displayed a read receipt.**
- Read receipts require RCS, **Google Messages → Messages settings → RCS chats → Send read receipts** to be on, and this app's per-chat receipt setting to be on. SMS/MMS does not support sender read receipts. Google keeps control of that master setting.

Install over 0.8.3; the package and signing key are unchanged. No re-pairing is required. Confirm reply navigation and receipt arrival on your own phone after installing; automated checks use synthetic messages and do not send real texts or receipts.
