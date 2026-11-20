# Translation comparisons and limits

The stronger mode replaces the compact sentence translator with a higher-capacity TranslateGemma 4B model, using Q8_0 weights rather than the smaller Q4 candidate. The 4.1 GB model is optional and downloaded only after an in-app Wi-Fi confirmation. Text stays on the phone. It takes more memory and processing time; compact mode and its existing packs remain available.

The comparison uses 18 synthetic English/Spanish messages, the actual pinned model files, the same language pair in both engines, and the production stronger decoding core. The complete inputs and outputs are in [translation-comparison.json](translation-comparison.json). This is a small developer corpus, not a general accuracy score. Host per-request timing excludes model loading and does not predict phone speed.

| Original | Compact output | Stronger output |
| --- | --- | --- |
| I'll take a rain check. | Tomaré un chequeo de lluvia. | Lo posponemos. |
| No manches. | Don't stain. | No way. |
| Perdón, se me fue el avión. ¿Qué estabas diciendo? | Sorry, I'm out of the plane. What were you saying? | Excuse me, I seem to have lost my train of thought. What were you saying? |
| Tell Sam I'll be there at 7:30 🙂 … | Dropped the emoji. | Preserved the emoji, time and exact URL. |

## Texting tone and speed (1.5.77)

What you send through the stronger translator now goes with a note that it's a casual text between friends or family, in the tone it was written in. For Spanish, the note asks for Latin American Spanish with tú (and ustedes, never vosotros), the masculine form when gender isn't clear, and the same emoji. Afterwards the app tidies Spanish output: one gender form instead of both (`agotado/a` becomes `agotado`), and a `¡` or `¿` that was never closed gets its `!` or `?`. If the casual answer changes a link, code, time or emoji, or starts repeating itself, the app tries once more with the plain prompt before giving up.

What you read keeps the plain prompt. Casual wording made English from Spanish read more naturally ("Did you get here yet?", "Who's picking up the kids?"), but it was less faithful to slang. For example, `se me fue el avión` became "I missed my flight" instead of "I lost my train of thought".

The engine also keeps its working memory between messages, with one slot for each direction (their Spanish in, your English out), in the same memory as before. Each direction's prompt is the same every time, so the next message only reads the message itself. The first message after loading still reads the whole prompt. The model stays loaded for 3 minutes after a translation, or 1 minute after you leave the app.

Comparison on 65 synthetic texts (`tools/quality-eval/cases.tsv`), using the pinned model on a GitHub Actions runner (CPU only; phone times differ):

| What you send | Before | Now |
| --- | --- | --- |
| No worries, take your time! | No se preocupe, tómese su tiempo. | Tranquilo, ¡tómate tu tiempo! |
| It's pouring here, drive safe | Está lloviendo a cántaros aquí, conduzca con precaución. | Está lloviendo a cántaros aquí, conduce con cuidado. |
| Can you grab some milk on your way home? | ¿Podrías comprar un poco de leche cuando vayas a casa? | ¿Puedes comprar leche cuando vayas a casa? |
| Hang in there, it'll get better | Mantén la esperanza, las cosas mejorarán. | Aguanta, ¡todo va a mejorar! |
| My bad, I totally forgot to text you back | Perdón, olvidé completamente enviarte un mensaje. | Perdón, ¡me olvidé totalmente de mandarte un mensaje! |
| Can't, I'm stuck at work | No puedo, estoy atado al trabajo. | No puedo, estoy atascado en el trabajo. |
| I'll take a rain check. | Lo posponemos. | Te lo dejo para otro momento. |

Speed, alternating directions the way a conversation does: before this change, every message took 5–9 s on the runner, because the engine read the whole prompt each time. Now, after the first message, each took 1.3–2.4 s (median 1.9 s), and the translations were identical either way. The harness is `tools/quality-eval` and its workflow `translation-eval.yml`.

Messages you type in the language a chat sends in now go as written. The app uses language ID to decide. An ordinary message takes language ID's usual confident answer. A short message such as `ya voy` needs a very confident answer. A word or two, such as `ok` or `sí`, is translated as before. A translation that comes back the same as what you typed, apart from punctuation, case or emoji, is also sent as typed.

## Speed on phones (1.5.79)

Until 1.5.78, the engine was built for plain `armv8-a`, so it couldn't use the dot-product instructions that phones since about 2018 have. It now builds for `armv8.2-a+dotprod+fp16`, and rearranges the weights at load for those instructions. The rearranged weights stay in memory instead of being read from the file as needed. If there isn't room, it loads them as stored. On phones without these instructions, the stronger translator isn't offered.

Measured with `tools/quality-eval` on GitHub's ARM runners (Neoverse, 4 cores), alternating directions:

| Build | Writing | Reading the prompt | Message after the first | First message |
| --- | --- | --- | --- | --- |
| `armv8-a` (1.5.78) | 7.3 tokens/s | 12 tokens/s | 2.5 s | 11.5 s |
| `armv8.2-a+dotprod+fp16` | 10.9 tokens/s | 28 tokens/s | 1.4 s | 5.3 s |
| the same, weights rearranged (1.5.79) | 15.4 tokens/s | 56 tokens/s | 0.95 s | 2.8 s |
| `armv8.6-a` (i8mm), weights rearranged | 15.4 tokens/s | 70 tokens/s | 0.9 s | 2.4 s |

The translations were identical in every build. i8mm only sped up reading, which the kept instruction mostly avoids, so the app doesn't require it. The engine now uses only the phone's fast cores (at least 70% of the fastest core's top speed), up to 6.

Two changes make the wait shorter on top of this. Opening a chat that translates what you send loads the model and reads its instruction in the background. With the stronger translator, a draft is also translated in the background once you pause typing, so Send can use it straight away. Each translation's timing (numbers only, never text) goes in the diagnostics journal.

From 1.5.80, tapping Send with the stronger translator puts the message in the chat at once, showing what you wrote, as a translating outbox item. The translation runs in the background, even after you leave the chat, and then replaces the text before the message goes. Later messages in the same chat wait behind it, so they stay in order. Taking it back stops the translation. If the translation fails a check, the message waits for you instead of going out untranslated.

## Known limits

Casual wording reads more naturally, but it is not more accurate overall; it makes different mistakes. On the comparison set:
- "Thanks for having us" became `invitarlo` (inviting him), not `invitarnos` (inviting us).
- "Are you guys coming Saturday?" became a question about going, both before and now.
- For "I", Spanish sometimes uses the feminine form (`estoy harta`).

When reading, `padre` (cool) still comes out as "Dad", and `qué oso` (how embarrassing) as "that's hilarious".

## Android validation

The dedicated `quality-smoke` job streams the pinned, SHA-256 checked model directly into an Android emulator. It checks real JNI inference on conversational phrases, negation and embedded literals, verifies that instructions inside a message are translated, exercises native cancellation, sends a translated message into an offline synthetic outbox with one tap, and checks native unload. It never signs in, pairs with a phone, or transmits a message. The usual complete app smoke suite and Android unit/lint/build checks also gate release.

The model is a derivative of Google TranslateGemma by Menterium IT Solutions GmbH, at revision `d1709f49e90452b9189e0144cc6826d199616921`, SHA-256 `cb8ef0b14bc7f80297ac219399fb17c6504742b00b6b4c8173069fd5d55b1063`. llama.cpp is source-built at `78e05298f0d68abff41d08d79fa7469264b2aff0`. Terms and notices are in `quality-native/` and in the app.
