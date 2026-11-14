# Проект: Синхронизация Google Calendar ↔ HCL Lotus Notes

## 📋 Обзор проекта

Кроссплатформенное приложение на Python для двусторонней синхронизации событий между Google Calendar и HCL Lotus Notes.

**Поддерживаемые платформы:** Windows, Linux

---

## 🎯 Цели проекта

1. Автоматическая синхронизация событий календаря
2. Поддержка двусторонней синхронизации (Google ↔ Lotus Notes)
3. Обработка конфликтов и дубликатов
4. Кроссплатформенность (Windows/Linux)
5. Настраиваемые правила синхронизации
6. Логирование и мониторинг процесса

---

## 🏗️ Архитектура проекта

### Основные компоненты

```
┌─────────────────────────────────────────────────────┐
│           Синхронизатор (Core Engine)               │
│                                                     │
│  ┌──────────────┐         ┌──────────────┐        │
│  │   Google     │ ◄─────► │    Lotus     │        │
│  │   Adapter    │         │   Adapter    │        │
│  └──────────────┘         └──────────────┘        │
│         ▲                        ▲                 │
│         │                        │                 │
│  ┌──────┴────────────────────────┴──────┐         │
│  │      Conflict Resolution Engine       │         │
│  └───────────────────────────────────────┘         │
│                                                     │
│  ┌───────────────────────────────────────┐         │
│  │      Database (SQLite) - Mapping      │         │
│  └───────────────────────────────────────┘         │
└─────────────────────────────────────────────────────┘
```

### Модульная структура

```
gcaltolotus/
│
├── src/
│   ├── __init__.py
│   ├── main.py                    # Точка входа
│   ├── config.py                  # Конфигурация
│   │
│   ├── adapters/                  # Адаптеры для интеграций
│   │   ├── __init__.py
│   │   ├── google_calendar.py    # Google Calendar API
│   │   └── lotus_notes.py        # Lotus Notes API
│   │
│   ├── sync/                      # Логика синхронизации
│   │   ├── __init__.py
│   │   ├── engine.py             # Основной движок синхронизации
│   │   ├── conflict_resolver.py  # Разрешение конфликтов
│   │   └── mapper.py             # Маппинг событий
│   │
│   ├── database/                  # Работа с БД
│   │   ├── __init__.py
│   │   ├── models.py             # Модели данных
│   │   └── repository.py         # Доступ к данным
│   │
│   └── utils/                     # Утилиты
│       ├── __init__.py
│       ├── logger.py             # Логирование
│       ├── validators.py         # Валидация данных
│       └── platform_utils.py     # Кроссплатформенные утилиты
│
├── tests/                         # Тесты
│   ├── __init__.py
│   ├── test_google_adapter.py
│   ├── test_lotus_adapter.py
│   └── test_sync_engine.py
│
├── config/                        # Конфигурационные файлы
│   ├── config.yaml.example
│   └── logging.yaml
│
├── data/                          # Локальная БД и кеш
│   └── .gitkeep
│
├── logs/                          # Логи
│   └── .gitkeep
│
├── scripts/                       # Вспомогательные скрипты
│   ├── setup_google_auth.py
│   ├── setup_lotus_auth.py
│   └── install_service.sh/bat
│
├── requirements.txt               # Python зависимости
├── requirements-dev.txt           # Зависимости для разработки
├── setup.py                       # Установка пакета
├── .env.example                   # Пример переменных окружения
├── .gitignore
├── README.md                      # Основная документация
└── LICENSE

```

---

## 🔧 Технический стек

### Основные библиотеки

1. **Google Calendar API:**
   - `google-auth`
   - `google-auth-oauthlib`
   - `google-auth-httplib2`
   - `google-api-python-client`

2. **Lotus Notes Integration:**
   - `pywin32` (для Windows)
   - `wine` + `pywin32` (для Linux через Wine)
   - Альтернатива: REST API Domino (если доступно)

3. **База данных:**
   - `SQLAlchemy` (ORM)
   - `SQLite` (локальная БД)

4. **Конфигурация:**
   - `pyyaml` (YAML конфиг)
   - `python-dotenv` (переменные окружения)

5. **Планирование задач:**
   - `schedule` или `APScheduler`

6. **Логирование:**
   - `logging` (встроенный)
   - `colorlog` (цветные логи)

7. **Утилиты:**
   - `python-dateutil` (работа с датами)
   - `pytz` (временные зоны)
   - `click` (CLI интерфейс)

---

## 📊 Детальный план реализации

### Фаза 1: Подготовка и инфраструктура (1-2 дня)

#### 1.1 Настройка проекта
- [x] Создание структуры проекта
- [ ] Инициализация Git репозитория
- [ ] Настройка виртуального окружения
- [ ] Создание requirements.txt
- [ ] Настройка .gitignore

#### 1.2 Конфигурация
- [ ] Создание config.yaml.example
- [ ] Настройка логирования
- [ ] Создание .env.example для секретов

#### 1.3 База данных
- [ ] Проектирование схемы БД
- [ ] Создание моделей SQLAlchemy
- [ ] Миграции БД

**Схема БД:**
```sql
-- Таблица для хранения маппинга событий
CREATE TABLE event_mapping (
    id INTEGER PRIMARY KEY,
    google_event_id VARCHAR(255) UNIQUE,
    lotus_event_id VARCHAR(255) UNIQUE,
    last_sync_timestamp DATETIME,
    google_updated DATETIME,
    lotus_updated DATETIME,
    sync_status VARCHAR(50),  -- 'synced', 'conflict', 'pending'
    created_at DATETIME,
    updated_at DATETIME
);

-- Таблица для логов синхронизации
CREATE TABLE sync_log (
    id INTEGER PRIMARY KEY,
    sync_timestamp DATETIME,
    direction VARCHAR(20),  -- 'google_to_lotus', 'lotus_to_google', 'bidirectional'
    events_processed INTEGER,
    events_created INTEGER,
    events_updated INTEGER,
    events_deleted INTEGER,
    conflicts_detected INTEGER,
    status VARCHAR(20),  -- 'success', 'partial', 'failed'
    error_message TEXT,
    created_at DATETIME
);

-- Таблица для конфликтов
CREATE TABLE conflicts (
    id INTEGER PRIMARY KEY,
    event_mapping_id INTEGER,
    conflict_type VARCHAR(50),
    google_data JSON,
    lotus_data JSON,
    resolution VARCHAR(20),  -- 'manual', 'google_wins', 'lotus_wins', 'merged'
    resolved BOOLEAN DEFAULT FALSE,
    created_at DATETIME,
    resolved_at DATETIME,
    FOREIGN KEY (event_mapping_id) REFERENCES event_mapping(id)
);
```

---

### Фаза 2: Google Calendar интеграция (2-3 дня)

#### 2.1 Аутентификация
- [ ] Настройка OAuth 2.0 для Google API
- [ ] Создание credentials в Google Cloud Console
- [ ] Реализация получения и обновления токенов
- [ ] Хранение токенов безопасным способом

#### 2.2 Работа с Google Calendar API
- [ ] Получение списка календарей
- [ ] Чтение событий из календаря
- [ ] Создание новых событий
- [ ] Обновление существующих событий
- [ ] Удаление событий
- [ ] Обработка повторяющихся событий (recurring events)
- [ ] Работа с напоминаниями и уведомлениями

#### 2.3 Обработка данных
- [ ] Парсинг событий Google Calendar
- [ ] Преобразование в унифицированный формат
- [ ] Обработка временных зон
- [ ] Валидация данных

**Структура события (унифицированная):**
```python
{
    'id': 'unique_id',
    'summary': 'Название события',
    'description': 'Описание',
    'location': 'Место',
    'start': {
        'datetime': '2025-11-17T10:00:00',
        'timezone': 'Europe/Moscow'
    },
    'end': {
        'datetime': '2025-11-17T11:00:00',
        'timezone': 'Europe/Moscow'
    },
    'attendees': [
        {'email': 'user@example.com', 'status': 'accepted'}
    ],
    'reminders': [
        {'method': 'email', 'minutes': 30}
    ],
    'recurrence': {
        'rule': 'RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR',
        'exceptions': []
    },
    'status': 'confirmed',  # confirmed, tentative, cancelled
    'visibility': 'default',  # default, public, private
    'created': '2025-11-01T12:00:00Z',
    'updated': '2025-11-15T14:30:00Z'
}
```

---

### Фаза 3: Lotus Notes интеграция (3-5 дней)

#### 3.1 Анализ окружения
- [ ] Определение версии Lotus Notes
- [ ] Проверка доступности COM API (Windows)
- [ ] Исследование REST API Domino (если есть)
- [ ] Настройка Wine для Linux (если нужно)

#### 3.2 Windows интеграция
- [ ] Подключение через pywin32
- [ ] Получение сессии Lotus Notes
- [ ] Доступ к календарю (mailbox)
- [ ] Чтение документов календаря
- [ ] Создание/обновление/удаление событий

**Пример кода для Windows:**
```python
import win32com.client

def get_lotus_session():
    """Получение сессии Lotus Notes"""
    session = win32com.client.Dispatch("Lotus.NotesSession")
    session.Initialize()
    return session

def get_calendar_database(session):
    """Получение базы данных календаря"""
    db = session.GetDatabase("", "")  # Текущая mail.nsf
    if not db.IsOpen:
        db.Open("")
    return db
```

#### 3.3 Linux интеграция
- [ ] Настройка Wine (если используется COM)
- [ ] Альтернативный подход через Domino REST API
- [ ] Тестирование на Linux

#### 3.4 Адаптер Lotus Notes
- [ ] Унификация API для Windows/Linux
- [ ] Чтение событий из Notes
- [ ] Создание событий в Notes
- [ ] Обновление событий
- [ ] Удаление событий
- [ ] Маппинг полей Notes ↔ унифицированный формат

**Маппинг полей Lotus Notes:**
```
Lotus Notes Field → Unified Format
------------------------------------
Subject → summary
Body → description
Location → location
StartDateTime → start.datetime
EndDateTime → end.datetime
Chair → organizer
RequiredAttendees → attendees
AppointmentType → event_type (0=appointment, 1=anniversary, 2=event, 3=reminder)
$Alarm → reminders
```

---

### Фаза 4: Движок синхронизации (3-4 дня)

#### 4.1 Основная логика
- [ ] Определение стратегии синхронизации
- [ ] Реализация односторонней синхронизации (Google → Lotus)
- [ ] Реализация односторонней синхронизации (Lotus → Google)
- [ ] Реализация двусторонней синхронизации
- [ ] Инкрементальная синхронизация (только изменения)

#### 4.2 Обнаружение изменений
- [ ] Сравнение timestamps событий
- [ ] Использование sync tokens (Google)
- [ ] Кеширование состояния
- [ ] Определение новых событий
- [ ] Определение обновленных событий
- [ ] Определение удаленных событий

#### 4.3 Разрешение конфликтов
- [ ] Обнаружение конфликтов (одновременные изменения)
- [ ] Стратегии разрешения:
  - Manual (требует вмешательства пользователя)
  - Google wins (приоритет Google)
  - Lotus wins (приоритет Lotus Notes)
  - Latest wins (приоритет последнего изменения)
  - Merge (объединение изменений)
- [ ] Сохранение конфликтов в БД
- [ ] UI для разрешения конфликтов

#### 4.4 Обработка особых случаев
- [ ] Повторяющиеся события
- [ ] Исключения из повторяющихся событий
- [ ] Временные зоны
- [ ] Отмененные события
- [ ] Приглашенные участники
- [ ] Вложения (если поддерживается)

---

### Фаза 5: CLI и автоматизация (2 дня)

#### 5.1 Command Line Interface
- [ ] Команда инициализации: `gcalsync init`
- [ ] Команда синхронизации: `gcalsync sync [--dry-run]`
- [ ] Команда просмотра статуса: `gcalsync status`
- [ ] Команда разрешения конфликтов: `gcalsync conflicts`
- [ ] Команда настройки: `gcalsync config`
- [ ] Справка и help

**Примеры команд:**
```bash
# Первичная настройка
gcalsync init --google-credentials=/path/to/credentials.json

# Синхронизация (пробный прогон)
gcalsync sync --dry-run --direction=both

# Реальная синхронизация
gcalsync sync --direction=google-to-lotus

# Просмотр конфликтов
gcalsync conflicts list
gcalsync conflicts resolve --id=123 --resolution=google-wins

# Статус
gcalsync status --verbose

# Настройка
gcalsync config set sync_interval 15  # минуты
gcalsync config set conflict_resolution latest_wins
```

#### 5.2 Планировщик задач
- [ ] Автоматический запуск синхронизации
- [ ] Настройка интервала синхронизации
- [ ] Обработка ошибок при автоматическом запуске
- [ ] Уведомления о проблемах

#### 5.3 Служба/Демон
- [ ] Windows Service (pywin32)
- [ ] Linux systemd service
- [ ] Установочные скрипты
- [ ] Логирование работы службы

---

### Фаза 6: Тестирование (2-3 дня)

#### 6.1 Unit тесты
- [ ] Тесты адаптеров
- [ ] Тесты движка синхронизации
- [ ] Тесты разрешения конфликтов
- [ ] Тесты базы данных
- [ ] Покрытие > 80%

#### 6.2 Integration тесты
- [ ] Тесты интеграции с Google Calendar
- [ ] Тесты интеграции с Lotus Notes
- [ ] Тесты полного цикла синхронизации

#### 6.3 E2E тесты
- [ ] Тестирование на Windows
- [ ] Тестирование на Linux
- [ ] Тестирование различных сценариев

---

### Фаза 7: Документация (1-2 дня)

- [x] README.md с инструкциями
- [ ] Документация API
- [ ] Руководство по установке
- [ ] Руководство по настройке
- [ ] FAQ
- [ ] Troubleshooting guide

---

## 🔄 Алгоритм синхронизации

### Двусторонняя синхронизация (Bidirectional Sync)

```
1. ИНИЦИАЛИЗАЦИЯ
   ├─ Загрузить конфигурацию
   ├─ Подключиться к Google Calendar API
   ├─ Подключиться к Lotus Notes
   └─ Загрузить последнее состояние из БД

2. ПОЛУЧЕНИЕ ИЗМЕНЕНИЙ
   ├─ Google: Получить события с последней синхронизации
   ├─ Lotus: Получить события с последней синхронизации
   └─ Сохранить timestamps для следующей синхронизации

3. СОПОСТАВЛЕНИЕ
   ├─ Для каждого события проверить маппинг в БД
   ├─ Определить новые события (нет в маппинге)
   ├─ Определить обновленные события (изменился timestamp)
   └─ Определить удаленные события (отсутствуют в источнике)

4. ОБНАРУЖЕНИЕ КОНФЛИКТОВ
   ├─ Если событие изменено в обоих системах:
   │   ├─ Сравнить timestamps
   │   ├─ Применить стратегию разрешения
   │   └─ Сохранить конфликт в БД (если нужно ручное разрешение)
   └─ Иначе: событие готово к синхронизации

5. СИНХРОНИЗАЦИЯ
   ├─ Новые события Google → создать в Lotus
   ├─ Новые события Lotus → создать в Google
   ├─ Обновленные события Google → обновить в Lotus
   ├─ Обновленные события Lotus → обновить в Google
   ├─ Удаленные события Google → удалить из Lotus
   └─ Удаленные события Lotus → удалить из Google

6. ОБНОВЛЕНИЕ МАППИНГА
   ├─ Сохранить ID событий в обоих системах
   ├─ Сохранить timestamps
   └─ Обновить статус синхронизации

7. ЛОГИРОВАНИЕ
   ├─ Записать статистику синхронизации
   ├─ Записать ошибки
   └─ Отправить уведомления (если настроено)
```

---

## ⚙️ Конфигурация

### config.yaml
```yaml
# Общие настройки
app:
  name: "Google Calendar to Lotus Notes Sync"
  version: "1.0.0"
  log_level: "INFO"  # DEBUG, INFO, WARNING, ERROR

# Настройки синхронизации
sync:
  direction: "bidirectional"  # google-to-lotus, lotus-to-google, bidirectional
  interval_minutes: 15
  batch_size: 100
  
  # Стратегия разрешения конфликтов
  conflict_resolution: "manual"  # manual, google_wins, lotus_wins, latest_wins, merge
  
  # Фильтры
  filters:
    sync_past_days: 7  # Синхронизировать события за последние N дней
    sync_future_days: 365  # Синхронизировать события на N дней вперед
    exclude_all_day_events: false
    exclude_declined_events: true

# Google Calendar
google:
  credentials_file: "config/google_credentials.json"
  token_file: "data/google_token.pickle"
  calendar_id: "primary"  # или конкретный ID календаря
  
# Lotus Notes
lotus:
  platform: "auto"  # auto, windows, linux
  server: ""  # Пусто для локального
  database: ""  # Пусто для mail.nsf пользователя
  
  # Для Linux через Wine
  wine_path: "/usr/bin/wine"
  notes_path: "~/.wine/drive_c/Program Files/IBM/Notes"

# База данных
database:
  path: "data/sync.db"
  backup_enabled: true
  backup_interval_days: 7

# Логирование
logging:
  file: "logs/gcalsync.log"
  max_size_mb: 10
  backup_count: 5
  format: "%(asctime)s - %(name)s - %(levelname)s - %(message)s"

# Уведомления (опционально)
notifications:
  enabled: false
  email:
    smtp_server: "smtp.gmail.com"
    smtp_port: 587
    from_address: "notifications@example.com"
    to_address: "admin@example.com"
    username: ""
    password: ""
```

---

## 🔐 Безопасность

### Рекомендации
1. **Хранение credentials:**
   - Использовать переменные окружения для паролей
   - Шифровать токены OAuth
   - Не коммитить credentials в Git

2. **Права доступа:**
   - Минимальные необходимые права для Google API
   - Безопасное хранение паролей Lotus Notes

3. **Сетевая безопасность:**
   - HTTPS для всех API запросов
   - Проверка SSL сертификатов

---

## 📈 Мониторинг и логи

### Логирование
- Все операции синхронизации
- Ошибки и исключения
- Производительность (время синхронизации)
- Статистика (количество синхронизированных событий)

### Метрики
- Время выполнения синхронизации
- Количество обработанных событий
- Количество конфликтов
- Успешность операций (%)

---

## 🚀 Развертывание

### Windows
1. Установка Python 3.8+
2. Установка зависимостей
3. Настройка конфигурации
4. Установка как Windows Service (опционально)

### Linux
1. Установка Python 3.8+
2. Установка зависимостей
3. Установка Wine (если нужно для Lotus Notes)
4. Настройка systemd service (опционально)

---

## 🔮 Будущие улучшения

1. **Web интерфейс:**
   - Dashboard для мониторинга
   - UI для разрешения конфликтов
   - Настройка через веб

2. **Расширенные возможности:**
   - Синхронизация вложений
   - Поддержка категорий/тегов
   - Умные уведомления
   - Синхронизация задач (To-Do)

3. **Оптимизация:**
   - Параллельная обработка событий
   - Кеширование
   - Delta sync (только изменения)

4. **Интеграции:**
   - Поддержка других календарей (Outlook, iCal)
   - Webhooks для real-time синхронизации
   - API для сторонних интеграций

---

## 📝 Риски и ограничения

### Технические риски
1. **Lotus Notes API:**
   - Ограниченная документация
   - Различия между версиями
   - Проблемы с Wine на Linux

2. **Google API:**
   - Ограничения quota (rate limits)
   - Изменения в API

3. **Конфликты:**
   - Сложность автоматического разрешения
   - Потеря данных при неправильной обработке

### Ограничения
- Lotus Notes должен быть установлен и настроен
- Требуется интернет для Google Calendar
- Ограничения по количеству запросов к API

---

## ⏱️ Оценка времени разработки

- **Фаза 1:** 1-2 дня
- **Фаза 2:** 2-3 дня
- **Фаза 3:** 3-5 дней (самая сложная часть)
- **Фаза 4:** 3-4 дня
- **Фаза 5:** 2 дня
- **Фаза 6:** 2-3 дня
- **Фаза 7:** 1-2 дня

**Итого:** 14-21 день чистой разработки

С учетом тестирования, отладки и непредвиденных проблем: **3-4 недели**

---

## 👥 Команда (рекомендуемая)

- **Python Developer** - основная разработка
- **DevOps** - развертывание и CI/CD (опционально)
- **QA** - тестирование (опционально)

Проект может быть реализован одним разработчиком.

---

## 📚 Ресурсы

### Документация
- [Google Calendar API](https://developers.google.com/calendar/api/v3/reference)
- [Lotus Notes API](https://help.hcltechsw.com/dom_designer/11.0.1/basic/H_NOTES_CLASSES.html)
- [pywin32 Documentation](https://github.com/mhammond/pywin32)

### Полезные библиотеки
- [SQLAlchemy](https://www.sqlalchemy.org/)
- [Click](https://click.palletsprojects.com/)
- [APScheduler](https://apscheduler.readthedocs.io/)

---

**Дата создания:** 17 ноября 2025
**Версия:** 1.0
**Статус:** Draft
