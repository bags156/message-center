Experimental on-device paired client: replaces the earlier notification-companion foundation.

Install this signed update over 0.1.x, open **Connect Google Messages**, sign in to the account selected in Google's **Device pairing**, approve the matching emoji there, and return. Allow Message Center notifications and background activity. No separate server is required. Google Messages must remain installed, default, and online.

Adds a native text composer/new one-to-one conversations, paged history, structured reactions attached by message ID with tappable actor lists, and an independent connection/notification channel. Google session credentials are saved with Android Keystore encryption. New pairing replaces this app's synced cache, without deleting Google Messages chats.

**Phone testing is required.** Offline tests do not prove Google sign-in, live SMS/RCS delivery, complete history, or ColorOS background reliability. Google can block embedded-browser sign-in. History coverage is limited to Google's supplied pages. Attachments are labels only; media sending/viewing and new groups are not implemented. SMS fallback reaction text may remain when the source provides no structured reaction metadata. There is no offline send queue; ambiguous sends are not automatically retried.

For Obtainium, enable **Include prereleases** to receive this beta. The permanent package and signing key are unchanged. Do not uninstall the earlier app first.
