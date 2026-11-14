import copy
import json
from datetime import date, datetime, timedelta, timezone

import pytest
import requests

from src.adapters import domino_rest
from src.adapters.domino_rest import DominoRestAdapter, event_from_domino, event_to_domino
from src.sync.event import CalendarEvent, DateRange, System

UTC = timezone.utc
MSK = timezone(timedelta(hours=3))
WINDOW = DateRange(datetime(2026, 11, 1, tzinfo=MSK), datetime(2026, 12, 1, tzinfo=MSK))
SERVER = "https://domino.example"
EVENTS_URL = SERVER + "/mail/user.nsf/api/calendar/events"
OWNER = {"distinguishedName": "Ivan Petrov/ACME", "email": "IPetrov@example.com"}
A_ID = "A1B2C3D4E5F6A7B8C9D0E1F2A3B4C5D6-Lotus_Notes_Generated"
W_ID = "C1B2C3D4E5F6A7B8C9D0E1F2A3B4C5D6-Lotus_Notes_Generated/20261117T070000Z"
ME_ID = "1A2B3C4D5E6F7A8B9C0D1E2F3A4B5C6D-Lotus_Notes_Generated"


class FakeResponse:
    def __init__(self, payload, status=200, headers=None):
        self._payload = payload
        self.status_code = status
        self.headers = headers or {}
        self.content = json.dumps(payload).encode() if payload is not None else b""

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.HTTPError(f"HTTP {self.status_code}", response=self)

    def json(self):
        return self._payload


class FakeSession:
    """Domino: список кусками не больше `cap`, Content-Range с перекрытием страниц,
    детали по полному URL события (href)."""

    def __init__(self, events=(), details=None, cap=None, overlap=0, calendar=None):
        self.events = list(events)
        self.details = dict(details or {})
        self.cap = cap
        self.overlap = overlap
        self.calendar = calendar
        self.gets = []
        self.posts = []
        self.puts = []
        self.deletes = []

    def get(self, url, params=None, timeout=None):
        self.gets.append((url, params))
        if url.endswith("/api/calendar"):
            return FakeResponse(self.calendar)
        if url == EVENTS_URL:
            start = params["start"]
            size = min(params["count"], self.cap or params["count"])
            page = self.events[start : start + size]
            if not page:
                return FakeResponse(None)  # Domino отдаёт пустое тело, если событий нет
            last = start + len(page) - 1 - self.overlap
            return FakeResponse({"events": page}, headers={"Content-Range": f"items {start}-{last}"})
        if url in self.details:
            value = self.details[url]
            return FakeResponse(None, status=value) if isinstance(value, int) else FakeResponse(value)
        return FakeResponse({"code": 404, "text": "Not found", "cserror": 1028}, status=404)

    def post(self, url, json, timeout=None):
        self.posts.append(json)
        return FakeResponse({"events": [{"id": "NEWUID"}]})

    def put(self, url, json, timeout=None, params=None):
        self.puts.append((url, json))
        return FakeResponse(json)

    def delete(self, url, timeout=None, params=None):
        self.deletes.append(url)
        status = self.details.pop(url, 200) if url in self.details else 404
        if isinstance(status, dict):
            status = 200
        return FakeResponse(None, status=status)


def adapter_with(session):
    adapter = DominoRestAdapter(server_url=SERVER, username="u", password="p")
    adapter.session = session
    adapter.events_url = EVENTS_URL
    adapter.calendar_info = {"owner": OWNER}
    return adapter


@pytest.fixture
def raw_events(fixture_json):
    return fixture_json("domino_events.json")["events"]


@pytest.fixture
def details(fixture_json, raw_events):
    """Детальные ответы: из фикстуры, для остальных событий — сводка + пустое описание."""
    result = {}
    for raw in raw_events:
        detail = {k: v for k, v in raw.items() if k != "x-lotus-summarydataonly"}
        detail.update({"description": "", "last-modified": "20261101T000000Z"})
        result[SERVER + raw["href"]] = {"events": [detail]}
    for href, body in fixture_json("domino_event_details.json").items():
        result[SERVER + href] = body
    return result


def by_id(events):
    return {e.id: e for e in events}


def _instance_gone(details):
    """Экземпляра в детальном ответе нет: сервер отдал только мастер серии."""
    url = EVENTS_URL + "/" + W_ID
    master = {k: v for k, v in details[url]["events"][0].items() if k != "recurrenceId"}
    master.update(summary="Weekly sync (series)", description="Series agenda")
    details[url] = {**details[url], "events": [master]}
    return details


# --- конвертация сводки ---


def test_utc_event_is_converted(raw_events):
    event = event_from_domino(raw_events[0], owner=OWNER)

    assert event.title == "Budget review"
    assert event.start == datetime(2026, 11, 13, 22, tzinfo=UTC)
    assert event.end == datetime(2026, 11, 13, 23, tzinfo=UTC)
    assert (event.location, event.source, event.id) == ("Room 2", None, A_ID)
    assert event.uid == "A1B2C3D4E5F6A7B8C9D0E1F2A3B4C5D6-Lotus_Notes_Generated"
    assert event.organizer_is_me and not event.has_attendees and not event.private
    assert not event.ignored and event.original_start is None


def test_all_day_end_is_exclusive_in_list(raw_events):
    """Реальный сервер: однодневное событие приходит как 2026-11-16 .. 2026-11-17."""
    event = event_from_domino(raw_events[1])

    assert event.all_day
    assert (event.start, event.end) == (date(2026, 11, 16), date(2026, 11, 17))


def test_all_day_end_equal_to_start_means_one_day():
    raw = {"id": "X", "summary": "S", "start": {"date": "2026-11-16"}, "end": {"date": "2026-11-16"}}

    assert event_from_domino(raw).end == date(2026, 11, 17)


def test_recurring_instance(raw_events):
    event = event_from_domino(raw_events[2])

    assert event.id == W_ID
    assert event.uid == "C1B2C3D4E5F6A7B8C9D0E1F2A3B4C5D6-Lotus_Notes_Generated"
    assert event.original_start == datetime(2026, 11, 17, 7, tzinfo=UTC)


def test_marker_becomes_source(raw_events):
    event = event_from_domino(raw_events[3])

    assert (event.title, event.source) == ("Release night", System.GOOGLE)


def test_tzid_time_without_definition_is_rejected(raw_events):
    with pytest.raises(ValueError):
        event_from_domino(raw_events[4])


def test_foreign_private_meeting(raw_events):
    event = event_from_domino(raw_events[5], owner=OWNER)

    assert not event.organizer_is_me
    assert event.has_attendees
    assert event.private


def test_own_meeting_organizer_formats_are_normalized(raw_events):
    """x-lotus-organizer "CN=Ivan Petrov/O=ACME" == владелец "Ivan Petrov/ACME"."""
    event = event_from_domino(raw_events[6], owner=OWNER)

    assert event.organizer_is_me


def test_foreign_organizer_with_unknown_owner_is_not_me(raw_events):
    assert not event_from_domino(raw_events[5]).organizer_is_me


def test_cancelled_instance_is_ignored(raw_events):
    assert event_from_domino(raw_events[7], owner=OWNER).ignored


def test_tzid_time_is_converted_with_timezones_block(fixture_json):
    body = fixture_json("domino_event_details.json")
    href = "/mail/user.nsf/api/calendar/events/" + A_ID
    raw = body[href]["events"][0]

    event = event_from_domino(raw, owner=OWNER, timezones=body[href]["timezones"])

    assert event.start == datetime(2026, 11, 13, 22, tzinfo=UTC)  # FLE зимой = UTC+2
    assert event.description == "Q4\nfigures"
    assert event.modified == datetime(2026, 10, 7, 5, 0, 45, tzinfo=UTC)


def test_tzid_daylight_time(fixture_json):
    tz = fixture_json("domino_event_details.json")
    timezones = next(iter(tz.values()))["timezones"]
    raw = {
        "id": "X",
        "summary": "Summer",
        "start": {"date": "2026-07-01", "time": "12:00:00", "tzid": "FLE Standard Time"},
        "end": {"date": "2026-07-01", "time": "13:00:00", "tzid": "FLE Standard Time"},
    }

    assert event_from_domino(raw, timezones=timezones).start == datetime(2026, 7, 1, 9, tzinfo=UTC)


# --- list ---


def test_list_merges_details_and_skips_unsupported(raw_events, details):
    session = FakeSession(raw_events, details)

    listed = by_id(adapter_with(session).list(WINDOW))

    assert len(listed) == 8  # tzid-событие пропущено, две записи "Business trip" склеены
    budget = listed[A_ID]
    assert budget.start == datetime(2026, 11, 13, 22, tzinfo=UTC)  # время из сводки (UTC)
    assert budget.description == "Q4\nfigures"
    assert budget.modified == datetime(2026, 10, 7, 5, 0, 45, tzinfo=UTC)
    assert listed[W_ID].description == "Standing agenda"
    assert listed[W_ID].start == datetime(2026, 11, 17, 7, tzinfo=UTC)
    mine = listed[ME_ID]
    assert mine.organizer_is_me and mine.has_attendees
    assert mine.description == "Line one\nLine two"
    assert all(e.modified is not None for e in listed.values() if not e.ignored)
    first_url, first_params = session.gets[0]
    assert first_params["since"] == "2026-10-31T21:00:00Z"
    assert first_params["before"] == "2026-11-30T21:00:00Z"
    # детали запрошены по href, в том числе для экземпляра серии
    assert SERVER + "/mail/user.nsf/api/calendar/events/" + W_ID in [u for u, _ in session.gets]


def test_multi_day_all_day_entries_are_merged(raw_events, details):
    listed = adapter_with(FakeSession(raw_events, details)).list(WINDOW)

    trip = [e for e in listed if e.title == "Business trip"]
    assert len(trip) == 1
    assert (trip[0].start, trip[0].end) == (date(2026, 11, 23), date(2026, 11, 25))


def test_list_skips_details_of_ignored_entries(raw_events, details):
    """Реальный сервер: отменённые экземпляры и уведомления (x-lotus-unid) отдают 404 на детали."""
    cancelled = dict(raw_events[7], href="/mail/user.nsf/api/calendar/events/C/20261126T080000Z")
    notice = {
        "href": "/mail/user.nsf/api/calendar/events/N-Lotus_Notes_Generated",
        "id": "N-Lotus_Notes_Generated",
        "summary": "Invitation: Planning",
        "start": {"date": "2026-11-13", "time": "12:30:00", "utc": True},
        "end": {"date": "2026-11-13", "time": "13:30:00", "utc": True},
        "transparency": "transparent",
        "x-lotus-noticetype": {"data": "I"},
        "x-lotus-unid": {"data": "B1A3589DF68FA0F243258E74004F64B2"},
    }
    for raw in (cancelled, notice):
        details[SERVER + raw["href"]] = 404
    session = FakeSession(raw_events + [cancelled, notice], details)

    listed = adapter_with(session).list(WINDOW)

    assert by_id(listed)["N-Lotus_Notes_Generated"].ignored
    assert all(e.ignored for e in listed if e.title == raw_events[7]["summary"])
    fetched = [u for u, _ in session.gets]
    assert SERVER + cancelled["href"] not in fetched
    assert SERVER + notice["href"] not in fetched


def test_list_detail_errors_abort(raw_events, details):
    """Без деталей описание было бы пустым: лучше прервать, чем затереть копии."""
    details[SERVER + raw_events[0]["href"]] = 500

    with pytest.raises(requests.HTTPError):
        adapter_with(FakeSession(raw_events, details)).list(WINDOW)


def test_list_instance_missing_from_detail_aborts(raw_events, details):
    """Детали экземпляра без него самого: поля мастера не подмешиваем, как с пропавшим событием."""
    with pytest.raises(RuntimeError):
        adapter_with(FakeSession(raw_events, _instance_gone(details))).list(WINDOW)


def test_list_pages_by_content_range_until_empty_and_dedups(monkeypatch):
    """Сервер отдаёт не больше 40 событий на запрос, а страницы перекрываются."""
    many = [
        {
            "href": f"/mail/user.nsf/api/calendar/events/U{i}",
            "id": f"U{i}",
            "summary": f"E{i}",
            "start": {"date": "2026-11-13", "time": f"{10 + i}:00:00", "utc": True},
            "end": {"date": "2026-11-13", "time": f"{10 + i}:30:00", "utc": True},
        }
        for i in range(7)
    ]
    session = FakeSession(many, cap=3, overlap=1)
    for raw in many:
        session.details[SERVER + raw["href"]] = {"events": [dict(raw, description="d")]}

    listed = adapter_with(session).list(WINDOW)

    assert [e.id for e in listed] == [f"U{i}" for i in range(7)]
    starts = [params["start"] for url, params in session.gets if url == EVENTS_URL]
    assert starts == [0, 2, 4, 6, 7]  # конец Content-Range + 1, до пустой страницы


def test_list_falls_back_to_page_length_without_content_range(monkeypatch):
    many = [
        {
            "href": f"/mail/user.nsf/api/calendar/events/U{i}",
            "id": f"U{i}",
            "summary": f"E{i}",
            "start": {"date": "2026-11-13", "time": "10:00:00", "utc": True},
            "end": {"date": "2026-11-13", "time": "11:00:00", "utc": True},
        }
        for i in range(5)
    ]

    class NoRange(FakeSession):
        def get(self, url, params=None, timeout=None):
            response = super().get(url, params, timeout)
            response.headers = {}
            return response

    session = NoRange(many, cap=2)
    for raw in many:
        session.details[SERVER + raw["href"]] = {"events": [dict(raw)]}

    listed = adapter_with(session).list(WINDOW)

    assert [e.id for e in listed] == ["U0", "U1", "U2", "U3", "U4"]


# --- connect / владелец ---


def test_connect_reads_owner(fixture_json):
    session = FakeSession(calendar=fixture_json("domino_calendar.json"))
    adapter = DominoRestAdapter(server_url=SERVER, username="u", password="p")
    adapter.session = session

    adapter.connect()

    assert adapter.events_url == EVENTS_URL
    assert adapter.calendar_info["owner"]["distinguishedName"] == "Ivan Petrov/ACME"


# --- create ---


def test_create_writes_utc_with_marker():
    session = FakeSession()
    copy_event = CalendarEvent(
        title="Release night",
        start=datetime(2026, 11, 14, 1, tzinfo=MSK),
        end=datetime(2026, 11, 14, 2, 30, tzinfo=MSK),
        source=System.GOOGLE,
    )

    assert adapter_with(session).create(copy_event) == "NEWUID"
    body = session.posts[0]["events"][0]
    assert body["summary"] == "[GC] Release night"
    assert body["start"] == {"date": "2026-11-13", "time": "22:00:00", "utc": True}
    assert body["end"] == {"date": "2026-11-13", "time": "23:30:00", "utc": True}
    assert "class" not in body


def test_create_private_copy():
    session = FakeSession()
    private = CalendarEvent(
        title="Doctor", start=date(2026, 11, 13), end=date(2026, 11, 14), source=System.TEAMS, private=True
    )

    adapter_with(session).create(private)

    assert session.posts[0]["events"][0]["class"] == "private"


def test_all_day_round_trip():
    holiday = CalendarEvent(
        title="Holiday", start=date(2026, 11, 13), end=date(2026, 11, 15), source=System.GOOGLE, id="H", uid="H"
    )

    assert event_from_domino({**event_to_domino(holiday), "id": "H"}) == holiday


# --- get ---


def test_get_reads_detail_by_id(details):
    session = FakeSession(details=details)

    event = adapter_with(session).get(W_ID)

    assert session.gets[0][0] == EVENTS_URL + "/" + W_ID
    assert event.id == W_ID
    assert event.start == datetime(2026, 11, 17, 7, tzinfo=UTC)  # из tzid через timezones
    assert event.description == "Standing agenda"


@pytest.mark.parametrize("status", [404, 410])
def test_get_missing_is_none(status):
    session = FakeSession(details={EVENTS_URL + "/GONE": status})

    assert adapter_with(session).get("GONE") is None


def test_get_unknown_is_none():
    assert adapter_with(FakeSession()).get("NOPE") is None


def test_get_cancelled_is_none(raw_events, details):
    cancelled = raw_events[7]
    session = FakeSession(details=details)
    event_id = f"{cancelled['id']}/{cancelled['recurrenceId']}"

    assert adapter_with(session).get(event_id) is None


def test_get_instance_missing_from_detail_is_none(details):
    """Экземпляр удалён или перенесён: мастер серии вместо него не возвращаем."""
    assert adapter_with(FakeSession(details=_instance_gone(details))).get(W_ID) is None


def test_get_series_without_recurrence_takes_first_entry(details):
    url = EVENTS_URL + "/" + A_ID
    other = dict(details[url]["events"][0], recurrenceId="20261120T070000Z")
    details[url] = {**details[url], "events": details[url]["events"] + [other]}

    assert adapter_with(FakeSession(details=details)).get(A_ID).id == A_ID


def test_get_other_errors_propagate():
    session = FakeSession(details={EVENTS_URL + "/X": 500})

    with pytest.raises(requests.HTTPError):
        adapter_with(session).get("X")


# --- update ---


def test_update_puts_detail_with_changed_fields(details):
    session = FakeSession(details=details)
    url = EVENTS_URL + "/" + W_ID
    original = copy.deepcopy(details[url])
    changed = CalendarEvent(
        title="Weekly sync v2",
        start=datetime(2026, 11, 17, 8, tzinfo=UTC),
        end=datetime(2026, 11, 17, 8, 45, tzinfo=UTC),
        location="",
        description="New agenda",
    )

    adapter_with(session).update(W_ID, changed)

    put_url, body = session.puts[0]
    assert put_url == url
    assert body["timezones"] == original["timezones"]
    event = body["events"][0]
    assert event["summary"] == "Weekly sync v2"
    assert event["location"] == ""
    assert event["description"] == "New agenda"
    assert event["start"] == {"date": "2026-11-17", "time": "08:00:00", "utc": True}
    assert event["end"] == {"date": "2026-11-17", "time": "08:45:00", "utc": True}
    assert event["sequence"] == original["events"][0]["sequence"]
    assert event["recurrenceId"] == "20261117T070000Z"
    assert details[url] == original  # фикстура не изменена на месте


def test_update_keeps_marker_of_copy(details):
    session = FakeSession(details=details)
    changed = CalendarEvent(
        title="Budget review",
        start=datetime(2026, 11, 13, 22, tzinfo=UTC),
        end=datetime(2026, 11, 13, 23, tzinfo=UTC),
        source=System.TEAMS,
    )

    adapter_with(session).update(A_ID, changed)

    assert session.puts[0][1]["events"][0]["summary"] == "[MT] Budget review"


def test_update_missing_event_raises():
    with pytest.raises(requests.HTTPError):
        adapter_with(FakeSession()).update("GONE", CalendarEvent(title="x", start=date(2026, 1, 1), end=date(2026, 1, 2)))


def test_update_instance_missing_from_detail_does_not_put_master(details):
    """Иначе PUT мастера по URL экземпляра перезаписал бы всю серию."""
    session = FakeSession(details=_instance_gone(details))
    changed = CalendarEvent(
        title="Weekly sync v2",
        start=datetime(2026, 11, 17, 8, tzinfo=UTC),
        end=datetime(2026, 11, 17, 8, 45, tzinfo=UTC),
    )

    with pytest.raises(RuntimeError):
        adapter_with(session).update(W_ID, changed)

    assert session.puts == []


# --- delete ---


def test_delete_instance_by_href(details):
    session = FakeSession(details=details)

    adapter_with(session).delete(W_ID)

    assert session.deletes == [EVENTS_URL + "/" + W_ID]


def test_delete_already_deleted_is_ok():
    session = FakeSession()

    adapter_with(session).delete("GONE")  # 404 (cserror 1031) — уже удалено

    assert session.deletes == [EVENTS_URL + "/GONE"]


def test_delete_other_errors_propagate():
    session = FakeSession(details={EVENTS_URL + "/X": 403})

    with pytest.raises(requests.HTTPError):
        adapter_with(session).delete("X")
