Fixes getting stuck while signing in to Google.

- After you approve the sign-in on your phone, Google can show optional prompts (add a profile photo, confirm recovery info, create a passkey) that could not be dismissed inside the app. Once Google has signed you in, Message Center now moves past these on its own and continues to pairing.
- Sign-in could load forever after approval because the app blocked a Google redirect it passes through (accounts.youtube.com) and some Google page frames. Those are now allowed; non-Google pages stay blocked.
- New **Continue ›** button on the sign-in screen: if a page still stalls after you're signed in, tap it to go straight to pairing.
- The line above the sign-in page now shows the current Google page (host and path only), and says when a non-Google page was blocked. If sign-in still fails, that line shows where.

Install over 0.2.0. The package and signing key are unchanged. Phone testing is still required; offline tests do not prove Google sign-in or live delivery.
