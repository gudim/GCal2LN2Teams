# 📝 TODO List - План разработки

## 🎯 Критический путь (Must Have для v1.0)

### ✅ Фаза 0: Инфраструктура (ЗАВЕРШЕНО)
- [x] Создать структуру проекта
- [x] Настроить зависимости (requirements.txt)
- [x] Создать конфигурационные файлы
- [x] Написать документацию
- [x] Создать CLI каркас

---

### 🔴 Фаза 1: Google Calendar Адаптер (ВЫСОКИЙ ПРИОРИТЕТ)

**src/adapters/google_calendar.py**
- [ ] Реализовать класс GoogleCalendarAdapter
- [ ] OAuth 2.0 авторизация
  - [ ] Получение токена
  - [ ] Обновление токена
  - [ ] Хранение credentials
- [ ] CRUD операции
  - [ ] list_events(start_date, end_date) - получить события
  - [ ] get_event(event_id) - получить одно событие
  - [ ] create_event(event_data) - создать событие
  - [ ] update_event(event_id, event_data) - обновить событие
  - [ ] delete_event(event_id) - удалить событие
- [ ] Обработка повторяющихся событий (recurring)
- [ ] Работа с участниками (attendees)
- [ ] Обработка напоминаний (reminders)
- [ ] Sync tokens для инкрементальных обновлений
- [ ] Обработка ошибок и rate limiting
- [ ] Unit тесты

**Зависимости:** Google API libraries (уже в requirements.txt)

**Приоритет:** 🔴 КРИТИЧЕСКИЙ  
**Оценка времени:** 2-3 дня  
**Назначено:** -

---

### 🟠 Фаза 2: Lotus Notes Адаптер (ВЫСОКИЙ ПРИОРИТЕТ)

**src/adapters/lotus_notes.py**
- [ ] Реализовать базовый класс LotusNotesAdapter
- [ ] Определить платформу (Windows/Linux)
- [ ] Windows реализация
  - [ ] Подключение через pywin32
  - [ ] Получение сессии Notes
  - [ ] Доступ к mail.nsf
  - [ ] CRUD операции для событий
- [ ] Linux реализация
  - [ ] Настройка Wine (опционально)
  - [ ] Альтернатива: Domino REST API
  - [ ] CRUD операции
- [ ] Унифицированный интерфейс для обеих платформ
- [ ] Маппинг полей Lotus ↔ Unified format
- [ ] Обработка повторяющихся событий
- [ ] Работа с участниками
- [ ] Обработка ошибок
- [ ] Unit тесты (mock)
- [ ] Integration тесты (реальный Notes)

**Зависимости:** pywin32 (Windows), Wine (Linux)

**Приоритет:** 🟠 ВЫСОКИЙ  
**Оценка времени:** 3-5 дней  
**Назначено:** -

**Риски:** 
- Документация Lotus Notes API ограничена
- Проблемы с Wine на Linux
- Различия между версиями Notes

---

### 🟡 Фаза 3: База данных (СРЕДНИЙ ПРИОРИТЕТ)

**src/database/models.py**
- [ ] Создать модель EventMapping
  - [ ] google_event_id
  - [ ] lotus_event_id
  - [ ] timestamps
  - [ ] sync_status
- [ ] Создать модель SyncLog
  - [ ] timestamp
  - [ ] direction
  - [ ] statistics
  - [ ] errors
- [ ] Создать модель Conflict
  - [ ] event_mapping_id
  - [ ] conflict_type
  - [ ] data (JSON)
  - [ ] resolution

**src/database/repository.py**
- [ ] EventMappingRepository
  - [ ] get_mapping(google_id / lotus_id)
  - [ ] create_mapping()
  - [ ] update_mapping()
  - [ ] delete_mapping()
- [ ] SyncLogRepository
  - [ ] create_log()
  - [ ] get_last_sync()
  - [ ] get_sync_history()
- [ ] ConflictRepository
  - [ ] create_conflict()
  - [ ] get_unresolved_conflicts()
  - [ ] resolve_conflict()

**src/database/migrations/**
- [ ] Создать Alembic миграции
- [ ] Initial schema migration

**Приоритет:** 🟡 СРЕДНИЙ  
**Оценка времени:** 1-2 дня  
**Назначено:** -

---

### 🔵 Фаза 4: Движок синхронизации (КРИТИЧЕСКИЙ)

**src/sync/engine.py**
- [ ] Класс SyncEngine
- [ ] Метод sync(direction, filters)
- [ ] Получение изменений из обеих систем
- [ ] Определение новых событий
- [ ] Определение обновленных событий
- [ ] Определение удаленных событий
- [ ] Вызов conflict_resolver при конфликтах
- [ ] Применение изменений
- [ ] Обновление маппинга
- [ ] Логирование операций
- [ ] Обработка ошибок и откат

**src/sync/conflict_resolver.py**
- [ ] Класс ConflictResolver
- [ ] detect_conflict(google_event, lotus_event)
- [ ] resolve_conflict(strategy, conflict_data)
- [ ] Стратегии:
  - [ ] manual - сохранить в БД
  - [ ] google_wins - приоритет Google
  - [ ] lotus_wins - приоритет Lotus
  - [ ] latest_wins - по timestamp
  - [ ] merge - объединение полей

**src/sync/mapper.py**
- [ ] Класс EventMapper
- [ ] google_to_unified(google_event)
- [ ] lotus_to_unified(lotus_event)
- [ ] unified_to_google(unified_event)
- [ ] unified_to_lotus(unified_event)
- [ ] Обработка временных зон
- [ ] Обработка повторяющихся событий
- [ ] Валидация данных

**Приоритет:** 🔴 КРИТИЧЕСКИЙ  
**Оценка времени:** 3-4 дня  
**Назначено:** -

---

### 🟢 Фаза 5: Утилиты (СРЕДНИЙ ПРИОРИТЕТ)

**src/utils/logger.py**
- [ ] Настройка логирования
- [ ] Цветные логи для консоли
- [ ] Ротация файлов логов
- [ ] Уровни логирования для модулей

**src/utils/config.py**
- [ ] Загрузка config.yaml
- [ ] Валидация конфигурации
- [ ] Загрузка .env переменных
- [ ] Доступ к настройкам

**src/utils/validators.py**
- [ ] Валидация событий
- [ ] Валидация дат
- [ ] Валидация email (участники)
- [ ] Валидация конфигурации

**src/utils/platform_utils.py**
- [ ] Определение платформы
- [ ] Пути к файлам (кроссплатформенные)
- [ ] Системные команды

**Приоритет:** 🟢 СРЕДНИЙ  
**Оценка времени:** 1 день  
**Назначено:** -

---

### 🟣 Фаза 6: CLI Интерфейс (ВЫСОКИЙ ПРИОРИТЕТ)

**src/main.py** (дополнить существующий)
- [ ] Команда `init`
  - [ ] Проверка зависимостей
  - [ ] Создание config.yaml из примера
  - [ ] Создание .env
  - [ ] Создание БД
  - [ ] Настройка Google OAuth
- [ ] Команда `sync`
  - [ ] Загрузка конфигурации
  - [ ] Инициализация адаптеров
  - [ ] Запуск sync engine
  - [ ] Вывод статистики
  - [ ] Поддержка --dry-run
  - [ ] Поддержка --verbose
- [ ] Команда `status`
  - [ ] Информация о последней синхронизации
  - [ ] Статус подключений
  - [ ] Количество событий
  - [ ] Неразрешенные конфликты
- [ ] Команда `conflicts`
  - [ ] list - список конфликтов
  - [ ] show - детали конфликта
  - [ ] resolve - разрешить конфликт
  - [ ] resolve-all - разрешить все
- [ ] Команда `config`
  - [ ] show - показать настройки
  - [ ] set - установить параметр
  - [ ] reset - сброс к умолчаниям
- [ ] Команда `daemon`
  - [ ] Запуск в фоне
  - [ ] Автоматическая синхронизация по расписанию
  - [ ] Graceful shutdown

**Приоритет:** 🟠 ВЫСОКИЙ  
**Оценка времени:** 2 дня  
**Назначено:** -

---

### 🔶 Фаза 7: Автоматизация (СРЕДНИЙ ПРИОРИТЕТ)

**Windows Service**
- [ ] src/service/windows_service.py
- [ ] Установка через pywin32
- [ ] Команды: install, start, stop, uninstall
- [ ] Логирование в Event Log
- [ ] Обработка ошибок

**Linux systemd**
- [ ] Улучшить scripts/install_service.sh
- [ ] Настройка логирования в journald
- [ ] Restart policy
- [ ] Документация

**Планировщик**
- [ ] src/scheduler.py
- [ ] Интеграция APScheduler
- [ ] Настройка интервала
- [ ] Обработка ошибок при автозапуске

**Приоритет:** 🟡 СРЕДНИЙ  
**Оценка времени:** 2 дня  
**Назначено:** -

---

### 🧪 Фаза 8: Тестирование (ВЫСОКИЙ ПРИОРИТЕТ)

**Unit тесты**
- [ ] tests/test_google_adapter.py
- [ ] tests/test_lotus_adapter.py
- [ ] tests/test_sync_engine.py
- [ ] tests/test_conflict_resolver.py
- [ ] tests/test_mapper.py
- [ ] tests/test_database.py
- [ ] Покрытие > 80%

**Integration тесты**
- [ ] tests/integration/test_google_integration.py
- [ ] tests/integration/test_lotus_integration.py
- [ ] tests/integration/test_full_sync.py

**E2E тесты**
- [ ] tests/e2e/test_sync_workflow.py
- [ ] Тестирование на Windows
- [ ] Тестирование на Linux

**Fixtures и моки**
- [ ] tests/fixtures/ - тестовые данные
- [ ] tests/mocks/ - моки для API

**Приоритет:** 🟠 ВЫСОКИЙ  
**Оценка времени:** 2-3 дня  
**Назначено:** -

---

## 🎨 Улучшения (Nice to Have для v1.1+)

### Web интерфейс
- [ ] Dashboard на Flask/FastAPI
- [ ] Мониторинг синхронизации
- [ ] UI для разрешения конфликтов
- [ ] Настройка через веб

### Расширенные возможности
- [ ] Синхронизация вложений
- [ ] Поддержка категорий/тегов
- [ ] Синхронизация задач (To-Do)
- [ ] Webhooks для real-time синхронизации

### Оптимизация
- [ ] Параллельная обработка событий
- [ ] Кеширование
- [ ] Пакетные операции API

### Дополнительные интеграции
- [ ] Microsoft Outlook Calendar
- [ ] Apple iCloud Calendar
- [ ] CalDAV

---

## 📊 Метрики успеха

- [ ] Успешная синхронизация 100 событий
- [ ] Работа на Windows и Linux
- [ ] Покрытие кода тестами > 80%
- [ ] Документация полная и понятная
- [ ] CLI интерфейс интуитивный
- [ ] Производительность: < 5 сек на 100 событий

---

## 🗓️ Временная шкала

| Фаза | Задача | Время | Статус |
|------|--------|-------|--------|
| 0 | Инфраструктура | 1-2 дня | ✅ Готово |
| 1 | Google Adapter | 2-3 дня | ⏳ Не начато |
| 2 | Lotus Adapter | 3-5 дней | ⏳ Не начато |
| 3 | База данных | 1-2 дня | ⏳ Не начато |
| 4 | Sync Engine | 3-4 дня | ⏳ Не начато |
| 5 | Утилиты | 1 день | ⏳ Не начато |
| 6 | CLI | 2 дня | 🔄 В процессе (каркас) |
| 7 | Автоматизация | 2 дня | ⏳ Не начато |
| 8 | Тестирование | 2-3 дня | ⏳ Не начато |

**Общее время:** 17-24 дня разработки

---

## 🚀 Следующий шаг

**Начните с Фазы 1: Google Calendar Адаптер**

1. Создайте файл `src/adapters/google_calendar.py`
2. Реализуйте базовую OAuth авторизацию
3. Добавьте метод list_events()
4. Напишите первый unit тест
5. Итеративно добавляйте функциональность

**Команда для начала:**
```bash
touch src/adapters/google_calendar.py
touch tests/test_google_adapter.py
```

---

**Удачи в разработке! 🎯**
