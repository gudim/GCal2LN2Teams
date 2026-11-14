"""
Интерфейс календаря для движка синхронизации и его in-memory реализация для тестов.
"""
from __future__ import annotations

from dataclasses import replace
from typing import Iterable, List, Optional

try:
    from typing import Protocol
except ImportError:  # Python 3.7
    from typing_extensions import Protocol  # type: ignore

from .event import CalendarEvent, DateRange, System


class Calendar(Protocol):
    """Календарь одной системы.

    `list` возвращает все события, пересекающие окно (с учётом пагинации), с заполненными
    description и modified. Копии распознаются по скрытой метке или маркеру в названии
    и возвращаются с `source`.
    `create` создаёт событие (маркер и скрытую метку добавляет адаптер по `event.source`)
    и возвращает его id.
    `get` возвращает событие по id или None, если оно удалено/отменено (404, cancelled).
    `update` переписывает синхронизируемые поля (название, время, место, описание)
    без рассылки писем участникам, где API это позволяет.
    `delete` удаляет событие; уже удалённое — не ошибка.
    """

    system: System

    def list(self, window: DateRange) -> List[CalendarEvent]:
        ...

    def create(self, event: CalendarEvent) -> str:
        ...

    def get(self, event_id: str) -> Optional[CalendarEvent]:
        ...

    def update(self, event_id: str, event: CalendarEvent) -> None:
        ...

    def delete(self, event_id: str) -> None:
        ...


class InMemoryCalendar:
    """Календарь в памяти: для тестов и dry-run экспериментов."""

    def __init__(self, system: System, events: Iterable[CalendarEvent] = ()) -> None:
        self.system = system
        self.events: List[CalendarEvent] = []
        self._next = 0
        for event in events:
            self.create(event)

    def list(self, window: DateRange) -> List[CalendarEvent]:
        return [e for e in self.events if window.overlaps(e)]

    def create(self, event: CalendarEvent) -> str:
        self._next += 1
        event_id = event.id or f"{self.system.value}-{self._next}"
        self.events.append(replace(event, id=event_id))
        return event_id

    def get(self, event_id: str) -> Optional[CalendarEvent]:
        return next((e for e in self.events if e.id == event_id), None)

    def update(self, event_id: str, event: CalendarEvent) -> None:
        for i, current in enumerate(self.events):
            if current.id == event_id:
                self.events[i] = replace(
                    current,
                    title=event.title,
                    start=event.start,
                    end=event.end,
                    location=event.location,
                    description=event.description,
                )
                return
        raise KeyError(event_id)

    def delete(self, event_id: str) -> None:
        self.events = [e for e in self.events if e.id != event_id]
