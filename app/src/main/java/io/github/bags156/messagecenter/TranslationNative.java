package io.github.bags156.messagecenter;

/** Source-built Bergamot + CLD2. Calls belong on Translation.worker, never the UI thread. */
final class TranslationNative {
    static { System.loadLibrary("message-translation"); }
    static native byte[] detect(byte[] utf8);
    static native byte[] translate(byte[] utf8, byte[] source, byte[] target, byte[] model, byte[] vocab, byte[] shortlist);
    /** Lets go of the models kept loaded between translations. */
    static native void unload();
    private TranslationNative() {}
}
