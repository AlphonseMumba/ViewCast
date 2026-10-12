package com.example.viewcast.security;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Test;

/** Vecteurs de test communs avec desktop/python/vc_secure.py (voir tools/test_secure_protocol.py). */
public class SrpVectorsTest {
    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    private static byte[] fill(int n, int v) {
        byte[] r = new byte[n];
        Arrays.fill(r, (byte) v);
        return r;
    }

    private static byte[] unhex(String h) {
        byte[] r = new byte[h.length() / 2];
        for (int i = 0; i < r.length; i++) r[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return r;
    }

    @Test
    public void handshakeMatchesPythonVectors() throws Exception {
        byte[] salt = new byte[16];
        for (int i = 0; i < 16; i++) salt[i] = (byte) i;
        Srp.Client c = new Srp.Client("123456", new BigInteger(1, fill(32, 0x11)));
        Srp.Server s = new Srp.Server("123456", salt, new BigInteger(1, fill(32, 0x22)));
        byte[] m1 = c.process(salt, s.bigB);
        byte[] m2 = s.verify(c.bigA, m1);
        assertEquals("ad8fc4a1fba7c548db731b8ecbca41a8a76c825ff3da5f262258d56c934c48f141b29eabc56996f440352a382ed61b75902ffe91efd16a6f08d3cb2b195b383387625410128840e09bd4df56c5ed40187949e519210e3e0ba9615d4a5c0828a41a3132f3c18b2bb1dfc2cbb91ce919c36cbd4c9cc22de8a277a5e90016c622b9d0396414b2f752ab9e0f3572ff55ccf58ca3f8aee6f7b0decd3535a87c745ba7c955be211671ed6f87f3af4d4fd37cf21b5cbfa446c72ef406917ad435b7a95f15b8e21b535bd3446d52d51c4668b408d3c900046b2526921d027b2ec87ee3873d609af90a901eb45504eb52370c0131eaeeea3b75e843e516e6625bc7e8400e", hex(Srp.pad(c.bigA)));
        assertEquals("685c01e76917a6abb8b851222640234d8e70d744ce097e744a8e3e92a39924800a82f6a538ecd5d112f9a0dee0e08c630fedc552aad5a3601dab2c0608e96b2a71223697551d6567bf2a7d8d3a2235a9da4bd94f51212d4752a0c7376ccd7ffa706ad7d970f91f8630c140fe75e77d70e48b1d4fc909942eb67422f5006a5b53ff47d9ceeea6472739869fe58983d0f883f2062d5e9d1fa58acd1f29092a59cd07a464252318709d21655ca743d0f026a79b2614654241c25b10b597f91927f28b14145af14eefb7dee71236489f50127dfe8d5b850f7b65db9f83dc7f34db665e065a29ac28178d5adfbf092610e24856d5c6684e7746a2c9a21ef47d47bfca", hex(Srp.pad(s.bigB)));
        assertEquals("1d1a8e7d2f0d5cf37c18f64c7d66a233ba6f32b4d3a4b516554fb025711e728e", hex(m1));
        assertEquals("d206cc8df51824b8d56b3cab9f7cd32cc7d83fc415561a0d35f8642129b15135", hex(m2));
        assertEquals("31e2c4c829f925374fbeda9ebc7a5c89690ea62fde455f4d929f68187b9dffb0", hex(c.sessionKey()));
        assertTrue(c.verifyServer(m2));
        assertArrayEquals(c.sessionKey(), s.sessionKey());
    }

    @Test
    public void recordEncryptionMatchesPythonVector() throws Exception {
        byte[] c2s = Kdf.hkdf32(unhex("31e2c4c829f925374fbeda9ebc7a5c89690ea62fde455f4d929f68187b9dffb0"), "VCS1".getBytes(StandardCharsets.US_ASCII),
                "client-to-server".getBytes(StandardCharsets.US_ASCII));
        assertEquals("d2232c06d454065f0eefe74361f487bda12d8081e5f9224d04ad1f31a59f9ffb", hex(c2s));
        byte[] plain = "ViewCast vector test".getBytes(StandardCharsets.US_ASCII);
        int clen = plain.length + 16;
        byte[] header = new byte[] {0, 0, (byte) (clen >> 8), (byte) clen};
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(c2s, "AES"), new GCMParameterSpec(128, new byte[12]));
        ci.updateAAD(header);
        assertEquals("ab7e9ca93265b6169154a59b861c31ad996be8fe6007dc8c23c21e5f64eaa481aae3f22e", hex(ci.doFinal(plain)));
    }

    @Test
    public void wrongPinIsRejected() throws Exception {
        byte[] salt = new byte[16];
        Srp.Client c = new Srp.Client("000000", new BigInteger(1, fill(32, 0x11)));
        Srp.Server s = new Srp.Server("123456", salt, new BigInteger(1, fill(32, 0x22)));
        byte[] m1 = c.process(salt, s.bigB);
        try {
            s.verify(c.bigA, m1);
            fail("un mauvais PIN ne doit pas etre accepte");
        } catch (HandshakeException e) {
            assertEquals(HandshakeException.BAD_PROOF, e.reason);
        }
    }

    @Test
    public void pinFormatAndLockout() {
        assertTrue(PinGuard.isValid("012345"));
        assertTrue(!PinGuard.isValid("12345") && !PinGuard.isValid("12a456"));
        PinGuard g = new PinGuard(3);
        assertTrue(!g.recordFailure() && !g.recordFailure() && g.recordFailure());
        assertTrue(PinGuard.isValid(PinGuard.newPin()));
    }
}
