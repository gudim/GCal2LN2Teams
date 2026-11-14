"""Двойники, обновления и удаления: правила из docs/adr/0001-conflicts-and-deletes.md."""
from dataclasses import replace
from datetime import datetime, timedelta, timezone

from src.sync.calendar import InMemoryCalendar
from src.sync.event import CalendarEvent, DateRange, System
from src.sync.planner import DIRECTIONS, Link, apply, collect, plan, select, undo_refusal

G, D, T = System.GOOGLE, System.DOMINO, System.TEAMS
UTC = timezone.utc
WINDOW = DateRange(datetime(2026, 1, 1, tzinfo=UTC), datetime(2027, 1, 1, tzinfo=UTC))
AT = datetime(2026, 11, 13, 10, tzinfo=UTC)


def timed(title, start=AT, hours=1, **kw):
    return CalendarEvent(title=title, start=start, end=start + timedelta(hours=hours), **kw)


class Store:
    """Хранилище связей в памяти."""

    def __init__(self, links=()):
        self.rows = {link.key(): link for link in links}

    def save(self, link):
        self.rows[link.key()] = link

    def drop(self, link):
        self.rows.pop(link.key(), None)

    @property
    def links(self):
        return list(self.rows.values())


def preview(calendars, store, direction="all"):
    snapshot, found = collect({c.system: c for c in calendars}, WINDOW, store.links)
    return plan(snapshot, DIRECTIONS[direction], store.links, found)


def run(calendars, store, direction="all", keys=None):
    result = preview(calendars, store, direction)
    chosen = select(result.actions, keys=keys)
    report = apply(chosen, {c.system: c for c in calendars}, store)
    return result, report


def visible(result):
    return [a for a in result.actions if not a.silent]


def kinds(result):
    return sorted((a.kind, a.target) for a in visible(result))


def get(calendar, title):
    return next(e for e in calendar.events if e.title == title)


# --- Двойники ---

def test_same_uid_in_two_systems_is_linked_without_copies():
    google = InMemoryCalendar(G, [timed("Sync", uid="U1", id="g1")])
    domino = InMemoryCalendar(D, [timed("Sync (Lotus)", uid="U1", id="d1")])
    store = Store()

    result, _ = run([google, domino], store)

    assert visible(result) == []
    assert [(l.kind, l.source_id, l.target_id) for l in store.links] == [("twin", "g1", "d1")]
    assert len(google.events) == len(domino.events) == 1
    assert visible(preview([google, domino], store)) == []


def test_same_title_and_start_is_a_twin_candidate_selected_by_default():
    google = InMemoryCalendar(G, [timed("Lunch", id="g1")])
    domino = InMemoryCalendar(D, [timed("lunch", id="d1")])
    store = Store()

    result, _ = run([google, domino], store)

    assert [(a.kind, a.selected) for a in visible(result)] == [("twin", True)]
    assert len(domino.events) == 1
    assert visible(preview([google, domino], store)) == []


def test_declined_twin_is_copied_as_usual_and_not_asked_again():
    google = InMemoryCalendar(G, [timed("Lunch", id="g1")])
    domino = InMemoryCalendar(D, [timed("Lunch", id="d1")])
    store = Store()

    run([google, domino], store, keys=[])

    assert len(google.events) == len(domino.events) == 2
    assert visible(preview([google, domino], store)) == []


def test_ambiguous_twins_pair_first_to_first():
    google = InMemoryCalendar(G, [timed("Lunch", id="g1"), timed("Lunch", id="g2")])
    domino = InMemoryCalendar(D, [timed("Lunch", id="d1")])

    result = preview([google, domino], Store(), "google-domino")

    assert kinds(result) == [("create", D), ("twin", D)]
    assert [a.event.id for a in visible(result) if a.kind == "create"] == ["g2"]


def test_leftover_copy_next_to_twin_is_deleted():
    google = InMemoryCalendar(G, [timed("Sync", uid="U1", id="g1")])
    domino = InMemoryCalendar(D, [timed("Sync", uid="U1", id="d1"), timed("Sync", source=G, id="d2")])
    store = Store([Link(G, "g1", D, "d2")])

    result, _ = run([google, domino], store, "google-domino")

    deletes = [a for a in visible(result) if a.kind == "delete"]
    assert [(a.event.id, a.selected, a.subject) for a in deletes] == [("d2", True, "extra")]
    assert [e.id for e in domino.events] == ["d1"]
    assert [l.kind for l in store.links] == ["twin"]


def test_twins_are_copied_to_third_system_only_once():
    google = InMemoryCalendar(G, [timed("Sync", uid="U1", id="g1")])
    domino = InMemoryCalendar(D, [timed("Sync", uid="U1", id="d1")])
    teams = InMemoryCalendar(T)
    store = Store()

    run([google, domino, teams], store)

    assert [(e.title, e.source) for e in teams.events] == [("Sync", G)]
    assert visible(preview([google, domino, teams], store)) == []


def test_recurring_instances_are_twins_per_instance():
    later = AT + timedelta(days=7)
    google = InMemoryCalendar(G, [
        timed("Weekly", uid="S", original_start=AT, id="g1"),
        timed("Weekly", start=later, uid="S", original_start=later, id="g2"),
    ])
    domino = InMemoryCalendar(D, [
        timed("Weekly", uid="S", original_start=AT, id="d1"),
        timed("Weekly", start=later + timedelta(hours=1), uid="S", original_start=later, id="d2"),
    ])
    store = Store()

    run([google, domino], store)

    assert sorted((l.source_id, l.target_id) for l in store.links) == [("g1", "d1"), ("g2", "d2")]


def test_twins_sync_fields():
    google = InMemoryCalendar(G, [timed("Sync", uid="U1", id="g1")])
    domino = InMemoryCalendar(D, [timed("Sync", uid="U1", id="d1", location="A")])
    store = Store()
    run([google, domino], store)

    domino.events[0] = replace(domino.events[0], location="B")
    run([google, domino], store)

    assert google.events[0].location == "B"


# --- Обновления ---

def synced(*calendars):
    store = Store()
    run(list(calendars), store)
    return store


def test_original_change_updates_copy_and_does_not_echo():
    google = InMemoryCalendar(G, [timed("Review", id="g1")])
    domino = InMemoryCalendar(D)
    store = synced(google, domino)

    google.events[0] = replace(google.events[0], title="Review v2")
    result, report = run([google, domino], store)

    assert [(a.kind, a.target, a.changed, a.selected) for a in visible(result)] == [
        ("update", D, ("title",), True)
    ]
    assert domino.events[0].title == "Review v2"
    assert report.pair(G, D).updated == 1
    assert visible(preview([google, domino], store)) == []


def test_copy_change_goes_to_original_without_attendees():
    google = InMemoryCalendar(G, [timed("Review", id="g1")])
    domino = InMemoryCalendar(D)
    store = synced(google, domino)

    domino.events[0] = replace(domino.events[0], location="Room 5")
    run([google, domino], store)

    assert google.events[0].location == "Room 5"
    assert visible(preview([google, domino], store)) == []


def test_copy_change_into_original_with_attendees_needs_confirmation():
    google = InMemoryCalendar(G, [timed("Review", id="g1", has_attendees=True)])
    domino = InMemoryCalendar(D)
    store = synced(google, domino)

    domino.events[0] = replace(domino.events[0], location="Room 5")
    result, _ = run([google, domino], store)

    [action] = visible(result)
    assert (action.kind, action.target, action.selected) == ("update", G, False)
    assert "участники" in action.reason
    assert google.events[0].location == ""
    assert len(visible(preview([google, domino], store))) == 1  # спрашиваем снова


def test_copy_change_never_goes_into_foreign_original():
    google = InMemoryCalendar(G, [timed("Invite", id="g1", organizer_is_me=False)])
    domino = InMemoryCalendar(D)
    store = synced(google, domino)

    domino.events[0] = replace(domino.events[0], location="Room 5")

    assert visible(preview([google, domino], store)) == []


def test_different_fields_changed_on_both_sides_are_merged():
    google = InMemoryCalendar(G, [timed("Review", id="g1")])
    domino = InMemoryCalendar(D)
    store = synced(google, domino)

    google.events[0] = replace(google.events[0], title="Review v2")
    domino.events[0] = replace(domino.events[0], location="Room 5")
    run([google, domino], store)

    assert (google.events[0].title, google.events[0].location) == ("Review v2", "Room 5")
    assert (domino.events[0].title, domino.events[0].location) == ("Review v2", "Room 5")


def test_same_field_changed_on_both_sides_later_wins_and_tie_goes_to_original():
    google = InMemoryCalendar(G, [timed("Review", id="g1")])
    domino = InMemoryCalendar(D)
    store = synced(google, domino)

    google.events[0] = replace(google.events[0], title="From G", modified=AT)
    domino.events[0] = replace(domino.events[0], title="From D", modified=AT + timedelta(minutes=1))
    run([google, domino], store)
    assert google.events[0].title == domino.events[0].title == "From D"

    google.events[0] = replace(google.events[0], title="G2", modified=None)
    domino.events[0] = replace(domino.events[0], title="D2", modified=AT)
    run([google, domino], store)
    assert google.events[0].title == domino.events[0].title == "G2"


def test_copy_change_propagates_to_other_copies():
    google = InMemoryCalendar(G, [timed("Review", id="g1")])
    domino = InMemoryCalendar(D)
    teams = InMemoryCalendar(T)
    store = synced(google, domino, teams)

    domino.events[0] = replace(domino.events[0], start=AT + timedelta(hours=2), end=AT + timedelta(hours=3))
    run([google, domino, teams], store)

    assert google.events[0].start == teams.events[0].start == AT + timedelta(hours=2)
    assert visible(preview([google, domino, teams], store)) == []


def test_old_link_without_snapshot_gets_baseline_without_update():
    google = InMemoryCalendar(G, [timed("Review", id="g1")])
    domino = InMemoryCalendar(D, [timed("Review", source=G, id="d1", location="X")])
    store = Store([Link(G, "g1", D, "d1")])

    result, _ = run([google, domino], store, "google-domino")

    assert visible(result) == []
    assert store.links[0].source_fields is not None
    google.events[0] = replace(google.events[0], title="Review v2")
    run([google, domino], store, "google-domino")
    assert domino.events[0].title == "Review v2"


def test_event_moved_out_of_window_is_updated_not_deleted():
    google = InMemoryCalendar(G, [timed("Review", id="g1")])
    domino = InMemoryCalendar(D)
    store = synced(google, domino)

    moved = AT + timedelta(days=400)
    google.events[0] = replace(google.events[0], start=moved, end=moved + timedelta(hours=1))
    result, _ = run([google, domino], store)

    assert kinds(result) == [("update", D)]
    assert domino.events[0].start == moved


class FailingUpdate(InMemoryCalendar):
    def update(self, event_id, event):
        raise RuntimeError("boom")


def test_failed_update_is_reported_and_snapshot_not_refreshed():
    google = InMemoryCalendar(G, [timed("Review", id="g1")])
    domino = FailingUpdate(D)
    store = synced(google, domino)

    google.events[0] = replace(google.events[0], title="Review v2")
    _, report = run([google, domino], store)

    assert report.pair(G, D).errors == ["Review v2: boom"]
    assert kinds(preview([google, domino], store)) == [("update", D)]


# --- Удаления ---

def test_original_gone_deletes_copy_silently():
    google = InMemoryCalendar(G, [timed("Review", id="g1")])
    domino = InMemoryCalendar(D)
    store = synced(google, domino)

    google.events.clear()
    result, report = run([google, domino], store)

    assert visible(result) == []
    assert [a.subject for a in result.actions if a.kind == "delete"] == ["copy"]
    assert domino.events == []
    assert report.pair(G, D).deleted == 1
    assert store.links == []


def test_ignored_original_is_not_copied_and_its_copy_is_deleted():
    google = InMemoryCalendar(G, [timed("Review", id="g1"), timed("Declined", id="g2", ignored=True)])
    domino = InMemoryCalendar(D)
    store = synced(google, domino)
    assert [e.title for e in domino.events] == ["Review"]

    google.events[0] = replace(google.events[0], ignored=True)
    run([google, domino], store)

    assert domino.events == []


def test_mass_delete_guard_unselects_deletions():
    google = InMemoryCalendar(G, [timed(f"E{i}", AT + timedelta(days=i), id=f"g{i}") for i in range(6)])
    domino = InMemoryCalendar(D)
    store = synced(google, domino)

    google.events.clear()
    result, _ = run([google, domino], store)

    deletes = [a for a in result.actions if a.kind == "delete"]
    assert len(deletes) == 6 and not any(a.selected or a.silent for a in deletes)
    assert result.warnings
    assert len(domino.events) == 6


def test_copy_gone_asks_to_delete_original():
    google = InMemoryCalendar(G, [timed("Review", id="g1")])
    domino = InMemoryCalendar(D)
    store = synced(google, domino)

    domino.events.clear()
    result = preview([google, domino], store)
    [ask] = visible(result)
    assert (ask.kind, ask.target, ask.selected, ask.subject) == ("delete", G, False, "original")

    run([google, domino], store, keys=[ask.key])
    assert google.events == [] and store.links == []


def test_declined_original_delete_is_remembered_and_copy_not_recreated():
    google = InMemoryCalendar(G, [timed("Review", id="g1")])
    domino = InMemoryCalendar(D)
    store = synced(google, domino)

    domino.events.clear()
    run([google, domino], store, keys=[])

    assert store.links[0].kept
    assert visible(preview([google, domino], store)) == []
    assert domino.events == []


def test_copy_of_foreign_original_gone_is_kept_silently():
    google = InMemoryCalendar(G, [timed("Invite", id="g1", organizer_is_me=False)])
    domino = InMemoryCalendar(D)
    store = synced(google, domino)

    domino.events.clear()
    result, _ = run([google, domino], store)

    assert visible(result) == []
    assert store.links[0].kept
    assert visible(preview([google, domino], store)) == [] and domino.events == []


def test_twin_gone_asks_to_delete_other_twin_and_foreign_twin_never():
    google = InMemoryCalendar(G, [timed("Sync", uid="U1", id="g1"), timed("Inv", uid="U2", id="g2", organizer_is_me=False)])
    domino = InMemoryCalendar(D, [timed("Sync", uid="U1", id="d1"), timed("Inv", uid="U2", id="d2")])
    store = synced(google, domino)

    domino.events.clear()
    result, _ = run([google, domino], store, keys=[])

    assert [(a.kind, a.target, a.event.id, a.selected, a.subject) for a in visible(result)] == [
        ("delete", G, "g1", False, "twin")
    ]
    assert all(l.kept for l in store.links)
    assert visible(preview([google, domino], store)) == [] and domino.events == []


# --- Выбор и применение ---

def test_select_without_keys_takes_defaults_and_with_keys_exactly_those():
    google = InMemoryCalendar(G, [timed("Review", id="g1", has_attendees=True), timed("Other", id="g2")])
    domino = InMemoryCalendar(D)
    store = synced(google, domino)
    domino.events[0] = replace(domino.events[0], location="X")
    domino.events[1] = replace(domino.events[1], location="Y")
    actions = preview([google, domino], store).actions

    default = [a.event.id for a in select(actions) if not a.silent]
    assert default == ["g2"]
    attendees = next(a for a in actions if a.event.id == "g1")
    assert [a.event.id for a in select(actions, keys=[attendees.key]) if not a.silent] == ["g1"]


class FailingCreate(InMemoryCalendar):
    def create(self, event):
        if event.title == "bad":
            raise RuntimeError("boom")
        return super().create(event)


def test_link_is_saved_right_after_each_create():
    google = InMemoryCalendar(G, [timed("good", id="g1"), timed("bad", id="g2")])
    domino = FailingCreate(D)
    store = Store()

    _, report = run([google, domino], store, "google-domino")

    assert [l.source_id for l in store.links] == ["g1"]
    assert report.pair(G, D).created == 1 and report.pair(G, D).errors == ["bad: boom"]


# --- Двойники по времени (docs/adr/0003-time-twins-and-refusals.md) ---

LOTUS_TITLE = "AI Agent Platform: status update (incl. demo of progress)"
TEAMS_TITLE = "AI Agent Platform: status update (дублирую в тимсе)"


def run_routes(calendars, store, direction="all", keys=None):
    result = preview(calendars, store, direction)
    chosen = select(result.actions, keys=keys, refused=result.refused)
    report = apply(chosen, {c.system: c for c in calendars}, store)
    return result, report


def test_same_start_and_end_with_other_title_is_an_unticked_twin_question():
    domino = InMemoryCalendar(D, [timed(LOTUS_TITLE, id="d1")])
    teams = InMemoryCalendar(T, [timed(TEAMS_TITLE, id="t1", organizer_is_me=False)])
    store = Store()

    result, _ = run([domino, teams], store, "domino-teams")

    twins = [a for a in visible(result) if a.kind == "twin"]
    assert [(a.selected, a.link.key()) for a in twins] == [(False, "twin:domino:d1->teams:t1")]
    assert "начало и конец" in twins[0].reason
    # Пока вопрос открыт, копии между ними — ответ «нет» (fallback), фоном не создаются
    assert [a for a in visible(result) if a.kind == "create"] == []
    assert len(domino.events) == len(teams.events) == 1
    assert store.links == []
    assert [a.kind for a in visible(preview([domino, teams], store, "domino-teams"))] == ["twin"]


def test_time_twin_needs_equal_end_timed_events_and_originals():
    domino = InMemoryCalendar(D, [
        timed("Lotus long", id="d1", hours=2),
        CalendarEvent(title="Праздник", start=AT.date(), end=AT.date() + timedelta(days=1), id="d2"),
        timed("Копия", AT + timedelta(hours=5), id="d3", source=G),
    ])
    teams = InMemoryCalendar(T, [
        timed("Teams short", id="t1"),
        CalendarEvent(title="Holiday", start=AT.date(), end=AT.date() + timedelta(days=1), id="t2"),
        timed("Другое", AT + timedelta(hours=5), id="t3"),
    ])

    result = preview([domino, teams], Store(), "domino-teams")

    assert [a for a in result.actions if a.kind == "twin"] == []


def test_time_twin_confirmed_deletes_redundant_copies_everywhere():
    lotus = timed(LOTUS_TITLE, id="d1")
    foreign = timed(TEAMS_TITLE, id="t1", organizer_is_me=False)
    google = InMemoryCalendar(G, [replace(lotus, id="g-ln", source=D),
                                  replace(foreign, id="g-mt", source=T, organizer_is_me=True)])
    domino = InMemoryCalendar(D, [lotus, replace(foreign, id="d-mt", source=T, organizer_is_me=True)])
    teams = InMemoryCalendar(T, [foreign, replace(lotus, id="t-ln", source=D)])
    store = Store([
        Link(D, "d1", G, "g-ln", source_fields=lotus.fields(), target_fields=lotus.fields()),
        Link(D, "d1", T, "t-ln", source_fields=lotus.fields(), target_fields=lotus.fields()),
        Link(T, "t1", D, "d-mt", source_fields=foreign.fields(), target_fields=foreign.fields()),
        Link(T, "t1", G, "g-mt", source_fields=foreign.fields(), target_fields=foreign.fields()),
    ])
    calendars = [google, domino, teams]
    question = next(a for a in preview(calendars, store).actions if a.kind == "twin")
    assert not question.selected

    # Фон (без ключей): ничего не удаляется
    run_routes(calendars, store)
    assert len(google.events) == len(domino.events) == len(teams.events) == 2

    # Пользователь отметил вопрос
    defaults = [a.key for a in preview(calendars, store).actions if a.selected]
    result, _ = run_routes(calendars, store, keys=[question.key] + defaults)

    extra = [(a.target, a.event.id, a.depends_on) for a in result.actions
             if a.kind == "delete" and a.subject == "extra"]
    assert sorted(extra) == sorted([
        (G, "g-mt", (question.key,)), (D, "d-mt", (question.key,)), (T, "t-ln", (question.key,)),
    ])
    assert [e.id for e in google.events] == ["g-ln"]
    assert [e.id for e in domino.events] == ["d1"]
    assert [e.id for e in teams.events] == ["t1"]
    assert sorted(l.key() for l in store.links) == ["domino:d1->google", "twin:domino:d1->teams:t1"]
    assert visible(preview(calendars, store)) == []


def test_time_twin_declined_is_remembered_as_distinct():
    domino = InMemoryCalendar(D, [timed(LOTUS_TITLE, id="d1")])
    teams = InMemoryCalendar(T, [timed(TEAMS_TITLE, id="t1")])
    store = Store()

    run_routes([domino, teams], store, "domino-teams", keys=[])

    assert len(domino.events) == len(teams.events) == 2
    assert "distinct:domino:d1->teams:t1" in [l.key() for l in store.links]
    assert visible(preview([domino, teams], store, "domino-teams")) == []


def test_title_twin_wins_over_time_twin():
    google = InMemoryCalendar(G, [timed("Обед", id="g1")])
    domino = InMemoryCalendar(D, [timed("Созвон", id="d1"), timed("обед", id="d2")])

    result = preview([google, domino], Store(), "google-domino")

    twins = [(a.link.target_id, a.selected) for a in result.actions if a.kind == "twin"]
    assert twins == [("d2", True)]


# --- Отказ от копирования ---

def test_unticked_create_is_remembered_as_refusal():
    google = InMemoryCalendar(G, [timed("Личное", id="g1"), timed("Рабочее", AT + timedelta(hours=2), id="g2")])
    domino = InMemoryCalendar(D)
    store = Store()
    keep = next(a.key for a in preview([google, domino], store, "google-domino").actions
                if a.kind == "create" and a.event.id == "g2")

    run_routes([google, domino], store, "google-domino", keys=[keep])

    assert [e.title for e in domino.events] == ["Рабочее"]
    refusal = Link(G, "g1", D, None, kept=True)
    assert refusal in store.links
    again = preview([google, domino], store, "google-domino")
    assert visible(again) == []
    assert [(a.key, a.event.id, a.link) for a in again.refused] == [("google:g1>domino", "g1", refusal)]
    # Ни ручной, ни фоновый запуск копию не создают
    run_routes([google, domino], store, "google-domino")
    run_routes([google, domino], store, "google-domino", keys=[])
    assert [e.title for e in domino.events] == ["Рабочее"]


def test_background_select_never_stores_refusals():
    google = InMemoryCalendar(G, [timed("Встреча", id="g1", has_attendees=True)])
    domino = InMemoryCalendar(D)
    store = Store()
    result = preview([google, domino], store, "google-domino")

    chosen = select(result.actions, routes={(D, G)})

    assert [a for a in chosen if a.kind == "link"] == []


def test_refusal_undo_brings_create_back():
    google = InMemoryCalendar(G, [timed("Личное", id="g1")])
    domino = InMemoryCalendar(D)
    store = Store([Link(G, "g1", D, None, kept=True)])
    refused = preview([google, domino], store, "google-domino").refused

    undo_refusal(refused[0].link, store)

    assert store.links == []
    assert [a.key for a in visible(preview([google, domino], store, "google-domino"))] == [
        "google:g1>domino"]


def test_reticked_refusal_creates_copy_and_replaces_refusal():
    google = InMemoryCalendar(G, [timed("Личное", id="g1")])
    domino = InMemoryCalendar(D)
    store = Store([Link(G, "g1", D, None, kept=True)])

    result, report = run_routes([google, domino], store, "google-domino", keys=["google:g1>domino"])

    assert report.pair(G, D).created == 1
    assert [(l.key(), l.kept, l.target_id) for l in store.links] == [
        ("google:g1->domino", False, domino.events[0].id)]
    again = preview([google, domino], store, "google-domino")
    assert visible(again) == [] and again.refused == []


def test_refusal_is_listed_only_within_pair_and_window():
    google = InMemoryCalendar(G, [timed("Личное", id="g1")])
    domino = InMemoryCalendar(D)
    teams = InMemoryCalendar(T)
    store = Store([Link(G, "g1", D, None, kept=True), Link(G, "gone", D, None, kept=True)])

    result = preview([google, domino, teams], store)

    assert [a.key for a in result.refused] == ["google:g1>domino"]
    assert [a.key for a in visible(result)] == ["google:g1>teams"]


# --- Недоступная система: связанные с ней группы заморожены ---

def outage(calendars, store, unavailable=frozenset({D}), direction="all"):
    """Запуск, в котором системы unavailable не прочитаны из-за сбоя."""
    snapshot, found = collect({c.system: c for c in calendars}, WINDOW, store.links)
    return plan(snapshot, DIRECTIONS[direction], store.links, found, unavailable=unavailable)


def three_way_twins():
    """Lotus и Teams — двойники, в Google одна копия группы — от Lotus."""
    lotus = timed("Встреча", id="d1")
    meeting = timed("Встреча (Teams)", id="t1")
    ln_copy = timed("Встреча", id="g-ln", source=D)
    store = Store([
        Link(D, "d1", T, "t1", "twin", lotus.fields(), meeting.fields()),
        Link(D, "d1", G, "g-ln", source_fields=lotus.fields(), target_fields=ln_copy.fields()),
    ])
    return InMemoryCalendar(G, [ln_copy]), InMemoryCalendar(T, [meeting]), store


def test_twin_of_unavailable_system_is_not_copied_but_unrelated_event_is():
    google, teams, store = three_way_twins()
    teams.events.append(timed("Ретро", AT + timedelta(hours=3), id="t2"))

    result = outage([google, teams], store)

    assert [(a.kind, a.event.id) for a in result.actions] == [("create", "t2")]


def test_without_freeze_twin_of_skipped_system_is_copied():
    """Старое поведение (баг): без unavailable двойник Lotus копируется в Google."""
    google, teams, store = three_way_twins()

    result = outage([google, teams], store, unavailable=frozenset())

    assert [(a.kind, a.event.id) for a in result.actions] == [("create", "t1")]


def test_copy_of_unavailable_original_is_neither_deleted_nor_questioned():
    lotus = timed("Планёрка", id="d1")
    copy = timed("Планёрка", id="t-ln", source=D)
    teams = InMemoryCalendar(T, [replace(copy, title="Планёрка 2")])
    google = InMemoryCalendar(G)
    store = Store([Link(D, "d1", T, "t-ln", source_fields=lotus.fields(),
                        target_fields=copy.fields())])

    assert outage([google, teams], store).actions == []


def test_event_linked_only_to_available_systems_updates_normally():
    google, teams, store = three_way_twins()
    review = timed("Обзор", AT + timedelta(days=1), id="t3")
    review_copy = timed("Обзор", review.start, id="g-mt", source=T)
    google.events.append(review_copy)
    teams.events.append(replace(review, title="Обзор v2"))
    store.save(Link(T, "t3", G, "g-mt", source_fields=review.fields(),
                    target_fields=review_copy.fields()))

    result = outage([google, teams], store)

    assert [(a.kind, a.target, a.event.title) for a in visible(result)] == [
        ("update", G, "Обзор v2")]


def test_candidate_twin_of_frozen_original_waits():
    """Новый оригинал Teams совпал с замороженным оригиналом Google: ни вопроса, ни копии."""
    google = InMemoryCalendar(G, [timed("Демо", id="g1")])
    teams = InMemoryCalendar(T, [timed("Демо", id="t1")])
    domino_copy = timed("Демо", id="d-gc", source=G)
    store = Store([Link(G, "g1", D, "d-gc", source_fields=google.events[0].fields(),
                        target_fields=domino_copy.fields())])

    assert outage([google, teams], store).actions == []


def test_frozen_links_do_not_count_for_mass_delete_guard():
    """Исчезли 7 оригиналов Google, но 6 из них связаны с Lotus: они заморожены."""
    originals = [timed(f"E{i}", AT + timedelta(days=i), id=f"g{i}") for i in range(7)]
    google = InMemoryCalendar(G)
    teams = InMemoryCalendar(T)
    links = []
    for e in originals:
        copy = replace(e, id=f"t-{e.id}", source=G)
        teams.events.append(copy)
        links.append(Link(G, e.id, T, copy.id, source_fields=e.fields(),
                          target_fields=copy.fields()))
    for e in originals[:6]:
        links.append(Link(G, e.id, D, f"d-{e.id}", source_fields=e.fields(),
                          target_fields=e.fields()))
    store = Store(links)

    unfrozen = outage([google, teams], store, unavailable=frozenset())
    assert unfrozen.warnings and len([a for a in unfrozen.actions if a.kind == "delete"]) == 7

    result = outage([google, teams], store)

    [delete] = [a for a in result.actions if a.kind == "delete"]
    assert (delete.event.id, delete.silent, delete.selected) == ("t-g6", True, True)
    assert result.warnings == []


def test_direction_excluding_system_is_not_an_outage():
    """Lotus выключен направлением, а не недоступен: двойник Lotus копируется как раньше."""
    google, teams, store = three_way_twins()
    domino = InMemoryCalendar(D, [timed("Встреча", id="d1")])

    result = outage([google, domino, teams], store, unavailable=frozenset(),
                    direction="google-teams")

    assert [(a.kind, a.event.id) for a in result.actions] == [("create", "t1")]
