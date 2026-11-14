"""/api/sync на календарях в памяти: предпросмотр отдаёт действия, запуск сохраняет связи."""
from datetime import datetime, timedelta, timezone

import pytest
import asyncio

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from src.database.models import Base
from src.sync.calendar import InMemoryCalendar
from src.sync.event import CalendarEvent, System
from src.sync.links import load_links
from src.web import app as web

SOON = datetime.now(timezone.utc).replace(microsecond=0) + timedelta(days=2)


class FakeCalendar(InMemoryCalendar):
    def __init__(self, system, events=()):
        super().__init__(system, events)

    def authenticate(self):
        pass

    def connect(self):
        pass

    def disconnect(self):
        pass


@pytest.fixture
def env(monkeypatch, tmp_path):
    engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine)()
    google = FakeCalendar(System.GOOGLE, [
        CalendarEvent(title="Review", start=SOON, end=SOON + timedelta(hours=1), id="g1"),
    ])
    domino = FakeCalendar(System.DOMINO)
    creds = tmp_path / "creds.json"
    creds.write_text("{}")
    monkeypatch.setattr(web, "get_config_value", lambda db, key, decrypt=False: (
        str(creds) if key == "google_credentials_file" else "x"
    ))
    monkeypatch.setattr(web.os.path, "exists", lambda path: True)
    monkeypatch.setattr(web, "GoogleCalendarAdapter", lambda **kw: google)
    monkeypatch.setattr(web, "DominoRestAdapter", lambda **kw: domino)

    def client(**body):
        return asyncio.run(web.run_sync(web.SyncRequest(**body), db=session))

    yield client, session, google, domino
    session.close()


def test_dry_run_returns_actions_and_run_saves_links(env):
    client, session, google, domino = env
    body = {"direction": "google-domino", "enable_teams": False}

    preview = client(**body, dry_run=True)

    [action] = preview["actions"]
    assert action["kind"] == "create" and action["selected"] is True
    assert (action["origin"], action["target"], action["title"]) == ("google", "domino", "Review")
    assert preview["warnings"] == [] and preview["google_to_domino"]["created"] == 1
    assert domino.events == []

    result = client(**body, keys=[action["key"]])

    assert result["google_to_domino"]["created"] == 1
    # связь записана в области текущего календаря Google (в конфиге — "x")
    assert [(l.source_id, l.target_system) for l in load_links(session, "x")] == [("g1", System.DOMINO)]
    assert load_links(session) == []
    again = client(**body, dry_run=True)
    assert again["actions"] == []


def test_unticked_create_is_refused_and_can_be_brought_back(env):
    client, session, google, domino = env
    body = {"direction": "google-domino", "enable_teams": False}
    [action] = client(**body, dry_run=True)["actions"]

    client(**body, keys=[])

    assert domino.events == []
    [refusal] = load_links(session, "x")
    assert (refusal.kept, refusal.target_id) == (True, None)
    preview = client(**body, dry_run=True)
    assert preview["actions"] == []
    assert [a["key"] for a in preview["refused"]] == [action["key"]]
    # Фон (без keys) отказ не нарушает
    client(**body)
    assert domino.events == []

    # Галочка у отказа в предпросмотре — копия создаётся, отказ заменяется связью копии
    result = client(**body, keys=[action["key"]])

    assert result["google_to_domino"]["created"] == 1
    [link] = load_links(session, "x")
    assert (link.kept, link.target_id) == (False, domino.events[0].id)
    assert client(**body, dry_run=True)["refused"] == []
