package app.gcaltolotus.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.gcaltolotus.adapters.teams.TeamsTokens
import app.gcaltolotus.app.GoogleTokens
import app.gcaltolotus.app.SecretStore
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first

/**
 * Шифрование секретов. [name] — имя секрета; оно входит в AAD, поэтому шифротекст одного
 * секрета нельзя подложить под другой.
 */
interface SecretCipher {
    fun encrypt(name: String, plain: ByteArray): ByteArray

    /** @throws GeneralSecurityException ключ потерян или данные испорчены. */
    fun decrypt(name: String, blob: ByteArray): ByteArray
}

/**
 * AES-256-GCM с ключом в Android Keystore (ключ не покидает Keystore и не попадает в резервные
 * копии). Формат: 12 байт IV, затем шифротекст с тегом.
 */
class KeystoreSecretCipher(private val alias: String = "gcaltolotus.secrets") : SecretCipher {
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(name: String, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key()) // IV выбирает Keystore (случайный)
        cipher.updateAAD(name.encodeToByteArray())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Неожиданная длина IV: ${iv.size}" }
        return iv + cipher.doFinal(plain)
    }

    override fun decrypt(name: String, blob: ByteArray): ByteArray {
        if (blob.size <= IV_BYTES) throw GeneralSecurityException("Слишком короткий шифротекст")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        cipher.updateAAD(name.encodeToByteArray())
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/**
 * [SecretStore] на DataStore: значения шифруются [cipher] и лежат в отдельном файле в Base64.
 * Секрет, который не расшифровывается (например, после переноса данных на другое устройство,
 * где ключа Keystore нет), считается отсутствующим и удаляется: нужен повторный вход.
 */
class KeystoreSecretStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) : SecretStore {
    override suspend fun dominoPassword(): String? = read(DOMINO_PASSWORD)

    override suspend fun setDominoPassword(password: String?) = write(DOMINO_PASSWORD, password)

    override suspend fun teamsTokens(): TeamsTokens? = read(TEAMS_TOKENS)?.let(SecretCodec::teamsFromJson)

    override suspend fun setTeamsTokens(tokens: TeamsTokens?) = write(TEAMS_TOKENS, tokens?.let(SecretCodec::teamsToJson))

    override suspend fun googleTokens(): GoogleTokens? = read(GOOGLE_TOKENS)?.let(SecretCodec::googleFromJson)

    override suspend fun setGoogleTokens(tokens: GoogleTokens?) =
        write(GOOGLE_TOKENS, tokens?.let(SecretCodec::googleToJson))

    private suspend fun read(name: String): String? {
        val key = stringPreferencesKey(name)
        val stored = dataStore.data.first()[key] ?: return null
        return try {
            cipher.decrypt(name, Base64.getDecoder().decode(stored)).decodeToString()
        } catch (e: GeneralSecurityException) {
            dataStore.edit { it.remove(key) }
            null
        } catch (e: IllegalArgumentException) {
            dataStore.edit { it.remove(key) }
            null
        }
    }

    private suspend fun write(name: String, value: String?) {
        val key = stringPreferencesKey(name)
        if (value == null) {
            dataStore.edit { it.remove(key) }
            return
        }
        val blob = Base64.getEncoder().encodeToString(cipher.encrypt(name, value.encodeToByteArray()))
        dataStore.edit { it[key] = blob }
    }

    private companion object {
        const val DOMINO_PASSWORD = "domino_password"
        const val TEAMS_TOKENS = "teams_tokens"
        const val GOOGLE_TOKENS = "google_tokens"
    }
}
