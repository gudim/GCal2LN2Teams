# Microsoft Teams - Финальные шаги интеграции

## ✅ Что уже сделано:

1. **Адаптер MS Teams** (`src/adapters/ms_teams_calendar.py`) - готов к использованию
2. **Модели конфигурации** - `TeamsConfig` добавлена в `app.py`
3. **API эндпоинты**:
   - `POST /api/config/teams` - сохранение конфигурации
   - `POST /api/test/teams` - тестирование подключения
   - `GET /api/config` - возвращает Teams конфигурацию
4. **Зависимости** - `msal` добавлена в requirements.txt
5. **Вспомогательные функции** (`src/web/sync_helpers.py`) - готовы
6. **Модель SyncRequest** - обновлена с поддержкой Teams

## 📋 Что нужно сделать:

### 1. Установить зависимости

```bash
cd ~/gcaltolotus
pip install msal>=1.25.0
```

### 2. Перезапустить веб-сервер

```bash
./scripts/run_web.sh
```

### 3. Настроить Teams в UI

Откройте http://localhost:8000 и добавьте секцию Teams в интерфейс.

**Для быстрого тестирования** можно использовать curl:

```bash
# Сохранить Teams конфигурацию
curl -X POST http://localhost:8000/api/config/teams \
  -H "Content-Type: application/json" \
  -d '{
    "client_id": "ВАШ_CLIENT_ID",
    "client_secret": "ВАШ_CLIENT_SECRET",
    "tenant_id": "ВАШ_TENANT_ID"
  }'

# Протестировать подключение
curl -X POST http://localhost:8000/api/test/teams
```

### 4. Логика синхронизации с маркерами

**Текущее поведение** (Google ↔ Domino):
- События из Google → Domino получают маркер `[GC]`
- События из Domino → Google получают маркер `[LN]`
- События с маркерами не синхронизируются обратно

**Новое поведение** (Google ↔ Domino ↔ Teams):

```
Маркеры:
  [GC] - событие из Google Calendar
  [LN] - событие из Lotus Notes/Domino
  [MT] - событие из Microsoft Teams

Правило фильтрации:
  При синхронизации ИЗ источника X в цель Y:
    - Берем только события БЕЗ маркеров (нативные)
    - Добавляем маркер источника X
    - Создаем в цели Y

Примеры:
  ✅ Google: "Meeting" → Domino: "[GC] Meeting", Teams: "[GC] Meeting"
  ❌ Google: "[LN] Birthday" → НЕ синхронизируется (имеет маркер)
  ✅ Teams: "Call" → Google: "[MT] Call", Domino: "[MT] Call"
  ❌ Domino: "[MT] Call" → НЕ синхронизируется (имеет маркер)
```

### 5. Обновление UI (index.html)

Добавьте секцию Teams после Domino:

```html
<!-- MS Teams Configuration -->
<div class="config-section">
    <h2>🟣 Microsoft Teams</h2>
    <form id="teamsConfigForm">
        <div class="form-group">
            <label for="teamsClientId">Client ID:</label>
            <input type="text" id="teamsClientId" name="client_id" required>
        </div>
        <div class="form-group">
            <label for="teamsClientSecret">Client Secret:</label>
            <input type="password" id="teamsClientSecret" name="client_secret" required>
        </div>
        <div class="form-group">
            <label for="teamsTenantId">Tenant ID:</label>
            <input type="text" id="teamsTenantId" name="tenant_id" required>
        </div>
        <div class="button-group">
            <button type="submit" class="btn btn-primary">Сохранить Teams</button>
            <button type="button" class="btn btn-secondary" onclick="testTeams()">Тестировать Teams</button>
        </div>
    </form>
    <div id="teams-status" class="status-message"></div>
</div>
```

Добавьте JavaScript функции в app.js:

```javascript
// Сохранение Teams конфигурации
document.getElementById('teamsConfigForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const formData = new FormData(e.target);
    const config = Object.fromEntries(formData);
    
    try {
        const response = await fetch('/api/config/teams', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(config)
        });
        const data = await response.json();
        showStatus('teams-status', data.message, data.status);
    } catch (error) {
        showStatus('teams-status', 'Ошибка: ' + error.message, 'error');
    }
});

// Тестирование Teams
async function testTeams() {
    try {
        const response = await fetch('/api/test/teams', {method: 'POST'});
        const data = await response.json();
        const message = data.status === 'success' 
            ? `✅ ${data.message}\nПользователь: ${data.info.user}`
            : `❌ ${data.message}`;
        showStatus('teams-status', message, data.status);
    } catch (error) {
        showStatus('teams-status', 'Ошибка: ' + error.message, 'error');
    }
}
```

Обновите форму синхронизации:

```html
<div class="form-group">
    <label>Источники синхронизации:</label>
    <div class="checkbox-group">
        <label>
            <input type="checkbox" name="enable_google" checked> Google Calendar
        </label>
        <label>
            <input type="checkbox" name="enable_domino" checked> Lotus Notes/Domino
        </label>
        <label>
            <input type="checkbox" name="enable_teams"> Microsoft Teams
        </label>
    </div>
</div>
```

### 6. Полная логика синхронизации

Функция `run_sync` в `app.py` слишком большая для полной переработки здесь. 

**Упрощенная схема**:

```python
# В начале run_sync добавить:
from .sync_helpers import (
    MARKER_GOOGLE, MARKER_LOTUS, MARKER_TEAMS,
    filter_native_events, build_existing_keys, 
    filter_birthday_duplicates, add_marker_to_summary,
    normalize_event_summary
)

# После получения событий из всех источников:

# Шаг 1: Получение событий
google_events_raw = google_adapter.list_events(...) if request.enable_google else []
domino_events_raw = domino_adapter.list_events(...) if request.enable_domino else []
teams_events_raw = teams_adapter.list_events(...) if request.enable_teams else []

# Шаг 2: Фильтрация нативных событий
google_native = filter_native_events(google_events_raw, [MARKER_LOTUS, MARKER_TEAMS])
domino_native = filter_native_events(domino_events_raw, [MARKER_GOOGLE, MARKER_TEAMS])
teams_native = filter_native_events(teams_events_raw, [MARKER_GOOGLE, MARKER_LOTUS])

# Шаг 3: Дедупликация дней рождений
google_native = filter_birthday_duplicates(google_native, MARKER_GOOGLE)
domino_native = filter_birthday_duplicates(domino_native, MARKER_LOTUS)
teams_native = filter_birthday_duplicates(teams_native, MARKER_TEAMS)

# Шаг 4: Построение ключей существующих событий
google_keys = build_existing_keys(google_events_raw)
domino_keys = build_existing_keys(domino_events_raw)
teams_keys = build_existing_keys(teams_events_raw)

# Шаг 5: Синхронизация
# Google → Domino
for event in google_native:
    summary = normalize_event_summary(event['summary'])
    marked_summary = add_marker_to_summary(summary, MARKER_GOOGLE)
    # ... проверка существования и создание

# Google → Teams
for event in google_native:
    summary = normalize_event_summary(event['summary'])
    marked_summary = add_marker_to_summary(summary, MARKER_GOOGLE)
    # ... проверка существования и создание

# Domino → Google
for event in domino_native:
    summary = normalize_event_summary(event['summary'])
    marked_summary = add_marker_to_summary(summary, MARKER_LOTUS)
    # ... проверка существования и создание

# Domino → Teams
# Teams → Google
# Teams → Domino
```

## 🎯 Быстрый старт для тестирования

1. Установите `msal`: `pip install msal`
2. Перезапустите сервер
3. Используйте curl для сохранения Teams конфигурации
4. Протестируйте подключение через `POST /api/test/teams`
5. Начните с синхронизации только Google ↔ Domino (enable_teams=false)
6. Когда Teams готов - включите enable_teams=true

## ❓ Нужна помощь?

Если хотите полностью переработать `run_sync` с поддержкой 3 источников - скажите, я создам полную версию функции (будет около 500+ строк кода).
