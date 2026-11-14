package app.gcaltolotus.ui

import app.gcaltolotus.app.CalendarSource
import app.gcaltolotus.app.InMemoryLinkRepository
import app.gcaltolotus.app.InMemorySecretStore
import app.gcaltolotus.app.InMemorySettingsStore
import app.gcaltolotus.app.OpenedCalendars
import app.gcaltolotus.app.SkipReason
import app.gcaltolotus.app.SkippedSystem
import app.gcaltolotus.app.SyncService
import app.gcaltolotus.app.SyncSettings
import app.gcaltolotus.sync.CalendarEvent
import app.gcaltolotus.sync.InMemoryCalendar
import app.gcaltolotus.sync.System
import app.gcaltolotus.sync.When
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Зависимости-заглушки: всё в памяти, ни одного сетевого вызова. Только для тестов и
 * предпросмотра UI; приложение собирает настоящие зависимости на платформе
 * (androidApp: `AppGraph.appDependencies`).
 */
fun demoDependencies(clock: Clock = Clock.System): AppDependencies {
    val settings = InMemorySettingsStore(SyncSettings())
    val secrets = InMemorySecretStore()
    val now = clock.now()
    fun event(id: String, title: String, inDays: Int) = CalendarEvent(
        title,
        When.of(now + inDays.days),
        When.of(now + inDays.days + 1.hours),
        id = id,
    )
    val calendars = mapOf(
        System.GOOGLE to InMemoryCalendar(System.GOOGLE, listOf(event("g1", "Обзор", 1))),
        System.TEAMS to InMemoryCalendar(System.TEAMS, listOf(event("t1", "Планёрка", 2))),
    )
    val accounts = DemoAccounts()
    val source = object : CalendarSource {
        override suspend fun open(settings: SyncSettings, systems: Set<System>): OpenedCalendars {
            val signedIn = buildSet {
                if (accounts.google.signedIn) add(System.GOOGLE)
                if (accounts.teams.signedIn) add(System.TEAMS)
            }
            val open = System.ORDER.filter { it in systems && it in signedIn }
            val skipped = System.ORDER.filter { it in systems && it !in signedIn }
                .map { SkippedSystem(it, if (it == System.DOMINO && settings.dominoDisabled) SkipReason.DOMINO_DISABLED else SkipReason.NOT_CONFIGURED) }
            return OpenedCalendars(open.associateWith { calendars.getValue(it) }, skipped)
        }
    }
    val sync = SyncService(settings, source, InMemoryLinkRepository(), clock)
    val history = InMemoryRunHistory()
    val runs = SyncRuns(sync, history, clock)
    return AppDependencies(
        settings = settings,
        secrets = secrets,
        syncService = { sync },
        accounts = accounts,
        checks = ConnectionChecks { CheckResult.Ok() },
        googleCalendars = object : GoogleCalendars {
            private val list = mutableListOf(GoogleCalendarInfo("primary", "demo@example.com", primary = true))

            override suspend fun list(): List<GoogleCalendarInfo> = list.toList()

            override suspend fun create(name: String): GoogleCalendarInfo =
                GoogleCalendarInfo("cal${list.size}", name).also { list += it }
        },
        history = history,
        systemControls = InMemorySystemControls(),
        clock = clock,
        syncRuns = { runs },
    )
}

private class DemoAccounts : AccountActions {
    var google = AccountStatus.SignedOut
    var teams = AccountStatus.SignedOut

    override suspend fun googleStatus() = google

    override suspend fun signInGoogle() = AccountStatus(true, "demo@example.com").also { google = it }

    override suspend fun signOutGoogle() {
        google = AccountStatus.SignedOut
    }

    override suspend fun teamsStatus() = teams

    override suspend fun signInTeams() = AccountStatus(true).also { teams = it }

    override suspend fun signOutTeams() {
        teams = AccountStatus.SignedOut
    }
}
