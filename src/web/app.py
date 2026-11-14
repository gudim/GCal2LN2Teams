"""
FastAPI Web Application for Google Calendar to Domino Sync
"""
import os
from typing import Dict, Any, List, Optional
from pathlib import Path

from fastapi import FastAPI, Request, Depends, HTTPException
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel
from sqlalchemy.orm import Session

from ..database import Config, get_db, init_db, EncryptionService
from ..adapters.google_calendar import GoogleCalendarAdapter
from ..adapters.domino_rest import DominoRestAdapter
from ..adapters.ms_teams_calendar import MSTeamsCalendarAdapter
from ..sync.event import DateRange, System
from ..sync.links import DbLinkStore, google_copies, load_links
from ..sync.planner import COUNTERS, DIRECTIONS, Action, apply, collect, plan, select
from ..utils.logger import get_logger

logger = get_logger(__name__)

# Инициализация FastAPI
app = FastAPI(
    title="Google Calendar to Domino Sync",
    description="Web interface for calendar synchronization",
    version="1.0.0"
)

# Инициализация БД
init_db()

# Сервис шифрования
encryption = EncryptionService()

# Настройка шаблонов и статических файлов
BASE_DIR = Path(__file__).resolve().parent
templates = Jinja2Templates(directory=str(BASE_DIR / "templates"))
app.mount("/static", StaticFiles(directory=str(BASE_DIR / "static")), name="static")


# === Pydantic Models ===

class GoogleConfig(BaseModel):
    credentials_file: str
    calendar_id: str = "primary"


class CreateCalendarRequest(BaseModel):
    summary: str


class SwitchCalendarRequest(BaseModel):
    calendar_id: str
    delete_old: bool = True  # удалить копии синхронизатора из прежнего календаря


class DominoConfig(BaseModel):
    server_url: str
    username: str
    password: Optional[str] = None  # пусто = оставить сохранённый
    verify_ssl: bool = False


class TeamsConfig(BaseModel):
    client_id: str
    client_secret: Optional[str] = None  # пусто = оставить сохранённый
    # tenant_id не нужен - используем /common для любого Microsoft аккаунта


class SyncRequest(BaseModel):
    direction: str = "all"  # all, google-domino, google-teams, domino-teams
    days_back: int = 1  # Сколько дней назад от сегодня
    days_forward: int = 30  # Сколько дней вперед от сегодня
    dry_run: bool = False
    enable_google: bool = True  # Включить синхронизацию с Google
    enable_domino: bool = True  # Включить синхронизацию с Domino
    enable_teams: bool = False  # Включить синхронизацию с Teams (по умолчанию выкл)
    routes: Optional[List[str]] = None  # Маршруты "domino>google"; None = все пары из direction
    # Ключи действий, отмеченных в предпросмотре; None = выбранные по умолчанию.
    # Не отмеченные вопросы (удалить оригинал? двойник?) и создания копий при переданных keys
    # запоминаются как отказ. Ключ из refused (dry run) в keys — снова создать копию.
    keys: Optional[List[str]] = None


# === Helper Functions ===

def get_config_value(db: Session, key: str, decrypt: bool = False) -> Optional[str]:
    """Получить значение конфигурации"""
    config = db.query(Config).filter(Config.key == key).first()
    if not config:
        return None
    
    value = config.value
    if decrypt and config.encrypted and value:
        value = encryption.decrypt(value)
    
    return value


def set_config_value(db: Session, key: str, value: str, encrypt: bool = False):
    """Установить значение конфигурации"""
    if encrypt and value:
        value = encryption.encrypt(value)
    
    config = db.query(Config).filter(Config.key == key).first()
    if config:
        config.value = value
        config.encrypted = encrypt
    else:
        config = Config(key=key, value=value, encrypted=encrypt)
        db.add(config)
    
    db.commit()
    logger.info(f"Конфигурация сохранена: {key}")


# === Routes ===

@app.get("/", response_class=HTMLResponse)
async def index(request: Request):
    """Главная страница"""
    return templates.TemplateResponse("index.html", {"request": request})


@app.get("/v2", response_class=HTMLResponse)
async def index_v2(request: Request):
    """Пробный интерфейс в новом дизайне (те же API)"""
    return templates.TemplateResponse("v2.html", {"request": request})


@app.get("/api/config")
async def get_config(db: Session = Depends(get_db)):
    """Получить текущую конфигурацию"""
    try:
        config = {
            "google": {
                "credentials_file": get_config_value(db, "google_credentials_file"),
                "calendar_id": get_config_value(db, "google_calendar_id") or "primary",
                "has_token": os.path.exists("data/google_token.pickle")
            },
            "domino": {
                "server_url": get_config_value(db, "domino_server_url"),
                "username": get_config_value(db, "domino_username"),
                "has_password": bool(get_config_value(db, "domino_password")),
                "verify_ssl": get_config_value(db, "domino_verify_ssl") == "true"
            },
            "teams": {
                "client_id": get_config_value(db, "teams_client_id"),
                "has_secret": bool(get_config_value(db, "teams_client_secret")),
                "has_token": os.path.exists("data/teams_token.pickle")
            }
        }
        return config
    except Exception as e:
        logger.exception("Ошибка получения конфигурации")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/config/google")
async def save_google_config(config: GoogleConfig, db: Session = Depends(get_db)):
    """Сохранить конфигурацию Google"""
    try:
        set_config_value(db, "google_credentials_file", config.credentials_file)
        set_config_value(db, "google_calendar_id", config.calendar_id)
        
        return {"status": "success", "message": "Google конфигурация сохранена"}
    except Exception as e:
        logger.exception("Ошибка сохранения Google конфигурации")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/config/domino")
async def save_domino_config(config: DominoConfig, db: Session = Depends(get_db)):
    """Сохранить конфигурацию Domino"""
    try:
        set_config_value(db, "domino_server_url", config.server_url)
        set_config_value(db, "domino_username", config.username)
        if config.password:
            set_config_value(db, "domino_password", config.password, encrypt=True)
        set_config_value(db, "domino_verify_ssl", "true" if config.verify_ssl else "false")
        
        return {"status": "success", "message": "Domino конфигурация сохранена"}
    except Exception as e:
        logger.exception("Ошибка сохранения Domino конфигурации")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/config/teams")
async def save_teams_config(config: TeamsConfig, db: Session = Depends(get_db)):
    """Сохранить конфигурацию MS Teams"""
    try:
        set_config_value(db, "teams_client_id", config.client_id)
        if config.client_secret:
            set_config_value(db, "teams_client_secret", config.client_secret, encrypt=True)
        
        return {"status": "success", "message": "MS Teams конфигурация сохранена"}
    except Exception as e:
        logger.exception("Ошибка сохранения MS Teams конфигурации")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/test/google")
async def test_google_connection(db: Session = Depends(get_db)):
    """Тестировать подключение к Google Calendar"""
    try:
        credentials_file = get_config_value(db, "google_credentials_file")
        calendar_id = get_config_value(db, "google_calendar_id") or "primary"
        token_file = "data/google_token.pickle"
        
        if not credentials_file or not os.path.exists(credentials_file):
            raise HTTPException(status_code=400, detail="Файл credentials не найден")
        
        if not os.path.exists(token_file):
            # Вместо возврата сообщения - автоматически запускаем авторизацию
            logger.info("Токен не найден, запуск OAuth авторизации...")
            try:
                adapter = GoogleCalendarAdapter(
                    credentials_file=credentials_file,
                    token_file=token_file,
                    calendar_id=calendar_id
                )
                # Это откроет браузер для авторизации
                adapter.authenticate(force_reauth=False)
                
                # После успешной авторизации тестируем
                info = adapter.test_connection()
                
                return {
                    "status": "success",
                    "message": "Google авторизация выполнена успешно!",
                    "info": info
                }
            except Exception as auth_error:
                logger.exception("Ошибка авторизации Google")
                return {
                    "status": "error",
                    "message": f"Ошибка авторизации: {str(auth_error)}"
                }
        
        # Создаем адаптер и тестируем
        adapter = GoogleCalendarAdapter(
            credentials_file=credentials_file,
            token_file=token_file,
            calendar_id=calendar_id
        )
        adapter.authenticate()
        info = adapter.test_connection()
        
        return {
            "status": "success",
            "message": "Подключение к Google Calendar успешно",
            "info": info
        }
    except Exception as e:
        logger.exception("Ошибка тестирования Google")
        return {
            "status": "error",
            "message": str(e)
        }


# === Календари Google: выбор, создание, смена ===

WRITABLE_ROLES = {"owner", "writer"}


def _google_adapter(db: Session, calendar_id: str = "primary") -> GoogleCalendarAdapter:
    credentials_file = get_config_value(db, "google_credentials_file")
    if not credentials_file or not os.path.exists(credentials_file):
        raise HTTPException(status_code=400, detail="Файл credentials не найден")
    if not os.path.exists("data/google_token.pickle"):
        raise HTTPException(status_code=400, detail="Требуется вход в Google")
    adapter = GoogleCalendarAdapter(
        credentials_file=credentials_file,
        token_file="data/google_token.pickle",
        calendar_id=calendar_id,
    )
    adapter.authenticate()
    return adapter


def _calendar_json(calendar: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "id": calendar["id"],
        "summary": calendar.get("summary") or calendar["id"],
        "primary": bool(calendar.get("primary")),
        "access_role": calendar.get("access_role"),
        "writable": calendar.get("access_role") in WRITABLE_ROLES,
    }


@app.get("/api/google/calendars")
async def list_google_calendars(db: Session = Depends(get_db)):
    """Календари аккаунта Google; копии можно писать только в writable."""
    current = get_config_value(db, "google_calendar_id") or "primary"
    try:
        calendars = _google_adapter(db).list_calendars()
    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Ошибка чтения календарей Google")
        raise HTTPException(status_code=502, detail=str(e))
    return {"current": current, "calendars": [_calendar_json(c) for c in calendars]}


@app.post("/api/google/calendars")
async def create_google_calendar(request: CreateCalendarRequest, db: Session = Depends(get_db)):
    """Создать новый (дополнительный) календарь в аккаунте Google."""
    try:
        calendar = _google_adapter(db).create_calendar(request.summary.strip() or "Notes")
    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Ошибка создания календаря Google")
        raise HTTPException(status_code=502, detail=str(e))
    logger.info(f"📅 Создан календарь Google: {calendar.get('summary')} ({calendar.get('id')})")
    return {"status": "success", "calendar": _calendar_json(calendar)}


@app.get("/api/google/switch-preview")
async def google_switch_preview(calendar_id: str, db: Session = Depends(get_db)):
    """Сколько копий синхронизатора лежит в текущем календаре (их предложим удалить)."""
    current = get_config_value(db, "google_calendar_id") or "primary"
    old = 0 if calendar_id == current else len(google_copies(db, current))
    return {"current": current, "old_copies": old}


@app.post("/api/google/switch")
async def switch_google_calendar(request: SwitchCalendarRequest, db: Session = Depends(get_db)):
    """Сменить календарь Google. Связи привязаны к календарю, поэтому старые копии
    либо удаляем (и в новом календаре они создадутся заново), либо оставляем как есть."""
    current = get_config_value(db, "google_calendar_id") or "primary"
    deleted, errors = 0, []
    if request.delete_old and request.calendar_id != current:
        copies = google_copies(db, current)
        if copies:
            adapter = _google_adapter(db, current)
            store = DbLinkStore(db, google_calendar=current)
            for link in copies:
                try:
                    adapter.delete(link.target_id)  # type: ignore[arg-type]
                except Exception as e:  # noqa: BLE001 - связь оставляем, сообщаем
                    logger.error(f"❌ Не удалось удалить копию {link.target_id}: {e}")
                    errors.append(f"{link.target_id}: {e}")
                    continue
                store.drop(link)
                deleted += 1
    set_config_value(db, "google_calendar_id", request.calendar_id)
    logger.info(f"📅 Календарь Google: {current} → {request.calendar_id}, удалено копий: {deleted}")
    return {"status": "success", "deleted": deleted, "errors": errors}


class DominoTestRequest(BaseModel):
    """Значения из формы; не переданные берутся из сохранённых настроек."""
    server_url: Optional[str] = None
    username: Optional[str] = None
    password: Optional[str] = None
    verify_ssl: Optional[bool] = None


@app.post("/api/test/domino")
async def test_domino_connection(form: Optional[DominoTestRequest] = None, db: Session = Depends(get_db)):
    """Тестировать подключение к Domino (с тем, что сейчас в форме)"""
    try:
        form = form or DominoTestRequest()
        server_url = form.server_url or get_config_value(db, "domino_server_url")
        username = form.username or get_config_value(db, "domino_username")
        password = form.password or get_config_value(db, "domino_password", decrypt=True)
        verify_ssl = form.verify_ssl if form.verify_ssl is not None else get_config_value(db, "domino_verify_ssl") == "true"
        
        if not all([server_url, username, password]):
            raise HTTPException(status_code=400, detail="Не все параметры Domino настроены")
        
        # Создаем адаптер и тестируем
        adapter = DominoRestAdapter(
            server_url=server_url,
            username=username,
            password=password,
            verify_ssl=verify_ssl
        )
        adapter.connect()
        info = adapter.test_connection()
        adapter.disconnect()
        
        return {
            "status": "success",
            "message": "Подключение к Domino успешно",
            "info": info
        }
    except Exception as e:
        logger.exception("Ошибка тестирования Domino")
        return {
            "status": "error",
            "message": str(e)
        }


@app.post("/api/test/teams")
async def test_teams_connection(db: Session = Depends(get_db)):
    """Тестировать подключение к MS Teams"""
    try:
        client_id = get_config_value(db, "teams_client_id")
        client_secret = get_config_value(db, "teams_client_secret", decrypt=True)
        
        if not all([client_id, client_secret]):
            raise HTTPException(status_code=400, detail="Не все параметры Teams настроены")
        
        # Проверяем есть ли токен
        token_file = "data/teams_token.pickle"
        if not os.path.exists(token_file):
            raise HTTPException(
                status_code=400, 
                detail="Требуется авторизация Teams. Нажмите кнопку 'Авторизовать Teams'"
            )
        
        # Создаем адаптер и тестируем
        adapter = MSTeamsCalendarAdapter(
            client_id=client_id,
            client_secret=client_secret
        )
        
        if not adapter.authenticate():
            raise HTTPException(
                status_code=400,
                detail="Требуется авторизация Teams. Нажмите кнопку 'Авторизовать Teams'"
            )
        
        info = adapter.test_connection()
        
        return {
            "status": "success",
            "message": "Подключение к MS Teams успешно",
            "info": info
        }
    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Ошибка тестирования Teams")
        return {
            "status": "error",
            "message": str(e)
        }


@app.post("/api/google/reauth")
async def google_reauthorize():
    """Переавторизация Google (удаление токена)"""
    try:
        token_file = "data/google_token.pickle"
        if os.path.exists(token_file):
            os.remove(token_file)
            logger.info("Google токен удален")
        
        return {
            "status": "success",
            "message": "Токен удален. Выполните авторизацию через CLI: python -m src.main init --force"
        }
    except Exception as e:
        logger.exception("Ошибка удаления токена")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/teams/authorize")
async def teams_authorize(db: Session = Depends(get_db)):
    """Получить URL для авторизации MS Teams"""
    try:
        client_id = get_config_value(db, "teams_client_id")
        client_secret = get_config_value(db, "teams_client_secret", decrypt=True)
        
        if not all([client_id, client_secret]):
            raise HTTPException(status_code=400, detail="Сначала сохраните Teams конфигурацию")
        
        adapter = MSTeamsCalendarAdapter(
            client_id=client_id,
            client_secret=client_secret
        )
        
        auth_url = adapter.get_authorization_url()
        
        return {
            "status": "success",
            "authorization_url": auth_url
        }
    except Exception as e:
        logger.exception("Ошибка получения authorization URL")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/teams/callback")
async def teams_callback(code: str, db: Session = Depends(get_db)):
    """Callback для завершения OAuth авторизации MS Teams"""
    try:
        logger.info("Teams OAuth callback получен")
        
        client_id = get_config_value(db, "teams_client_id")
        client_secret = get_config_value(db, "teams_client_secret", decrypt=True)
        
        if not all([client_id, client_secret]):
            raise HTTPException(status_code=400, detail="Teams конфигурация не найдена")
        
        adapter = MSTeamsCalendarAdapter(
            client_id=client_id,
            client_secret=client_secret
        )
        
        # Обменяем код на токен
        success = adapter.complete_authorization(code)
        
        if success:
            # Редирект на главную с сообщением об успехе
            return HTMLResponse("""
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Teams Authorization Success</title>
                    <style>
                        body { font-family: Arial, sans-serif; text-align: center; padding: 50px; }
                        .success { color: green; font-size: 24px; margin: 20px; }
                        .btn { 
                            background: #0078d4; 
                            color: white; 
                            padding: 10px 20px; 
                            text-decoration: none;
                            border-radius: 5px;
                            display: inline-block;
                            margin-top: 20px;
                        }
                    </style>
                </head>
                <body>
                    <div class="success">✅ Microsoft Teams успешно авторизован!</div>
                    <p>Теперь вы можете закрыть это окно и вернуться к приложению.</p>
                    <a href="/" class="btn">Вернуться на главную</a>
                </body>
                </html>
            """)
        else:
            raise HTTPException(status_code=400, detail="Ошибка авторизации Teams")
            
    except Exception as e:
        logger.exception("Ошибка Teams callback")
        return HTMLResponse(f"""
            <!DOCTYPE html>
            <html>
            <head>
                <title>Teams Authorization Error</title>
                <style>
                    body {{ font-family: Arial, sans-serif; text-align: center; padding: 50px; }}
                    .error {{ color: red; font-size: 24px; margin: 20px; }}
                </style>
            </head>
            <body>
                <div class="error">❌ Ошибка авторизации Teams</div>
                <p>{str(e)}</p>
                <a href="/">Вернуться на главную</a>
            </body>
            </html>
        """)


def _empty_pair() -> Dict[str, Any]:
    return {"created": 0, "updated": 0, "deleted": 0, "linked": 0, "errors": []}


def _action_json(action: Action) -> Dict[str, Any]:
    """Действие для предпросмотра."""
    event = action.event
    return {
        "key": action.key,
        "kind": action.kind,
        "origin": action.origin.value,
        "target": action.target.value,
        "title": event.title,
        "start": event.start.isoformat(),
        "end": event.end.isoformat(),
        "all_day": event.all_day,
        "location": event.location,
        "selected": action.selected,
        "reason": action.reason,
        "subject": action.subject,
        "changed": list(action.changed),
    }


@app.post("/api/sync")
async def run_sync(request: SyncRequest, db: Session = Depends(get_db)):
    """Запустить синхронизацию (3-way: Google ↔ Domino ↔ Teams)"""
    from datetime import datetime, timedelta, timezone

    if request.direction not in DIRECTIONS:
        raise HTTPException(status_code=400, detail=f"Неизвестное направление: {request.direction}")
    routes = None
    if request.routes is not None:
        try:
            routes = {tuple(System(part) for part in r.split(">")) for r in request.routes}
        except ValueError:
            raise HTTPException(status_code=400, detail=f"Неизвестный маршрут: {request.routes}")
        if any(len(r) != 2 for r in routes):
            raise HTTPException(status_code=400, detail=f"Неизвестный маршрут: {request.routes}")

    now = datetime.now(timezone.utc)
    window = DateRange(now - timedelta(days=request.days_back), now + timedelta(days=request.days_forward))
    logger.info(f"🔄 Синхронизация {request.direction}: {window.start:%Y-%m-%d} - {window.end:%Y-%m-%d}, dry_run={request.dry_run}")

    calendars: Dict[System, Any] = {}
    domino_adapter = None
    try:
        if request.enable_google:
            google_creds = get_config_value(db, "google_credentials_file")
            if not google_creds or not os.path.exists(google_creds):
                raise HTTPException(status_code=400, detail="Google credentials не настроены")
            if not os.path.exists("data/google_token.pickle"):
                raise HTTPException(status_code=400, detail="Требуется авторизация Google")
            google_adapter = GoogleCalendarAdapter(
                credentials_file=google_creds,
                token_file="data/google_token.pickle",
                calendar_id=get_config_value(db, "google_calendar_id") or "primary",
            )
            google_adapter.authenticate()
            calendars[System.GOOGLE] = google_adapter

        if request.enable_domino:
            server = get_config_value(db, "domino_server_url")
            username = get_config_value(db, "domino_username")
            password = get_config_value(db, "domino_password", decrypt=True)
            if not all([server, username, password]):
                raise HTTPException(status_code=400, detail="Настройки Domino не заполнены")
            domino_adapter = DominoRestAdapter(
                server_url=server,
                username=username,
                password=password,
                verify_ssl=get_config_value(db, "domino_verify_ssl") == "true",
            )
            domino_adapter.connect()
            calendars[System.DOMINO] = domino_adapter

        if request.enable_teams:
            client_id = get_config_value(db, "teams_client_id")
            client_secret = get_config_value(db, "teams_client_secret", decrypt=True)
            if not client_id or not client_secret:
                raise HTTPException(status_code=400, detail="Настройки Teams не заполнены")
            if not os.path.exists("data/teams_token.pickle"):
                raise HTTPException(status_code=400, detail="Требуется авторизация Teams")
            teams_adapter = MSTeamsCalendarAdapter(client_id=client_id, client_secret=client_secret)
            teams_adapter.authenticate()
            calendars[System.TEAMS] = teams_adapter

        google_calendar = get_config_value(db, "google_calendar_id") or "primary"
        links = load_links(db, google_calendar=google_calendar)
        snapshot, found = collect(calendars, window, links)
        for system, events in snapshot.items():
            logger.info(f"📥 {system.value}: {len(events)} событий")

        result = plan(snapshot, DIRECTIONS[request.direction], links, found)
        keys = set(request.keys) if request.keys is not None else None
        chosen = select(result.actions, routes, keys, refused=result.refused)
        for warning in result.warnings:
            logger.warning(f"⚠️ {warning}")

        results: Dict[str, Any] = {
            "status": "success",
            "direction": request.direction,
            "dry_run": request.dry_run,
            "sync_window": {"start": f"{window.start:%Y-%m-%d}", "end": f"{window.end:%Y-%m-%d}"},
            "warnings": result.warnings,
        }
        if request.dry_run:
            # Что выполнится по умолчанию — по парам; список — всё, что требует решения
            for action in select(result.actions, routes):
                counter = COUNTERS.get(action.kind)
                if counter:
                    name = f"{action.origin.value}_to_{action.target.value}"
                    pair = results.setdefault(name, _empty_pair())
                    pair[counter] += 1
            shown = [a for a in select(result.actions, routes, keys=[a.key for a in result.actions])
                     if not a.silent and a.kind in COUNTERS]
            results["actions"] = [_action_json(a) for a in shown]
            # «Не копируется»: отказы в окне; ключ в keys создаёт копию
            results["refused"] = [_action_json(a) for a in result.refused
                                  if routes is None or (a.origin, a.target) in routes]
            # Старый формат предпросмотра (только создание), пока UI не перешёл на actions
            results["events"] = [_action_json(a) for a in shown if a.kind == "create"]
        else:
            report = apply(chosen, calendars, DbLinkStore(db, google_calendar=google_calendar))
            for (origin, target), pair_result in report.pairs.items():
                results[f"{origin.value}_to_{target.value}"] = {
                    "created": pair_result.created,
                    "updated": pair_result.updated,
                    "deleted": pair_result.deleted,
                    "linked": pair_result.linked,
                    "errors": pair_result.errors,
                }

        logger.info(f"✅ Синхронизация завершена: {results}")
        return results

    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Ошибка запуска синхронизации")
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        if domino_adapter:
            domino_adapter.disconnect()


@app.get("/api/status")
async def get_status():
    """Получить статус приложения"""
    return {
        "status": "running",
        "version": "1.0.0",
        "database": "connected",
        "encryption": "enabled"
    }


@app.get("/api/logs")
async def get_logs():
    """Получить последние логи"""
    try:
        log_file = Path("logs/gcalsync.log")
        if not log_file.exists():
            return {"logs": ["Лог-файл не найден. Логи появятся после первой синхронизации."], "message": "Лог-файл не найден"}
        
        # Читаем последние 500 строк
        with open(log_file, 'r', encoding='utf-8', errors='ignore') as f:
            lines = f.readlines()
            # Берем последние 500 строк
            recent_lines = lines[-500:] if len(lines) > 500 else lines
            return {"logs": [line.rstrip() for line in recent_lines]}
    except Exception as e:
        logger.exception("Ошибка чтения логов")
        return {"logs": [f"❌ Ошибка чтения логов: {str(e)}"], "error": str(e)}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000, log_level="info")
