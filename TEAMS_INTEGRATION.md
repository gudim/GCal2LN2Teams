# Интеграция Microsoft Teams Calendar (OAuth 2.0)

## ✅ Обновлено: Используется OAuth 2.0 (Delegated Permissions)

Теперь Teams работает как Google Calendar - пользователь вводит логин/пароль, получает токен, синхронизируется только его календарь.

## Регистрация приложения в Azure AD (обновлено)

### Вариант A: Для корпоративного аккаунта (требует админа)

1. Зайти в Azure Portal: https://portal.azure.com
2. Перейти в **Azure Active Directory** → **App registrations** → **New registration**
3. Заполнить:
   - **Name**: "GCal to Lotus Sync"
   - **Supported account types**: **"Accounts in this organizational directory only (Single tenant)"**
   - **Redirect URI**: 
     - Platform: **Web**
     - URL: `http://localhost:8000/api/teams/callback`
4. После создания - скопировать **Application (client) ID**
5. **Certificates & secrets** → **New client secret** → скопировать **Value**
6. **API permissions** → **Add a permission** → **Microsoft Graph** → **Delegated permissions**:
   - `Calendars.ReadWrite`
   - `User.Read`
7. ⚠️ **Попросите админа**: Azure AD → Enterprise Applications → "GCal to Lotus Sync" → Permissions → **Grant admin consent**

### Вариант B: Для личного Microsoft аккаунта (БЕЗ админа)

Если админ не дает согласие, используйте личный Outlook/Hotmail аккаунт:

1. Зайти в Azure Portal: https://portal.azure.com (под личным аккаунтом)
2. Создать бесплатную подписку Azure (если нет)
3. **App registrations** → **New registration**:
   - **Name**: "GCal to Lotus Sync"
   - **Supported account types**: **"Personal Microsoft accounts only"**
   - **Redirect URI**: `http://localhost:8000/api/teams/callback`
4. Создать Client Secret
5. **API permissions** → Delegated: `Calendars.ReadWrite`, `User.Read`
6. ✅ Admin consent НЕ нужен для личных аккаунтов!

### Вариант C: Multitenant (для распространения приложения)

**Используйте только если нужно:**
- Дать доступ пользователям из других организаций
- Опубликовать приложение публично

**Ограничения:**
- Требует верификации издателя (MPN ID, custom domain)
- Сложная настройка
- НЕ нужно для использования внутри одной организации

**Для Single Tenant:**
- ✅ Одно приложение
- ✅ Админ дает consent один раз
- ✅ Все пользователи вашей организации могут использовать
- ✅ Каждый авторизуется под своей учеткой
- ✅ Каждый получает доступ к своему календарю

---

## Как дать доступ коллегам (Single Tenant)

1. **Админ регистрирует приложение** (один раз):
   - Single Tenant (only your organization)
   - Grant admin consent

2. **Вы делитесь с коллегами**:
   - Client ID
   - Client Secret
   - Ссылку на ваш GitHub/инструкцию

3. **Каждый коллега**:
   - Клонирует/скачивает приложение
   - Запускает у себя (localhost:8000)
   - Вводит Client ID/Secret
   - Авторизуется под **своей учеткой**
   - Синхронизирует **свой календарь**

**Безопасность:**
- Client Secret можно передавать внутри организации (это НЕ пароль пользователя)
- Токен (teams_token.pickle) хранится локально у каждого
- Доступ только к календарю авторизованного пользователя

---

5. Перейти в **Certificates & secrets** → **New client secret**
   - Description: "Calendar Sync Secret"
   - Expires: 24 months
   - Скопировать **Value** (это client_secret) ⚠️ больше не покажут!

6. Перейти в **API permissions** → **Add a permission**
   - **Microsoft Graph** → **Delegated permissions** (НЕ Application!)
   - Добавить:
     - `Calendars.ReadWrite` ← доступ к календарю пользователя
     - `User.Read` ← базовая информация о пользователе
   - **НЕ НУЖНО** Grant admin consent (пользователь даст согласие при первом входе)

7. Перейти в **Authentication** (важно!)
   - В разделе **Web** убедитесь что указан: `http://localhost:8000/api/teams/callback`
   - **Access tokens**: НЕ включать
   - **ID tokens**: НЕ включать
   - Сохранить

## Настройка в веб-интерфейсе

1. Откройте http://localhost:8000
2. Секция **MS Teams**:
   - **Client ID**: вставить Application (client) ID
   - **Client Secret**: вставить значение секрета
   - ⚠️ **Tenant ID больше НЕ НУЖЕН** (используется /common для любого Microsoft аккаунта)
3. Нажать **Сохранить Teams**
4. Нажать **Авторизовать Teams** - откроется окно браузера
5. Войти под своей учеткой Microsoft
6. Разрешить доступ к календарю
7. Вернуться в приложение → **Тестировать Teams**

## Отличия от предыдущей версии

**Было (Application Permissions)**:
- Требовался Tenant ID
- Нужен был Admin Consent
- Доступ ко ВСЕМ календарям в организации
- Админы не дают такие права

**Стало (Delegated Permissions)**:
- Tenant ID не нужен
- Admin Consent не нужен
- Доступ только к календарю текущего пользователя
- Как Google Calendar - логин/пароль → токен → синхронизация

## API Endpoints

- `POST /api/config/teams` - сохранить Client ID и Client Secret
- `GET /api/teams/authorize` - получить URL для авторизации
- `GET /api/teams/callback` - callback после авторизации (автоматически)
- `POST /api/test/teams` - тестировать подключение

## Тестирование через curl

```bash
# 1. Сохранить конфигурацию
curl -X POST http://localhost:8000/api/config/teams \
  -H "Content-Type: application/json" \
  -d '{
    "client_id": "ВАШ_CLIENT_ID",
    "client_secret": "ВАШ_CLIENT_SECRET"
  }'

# 2. Получить URL для авторизации
curl http://localhost:8000/api/teams/authorize

# 3. Открыть полученный URL в браузере, авторизоваться

# 4. После успешной авторизации - протестировать
curl -X POST http://localhost:8000/api/test/teams
```

## Следующие шаги

1. Зарегистрируйте приложение в Azure AD по инструкции выше
2. Сохраните Client ID и Client Secret в веб-интерфейсе
3. Авторизуйтесь через кнопку "Авторизовать Teams"
4. Тестируйте подключение

**Правило:** Синхронизируются только нативные события (без маркеров)

- `[GC]` - событие из Google Calendar
- `[LN]` - событие из Lotus Notes  
- `[MT]` - событие из Microsoft Teams

**Примеры:**
- ✅ Событие "Meeting" из Google → синхронизируется в Domino как "[GC] Meeting" и Teams как "[GC] Meeting"
- ❌ Событие "[LN] Birthday" из Domino → НЕ синхронизируется в Google и Teams
- ❌ Событие "[GC] Project Call" из Teams → НЕ синхронизируется обратно в Google

### 4. Направления синхронизации

Нужно добавить в `SyncRequest`:
```python
class SyncRequest(BaseModel):
    # Новые опции direction:
    # - "all" - между всеми тремя системами (только нативные события)
    # - "google-domino" - только Google ↔ Domino
    # - "google-teams" - только Google ↔ Teams
    # - "domino-teams" - только Domino ↔ Teams
    # - "google-only" - только из Google → Domino + Teams
    # - "domino-only" - только из Domino → Google + Teams
    # - "teams-only" - только из Teams → Google + Domino
    direction: str = "all"
    days_back: int = 1
    days_forward: int = 30
    dry_run: bool = False
    sync_sources: List[str] = ["google", "domino", "teams"]  # какие источники включить
```

### 5. Структура синхронизации

```
Шаг 1: Получение событий из всех источников
  - Google Calendar
  - Domino/Lotus Notes  
  - Microsoft Teams

Шаг 2: Фильтрация по маркерам
  - Для каждого источника: отбираем только события БЕЗ маркеров
  - Пример: из Google берем только те, что не начинаются с [LN] или [MT]

Шаг 3: Создание целевых списков
  - google_native → копировать в Domino (как [GC]) + Teams (как [GC])
  - domino_native → копировать в Google (как [LN]) + Teams (как [LN])
  - teams_native → копировать в Google (как [MT]) + Domino (как [MT])

Шаг 4: Проверка существования
  - Для каждой целевой системы: проверяем есть ли уже событие
  - Ключ сравнения: normalized_summary + date

Шаг 5: Создание событий
  - Синхронизация с учетом direction
```

### 6. Код изменений для app.py

Добавить после `DominoConfig`:
```python
class TeamsConfig(BaseModel):
    client_id: str
    client_secret: str
    tenant_id: str
```

Добавить эндпоинты:
```python
@app.post("/api/config/teams")
async def save_teams_config(config: TeamsConfig, db: Session = Depends(get_db)):
    set_config_value(db, "teams_client_id", config.client_id)
    set_config_value(db, "teams_client_secret", config.client_secret, encrypt=True)
    set_config_value(db, "teams_tenant_id", config.tenant_id)
    return {"status": "success", "message": "Teams конфигурация сохранена"}

@app.post("/api/test/teams")
async def test_teams_connection(db: Session = Depends(get_db)):
    from ..adapters.ms_teams_calendar import MSTeamsCalendarAdapter
    
    client_id = get_config_value(db, "teams_client_id")
    client_secret = get_config_value(db, "teams_client_secret", decrypt=True)
    tenant_id = get_config_value(db, "teams_tenant_id")
    
    if not all([client_id, client_secret, tenant_id]):
        raise HTTPException(status_code=400, detail="Teams настройки не заполнены")
    
    adapter = MSTeamsCalendarAdapter(
        client_id=client_id,
        client_secret=client_secret,
        tenant_id=tenant_id
    )
    adapter.authenticate()
    info = adapter.test_connection()
    
    return {
        "status": "success",
        "message": "Подключение к MS Teams успешно",
        "info": info
    }
```

### 7. UI изменения

Добавить в `index.html` секцию конфигурации Teams (аналогично Google и Domino).

## Следующие шаги

1. Установите `msal`: `pip install msal`
2. Зарегистрируйте приложение в Azure AD (см. инструкцию выше)
3. Скажите мне когда получите credentials, и я доделаю интеграцию в `app.py`
