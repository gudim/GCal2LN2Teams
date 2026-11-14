"""Выбор календаря Google: список, создание, смена с удалением старых копий."""
import asyncio

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from src.database.models import Base
from src.sync.event import System
from src.sync.links import load_links, save_links
from src.sync.planner import Link
from src.web import app as web

G, D, T = System.GOOGLE, System.DOMINO, System.TEAMS


class FakeGoogle:
    """Адаптер Google: календари и удаление событий, по календарю на экземпляр."""

    calendars = [
        {"id": "me@x", "summary": "Я", "access_role": "owner", "primary": True},
        {"id": "hol@x", "summary": "Праздники", "access_role": "reader", "primary": False},
    ]

    def __init__(self, calendar_id, events, deleted, fail=()):
        self.calendar_id = calendar_id
        self.events = events
        self.deleted = deleted
        self.fail = fail

    def authenticate(self):
        pass

    def list_calendars(self):
        return list(self.calendars)

    def create_calendar(self, summary):
        calendar = {"id": "new@group", "summary": summary, "access_role": "owner", "primary": False}
        self.calendars.append(calendar)
        return calendar

    def delete(self, event_id):
        if event_id in self.fail:
            raise RuntimeError("boom")
        self.deleted.append((self.calendar_id, event_id))


@pytest.fixture
def env(monkeypatch, tmp_path):
    engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    config = {"google_credentials_file": str(tmp_path / "c.json"), "google_calendar_id": "primary"}
    (tmp_path / "c.json").write_text("{}")
    deleted, fail = [], set()
    monkeypatch.setattr(web, "get_config_value", lambda db, key, decrypt=False: config.get(key))
    monkeypatch.setattr(web, "set_config_value", lambda db, key, value, encrypt=False: config.__setitem__(key, value))
    monkeypatch.setattr(web.os.path, "exists", lambda path: True)
    monkeypatch.setattr(web, "GoogleCalendarAdapter", lambda calendar_id="primary", **kw: FakeGoogle(calendar_id, [], deleted, fail))
    yield db, config, deleted, fail
    db.close()


def call(coro):
    return asyncio.run(coro)


def test_list_marks_writable_and_current(env):
    db, config, _, _ = env
    config["google_calendar_id"] = "me@x"

    result = call(web.list_google_calendars(db=db))

    assert result["current"] == "me@x"
    assert [(c["id"], c["writable"]) for c in result["calendars"]][:2] == [("me@x", True), ("hol@x", False)]


def test_create_calendar(env):
    db, _, _, _ = env

    result = call(web.create_google_calendar(web.CreateCalendarRequest(summary="Notes"), db=db))

    assert result["calendar"]["id"] == "new@group" and result["calendar"]["writable"]


def test_switch_deletes_old_copies_and_keeps_other_links(env):
    db, config, deleted, _ = env
    save_links(db, [Link(D, "d1", G, "g1"), Link(G, "g2", D, "d2"), Link(D, "d3", T, "t3")])

    preview = call(web.google_switch_preview(calendar_id="new@group", db=db))
    result = call(web.switch_google_calendar(web.SwitchCalendarRequest(calendar_id="new@group", delete_old=True), db=db))

    assert preview["old_copies"] == 1
    assert (result["deleted"], result["errors"]) == (1, [])
    assert deleted == [("primary", "g1")]  # удалено в СТАРОМ календаре
    assert config["google_calendar_id"] == "new@group"
    # копии больше нет; оригинал из старого календаря и Domino↔Teams остались
    assert sorted(load_links(db)) == sorted([Link(G, "g2", D, "d2"), Link(D, "d3", T, "t3")])


def test_switch_without_deleting_keeps_old_links(env):
    db, config, deleted, _ = env
    save_links(db, [Link(D, "d1", G, "g1")])

    result = call(web.switch_google_calendar(web.SwitchCalendarRequest(calendar_id="new@group", delete_old=False), db=db))

    assert result["deleted"] == 0 and deleted == []
    assert load_links(db) == [Link(D, "d1", G, "g1")] and load_links(db, "new@group") == []


def test_failed_delete_keeps_link_and_reports(env):
    db, _, deleted, fail = env
    fail.add("g1")
    save_links(db, [Link(D, "d1", G, "g1")])

    result = call(web.switch_google_calendar(web.SwitchCalendarRequest(calendar_id="new@group", delete_old=True), db=db))

    assert result["deleted"] == 0 and len(result["errors"]) == 1
    assert load_links(db) == [Link(D, "d1", G, "g1")]

