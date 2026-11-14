import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from src.database.models import Base
from src.sync.event import System
from src.sync.links import DbLinkStore, google_copies, load_links, save_links
from src.sync.planner import Link


@pytest.fixture
def db():
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine)()
    yield session
    session.close()


def test_links_round_trip(db):
    links = [Link(System.GOOGLE, "g1", System.DOMINO, "d1"), Link(System.TEAMS, "t1", System.GOOGLE, "g9")]

    save_links(db, links)

    assert sorted(load_links(db)) == sorted(links)


def test_saving_same_link_twice_updates_target(db):
    save_links(db, [Link(System.GOOGLE, "g1", System.DOMINO, "d1")])
    save_links(db, [Link(System.GOOGLE, "g1", System.DOMINO, "d2")])

    assert load_links(db) == [Link(System.GOOGLE, "g1", System.DOMINO, "d2")]


def test_snapshot_kind_and_kept_round_trip(db):
    snap = {"title": "A", "start": "s", "end": "e", "location": "", "description": ""}
    twin = Link(System.GOOGLE, "g1", System.DOMINO, "d1", "twin", snap, dict(snap, title="B"), kept=True)
    copy = Link(System.GOOGLE, "g1", System.TEAMS, "t1", source_fields=snap, target_fields=snap)

    save_links(db, [twin, copy])

    assert sorted(load_links(db), key=Link.key) == sorted([twin, copy], key=Link.key)


def test_refusal_round_trips_and_is_replaced_by_copy(db):
    refusal = Link(System.GOOGLE, "g1", System.DOMINO, None, kept=True)
    store = DbLinkStore(db)

    store.save(refusal)

    assert load_links(db) == [refusal] and load_links(db)[0].is_refusal
    store.save(Link(System.GOOGLE, "g1", System.DOMINO, "d1"))
    assert load_links(db) == [Link(System.GOOGLE, "g1", System.DOMINO, "d1")]


def test_store_saves_and_drops_immediately(db):
    store = DbLinkStore(db)
    link = Link(System.GOOGLE, "g1", System.DOMINO, "d1")

    store.save(link)
    assert load_links(db) == [link]
    store.drop(link)
    assert load_links(db) == []


def test_old_table_gets_new_columns_without_losing_rows(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'old.db'}")
    with engine.begin() as conn:
        conn.exec_driver_sql(
            "CREATE TABLE sync_history (id INTEGER PRIMARY KEY, event_key VARCHAR NOT NULL UNIQUE,"
            " source_system VARCHAR NOT NULL, target_system VARCHAR NOT NULL,"
            " source_event_id VARCHAR, target_event_id VARCHAR, synced_at DATETIME)"
        )
        conn.exec_driver_sql(
            "INSERT INTO sync_history (event_key, source_system, target_system, source_event_id,"
            " target_event_id) VALUES ('google:g1->domino', 'google', 'domino', 'g1', 'd1')"
        )

    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine)()

    assert load_links(session) == [Link(System.GOOGLE, "g1", System.DOMINO, "d1")]
    session.close()


# --- Связи привязаны к календарю Google ---

G, D, T = System.GOOGLE, System.DOMINO, System.TEAMS


def test_links_with_google_are_scoped_to_its_calendar(db):
    save_links(db, [Link(D, "d1", G, "g1"), Link(D, "d1", T, "t1")], google_calendar="primary")

    assert sorted(load_links(db, google_calendar="primary")) == sorted(
        [Link(D, "d1", G, "g1"), Link(D, "d1", T, "t1")]
    )
    # в другом календаре Google связь с ним не видна, а связь Domino↔Teams — видна
    assert load_links(db, google_calendar="notes@group") == [Link(D, "d1", T, "t1")]


def test_old_rows_without_scope_belong_to_primary(db):
    save_links(db, [Link(D, "d1", G, "g1")], google_calendar=None)

    assert load_links(db) == [Link(D, "d1", G, "g1")]
    assert load_links(db, google_calendar="notes@group") == []


def test_store_saves_with_scope(db):
    DbLinkStore(db, google_calendar="notes@group").save(Link(D, "d1", G, "g1"))

    assert load_links(db, google_calendar="notes@group") == [Link(D, "d1", G, "g1")]
    assert load_links(db) == []


def test_google_copies_in_calendar(db):
    save_links(db, [
        Link(D, "d1", G, "g1"),  # копия в Google
        Link(G, "g2", D, "d2"),  # оригинал в Google — не копия
        Link(D, "d3", G, "g3", "twin"),  # двойник — не копия
        Link(D, "d4", T, "t4"),
    ], google_calendar="primary")
    save_links(db, [Link(T, "t5", G, "g5")], google_calendar="notes@group")

    assert google_copies(db, "primary") == [Link(D, "d1", G, "g1")]
    assert google_copies(db, "notes@group") == [Link(T, "t5", G, "g5")]
