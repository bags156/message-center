Message Center 1.5.29.

- Fixed: the send button could stop responding. Sending had to wait for whatever the app was loading, so a load that hung (often right after a reconnect) left Send dead until the app was closed. Now sending never waits on a load, and a load that hasn't answered after a minute stops holding anything up.
- Texts now go out on their own line to Google. A slow load, a long photo download or a reconnect can't hold up a message you sent.
- If a request to Google gets stuck for 90 seconds, the app starts the connection over instead of letting everything wait behind it.
- Opening the app while Google Messages on your phone isn't answering no longer starts a fresh connection. That wasn't needed, and it could make things wait longer.

Install over the existing app through Obtainium. Your pairing, conversations and settings are retained.
