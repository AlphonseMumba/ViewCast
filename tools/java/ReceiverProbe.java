package com.example.viewcast.receive;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sonde de test : utilise le vrai RtspPlayer (code Android) pour recevoir un flux pendant N secondes,
 * puis écrit l'image reçue (video.h264, Annex-B) et le son reçu (audio.aac, ADTS) pour les vérifier avec ffmpeg.
 *
 * Usage : ReceiverProbe hôte port code|- secondes dossier
 */
public class ReceiverProbe {
    static byte[] adts(int len, byte[] asc) {
        int obj = (asc[0] >> 3) & 0x1F, idx = ((asc[0] & 7) << 1) | ((asc[1] >> 7) & 1), ch = (asc[1] >> 3) & 0xF;
        int n = len + 7;
        return new byte[] {(byte) 0xFF, (byte) 0xF1, (byte) (((obj - 1) << 6) | (idx << 2) | (ch >> 2)),
                (byte) (((ch & 3) << 6) | (n >> 11)), (byte) ((n >> 3) & 0xFF), (byte) (((n & 7) << 5) | 0x1F), (byte) 0xFC};
    }

    public static void main(String[] a) throws Exception {
        final String pin = a[2].equals("-") ? null : a[2];
        final FileOutputStream video = new FileOutputStream(a[4] + "/video.h264");
        final FileOutputStream audio = new FileOutputStream(a[4] + "/audio.aac");
        final AtomicInteger frames = new AtomicInteger(), keys = new AtomicInteger(), audios = new AtomicInteger();
        final byte[][] asc = new byte[1][];
        final int[] error = new int[1];
        RtspPlayer p = new RtspPlayer(a[0], Integer.parseInt(a[1]), pin, new RtspPlayer.Listener() {
            public void onConfig(byte[] sps, byte[] pps, byte[] cfg, int rate, int ch) {
                asc[0] = cfg;
                System.out.println("CONFIG sps=" + sps.length + " pps=" + pps.length + " aac=" + (cfg == null ? "non" : rate + "Hz/" + ch + "ch"));
            }
            public synchronized void onVideoFrame(byte[] annexB, long pts, boolean key) {
                try { video.write(annexB); } catch (IOException e) { throw new RuntimeException(e); }
                frames.incrementAndGet(); if (key) keys.incrementAndGet();
            }
            public synchronized void onAudioFrame(byte[] f, long pts) {
                try { audio.write(adts(f.length, asc[0])); audio.write(f); } catch (IOException e) { throw new RuntimeException(e); }
                audios.incrementAndGet();
            }
            public void onError(int kind, String msg) { error[0] = kind; System.out.println("ERR " + kind + " " + msg); }
            public void onStopped() { System.out.println("STOPPED"); }
        });
        p.start();
        Thread.sleep(Long.parseLong(a[3]) * 1000);
        p.stop();
        Thread.sleep(300);
        synchronized (p) { }
        video.close(); audio.close();
        System.out.println("STATS frames=" + frames + " keys=" + keys + " audio=" + audios + " bytes=" + p.bytesReceived() + " err=" + error[0]);
    }
}
