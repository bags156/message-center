# OnePlus phone test

This build is experimental. Do not rely on it for alerts until these checks pass on the phone.

1. Update with the signed APK. Open the app and pair through Google sign-in and Google's matching-emoji approval. Do not share credentials/cookies in reports.
2. Confirm that inbox conversations load. Open a known older chat and load several **Older messages** pages. Compare actual messages and dates against Google Messages. The app must label the end of Google's supplied pages without claiming complete archive coverage.
3. Send a test to your own number or a person who agrees to help. Confirm actual receipt and Google-reported status. Create a new one-to-one conversation in the app; it should stay inside Message Center. Test SMS and RCS separately.
4. Have that person react to a known message. Confirm the emoji appears on that original message and tapping it shows the correct actor. Change/remove the reaction and refresh. Test an iPhone RCS conversation and SMS fallback separately; fallback may arrive without structured metadata.
5. Allow Message Center notifications. Disable **Google Messages notifications only**, leaving Google Messages default and allowed to run in the background. Receive another message with Message Center closed. Confirm a Message Center alert appears and opens the correct chat.
6. Repeat after screen lock, at least 30 minutes idle, Wi-Fi/mobile-data switching, and a reboot/unlock. Check ColorOS background activity, auto-launch, and battery settings for both apps if delivery pauses.
7. Restart Message Center and confirm pairing/cache survive. Test a temporary network outage; do not resend an ambiguous send until checking Google Messages to avoid duplicates.

Report which numbered check failed, the app version, and the exact error wording. Redact message contents, phone numbers, names, Google account details, and pairing information before sharing screenshots.

To stop the experiment, use **Settings → Stop background connection**. To remove its local synced data, choose **Forget pairing and synced messages**, and revoke the paired entry in Google Messages. Google's own chats are retained.
