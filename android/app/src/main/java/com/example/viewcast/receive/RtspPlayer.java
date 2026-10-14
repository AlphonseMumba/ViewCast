package com.example.viewcast.receive;

import com.example.viewcast.security.HandshakeException;
import com.example.viewcast.security.SecureChannel;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client RTSP de ViewCast pour RECEVOIR l'écran d'un autre appareil : se connecte (en clair, ou chiffré
 * et authentifié par code PIN), négocie OPTIONS / DESCRIBE / SETUP / PLAY en RTP sur TCP, puis
 * dépaquette l'image H.264 (RFC 6184 : NAL seule + FU-A) et le son AAC (RFC 3640 : AAC-hbr).
 *
 * Java pur (aucune API Android) : testé sur PC contre le serveur de test, voir tools/test_receiver_java.py.
 */
public final class RtspPlayer {
    public static final int ERR_UNREACHABLE = 1;   // appareil introuvable / connexion refusée
    public static final int ERR_AUTH = 2;          // code PIN incorrect
    public static final int ERR_LOCKED = 3;        // trop d'essais : un nouveau code a été généré
    public static final int ERR_PROTOCOL = 4;      // l'autre appareil ne parle pas ce protocole (mode PIN / ouvert ?)
    public static final int ERR_DROPPED = 5;       // connexion perdue en cours de route

    public interface Listener {
        /** SPS/PPS (sans start code) ; aacConfig = AudioSpecificConfig ou null s'il n'y a pas de son. */
        void onConfig(byte[] sps, byte[] pps, byte[] aacConfig, int audioRate, int audioChannels);

        /** Une image complète au format Annex-B (start codes 00 00 00 01). */
        void onVideoFrame(byte[] annexB, long ptsUs, boolean keyFrame);

        /** Une trame AAC brute (sans ADTS). */
        void onAudioFrame(byte[] aacFrame, long ptsUs);

        void onError(int kind, String message);

        /** Toujours appelé une fois, à la fin du thread. */
        void onStopped();
    }

    private final String host;
    private final int port;
    private final String pin;
    private final Listener listener;
    private volatile boolean stopped;
    private volatile Socket socket;
    private Thread thread;
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicInteger cseq = new AtomicInteger(1);

    // état du dépaquetage
    private final List<byte[]> auNals = new ArrayList<byte[]>();
    private long auTs = -1;
    private final ByteArrayOutputStream fu = new ByteArrayOutputStream();
    private long baseVideoTs = -1;
    private long baseAudioTs = -1;
    private int audioRate = 48000;

    /** @param pin code à 6 chiffres, ou null pour le mode ouvert (RTSP en clair). */
    public RtspPlayer(String host, int port, String pin, Listener listener) {
        this.host = host;
        this.port = port;
        this.pin = pin;
        this.listener = listener;
    }

    public void start() {
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runSession();
            }
        }, "ViewCast-rx");
        thread.start();
    }

    public void stop() {
        stopped = true;
        close();
    }

    public long bytesReceived() {
        return bytes.get();
    }

    private void close() {
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void fail(int kind, String message) {
        if (!stopped) listener.onError(kind, message);
    }

    // ───────────────────────── session ─────────────────────────

    private void runSession() {
        Socket s = new Socket();
        try {
            try {
                s.connect(new InetSocketAddress(host, port), 5000);
            } catch (IOException e) {
                fail(ERR_UNREACHABLE, String.valueOf(e.getMessage()));
                return;
            }
            socket = s;
            s.setTcpNoDelay(true);
            InputStream rawIn;
            OutputStream rawOut;
            if (pin != null) {
                SecureChannel ch;
                try {
                    ch = SecureChannel.clientHandshake(s, pin);
                } catch (HandshakeException e) {
                    int kind = e.reason == HandshakeException.BAD_PROOF ? ERR_AUTH
                            : e.reason == HandshakeException.LOCKED ? ERR_LOCKED : ERR_PROTOCOL;
                    fail(kind, e.getMessage());
                    return;
                } catch (IOException e) {
                    fail(ERR_PROTOCOL, "pas de réponse sécurisée : " + e.getMessage());
                    return;
                }
                rawIn = ch.getInputStream();
                rawOut = ch.getOutputStream();
            } else {
                rawIn = s.getInputStream();
                rawOut = s.getOutputStream();
            }
            s.setSoTimeout(15000); // le serveur envoie un rapport RTCP toutes les 2 s : 15 s de silence = connexion perdue
            play(new BufferedInputStream(rawIn, 32768), rawOut);
        } catch (IOException e) {
            fail(ERR_DROPPED, String.valueOf(e.getMessage()));
        } finally {
            close();
            listener.onStopped();
        }
    }

    private void play(InputStream in, final OutputStream out) throws IOException {
        String base = "rtsp://" + host + ":" + port + "/stream";
        Response r = request(in, out, "OPTIONS", base, "");
        if (r.status != 200) throw new IOException("OPTIONS refusé (" + r.status + ")");

        r = request(in, out, "DESCRIBE", base, "Accept: application/sdp\r\n");
        if (r.status != 200) throw new IOException("DESCRIBE refusé (" + r.status + ")");
        Sdp sdp = Sdp.parse(r.body);
        if (sdp.sps == null || sdp.pps == null) throw new IOException("SDP sans SPS/PPS");
        audioRate = sdp.audioRate;
        String contentBase = r.headers.get("content-base");
        if (contentBase == null) contentBase = base + "/";
        if (!contentBase.endsWith("/")) contentBase += "/";
        listener.onConfig(sdp.sps, sdp.pps, sdp.aacConfig, sdp.audioRate, sdp.audioChannels);

        r = request(in, out, "SETUP", contentBase + "streamid=0", "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n");
        if (r.status != 200) throw new IOException("SETUP vidéo refusé (" + r.status + ")");
        final String session = sessionId(r);
        if (sdp.aacConfig != null) {
            r = request(in, out, "SETUP", contentBase + "streamid=1",
                    "Transport: RTP/AVP/TCP;unicast;interleaved=2-3\r\nSession: " + session + "\r\n");
            if (r.status != 200) throw new IOException("SETUP audio refusé (" + r.status + ")");
        }
        r = request(in, out, "PLAY", base, "Session: " + session + "\r\nRange: npt=0.000-\r\n");
        if (r.status != 200) throw new IOException("PLAY refusé (" + r.status + ")");

        final String keepAliveUrl = base;
        Thread keepAlive = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (!stopped) {
                        Thread.sleep(30000);
                        String req = "GET_PARAMETER " + keepAliveUrl + " RTSP/1.0\r\nCSeq: " + cseq.getAndIncrement()
                                + "\r\nSession: " + session + "\r\n\r\n";
                        synchronized (out) {
                            out.write(req.getBytes(StandardCharsets.US_ASCII));
                            out.flush();
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }, "ViewCast-rx-keepalive");
        keepAlive.setDaemon(true);
        keepAlive.start();

        readLoop(in);
    }

    private static String sessionId(Response r) {
        String s = r.headers.get("session");
        if (s == null) return "0";
        int i = s.indexOf(';');
        return (i >= 0 ? s.substring(0, i) : s).trim();
    }

    // ───────────────────────── RTSP texte ─────────────────────────

    private static final class Response {
        int status;
        final Map<String, String> headers = new HashMap<String, String>();
        String body = "";
    }

    private Response request(InputStream in, OutputStream out, String method, String url, String extraHeaders) throws IOException {
        String req = method + " " + url + " RTSP/1.0\r\nCSeq: " + cseq.getAndIncrement() + "\r\n" + extraHeaders
                + "User-Agent: ViewCast\r\n\r\n";
        synchronized (out) {
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
        return readResponse(in, in.read());
    }

    /** Lit une réponse RTSP dont le premier octet est déjà lu. */
    private Response readResponse(InputStream in, int first) throws IOException {
        if (first < 0) throw new EOFException("connexion fermée");
        Response r = new Response();
        String status = readLine(in, first);
        String[] parts = status.split(" ");
        if (parts.length < 2 || !parts[0].startsWith("RTSP/")) throw new IOException("réponse RTSP invalide");
        try {
            r.status = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("code RTSP invalide");
        }
        while (true) {
            String line = readLine(in, in.read());
            if (line.length() == 0) break;
            int i = line.indexOf(':');
            if (i > 0) r.headers.put(line.substring(0, i).trim().toLowerCase(), line.substring(i + 1).trim());
        }
        String cl = r.headers.get("content-length");
        if (cl != null) {
            int n = Integer.parseInt(cl.trim());
            if (n < 0 || n > 65536) throw new IOException("corps trop long");
            byte[] body = new byte[n];
            readFully(in, body);
            r.body = new String(body, StandardCharsets.UTF_8);
        }
        return r;
    }

    private static String readLine(InputStream in, int first) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b = first;
        while (true) {
            if (b < 0) throw new EOFException("connexion fermée");
            if (b == '\n') return sb.toString();
            if (b != '\r') sb.append((char) b);
            if (sb.length() > 8192) throw new IOException("ligne trop longue");
            b = in.read();
        }
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new EOFException("connexion fermée");
            off += n;
        }
    }

    // ───────────────────────── RTP entrelacé ─────────────────────────

    private void readLoop(InputStream in) throws IOException {
        while (!stopped) {
            int b = in.read();
            if (b < 0) throw new EOFException("connexion fermée");
            if (b == '$') {
                int channel = in.read();
                int hi = in.read();
                int lo = in.read();
                if (lo < 0) throw new EOFException("connexion fermée");
                byte[] payload = new byte[(hi << 8) | lo];
                readFully(in, payload);
                bytes.addAndGet(payload.length + 4);
                if (channel == 0) onVideoRtp(payload);
                else if (channel == 2) onAudioRtp(payload);
                // canaux 1 et 3 : rapports RTCP, ignorés
            } else {
                readResponse(in, b); // réponse à un keep-alive : ignorée
            }
        }
    }

    /** Début du contenu RTP (après en-tête fixe, CSRC et extension) ou -1 si le paquet est invalide. */
    private static int rtpPayloadOffset(byte[] p) {
        if (p.length < 12 || (p[0] & 0xC0) != 0x80) return -1;
        int off = 12 + 4 * (p[0] & 0x0F);
        if ((p[0] & 0x10) != 0) {
            if (p.length < off + 4) return -1;
            off += 4 + 4 * (((p[off + 2] & 0xFF) << 8) | (p[off + 3] & 0xFF));
        }
        return off <= p.length ? off : -1;
    }

    private static int rtpPayloadEnd(byte[] p) {
        return (p[0] & 0x20) != 0 ? p.length - (p[p.length - 1] & 0xFF) : p.length;
    }

    private static long rtpTimestamp(byte[] p) {
        return (((long) (p[4] & 0xFF) << 24) | ((p[5] & 0xFF) << 16) | ((p[6] & 0xFF) << 8) | (p[7] & 0xFF)) & 0xFFFFFFFFL;
    }

    private void onVideoRtp(byte[] p) {
        int off = rtpPayloadOffset(p);
        int end = p.length >= 12 ? rtpPayloadEnd(p) : 0;
        if (off < 0 || end - off < 2) return;
        long ts = rtpTimestamp(p);
        boolean marker = (p[1] & 0x80) != 0;
        if (auTs >= 0 && ts != auTs && !auNals.isEmpty()) emitVideo(); // marqueur perdu : on sort l'image précédente
        auTs = ts;
        int type = p[off] & 0x1F;
        if (type >= 1 && type <= 23) {
            auNals.add(copy(p, off, end));
        } else if (type == 28) {                       // FU-A
            int indicator = p[off] & 0xFF;
            int header = p[off + 1] & 0xFF;
            if ((header & 0x80) != 0) {                // début de fragment : on reconstruit l'en-tête NAL
                fu.reset();
                fu.write((indicator & 0xE0) | (header & 0x1F));
            }
            fu.write(p, off + 2, end - off - 2);
            if ((header & 0x40) != 0 && fu.size() > 0) { // fin de fragment
                auNals.add(fu.toByteArray());
                fu.reset();
            }
        }
        if (marker) emitVideo();
    }

    private void emitVideo() {
        if (auNals.isEmpty()) return;
        if (baseVideoTs < 0) baseVideoTs = auTs;
        long ptsUs = ((auTs - baseVideoTs) & 0xFFFFFFFFL) * 1000000L / 90000L;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean key = false;
        for (byte[] nal : auNals) {
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(1);
            out.write(nal, 0, nal.length);
            if ((nal[0] & 0x1F) == 5) key = true;
        }
        auNals.clear();
        listener.onVideoFrame(out.toByteArray(), ptsUs, key);
    }

    private void onAudioRtp(byte[] p) {
        int off = rtpPayloadOffset(p);
        int end = p.length >= 12 ? rtpPayloadEnd(p) : 0;
        if (off < 0 || end - off < 4) return;
        long ts = rtpTimestamp(p);
        if (baseAudioTs < 0) baseAudioTs = ts;
        int headersBits = ((p[off] & 0xFF) << 8) | (p[off + 1] & 0xFF);
        int count = headersBits / 16;
        int dataOff = off + 2 + 2 * count;
        long ptsUs = ((ts - baseAudioTs) & 0xFFFFFFFFL) * 1000000L / audioRate;
        for (int i = 0; i < count; i++) {
            int h = ((p[off + 2 + 2 * i] & 0xFF) << 8) | (p[off + 3 + 2 * i] & 0xFF);
            int size = h >> 3;
            if (size <= 0 || dataOff + size > end) return;
            listener.onAudioFrame(copy(p, dataOff, dataOff + size), ptsUs);
            dataOff += size;
            ptsUs += 1024L * 1000000L / audioRate;
        }
    }

    private static byte[] copy(byte[] src, int from, int to) {
        byte[] r = new byte[to - from];
        System.arraycopy(src, from, r, 0, r.length);
        return r;
    }

    // ───────────────────────── SDP ─────────────────────────

    static final class Sdp {
        byte[] sps;
        byte[] pps;
        byte[] aacConfig;
        int audioRate = 48000;
        int audioChannels = 2;

        static Sdp parse(String text) {
            Sdp s = new Sdp();
            String media = "";
            for (String raw : text.split("\n")) {
                String line = raw.trim();
                if (line.startsWith("m=")) {
                    media = line.substring(2, line.indexOf(' '));
                } else if (media.equals("video") && line.startsWith("a=fmtp:")) {
                    String v = param(line, "sprop-parameter-sets");
                    if (v != null) {
                        String[] sets = v.split(",");
                        if (sets.length >= 2) {
                            s.sps = base64(sets[0]);
                            s.pps = base64(sets[1]);
                        }
                    }
                } else if (media.equals("audio") && line.startsWith("a=rtpmap:")) {
                    String[] parts = line.substring(line.indexOf(' ') + 1).split("/");
                    if (parts.length >= 2) s.audioRate = parseInt(parts[1], 48000);
                    if (parts.length >= 3) s.audioChannels = parseInt(parts[2], 2);
                } else if (media.equals("audio") && line.startsWith("a=fmtp:")) {
                    String cfg = param(line, "config");
                    if (cfg != null && cfg.length() >= 4) s.aacConfig = hex(cfg);
                }
            }
            return s;
        }

        private static String param(String line, String name) {
            int i = line.indexOf(name + "=");
            if (i < 0) return null;
            int start = i + name.length() + 1;
            int end = line.indexOf(';', start);
            return (end < 0 ? line.substring(start) : line.substring(start, end)).trim();
        }

        private static int parseInt(String v, int def) {
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }

        static byte[] hex(String h) {
            byte[] r = new byte[h.length() / 2];
            for (int i = 0; i < r.length; i++) r[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
            return r;
        }

        /** Base64 standard (java.util.Base64 n'existe qu'à partir d'Android 8). */
        static byte[] base64(String s) {
            String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int acc = 0;
            int bits = 0;
            for (int i = 0; i < s.length(); i++) {
                int v = alphabet.indexOf(s.charAt(i));
                if (v < 0) continue; // '=' et espaces
                acc = (acc << 6) | v;
                bits += 6;
                if (bits >= 8) {
                    bits -= 8;
                    out.write((acc >> bits) & 0xFF);
                }
            }
            return out.toByteArray();
        }
    }
}
