package app.gcaltolotus.platform

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.gcaltolotus.app.AppRelease
import app.gcaltolotus.app.UpdateState
import app.gcaltolotus.app.UpdateStore
import kotlin.time.Instant
import kotlinx.coroutines.flow.first

/** [UpdateState] в Jetpack DataStore; негодное значение читается как отсутствующее. */
class DataStoreUpdateStore(private val dataStore: DataStore<Preferences>) : UpdateStore {
    override suspend fun load(): UpdateState {
        val p = dataStore.data.first()
        val version = p[LATEST_VERSION]
        val page = p[LATEST_PAGE]
        return UpdateState(
            lastCheck = p[LAST_CHECK]?.let { runCatching { Instant.parse(it) }.getOrNull() },
            latest = if (version != null && page != null) AppRelease(version, page) else null,
            notified = p[NOTIFIED],
        )
    }

    override suspend fun save(state: UpdateState) {
        dataStore.edit { p ->
            p.set(LAST_CHECK, state.lastCheck?.toString())
            p.set(LATEST_VERSION, state.latest?.version)
            p.set(LATEST_PAGE, state.latest?.pageUrl)
            p.set(NOTIFIED, state.notified)
        }
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.set(key: Preferences.Key<String>, value: String?) {
        if (value == null) remove(key) else this[key] = value
    }

    private companion object {
        val LAST_CHECK = stringPreferencesKey("last_check")
        val LATEST_VERSION = stringPreferencesKey("latest_version")
        val LATEST_PAGE = stringPreferencesKey("latest_page")
        val NOTIFIED = stringPreferencesKey("notified")
    }
}
