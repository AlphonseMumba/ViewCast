"""Canal chiffré et authentifié par code PIN (protocole « VCS1 », voir docs/SECURITY.md).

Identique côté Android (security/SecureChannel.java) :
  SRP-6a (groupe MODP 2048 bits RFC 3526 n°14, SHA-256) pour prouver la connaissance du PIN sans
  l'envoyer, puis enregistrements AES-256-GCM (clés HKDF-SHA256, nonce = compteur 64 bits par sens).

Le sens TCP (qui se connecte) est indépendant du sens des médias : le canal est symétrique.
"""
import hashlib
import hmac
import io
import os
import socket
import struct
import threading

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

N_LEN = 256
SALT_LEN = 16
IDENTITY = b"viewcast"
MAGIC = b"VCS1"
MAX_PLAIN = 16384
TAG_LEN = 16
HANDSHAKE_TIMEOUT = 8.0

MSG_CLIENT_HELLO, MSG_SERVER_HELLO, MSG_CLIENT_PROOF, MSG_SERVER_PROOF, MSG_FAIL = 1, 2, 3, 4, 5
BAD_PROOF, LOCKED, PROTOCOL = 1, 2, 3

N = int(
    "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A0879"
    "8E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B"
    "0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA4836"
    "1C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804"
    "F1746C08CA18217C32905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6"
    "955817183995497CEA956AE515D2261898FA051015728E5A8AACAA68FFFFFFFFFFFFFFFF", 16)
G = 2


class HandshakeError(Exception):
    """Échec d'authentification ou de protocole ; .reason = BAD_PROOF / LOCKED / PROTOCOL."""

    def __init__(self, reason, message=""):
        super().__init__(message or "handshake failed (%d)" % reason)
        self.reason = reason


def _h(*parts):
    m = hashlib.sha256()
    for p in parts:
        m.update(p)
    return m.digest()


def pad(v):
    return v.to_bytes(N_LEN, "big")


_K = int.from_bytes(_h(pad(N), pad(G)), "big")


def _x(salt, pin):
    return int.from_bytes(_h(salt, _h(IDENTITY + b":" + pin.encode())), "big")


def _u(a, b):
    return int.from_bytes(_h(pad(a), pad(b)), "big")


def _random_private():
    return int.from_bytes(os.urandom(32), "big") + 1


def _proof_client(salt, a, b, key):
    xor = bytes(x ^ y for x, y in zip(_h(pad(N)), _h(pad(G))))
    return _h(xor, _h(IDENTITY), salt, pad(a), pad(b), key)


def _proof_server(a, m1, key):
    return _h(pad(a), m1, key)


def _hkdf32(ikm, salt, info):
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    return hmac.new(prk, info + b"\x01", hashlib.sha256).digest()


class SrpClient:
    def __init__(self, pin, private_a=None):
        self.pin = pin
        self._a = private_a or _random_private()
        self.A = pow(G, self._a, N)
        self.key = None
        self._m1 = None

    def process(self, salt, B):
        if B % N == 0:
            raise HandshakeError(PROTOCOL, "B invalide")
        u = _u(self.A, B)
        if u == 0:
            raise HandshakeError(PROTOCOL, "u invalide")
        x = _x(salt, self.pin)
        s = pow((B - _K * pow(G, x, N)) % N, self._a + u * x, N)
        self.key = _h(pad(s))
        self._m1 = _proof_client(salt, self.A, B, self.key)
        return self._m1

    def verify_server(self, m2):
        return hmac.compare_digest(m2, _proof_server(self.A, self._m1, self.key))


class SrpServer:
    def __init__(self, pin, salt=None, private_b=None):
        self.salt = salt or os.urandom(SALT_LEN)
        self._b = private_b or _random_private()
        self._v = pow(G, _x(self.salt, pin), N)
        self.B = (_K * self._v + pow(G, self._b, N)) % N
        self.key = None

    def verify(self, A, m1):
        if A % N == 0:
            raise HandshakeError(PROTOCOL, "A invalide")
        u = _u(A, self.B)
        if u == 0:
            raise HandshakeError(PROTOCOL, "u invalide")
        s = pow(A * pow(self._v, u, N) % N, self._b, N)
        key = _h(pad(s))
        if not hmac.compare_digest(m1, _proof_client(self.salt, A, self.B, key)):
            raise HandshakeError(BAD_PROOF, "code PIN incorrect")
        self.key = key
        return _proof_server(A, m1, key)


def _recv_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("connexion fermée")
        buf += chunk
    return bytes(buf)


def _write_message(sock, mtype, payload):
    sock.sendall(bytes([mtype]) + struct.pack(">H", len(payload)) + payload)


def _send_fail(sock, reason):
    try:
        _write_message(sock, MSG_FAIL, bytes([reason]))
    except OSError:
        pass


def _read_message(sock, expected):
    mtype = _recv_exact(sock, 1)[0]
    (length,) = struct.unpack(">H", _recv_exact(sock, 2))
    if length > 512:
        raise HandshakeError(PROTOCOL, "message trop long")
    payload = _recv_exact(sock, length)
    if mtype == MSG_FAIL:
        raise HandshakeError(payload[0] if payload else PROTOCOL, "refusé par le pair")
    if mtype != expected:
        raise HandshakeError(PROTOCOL, "message inattendu")
    return payload


def _nonce(counter):
    return b"\x00\x00\x00\x00" + struct.pack(">Q", counter)


class SecureChannel:
    """Socket chiffré : sendall()/recv() comme un socket normal, avec des enregistrements AES-GCM."""

    def __init__(self, sock, session_key, is_server):
        self.sock = sock
        c2s = _hkdf32(session_key, MAGIC, b"client-to-server")
        s2c = _hkdf32(session_key, MAGIC, b"server-to-client")
        self._send = AESGCM(s2c if is_server else c2s)
        self._recv = AESGCM(c2s if is_server else s2c)
        self._send_counter = 0
        self._recv_counter = 0
        self._buf = b""
        self._send_lock = threading.Lock()
        self._closed = False

    def sendall(self, data):
        with self._send_lock:
            for off in range(0, len(data), MAX_PLAIN):
                chunk = data[off:off + MAX_PLAIN]
                header = struct.pack(">I", len(chunk) + TAG_LEN)
                ct = self._send.encrypt(_nonce(self._send_counter), chunk, header)
                self._send_counter += 1
                self.sock.sendall(header + ct)

    def _read_record(self):
        first = self.sock.recv(1)
        if not first:
            return None                      # fin de flux propre
        header = first + _recv_exact(self.sock, 3)
        (clen,) = struct.unpack(">I", header)
        if clen < TAG_LEN or clen > MAX_PLAIN + TAG_LEN:
            raise ConnectionError("enregistrement invalide")
        ct = _recv_exact(self.sock, clen)
        try:
            plain = self._recv.decrypt(_nonce(self._recv_counter), ct, header)
        except Exception as exc:             # InvalidTag
            raise ConnectionError("enregistrement corrompu ou falsifié") from exc
        self._recv_counter += 1
        return plain

    def recv(self, n):
        """Jusqu'à n octets déchiffrés ; b'' en fin de flux."""
        while not self._buf:
            rec = self._read_record()
            if rec is None:
                return b""
            self._buf = rec
        out, self._buf = self._buf[:n], self._buf[n:]
        return out

    def makefile_r(self):
        """Flux binaire tamponné (read/readline) sur les données déchiffrées."""
        chan = self

        class _Raw(io.RawIOBase):
            def readable(self):
                return True

            def readinto(self, b):
                data = chan.recv(len(b))
                b[:len(data)] = data
                return len(data)

        return io.BufferedReader(_Raw())

    def makefile(self, mode="rb"):
        return self.makefile_r()

    def getsockname(self):
        return self.sock.getsockname()

    def shutdown_write(self):
        try:
            self.sock.shutdown(socket.SHUT_WR)
        except OSError:
            pass

    def close(self):
        self._closed = True
        try:
            self.sock.close()
        except OSError:
            pass


def server_handshake(sock, pin):
    """Listener : attend un client et vérifie son PIN. Lève HandshakeError ; renvoie un SecureChannel."""
    sock.settimeout(HANDSHAKE_TIMEOUT)
    try:
        hello = _read_message(sock, MSG_CLIENT_HELLO)
        if len(hello) != len(MAGIC) + N_LEN or hello[:4] != MAGIC:
            _send_fail(sock, PROTOCOL)
            raise HandshakeError(PROTOCOL, "client non VCS1")
        A = int.from_bytes(hello[4:], "big")
        srp = SrpServer(pin)
        _write_message(sock, MSG_SERVER_HELLO, srp.salt + pad(srp.B))
        m1 = _read_message(sock, MSG_CLIENT_PROOF)
        try:
            m2 = srp.verify(A, m1)
        except HandshakeError as exc:
            _send_fail(sock, exc.reason)
            raise
        _write_message(sock, MSG_SERVER_PROOF, m2)
        sock.settimeout(None)
        return SecureChannel(sock, srp.key, True)
    except (OSError, ConnectionError) as exc:
        raise HandshakeError(PROTOCOL, str(exc))


def client_handshake(sock, pin):
    """Initiateur : prouve la connaissance du PIN. Lève HandshakeError ; renvoie un SecureChannel."""
    sock.settimeout(HANDSHAKE_TIMEOUT)
    try:
        srp = SrpClient(pin)
        _write_message(sock, MSG_CLIENT_HELLO, MAGIC + pad(srp.A))
        hello = _read_message(sock, MSG_SERVER_HELLO)
        if len(hello) != SALT_LEN + N_LEN:
            raise HandshakeError(PROTOCOL, "réponse invalide")
        m1 = srp.process(hello[:SALT_LEN], int.from_bytes(hello[SALT_LEN:], "big"))
        _write_message(sock, MSG_CLIENT_PROOF, m1)
        m2 = _read_message(sock, MSG_SERVER_PROOF)
        if not srp.verify_server(m2):
            raise HandshakeError(BAD_PROOF, "serveur non authentique")
        sock.settimeout(None)
        return SecureChannel(sock, srp.key, False)
    except (OSError, ConnectionError) as exc:
        raise HandshakeError(PROTOCOL, str(exc))


def valid_pin(pin):
    return len(pin) == 6 and pin.isdigit()


class SecureProxy:
    """Pont local : le lecteur vidéo (FFmpeg/PyAV) parle RTSP en clair à 127.0.0.1, le proxy relaie
    ces octets chiffrés vers le téléphone. Une connexion locale = une connexion sécurisée distante.

    .status : "idle" | "ok" | "unreachable" | "auth_failed" | "locked" | "protocol"
    """

    def __init__(self, host, port, pin):
        self.host, self.port, self.pin = host, port, pin
        self.status = "idle"
        self._listener = socket.socket()
        self._listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._listener.bind(("127.0.0.1", 0))
        self._listener.listen(4)
        self.local_port = self._listener.getsockname()[1]
        self._stop = threading.Event()
        threading.Thread(target=self._accept_loop, daemon=True).start()

    @property
    def local_url(self):
        return "rtsp://127.0.0.1:%d/stream" % self.local_port

    def _accept_loop(self):
        while not self._stop.is_set():
            try:
                local, _ = self._listener.accept()
            except OSError:
                return
            threading.Thread(target=self._serve, args=(local,), daemon=True).start()

    def _serve(self, local):
        remote = None
        try:
            try:
                remote = socket.create_connection((self.host, self.port), timeout=5)
                remote.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            except OSError:
                self.status = "unreachable"
                return
            try:
                chan = client_handshake(remote, self.pin)
            except HandshakeError as exc:
                self.status = {BAD_PROOF: "auth_failed", LOCKED: "locked"}.get(exc.reason, "protocol")
                return
            self.status = "ok"
            local.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            t = threading.Thread(target=self._pipe_up, args=(local, chan), daemon=True)
            t.start()
            try:
                while True:                      # téléphone -> lecteur
                    data = chan.recv(65536)
                    if not data:
                        break
                    local.sendall(data)
            except (OSError, ConnectionError):
                pass
        finally:
            for s in (local, remote):
                if s is not None:
                    try:
                        s.close()
                    except OSError:
                        pass

    @staticmethod
    def _pipe_up(local, chan):
        try:
            while True:                          # lecteur -> téléphone
                data = local.recv(65536)
                if not data:
                    break
                chan.sendall(data)
        except (OSError, ConnectionError):
            pass
        finally:
            chan.close()

    def close(self):
        self._stop.set()
        try:
            self._listener.close()
        except OSError:
            pass
