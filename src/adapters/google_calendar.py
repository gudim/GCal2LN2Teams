"""
Google Calendar API Adapter
Реализует интеграцию с Google Calendar через OAuth 2.0
"""

import os
import pickle
from typing import List, Dict, Optional, Any
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import InstalledAppFlow
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError

from ..sync.event import CalendarEvent, DateRange, System, split_marker
from ..utils.logger import get_logger

logger = get_logger(__name__)


# Скрытая метка копии: приватные extendedProperties видны только этому календарю
ORIGIN_KEY = "gcalsync_origin"
ORIGIN_ID_KEY = "gcalsync_origin_id"
# Служебные типы событий Google: не копируются. default, outOfOffice, focusTime — копируются.
IGNORED_EVENT_TYPES = {"birthday", "workingLocation", "fromGmail"}
PRIVATE_VISIBILITY = {"private", "confidential"}
GONE_STATUSES = {404, 410}


def _parse_google_when(value: Dict[str, Any]):
    if "dateTime" in value:
        return datetime.fromisoformat(value["dateTime"].replace("Z", "+00:00"))
    return date.fromisoformat(value["date"])


def _parse_rfc3339(value: Optional[str]) -> Optional[datetime]:
    if not value:
        return None
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)


def _hidden_origin(raw: Dict[str, Any]) -> Optional[System]:
    origin = ((raw.get("extendedProperties") or {}).get("private") or {}).get(ORIGIN_KEY)
    try:
        system = System(origin) if origin else None
    except ValueError:
        logger.warning(f"Неизвестная система в скрытой метке события Google {raw.get('id')}: "
                       f"{origin!r}")
        return None
    return system if system != System.GOOGLE else None


def _is_ignored(raw: Dict[str, Any]) -> bool:
    if raw.get("status") == "cancelled":
        return True
    if raw.get("eventType", "default") in IGNORED_EVENT_TYPES:
        return True
    me = next((a for a in raw.get("attendees") or [] if a.get("self")), None)
    return bool(me and me.get("responseStatus") == "declined")


def event_from_google(raw: Dict[str, Any]) -> CalendarEvent:
    """Событие Google Calendar API -> CalendarEvent.

    Копия распознаётся по скрытой метке в extendedProperties.private, а если её нет —
    по маркеру в названии. Отменённый экземпляр серии приходит без start/end:
    берём время из originalStartTime и помечаем событие ignored.
    """
    title, source = split_marker(raw.get("summary", ""), own=System.GOOGLE)
    source = _hidden_origin(raw) or source
    original = raw.get("originalStartTime")
    original_start = _parse_google_when(original) if original else None
    if "start" in raw:
        start, end = _parse_google_when(raw["start"]), _parse_google_when(raw["end"])
    elif raw.get("status") == "cancelled" and original_start is not None:
        start = end = original_start
    else:
        raise KeyError("start")
    attendees = [a for a in raw.get("attendees") or [] if not a.get("self")]
    return CalendarEvent(
        title=title,
        start=start,
        end=end,
        location=raw.get("location", ""),
        description=raw.get("description", ""),
        source=source,
        id=raw.get("id"),
        uid=raw.get("iCalUID"),
        original_start=original_start,
        organizer_is_me=bool((raw.get("organizer") or {"self": True}).get("self")),
        has_attendees=bool(attendees),
        private=raw.get("visibility") in PRIVATE_VISIBILITY,
        modified=_parse_rfc3339(raw.get("updated")),
        ignored=_is_ignored(raw),
    )


def _synced_fields(event: CalendarEvent) -> Dict[str, Any]:
    """Синхронизируемые поля: название, время, место, описание. Конец all-day исключающий."""
    if event.all_day:
        start, end = {"date": event.start.isoformat()}, {"date": event.end.isoformat()}
    else:
        start = {"dateTime": event.start.isoformat(), "timeZone": "UTC"}
        end = {"dateTime": event.end.isoformat(), "timeZone": "UTC"}
    return {
        "summary": event.marked_title(),
        "location": event.location,
        "description": event.description,
        "start": start,
        "end": end,
    }


def event_to_google(event: CalendarEvent) -> Dict[str, Any]:
    """CalendarEvent -> тело запроса events.insert (со скрытой меткой для копии)."""
    body = _synced_fields(event)
    if event.source is not None:
        hidden = {ORIGIN_KEY: event.source.value}
        if event.id:
            hidden[ORIGIN_ID_KEY] = event.id
        body["extendedProperties"] = {"private": hidden}
    if event.private:
        body["visibility"] = "private"
    return body


def _is_gone(error: HttpError) -> bool:
    return getattr(error.resp, "status", None) in GONE_STATUSES


class GoogleCalendarAdapter:
    """Адаптер для работы с Google Calendar API"""

    system = System.GOOGLE
    
    # Необходимые разрешения для работы с календарем
    SCOPES = [
        'https://www.googleapis.com/auth/calendar',
        'https://www.googleapis.com/auth/calendar.events'
    ]
    
    def __init__(
        self,
        credentials_file: str,
        token_file: str,
        calendar_id: str = 'primary'
    ):
        """
        Инициализация адаптера Google Calendar
        
        Args:
            credentials_file: Путь к файлу с credentials от Google Cloud Console
            token_file: Путь для хранения токена авторизации
            calendar_id: ID календаря (по умолчанию 'primary')
        """
        self.credentials_file = credentials_file
        self.token_file = token_file
        self.calendar_id = calendar_id
        self.credentials: Optional[Credentials] = None
        self.service = None
        
        logger.info(f"Инициализация Google Calendar Adapter для календаря: {calendar_id}")
    
    def authenticate(self, force_reauth: bool = False) -> bool:
        """
        Аутентификация в Google Calendar через OAuth 2.0
        
        Args:
            force_reauth: Принудительная повторная авторизация
            
        Returns:
            bool: True если авторизация успешна
        """
        logger.info("Начало процесса аутентификации Google Calendar")
        
        try:
            # Проверяем наличие файла credentials
            if not os.path.exists(self.credentials_file):
                logger.error(f"Файл credentials не найден: {self.credentials_file}")
                raise FileNotFoundError(
                    f"Файл credentials не найден: {self.credentials_file}\n"
                    f"Получите credentials в Google Cloud Console:\n"
                    f"https://console.cloud.google.com/apis/credentials"
                )
            
            # Загружаем существующий токен, если есть
            if os.path.exists(self.token_file) and not force_reauth:
                logger.info(f"Загрузка существующего токена из: {self.token_file}")
                with open(self.token_file, 'rb') as token:
                    self.credentials = pickle.load(token)
                logger.info("Токен успешно загружен")
            
            # Проверяем валидность токена
            if not self.credentials or not self.credentials.valid:
                if self.credentials and self.credentials.expired and self.credentials.refresh_token:
                    # Обновляем токен
                    logger.info("Токен истек, обновление...")
                    self.credentials.refresh(Request())
                    logger.info("Токен успешно обновлен")
                else:
                    # Запускаем новый OAuth flow
                    logger.info("Запуск OAuth 2.0 flow для получения нового токена")
                    logger.info("Откроется браузер для авторизации в Google...")
                    
                    flow = InstalledAppFlow.from_client_secrets_file(
                        self.credentials_file,
                        self.SCOPES
                    )
                    
                    # Запускаем локальный сервер для получения кода авторизации
                    # Браузер откроется автоматически
                    self.credentials = flow.run_local_server(
                        port=0,  # Автоматический выбор свободного порта
                        open_browser=True,  # Открыть браузер автоматически
                        authorization_prompt_message='Откройте браузер для авторизации...',
                        success_message='Авторизация успешна! Можете закрыть это окно.',
                        bind_addr='127.0.0.1'
                    )
                    
                    logger.info("OAuth flow завершен успешно")
                
                # Сохраняем токен для последующего использования
                logger.info(f"Сохранение токена в: {self.token_file}")
                
                # Создаем директорию, если не существует
                token_dir = os.path.dirname(self.token_file)
                if token_dir:
                    os.makedirs(token_dir, exist_ok=True)
                
                with open(self.token_file, 'wb') as token:
                    pickle.dump(self.credentials, token)
                
                logger.info("Токен успешно сохранен")
            else:
                logger.info("Используется существующий валидный токен")
            
            # Создаем сервис для работы с API
            logger.info("Создание сервиса Google Calendar API")
            self.service = build('calendar', 'v3', credentials=self.credentials)
            
            # Проверяем доступ к календарю
            calendar = self.service.calendars().get(calendarId=self.calendar_id).execute()
            logger.info(f"✅ Успешное подключение к календарю: {calendar.get('summary', 'Без названия')}")
            
            return True
            
        except FileNotFoundError as e:
            logger.error(f"Ошибка: {e}")
            raise
        except HttpError as e:
            logger.error(f"HTTP ошибка при аутентификации: {e}")
            raise
        except Exception as e:
            logger.error(f"Неожиданная ошибка при аутентификации: {e}")
            raise
    
    def test_connection(self) -> Dict[str, Any]:
        """
        Тестирование подключения к Google Calendar
        
        Returns:
            Dict с информацией о календаре
        """
        if not self.service:
            raise RuntimeError("Необходимо сначала вызвать authenticate()")
        
        logger.info(f"Тестирование подключения к календарю: {self.calendar_id}")
        
        try:
            # Получаем информацию о календаре
            calendar = self.service.calendars().get(calendarId=self.calendar_id).execute()
            
            # Получаем список последних событий для проверки
            now = datetime.utcnow().isoformat() + 'Z'
            events_result = self.service.events().list(
                calendarId=self.calendar_id,
                timeMin=now,
                maxResults=5,
                singleEvents=True,
                orderBy='startTime'
            ).execute()
            
            events = events_result.get('items', [])
            
            result = {
                'calendar_id': calendar.get('id'),
                'calendar_name': calendar.get('summary', 'Без названия'),
                'timezone': calendar.get('timeZone', 'UTC'),
                'description': calendar.get('description', ''),
                'upcoming_events_count': len(events),
                'status': 'connected'
            }
            
            logger.info(f"✅ Подключение успешно: {result['calendar_name']}")
            logger.info(f"   Временная зона: {result['timezone']}")
            logger.info(f"   Предстоящих событий: {result['upcoming_events_count']}")
            
            return result
            
        except HttpError as e:
            logger.error(f"HTTP ошибка при тестировании подключения: {e}")
            raise
        except Exception as e:
            logger.error(f"Ошибка при тестировании подключения: {e}")
            raise
    
    def get_calendar_info(self) -> Dict[str, Any]:
        """
        Получение информации о календаре
        
        Returns:
            Dict с детальной информацией о календаре
        """
        if not self.service:
            raise RuntimeError("Необходимо сначала вызвать authenticate()")
        
        try:
            calendar = self.service.calendars().get(calendarId=self.calendar_id).execute()
            
            return {
                'id': calendar.get('id'),
                'summary': calendar.get('summary'),
                'description': calendar.get('description'),
                'location': calendar.get('location'),
                'timezone': calendar.get('timeZone'),
                'etag': calendar.get('etag')
            }
        except HttpError as e:
            logger.error(f"Ошибка получения информации о календаре: {e}")
            raise
    
    def list_calendars(self) -> List[Dict[str, Any]]:
        """
        Получение списка всех доступных календарей
        
        Returns:
            List[Dict] со списком календарей
        """
        if not self.service:
            raise RuntimeError("Необходимо сначала вызвать authenticate()")
        
        logger.info("Получение списка всех календарей")
        
        try:
            calendar_list = self.service.calendarList().list().execute()
            calendars = []
            
            for calendar in calendar_list.get('items', []):
                calendars.append({
                    'id': calendar.get('id'),
                    'summary': calendar.get('summary'),
                    'description': calendar.get('description', ''),
                    'timezone': calendar.get('timeZone'),
                    'access_role': calendar.get('accessRole'),
                    'primary': calendar.get('primary', False),
                    'selected': calendar.get('selected', False)
                })
            
            logger.info(f"Найдено календарей: {len(calendars)}")
            return calendars
            
        except HttpError as e:
            logger.error(f"Ошибка получения списка календарей: {e}")
            raise
    
    def create_calendar(self, summary: str) -> Dict[str, Any]:
        """Создать дополнительный календарь (владелец — пользователь) в часовом поясе основного."""
        if not self.service:
            raise RuntimeError("Необходимо сначала вызвать authenticate()")
        primary = self.service.calendars().get(calendarId="primary").execute()
        body = {"summary": summary, "timeZone": primary.get("timeZone", "UTC")}
        created = self.service.calendars().insert(body=body).execute()
        return {
            "id": created["id"],
            "summary": created.get("summary", summary),
            "access_role": "owner",
            "primary": False,
        }

    def list_events(
        self,
        time_min: Optional[str] = None,
        time_max: Optional[str] = None,
        max_results: int = 100,
        single_events: bool = True,
        order_by: str = 'startTime'
    ) -> List[Dict[str, Any]]:
        """
        Получение списка событий из календаря
        
        Args:
            time_min: Минимальное время начала события (RFC3339 timestamp)
            time_max: Максимальное время начала события (RFC3339 timestamp)
            max_results: Максимальное количество событий
            single_events: Разбивать повторяющиеся события на отдельные экземпляры
            order_by: Сортировка ('startTime' или 'updated')
            
        Returns:
            List[Dict]: Список событий
        """
        if not self.service:
            raise RuntimeError("Необходимо сначала вызвать authenticate()")
        
        logger.info(f"Получение событий из календаря {self.calendar_id}")
        
        try:
            # Если время не указано, берем следующие 30 дней
            if not time_min:
                time_min = datetime.utcnow().isoformat() + 'Z'
            if not time_max:
                end_date = datetime.utcnow() + timedelta(days=30)
                time_max = end_date.isoformat() + 'Z'
            
            logger.info(f"Период: {time_min} - {time_max}")
            
            events_result = self.service.events().list(
                calendarId=self.calendar_id,
                timeMin=time_min,
                timeMax=time_max,
                maxResults=max_results,
                singleEvents=single_events,
                orderBy=order_by
            ).execute()
            
            events = events_result.get('items', [])
            logger.info(f"Найдено событий: {len(events)}")
            
            return events
            
        except HttpError as e:
            logger.error(f"Ошибка получения событий: {e}")
            raise
    
    def revoke_access(self) -> bool:
        """
        Отзыв доступа и удаление токена
        
        Returns:
            bool: True если успешно
        """
        logger.info("Отзыв доступа к Google Calendar")
        
        try:
            if self.credentials:
                # Отзываем токен
                self.credentials.revoke(Request())
                logger.info("Токен отозван")
            
            # Удаляем файл токена
            if os.path.exists(self.token_file):
                os.remove(self.token_file)
                logger.info(f"Файл токена удален: {self.token_file}")
            
            self.credentials = None
            self.service = None
            
            logger.info("✅ Доступ успешно отозван")
            return True
            
        except Exception as e:
            logger.error(f"Ошибка при отзыве доступа: {e}")
            return False
    
    def is_authenticated(self) -> bool:
        """
        Проверка, аутентифицирован ли пользователь
        
        Returns:
            bool: True если аутентифицирован
        """
        return (
            self.credentials is not None and 
            self.credentials.valid and 
            self.service is not None
        )

    # === Calendar interface (src/sync/calendar.py) ===

    def list(self, window: DateRange) -> List[CalendarEvent]:
        """Все события в окне, со всех страниц. Повторяющиеся развёрнуты в экземпляры."""
        if not self.service:
            raise RuntimeError("Необходимо сначала вызвать authenticate()")
        events: List[CalendarEvent] = []
        page_token = None
        while True:
            response = self.service.events().list(
                calendarId=self.calendar_id,
                timeMin=window.start.isoformat(),
                timeMax=window.end.isoformat(),
                singleEvents=True,
                maxResults=250,
                pageToken=page_token,
            ).execute()
            for raw in response.get("items", []):
                try:
                    events.append(event_from_google(raw))
                except (KeyError, ValueError) as e:
                    logger.error(f"Пропущено событие Google {raw.get('id')}: {e}")
            page_token = response.get("nextPageToken")
            if not page_token:
                return events

    def create(self, event: CalendarEvent) -> str:
        if not self.service:
            raise RuntimeError("Необходимо сначала вызвать authenticate()")
        created = self.service.events().insert(
            calendarId=self.calendar_id, body=event_to_google(event)
        ).execute()
        return created["id"]


    def get(self, event_id: str) -> Optional[CalendarEvent]:
        if not self.service:
            raise RuntimeError("Необходимо сначала вызвать authenticate()")
        try:
            raw = self.service.events().get(calendarId=self.calendar_id, eventId=event_id).execute()
        except HttpError as e:
            if _is_gone(e):
                return None
            raise
        if raw.get("status") == "cancelled":
            return None
        return event_from_google(raw)

    def update(self, event_id: str, event: CalendarEvent) -> None:
        """patch только синхронизируемых полей; участникам письма не рассылаются."""
        if not self.service:
            raise RuntimeError("Необходимо сначала вызвать authenticate()")
        self.service.events().patch(
            calendarId=self.calendar_id,
            eventId=event_id,
            body=_synced_fields(event),
            sendUpdates="none",
        ).execute()

    def delete(self, event_id: str) -> None:
        if not self.service:
            raise RuntimeError("Необходимо сначала вызвать authenticate()")
        try:
            self.service.events().delete(
                calendarId=self.calendar_id, eventId=event_id, sendUpdates="none"
            ).execute()
        except HttpError as e:
            if not _is_gone(e):
                raise
            logger.info(f"Событие Google {event_id} уже удалено")
