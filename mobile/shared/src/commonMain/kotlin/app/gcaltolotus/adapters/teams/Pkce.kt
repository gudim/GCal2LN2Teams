package app.gcaltolotus.adapters.teams

import io.ktor.http.URLBuilder
import kotlin.io.encoding.Base64
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// PKCE (RFC 7636, метод S256) для публичного клиента Teams: секрета нет, поэтому код
// авторизации привязывается к случайному code_verifier, который знает только это приложение.

private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

/**
 * Новый `code_verifier`: 48 случайных байт в base64url без `=` (64 символа из набора
 * unreserved `[A-Za-z0-9-_]`, RFC 7636 требует 43–128).
 *
 * Источник случайности — [Uuid.random]: в общем Kotlin это единственный генератор, который по
 * документации криптостойкий на всех платформах (SecureRandom на JVM/Android,
 * SecRandomCopyBytes/arc4random на Apple). `kotlin.random.Random` для этого не годится. В
 * UUID v4 из 128 бит случайны 122 (6 бит — версия и вариант), так что три UUID дают 366 бит
 * энтропии, больше рекомендуемых RFC 256.
 */
@OptIn(ExperimentalUuidApi::class)
fun newPkceVerifier(): String =
    base64Url.encode(Uuid.random().toByteArray() + Uuid.random().toByteArray() + Uuid.random().toByteArray())

/** `code_challenge` для метода S256: base64url(SHA-256(ASCII(verifier))) без `=`. */
fun pkceChallenge(verifier: String): String = base64Url.encode(sha256(verifier.encodeToByteArray()))

/**
 * Loopback-redirect с портом: `http://localhost` и 53124 дают `http://localhost:53124`.
 * Microsoft для loopback-адресов не сверяет порт с регистрацией, поэтому браузер входа слушает
 * свободный порт и подставляет его сюда.
 */
fun loopbackRedirectUri(base: String, port: Int): String = URLBuilder(base).apply { this.port = port }.buildString()

/** Константы раундов SHA-256: дробные части кубических корней первых 64 простых. */
private val K = intArrayOf(
    0x428A2F98, 0x71374491, 0xB5C0FBCF.toInt(), 0xE9B5DBA5.toInt(),
    0x3956C25B, 0x59F111F1, 0x923F82A4.toInt(), 0xAB1C5ED5.toInt(),
    0xD807AA98.toInt(), 0x12835B01, 0x243185BE, 0x550C7DC3,
    0x72BE5D74, 0x80DEB1FE.toInt(), 0x9BDC06A7.toInt(), 0xC19BF174.toInt(),
    0xE49B69C1.toInt(), 0xEFBE4786.toInt(), 0x0FC19DC6, 0x240CA1CC,
    0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
    0x983E5152.toInt(), 0xA831C66D.toInt(), 0xB00327C8.toInt(), 0xBF597FC7.toInt(),
    0xC6E00BF3.toInt(), 0xD5A79147.toInt(), 0x06CA6351, 0x14292967,
    0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13,
    0x650A7354, 0x766A0ABB, 0x81C2C92E.toInt(), 0x92722C85.toInt(),
    0xA2BFE8A1.toInt(), 0xA81A664B.toInt(), 0xC24B8B70.toInt(), 0xC76C51A3.toInt(),
    0xD192E819.toInt(), 0xD6990624.toInt(), 0xF40E3585.toInt(), 0x106AA070,
    0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5,
    0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
    0x748F82EE, 0x78A5636F, 0x84C87814.toInt(), 0x8CC70208.toInt(),
    0x90BEFFFA.toInt(), 0xA4506CEB.toInt(), 0xBEF9A3F7.toInt(), 0xC67178F2.toInt(),
)

/** SHA-256 (FIPS 180-4): в общем коде Kotlin его нет. Для PKCE; написан как [sha1]. */
internal fun sha256(data: ByteArray): ByteArray {
    val h = intArrayOf(
        0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt(),
        0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB, 0x5BE0CD19,
    )

    val padding = (55 - data.size % 64 + 64) % 64
    val message = ByteArray(data.size + 1 + padding + 8)
    data.copyInto(message)
    message[data.size] = 0x80.toByte()
    val bits = data.size.toLong() * 8
    for (i in 0 until 8) message[message.size - 1 - i] = (bits ushr (8 * i)).toByte()

    val w = IntArray(64)
    for (chunk in message.indices step 64) {
        for (i in 0 until 16) {
            val j = chunk + 4 * i
            w[i] = ((message[j].toInt() and 0xff) shl 24) or
                ((message[j + 1].toInt() and 0xff) shl 16) or
                ((message[j + 2].toInt() and 0xff) shl 8) or
                (message[j + 3].toInt() and 0xff)
        }
        for (i in 16 until 64) {
            val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
            val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }

        var a = h[0]
        var b = h[1]
        var c = h[2]
        var d = h[3]
        var e = h[4]
        var f = h[5]
        var g = h[6]
        var hh = h[7]
        for (i in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = hh + s1 + ch + K[i] + w[i]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + maj
            hh = g
            g = f
            f = e
            e = d + t1
            d = c
            c = b
            b = a
            a = t1 + t2
        }
        h[0] += a
        h[1] += b
        h[2] += c
        h[3] += d
        h[4] += e
        h[5] += f
        h[6] += g
        h[7] += hh
    }

    val out = ByteArray(32)
    for ((n, v) in h.withIndex()) {
        for (i in 0 until 4) out[4 * n + i] = (v ushr (24 - 8 * i)).toByte()
    }
    return out
}
