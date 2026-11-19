# Translation comparisons and limits

The stronger mode replaces the compact sentence translator with a higher-capacity TranslateGemma 4B model, using Q8_0 weights rather than the smaller Q4 candidate. The 4.1 GB model is optional and downloaded only after an in-app Wi-Fi confirmation. Text stays on the phone. It takes more memory and processing time; compact mode and its existing packs remain available.

The comparison uses 18 synthetic English/Spanish messages, the actual pinned model files, the same language pair in both engines, and the production stronger decoding core. The complete inputs and outputs are in [translation-comparison.json](translation-comparison.json). This is a small developer corpus, not a general accuracy score. Host per-request timing excludes model loading and does not predict phone speed.

| Original | Compact output | Stronger output |
| --- | --- | --- |
| I'll take a rain check. | Tomaré un chequeo de lluvia. | Lo posponemos. |
| No manches. | Don't stain. | No way. |
| Perdón, se me fue el avión. ¿Qué estabas diciendo? | Sorry, I'm out of the plane. What were you saying? | Excuse me, I seem to have lost my train of thought. What were you saying? |
| Tell Sam I'll be there at 7:30 🙂 … | Dropped the emoji. | Preserved the emoji, time and exact URL. |

## Known limits

The stronger model still makes mistakes. For `Te marco cuando salga.`, the compact model produced `I frame you when I get out.` and the stronger model produced `I'll call you as soon as I have the information.` The phrase needs context to establish what `salga` refers to, and the stronger output adds an interpretation. Neither result should be treated as a verified translation. Some inputs also produce alternative phrasings instead of a single version. The app does not remove alternatives heuristically because that could discard part of a real message.

Declaring **I write in** avoids a separate source-language detection failure for outgoing drafts. **Send in** still belongs to the individual chat, and Send automatically translates once. Stronger mode validates URLs, emails, long digit codes, times and emoji; failed integrity or output checks retain the source draft. These checks cannot establish semantic correctness. Original incoming messages stay available through Show original.

## Android validation

The dedicated `quality-smoke` job streams the pinned, SHA-256 checked model directly into an Android emulator. It checks real JNI inference on conversational phrases, negation and embedded literals, verifies that instructions inside a message are translated, exercises native cancellation, sends a translated message into an offline synthetic outbox with one tap, and checks native unload. It never signs in, pairs with a phone, or transmits a message. The usual complete app smoke suite and Android unit/lint/build checks also gate release.

The model is a derivative of Google TranslateGemma by Menterium IT Solutions GmbH, at revision `d1709f49e90452b9189e0144cc6826d199616921`, SHA-256 `cb8ef0b14bc7f80297ac219399fb17c6504742b00b6b4c8173069fd5d55b1063`. llama.cpp is source-built at `78e05298f0d68abff41d08d79fa7469264b2aff0`. Terms and notices are in `quality-native/` and in the app.
