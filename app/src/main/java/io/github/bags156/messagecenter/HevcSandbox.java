package io.github.bags156.messagecenter;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SharedMemory;
import android.system.OsConstants;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The app's side of HevcDecoderService: hands an HEVC picture to the isolated decoder process
 * and reads back its planes. Call from a background thread; it waits for the reply.
 */
final class HevcSandbox {
    static final class Picture {
        int chroma;
        final int[] widths = new int[3], heights = new int[3];
        final byte[][] planes = new byte[3][];
    }

    private static volatile Context app;
    private static final Object lock = new Object();
    private static IBinder binder;
    private static ServiceConnection connection;

    static void init(Context context) { if (app == null) app = context.getApplicationContext(); }

    /** The decoded picture, or null (with HeifDecoder.lastProblem set) if the sandbox couldn't decode it. */
    static Picture decode(byte[] stream) {
        if (stream.length == 0 || stream.length > HevcDecoderService.MAX_INPUT) return null;
        IBinder b = connect();
        if (b == null) return null;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        SharedMemory in = null, out = null;
        try {
            in = SharedMemory.create("hevc-stream", stream.length);
            ByteBuffer m = in.mapReadWrite(); m.put(stream); SharedMemory.unmap(m);
            in.setProtect(OsConstants.PROT_READ);
            data.writeInt(stream.length); data.writeParcelable(in, 0);
            if (!b.transact(HevcDecoderService.DECODE, data, reply, 0)) { HeifDecoder.lastProblem = "sandbox refused the call"; return null; }
            if (reply.readInt() != 0) { HeifDecoder.lastProblem = "sandbox: " + reply.readString(); return null; }
            Picture p = new Picture(); p.chroma = reply.readInt();
            int planes = p.chroma == 0 ? 1 : 3; long total = 0;
            for (int i = 0; i < planes; i++) {
                p.widths[i] = reply.readInt(); p.heights[i] = reply.readInt();
                if (p.widths[i] <= 0 || p.heights[i] <= 0) throw new IllegalStateException("bad plane size");
                total += (long) p.widths[i] * p.heights[i];
            }
            out = reply.readParcelable(SharedMemory.class.getClassLoader(), SharedMemory.class);
            if (out == null || total > out.getSize()) throw new IllegalStateException("picture missing");
            ByteBuffer o = out.mapReadOnly();
            for (int i = 0; i < planes; i++) { p.planes[i] = new byte[p.widths[i] * p.heights[i]]; o.get(p.planes[i]); }
            SharedMemory.unmap(o);
            return p;
        } catch (android.os.DeadObjectException e) {
            // The decoder process crashed (a damaged or hostile file); the next picture gets a fresh one.
            HeifDecoder.lastProblem = "sandbox stopped";
            synchronized (lock) { binder = null; }
            return null;
        } catch (Exception | OutOfMemoryError e) {
            HeifDecoder.lastProblem = "sandbox: " + e;
            return null;
        } finally {
            data.recycle(); reply.recycle();
            if (in != null) in.close();
            if (out != null) out.close();
        }
    }

    private static IBinder connect() {
        Context context = app;
        if (context == null) { HeifDecoder.lastProblem = "sandbox not set up"; return null; }
        if (Looper.myLooper() == Looper.getMainLooper()) { HeifDecoder.lastProblem = "sandbox called on the main thread"; return null; }
        CountDownLatch ready;
        synchronized (lock) {
            if (binder != null && binder.isBinderAlive()) return binder;
            binder = null;
            if (connection != null) { try { context.unbindService(connection); } catch (Exception ignored) {} connection = null; }
            CountDownLatch latch = new CountDownLatch(1); ready = latch;
            ServiceConnection c = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder service) { synchronized (lock) { binder = service; } latch.countDown(); }
                @Override public void onServiceDisconnected(ComponentName name) { synchronized (lock) { binder = null; } }
                @Override public void onBindingDied(ComponentName name) { synchronized (lock) { binder = null; } latch.countDown(); }
                @Override public void onNullBinding(ComponentName name) { latch.countDown(); }
            };
            if (!context.bindService(new Intent(context, HevcDecoderService.class), c, Context.BIND_AUTO_CREATE)) { HeifDecoder.lastProblem = "sandbox wouldn't start"; return null; }
            connection = c;
        }
        try { if (!ready.await(8, TimeUnit.SECONDS)) { HeifDecoder.lastProblem = "sandbox didn't start in time"; return null; } }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        synchronized (lock) { return binder; }
    }
}
