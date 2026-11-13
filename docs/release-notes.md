Message Center 1.5.28.

- Steadier connection to Google Messages:
  - When it drops, it reconnects after 5 seconds, then 15, 30 and 60. After that it tries every 5 minutes at most, where it used to wait as long as 30.
  - It reconnects right away when your network comes back or switches (Wi-Fi to mobile data, say), when you turn the screen on, and when you open the app.
  - A watchdog notices when the connection looks fine but has quietly died, which can happen after a network switch, and reopens it.
  - Short blips aren't announced. The "reconnecting" line only shows if it's still down after about 20 seconds.
- In the black theme, the note editor, the notes list, the notes card in Details and the settings pages now match the rest of the app. The screen stays jet black, and cards use the same near-black as before, not a lighter gray.

Install over the existing app through Obtainium. Your pairing, conversations and settings are retained.
