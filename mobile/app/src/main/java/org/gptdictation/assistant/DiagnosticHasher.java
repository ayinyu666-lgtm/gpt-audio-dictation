package org.gptdictation.assistant;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class DiagnosticHasher {
    private final Mac mac;
    DiagnosticHasher(byte[] key) throws GeneralSecurityException {
        mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
    }
    String hash(CharSequence value) {
        if (value == null || value.length() == 0) return "";
        byte[] bytes = mac.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
        char[] result = new char[bytes.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) { result[i * 2] = digits[(bytes[i] & 255) >>> 4]; result[i * 2 + 1] = digits[bytes[i] & 15]; }
        return new String(result);
    }
}
