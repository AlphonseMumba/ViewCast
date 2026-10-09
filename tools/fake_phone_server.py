#!/usr/bin/env python3
"""Faux téléphone ViewCast : sert une mire de test en RTSP/RTP-sur-TCP, SANS Android.

Sert à tester le viewer Windows (ou ffplay/VLC) indépendamment du téléphone.
Le protocole (SDP, trames interleaved, FU-A, gestion SPS/PPS/IDR) reproduit celui de
android/.../rtsp/RtspServer.kt et RtpPacketizer.kt.

Prérequis : ffmpeg dans le PATH.
Usage     : python fake_phone_server.py [--port 8554] [--size 720x1280] [--fps 30]
Puis      : python ../desktop/python/viewcast_viewer.py 127.0.0.1:8554
"""
import argparse
import base64
import queue
import random
import re
import socket
import subprocess
import sys
import threading
import time

MAX_PAYLOAD = 1400
PT = 96


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


class Packetizer:
    def __init__(self, ssrc, seq, channel):
        self.ssrc, self.seq, self.channel = ssrc, seq & 0xFFFF, channel

    def _build(self, fu, src, marker, ts):
        rtp_len = 12 + (2 if fu else 0) + len(src)
        pkt = bytearray(b"$" + bytes([self.channel]) + rtp_len.to_bytes(2, "big"))
        pkt += bytes([0x80, (0x80 if marker else 0) | PT]) + self.seq.to_bytes(2, "big")
        pkt += (ts & 0xFFFFFFFF).to_bytes(4, "big") + (self.ssrc & 0xFFFFFFFF).to_bytes(4, "big")
        if fu:
            pkt += bytes(fu)
        pkt += src
        self.seq = (self.seq + 1) & 0xFFFF
        return bytes(pkt)

    def packetize(self, nals, ts, out, marker=True):
        for idx, nal in enumerate(nals):
            last_nal = idx == len(nals) - 1
            if len(nal) <= MAX_PAYLOAD:
                out.append(self._build(None, nal, marker and last_nal, ts))
                continue
            header = nal[0]
            indicator = (header & 0xE0) | 28
            off, first = 1, True
            while off < len(nal):
                ln = min(MAX_PAYLOAD - 2, len(nal) - off)
                last = off + ln >= len(nal)
                fu = (header & 0x1F) | (0x80 if first else 0) | (0x40 if last else 0)
                out.append(self._build((indicator, fu), nal[off:off + ln], marker and last_nal and last, ts))
                off += ln
                first = False


class Client:
    def __init__(self, sock):
        self.sock = sock
        self.q = queue.Queue(maxsize=600)
        self.playing = False
        self.waiting_key = True
        self.session = ""
        self.ssrc = random.getrandbits(32)
        self.pk = None
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
    def __init__(self, port):
        self.port = port
        self.sps = self.pps = None
        self.clients = []

    def start(self):
        s = socket.socket()
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("0.0.0.0", self.port))
        s.listen(5)
        threading.Thread(target=self._accept, args=(s,), daemon=True).start()

    def _accept(self, s):
        while True:
            c, _ = s.accept()
            c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            cl = Client(c)
            self.clients.append(cl)
            threading.Thread(target=self._handle, args=(cl,), daemon=True).start()

    # --- réponses
    def _reply(self, c, cseq, code, headers=(), body=None):
        text = {200: "OK", 405: "Method Not Allowed", 454: "Session Not Found",
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

    def _handle(self, c):
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
                line = b + f.readline()
                parts = line.decode().strip().split(" ")
                headers = {}
                while True:
                    h = f.readline().decode().strip()
                    if not h:
                        break
                    k, _, v = h.partition(":")
                    headers[k.strip().lower()] = v.strip()
                method, cseq = parts[0].upper(), headers.get("cseq", "0")
                sess = ["Session: %s;timeout=60" % c.session] if c.session else []
                host = c.sock.getsockname()[0]
                if method == "OPTIONS":
                    self._reply(c, cseq, 200, ["Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER, TEARDOWN"])
                elif method == "DESCRIBE":
                    t0 = time.time()
                    while (self.sps is None or self.pps is None) and time.time() - t0 < 5:
                        time.sleep(0.05)
                    if self.sps is None:
                        self._reply(c, cseq, 503)
                        continue
                    s = self.sps
                    sdp = ("v=0\r\no=- 0 0 IN IP4 %s\r\ns=ViewCast\r\nc=IN IP4 0.0.0.0\r\nt=0 0\r\n"
                           "m=video 0 RTP/AVP %d\r\na=rtpmap:%d H264/90000\r\n"
                           "a=fmtp:%d packetization-mode=1;profile-level-id=%02X%02X%02X;sprop-parameter-sets=%s,%s\r\n"
                           "a=control:streamid=0\r\n") % (
                        host, PT, PT, PT, s[1], s[2], s[3],
                        base64.b64encode(s).decode(), base64.b64encode(self.pps).decode())
                    self._reply(c, cseq, 200, ["Content-Base: rtsp://%s:%d/stream/" % (host, self.port),
                                               "Content-Type: application/sdp"], sdp)
                elif method == "SETUP":
                    tr = headers.get("transport", "")
                    if "rtp/avp/tcp" not in tr.lower():
                        self._reply(c, cseq, 461)
                        continue
                    m = re.search(r"interleaved=(\d+)-(\d+)", tr)
                    a, b2 = (int(m.group(1)), int(m.group(2))) if m else (0, 1)
                    c.pk = Packetizer(c.ssrc, random.getrandbits(16), a)
                    c.session = c.session or "%08x" % random.getrandbits(31)
                    self._reply(c, cseq, 200, ["Transport: RTP/AVP/TCP;unicast;interleaved=%d-%d;ssrc=%08X" % (a, b2, c.ssrc),
                                               "Session: %s;timeout=60" % c.session])
                elif method == "PLAY":
                    if not c.session:
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

    def send_frame(self, nals, pts_us, key):
        ts = (pts_us * 90 // 1000) & 0xFFFFFFFF
        for c in list(self.clients):
            if not c.playing:
                continue
            if c.waiting_key:
                if not key:
                    continue
                c.waiting_key = False
            pk = []
            if key and self.sps and self.pps:
                c.pk.packetize([self.sps, self.pps], ts, pk, marker=False)
            c.pk.packetize(nals, ts, pk)
            for p in pk:
                try:
                    c.q.put_nowait(p)
                except queue.Full:
                    with c.q.mutex:
                        c.q.queue.clear()
                    c.waiting_key = True
                    break


def feed(server, size, fps):
    """Encode une mire avec ffmpeg et la pousse comme le ferait MediaCodec (frames = NAL sans start code)."""
    cmd = ["ffmpeg", "-loglevel", "error", "-re", "-f", "lavfi", "-i", "testsrc2=size=%s:rate=%d" % (size, fps),
           "-c:v", "libx264", "-profile:v", "baseline", "-preset", "ultrafast", "-tune", "zerolatency",
           "-g", str(fps), "-bf", "0", "-b:v", "4M", "-x264-params", "slices=1:repeat-headers=1:aud=0",
           "-f", "h264", "pipe:1"]
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE)
    buf, pending, n = b"", [], 0
    while True:
        chunk = p.stdout.read(65536)
        if not chunk:
            break
        buf += chunk
        # ne traiter que les NAL complètes : on garde la dernière (peut-être tronquée)
        nals = split_nal_units(buf)
        if len(nals) < 2:
            continue
        last_code = buf.rfind(b"\x00\x00\x01")
        buf = buf[max(0, last_code - 1):] if last_code > 0 and buf[last_code - 1] == 0 else buf[last_code:]
        for nal in nals[:-1]:
            t = nal[0] & 0x1F
            if t == 7:
                server.sps = nal
            elif t == 8:
                server.pps = nal
            elif t == 9:
                pass
            elif t in (1, 5):
                pending.append(nal)
                server.send_frame(pending, int(n * 1_000_000 / fps), t == 5)
                pending, n = [], n + 1
            else:
                pending.append(nal)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8554)
    ap.add_argument("--size", default="720x1280")
    ap.add_argument("--fps", type=int, default=30)
    a = ap.parse_args()
    srv = Server(a.port)
    srv.start()
    print("Faux telephone ViewCast sur rtsp://127.0.0.1:%d/stream (Ctrl+C pour arreter)" % a.port)
    try:
        while True:
            feed(srv, a.size, a.fps)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    sys.exit(main())
