package app.gcaltolotus.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.gcaltolotus.sync.CalendarEvent
import app.gcaltolotus.sync.InMemoryCalendar
import app.gcaltolotus.sync.Link
import app.gcaltolotus.sync.LinkKind
import app.gcaltolotus.sync.System
import app.gcaltolotus.sync.When
import app.gcaltolotus.sync.Window
import app.gcaltolotus.sync.apply
import app.gcaltolotus.sync.collect
import app.gcaltolotus.sync.plan
import app.gcaltolotus.sync.select
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class DbLinkStoreTest {
    private val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
        SyncDatabase.Schema.create(it)
    }
    private val db = SyncDatabase(driver)

    @AfterTest
    fun close() = driver.close()

    private val event = CalendarEvent("Обзор", at("2026-11-13T10:00:00Z"), at("2026-11-13T11:00:00Z"))
    private val copy = Link(System.GOOGLE, "g1", System.DOMINO, "d1", sourceFields = event.fields(), targetFields = event.fields())
    private val twin = Link(System.DOMINO, "d2", System.TEAMS, "t2", LinkKind.TWIN, event.fields(), event.fields())
    private val distinct = Link(System.GOOGLE, "g3", System.TEAMS, "t3", LinkKind.DISTINCT)
    private val kept = Link(System.TEAMS, "t4", System.GOOGLE, "g4", kept = true)
    private val oldWithoutTarget = Link(System.DOMINO, "d5", System.GOOGLE, null)
    private val refusal = Link.refusal(System.TEAMS, "t6", System.DOMINO)

    @Test
    fun savedLinksLoadBackSortedByKeyWithSnapshotsAndFlags() {
        val store = DbLinkStore(db)
        listOf(copy, twin, distinct, kept, oldWithoutTarget, refusal).forEach(store::save)

        val loaded = store.load()

        assertEquals(listOf(copy, twin, distinct, kept, oldWithoutTarget, refusal).sortedBy { it.key() }, loaded)
        assertTrue(loaded.single { it.isRefusal } == refusal)
        assertEquals(event.fields().keys.toList(), loaded.first { it == copy }.sourceFields!!.keys.toList())
    }

    @Test
    fun saveReplacesLinkWithSameKeyAndDropRemovesIt() {
        val store = DbLinkStore(db)
        store.save(copy)
        val changed = copy.copy(targetId = "d9", targetFields = null, kept = true)
        store.save(changed)
        assertEquals(listOf(changed), store.load())

        store.drop(copy) // ключ тот же
        store.drop(copy) // нет связи — не ошибка
        assertEquals(emptyList(), store.load())
    }

    @Test
    fun linksWithGoogleBelongToOneGoogleCalendar() {
        val primary = DbLinkStore(db)
        val work = DbLinkStore(db, googleCalendar = "work")
        primary.save(copy)
        work.save(kept)
        work.save(twin) // без Google: видна при любом календаре

        assertEquals(listOf(copy, twin), primary.load())
        assertEquals(listOf(kept, twin), work.load()) // по ключу: "teams:..." < "twin:..."
        assertEquals(listOf(kept), work.googleCopies())
        assertEquals(emptyList(), primary.googleCopies())
    }

    @Test
    fun applySavesEachLinkInTheDatabase() = runTest {
        val window = Window(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"))
        val calendars = mapOf(
            System.GOOGLE to InMemoryCalendar(System.GOOGLE, listOf(event.copy(id = "g1"))),
            System.DOMINO to InMemoryCalendar(System.DOMINO),
        )
        val store = DbLinkStore(db)
        val pairs = listOf(setOf(System.GOOGLE, System.DOMINO))

        val first = collect(calendars, window, store.load())
        apply(select(plan(first.snapshot, pairs, store.load(), first.found).actions), calendars, store)

        val links = store.load()
        assertEquals(1, links.size)
        assertEquals("google:g1->domino", links.single().key())
        assertEquals(event.fields(), links.single().targetFields)
        val again = collect(calendars, window, links)
        assertTrue(plan(again.snapshot, pairs, links, again.found).actions.isEmpty(), "нет эха")
    }

    private fun at(text: String): When = When.parse(text)
}
