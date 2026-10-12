#!/usr/bin/env python3
"""Faux téléphone ViewCast : sert une mire + un son de test en RTSP/RTP-sur-TCP, SANS Android.

Sert à tester le viewer Windows (ou ffplay/VLC) indépendamment du téléphone.
Le protocole reproduit celui de android/.../rtsp/RtspServer.kt et RtpPacketizer.kt :
H.264 (FU-A), AAC (RFC 3640 AAC-hbr), RTCP Sender Reports, pistes streamid=0 (vidéo) / streamid=1 (son).

Prérequis : ffmpeg dans le PATH.
Usage     : python fake_phone_server.py [--port 8554] [--size 720x1280] [--fps 30] [--no-audio] [--pin 482913]
            (--pin : flux chiffré + protégé par code PIN, comme l'application ; sans --pin : mode ouvert)
Puis      : python ../desktop/python/viewcast_viewer.py 127.0.0.1:8554
"""
import argparse
import base64
import os
import queue
import random
import re
import socket
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "desktop", "python"))
MAX_PAYLOAD = 1400
VIDEO_PT, AUDIO_PT = 96, 97


def mono_us():
    return int(time.monotonic() * 1_000_000)


def split_nal_units(data):
    codes, starts, i = [], [], 0
    while i + 2 < len(data):
        if data[i] == 0 and data[i + 1] == 0 and data[i + 2] == 1:
            codes.append(i)
            starts.append(i + 3)
            i += 3
        else:
            i += 1
    if not starts:
        return [bytes(data)] if data else []
    out = []
    for k, s in enumerate(starts):
        e = codes[k + 1] if k + 1 < len(starts) else len(data)
        while e > s and data[e - 1] == 0:
            e -= 1
        if e > s:
            out.append(bytes(data[s:e]))
    return out


class RtpStream:
    def __init__(self, ssrc, seq, channel, clock, pt):
        self.ssrc, self.seq, self.channel = ssrc, seq & 0xFFFF, channel
        self.clock, self.pt = clock, pt
        self.ts_offset = random.getrandbits(32)
        self.packets = self.octets = 0
        self.sr_sent = False

    def timestamp(self, pts_us):
        return (pts_us * self.clock // 1_000_000 + self.ts_offset) & 0xFFFFFFFF

    def _build(self, prefix, src, marker, ts):
        payload = prefix + src
        rtp_len = 12 + len(payload)
        pkt = bytearray(b"$" + bytes([self.channel]) + rtp_len.to_bytes(2, "big"))
        pkt += bytes([0x80, (0x80 if marker else 0) | self.pt]) + self.seq.to_bytes(2, "big")
        pkt += ts.to_bytes(4, "big") + (self.ssrc & 0xFFFFFFFF).to_bytes(4, "big") + payload
        self.seq = (self.seq + 1) & 0xFFFF
        self.packets += 1
        self.octets += len(payload)
        return bytes(pkt)

    def sender_report(self, ntp_ms, pts_us):
        sec = ntp_ms // 1000 + 2208988800
        frac = ((ntp_ms % 1000) << 32) // 1000
        body = (b"\x80\xc8\x00\x06" + (self.ssrc & 0xFFFFFFFF).to_bytes(4, "big") +
                sec.to_bytes(4, "big") + frac.to_bytes(4, "big") +
                self.timestamp(pts_us).to_bytes(4, "big") +
                self.packets.to_bytes(4, "big") + self.octets.to_bytes(4, "big"))
        self.sr_sent = True
        return b"$" + bytes([self.channel + 1]) + len(body).to_bytes(2, "big") + body


class H264Stream(RtpStream):
    def __init__(self, ssrc, seq, channel):
        super().__init__(ssrc, seq, channel, 90000, VIDEO_PT)

    def packetize(self, nals, ts, out, marker=True):
        for idx, nal in enumerate(nals):
            last_nal = idx == len(nals) - 1
            if len(nal) <= MAX_PAYLOAD:
                out.append(self._build(b"", nal, marker and last_nal, ts))
                continue
            header = nal[0]
            indicator = (header & 0xE0) | 28
            off, first = 1, True
            while off < len(nal):
                ln = min(MAX_PAYLOAD - 2, len(nal) - off)
                last = off + ln >= len(nal)
                fu = (header & 0x1F) | (0x80 if first else 0) | (0x40 if last else 0)
                out.append(self._build(bytes((indicator, fu)), nal[off:off + ln], marker and last_nal and last, ts))
                off += ln
                first = False


class AacStream(RtpStream):
    def __init__(self, ssrc, seq, channel, rate):
        super().__init__(ssrc, seq, channel, rate, AUDIO_PT)

    def packetize(self, frame, ts, out):
        au = len(frame) << 3
        out.append(self._build(bytes((0, 16, au >> 8, au & 0xFF)), frame, True, ts))


class Client:
    def __init__(self, sock):
        self.sock = sock
        self.q = queue.Queue(maxsize=800)
        self.playing = False
        self.waiting_key = True
        self.session = ""
        self.video = self.audio = None
        self.closed = False
        self.lock = threading.Lock()

    def write(self, data):
        with self.lock:
            self.sock.sendall(data)

    def close(self):
        self.closed = True
        try:
            self.sock.close()
        except OSError:
            pass


class Server:
    def __init__(self, port, with_audio, pin=None):
        self.port = port
        self.pin = pin
        self.pin_failures = 0
        self.secure = None
        if pin:
            import vc_secure
            self.secure = vc_secure
        self.sps = self.pps = None
        self.asc = None
        self.with_audio = with_audio
        self.rate, self.channels = 48000, 2
        self.clients = []

    def start(self):
        s = socket.socket()
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("0.0.0.0", self.port))
        s.listen(5)
        threading.Thread(target=self._accept, args=(s,), daemon=True).start()
        threading.Thread(target=self._sr_loop, daemon=True).start()

    def _accept(self, s):
        while True:
            c, _ = s.accept()
            c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            cl = Client(c)
            self.clients.append(cl)
            threading.Thread(target=self._handle, args=(cl,), daemon=True).start()

    def _sr_loop(self):
        while True:
            time.sleep(2)
            now_us, ntp = mono_us(), int(time.time() * 1000)
            for c in list(self.clients):
                if c.playing:
                    for st in (c.video, c.audio):
                        if st and st.sr_sent:
                            try:
                                c.q.put_nowait(st.sender_report(ntp, now_us))
                            except queue.Full:
                                pass

    def _reply(self, c, cseq, code, headers=(), body=None):
        text = {200: "OK", 404: "Not Found", 405: "Method Not Allowed", 454: "Session Not Found",
                461: "Unsupported Transport", 503: "Service Unavailable"}.get(code, "Error")
        head = "RTSP/1.0 %d %s\r\nCSeq: %s\r\nServer: ViewCast\r\n" % (code, text, cseq)
        head += "".join(h + "\r\n" for h in headers)
        b = body.encode() if body else None
        if b is not None:
            head += "Content-Length: %d\r\n" % len(b)
        c.write(head.encode() + b"\r\n" + (b or b""))

    def _writer(self, c):
        try:
            while not c.closed:
                try:
                    pkt = c.q.get(timeout=1)
                except queue.Empty:
                    continue
                c.write(pkt)
        except OSError:
            pass
        finally:
            c.close()

    def _wait(self, cond, timeout):
        t0 = time.time()
        while not cond() and time.time() - t0 < timeout:
            time.sleep(0.05)
        return cond()

    def _bad_pin(self):
        """Comme l'application : après 5 échecs d'affilée, un NOUVEAU code est généré."""
        self.pin_failures += 1
        if self.pin_failures >= 5:
            self.pin_failures = 0
            self.pin = "%06d" % random.randrange(1000000)
            print("NEW_PIN", self.pin, flush=True)

    def _handle(self, c):
        if self.pin:
            try:
                c.sock = self.secure.server_handshake(c.sock, self.pin)
                self.pin_failures = 0
            except self.secure.HandshakeError as e:
                if e.reason == self.secure.BAD_PROOF:
                    self._bad_pin()
                c.close()
                if c in self.clients:
                    self.clients.remove(c)
                return
        f = c.sock.makefile("rb")
        try:
            while not c.closed:
                b = f.read(1)
                if not b:
                    break
                if b == b"$":
                    hdr = f.read(3)
                    f.read(int.from_bytes(hdr[1:3], "big"))
                    continue
                if b in (b"\r", b"\n"):
                    continue
                parts = (b + f.readline()).decode().strip().split(" ")
                headers = {}
                while True:
                    h = f.readline().decode().strip()
                    if not h:
                        break
                    k, _, v = h.partition(":")
                    headers[k.strip().lower()] = v.strip()
                method, cseq, url = parts[0].upper(), headers.get("cseq", "0"), parts[1] if len(parts) > 1 else ""
                sess = ["Session: %s;timeout=60" % c.session] if c.session else []
                host = c.sock.getsockname()[0]
                if method == "OPTIONS":
                    self._reply(c, cseq, 200, ["Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER, TEARDOWN"])
                elif method == "DESCRIBE":
                    if not self._wait(lambda: self.sps and self.pps, 5):
                        self._reply(c, cseq, 503)
                        continue
                    asc = self.asc if (self.with_audio and self._wait(lambda: self.asc, 3)) else None
                    s = self.sps
                    sdp = ("v=0\r\no=- 0 0 IN IP4 %s\r\ns=ViewCast\r\nc=IN IP4 0.0.0.0\r\nt=0 0\r\n"
                           "m=video 0 RTP/AVP %d\r\na=rtpmap:%d H264/90000\r\n"
                           "a=fmtp:%d packetization-mode=1;profile-level-id=%02X%02X%02X;sprop-parameter-sets=%s,%s\r\n"
                           "a=control:streamid=0\r\n") % (
                        host, VIDEO_PT, VIDEO_PT, VIDEO_PT, s[1], s[2], s[3],
                        base64.b64encode(s).decode(), base64.b64encode(self.pps).decode())
                    if asc:
                        sdp += ("m=audio 0 RTP/AVP %d\r\na=rtpmap:%d MPEG4-GENERIC/%d/%d\r\n"
                                "a=fmtp:%d streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;"
                                "indexlength=3;indexdeltalength=3;config=%s\r\na=control:streamid=1\r\n") % (
                            AUDIO_PT, AUDIO_PT, self.rate, self.channels, AUDIO_PT, asc.hex())
                    self._reply(c, cseq, 200, ["Content-Base: rtsp://%s:%d/stream/" % (host, self.port),
                                               "Content-Type: application/sdp"], sdp)
                elif method == "SETUP":
                    tr = headers.get("transport", "")
                    if "rtp/avp/tcp" not in tr.lower():
                        self._reply(c, cseq, 461)
                        continue
                    is_audio = url.endswith("streamid=1")
                    if is_audio and not self.asc:
                        self._reply(c, cseq, 404)
                        continue
                    m = re.search(r"interleaved=(\d+)-(\d+)", tr)
                    a, b2 = (int(m.group(1)), int(m.group(2))) if m else ((2, 3) if is_audio else (0, 1))
                    ssrc = random.getrandbits(32)
                    if is_audio:
                        c.audio = AacStream(ssrc, random.getrandbits(16), a, self.rate)
                    else:
                        c.video = H264Stream(ssrc, random.getrandbits(16), a)
                    c.session = c.session or "%08x" % random.getrandbits(31)
                    self._reply(c, cseq, 200, ["Transport: RTP/AVP/TCP;unicast;interleaved=%d-%d;ssrc=%08X" % (a, b2, ssrc),
                                               "Session: %s;timeout=60" % c.session])
                elif method == "PLAY":
                    if not c.session or not (c.video or c.audio):
                        self._reply(c, cseq, 454)
                        continue
                    c.waiting_key = True
                    self._reply(c, cseq, 200, ["Range: npt=0.000-"] + sess)
                    threading.Thread(target=self._writer, args=(c,), daemon=True).start()
                    c.playing = True
                elif method == "PAUSE":
                    c.playing = False
                    self._reply(c, cseq, 200, sess)
                elif method in ("GET_PARAMETER", "SET_PARAMETER"):
                    self._reply(c, cseq, 200, sess)
                elif method == "TEARDOWN":
                    self._reply(c, cseq, 200, sess)
                    break
                else:
                    self._reply(c, cseq, 405)
        except (OSError, ValueError):
            pass
        finally:
            c.close()
            if c in self.clients:
                self.clients.remove(c)

    @staticmethod
    def _ntp_for(pts_us):
        return int(time.time() * 1000) - (mono_us() - pts_us) // 1000

    def send_frame(self, nals, pts_us, key):
        for c in list(self.clients):
            if not c.playing or not c.video:
                continue
            if c.waiting_key:
                if not key:
                    continue
                c.waiting_key = False
            v, ts, pk = c.video, c.video.timestamp(pts_us), []
            if not v.sr_sent:
                pk.append(v.sender_report(self._ntp_for(pts_us), pts_us))
            if key and self.sps and self.pps:
                v.packetize([self.sps, self.pps], ts, pk, marker=False)
            v.packetize(nals, ts, pk)
            for p in pk:
                try:
                    c.q.put_nowait(p)
                except queue.Full:
                    with c.q.mutex:
                        c.q.queue.clear()
                    c.waiting_key = True
                    break

    def send_audio(self, frame, pts_us):
        for c in list(self.clients):
            if not c.playing or not c.audio or (c.video and c.waiting_key):
                continue
            a, pk = c.audio, []
            if not a.sr_sent:
                pk.append(a.sender_report(self._ntp_for(pts_us), pts_us))
            a.packetize(frame, a.timestamp(pts_us), pk)
            for p in pk:
                try:
                    c.q.put_nowait(p)
                except queue.Full:
                    break


def encode_clip(size, fps, seconds=10):
    """Pré-encode une mire H.264 (baseline) et un sinus AAC ; renvoie (sps, pps, images, asc, trames_aac).

    Les médias sont ensuite rejoués au rythme EXACT de l'horloge (comme un vrai téléphone) :
    ffmpeg -re enverrait des rafales et fausserait les instants.
    """
    v = subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=size=%s:rate=%d" % (size, fps),
         "-t", str(seconds), "-c:v", "libx264", "-profile:v", "baseline", "-preset", "ultrafast", "-tune", "zerolatency",
         "-g", str(fps), "-bf", "0", "-b:v", "4M", "-x264-params", "slices=1:repeat-headers=1:aud=0",
         "-f", "h264", "pipe:1"], capture_output=True, check=True).stdout
    sps = pps = None
    frames, pending = [], []          # frames : (liste de NAL, est_image_cle)
    for nal in split_nal_units(v):
        t = nal[0] & 0x1F
        if t == 7:
            sps = nal
        elif t == 8:
            pps = nal
        elif t in (1, 5):
            pending.append(nal)
            frames.append((pending, t == 5))
            pending = []
        elif t != 9:
            pending.append(nal)
    a = subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
         "-t", str(seconds), "-ac", "2", "-c:a", "aac", "-b:a", "128k", "-f", "adts", "pipe:1"],
        capture_output=True, check=True).stdout
    asc, aac, buf = None, [], a
    while len(buf) >= 7:
        if buf[0] != 0xFF or (buf[1] & 0xF0) != 0xF0:
            buf = buf[1:]
            continue
        length = ((buf[3] & 3) << 11) | (buf[4] << 3) | (buf[5] >> 5)
        if len(buf) < length:
            break
        hdr = 9 if (buf[1] & 1) == 0 else 7
        if asc is None:
            obj, idx, ch = ((buf[2] >> 6) & 3) + 1, (buf[2] >> 2) & 0xF, ((buf[2] & 1) << 2) | (buf[3] >> 6)
            asc = ((obj << 11) | (idx << 7) | (ch << 3)).to_bytes(2, "big")
        aac.append(buf[hdr:length])
        buf = buf[length:]
    return sps, pps, frames, asc, aac


def play_loop(period_us, count, t0, send):
    """Appelle send(k, instant_us) à l'heure exacte, indéfiniment (instant = horloge monotone)."""
    k = 0
    while True:
        due = t0 + k * period_us
        delay = (due - mono_us()) / 1_000_000
        if delay > 0:
            time.sleep(delay)
        send(k % count, due)
        k += 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8554)
    ap.add_argument("--size", default="720x1280")
    ap.add_argument("--fps", type=int, default=30)
    ap.add_argument("--no-audio", action="store_true")
    ap.add_argument("--pin", default=None, help="code PIN à 6 chiffres : active le chiffrement")
    a = ap.parse_args()
    if a.pin is not None and not (len(a.pin) == 6 and a.pin.isdigit()):
        sys.exit("--pin doit contenir exactement 6 chiffres")
    srv = Server(a.port, not a.no_audio, a.pin)
    srv.start()
    print("Preparation des medias de test ...")
    srv.sps, srv.pps, frames, srv.asc, aac = encode_clip(a.size, a.fps)
    t0 = mono_us() + 200_000
    if not a.no_audio:
        threading.Thread(target=play_loop, daemon=True,
                         args=(1024 * 1_000_000 // srv.rate, len(aac), t0,
                               lambda i, pts: srv.send_audio(aac[i], pts))).start()
    print("Faux telephone ViewCast sur 127.0.0.1:%d - %s (Ctrl+C pour arreter)" %
          (a.port, "chiffre, PIN " + a.pin if a.pin else "mode ouvert (RTSP en clair)"), flush=True)
    try:
        play_loop(1_000_000 // a.fps, len(frames), t0,
                  lambda i, pts: srv.send_frame(frames[i][0], pts, frames[i][1]))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    sys.exit(main())
