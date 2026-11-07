package io.github.bags156.messagecenter;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Auth/session credentials are encrypted with a non-exportable Android Keystore key. */
final class SessionVault {
    private static final String ALIAS="message-center-paired-session-v1";
    private final AtomicFile file;
    SessionVault(Context context) { file=new AtomicFile(new File(context.getNoBackupFilesDir(),"paired-session.enc")); }
    boolean exists() { return file.getBaseFile().exists(); }
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if(store.containsAlias(ALIAS)) return (SecretKey)store.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return generator.generateKey();
    }
    synchronized void save(String value) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)),iv=cipher.getIV();
        byte[] data=ByteBuffer.allocate(1+iv.length+encrypted.length).put((byte)iv.length).put(iv).put(encrypted).array();
        FileOutputStream stream=file.startWrite();
        try {stream.write(data);file.finishWrite(stream);} catch(Exception e) {file.failWrite(stream);throw e;}
    }
    synchronized String load() throws Exception {
        if(!exists()) return "";
        ByteBuffer data=ByteBuffer.wrap(file.readFully());int size=Byte.toUnsignedInt(data.get());
        if(size!=12 || data.remaining()<size+16) throw new IllegalStateException("Invalid session file");
        byte[] iv=new byte[size];data.get(iv);byte[] encrypted=new byte[data.remaining()];data.get(encrypted);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));
        return new String(cipher.doFinal(encrypted),StandardCharsets.UTF_8);
    }
    synchronized void clear() { file.delete(); }
}
