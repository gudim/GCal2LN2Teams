"""
Microsoft Teams Calendar Adapter using MS Graph API with OAuth 2.0
Использует Delegated Permissions для доступа к календарю конкретного пользователя
"""
import json
import os
import pickle
import re
import uuid
from html.parser import HTMLParser
from typing import List, Dict, Any, Optional
from datetime import date, datetime, timedelta, timezone
import msal
import requests
from pathlib import Path

from ..sync.event import CalendarEvent, DateRange, System, split_marker
from ..utils.logger import get_logger

logger = get_logger(__name__)

# Время в UTC, описание текстом, а id неизменяемые (не меняются при переносе между папками)
GRAPH_PREFER = 'outlook.timezone="UTC", outlook.body-content-type="text", IdType="ImmutableId"'
GRAPH_SELECT = (
    "id,subject,body,bodyPreview,location,start,end,isAllDay,isCancelled,iCalUId,type,"
    "originalStart,isOrganizer,attendees,sensitivity,lastModifiedDateTime,responseStatus"
)
# Скрытая метка копии: именованные extended properties в собственном пространстве имён
_PROP_NAMESPACE = "{66f5a359-4659-4830-9070-00047ec6ac6e}"
ORIGIN_PROP = f"String {_PROP_NAMESPACE} Name gcalsync_origin"
ORIGIN_ID_PROP = f"String {_PROP_NAMESPACE} Name gcalsync_origin_id"
GRAPH_EXPAND = (
    f"singleValueExtendedProperties($filter=id eq '{ORIGIN_PROP}' or id eq '{ORIGIN_ID_PROP}')"
)
PRIVATE_SENSITIVITY = {"private", "confidential"}
GONE_STATUSES = {404, 410}
# Пространство имён для детерминированного transactionId (повторный POST не создаст дубль)
_TRANSACTION_NAMESPACE = uuid.UUID("66f5a359-4659-4830-9070-00047ec6ac6e")


class _HtmlText(HTMLParser):
    """Минимальное преобразование HTML-тела письма в текст."""

    BLOCKS = {"br", "p", "div", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6"}
    SKIP = {"style", "script", "head", "title"}

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: List[str] = []
        self._skip = 0

    def handle_starttag(self, tag: str, attrs: Any) -> None:
        if tag in self.SKIP:
            self._skip += 1
        elif tag in self.BLOCKS:
            self.parts.append("\n")

    def handle_endtag(self, tag: str) -> None:
        if tag in self.SKIP:
            self._skip = max(0, self._skip - 1)
        elif tag in self.BLOCKS and tag != "br":
            self.parts.append("\n")

    def handle_data(self, data: str) -> None:
        if not self._skip:
            self.parts.append(data)


def html_to_text(content: str) -> str:
    parser = _HtmlText()
    parser.feed(content)
    parser.close()
    text = "".join(parser.parts).replace("\xa0", " ")
    lines = [" ".join(line.split()) for line in text.splitlines()]
    return "\n".join(line for line in lines if line)


def _parse_graph_when(value: Dict[str, Any], all_day: bool):
    if value.get("timeZone") != "UTC":
        raise ValueError(f"Ожидалось время в UTC, получено {value.get('timeZone')!r}")
    stamp = value["dateTime"][:19]  # Graph отдаёт 7 знаков дробной части
    if all_day:
        return date.fromisoformat(stamp[:10])
    return datetime.fromisoformat(stamp).replace(tzinfo=timezone.utc)


def _parse_offset_stamp(value: Optional[str]) -> Optional[datetime]:
    """'2026-11-10T08:15:30.1234567Z' -> aware datetime (дробная часть до микросекунд)."""
    if not value:
        return None
    match = re.match(r"^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d+))?(Z|[+-]\d\d:\d\d)?$", value)
    if not match:
        raise ValueError(f"Неожиданный формат времени Graph: {value!r}")
    stamp, fraction, offset = match.groups()
    if fraction:
        stamp += "." + fraction[:6].ljust(6, "0")
    offset = "+00:00" if offset in (None, "Z") else offset
    return datetime.fromisoformat(stamp + offset).astimezone(timezone.utc)


def _extended(raw: Dict[str, Any]) -> Dict[str, str]:
    return {p.get("id"): p.get("value") for p in raw.get("singleValueExtendedProperties") or []}


def _hidden_origin(raw: Dict[str, Any]) -> Optional[System]:
    origin = _extended(raw).get(ORIGIN_PROP)
    try:
        system = System(origin) if origin else None
    except ValueError:
        logger.warning(f"Неизвестная система в скрытой метке события Teams {raw.get('id')}: "
                       f"{origin!r}")
        return None
    return system if system != System.TEAMS else None


def _description(raw: Dict[str, Any]) -> str:
    body = raw.get("body") or {}
    if body.get("contentType") == "text":
        return (body.get("content") or "").strip()
    if body.get("contentType") == "html":
        return html_to_text(body.get("content") or "")
    return (raw.get("bodyPreview") or "").strip()


def event_from_graph(raw: Dict[str, Any]) -> CalendarEvent:
    """Событие MS Graph (calendarView, Prefer outlook.timezone="UTC") -> CalendarEvent.

    Копия распознаётся по скрытой метке (singleValueExtendedProperties), иначе — по маркеру.
    """
    title, source = split_marker(raw.get("subject") or "", own=System.TEAMS)
    source = _hidden_origin(raw) or source
    all_day = bool(raw.get("isAllDay"))
    declined = (raw.get("responseStatus") or {}).get("response") == "declined"
    original = None
    if raw.get("type") != "singleInstance":  # occurrence / exception: исходное начало экземпляра
        original = _parse_offset_stamp(raw.get("originalStart"))
    return CalendarEvent(
        title=title,
        start=_parse_graph_when(raw["start"], all_day),
        end=_parse_graph_when(raw["end"], all_day),
        location=(raw.get("location") or {}).get("displayName") or "",
        description=_description(raw),
        source=source,
        id=raw.get("id"),
        uid=raw.get("iCalUId"),
        original_start=original,
        organizer_is_me=bool(raw.get("isOrganizer", True)),
        has_attendees=bool(raw.get("attendees")),
        private=raw.get("sensitivity") in PRIVATE_SENSITIVITY,
        modified=_parse_offset_stamp(raw.get("lastModifiedDateTime")),
        ignored=bool(raw.get("isCancelled")) or declined,
    )


def _synced_fields(event: CalendarEvent) -> Dict[str, Any]:
    """Синхронизируемые поля (для POST и PATCH). Описание пишется простым текстом."""
    def when(value) -> Dict[str, str]:
        if event.all_day:
            return {"dateTime": f"{value.isoformat()}T00:00:00", "timeZone": "UTC"}
        utc = value.astimezone(timezone.utc).replace(tzinfo=None)
        return {"dateTime": utc.isoformat(), "timeZone": "UTC"}

    return {
        "subject": event.marked_title(),
        "start": when(event.start),
        "end": when(event.end),
        "isAllDay": event.all_day,
        "location": {"displayName": event.location},
        "body": {"contentType": "text", "content": event.description},
    }


def event_to_graph(event: CalendarEvent) -> Dict[str, Any]:
    """CalendarEvent -> тело POST /me/events (со скрытой меткой и transactionId для копии)."""
    body = _synced_fields(event)
    if event.source is not None:
        props = [{"id": ORIGIN_PROP, "value": event.source.value}]
        if event.id:
            props.append({"id": ORIGIN_ID_PROP, "value": event.id})
            key = f"{event.source.value}|{event.id}|{System.TEAMS.value}"
            body["transactionId"] = str(uuid.uuid5(_TRANSACTION_NAMESPACE, key))
        body["singleValueExtendedProperties"] = props
    if event.private:
        body["sensitivity"] = "private"
    return body


def _is_gone(error: requests.HTTPError) -> bool:
    return getattr(error.response, "status_code", None) in GONE_STATUSES


class MSTeamsCalendarAdapter:
    """Адаптер для работы с Microsoft Teams Calendar через MS Graph API с OAuth 2.0"""

    system = System.TEAMS
    
    GRAPH_API_ENDPOINT = "https://graph.microsoft.com/v1.0"
    # Для Single Tenant используем organizations
    # Для Multitenant было бы: /common
    AUTHORITY = "https://login.microsoftonline.com/organizations"
    
    # Delegated permissions - доступ от имени пользователя
    SCOPES = [
        "Calendars.ReadWrite",
        "User.Read"
    ]
    
    def __init__(
        self,
        client_id: str,
        client_secret: str,
        redirect_uri: str = "http://localhost:8000/api/teams/callback",
        token_file: str = "data/teams_token.pickle"
    ):
        """
        Инициализация адаптера MS Teams Calendar с OAuth 2.0
        
        Args:
            client_id: Azure AD Application (client) ID
            client_secret: Azure AD Application client secret (optional для Public Client)
            redirect_uri: URL для редиректа после авторизации
            token_file: Путь к файлу для сохранения токена
        """
        self.client_id = client_id
        self.client_secret = client_secret
        self.redirect_uri = redirect_uri
        self.token_file = token_file
        
        self.access_token = None
        self.refresh_token = None
        self.token_expiry = None
        
        # Создаем MSAL приложение
        self.app = msal.ConfidentialClientApplication(
            client_id=self.client_id,
            client_credential=self.client_secret,
            authority=self.AUTHORITY
        )
        
        logger.info("Инициализация MS Teams Calendar Adapter (OAuth 2.0)")
    
    def get_authorization_url(self) -> str:
        """
        Получить URL для авторизации пользователя
        
        Returns:
            URL для редиректа пользователя
        """
        auth_url = self.app.get_authorization_request_url(
            scopes=self.SCOPES,
            redirect_uri=self.redirect_uri
        )
        logger.info(f"Authorization URL создан: {auth_url}")
        return auth_url
    
    def authenticate(self, force_reauth: bool = False) -> bool:
        """
        Аутентификация через OAuth 2.0
        
        Args:
            force_reauth: Принудительная повторная авторизация
            
        Returns:
            bool: True если успешно
        """
        try:
            logger.info("Начало аутентификации MS Teams Calendar (OAuth 2.0)")
            
            # Пытаемся загрузить существующий токен
            if not force_reauth and os.path.exists(self.token_file):
                logger.info(f"Загрузка существующего токена из: {self.token_file}")
                token_data = self._load_token()
                
                if token_data:
                    self.access_token = token_data.get("access_token")
                    self.refresh_token = token_data.get("refresh_token")
                    expires_in = token_data.get("expires_in", 3600)
                    
                    # Проверяем срок действия
                    if "token_expiry" in token_data:
                        self.token_expiry = datetime.fromisoformat(token_data["token_expiry"])
                        
                        if datetime.now() < self.token_expiry:
                            logger.info("✅ Используется существующий валидный токен")
                            return True
                    
                    # Токен истек - пробуем обновить через refresh_token
                    if self.refresh_token:
                        logger.info("Токен истек, попытка обновления через refresh_token...")
                        return self._refresh_access_token()
            
            # Если токена нет или force_reauth - нужна авторизация через браузер
            logger.warning("❗ Требуется авторизация пользователя через браузер")
            logger.info("Используйте веб-интерфейс для авторизации Teams")
            return False
            
        except Exception as e:
            logger.exception("Ошибка при аутентификации MS Teams")
            return False
    
    def complete_authorization(self, authorization_code: str) -> bool:
        """
        Завершить авторизацию после получения кода от Microsoft
        
        Args:
            authorization_code: Код авторизации из redirect URL
            
        Returns:
            bool: True если успешно
        """
        try:
            logger.info("Обмен authorization_code на access_token...")
            
            result = self.app.acquire_token_by_authorization_code(
                code=authorization_code,
                scopes=self.SCOPES,
                redirect_uri=self.redirect_uri
            )
            
            if "access_token" in result:
                self.access_token = result["access_token"]
                self.refresh_token = result.get("refresh_token")
                expires_in = result.get("expires_in", 3600)
                self.token_expiry = datetime.now() + timedelta(seconds=expires_in)
                
                logger.info("✅ Успешная авторизация MS Teams")
                
                # Сохраняем токен
                self._save_token(result)
                
                return True
            else:
                error = result.get("error")
                error_desc = result.get("error_description")
                logger.error(f"❌ Ошибка получения токена: {error} - {error_desc}")
                return False
                
        except Exception as e:
            logger.exception("Ошибка при завершении авторизации MS Teams")
            return False
    
    def _refresh_access_token(self) -> bool:
        """Обновить access token используя refresh token"""
        try:
            logger.info("Обновление access token через refresh token...")
            
            result = self.app.acquire_token_by_refresh_token(
                refresh_token=self.refresh_token,
                scopes=self.SCOPES
            )
            
            if "access_token" in result:
                self.access_token = result["access_token"]
                self.refresh_token = result.get("refresh_token", self.refresh_token)
                expires_in = result.get("expires_in", 3600)
                self.token_expiry = datetime.now() + timedelta(seconds=expires_in)
                
                logger.info("✅ Токен успешно обновлен")
                
                # Сохраняем обновленный токен
                self._save_token(result)
                
                return True
            else:
                logger.error("❌ Не удалось обновить токен")
                return False
                
        except Exception as e:
            logger.exception("Ошибка обновления токена")
            return False
    
    def _save_token(self, token_data: Dict[str, Any]):
        """Сохранить токен в файл"""
        try:
            # Добавляем время истечения для удобства
            token_data["token_expiry"] = self.token_expiry.isoformat() if self.token_expiry else None
            
            # Создаем директорию если нужно
            token_path = Path(self.token_file)
            token_path.parent.mkdir(parents=True, exist_ok=True)
            
            # Сохраняем в pickle (как Google)
            with open(self.token_file, 'wb') as f:
                pickle.dump(token_data, f)
            
            logger.info(f"Токен сохранен в {self.token_file}")
        except Exception as e:
            logger.error(f"Ошибка сохранения токена: {e}")
    
    def _load_token(self) -> Optional[Dict[str, Any]]:
        """Загрузить токен из файла"""
        try:
            with open(self.token_file, 'rb') as f:
                return pickle.load(f)
        except FileNotFoundError:
            return None
        except Exception as e:
            logger.error(f"Ошибка загрузки токена: {e}")
            return None
    
    def _ensure_authenticated(self):
        """Проверить и обновить токен если нужно"""
        if not self.access_token or (self.token_expiry and datetime.now() >= self.token_expiry):
            logger.info("Токен истек или отсутствует, выполняем повторную аутентификацию")
            if not self.authenticate():
                raise Exception("Требуется авторизация. Откройте веб-интерфейс для авторизации Teams")
    
    def _make_request(
        self,
        method: str,
        endpoint: str,
        data: Optional[Dict] = None,
        params: Optional[Dict] = None,
        headers: Optional[Dict[str, str]] = None
    ) -> Dict[str, Any]:
        """
        Выполнить HTTP запрос к MS Graph API
        
        Args:
            method: HTTP метод (GET, POST, etc.)
            endpoint: Endpoint относительно GRAPH_API_ENDPOINT
            data: JSON данные для POST/PATCH запросов
            params: Query параметры
            
        Returns:
            Dict с ответом от API
        """
        self._ensure_authenticated()
        
        url = f"{self.GRAPH_API_ENDPOINT}{endpoint}"
        headers = {
            "Authorization": f"Bearer {self.access_token}",
            "Content-Type": "application/json",
            **(headers or {})
        }
        
        response = requests.request(
            method=method,
            url=url,
            headers=headers,
            json=data,
            params=params
        )
        
        response.raise_for_status()
        
        if response.content:
            return response.json()
        return {}
    
    def test_connection(self) -> Dict[str, Any]:
        """
        Тестирование подключения к MS Teams Calendar
        
        Returns:
            Dict с информацией о пользователе и календаре
        """
        try:
            logger.info("Тестирование подключения к MS Teams Calendar...")
            
            self._ensure_authenticated()
            
            # Получаем информацию о пользователе
            user_info = self._make_request("GET", "/me")
            
            # Получаем основной календарь
            calendar_info = self._make_request("GET", "/me/calendar")
            
            info = {
                "user": user_info.get("userPrincipalName", "Unknown"),
                "display_name": user_info.get("displayName", "Unknown"),
                "calendar_id": calendar_info.get("id", "Unknown"),
                "calendar_name": calendar_info.get("name", "Calendar"),
                "status": "connected"
            }
            
            logger.info(f"✅ Подключено к календарю: {info['user']}")
            return info
            
        except Exception as e:
            logger.exception("Ошибка тестирования MS Teams подключения")
            raise
    
    # === Calendar interface (src/sync/calendar.py) ===
    # Все запросы календаря идут с GRAPH_PREFER: UTC, текстовое тело и неизменяемые id.

    def list(self, window: DateRange) -> List[CalendarEvent]:
        """Все события окна через calendarView (повторяющиеся развёрнуты), все страницы.

        Отменённые и отклонённые возвращаются с ignored=True: движок удалит их копии.
        """
        endpoint: Optional[str] = "/me/calendarView"
        params: Optional[Dict[str, Any]] = {
            "startDateTime": window.start.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "endDateTime": window.end.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "$select": GRAPH_SELECT,
            "$expand": GRAPH_EXPAND,
            "$top": 100,
        }
        events: List[CalendarEvent] = []
        while endpoint:
            response = self._make_request(
                "GET", endpoint, params=params, headers={"Prefer": GRAPH_PREFER}
            )
            for raw in response.get("value", []):
                try:
                    events.append(event_from_graph(raw))
                except (KeyError, ValueError) as e:
                    logger.error(f"Пропущено событие Teams {raw.get('id')}: {e}")
            next_link = response.get("@odata.nextLink")
            endpoint = next_link[len(self.GRAPH_API_ENDPOINT):] if next_link else None
            params = None  # nextLink уже содержит все параметры
        return events

    def create(self, event: CalendarEvent) -> str:
        created = self._make_request(
            "POST", "/me/events", data=event_to_graph(event), headers={"Prefer": GRAPH_PREFER}
        )
        return created["id"]

    def get(self, event_id: str) -> Optional[CalendarEvent]:
        try:
            raw = self._make_request(
                "GET",
                f"/me/events/{event_id}",
                params={"$select": GRAPH_SELECT, "$expand": GRAPH_EXPAND},
                headers={"Prefer": GRAPH_PREFER},
            )
        except requests.HTTPError as e:
            if _is_gone(e):
                return None
            raise
        if raw.get("isCancelled"):
            return None
        return event_from_graph(raw)

    def update(self, event_id: str, event: CalendarEvent) -> None:
        """PATCH синхронизируемых полей.

        Graph не умеет править встречу организатора без рассылки обновлений участникам:
        движок не должен вызывать update для оригиналов с has_attendees.
        """
        self._make_request(
            "PATCH",
            f"/me/events/{event_id}",
            data=_synced_fields(event),
            headers={"Prefer": GRAPH_PREFER},
        )

    def delete(self, event_id: str) -> None:
        try:
            self._make_request("DELETE", f"/me/events/{event_id}", headers={"Prefer": GRAPH_PREFER})
        except requests.HTTPError as e:
            if not _is_gone(e):
                raise
            logger.info(f"Событие Teams {event_id} уже удалено")
