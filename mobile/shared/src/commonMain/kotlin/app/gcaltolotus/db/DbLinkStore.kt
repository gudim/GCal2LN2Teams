package app.gcaltolotus.db

import app.gcaltolotus.sync.Link
import app.gcaltolotus.sync.LinkKind
import app.gcaltolotus.sync.LinkStore
import app.gcaltolotus.sync.SyncJson
import app.gcaltolotus.sync.System
import kotlinx.serialization.json.Json

/** Основной календарь Google (`PRIMARY` в `src/sync/links.py`). */
const val PRIMARY_CALENDAR: String = "primary"

/**
 * Хранилище связей в SQLite (таблица `sync_link`). Порт `DbLinkStore`/`load_links` из
 * `src/sync/links.py`.
 *
 * Связь с Google относится к одному календарю Google — [googleCalendar] этого хранилища;
 * связи без Google (Domino ↔ Teams) видны при любом календаре. Каждое [save]/[drop] пишется
 * сразу, чтобы сбой посередине синхронизации не привёл к дублям.
 */
class DbLinkStore(
    private val db: SyncDatabase,
    val googleCalendar: String = PRIMARY_CALENDAR,
) : LinkStore {
    /** Связи, видимые при синхронизации с календарём [googleCalendar], по ключу. */
    fun load(): List<Link> = db.storedLinks()
        .filter { (link, calendar) -> !link.hasGoogle() || (calendar ?: PRIMARY_CALENDAR) == googleCalendar }
        .map { it.link }

    /** Копии синхронизатора, лежащие в календаре Google [googleCalendar]. */
    fun googleCopies(): List<Link> = load().filter {
        it.kind == LinkKind.COPY && it.targetSystem == System.GOOGLE && !it.targetId.isNullOrEmpty()
    }

    /** Сохранить связь (заменяет связь с тем же ключом). */
    override fun save(link: Link) {
        db.upsertLink(link, googleCalendar.takeIf { link.hasGoogle() })
    }

    /** Сохранить несколько связей одной транзакцией. */
    fun saveAll(links: Iterable<Link>) {
        db.transaction { links.forEach(::save) }
    }

    override fun drop(link: Link) {
        db.syncLinkQueries.deleteByKey(link.key())
    }
}

/** Связь из таблицы и календарь Google, к которому она относится (`null` — связь без Google). */
private data class StoredLink(val link: Link, val googleCalendar: String?)

private fun Link.hasGoogle(): Boolean = sourceSystem == System.GOOGLE || targetSystem == System.GOOGLE

private fun SyncDatabase.upsertLink(link: Link, googleCalendar: String?) {
    syncLinkQueries.upsert(
        link_key = link.key(),
        kind = link.kind.value,
        source_system = link.sourceSystem.value,
        source_id = link.sourceId,
        target_system = link.targetSystem.value,
        target_id = link.targetId,
        source_fields = link.sourceFields?.let { SyncJson.fieldsToJson(it).toString() },
        target_fields = link.targetFields?.let { SyncJson.fieldsToJson(it).toString() },
        kept = if (link.kept) 1L else 0L,
        google_calendar = googleCalendar,
    )
}

/** Строки таблицы; строки, которые не разбираются (чужая система или вид), пропускаются. */
private fun SyncDatabase.storedLinks(): List<StoredLink> =
    syncLinkQueries.selectAll().executeAsList().mapNotNull { row ->
        val link = try {
            Link(
                sourceSystem = System.fromValue(row.source_system),
                sourceId = row.source_id.takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                targetSystem = System.fromValue(row.target_system),
                targetId = row.target_id,
                kind = LinkKind.fromValue(row.kind),
                sourceFields = row.source_fields?.let { SyncJson.fieldsFromJson(Json.parseToJsonElement(it)) },
                targetFields = row.target_fields?.let { SyncJson.fieldsFromJson(Json.parseToJsonElement(it)) },
                kept = row.kept != 0L,
            )
        } catch (e: IllegalArgumentException) {
            return@mapNotNull null
        }
        StoredLink(link, row.google_calendar)
    }
