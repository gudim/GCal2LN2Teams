package app.gcaltolotus.app

import app.gcaltolotus.db.DbLinkStore
import app.gcaltolotus.db.SyncDatabase
import app.gcaltolotus.sync.Link
import app.gcaltolotus.sync.LinkKind
import app.gcaltolotus.sync.LinkStore
import app.gcaltolotus.sync.System

/**
 * Связи для оркестрации. Связь с Google относится к одному календарю Google; связи без Google
 * видны при любом календаре (как `load_links(db, google_calendar)` в Python).
 */
interface LinkRepository {
    /** Связи, видимые при синхронизации с календарём Google [googleCalendar], по ключу. */
    fun load(googleCalendar: String): List<Link>

    /** Куда `apply` пишет связи при календаре Google [googleCalendar]. */
    fun store(googleCalendar: String): LinkStore

    fun save(googleCalendar: String, link: Link) = store(googleCalendar).save(link)
}

/** Копии синхронизатора в календаре Google [googleCalendar] (`google_copies` в Python). */
fun LinkRepository.googleCopies(googleCalendar: String): List<Link> = load(googleCalendar).filter {
    it.kind == LinkKind.COPY && it.targetSystem == System.GOOGLE && !it.targetId.isNullOrEmpty()
}

/** Связи в SQLite ([DbLinkStore]). */
class DbLinkRepository(private val db: SyncDatabase) : LinkRepository {
    override fun load(googleCalendar: String): List<Link> = DbLinkStore(db, googleCalendar).load()

    override fun store(googleCalendar: String): LinkStore = DbLinkStore(db, googleCalendar)

}

/** Связи в памяти с той же видимостью по календарю Google, что у [DbLinkRepository]: для тестов. */
class InMemoryLinkRepository : LinkRepository {
    /** Ключ связи → (связь, календарь Google или `null` у связей без Google). */
    private val rows = LinkedHashMap<String, Pair<Link, String?>>()

    private fun Link.hasGoogle() = sourceSystem == System.GOOGLE || targetSystem == System.GOOGLE

    override fun load(googleCalendar: String): List<Link> = rows.entries.sortedBy { it.key }.map { it.value }
        .filter { (link, calendar) -> !link.hasGoogle() || (calendar ?: PRIMARY_CALENDAR_ID) == googleCalendar }
        .map { it.first }

    override fun store(googleCalendar: String): LinkStore = object : LinkStore {
        override fun save(link: Link) {
            rows[link.key()] = link to googleCalendar.takeIf { link.hasGoogle() }
        }

        override fun drop(link: Link) {
            rows.remove(link.key())
        }
    }
}
