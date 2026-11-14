package app.gcaltolotus.adapters.teams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** SHA-256 на стандартных векторах (FIPS 180-4) и PKCE по RFC 7636. */
class PkceTest {
    private fun hex(bytes: ByteArray) =
        bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun sha256OfEmptyString() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hex(sha256(ByteArray(0))),
        )
    }

    @Test
    fun sha256OfAbc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex(sha256("abc".encodeToByteArray())),
        )
    }

    @Test
    fun sha256Of448BitMessage() {
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex(sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())),
        )
    }

    @Test
    fun sha256OfMillionAs() {
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            hex(sha256(ByteArray(1_000_000) { 'a'.code.toByte() })),
        )
    }

    @Test
    fun challengeMatchesRfc7636AppendixB() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun verifierIsLongRandomAndUnreserved() {
        val a = newPkceVerifier()
        val b = newPkceVerifier()
        assertNotEquals(a, b)
        for (v in listOf(a, b)) {
            assertTrue(v.length in 43..128, v)
            assertTrue(v.all { (it.isLetterOrDigit() && it.code < 128) || it in "-._~" }, v)
        }
    }

    @Test
    fun loopbackRedirectGetsThePort() {
        assertEquals("http://localhost:53124", loopbackRedirectUri("http://localhost", 53124))
        assertEquals("http://localhost:53124/cb", loopbackRedirectUri("http://localhost/cb", 53124))
    }
}
