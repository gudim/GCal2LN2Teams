package app.gcaltolotus.platform

import app.gcaltolotus.adapters.teams.TeamsTokens
import app.gcaltolotus.app.GoogleTokens
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Токены в JSON перед шифрованием. Негодный JSON читается как `null` (секрета нет), чтобы
 * испорченная запись вела к повторному входу, а не к падению.
 */
internal object SecretCodec {
    fun teamsToJson(t: TeamsTokens): String = buildJsonObject {
        put("access_token", t.accessToken)
        put("refresh_token", t.refreshToken)
        put("expires_at", t.expiresAt.toString())
    }.toString()

    fun teamsFromJson(text: String): TeamsTokens? {
        val o = parse(text) ?: return null
        val access = o.string("access_token") ?: return null
        val expires = o.string("expires_at")?.let(::instantOrNull) ?: return null
        return TeamsTokens(access, o.string("refresh_token"), expires)
    }

    fun googleToJson(t: GoogleTokens): String = buildJsonObject {
        put("access_token", t.accessToken)
        put("refresh_token", t.refreshToken)
        put("expires_at", t.expiresAt?.toString())
    }.toString()

    fun googleFromJson(text: String): GoogleTokens? {
        val o = parse(text) ?: return null
        val access = o.string("access_token") ?: return null
        return GoogleTokens(access, o.string("refresh_token"), o.string("expires_at")?.let(::instantOrNull))
    }

    private fun parse(text: String): JsonObject? = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull

    private fun instantOrNull(text: String): Instant? = try {
        Instant.parse(text)
    } catch (e: IllegalArgumentException) {
        null
    }
}
