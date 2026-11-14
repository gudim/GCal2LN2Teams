from datetime import date, datetime, timedelta, timezone

import pytest

from src.sync.calendar import InMemoryCalendar
from src.sync.event import CalendarEvent, DateRange, System, split_marker
from src.sync.planner import DIRECTIONS, Link, apply, copy_key, plan, select

G, D, T = System.GOOGLE, System.DOMINO, System.TEAMS
MSK = timezone(timedelta(hours=3))
UTC = timezone.utc
WINDOW = DateRange(datetime(2026, 1, 1, tzinfo=UTC), datetime(2027, 1, 1, tzinfo=UTC))


def timed(title, start, hours=1, **kw):
    return CalendarEvent(title=title, start=start, end=start + timedelta(hours=hours), **kw)


def snapshot(*calendars):
    return {c.system: c.list(WINDOW) for c in calendars}


class Store:
    def __init__(self):
        self.links = []

    def save(self, link):
        self.links.append(link)

    def drop(self, link):
        self.links.remove(link)


def copies(*args):
    """Видимые действия плана (без служебных связей)."""
    return [a for a in plan(*args).actions if not a.silent]


def sync(direction, *calendars, store=None):
    actions = select(plan(snapshot(*calendars), DIRECTIONS[direction]).actions)
    return apply(actions, {c.system: c for c in calendars}, store if store is not None else Store())


def test_native_event_is_copied_with_origin():
    google = InMemoryCalendar(G, [timed("Standup", datetime(2026, 11, 13, 10, tzinfo=UTC))])
    domino = InMemoryCalendar(D)

    planned = copies(snapshot(google, domino), DIRECTIONS["google-domino"])

    assert [(c.target, c.event.title, c.event.source) for c in planned] == [(D, "Standup", G)]
    assert planned[0].event.marked_title() == "[GC] Standup"


def test_late_evening_copy_in_other_timezone_is_recognised():
    # 01:00 по Москве 14-го == 22:00 UTC 13-го: разные даты, один момент
    google = InMemoryCalendar(G, [timed("Release", datetime(2026, 11, 14, 1, tzinfo=MSK))])
    domino = InMemoryCalendar(D, [timed("Release", datetime(2026, 11, 13, 22, tzinfo=UTC), source=G)])

    assert copies(snapshot(google, domino), DIRECTIONS["google-domino"]) == []


def test_all_day_event_stays_all_day_and_is_recognised_after_copy():
    holiday = CalendarEvent(title="Holiday", start=date(2026, 11, 13), end=date(2026, 11, 14))
    google = InMemoryCalendar(G, [holiday])
    domino = InMemoryCalendar(D)

    report = sync("google-domino", google, domino)

    assert report.pair(G, D).created == 1
    assert domino.events[0].all_day
    assert (domino.events[0].start, domino.events[0].end) == (date(2026, 11, 13), date(2026, 11, 14))
    assert copies(snapshot(google, domino), DIRECTIONS["google-domino"]) == []


def test_linked_copy_is_not_recreated_even_if_target_copy_was_moved():
    google = InMemoryCalendar(G, [timed("Review", datetime(2026, 11, 13, 10, tzinfo=UTC), id="g1")])
    domino = InMemoryCalendar(D, [timed("Review", datetime(2026, 11, 13, 15, tzinfo=UTC), source=G, id="d1")])

    links = [Link(G, "g1", D, "d1")]

    assert copies(snapshot(google, domino), DIRECTIONS["google-domino"], links) == []


def test_copies_are_not_copied_further():
    domino = InMemoryCalendar(D, [timed("From Google", datetime(2026, 11, 13, 10, tzinfo=UTC), source=G)])
    teams = InMemoryCalendar(T)

    assert copies(snapshot(domino, teams), DIRECTIONS["domino-teams"]) == []


def test_direction_limits_pairs():
    start = datetime(2026, 11, 13, 10, tzinfo=UTC)
    # Разное время: одинаковые начало и конец дали бы вопрос о двойниках по времени
    google = InMemoryCalendar(G, [timed("G", start)])
    domino = InMemoryCalendar(D, [timed("D", start + timedelta(hours=2))])
    teams = InMemoryCalendar(T, [timed("T", start + timedelta(hours=4))])

    planned = copies(snapshot(google, domino, teams), DIRECTIONS["google-teams"])

    assert {(c.event.source, c.target) for c in planned} == {(G, T), (T, G)}


def test_three_way_sync_is_idempotent_with_many_events():
    base = datetime(2026, 3, 1, 21, 30, tzinfo=MSK)  # поздний вечер: дата по UTC другая
    google = InMemoryCalendar(G, [timed(f"G{i}", base + timedelta(days=i)) for i in range(150)])
    domino = InMemoryCalendar(D, [timed(f"D{i}", base + timedelta(days=i, hours=2))
                                  for i in range(120)])
    teams = InMemoryCalendar(T)

    store = Store()
    first = sync("all", google, domino, teams, store=store)

    assert first.pair(G, D).created == 150
    assert first.pair(D, G).created == 120
    assert first.pair(G, T).created == 150
    assert first.pair(D, T).created == 120
    assert len(store.links) == 540
    assert copies(snapshot(google, domino, teams), DIRECTIONS["all"], store.links) == []
    assert copies(snapshot(google, domino, teams), DIRECTIONS["all"]) == []  # и без links


class FailingCalendar(InMemoryCalendar):
    def create(self, event):
        if event.title == "bad":
            raise RuntimeError("boom")
        return super().create(event)


def test_apply_reports_errors_and_continues():
    start = datetime(2026, 11, 13, 10, tzinfo=UTC)
    google = InMemoryCalendar(G, [timed("bad", start), timed("good", start)])
    domino = FailingCalendar(D)

    report = sync("google-domino", google, domino)

    assert report.pair(G, D).created == 1
    assert report.pair(G, D).errors == ["bad: boom"]
    assert [e.title for e in domino.events] == ["good"]


def test_split_marker_ignores_own_marker():
    assert split_marker(" [GC]  Team  sync ", own=D) == ("Team sync", G)
    assert split_marker("[LN] Mine", own=D) == ("[LN] Mine", None)


def test_naive_datetime_is_rejected():
    with pytest.raises(ValueError):
        CalendarEvent(title="x", start=datetime(2026, 1, 1), end=datetime(2026, 1, 1, 1))


def test_select_keeps_only_requested_routes():
    google = InMemoryCalendar(G, [timed("Standup", datetime(2026, 11, 13, 10, tzinfo=UTC), id="g1")])
    domino = InMemoryCalendar(D, [timed("Review", datetime(2026, 11, 13, 12, tzinfo=UTC), id="d1")])
    planned = copies(snapshot(google, domino), DIRECTIONS["google-domino"])

    chosen = select(planned, routes={(D, G)})

    assert [(c.event.source, c.target, c.event.title) for c in chosen] == [(D, G, "Review")]


def test_select_keeps_only_confirmed_keys_and_keys_are_stable():
    google = InMemoryCalendar(G, [
        timed("Standup", datetime(2026, 11, 13, 10, tzinfo=UTC), id="g1"),
        timed("Lunch", datetime(2026, 11, 13, 13, tzinfo=UTC), id="g2"),
    ])
    domino = InMemoryCalendar(D)
    first = copies(snapshot(google, domino), DIRECTIONS["google-domino"])
    again = copies(snapshot(google, domino), DIRECTIONS["google-domino"])

    assert [copy_key(c) for c in first] == [copy_key(c) for c in again]
    chosen = select(again, keys={copy_key(first[1])})
    assert [c.event.title for c in chosen if not c.silent] == ["Lunch"]
    # Снятая галочка у Standup — отказ от копирования (связь kept без копии)
    assert [c.link for c in chosen if c.silent] == [Link(G, "g1", D, None, kept=True)]


def test_copy_key_without_id_uses_title_and_start():
    raw = {G: [timed("Standup", datetime(2026, 11, 13, 10, tzinfo=UTC))], D: []}
    planned = copies(raw, DIRECTIONS["google-domino"])

    assert copy_key(planned[0]) == "google:Standup@2026-11-13T10:00:00+00:00>domino"
