from datetime import date, datetime, timezone

import httplib2
import pytest
from googleapiclient.errors import HttpError

from src.adapters.google_calendar import GoogleCalendarAdapter, event_from_google, event_to_google
from src.sync.event import CalendarEvent, DateRange, System

UTC = timezone.utc
WINDOW = DateRange(datetime(2026, 11, 1, tzinfo=UTC), datetime(2026, 12, 1, tzinfo=UTC))


class _Call:
    def __init__(self, result):
        self._result = result

    def execute(self):
        return self._result


class FakeEvents:
    def __init__(self, pages):
        self.pages = pages  # pageToken -> response
        self.list_calls = []
        self.inserted = []
        self.stored = {}  # eventId -> событие или HTTP-статус ошибки
        self.patched = []
        self.deleted = []

    def list(self, **kwargs):
        self.list_calls.append(kwargs)
        return _Call(self.pages[kwargs.get("pageToken")])

    def insert(self, calendarId, body):
        self.inserted.append(body)
        return _Call({"id": f"new-{len(self.inserted)}"})

    def _stored(self, eventId):
        value = self.stored.get(eventId, 404)
        if isinstance(value, int):
            raise HttpError(httplib2.Response({"status": value}), b"{}")
        return value

    def get(self, calendarId, eventId):
        return _Lazy(lambda: self._stored(eventId))

    def patch(self, calendarId, eventId, body, **kwargs):
        self.patched.append((eventId, body, kwargs))
        return _Lazy(lambda: self._stored(eventId))

    def delete(self, calendarId, eventId, **kwargs):
        self.deleted.append((eventId, kwargs))
        return _Lazy(lambda: self._stored(eventId) and "")


class _Lazy:
    def __init__(self, fn):
        self._fn = fn

    def execute(self):
        return self._fn()


class FakeService:
    def __init__(self, events):
        self._events = events

    def events(self):
        return self._events


def adapter_with(events):
    adapter = GoogleCalendarAdapter(credentials_file="unused", token_file="unused")
    adapter.service = FakeService(events)
    return adapter


def test_timed_event_is_converted_to_utc(fixture_json):
    raw = fixture_json("google_events_page1.json")["items"][0]

    event = event_from_google(raw)

    assert event.title == "Release night"
    assert event.start == datetime(2026, 11, 13, 22, 0, tzinfo=UTC)
    assert event.end == datetime(2026, 11, 13, 23, 30, tzinfo=UTC)
    assert (event.location, event.description, event.source, event.id) == ("Room 1", "Deploy", None, "g-late")


def test_all_day_event_keeps_dates(fixture_json):
    event = event_from_google(fixture_json("google_events_page1.json")["items"][1])

    assert event.all_day
    assert (event.start, event.end) == (date(2026, 11, 13), date(2026, 11, 14))


def test_marker_becomes_source(fixture_json):
    event = event_from_google(fixture_json("google_events_page2.json")["items"][0])

    assert (event.title, event.source) == ("Budget review", System.DOMINO)


def test_list_reads_all_pages_within_window(fixture_json):
    events = FakeEvents({None: fixture_json("google_events_page1.json"), "page-2": fixture_json("google_events_page2.json")})

    listed = adapter_with(events).list(WINDOW)

    assert [e.id for e in listed] == [
        "g-late", "g-allday", "g-birthday", "g-meeting_20261117T070000Z",
        "g-copy", "g-hidden-copy", "g-team", "g-wl", "g-gone",
    ]
    assert events.list_calls[0]["timeMin"] == "2026-11-01T00:00:00+00:00"
    assert events.list_calls[0]["timeMax"] == "2026-12-01T00:00:00+00:00"
    assert events.list_calls[0]["singleEvents"] is True
    assert events.list_calls[1]["pageToken"] == "page-2"


def test_create_sends_marker_and_utc_times():
    events = FakeEvents({})
    copy = CalendarEvent(
        title="Standup",
        start=datetime(2026, 11, 13, 7, tzinfo=UTC),
        end=datetime(2026, 11, 13, 7, 15, tzinfo=UTC),
        source=System.TEAMS,
    )

    new_id = adapter_with(events).create(copy)

    assert new_id == "new-1"
    body = events.inserted[0]
    assert body["summary"] == "[MT] Standup"
    assert body["start"] == {"dateTime": "2026-11-13T07:00:00+00:00", "timeZone": "UTC"}


def test_all_day_round_trip():
    holiday = CalendarEvent(title="Holiday", start=date(2026, 11, 13), end=date(2026, 11, 14), source=System.DOMINO, id="x")

    body = event_to_google(holiday)
    back = event_from_google({**body, "id": "x"})

    assert back == holiday


@pytest.fixture
def items(fixture_json):
    raw = fixture_json("google_events_page1.json")["items"] + fixture_json("google_events_page2.json")["items"]
    return {item["id"]: item for item in raw}


def test_native_event_metadata(items):
    event = event_from_google(items["g-late"])

    assert event.uid == "g-late@google.com"
    assert event.modified == datetime(2026, 11, 10, 8, 15, 30, 123000, tzinfo=UTC)
    assert event.organizer_is_me and not event.has_attendees
    assert not event.private and not event.ignored and event.original_start is None


def test_private_visibility(items):
    assert event_from_google(items["g-allday"]).private
    assert event_from_google(items["g-meeting_20261117T070000Z"]).private  # confidential


def test_service_event_types_are_ignored(items):
    assert event_from_google(items["g-birthday"]).ignored
    assert event_from_google(items["g-wl"]).ignored
    assert not event_from_google(items["g-team"]).ignored  # focusTime — обычное событие


def test_declined_foreign_instance(items):
    event = event_from_google(items["g-meeting_20261117T070000Z"])

    assert event.ignored
    assert not event.organizer_is_me
    assert event.has_attendees
    assert event.uid == "g-meeting@google.com"
    assert event.original_start == datetime(2026, 11, 17, 7, tzinfo=UTC)


def test_own_meeting_with_guests_has_attendees(items):
    event = event_from_google(items["g-team"])

    assert event.organizer_is_me and event.has_attendees


def test_hidden_marker_wins_over_title(items):
    event = event_from_google(items["g-hidden-copy"])

    assert (event.title, event.source) == ("Standup renamed by user", System.TEAMS)


def test_cancelled_instance_without_times_is_ignored(items):
    event = event_from_google(items["g-gone"])

    assert event.ignored
    assert event.id == "g-gone"


def test_create_stores_hidden_marker_and_privacy():
    events = FakeEvents({})
    copy = CalendarEvent(
        title="Budget review",
        start=datetime(2026, 11, 13, 9, tzinfo=UTC),
        end=datetime(2026, 11, 13, 10, tzinfo=UTC),
        source=System.DOMINO,
        id="A1B2/20261113T090000Z",
        private=True,
    )

    adapter_with(events).create(copy)

    body = events.inserted[0]
    assert body["extendedProperties"] == {
        "private": {"gcalsync_origin": "domino", "gcalsync_origin_id": "A1B2/20261113T090000Z"}
    }
    assert body["visibility"] == "private"
    assert body["summary"] == "[LN] Budget review"


def test_native_event_body_has_no_marker():
    body = event_to_google(CalendarEvent(title="Mine", start=date(2026, 11, 13), end=date(2026, 11, 14)))

    assert "extendedProperties" not in body and "visibility" not in body


def test_get_returns_event(items):
    events = FakeEvents({})
    events.stored["g-late"] = items["g-late"]

    assert adapter_with(events).get("g-late").title == "Release night"


@pytest.mark.parametrize("stored", [404, 410, {"id": "x", "status": "cancelled"}])
def test_get_missing_or_cancelled_is_none(stored):
    events = FakeEvents({})
    events.stored["x"] = stored

    assert adapter_with(events).get("x") is None


def test_get_other_errors_propagate():
    events = FakeEvents({})
    events.stored["x"] = 500

    with pytest.raises(HttpError):
        adapter_with(events).get("x")


def test_update_patches_fields_without_notifications(items):
    events = FakeEvents({})
    events.stored["g-copy"] = items["g-copy"]
    changed = CalendarEvent(
        title="Budget review v2",
        start=datetime(2026, 11, 13, 9, 30, tzinfo=UTC),
        end=datetime(2026, 11, 13, 10, 30, tzinfo=UTC),
        location="",
        description="New agenda",
        source=System.DOMINO,
    )

    adapter_with(events).update("g-copy", changed)

    event_id, body, kwargs = events.patched[0]
    assert event_id == "g-copy"
    assert kwargs["sendUpdates"] == "none"
    assert body["summary"] == "[LN] Budget review v2"
    assert body["description"] == "New agenda"
    assert body["location"] == ""
    assert body["start"] == {"dateTime": "2026-11-13T09:30:00+00:00", "timeZone": "UTC"}
    assert "extendedProperties" not in body and "visibility" not in body


@pytest.mark.parametrize("status", [404, 410])
def test_delete_already_deleted_is_ok(status):
    events = FakeEvents({})
    events.stored["x"] = status

    adapter_with(events).delete("x")

    assert events.deleted == [("x", {"sendUpdates": "none"})]


def test_delete_other_errors_propagate():
    events = FakeEvents({})
    events.stored["x"] = 403

    with pytest.raises(HttpError):
        adapter_with(events).delete("x")
