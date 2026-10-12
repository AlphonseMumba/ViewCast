package com.example.viewcast.security;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Hachage SHA-256 et HKDF-SHA256 (RFC 5869). */
final class Kdf {
    private Kdf() {}

    static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] hmac(byte[] key, byte[]... parts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] p : parts) mac.update(p);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** HKDF avec une seule sortie de 32 octets (suffisant pour une clé AES-256). */
    static byte[] hkdf32(byte[] ikm, byte[] salt, byte[] info) {
        byte[] prk = hmac(salt, ikm);
        return hmac(prk, info, new byte[] {1});
    }
}
