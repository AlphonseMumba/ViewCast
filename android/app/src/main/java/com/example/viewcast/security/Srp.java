package com.example.viewcast.security;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * SRP-6a (PAKE) : prouve la connaissance du code PIN sans jamais l envoyer et sans permettre
 * de le deviner hors ligne. Groupe MODP 2048 bits (RFC 3526 n°14, g=2), hachage SHA-256.
 * Spécification complète : docs/SECURITY.md (le code Python vc_secure.py est identique).
 */
public final class Srp {
    public static final int N_LEN = 256;
    public static final int SALT_LEN = 16;
    static final String IDENTITY = "viewcast";

    static final BigInteger N = new BigInteger("FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA051015728E5A8AACAA68FFFFFFFFFFFFFFFF", 16);
    static final BigInteger G = BigInteger.valueOf(2);
    private static final BigInteger K = new BigInteger(1, Kdf.sha256(pad(N), pad(G)));

    private Srp() {}

    /** Entier -> 256 octets big-endian (zéros à gauche). */
    static byte[] pad(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[N_LEN];
        int len = raw.length;
        int skip = (len > 1 && raw[0] == 0) ? 1 : 0;
        System.arraycopy(raw, skip, out, N_LEN - (len - skip), len - skip);
        return out;
    }

    static BigInteger x(byte[] salt, String pin) {
        byte[] inner = Kdf.sha256((IDENTITY + ":" + pin).getBytes(StandardCharsets.UTF_8));
        return new BigInteger(1, Kdf.sha256(salt, inner));
    }

    static BigInteger u(BigInteger a, BigInteger b) {
        return new BigInteger(1, Kdf.sha256(pad(a), pad(b)));
    }

    static BigInteger randomPrivate(SecureRandom rnd) {
        byte[] r = new byte[32];
        rnd.nextBytes(r);
        return new BigInteger(1, r).add(BigInteger.ONE);
    }

    static byte[] deriveKey(BigInteger s) {
        return Kdf.sha256(pad(s));
    }

    static byte[] proofClient(byte[] salt, BigInteger a, BigInteger b, byte[] key) {
        byte[] hn = Kdf.sha256(pad(N));
        byte[] hg = Kdf.sha256(pad(G));
        byte[] xor = new byte[32];
        for (int i = 0; i < 32; i++) xor[i] = (byte) (hn[i] ^ hg[i]);
        byte[] hi = Kdf.sha256(IDENTITY.getBytes(StandardCharsets.UTF_8));
        return Kdf.sha256(xor, hi, salt, pad(a), pad(b), key);
    }

    static byte[] proofServer(BigInteger a, byte[] m1, byte[] key) {
        return Kdf.sha256(pad(a), m1, key);
    }

    /** Côté initiateur (celui qui saisit le PIN). */
    public static final class Client {
        private final String pin;
        private final BigInteger a;
        public final BigInteger bigA;
        private byte[] salt;
        private BigInteger bigB;
        private byte[] key;
        private byte[] m1;

        public Client(String pin, SecureRandom rnd) { this(pin, randomPrivate(rnd)); }

        Client(String pin, BigInteger privateA) {
            this.pin = pin;
            this.a = privateA;
            this.bigA = G.modPow(a, N);
        }

        /** Traite salt + B du serveur ; renvoie M1. */
        public byte[] process(byte[] salt, BigInteger bigB) throws HandshakeException {
            if (bigB.mod(N).signum() == 0) throw new HandshakeException(HandshakeException.PROTOCOL, "B invalide");
            BigInteger uu = u(bigA, bigB);
            if (uu.signum() == 0) throw new HandshakeException(HandshakeException.PROTOCOL, "u invalide");
            BigInteger xx = x(salt, pin);
            BigInteger base = bigB.subtract(K.multiply(G.modPow(xx, N))).mod(N);
            BigInteger s = base.modPow(a.add(uu.multiply(xx)), N);
            this.salt = salt;
            this.bigB = bigB;
            this.key = deriveKey(s);
            this.m1 = proofClient(salt, bigA, bigB, key);
            return m1;
        }

        public boolean verifyServer(byte[] m2) {
            return MessageDigest.isEqual(m2, proofServer(bigA, m1, key));
        }

        public byte[] sessionKey() { return key; }
    }

    /** Côté listener (celui qui affiche le PIN). */
    public static final class Server {
        public final byte[] salt;
        public final BigInteger bigB;
        private final BigInteger v;
        private final BigInteger b;
        private byte[] key;

        public Server(String pin, SecureRandom rnd) {
            this(pin, randomSalt(rnd), randomPrivate(rnd));
        }

        Server(String pin, byte[] salt, BigInteger privateB) {
            this.salt = salt;
            this.b = privateB;
            this.v = G.modPow(x(salt, pin), N);
            this.bigB = K.multiply(v).add(G.modPow(b, N)).mod(N);
        }

        static byte[] randomSalt(SecureRandom rnd) {
            byte[] s = new byte[SALT_LEN];
            rnd.nextBytes(s);
            return s;
        }

        /** Vérifie M1 ; renvoie M2 ou lève HandshakeException(BAD_PROOF). */
        public byte[] verify(BigInteger bigA, byte[] m1) throws HandshakeException {
            if (bigA.mod(N).signum() == 0) throw new HandshakeException(HandshakeException.PROTOCOL, "A invalide");
            BigInteger uu = u(bigA, bigB);
            if (uu.signum() == 0) throw new HandshakeException(HandshakeException.PROTOCOL, "u invalide");
            BigInteger s = bigA.multiply(v.modPow(uu, N)).mod(N).modPow(b, N);
            byte[] k = deriveKey(s);
            if (!MessageDigest.isEqual(m1, proofClient(salt, bigA, bigB, k))) {
                throw new HandshakeException(HandshakeException.BAD_PROOF, "code PIN incorrect");
            }
            this.key = k;
            return proofServer(bigA, m1, k);
        }

        public byte[] sessionKey() { return key; }
    }
}
