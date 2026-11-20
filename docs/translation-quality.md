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

Stronger mode now tells the model that it is translating a casual text between friends or family, in the tone it was written in. For Spanish it asks for Latin American Spanish with tú (and ustedes, never vosotros), the masculine form when gender isn't clear, and the same emoji. Afterwards the app tidies Spanish output: one gender form instead of both (`agotado/a` becomes `agotado`), and a `¡` or `¿` that was never closed gets its `!` or `?`. If the casual answer changes a link, code, time or emoji, or starts repeating itself, the app tries once more with the plain prompt before giving up.

The engine also keeps its working memory between messages. The prompt for a language pair is the same every time, so the next message in the same direction only reads the message itself. The first message after loading, or after switching direction, still reads the whole prompt. The model stays loaded for 3 minutes after a translation, or 1 minute after you leave the app.

Comparison on 62 synthetic texts (`tools/quality-eval/cases.tsv`), using the pinned model on a GitHub Actions runner (CPU only; phone times differ):

| Original | Before | Now |
| --- | --- | --- |
| No worries, take your time! | No se preocupe, tómese su tiempo. | Tranquilo, ¡tómate tu tiempo! |
| It's pouring here, drive safe | Está lloviendo a cántaros aquí, conduzca con precaución. | Está lloviendo a cántaros aquí, conduce con cuidado. |
| Can you grab some milk on your way home? | ¿Podrías comprar un poco de leche cuando vayas a casa? | ¿Puedes comprar leche cuando vayas a casa? |
| Hang in there, it'll get better | Mantén la esperanza, las cosas mejorarán. | Aguanta, ¡todo va a mejorar! |
| Ahorita voy | I'm going now. | I'm on my way |
| ¿Quién va a recoger a los niños? | Who is going to pick up the children? | Who's going to pick up the kids? |
| Mándame foto porfa | Please send me a photo. | Send me a pic, please. |

Median time per message on the runner: 5.1–5.7 s when the engine starts fresh each time, 1.7–2.6 s when it keeps its memory. The output is identical either way. The harness is `tools/quality-eval` and its workflow `translation-eval.yml`.

Messages you type in the language a chat sends in now go as written. The app uses language ID to decide. An ordinary message takes language ID's usual confident answer. A short message such as `ya voy` needs a very confident answer. A word or two, such as `ok` or `sí`, is translated as before. A translation that comes back the same as what you typed, apart from punctuation, case or emoji, is also sent as typed.

## Known limits

The casual prompt reads more naturally but is not more accurate overall. It made about as many meaning errors as the plain prompt on the comparison set, just different ones:
- `padre` (cool) still came out as "Dad", and `qué oso` (how embarrassing) as "that's hilarious".
- `Ya ni modo` and `Bien, gracias a Dios` were mistranslated.
- "Are you guys coming Saturday?" became a question about going.
- For "I", Spanish sometimes uses the feminine form (`estoy harta`).

The stronger model still makes mistakes. For `Te marco cuando salga.`, the compact model produced `I frame you when I get out.` and the stronger model produced `I'll call you as soon as I have the information.` The phrase needs context to establish what `salga` refers to, and the stronger output adds an interpretation. Neither result should be treated as a verified translation. Some inputs also produce alternative phrasings instead of a single version. The app does not remove alternatives heuristically because that could discard part of a real message.

Declaring **I write in** avoids a separate source-language detection failure for outgoing drafts. **Send in** still belongs to the individual chat, and Send automatically translates once (or, with Check before sending on, shows the translation first). What you wrote stays on the phone: Show what you wrote under a sent translation. Stronger mode validates URLs, emails, long digit codes, times and emoji; failed integrity or output checks retain the source draft. These checks cannot establish semantic correctness. Original incoming messages stay available through Show original.

## Android validation

The dedicated `quality-smoke` job streams the pinned, SHA-256 checked model directly into an Android emulator. It checks real JNI inference on conversational phrases, negation and embedded literals, verifies that instructions inside a message are translated, exercises native cancellation, sends a translated message into an offline synthetic outbox with one tap, and checks native unload. It never signs in, pairs with a phone, or transmits a message. The usual complete app smoke suite and Android unit/lint/build checks also gate release.

The model is a derivative of Google TranslateGemma by Menterium IT Solutions GmbH, at revision `d1709f49e90452b9189e0144cc6826d199616921`, SHA-256 `cb8ef0b14bc7f80297ac219399fb17c6504742b00b6b4c8173069fd5d55b1063`. llama.cpp is source-built at `78e05298f0d68abff41d08d79fa7469264b2aff0`. Terms and notices are in `quality-native/` and in the app.
