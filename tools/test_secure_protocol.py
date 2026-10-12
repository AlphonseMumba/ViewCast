#!/usr/bin/env python3
"""Tests du canal sécurisé (PIN + chiffrement) : Python seul, puis Python <-> Java (le vrai code Android).

Usage : python tools/test_secure_protocol.py      (Java 8+ requis pour la partie interopérabilité)
"""
import os
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "desktop", "python"))
import vc_secure as s  # noqa: E402
from cryptography.hazmat.primitives.ciphers.aead import AESGCM  # noqa: E402

VEC = {"A": "ad8fc4a1fba7c548db731b8ecbca41a8a76c825ff3da5f262258d56c934c48f141b29eabc56996f440352a382ed61b75902ffe91efd16a6f08d3cb2b195b383387625410128840e09bd4df56c5ed40187949e519210e3e0ba9615d4a5c0828a41a3132f3c18b2bb1dfc2cbb91ce919c36cbd4c9cc22de8a277a5e90016c622b9d0396414b2f752ab9e0f3572ff55ccf58ca3f8aee6f7b0decd3535a87c745ba7c955be211671ed6f87f3af4d4fd37cf21b5cbfa446c72ef406917ad435b7a95f15b8e21b535bd3446d52d51c4668b408d3c900046b2526921d027b2ec87ee3873d609af90a901eb45504eb52370c0131eaeeea3b75e843e516e6625bc7e8400e", "B": "685c01e76917a6abb8b851222640234d8e70d744ce097e744a8e3e92a39924800a82f6a538ecd5d112f9a0dee0e08c630fedc552aad5a3601dab2c0608e96b2a71223697551d6567bf2a7d8d3a2235a9da4bd94f51212d4752a0c7376ccd7ffa706ad7d970f91f8630c140fe75e77d70e48b1d4fc909942eb67422f5006a5b53ff47d9ceeea6472739869fe58983d0f883f2062d5e9d1fa58acd1f29092a59cd07a464252318709d21655ca743d0f026a79b2614654241c25b10b597f91927f28b14145af14eefb7dee71236489f50127dfe8d5b850f7b65db9f83dc7f34db665e065a29ac28178d5adfbf092610e24856d5c6684e7746a2c9a21ef47d47bfca", "M1": "1d1a8e7d2f0d5cf37c18f64c7d66a233ba6f32b4d3a4b516554fb025711e728e", "M2": "d206cc8df51824b8d56b3cab9f7cd32cc7d83fc415561a0d35f8642129b15135", "K": "31e2c4c829f925374fbeda9ebc7a5c89690ea62fde455f4d929f68187b9dffb0", "C2S": "d2232c06d454065f0eefe74361f487bda12d8081e5f9224d04ad1f31a59f9ffb", "REC": "ab7e9ca93265b6169154a59b861c31ad996be8fe6007dc8c23c21e5f64eaa481aae3f22e"}
ok = []


def check(cond, label):
    ok.append(bool(cond))
    print(("  OK   " if cond else "  ÉCHEC ") + label)


def pair(pin_server, pin_client):
    ls = socket.socket()
    ls.bind(("127.0.0.1", 0))
    ls.listen(1)
    res = {}

    def srv():
        conn, _ = ls.accept()
        try:
            res["s"] = s.server_handshake(conn, pin_server)
        except s.HandshakeError as e:
            res["se"] = e.reason

    t = threading.Thread(target=srv)
    t.start()
    cl = socket.create_connection(ls.getsockname())
    try:
        res["c"] = s.client_handshake(cl, pin_client)
    except s.HandshakeError as e:
        res["ce"] = e.reason
    t.join()
    ls.close()
    return res


print("1. Vecteurs de test (communs avec le JUnit Android)")
salt = bytes(range(16))
c = s.SrpClient("123456", int.from_bytes(bytes([0x11]) * 32, "big"))
sv = s.SrpServer("123456", salt, int.from_bytes(bytes([0x22]) * 32, "big"))
m1 = c.process(salt, sv.B)
m2 = sv.verify(c.A, m1)
check(s.pad(c.A).hex() == VEC["A"] and s.pad(sv.B).hex() == VEC["B"], "A et B identiques à Java")
check(m1.hex() == VEC["M1"] and m2.hex() == VEC["M2"] and c.key.hex() == VEC["K"],
      "preuves M1/M2 et clé de session identiques à Java")
c2s = s._hkdf32(c.key, b"VCS1", b"client-to-server")
check(c2s.hex() == VEC["C2S"], "clé de chiffrement HKDF identique à Java")
rec = AESGCM(c2s).encrypt(bytes(12), b"ViewCast vector test", struct.pack(">I", 36))
check(rec.hex() == VEC["REC"], "enregistrement AES-GCM identique à Java")

print("2. Python <-> Python (boucle locale)")
r = pair("482913", "482913")
check("s" in r and "c" in r, "bon PIN : canal établi")
data = os.urandom(200000)
threading.Thread(target=lambda: r["c"].sendall(data), daemon=True).start()
got = b""
while len(got) < len(data):
    got += r["s"].recv(65536)
check(got == data, "200 Ko chiffrés client -> serveur reçus intacts")
r["s"].sendall(b"retour")
check(r["c"].recv(100) == b"retour", "serveur -> client")
r = pair("482913", "482914")
check(r.get("se") == s.BAD_PROOF and r.get("ce") == s.BAD_PROOF, "mauvais PIN : refusé des deux côtés (BAD_PROOF)")

r = pair("111111", "111111")        # falsification : un octet chiffré est modifié en transit
ch = r["c"]
header = struct.pack(">I", 5 + 16)
enc = ch._send.encrypt(s._nonce(ch._send_counter), b"hello", header)
tampered = bytearray(header + enc)
tampered[6] ^= 1
ch.sock.sendall(bytes(tampered))
try:
    r["s"].recv(10)
    check(False, "falsification détectée")
except ConnectionError:
    check(True, "octet modifié en transit : enregistrement rejeté (GCM)")

r = pair("222222", "222222")        # rejeu : un enregistrement valide envoyé deux fois
ch = r["c"]
header = struct.pack(">I", 3 + 16)
enc = ch._send.encrypt(s._nonce(ch._send_counter), b"abc", header)
ch.sock.sendall(header + enc)
ch.sock.sendall(header + enc)
check(r["s"].recv(10) == b"abc", "enregistrement valide accepté")
try:
    r["s"].recv(10)
    check(False, "rejeu détecté")
except ConnectionError:
    check(True, "enregistrement rejoué : refusé (compteur de nonce)")

ls = socket.socket()                # un lecteur RTSP en clair se connecte au port sécurisé
ls.bind(("127.0.0.1", 0))
ls.listen(1)
out = {}


def srv2():
    conn, _ = ls.accept()
    try:
        s.server_handshake(conn, "123456")
    except s.HandshakeError as e:
        out["e"] = e.reason


th = threading.Thread(target=srv2)
th.start()
cl = socket.create_connection(ls.getsockname())
cl.sendall(b"OPTIONS rtsp://x/stream RTSP/1.0\r\nCSeq: 1\r\n\r\n")
th.join()
check(out.get("e") == s.PROTOCOL, "client RTSP en clair : refusé (PROTOCOL)")
ls.close()

print("3. Python <-> Java (code Android réel)")
SEC = os.path.join(HERE, "..", "android", "app", "src", "main", "java", "com", "example", "viewcast", "security")
srcs = [os.path.join(SEC, f) for f in ("HandshakeException.java", "Kdf.java", "Srp.java", "SecureChannel.java", "PinGuard.java")]
srcs.append(os.path.join(HERE, "java", "Interop.java"))
if shutil.which("java") is None:
    print("  (Java absent : partie interopérabilité ignorée)")
else:
    jdir = tempfile.mkdtemp()
    javac = shutil.which("javac")
    cmd = ([javac] if javac else ["java", "-m", "jdk.compiler/com.sun.tools.javac.Main"]) + \
        ["-source", "8", "-target", "8", "-Xlint:-options", "-d", jdir] + srcs
    comp = subprocess.run(cmd, capture_output=True, text=True)
    check(comp.returncode == 0, "le code Java de sécurité compile (cible Java 8, comme Android)")
    if comp.returncode == 0:
        jrun = ["java", "-cp", jdir, "com.example.viewcast.security.Interop"]

        def free_port():
            sk = socket.socket()
            sk.bind(("127.0.0.1", 0))
            p = sk.getsockname()[1]
            sk.close()
            return p

        port = free_port()                          # a) serveur Java, client Python
        p = subprocess.Popen(jrun + ["serve", str(port), "2", "482913"], stdout=subprocess.PIPE, text=True)
        p.stdout.readline()
        time.sleep(0.2)
        ch = s.client_handshake(socket.create_connection(("127.0.0.1", port)), "482913")
        payload = os.urandom(700000)
        threading.Thread(target=lambda: (ch.sendall(payload), ch.shutdown_write()), daemon=True).start()
        back = b""
        while len(back) < len(payload):
            d = ch.recv(65536)
            if not d:
                break
            back += d
        check(back == payload, "client Python -> serveur Java : 700 Ko aller-retour intacts")
        try:
            s.client_handshake(socket.create_connection(("127.0.0.1", port)), "000000")
            check(False, "mauvais PIN refusé par Java")
        except s.HandshakeError as e:
            check(e.reason == s.BAD_PROOF, "client Python avec mauvais PIN : refusé par le serveur Java")
        p.wait(timeout=15)

        ls = socket.socket()                        # b) serveur Python, client Java
        ls.bind(("127.0.0.1", 0))
        ls.listen(2)
        port = ls.getsockname()[1]

        def echo():
            conn, _ = ls.accept()
            ch2 = s.server_handshake(conn, "135790")
            while True:
                d = ch2.recv(65536)
                if not d:
                    break
                ch2.sendall(d)
            ch2.shutdown_write()

        threading.Thread(target=echo, daemon=True).start()
        res = subprocess.run(jrun + ["connect", "127.0.0.1", str(port), "135790"], capture_output=True, text=True, timeout=60)
        check("CLIENT_ECHO_OK 1500000" in res.stdout, "client Java -> serveur Python : 1,5 Mo aller-retour intacts")

        def srv3():
            conn, _ = ls.accept()
            try:
                s.server_handshake(conn, "135790")
            except s.HandshakeError:
                pass

        threading.Thread(target=srv3, daemon=True).start()
        res = subprocess.run(jrun + ["connect", "127.0.0.1", str(port), "999999"], capture_output=True, text=True, timeout=30)
        check("CLIENT_AUTH_FAIL 1" in res.stdout, "client Java avec mauvais PIN : refusé par le serveur Python")
        ls.close()

print("\nRésultat : %d/%d tests réussis" % (sum(ok), len(ok)))
sys.exit(0 if all(ok) else 1)
