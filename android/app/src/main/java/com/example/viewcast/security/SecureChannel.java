package com.example.viewcast.security;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Canal chiffré et authentifié par code PIN (protocole « VCS1 ») :
 *
 * <pre>
 *  1. C->S  HELLO   "VCS1" | A (256 o)
 *  2. S->C  HELLO   sel (16 o) | B (256 o)
 *  3. C->S  PROOF   M1 (32 o)
 *  4. S->C  PROOF   M2 (32 o)   ou   FAIL  code (1 o)
 *  5. puis des enregistrements AES-256-GCM :  longueur (4 o) | texte chiffré + étiquette (16 o)
 * </pre>
 *
 * Les clés (une par sens) dérivent de la clé de session SRP par HKDF-SHA256. Le nonce GCM est un
 * compteur de 64 bits par sens : un enregistrement rejoué, supprimé ou modifié est refusé.
 * Le sens TCP (qui se connecte) est indépendant du sens des médias : le canal est symétrique.
 */
public final class SecureChannel {
    static final int MSG_CLIENT_HELLO = 1;
    static final int MSG_SERVER_HELLO = 2;
    static final int MSG_CLIENT_PROOF = 3;
    static final int MSG_SERVER_PROOF = 4;
    static final int MSG_FAIL = 5;
    static final byte[] MAGIC = "VCS1".getBytes(StandardCharsets.US_ASCII);
    static final int MAX_PLAIN = 16384;
    static final int TAG_LEN = 16;
    static final int HANDSHAKE_TIMEOUT_MS = 8000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Socket socket;
    private final DataInputStream raw;
    private final OutputStream rawOut;
    private final byte[] sendKey;
    private final byte[] recvKey;
    private long sendCounter = 0;
    private long recvCounter = 0;
    private final InputStream in = new SecureIn();
    private final OutputStream out = new SecureOut();

    private SecureChannel(Socket socket, DataInputStream raw, OutputStream rawOut, byte[] sessionKey, boolean isServer)
            throws IOException {
        this.socket = socket;
        this.raw = raw;
        this.rawOut = rawOut;
        byte[] salt = "VCS1".getBytes(StandardCharsets.US_ASCII);
        byte[] c2s = Kdf.hkdf32(sessionKey, salt, "client-to-server".getBytes(StandardCharsets.US_ASCII));
        byte[] s2c = Kdf.hkdf32(sessionKey, salt, "server-to-client".getBytes(StandardCharsets.US_ASCII));
        this.sendKey = isServer ? s2c : c2s;
        this.recvKey = isServer ? c2s : s2c;
    }

    public InputStream getInputStream() { return in; }

    public OutputStream getOutputStream() { return out; }

    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    // ───────────────────────── poignées de main ─────────────────────────

    /** Listener : attend un client, vérifie son PIN. Le socket garde son délai de lecture d origine ensuite. */
    public static SecureChannel serverHandshake(Socket socket, String pin) throws IOException {
        int previousTimeout = socket.getSoTimeout();
        socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
        DataInputStream in = new DataInputStream(socket.getInputStream());
        OutputStream out = socket.getOutputStream();
        try {
            byte[] hello = readMessage(in, MSG_CLIENT_HELLO);
            if (hello.length != MAGIC.length + Srp.N_LEN || !Arrays.equals(Arrays.copyOf(hello, MAGIC.length), MAGIC)) {
                sendFail(out, HandshakeException.PROTOCOL);
                throw new HandshakeException(HandshakeException.PROTOCOL, "client non VCS1");
            }
            BigInteger bigA = new BigInteger(1, Arrays.copyOfRange(hello, MAGIC.length, hello.length));

            Srp.Server srp = new Srp.Server(pin, RANDOM);
            writeMessage(out, MSG_SERVER_HELLO, concat(srp.salt, Srp.pad(srp.bigB)));

            byte[] m1 = readMessage(in, MSG_CLIENT_PROOF);
            byte[] m2;
            try {
                m2 = srp.verify(bigA, m1);
            } catch (HandshakeException e) {
                sendFail(out, e.reason);
                throw e;
            }
            writeMessage(out, MSG_SERVER_PROOF, m2);
            socket.setSoTimeout(previousTimeout);
            return new SecureChannel(socket, in, out, srp.sessionKey(), true);
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    /** Initiateur : prouve la connaissance du PIN au listener. */
    public static SecureChannel clientHandshake(Socket socket, String pin) throws IOException {
        int previousTimeout = socket.getSoTimeout();
        socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
        DataInputStream in = new DataInputStream(socket.getInputStream());
        OutputStream out = socket.getOutputStream();
        try {
            Srp.Client srp = new Srp.Client(pin, RANDOM);
            writeMessage(out, MSG_CLIENT_HELLO, concat(MAGIC, Srp.pad(srp.bigA)));

            byte[] hello = readMessage(in, MSG_SERVER_HELLO);
            if (hello.length != Srp.SALT_LEN + Srp.N_LEN) {
                throw new HandshakeException(HandshakeException.PROTOCOL, "réponse invalide");
            }
            byte[] salt = Arrays.copyOf(hello, Srp.SALT_LEN);
            BigInteger bigB = new BigInteger(1, Arrays.copyOfRange(hello, Srp.SALT_LEN, hello.length));
            byte[] m1 = srp.process(salt, bigB);
            writeMessage(out, MSG_CLIENT_PROOF, m1);

            byte[] m2 = readMessage(in, MSG_SERVER_PROOF);
            if (!srp.verifyServer(m2)) {
                throw new HandshakeException(HandshakeException.BAD_PROOF, "serveur non authentique");
            }
            socket.setSoTimeout(previousTimeout);
            return new SecureChannel(socket, in, out, srp.sessionKey(), false);
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static void writeMessage(OutputStream out, int type, byte[] payload) throws IOException {
        byte[] m = new byte[3 + payload.length];
        m[0] = (byte) type;
        m[1] = (byte) (payload.length >> 8);
        m[2] = (byte) payload.length;
        System.arraycopy(payload, 0, m, 3, payload.length);
        out.write(m);
        out.flush();
    }

    private static void sendFail(OutputStream out, int reason) {
        try {
            writeMessage(out, MSG_FAIL, new byte[] {(byte) reason});
        } catch (IOException ignored) {
        }
    }

    /** Lit un message de poignée de main ; un FAIL du pair devient une HandshakeException. */
    private static byte[] readMessage(DataInputStream in, int expectedType) throws IOException {
        int type = in.readUnsignedByte();
        int len = in.readUnsignedShort();
        if (len > 512) throw new HandshakeException(HandshakeException.PROTOCOL, "message trop long");
        byte[] payload = new byte[len];
        in.readFully(payload);
        if (type == MSG_FAIL) {
            int reason = len > 0 ? payload[0] : HandshakeException.PROTOCOL;
            throw new HandshakeException(reason, "refusé par le pair (code " + reason + ")");
        }
        if (type != expectedType) throw new HandshakeException(HandshakeException.PROTOCOL, "message inattendu");
        return payload;
    }

    // ───────────────────────── enregistrements chiffrés ─────────────────────────

    private static byte[] nonce(long counter) {
        byte[] n = new byte[12];
        for (int i = 0; i < 8; i++) n[11 - i] = (byte) (counter >>> (8 * i));
        return n;
    }

    private void sendRecord(byte[] data, int off, int len) throws IOException {
        try {
            int clen = len + TAG_LEN;
            byte[] header = new byte[] {(byte) (clen >>> 24), (byte) (clen >>> 16), (byte) (clen >>> 8), (byte) clen};
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sendKey, "AES"), new GCMParameterSpec(128, nonce(sendCounter++)));
            c.updateAAD(header);
            byte[] ct = c.doFinal(data, off, len);
            byte[] rec = concat(header, ct);
            rawOut.write(rec);
            rawOut.flush();
        } catch (GeneralSecurityException e) {
            throw new IOException("chiffrement impossible", e);
        }
    }

    /** Lit et déchiffre un enregistrement ; null en fin de flux propre. */
    private byte[] receiveRecord() throws IOException {
        byte[] header = new byte[4];
        try {
            raw.readFully(header, 0, 1);
        } catch (EOFException e) {
            return null;
        }
        raw.readFully(header, 1, 3);
        int clen = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16) | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        if (clen < TAG_LEN || clen > MAX_PLAIN + TAG_LEN) throw new IOException("enregistrement invalide");
        byte[] ct = new byte[clen];
        raw.readFully(ct);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(recvKey, "AES"), new GCMParameterSpec(128, nonce(recvCounter++)));
            c.updateAAD(header);
            return c.doFinal(ct);
        } catch (GeneralSecurityException e) {
            throw new IOException("enregistrement corrompu ou falsifié", e);
        }
    }

    private final class SecureIn extends InputStream {
        private byte[] buf = new byte[0];
        private int pos = 0;

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (pos >= buf.length) {
                byte[] next = receiveRecord();
                if (next == null) return -1;
                buf = next;
                pos = 0;
            }
            int n = Math.min(len, buf.length - pos);
            System.arraycopy(buf, pos, b, off, n);
            pos += n;
            return n;
        }

        @Override
        public int available() { return buf.length - pos; }

        @Override
        public void close() { SecureChannel.this.close(); }
    }

    private final class SecureOut extends OutputStream {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int b) throws IOException {
            pending.write(b);
            if (pending.size() >= MAX_PLAIN) flush();
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            pending.write(b, off, len);
            if (pending.size() >= MAX_PLAIN) flush();
        }

        /** Chaque flush() envoie un enregistrement (un paquet RTP ≈ 1,4 Ko => 20 octets de surcoût). */
        @Override
        public synchronized void flush() throws IOException {
            byte[] data = pending.toByteArray();
            pending.reset();
            int off = 0;
            while (off < data.length) {
                int n = Math.min(MAX_PLAIN, data.length - off);
                sendRecord(data, off, n);
                off += n;
            }
        }

        @Override
        public void close() { SecureChannel.this.close(); }
    }
}
