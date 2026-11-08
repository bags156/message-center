package io.github.bags156.messagecenter;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SharedMemory;
import io.github.bags156.gm.bridge.Bridge;
import io.github.bags156.gm.bridge.HEVCPicture;
import java.nio.ByteBuffer;

/**
 * Runs the software HEVC decoder (libde265) in an isolated process: no permissions, no
 * network, no access to Message Center's files or messages. The pictures it decodes come
 * from whoever sent them, so a flaw in the decoder can't reach anything that matters.
 * Input and output travel as shared memory; see HevcSandbox for the caller.
 */
public final class HevcDecoderService extends Service {
    static final int DECODE = IBinder.FIRST_CALL_TRANSACTION;
    static final int MAX_INPUT = 32 << 20;

    private final Binder binder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code != DECODE) {
                try { return super.onTransact(code, data, reply, flags); } catch (Exception e) { return false; }
            }
            SharedMemory in = null, out = null;
            try {
                int length = data.readInt();
                in = data.readParcelable(SharedMemory.class.getClassLoader(), SharedMemory.class);
                if (in == null || length <= 0 || length > MAX_INPUT || length > in.getSize()) throw new IllegalArgumentException("bad input");
                ByteBuffer src = in.mapReadOnly(); byte[] stream = new byte[length]; src.get(stream); SharedMemory.unmap(src);
                HEVCPicture p = Bridge.decodeHEVC(stream);
                int chroma = (int) p.chroma(), planes = chroma == 0 ? 1 : 3; long total = 0;
                for (int i = 0; i < planes; i++) total += p.width(i) * p.height(i);
                if (total <= 0 || total > Integer.MAX_VALUE) throw new IllegalStateException("bad picture size");
                out = SharedMemory.create("hevc-picture", (int) total);
                ByteBuffer dst = out.mapReadWrite();
                for (int i = 0; i < planes; i++) dst.put(p.plane(i));
                SharedMemory.unmap(dst);
                reply.writeInt(0); reply.writeInt(chroma);
                for (int i = 0; i < planes; i++) { reply.writeInt((int) p.width(i)); reply.writeInt((int) p.height(i)); }
                reply.writeParcelable(out, 0); // the parcel keeps its own copy of the descriptor
            } catch (Exception | OutOfMemoryError e) {
                reply.setDataPosition(0); reply.setDataSize(0);
                reply.writeInt(-1); reply.writeString(String.valueOf(e.getMessage()));
            } finally {
                if (in != null) in.close();
                if (out != null) out.close();
            }
            return true;
        }
    };

    @Override public IBinder onBind(Intent intent) { return binder; }
}
