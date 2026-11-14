package app.gcaltolotus.adapters.teams

/**
 * UUID версии 5 (RFC 4122, SHA-1), как Python `uuid.uuid5(namespace, name)`; имя в UTF-8.
 * В общем коде Kotlin нет SHA-1, поэтому он реализован здесь ([sha1]).
 */
internal fun uuid5(namespace: String, name: String): String {
    val ns = namespace.replace("-", "")
    require(ns.length == 32) { "Неверный UUID пространства имён: $namespace" }
    val nsBytes = ByteArray(16) { ns.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    val hash = sha1(nsBytes + name.encodeToByteArray()).copyOf(16)
    hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
    hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = hash.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20)}"
}

/** SHA-1 (FIPS 180-4). Только для UUID5, не для защиты. */
internal fun sha1(data: ByteArray): ByteArray {
    var h0 = 0x67452301
    var h1 = 0xEFCDAB89.toInt()
    var h2 = 0x98BADCFE.toInt()
    var h3 = 0x10325476
    var h4 = 0xC3D2E1F0.toInt()

    val padding = (55 - data.size % 64 + 64) % 64
    val message = ByteArray(data.size + 1 + padding + 8)
    data.copyInto(message)
    message[data.size] = 0x80.toByte()
    val bits = data.size.toLong() * 8
    for (i in 0 until 8) message[message.size - 1 - i] = (bits ushr (8 * i)).toByte()

    val w = IntArray(80)
    for (chunk in message.indices step 64) {
        for (i in 0 until 16) {
            val j = chunk + 4 * i
            w[i] = ((message[j].toInt() and 0xff) shl 24) or
                ((message[j + 1].toInt() and 0xff) shl 16) or
                ((message[j + 2].toInt() and 0xff) shl 8) or
                (message[j + 3].toInt() and 0xff)
        }
        for (i in 16 until 80) w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)

        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4
        for (i in 0 until 80) {
            val f: Int
            val k: Int
            when {
                i < 20 -> { f = (b and c) or (b.inv() and d); k = 0x5A827999 }
                i < 40 -> { f = b xor c xor d; k = 0x6ED9EBA1 }
                i < 60 -> { f = (b and c) or (b and d) or (c and d); k = 0x8F1BBCDC.toInt() }
                else -> { f = b xor c xor d; k = 0xCA62C1D6.toInt() }
            }
            val t = a.rotateLeft(5) + f + e + k + w[i]
            e = d
            d = c
            c = b.rotateLeft(30)
            b = a
            a = t
        }
        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
    }

    val out = ByteArray(20)
    for ((n, h) in intArrayOf(h0, h1, h2, h3, h4).withIndex()) {
        for (i in 0 until 4) out[4 * n + i] = (h ushr (24 - 8 * i)).toByte()
    }
    return out
}
