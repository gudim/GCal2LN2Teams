package app.gcaltolotus.app

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.gcaltolotus.db.SyncDatabase
import app.gcaltolotus.sync.Link
import app.gcaltolotus.sync.System
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Оркестрация поверх настоящей SQLite: связи, импорт, смена календаря Google. */
class DbLinkRepositoryTest {
    private val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
        SyncDatabase.Schema.create(it)
    }
    private val links = DbLinkRepository(SyncDatabase(driver))
    private val settings = InMemorySettingsStore()

    @AfterTest
    fun close() = driver.close()

    @Test
    fun saveLoadAndDropAreScopedByGoogleCalendar() {
        links.save("notes@group", Link(System.DOMINO, "d1", System.GOOGLE, "c1"))
        links.save("notes@group", Link(System.DOMINO, "d2", System.TEAMS, "t2"))
        assertEquals(listOf("domino:d2->teams"), links.load(PRIMARY_CALENDAR_ID).map { it.key() })
        assertEquals(2, links.load("notes@group").size)
        links.store("notes@group").drop(Link(System.DOMINO, "d1", System.GOOGLE, "c1"))
        assertEquals(1, links.load("notes@group").size)
    }

    @Test
    fun manualRunWritesLinksToDatabase() = runTest {
        val google = memory(System.GOOGLE, event("g1", "Обзор"))
        val teams = memory(System.TEAMS)
        val svc = SyncService(settings, FakeCalendarSource(google, teams), links, FixedClock)
        val preview = svc.preview()
        svc.applySelected(preview, preview.defaultKeys)
        assertEquals(listOf("google:g1->teams"), links.load(PRIMARY_CALENDAR_ID).map { it.key() })
        // второй запуск ничего не копирует
        assertTrue(svc.preview().actions.isEmpty())
    }

    @Test
    fun switchGoogleCalendarDeletesOldCopies() = runTest {
        links.save(PRIMARY_CALENDAR_ID, Link(System.DOMINO, "d1", System.GOOGLE, "c1"))
        val google = memory(System.GOOGLE, event("c1", "Обзор", source = System.DOMINO))
        val svc = SyncService(settings, FakeCalendarSource(google), links, FixedClock)
        assertEquals(1, svc.googleSwitchPreview("notes@group").oldCopies)
        assertEquals(1, svc.switchGoogleCalendar("notes@group", deleteOld = true).deleted)
        assertTrue(links.load(PRIMARY_CALENDAR_ID).isEmpty())
        assertTrue(google.events.isEmpty())
    }
}
