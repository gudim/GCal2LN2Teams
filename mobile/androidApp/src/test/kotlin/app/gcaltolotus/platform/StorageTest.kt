package app.gcaltolotus.platform

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.gcaltolotus.adapters.teams.TeamsTokens
import app.gcaltolotus.app.AppRelease
import app.gcaltolotus.app.AttendeeAnswer
import app.gcaltolotus.app.GoogleTokens
import app.gcaltolotus.app.Route
import app.gcaltolotus.app.SyncSettings
import app.gcaltolotus.app.UpdateState
import app.gcaltolotus.sync.System
import java.io.File
import java.nio.file.Files
import java.security.GeneralSecurityException
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCodecTest {
    @Test
    fun emptyGivesDefaults() {
        assertEquals(SyncSettings(), SettingsCodec.read(emptyPreferences()))
    }

    @Test
    fun roundTrip() {
        val s = SyncSettings(
            dominoUrl = "https://domino.example/mail/u.nsf",
            dominoUser = "user",
            googleCalendarId = "work@group.calendar.google.com",
            direction = "all",
            routes = setOf(Route(System.GOOGLE, System.DOMINO), Route(System.TEAMS, System.GOOGLE)),
            daysBack = 3,
            daysForward = 60,
            backgroundIntervalMinutes = 240,
            backgroundConfigured = true,
            dominoDisabled = true,
            lastDominoSuccess = Instant.parse("2026-11-14T10:00:00Z"),
            notifyBackgroundSummary = true,
            confirmEachAction = true,
            manualApplied = true,
            attendeeAnswers = mapOf(
                "update:domino:d1" to AttendeeAnswer("Обзор | 1", allow = true),
                "update:teams:t1" to AttendeeAnswer("", allow = false),
            ),
        )
        val p = mutablePreferencesOf()
        SettingsCodec.write(s, p)
        assertEquals(s, SettingsCodec.read(p))
        // Сброс маршрутов и времени удаляет ключи.
        SettingsCodec.write(s.copy(routes = null, lastDominoSuccess = null), p)
        assertEquals(s.copy(routes = null, lastDominoSuccess = null), SettingsCodec.read(p))
    }

    @Test
    fun freshInstallHasBackgroundOffUntilFirstApply() {
        val s = SettingsCodec.read(emptyPreferences())
        assertEquals(0, s.backgroundIntervalMinutes)
        assertFalse(s.backgroundConfigured)
        // Записанные настройки новой установки остаются «не настроенными».
        val p = mutablePreferencesOf()
        SettingsCodec.write(s.copy(daysBack = 5), p)
        assertFalse(SettingsCodec.read(p).backgroundConfigured)
        // До первого ручного применения автоприменения нет (ADR 0004).
        assertFalse(s.autoApply)
        assertFalse(SettingsCodec.read(p).manualApplied)
    }

    @Test
    fun installWithoutConfiguredKeyKeepsItsInterval() {
        // Версия без background_configured всегда писала период (по умолчанию 60).
        val p = mutablePreferencesOf(intPreferencesKey("background_interval_minutes") to 60)
        val s = SettingsCodec.read(p)
        assertEquals(60, s.backgroundIntervalMinutes)
        assertTrue(s.backgroundConfigured)
        assertEquals(s, s.afterManualApply())
        // Ключа итога фоновой синхронизации в прежних версиях не было: он выключен.
        assertFalse(s.notifyBackgroundSummary)
        // Фон настроен — ручное применение было: автоприменение включено (ADR 0004).
        assertTrue(s.manualApplied)
        assertTrue(s.autoApply)
    }

    @Test
    fun badValuesFallBackToDefaults() {
        val p = mutablePreferencesOf(
            stringPreferencesKey("direction") to "nonsense",
            stringPreferencesKey("last_domino_success") to "вчера",
        )
        val s = SettingsCodec.read(p)
        assertEquals(SyncSettings().direction, s.direction)
        assertNull(s.lastDominoSuccess)
    }
}

class DataStoreSettingsStoreTest {
    /**
     * Фон и экран меняют настройки одновременно (dominoDisabled и маршрут). Ни одно изменение не
     * должно затереть другое: update — одно атомарное чтение-изменение-запись.
     */
    @Test
    fun concurrentUpdatesAreNotLost() {
        val dir = Files.createTempDirectory("settings").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val data = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") }
            val store = DataStoreSettingsStore(data)
            runBlocking {
                (1..50).map { launch(Dispatchers.IO) { store.update { it.copy(daysBack = it.daysBack + 1) } } }.joinAll()
                launch(Dispatchers.IO) { store.update { it.copy(dominoDisabled = true) } }.join()
            }
            val s = runBlocking { store.load() }
            assertEquals(SyncSettings().daysBack + 50, s.daysBack)
            assertTrue(s.dominoDisabled)
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}

class SecretCodecTest {
    @Test
    fun teamsRoundTrip() {
        val t = TeamsTokens("a", "r", Instant.parse("2026-11-14T11:00:00Z"))
        assertEquals(t, SecretCodec.teamsFromJson(SecretCodec.teamsToJson(t)))
        val noRefresh = t.copy(refreshToken = null)
        assertEquals(noRefresh, SecretCodec.teamsFromJson(SecretCodec.teamsToJson(noRefresh)))
    }

    @Test
    fun googleRoundTrip() {
        val g = GoogleTokens("a", null, Instant.parse("2026-11-14T11:00:00Z"))
        assertEquals(g, SecretCodec.googleFromJson(SecretCodec.googleToJson(g)))
        val noExpiry = GoogleTokens("a", "r", null)
        assertEquals(noExpiry, SecretCodec.googleFromJson(SecretCodec.googleToJson(noExpiry)))
    }

    @Test
    fun garbageIsAbsent() {
        assertNull(SecretCodec.teamsFromJson("not json"))
        assertNull(SecretCodec.teamsFromJson("{\"access_token\":\"a\"}"))
        assertNull(SecretCodec.googleFromJson("[]"))
    }
}

/** Логика [KeystoreSecretStore] на настоящем DataStore с подставным шифром (Keystore на JVM нет). */
class KeystoreSecretStoreTest {
    /** «Шифр» XOR с AAD-проверкой через префикс имени; [broken] — ключ потерян. */
    private class FakeCipher : SecretCipher {
        var broken = false

        override fun encrypt(name: String, plain: ByteArray): ByteArray =
            (name + "|").encodeToByteArray() + plain.map { (it.toInt() xor 0x5A).toByte() }

        override fun decrypt(name: String, blob: ByteArray): ByteArray {
            if (broken) throw GeneralSecurityException("ключ потерян")
            val prefix = (name + "|").encodeToByteArray()
            if (!blob.copyOfRange(0, prefix.size).contentEquals(prefix)) throw GeneralSecurityException("AAD")
            return blob.copyOfRange(prefix.size, blob.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    private fun <T> withStore(block: suspend (KeystoreSecretStore, FakeCipher, File) -> T): T {
        val dir = Files.createTempDirectory("secrets").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val file = File(dir, "secrets.preferences_pb")
            val data = PreferenceDataStoreFactory.create(scope = scope) { file }
            val cipher = FakeCipher()
            return runBlocking { block(KeystoreSecretStore(data, cipher), cipher, file) }
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun storesEncryptedAndReadsBack() = withStore { store, _, file ->
        store.setDominoPassword("пароль-123")
        val tokens = TeamsTokens("access", "refresh", Instant.parse("2026-11-14T11:00:00Z"))
        store.setTeamsTokens(tokens)
        assertEquals("пароль-123", store.dominoPassword())
        assertEquals(tokens, store.teamsTokens())
        assertNull(store.googleTokens())
        assertFalse("пароль-123".encodeToByteArray().let { p -> file.readBytes().decodeToString().contains(p.decodeToString()) })
    }

    @Test
    fun nullDeletes() = withStore { store, _, _ ->
        store.setDominoPassword("x")
        store.setDominoPassword(null)
        assertNull(store.dominoPassword())
    }

    @Test
    fun undecryptableSecretIsDroppedAsAbsent() = withStore { store, cipher, _ ->
        store.setGoogleTokens(GoogleTokens("a", null, null))
        cipher.broken = true
        assertNull(store.googleTokens())
        cipher.broken = false
        assertNull(store.googleTokens()) // удалён, а не просто не прочитан
    }
}

class DataStoreUpdateStoreTest {
    @Test
    fun roundTripsAndClears() {
        val dir = Files.createTempDirectory("updates").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = DataStoreUpdateStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "updates.preferences_pb") })
            val full = UpdateState(Instant.parse("2026-11-14T10:00:00Z"), AppRelease("0.2.0", "https://example.com/r"), "0.2.0")
            runBlocking {
                assertEquals(UpdateState(), store.load())
                store.save(full)
                assertEquals(full, store.load())
                store.save(UpdateState(lastCheck = full.lastCheck))
                assertEquals(UpdateState(lastCheck = full.lastCheck), store.load())
            }
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}
