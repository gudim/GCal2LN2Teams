"""
Единая модель события, которую адаптеры отдают движку синхронизации.

Адаптер обязан на своей стороне:
- убрать маркер [GC]/[LN]/[MT] из названия и указать систему-источник в `source`;
- привести время к aware datetime (движок хранит его в UTC);
- для событий "весь день" использовать `date`, а не datetime.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timezone
from enum import Enum
from typing import Dict, Optional, Tuple, Union


class System(str, Enum):
    GOOGLE = "google"
    DOMINO = "domino"
    TEAMS = "teams"


MARKERS = {System.GOOGLE: "[GC]", System.DOMINO: "[LN]", System.TEAMS: "[MT]"}

When = Union[datetime, date]


def _normalize_title(title: str) -> str:
    return " ".join(title.split())


@dataclass(frozen=True)
class CalendarEvent:
    """Событие календаря. Время timed-событий хранится в UTC."""

    title: str
    start: When
    end: When
    location: str = ""
    description: str = ""
    source: Optional[System] = None  # система, из которой событие скопировано; None = родное
    id: Optional[str] = None
    # iCalendar UID, общий для одной встречи в разных системах (для серии — общий для экземпляров)
    uid: Optional[str] = None
    # исходное начало экземпляра повторяющейся встречи (RECURRENCE-ID); None для одиночных
    original_start: Optional[When] = None
    organizer_is_me: bool = True  # False = чужая встреча: синхронизатор её только читает
    has_attendees: bool = False  # правка такого оригинала разошлёт письма участникам
    private: bool = False  # копия частного события тоже частная
    modified: Optional[datetime] = None  # время последнего изменения на сервере (UTC)
    # служебное / отклонённое / отменённое: не копируется, существующие копии удаляются
    ignored: bool = False

    def __post_init__(self) -> None:
        start_timed = isinstance(self.start, datetime)
        end_timed = isinstance(self.end, datetime)
        if start_timed != end_timed:
            raise ValueError("start и end должны быть оба datetime или оба date")
        if start_timed:
            for value in (self.start, self.end):
                if value.tzinfo is None:  # type: ignore[union-attr]
                    raise ValueError(f"Время без часового пояса: {value}")
            object.__setattr__(self, "start", self.start.astimezone(timezone.utc))  # type: ignore[union-attr]
            object.__setattr__(self, "end", self.end.astimezone(timezone.utc))  # type: ignore[union-attr]
        object.__setattr__(self, "title", _normalize_title(self.title))

    @property
    def all_day(self) -> bool:
        return not isinstance(self.start, datetime)

    def match_key(self) -> Tuple[str, When]:
        """Ключ для распознавания копии: название + момент начала (UTC) или дата."""
        return (self.title, self.start)

    def twin_key(self) -> Tuple[str, When]:
        """Ключ кандидата в двойники: название без учёта регистра + начало."""
        return (self.title.casefold(), self.start)

    def time_key(self) -> Optional[Tuple[datetime, datetime]]:
        """Ключ кандидата в двойники по времени: начало + конец. У событий на весь день нет."""
        if self.all_day:
            return None
        return (self.start, self.end)  # type: ignore[return-value]

    def uid_key(self) -> Optional[Tuple[str, When]]:
        """Ключ двойника по UID: UID + исходное начало экземпляра (или начало)."""
        if not self.uid:
            return None
        return (self.uid, self.original_start if self.original_start is not None else self.start)

    def fields(self) -> Dict[str, str]:
        """Синхронизируемые поля в нормализованном виде — для снимка и сравнения."""
        return {
            "title": self.title,
            "start": self.start.isoformat(),
            "end": self.end.isoformat(),
            "location": " ".join(self.location.split()),
            "description": "\n".join(line.rstrip() for line in self.description.strip().splitlines()),
        }

    def marked_title(self) -> str:
        """Название с маркером системы-источника, как его видит пользователь."""
        if self.source is None:
            return self.title
        return f"{MARKERS[self.source]} {self.title}"


def split_marker(raw_title: str, own: System) -> Tuple[str, Optional[System]]:
    """Отделить маркер чужой системы от названия.

    Маркер собственной системы не считается признаком копии.
    """
    title = raw_title.strip()
    for system, marker in MARKERS.items():
        if system != own and title.startswith(marker):
            return _normalize_title(title[len(marker):]), system
    return _normalize_title(title), None


@dataclass(frozen=True)
class DateRange:
    """Окно синхронизации [start, end). Границы — aware datetime."""

    start: datetime
    end: datetime

    def overlaps(self, event: CalendarEvent) -> bool:
        if event.all_day:
            return event.start < self.end.date() and event.end > self.start.date()  # type: ignore[operator]
        return event.start < self.end and event.end > self.start  # type: ignore[operator]
