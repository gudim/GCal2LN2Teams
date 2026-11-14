"""
Domino REST API Adapter
Реализует интеграцию с HCL Domino через REST API
"""

import copy
import re

import requests
from typing import List, Dict, Optional, Any
from datetime import date, datetime, time, timedelta, timezone
from urllib.parse import urljoin

from ..sync.event import CalendarEvent, DateRange, System, split_marker
from ..utils.logger import get_logger

logger = get_logger(__name__)

# Сервер отдаёт не больше ~40 событий за запрос, сколько бы ни просили в count
PAGE_SIZE = 100
GONE_STATUSES = {404, 410}
PRIVATE_CLASSES = {"private", "confidential"}
# Поля, которые берём из детального GET: список отдаёт только сводку (x-lotus-summarydataonly)
DETAIL_FIELDS = ("description", "last-modified", "attendees", "organizer", "sequence")
_WEEKDAYS = {"MO": 0, "TU": 1, "WE": 2, "TH": 3, "FR": 4, "SA": 5, "SU": 6}


def _yearly_transition(rule: Dict[str, Any], year: int) -> Optional[datetime]:
    """Момент перехода (местное время) по правилу FREQ=YEARLY;BYMONTH=..;BYDAY=-1SU;BYHOUR=.."""
    parts = dict(p.split("=", 1) for p in rule.get("recurrenceRule", "").split(";") if "=" in p)
    if "BYMONTH" not in parts or "BYDAY" not in parts:
        return None
    month = int(parts["BYMONTH"])
    match = re.fullmatch(r"([+-]?\d+)([A-Z]{2})", parts["BYDAY"])
    if not match:
        return None
    nth, weekday = int(match.group(1)), _WEEKDAYS[match.group(2)]
    if nth > 0:
        day = date(year, month, 1)
        day += timedelta(days=(weekday - day.weekday()) % 7 + 7 * (nth - 1))
    else:
        last = date(year + (month == 12), month % 12 + 1, 1) - timedelta(days=1)
        day = last - timedelta(days=(last.weekday() - weekday) % 7 + 7 * (-nth - 1))
    clock = time(int(parts.get("BYHOUR", 0)), int(parts.get("BYMINUTE", 0)))
    return datetime.combine(day, clock)


def _offset(value: str) -> timedelta:
    sign = -1 if value.startswith("-") else 1
    digits = value.lstrip("+-")
    return sign * timedelta(hours=int(digits[:2]), minutes=int(digits[2:4]))


def _utc_offset(zone: Dict[str, Any], local: datetime) -> timedelta:
    """Смещение зоны из массива timezones ответа Domino (аналог VTIMEZONE) для местного времени."""
    standard, daylight = zone.get("standard"), zone.get("daylight")
    if not daylight:
        return _offset(standard["offsetTo"])
    to_daylight = _yearly_transition(daylight, local.year)
    to_standard = _yearly_transition(standard, local.year)
    if to_daylight is None or to_standard is None:
        return _offset(standard["offsetTo"])
    if to_daylight < to_standard:
        summer = to_daylight <= local < to_standard
    else:  # южное полушарие
        summer = not (to_standard <= local < to_daylight)
    return _offset((daylight if summer else standard)["offsetTo"])


def _parse_domino_when(value: Dict[str, Any], timezones: Optional[Dict[str, Any]] = None):
    day = date.fromisoformat(value["date"])
    if "time" not in value:
        return day
    clock = datetime.strptime(value["time"][:8], "%H:%M:%S").time()
    if value.get("utc"):
        return datetime.combine(day, clock, tzinfo=timezone.utc)
    zone = (timezones or {}).get(value.get("tzid"))
    if zone is None:
        # tzid ссылается на описание зоны из массива timezones ответа; без него время не понять
        raise ValueError(f"Время не в UTC (tzid={value.get('tzid')!r}) без описания зоны")
    local = datetime.combine(day, clock)
    return (local - _utc_offset(zone, local)).replace(tzinfo=timezone.utc)


def _parse_stamp(value: Optional[str]) -> Optional[datetime]:
    """'20261007T050045Z' -> aware datetime (UTC); '20261007' -> полночь UTC."""
    if not value:
        return None
    fmt = "%Y%m%dT%H%M%SZ" if "T" in value else "%Y%m%d"
    return datetime.strptime(value, fmt).replace(tzinfo=timezone.utc)


def _normalize_dn(name: str) -> str:
    """'CN=Ivan Petrov/O=ACME' и 'Ivan Petrov/ACME' -> 'ivan petrov/acme'."""
    parts = [re.sub(r"^[A-Za-z]+=", "", part.strip()) for part in name.split("/")]
    return "/".join(parts).casefold()


def _is_owner(name: Optional[str], email: Optional[str], owner: Optional[Dict[str, Any]]) -> bool:
    if not owner:
        return False
    owner_dn = owner.get("distinguishedName")
    owner_email = owner.get("email")
    if name and owner_dn and _normalize_dn(name) == _normalize_dn(owner_dn):
        return True
    return bool(email and owner_email and email.casefold() == owner_email.casefold())


def event_from_domino(
    raw: Dict[str, Any],
    owner: Optional[Dict[str, Any]] = None,
    timezones: Optional[List[Dict[str, Any]]] = None,
) -> CalendarEvent:
    """Событие Domino Calendar API -> CalendarEvent.

    raw — сводка из списка (время в UTC), дополненная полями детального GET, или сам
    детальный ответ (время с tzid, тогда нужен его массив timezones).
    owner — calendars[0].owner из GET /api/calendar: по нему решаем, моя ли это встреча.
    id экземпляра повторяющегося события = "<uid>/<recurrenceId>": у всех экземпляров общий uid.
    Дата конца all-day события исключающая (так отдаёт реальный сервер 14.5.1);
    конец, равный началу (форма из документации), считаем однодневным событием.
    """
    zones = {zone.get("tzid"): zone for zone in timezones or []}
    title, source = split_marker(raw.get("summary", ""), own=System.DOMINO)
    start = _parse_domino_when(raw["start"], zones)
    end = _parse_domino_when(raw["end"], zones) if "end" in raw else start
    if not isinstance(start, datetime) and end <= start:
        end = start + timedelta(days=1)
    uid = raw.get("id")
    event_id = f"{uid}/{raw['recurrenceId']}" if uid and raw.get("recurrenceId") else uid

    organizer = raw.get("organizer") or {}
    lotus_organizer = (raw.get("x-lotus-organizer") or {}).get("data")
    organizer_name = lotus_organizer or organizer.get("displayName")
    organizer_email = organizer.get("email")
    has_organizer = bool(organizer_name or organizer_email)
    organizer_is_me = not has_organizer or _is_owner(organizer_name, organizer_email, owner)
    # участник, кроме владельца календаря; чужой организатор тоже считается участником
    others = [
        a for a in raw.get("attendees") or []
        if not _is_owner(a.get("displayName"), a.get("email"), owner)
    ]
    has_attendees = bool(others) or (has_organizer and not organizer_is_me)

    return CalendarEvent(
        title=title,
        start=start,
        end=end,
        location=raw.get("location", ""),
        description=(raw.get("description") or "").replace("\r\n", "\n"),
        source=source,
        id=event_id,
        uid=uid,
        original_start=_parse_stamp(raw.get("recurrenceId")),
        organizer_is_me=organizer_is_me,
        has_attendees=has_attendees,
        private=str(raw.get("class", "")).lower() in PRIVATE_CLASSES,
        modified=_parse_stamp(raw.get("last-modified")),
        # x-lotus-unid есть только у документов-уведомлений (приглашение, перенос, отмена),
        # а не у записей календаря; деталей у них нет (404)
        ignored=raw.get("status") == "cancelled" or "x-lotus-unid" in raw,
    )


def _domino_times(event: CalendarEvent) -> Dict[str, Any]:
    if event.all_day:
        # исключающая дата конца, как в ответах сервера (запись не проверена на живом сервере)
        return {"start": {"date": event.start.isoformat()}, "end": {"date": event.end.isoformat()}}

    def when(moment: datetime) -> Dict[str, Any]:
        return {
            "date": moment.strftime("%Y-%m-%d"),
            "time": moment.strftime("%H:%M:%S"),
            "utc": True,
        }

    return {"start": when(event.start), "end": when(event.end)}


def event_to_domino(event: CalendarEvent) -> Dict[str, Any]:
    """CalendarEvent -> событие для POST .../api/calendar/events.

    Скрытой метки у Domino нет: копию узнаём по маркеру в названии и по таблице связей.
    """
    body: Dict[str, Any] = {"summary": event.marked_title(), **_domino_times(event)}
    if event.location:
        body["location"] = event.location
    if event.description:
        body["description"] = event.description
    if event.private:
        body["class"] = "private"
    return body


def _utc_param(moment: datetime) -> str:
    return moment.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _next_start(content_range: Optional[str], offset: int, count: int) -> int:
    """Следующий start: конец Content-Range + 1 (единицы start != число событий)."""
    match = re.search(r"(\d+)-(\d+)", content_range or "")
    if match and int(match.group(2)) + 1 > offset:
        return int(match.group(2)) + 1
    return offset + count


def _merge_multi_day(entries: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Многодневное all-day событие список отдаёт по записи на каждый день с одним href:
    склеиваем их в одно событие от первого до последнего дня."""
    result: List[Dict[str, Any]] = []
    by_href: Dict[str, Dict[str, Any]] = {}
    for raw in entries:
        href = raw.get("href")
        all_day = "time" not in raw.get("start", {"time": None})
        if not href or raw.get("recurrenceId") or not all_day:
            result.append(raw)
            continue
        merged = by_href.get(href)
        if merged is None:
            merged = by_href[href] = dict(raw)
            result.append(merged)
            continue
        if raw["start"]["date"] < merged["start"]["date"]:
            merged["start"] = raw["start"]
        if raw.get("end", raw["start"])["date"] > merged.get("end", merged["start"])["date"]:
            merged["end"] = raw.get("end", raw["start"])
    return result


def _pick(events: List[Dict[str, Any]], event_id: str) -> Optional[Dict[str, Any]]:
    """Событие из детального ответа: GET серии отдаёт мастер + все экземпляры.

    Для id экземпляра (uid/recurrenceId) нужна запись с тем же recurrenceId; если её нет
    (экземпляр удалён или перенесён) — None, а не мастер: иначе чтение или PUT задели бы
    всю серию. Первая запись подходит только для id без recurrenceId.
    """
    recurrence = event_id.split("/", 1)[1] if "/" in event_id else None
    for raw in events:
        if raw.get("recurrenceId") == recurrence:
            return raw
    if recurrence is not None:
        return None
    return events[0] if events else None


class DominoRestAdapter:
    """Адаптер для работы с HCL Domino через REST API"""

    system = System.DOMINO
    
    def __init__(
        self,
        server_url: str,
        username: str,
        password: str,
        verify_ssl: bool = True
    ):
        """
        Инициализация Domino REST адаптера
        
        Args:
            server_url: URL Domino сервера (например, https://domino.example.com)
            username: Имя пользователя
            password: Пароль
            verify_ssl: Проверять SSL сертификат
        """
        self.server_url = server_url.rstrip('/')
        self.username = username
        self.password = password
        self.verify_ssl = verify_ssl
        
        self.session = requests.Session()
        self.session.auth = (username, password)
        self.session.verify = verify_ssl
        
        # Данные календаря
        self.calendar_info = None
        self.events_url = None
        self.invitations_url = None
        
        logger.info(f"Инициализация Domino REST Adapter для сервера: {server_url}")
    
    def connect(self) -> bool:
        """
        Подключение к Domino и получение информации о календаре
        
        Returns:
            bool: True если подключение успешно
        """
        try:
            logger.info("Подключение к Domino REST API...")
            
            # Получаем информацию о календаре
            url = urljoin(self.server_url, '/api/calendar')
            
            response = self.session.get(url, timeout=10)
            response.raise_for_status()
            
            data = response.json()
            
            if 'calendars' not in data or len(data['calendars']) == 0:
                raise RuntimeError("Календарь не найден в ответе API")
            
            # Сохраняем информацию о календаре
            self.calendar_info = data['calendars'][0]
            
            # Извлекаем URLs для событий и приглашений
            for link in self.calendar_info.get('links', []):
                if link['rel'] == 'events':
                    self.events_url = urljoin(self.server_url, link['href'])
                elif link['rel'] == 'invitations':
                    self.invitations_url = urljoin(self.server_url, link['href'])
            
            if not self.events_url:
                raise RuntimeError("URL событий не найден в ответе API")
            
            owner = self.calendar_info.get('owner', {})
            logger.info(f"✅ Подключено к календарю пользователя: {owner.get('displayName')}")
            logger.info(f"   Email: {owner.get('email')}")
            logger.info(f"   Server: {owner.get('homeServer')}")
            logger.info(f"   Events URL: {self.events_url}")
            
            return True
            
        except requests.exceptions.RequestException as e:
            logger.error(f"Ошибка при подключении к Domino: {e}")
            raise RuntimeError(f"Не удалось подключиться к Domino REST API: {e}")
        except Exception as e:
            logger.error(f"Неожиданная ошибка: {e}")
            raise
    
    def disconnect(self):
        """Отключение от Domino"""
        if self.session:
            self.session.close()
        logger.info("✅ Отключение от Domino выполнено")
    
    def test_connection(self) -> Dict[str, Any]:
        """
        Тестирование подключения
        
        Returns:
            Dict с информацией о подключении
        """
        if not self.calendar_info:
            raise RuntimeError("Необходимо сначала вызвать connect()")
        
        owner = self.calendar_info.get('owner', {})
        
        # Пробуем получить события для теста
        try:
            response = self.session.get(
                self.events_url,
                params={'count': 1},
                timeout=10
            )
            response.raise_for_status()
            events_data = response.json()
            event_count = len(events_data.get('events', []))
        except Exception as e:
            logger.warning(f"Не удалось получить количество событий: {e}")
            event_count = 0
        
        return {
            'connected': True,
            'username': owner.get('displayName', 'N/A'),
            'distinguished_name': owner.get('distinguishedName', 'N/A'),
            'email': owner.get('email', 'N/A'),
            'server': owner.get('homeServer', 'N/A'),
            'events_url': self.events_url,
            'invitations_url': self.invitations_url,
            'calendar_entries': event_count,
            'api_type': 'Domino REST API'
        }
    
    def list_events(
        self,
        start_date: Optional[datetime] = None,
        end_date: Optional[datetime] = None,
        limit: int = 100
    ) -> List[Dict[str, Any]]:
        """
        Получить список событий календаря
        
        Args:
            start_date: Начальная дата (опционально)
            end_date: Конечная дата (опционально)
            limit: Максимальное количество событий
            
        Returns:
            Список событий
        """
        if not self.events_url:
            raise RuntimeError("Необходимо сначала вызвать connect()")
        
        try:
            params = {'count': limit}
            
            # Domino REST API не поддерживает фильтрацию по датам через since/before
            # Получаем все события и фильтруем на клиенте если нужно
            
            logger.info(f"Получение событий из Domino (limit={limit})...")
            
            response = self.session.get(
                self.events_url,
                params=params,
                timeout=30
            )
            response.raise_for_status()
            
            data = response.json()
            events = data.get('events', [])
            
            logger.info(f"✅ Получено событий: {len(events)}")
            
            return events
            
        except requests.exceptions.RequestException as e:
            logger.error(f"Ошибка при получении событий: {e}")
            raise RuntimeError(f"Не удалось получить события: {e}")
    
    def get_event(self, event_id: str) -> Dict[str, Any]:
        """
        Получить событие по ID
        
        Args:
            event_id: ID события (recurrenceId или uid)
            
        Returns:
            Dict с данными события
        """
        if not self.events_url:
            raise RuntimeError("Необходимо сначала вызвать connect()")
        
        try:
            # URL для конкретного события
            url = f"{self.events_url}/{event_id}"
            
            response = self.session.get(url, timeout=10)
            response.raise_for_status()
            
            event = response.json()
            logger.info(f"✅ Получено событие: {event_id}")
            
            return event
            
        except requests.exceptions.RequestException as e:
            logger.error(f"Ошибка при получении события {event_id}: {e}")
            raise RuntimeError(f"Не удалось получить событие: {e}")
    
    def update_event(
        self,
        event_id: str,
        subject: Optional[str] = None,
        start_datetime: Optional[datetime] = None,
        end_datetime: Optional[datetime] = None,
        location: Optional[str] = None,
        description: Optional[str] = None,
        **kwargs
    ) -> bool:
        """
        Обновить событие
        
        Args:
            event_id: ID события
            subject: Новая тема (опционально)
            start_datetime: Новая дата начала (опционально)
            end_datetime: Новая дата окончания (опционально)
            location: Новое место (опционально)
            description: Новое описание (опционально)
            **kwargs: Дополнительные поля
            
        Returns:
            bool: True если обновление успешно
        """
        if not self.events_url:
            raise RuntimeError("Необходимо сначала вызвать connect()")
        
        try:
            # Сначала получаем текущее событие
            current_event = self.get_event(event_id)
            
            # Обновляем только указанные поля
            if subject is not None:
                current_event['summary'] = subject
            
            if start_datetime is not None:
                current_event['start'] = {
                    'date': start_datetime.strftime('%Y-%m-%d'),
                    'time': start_datetime.strftime('%H:%M:%S'),
                    'tzid': kwargs.get('timezone', current_event.get('start', {}).get('tzid', 'UTC'))
                }
            
            if end_datetime is not None:
                current_event['end'] = {
                    'date': end_datetime.strftime('%Y-%m-%d'),
                    'time': end_datetime.strftime('%H:%M:%S'),
                    'tzid': kwargs.get('timezone', current_event.get('end', {}).get('tzid', 'UTC'))
                }
            
            if location is not None:
                current_event['location'] = location
            
            if description is not None:
                current_event['description'] = description
            
            # Отправляем обновленное событие
            url = f"{self.events_url}/{event_id}"
            
            response = self.session.put(
                url,
                json=current_event,
                timeout=10
            )
            response.raise_for_status()
            
            logger.info(f"✅ Событие обновлено: {event_id}")
            return True
            
        except requests.exceptions.RequestException as e:
            logger.error(f"Ошибка при обновлении события {event_id}: {e}")
            raise RuntimeError(f"Не удалось обновить событие: {e}")
    
    def delete_event(self, event_id: str) -> bool:
        """
        Удалить событие
        
        Args:
            event_id: ID события
            
        Returns:
            bool: True если удаление успешно
        """
        if not self.events_url:
            raise RuntimeError("Необходимо сначала вызвать connect()")
        
        try:
            url = f"{self.events_url}/{event_id}"
            
            response = self.session.delete(url, timeout=10)
            response.raise_for_status()
            
            logger.info(f"✅ Событие удалено: {event_id}")
            return True
            
        except requests.exceptions.RequestException as e:
            logger.error(f"Ошибка при удалении события {event_id}: {e}")
            raise RuntimeError(f"Не удалось удалить событие: {e}")

    # === Calendar interface (src/sync/calendar.py) ===

    @property
    def owner(self) -> Optional[Dict[str, Any]]:
        """Владелец календаря (calendars[0].owner): distinguishedName, email."""
        return (self.calendar_info or {}).get("owner")

    def _event_url(self, event_id: str) -> str:
        """URL события; для экземпляра серии id = "<uid>/<recurrenceId>", как в его href."""
        if not self.events_url:
            raise RuntimeError("Необходимо сначала вызвать connect()")
        return f"{self.events_url}/{event_id}"

    def _list_summaries(self, window: DateRange) -> List[Dict[str, Any]]:
        """Сводки событий окна со всех страниц.

        Сервер отдаёт не больше ~40 событий за запрос, а Content-Range ("items 0-43") считает
        не события: листаем с конца диапазона + 1 до пустой страницы, а дубли на стыках
        страниц убираем по (href, начало).
        """
        entries: List[Dict[str, Any]] = []
        seen = set()
        offset = 0
        while True:
            response = self.session.get(
                self.events_url,
                params={
                    "since": _utc_param(window.start),
                    "before": _utc_param(window.end),
                    "start": offset,
                    "count": PAGE_SIZE,
                },
                timeout=30,
            )
            response.raise_for_status()
            page = response.json().get("events", []) if response.content else []
            if not page:
                return entries
            for raw in page:
                start = repr(sorted((raw.get("start") or {}).items()))
                key = (raw.get("href") or raw.get("id"), start)
                if key not in seen:
                    seen.add(key)
                    entries.append(raw)
            offset = _next_start(response.headers.get("Content-Range"), offset, len(page))

    def _detail(self, url: str) -> Optional[Dict[str, Any]]:
        """Детальный GET события: {"timezones": [...], "events": [...]}; None, если удалено."""
        response = self.session.get(url, timeout=10)
        if response.status_code in GONE_STATUSES:
            return None
        response.raise_for_status()
        return response.json() if response.content else None

    def list(self, window: DateRange) -> List[CalendarEvent]:
        """Все события окна с описанием и временем изменения.

        Список отдаёт только сводку (без description и last-modified), поэтому для каждого
        события делается ещё один GET по его href: N+1 запросов на окно. Время берём из
        сводки (там UTC), из деталей — только DETAIL_FIELDS (там время с tzid).
        last-modified — время изменения документа: правка одного экземпляра серии сдвигает
        его у всех экземпляров.
        """
        if not self.events_url:
            raise RuntimeError("Необходимо сначала вызвать connect()")
        events: List[CalendarEvent] = []
        for raw in _merge_multi_day(self._list_summaries(window)):
            try:
                event = event_from_domino(raw, owner=self.owner)
            except (KeyError, ValueError) as e:
                logger.error(f"Пропущено событие Domino {raw.get('id')}: {e}")
                continue
            if event.ignored:
                events.append(event)  # отменённое или уведомление: детали не нужны
                continue
            href = raw.get("href")
            url = urljoin(self.server_url, href) if href else self._event_url(event.id or "")
            body = self._detail(url)
            detail = _pick(body.get("events", []), event.id or "") if body else None
            if detail is None:
                # событие (или экземпляр серии) удалили между запросами; без деталей
                # описание было бы пустым, а детали мастера исказили бы экземпляр
                raise RuntimeError(f"Событие Domino {event.id} пропало при чтении деталей")
            merged = {**raw, **{k: detail[k] for k in DETAIL_FIELDS if k in detail}}
            events.append(event_from_domino(merged, owner=self.owner))
        return events

    def create(self, event: CalendarEvent) -> str:
        if not self.events_url:
            raise RuntimeError("Необходимо сначала вызвать connect()")
        body = {"events": [event_to_domino(event)]}
        response = self.session.post(self.events_url, json=body, timeout=10)
        response.raise_for_status()
        created = response.json().get("events", [])
        if not created or not created[0].get("id"):
            raise RuntimeError("ID события не найден в ответе Domino")
        return created[0]["id"]

    def get(self, event_id: str) -> Optional[CalendarEvent]:
        body = self._detail(self._event_url(event_id)) or {}
        raw = _pick(body.get("events", []), event_id)
        if raw is None or raw.get("status") == "cancelled":
            return None
        return event_from_domino(raw, owner=self.owner, timezones=body.get("timezones"))

    def update(self, event_id: str, event: CalendarEvent) -> None:
        """GET деталей -> замена синхронизируемых полей -> PUT того же тела по тому же URL.

        Остальное (sequence, recurrenceId, участники, timezones) уходит как пришло;
        PUT по URL экземпляра меняет только этот экземпляр.
        """
        url = self._event_url(event_id)
        response = self.session.get(url, timeout=10)
        response.raise_for_status()
        body = copy.deepcopy(response.json())
        target = _pick(body.get("events", []), event_id)
        if target is None:
            raise RuntimeError(f"Событие Domino {event_id} не найдено в ответе")
        if len(body["events"]) > 1:
            body["events"] = [target]
        target.update(
            summary=event.marked_title(),
            location=event.location,
            description=event.description,
            **_domino_times(event),
        )
        response = self.session.put(url, json=body, timeout=10)
        response.raise_for_status()

    def delete(self, event_id: str) -> None:
        """DELETE по URL события (для экземпляра — только он). 404/410 — уже удалено."""
        response = self.session.delete(self._event_url(event_id), timeout=10)
        if response.status_code in GONE_STATUSES:
            logger.info(f"Событие Domino {event_id} уже удалено")
            return
        response.raise_for_status()
