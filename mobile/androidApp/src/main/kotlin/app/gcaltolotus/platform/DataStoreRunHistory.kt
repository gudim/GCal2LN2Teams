package app.gcaltolotus.platform

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.gcaltolotus.ui.LastRun
import app.gcaltolotus.ui.LastRunCodec
import app.gcaltolotus.ui.RunHistory
import kotlinx.coroutines.flow.first

/** Итог последнего запуска (ручного или фонового) в DataStore, JSON по [LastRunCodec]. */
class DataStoreRunHistory(private val dataStore: DataStore<Preferences>) : RunHistory {
    override suspend fun last(): LastRun? = LastRunCodec.decode(dataStore.data.first()[LAST_RUN])

    override suspend fun record(run: LastRun) {
        dataStore.edit { it[LAST_RUN] = LastRunCodec.encode(run) }
    }

    private companion object {
        val LAST_RUN = stringPreferencesKey("last_run")
    }
}
