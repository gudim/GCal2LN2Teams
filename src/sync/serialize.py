"""
Языконезависимый JSON для событий, связей и действий движка.

Формат описан в spec/README.md (эталонные планы spec/golden).
"""
from __future__ import annotations

from datetime import date, datetime, timezone
from typing import Any, Dict, List, Optional

from .event import CalendarEvent, DateRange, System, When
from .planner import Action, Link, Plan

Json = Dict[str, Any]

BOOKKEEPING = ("link", "unlink")  # у служебных действий нет события


def when_to_json(value: Optional[When]) -> Optional[str]:
    """datetime -> "2026-11-13T10:00:00Z" (UTC), date -> "2026-11-13"."""
    if value is None:
        return None
    if isinstance(value, datetime):
        return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
    return value.isoformat()


def when_from_json(value: Optional[str]) -> Optional[When]:
    if value is None:
        return None
    if len(value) == 10:
        return date.fromisoformat(value)
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def event_to_json(event: CalendarEvent) -> Json:
    return {
        "id": event.id,
        "title": event.title,
        "start": when_to_json(event.start),
        "end": when_to_json(event.end),
        "location": event.location,
        "description": event.description,
        "source": event.source.value if event.source else None,
        "uid": event.uid,
        "original_start": when_to_json(event.original_start),
        "organizer_is_me": event.organizer_is_me,
        "has_attendees": event.has_attendees,
        "private": event.private,
        "modified": when_to_json(event.modified),
        "ignored": event.ignored,
    }


def event_from_json(data: Json) -> CalendarEvent:
    return CalendarEvent(
        id=data["id"],
        title=data["title"],
        start=when_from_json(data["start"]),  # type: ignore[arg-type]
        end=when_from_json(data["end"]),  # type: ignore[arg-type]
        location=data["location"],
        description=data["description"],
        source=System(data["source"]) if data["source"] else None,
        uid=data["uid"],
        original_start=when_from_json(data["original_start"]),
        organizer_is_me=data["organizer_is_me"],
        has_attendees=data["has_attendees"],
        private=data["private"],
        modified=when_from_json(data["modified"]),  # type: ignore[arg-type]
        ignored=data["ignored"],
    )


def link_to_json(link: Link) -> Json:
    """Снимки (source_fields/target_fields) пишутся как есть: это строки CalendarEvent.fields()."""
    return {
        "key": link.key(),
        "kind": link.kind,
        "source_system": link.source_system.value,
        "source_id": link.source_id,
        "target_system": link.target_system.value,
        "target_id": link.target_id,
        "source_fields": link.source_fields,
        "target_fields": link.target_fields,
        "kept": link.kept,
    }


def link_from_json(data: Json) -> Link:
    """Поле key вычисляемое: при чтении не используется."""
    return Link(
        System(data["source_system"]), data["source_id"],
        System(data["target_system"]), data["target_id"],
        kind=data["kind"],
        source_fields=data["source_fields"],
        target_fields=data["target_fields"],
        kept=data["kept"],
    )


def action_to_json(action: Action) -> Json:
    return {
        "key": action.key,
        "kind": action.kind,
        "origin": action.origin.value,
        "target": action.target.value,
        "event": None if action.kind in BOOKKEEPING else event_to_json(action.event),
        "selected": action.selected,
        "silent": action.silent,
        "reason": action.reason,
        "subject": action.subject,
        "changed": list(action.changed),
        "depends_on": list(action.depends_on),
        "link": link_to_json(action.link) if action.link is not None else None,
        "fallback": [action_to_json(f) for f in action.fallback],
    }


def plan_to_json(result: Plan) -> Json:
    return {
        "actions": [action_to_json(a) for a in result.actions],
        "warnings": list(result.warnings),
        "refused": [action_to_json(a) for a in result.refused],
    }


def window_to_json(window: DateRange) -> Json:
    return {"start": when_to_json(window.start), "end": when_to_json(window.end)}


def window_from_json(data: Json) -> DateRange:
    start, end = when_from_json(data["start"]), when_from_json(data["end"])
    return DateRange(start, end)  # type: ignore[arg-type]


def links_to_json(links: List[Link]) -> List[Json]:
    return [link_to_json(link) for link in sorted(links, key=Link.key)]
