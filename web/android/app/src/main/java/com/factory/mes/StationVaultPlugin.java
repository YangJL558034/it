package com.factory.mes;
import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypted terminal credentials only. Never stores passwords. */
@CapacitorPlugin(name = "StationVault")
public class StationVaultPlugin extends Plugin {
    private static final String ALIAS = "mes-station-v1";
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
    @PluginMethod public void set(PluginCall call) {
        try {
            String value = call.getString("value");
            if (value == null) { call.reject("Missing credential"); return; }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
            String encrypted = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "." +
                Base64.encodeToString(cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)), Base64.NO_WRAP);
            if (!getContext().getSharedPreferences("mes-vault", Context.MODE_PRIVATE).edit().putString("credential", encrypted).commit())
                throw new Exception("Storage unavailable");
            call.resolve();
        } catch (Exception ex) { call.reject("无法安全保存终端登录"); }
    }
    @PluginMethod public void get(PluginCall call) {
        try {
            String value = getContext().getSharedPreferences("mes-vault", Context.MODE_PRIVATE).getString("credential", null);
            JSObject result = new JSObject();
            if (value != null) {
                String[] parts = value.split("\\.");
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
                result.put("value", new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), java.nio.charset.StandardCharsets.UTF_8));
            }
            call.resolve(result);
        } catch (Exception ex) { call.reject("终端登录凭据不可用，请重新登录"); }
    }
    @PluginMethod public void clear(PluginCall call) {
        getContext().getSharedPreferences("mes-vault", Context.MODE_PRIVATE).edit().clear().commit();
        call.resolve();
    }
}
