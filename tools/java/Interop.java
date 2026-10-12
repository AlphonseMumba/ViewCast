package com.example.viewcast.security;

import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

public class Interop {
    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }
    static byte[] fill(int n, int v) { byte[] r = new byte[n]; Arrays.fill(r, (byte) v); return r; }

    public static void main(String[] a) throws Exception {
        if (a[0].equals("vectors")) {
            byte[] salt = new byte[16]; for (int i = 0; i < 16; i++) salt[i] = (byte) i;
            BigInteger pa = new BigInteger(1, fill(32, 0x11)), pb = new BigInteger(1, fill(32, 0x22));
            Srp.Client c = new Srp.Client("123456", pa);
            Srp.Server s = new Srp.Server("123456", salt, pb);
            byte[] m1 = c.process(salt, s.bigB);
            byte[] m2 = s.verify(c.bigA, m1);
            System.out.println("A=" + hex(Srp.pad(c.bigA)));
            System.out.println("B=" + hex(Srp.pad(s.bigB)));
            System.out.println("M1=" + hex(m1));
            System.out.println("M2=" + hex(m2));
            System.out.println("K=" + hex(c.sessionKey()));
            System.out.println("KOK=" + Arrays.equals(c.sessionKey(), s.sessionKey()) + " M2OK=" + c.verifyServer(m2));
            byte[] k = c.sessionKey(), salt2 = "VCS1".getBytes(StandardCharsets.US_ASCII);
            byte[] c2s = Kdf.hkdf32(k, salt2, "client-to-server".getBytes(StandardCharsets.US_ASCII));
            System.out.println("C2S=" + hex(c2s));
            byte[] plain = "ViewCast vector test".getBytes(StandardCharsets.US_ASCII);
            int clen = plain.length + 16; byte[] header = new byte[] {0, 0, (byte) (clen >> 8), (byte) clen};
            byte[] nonce = new byte[12];
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(c2s, "AES"), new GCMParameterSpec(128, nonce));
            ci.updateAAD(header);
            System.out.println("REC=" + hex(ci.doFinal(plain)));
            return;
        }
        String pin = a[a.length - 1];
        if (a[0].equals("serve")) {
            ServerSocket ss = new ServerSocket(Integer.parseInt(a[1]));
            System.out.println("READY"); System.out.flush();
            int conns = Integer.parseInt(a[2]);
            for (int i = 0; i < conns; i++) {
                Socket s = ss.accept();
                try {
                    SecureChannel ch = SecureChannel.serverHandshake(s, pin);
                    InputStream in = ch.getInputStream(); OutputStream out = ch.getOutputStream();
                    byte[] buf = new byte[5000]; int n; long total = 0;
                    while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); out.flush(); total += n; }
                    System.out.println("SERVER_ECHOED " + total); System.out.flush();
                } catch (HandshakeException e) { System.out.println("SERVER_AUTH_FAIL " + e.reason); System.out.flush(); }
            }
        } else {
            Socket s = new Socket(a[1], Integer.parseInt(a[2]));
            try {
                SecureChannel ch = SecureChannel.clientHandshake(s, pin);
                final byte[] data = new byte[1500000]; new Random(7).nextBytes(data);
                final OutputStream out = ch.getOutputStream();
                Thread w = new Thread(() -> { try { for (int o = 0; o < data.length; o += 1400) out.write(data, o, Math.min(1400, data.length - o)); out.flush(); s.shutdownOutput(); } catch (IOException e) { e.printStackTrace(); } });
                w.start();
                InputStream in = ch.getInputStream(); byte[] got = new byte[data.length]; int off = 0, n;
                while (off < got.length && (n = in.read(got, off, got.length - off)) > 0) off += n;
                System.out.println(Arrays.equals(got, data) ? "CLIENT_ECHO_OK " + off : "CLIENT_ECHO_MISMATCH");
            } catch (HandshakeException e) { System.out.println("CLIENT_AUTH_FAIL " + e.reason); }
        }
    }
}
