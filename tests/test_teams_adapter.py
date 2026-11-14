from datetime import date, datetime, timedelta, timezone

import pytest
import requests

from src.adapters.ms_teams_calendar import (
    ORIGIN_ID_PROP,
    ORIGIN_PROP,
    MSTeamsCalendarAdapter,
    event_from_graph,
    event_to_graph,
)
from src.sync.event import CalendarEvent, DateRange, System

UTC = timezone.utc
MSK = timezone(timedelta(hours=3))
WINDOW = DateRange(datetime(2026, 11, 1, tzinfo=UTC), datetime(2026, 12, 1, tzinfo=UTC))


class FakeGraph(MSTeamsCalendarAdapter):
    """Подменяем только HTTP-вызов; MSAL-клиент не создаётся."""

    def __init__(self, responses):
        self.responses = list(responses)
        self.requests = []

    def _make_request(self, method, endpoint, data=None, params=None, headers=None):
        self.requests.append({"method": method, "endpoint": endpoint, "data": data, "params": params, "headers": headers})
        response = self.responses.pop(0)
        if isinstance(response, int):
            error_response = requests.Response()
            error_response.status_code = response
            raise requests.HTTPError(f"HTTP {response}", response=error_response)
        return response


@pytest.fixture
def pages(fixture_json):
    return fixture_json("graph_calendarview_page1.json"), fixture_json("graph_calendarview_page2.json")


def test_timed_event_is_converted(pages):
    event = event_from_graph(pages[0]["value"][0])

    assert event.title == "Design review"
    assert event.start == datetime(2026, 11, 13, 22, tzinfo=UTC)
    assert event.end == datetime(2026, 11, 13, 22, 45, tzinfo=UTC)
    assert (event.location, event.description, event.source) == ("Teams Meeting", "Agenda", None)


def test_all_day_event_keeps_dates(pages):
    event = event_from_graph(pages[0]["value"][1])

    assert event.all_day
    assert (event.start, event.end) == (date(2026, 11, 20), date(2026, 11, 22))


def test_non_utc_time_is_rejected(pages):
    raw = {**pages[0]["value"][0], "start": {"dateTime": "2026-11-13T10:00:00.0000000", "timeZone": "Russian Standard Time"}}

    with pytest.raises(ValueError):
        event_from_graph(raw)


def test_list_follows_next_link_and_marks_cancelled_ignored(pages):
    graph = FakeGraph(pages)

    listed = graph.list(WINDOW)

    assert [e.id for e in listed] == [
        "AAMkAGI1-timed", "AAMkAGI1-allday", "AAMkAGI1-occurrence",
        "AAMkAGI1-copy", "AAMkAGI1-cancelled", "AAMkAGI1-hidden",
    ]
    assert listed[3].source == System.GOOGLE
    assert [e.id for e in listed if e.ignored] == ["AAMkAGI1-occurrence", "AAMkAGI1-cancelled"]
    first, second = graph.requests
    assert first["endpoint"] == "/me/calendarView"
    assert first["params"]["startDateTime"] == "2026-11-01T00:00:00Z"
    assert ORIGIN_PROP in first["params"]["$expand"] and ORIGIN_ID_PROP in first["params"]["$expand"]
    assert 'outlook.timezone="UTC"' in first["headers"]["Prefer"]
    assert 'IdType="ImmutableId"' in first["headers"]["Prefer"]
    assert second["endpoint"].startswith("/me/calendarView?") and second["params"] is None
    assert 'IdType="ImmutableId"' in second["headers"]["Prefer"]


def test_create_sends_utc_without_offset():
    graph = FakeGraph([{"id": "NEW"}])
    copy = CalendarEvent(
        title="Release night",
        start=datetime(2026, 11, 14, 1, tzinfo=MSK),
        end=datetime(2026, 11, 14, 2, 30, tzinfo=MSK),
        description="Deploy",
        source=System.GOOGLE,
    )

    assert graph.create(copy) == "NEW"
    body = graph.requests[0]["data"]
    assert body["subject"] == "[GC] Release night"
    assert body["start"] == {"dateTime": "2026-11-13T22:00:00", "timeZone": "UTC"}
    assert body["body"] == {"contentType": "text", "content": "Deploy"}
    assert body["isAllDay"] is False


def test_all_day_round_trip():
    holiday = CalendarEvent(title="Holiday", start=date(2026, 11, 13), end=date(2026, 11, 14), source=System.DOMINO, id="H")

    body = event_to_graph(holiday)
    back = event_from_graph({**body, "id": "H"})

    assert back == holiday


@pytest.fixture
def raw_by_id(pages):
    return {raw["id"]: raw for page in pages for raw in page["value"]}


def test_native_event_metadata(raw_by_id):
    event = event_from_graph(raw_by_id["AAMkAGI1-timed"])

    assert event.uid == "040000008200E00074C5B7101A82E00800000000timed"
    assert event.modified == datetime(2026, 11, 10, 8, 15, 30, 123456, tzinfo=UTC)
    assert event.organizer_is_me and not event.has_attendees
    assert not event.private and not event.ignored and event.original_start is None


def test_sensitivity_is_private(raw_by_id):
    assert event_from_graph(raw_by_id["AAMkAGI1-allday"]).private
    assert event_from_graph(raw_by_id["AAMkAGI1-occurrence"]).private  # confidential


def test_declined_foreign_occurrence(raw_by_id):
    event = event_from_graph(raw_by_id["AAMkAGI1-occurrence"])

    assert event.ignored and not event.organizer_is_me and event.has_attendees
    assert event.original_start == datetime(2026, 11, 17, 7, tzinfo=UTC)
    assert event.uid == "040000008200E00074C5B7101A82E00807EA0B11series"


def test_html_body_becomes_plain_text(raw_by_id):
    event = event_from_graph(raw_by_id["AAMkAGI1-occurrence"])

    assert event.description == "Hello team\nLine&two\nThird"


def test_hidden_marker_wins_over_title(raw_by_id):
    event = event_from_graph(raw_by_id["AAMkAGI1-hidden"])

    assert (event.title, event.source) == ("Renamed by user", System.DOMINO)


def test_create_stores_hidden_marker_privacy_and_transaction_id():
    graph = FakeGraph([{"id": "NEW"}, {"id": "NEW2"}])
    copy = CalendarEvent(
        title="Budget review",
        start=datetime(2026, 11, 13, 9, tzinfo=UTC),
        end=datetime(2026, 11, 13, 10, tzinfo=UTC),
        source=System.DOMINO,
        id="A1B2/20261113T090000Z",
        private=True,
    )

    graph.create(copy)
    graph.create(copy)

    first, second = graph.requests
    body = first["data"]
    assert body["singleValueExtendedProperties"] == [
        {"id": ORIGIN_PROP, "value": "domino"},
        {"id": ORIGIN_ID_PROP, "value": "A1B2/20261113T090000Z"},
    ]
    assert body["sensitivity"] == "private"
    assert body["transactionId"] and body["transactionId"] == second["data"]["transactionId"]
    assert 'IdType="ImmutableId"' in first["headers"]["Prefer"]


def test_transaction_id_differs_per_origin():
    a = CalendarEvent(title="A", start=date(2026, 11, 13), end=date(2026, 11, 14), source=System.DOMINO, id="1")
    b = CalendarEvent(title="A", start=date(2026, 11, 13), end=date(2026, 11, 14), source=System.GOOGLE, id="1")

    assert event_to_graph(a)["transactionId"] != event_to_graph(b)["transactionId"]


def test_native_event_body_has_no_marker():
    body = event_to_graph(CalendarEvent(title="Mine", start=date(2026, 11, 13), end=date(2026, 11, 14)))

    assert "singleValueExtendedProperties" not in body and "transactionId" not in body
    assert "sensitivity" not in body


def test_get_returns_event(raw_by_id):
    graph = FakeGraph([raw_by_id["AAMkAGI1-timed"]])

    event = graph.get("AAMkAGI1-timed")

    assert event.title == "Design review"
    request = graph.requests[0]
    assert (request["method"], request["endpoint"]) == ("GET", "/me/events/AAMkAGI1-timed")
    assert ORIGIN_PROP in request["params"]["$expand"]
    assert 'IdType="ImmutableId"' in request["headers"]["Prefer"]


@pytest.mark.parametrize("response", [404, 410, "cancelled"])
def test_get_missing_or_cancelled_is_none(raw_by_id, response):
    if response == "cancelled":
        response = raw_by_id["AAMkAGI1-cancelled"]
    graph = FakeGraph([response])

    assert graph.get("x") is None


def test_get_other_errors_propagate():
    with pytest.raises(requests.HTTPError):
        FakeGraph([500]).get("x")


def test_update_patches_synced_fields():
    graph = FakeGraph([{}])
    changed = CalendarEvent(
        title="Budget review v2",
        start=datetime(2026, 11, 13, 9, 30, tzinfo=UTC),
        end=datetime(2026, 11, 13, 10, 30, tzinfo=UTC),
        description="New agenda",
        source=System.DOMINO,
    )

    graph.update("AAMk-copy", changed)

    request = graph.requests[0]
    assert (request["method"], request["endpoint"]) == ("PATCH", "/me/events/AAMk-copy")
    body = request["data"]
    assert body["subject"] == "[LN] Budget review v2"
    assert body["body"] == {"contentType": "text", "content": "New agenda"}
    assert body["location"] == {"displayName": ""}
    assert "transactionId" not in body and "singleValueExtendedProperties" not in body
    assert 'IdType="ImmutableId"' in request["headers"]["Prefer"]


@pytest.mark.parametrize("response", [{}, 404, 410])
def test_delete_tolerates_already_deleted(response):
    graph = FakeGraph([response])

    graph.delete("AAMk-copy")

    request = graph.requests[0]
    assert (request["method"], request["endpoint"]) == ("DELETE", "/me/events/AAMk-copy")


def test_delete_other_errors_propagate():
    with pytest.raises(requests.HTTPError):
        FakeGraph([403]).delete("x")
