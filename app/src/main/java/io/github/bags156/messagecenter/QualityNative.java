package io.github.bags156.messagecenter;

import java.util.function.BooleanSupplier;

/** Source-built llama.cpp. All calls, including unload, belong on Translation.worker. */
final class QualityNative {
    static { System.loadLibrary("message-quality"); }
    static native byte[] translate(byte[] prefix,byte[] text,byte[] suffix,byte[] model,BooleanSupplier cancelled);
    /** Reads an instruction in ahead of time (loading the model if needed), so the next message with it only reads itself. */
    static native void prime(byte[] prefix,byte[] model,BooleanSupplier cancelled);
    /** The last translation's numbers, never its text: tokens reused, read and written, ms reading and writing, threads. */
    static native long[] timing();
    static native void unload();
    static native boolean loaded();
    private QualityNative() {}
}
