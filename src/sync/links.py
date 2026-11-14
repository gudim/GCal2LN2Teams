"""
Хранение связей (оригинал -> копия, двойники) в таблице sync_history.
"""
from __future__ import annotations

import json
from typing import Iterable, List, Optional

from sqlalchemy.orm import Session

from ..database.sync_history import SyncHistory
from .event import System
from .planner import Link


PRIMARY = "primary"


def _in_calendar(row: SyncHistory, source: System, target: System, google_calendar: str) -> bool:
    """Связь с Google относится к одному календарю Google; остальные — ко всем."""
    if System.GOOGLE not in (source, target):
        return True
    return (row.google_calendar or PRIMARY) == google_calendar


def load_links(db: Session, google_calendar: str = PRIMARY) -> List[Link]:
    """Связи, видимые при синхронизации с календарём Google `google_calendar`."""
    links = []
    for row in db.query(SyncHistory).all():
        link = _link(row)
        if link is None:
            continue
        if not _in_calendar(row, link.source_system, link.target_system, google_calendar):
            continue
        links.append(link)
    return links


def _link(row: SyncHistory) -> Optional[Link]:
    """Связь из строки таблицы; None — строка в старом формате."""
    try:
        source, target = System(row.source_system), System(row.target_system)
    except ValueError:
        return None
    if not row.source_event_id:
        return None
    snapshot = json.loads(row.snapshot) if row.snapshot else {}
    return Link(
        source, row.source_event_id, target, row.target_event_id,
        kind=row.kind or "copy",
        source_fields=snapshot.get("source"),
        target_fields=snapshot.get("target"),
        kept=bool(row.kept),
    )


def google_copies(db: Session, google_calendar: str) -> List[Link]:
    """Копии синхронизатора, лежащие в календаре Google `google_calendar`."""
    return [
        link for link in load_links(db, google_calendar)
        if link.kind == "copy" and link.target_system == System.GOOGLE and link.target_id
    ]


def save_links(
    db: Session, links: Iterable[Link], google_calendar: Optional[str] = PRIMARY
) -> None:
    for link in links:
        db.add(_row(db, link, google_calendar))
    db.commit()


class DbLinkStore:
    """Сохраняет каждую связь сразу (commit), чтобы сбой посередине не привёл к дублям."""

    def __init__(self, db: Session, google_calendar: str = PRIMARY) -> None:
        self.db = db
        self.google_calendar = google_calendar

    def save(self, link: Link) -> None:
        save_links(self.db, [link], self.google_calendar)

    def drop(self, link: Link) -> None:
        self.db.query(SyncHistory).filter(SyncHistory.event_key == link.key()).delete()
        self.db.commit()


def _row(db: Session, link: Link, google_calendar: Optional[str]) -> SyncHistory:
    key = link.key()
    row = db.query(SyncHistory).filter(SyncHistory.event_key == key).first()
    row = row or SyncHistory(event_key=key)
    row.source_system = link.source_system.value
    row.target_system = link.target_system.value
    row.source_event_id = link.source_id
    row.target_event_id = link.target_id
    row.kind = link.kind
    has_snapshot = link.source_fields is not None or link.target_fields is not None
    row.snapshot = (
        json.dumps({"source": link.source_fields, "target": link.target_fields}, ensure_ascii=False)
        if has_snapshot else None
    )
    row.kept = link.kept
    if System.GOOGLE in (link.source_system, link.target_system):
        row.google_calendar = google_calendar
    return row
