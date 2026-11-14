package io.github.bags156.messagecenter;

import android.content.Context;
import android.content.Intent;
import android.icu.util.ULocale;
import android.os.CancellationSignal;
import android.util.SparseArray;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextLanguage;
import android.view.translation.TranslationCapability;
import android.view.translation.TranslationContext;
import android.view.translation.TranslationManager;
import android.view.translation.TranslationRequest;
import android.view.translation.TranslationRequestValue;
import android.view.translation.TranslationResponseValue;
import android.view.translation.TranslationSpec;
import android.view.translation.Translator;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Translating a message: Android's own on-device translation where the phone has it (nothing is
 * sent anywhere, and the app stays fully open source); otherwise the words go to the phone's
 * translation app (Google Translate shows its pop-up over the chat).
 */
final class Translation {
    private Translation() {}

    static final String GOOGLE_TRANSLATE = "com.google.android.apps.translate";

    /** What a translation came out as. same: it's already in your language (text is empty then). */
    static final class Result { final String text, from; final boolean same; Result(String text, String from, boolean same) { this.text = text; this.from = from; this.same = same; } }

    /** Where the last on-device translation stopped (never the words): for the device checks. */
    static volatile String lastStep = "";

    /** The language the words are in (Android's guess, on the phone), or null. Off the main thread. */
    static ULocale language(Context c, String words) {
        try {
            TextLanguage found = c.getSystemService(TextClassificationManager.class).getTextClassifier().detectLanguage(new TextLanguage.Request.Builder(words).build());
            if (found.getLocaleHypothesisCount() == 0) return null;
            ULocale best = found.getLocale(0);
            return found.getConfidenceScore(best) >= 0.5f ? best : null;
        } catch (Exception e) { return null; }
    }

    /** The words in your language by Android's on-device translation, or null when this phone has none for that language. Off the main thread. */
    static Result onDevice(Context c, String words) {
        ULocale from = language(c, words);
        ULocale to = ULocale.forLocale(Locale.getDefault());
        if (from != null && from.getLanguage().equals(to.getLanguage())) return new Result("", from.getDisplayLanguage(ULocale.forLocale(Locale.getDefault())), true);
        try {
            TranslationManager manager = c.getSystemService(TranslationManager.class);
            if (manager == null || from == null) { lastStep = from == null ? "language unknown" : "no translation service"; return null; }
            int state = -1;
            for (TranslationCapability cap : manager.getOnDeviceTranslationCapabilities(TranslationSpec.DATA_FORMAT_TEXT, TranslationSpec.DATA_FORMAT_TEXT)) {
                if (cap.getSourceSpec().getLocale().getLanguage().equals(from.getLanguage()) && cap.getTargetSpec().getLocale().getLanguage().equals(to.getLanguage())
                    && cap.getState() != TranslationCapability.STATE_NOT_AVAILABLE) state = cap.getState();
            }
            lastStep = "pair state " + state;
            if (state == -1) return null;
            Executor now = Runnable::run;
            TranslationContext context = new TranslationContext.Builder(new TranslationSpec(from, TranslationSpec.DATA_FORMAT_TEXT), new TranslationSpec(to, TranslationSpec.DATA_FORMAT_TEXT)).build();
            AtomicReference<Translator> made = new AtomicReference<>(); CountDownLatch ready = new CountDownLatch(1);
            manager.createOnDeviceTranslator(context, now, t -> { made.set(t); ready.countDown(); });
            if (!ready.await(10, TimeUnit.SECONDS) || made.get() == null) { lastStep += ", no translator"; return null; }
            Translator translator = made.get();
            try {
                AtomicReference<String> said = new AtomicReference<>(); CountDownLatch done = new CountDownLatch(1);
                TranslationRequest request = new TranslationRequest.Builder().setTranslationRequestValues(Collections.singletonList(TranslationRequestValue.forText(words))).build();
                translator.translate(request, new CancellationSignal(), now, response -> {
                    SparseArray<TranslationResponseValue> values = response.getTranslationResponseValues();
                    if (values != null && values.size() > 0 && values.valueAt(0).getText() != null) said.set(values.valueAt(0).getText().toString());
                    if (said.get() != null || response.isFinalResponse()) done.countDown();
                });
                boolean answered = done.await(30, TimeUnit.SECONDS);
                if (!answered || said.get() == null || said.get().trim().isEmpty()) { lastStep += answered ? ", empty answer" : ", no answer in 30 s"; return null; }
                lastStep += ", translated";
                return new Result(said.get(), from.getDisplayLanguage(ULocale.forLocale(Locale.getDefault())), false);
            } finally { translator.destroy(); }
        } catch (Exception e) { lastStep += ", " + e.getClass().getSimpleName(); return null; }
    }

    /** Hands the words to the phone's translation app (Google Translate's pop-up). False when there isn't one. */
    static boolean handOff(Context c, String words) {
        Intent popup = new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setPackage(GOOGLE_TRANSLATE)
            .putExtra(Intent.EXTRA_PROCESS_TEXT, words).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
        Intent any = new Intent(Intent.ACTION_TRANSLATE).putExtra(Intent.EXTRA_TEXT, words);
        for (Intent i : new Intent[]{popup, any}) {
            if (i.resolveActivity(c.getPackageManager()) == null) continue;
            try { c.startActivity(i); return true; } catch (Exception ignored) {}
        }
        return false;
    }
}
