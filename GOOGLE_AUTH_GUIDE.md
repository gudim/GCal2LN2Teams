# 🔐 Google OAuth 2.0 SSO - Руководство по использованию

## ✅ Реализованная функциональность

### Что работает:
- ✅ OAuth 2.0 авторизация через браузер
- ✅ Автоматическое открытие браузера для входа в Google
- ✅ Сохранение и автоматическое обновление токенов
- ✅ Проверка подключения к Google Calendar
- ✅ Получение списка календарей
- ✅ Отзыв доступа
- ✅ CLI команды для управления авторизацией

---

## 🚀 Быстрый старт

### Шаг 1: Получить Google Credentials

1. Перейдите в [Google Cloud Console](https://console.cloud.google.com/)
2. Создайте новый проект или выберите существующий
3. Включите **Google Calendar API**:
   - Navigation → APIs & Services → Library
   - Найдите "Google Calendar API"
   - Нажмите "Enable"
4. **Настройте OAuth consent screen** (ВАЖНО!):
   - APIs & Services → OAuth consent screen
   - User Type: **External**
   - App name: "GCalSync"
   - User support email: ваш email
   - Developer contact: ваш email
   - Нажмите "Save and Continue"
   - Scopes: пропустите (Skip)
   - **Test users:** Нажмите "+ ADD USERS"
     - **Добавьте ваш email** (которым будете входить!)
     - Это критически важно! Без этого будет ошибка 403
   - Нажмите "Save and Continue"
5. Создайте **OAuth 2.0 credentials**:
   - APIs & Services → Credentials
   - Create Credentials → OAuth client ID
   - Application type: **Desktop app**
   - Название: "GCalSync" (или любое другое)
6. Скачайте JSON файл
7. Сохраните как `config/google_credentials.json`

### Шаг 2: Установить зависимости

```bash
cd ~/gcaltolotus
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

### Шаг 3: Первая авторизация

```bash
# Способ 1: Через CLI (рекомендуется)
python -m src.main init

# Способ 2: Через тестовый скрипт
python scripts/test_google_auth.py
```

**Что произойдет:**
1. 🌐 Откроется браузер с страницей авторизации Google
2. 🔑 Вы войдете в свой Google аккаунт
3. ✅ Предоставите разрешения приложению
4. 💾 Токен будет сохранен в `data/google_token.pickle`
5. ✨ При следующем запуске авторизация не понадобится!

---

## 📚 Доступные команды

### Инициализация и авторизация

```bash
# Первая авторизация
python -m src.main init

# С указанием пути к credentials
python -m src.main init --google-credentials=/path/to/credentials.json

# С указанием конкретного календаря
python -m src.main init --calendar-id=your_calendar_id@group.calendar.google.com

# Принудительная повторная авторизация (пересоздать токен)
python -m src.main init --force
```

### Проверка подключения

```bash
# Проверить подключение к Google Calendar
python -m src.main test-google

# Проверить подключение к конкретному календарю
python -m src.main test-google --calendar-id=other_calendar_id
```

### Просмотр календарей

```bash
# Показать список всех доступных календарей
python -m src.main list-calendars
```

**Вывод:**
```
📋 Получение списка календарей Google...

Найдено календарей: 3

1. Личный [PRIMARY] ✓
   ID: your_email@gmail.com
   Доступ: owner
   Временная зона: Europe/Moscow

2. Рабочий
   ID: work_calendar@group.calendar.google.com
   Доступ: writer
   Временная зона: Europe/Moscow

3. Праздники в России
   ID: ru.russian#holiday@group.v.calendar.google.com
   Доступ: reader
   Временная зона: Europe/Moscow
```

### Отзыв доступа

```bash
# Отозвать доступ и удалить токен
python -m src.main revoke-google
```

---

## 🔧 Программное использование

### Базовый пример

```python
from adapters.google_calendar import GoogleCalendarAdapter

# Создать адаптер
adapter = GoogleCalendarAdapter(
    credentials_file="config/google_credentials.json",
    token_file="data/google_token.pickle",
    calendar_id="primary"  # или конкретный ID
)

# Авторизоваться (откроется браузер при первом запуске)
adapter.authenticate()

# Проверить подключение
info = adapter.test_connection()
print(f"Подключен к: {info['calendar_name']}")
print(f"Временная зона: {info['timezone']}")
print(f"Предстоящих событий: {info['upcoming_events_count']}")

# Получить список календарей
calendars = adapter.list_calendars()
for cal in calendars:
    print(f"- {cal['summary']} ({cal['id']})")
```

### Принудительная повторная авторизация

```python
# Удалить старый токен и получить новый
adapter.authenticate(force_reauth=True)
```

### Проверка статуса авторизации

```python
if adapter.is_authenticated():
    print("✅ Авторизован")
else:
    print("❌ Не авторизован")
```

### Отзыв доступа

```python
# Отозвать токен и удалить файл
adapter.revoke_access()
```

---

## 🔑 OAuth Flow - Детали

### Что происходит при авторизации:

1. **Проверка существующего токена**
   - Если токен есть и валиден → используется
   - Если токен истек, но есть refresh_token → обновляется
   - Если токена нет → запускается OAuth flow

2. **OAuth 2.0 Flow**
   ```
   ┌─────────────────┐
   │ Приложение      │
   │ запускает       │──┐
   │ локальный сервер│  │
   └─────────────────┘  │
                        │
   ┌─────────────────┐  │  1. Redirect URL
   │ Браузер         │◄─┘
   │ открывается     │
   └─────────────────┘
           │
           │ 2. Пользователь входит
           │    и дает разрешения
           ▼
   ┌─────────────────┐
   │ Google OAuth    │
   │ Server          │
   └─────────────────┘
           │
           │ 3. Redirect с кодом
           ▼
   ┌─────────────────┐
   │ Локальный сервер│
   │ получает код    │──┐
   └─────────────────┘  │
                        │ 4. Обмен кода на токен
   ┌─────────────────┐  │
   │ Приложение      │◄─┘
   │ сохраняет токен │
   └─────────────────┘
   ```

3. **Сохранение токена**
   - Токен сохраняется в `data/google_token.pickle`
   - При следующем запуске браузер не нужен
   - Токен автоматически обновляется при истечении

### Запрашиваемые разрешения (Scopes):

```python
SCOPES = [
    'https://www.googleapis.com/auth/calendar',           # Полный доступ
    'https://www.googleapis.com/auth/calendar.events'     # Управление событиями
]
```

**Что это позволяет:**
- ✅ Читать события календаря
- ✅ Создавать новые события
- ✅ Обновлять существующие события
- ✅ Удалять события
- ✅ Управлять календарями

---

## 🛠️ Структура файлов

```
gcaltolotus/
├── config/
│   └── google_credentials.json       # Credentials от Google (НЕ коммитить!)
├── data/
│   └── google_token.pickle           # Токен авторизации (НЕ коммитить!)
├── src/
│   ├── adapters/
│   │   └── google_calendar.py        # GoogleCalendarAdapter класс
│   ├── utils/
│   │   └── logger.py                 # Логирование
│   └── main.py                       # CLI команды
└── scripts/
    └── test_google_auth.py           # Тестовый скрипт
```

---

## 🔒 Безопасность

### ⚠️ ВАЖНО: Не коммитить секреты!

`.gitignore` уже настроен для исключения:
```gitignore
config/google_credentials.json
data/google_token.pickle
*.pem
*.key
.env
```

### Рекомендации:

1. **Credentials файл**
   - Храните в безопасном месте
   - Не публикуйте в публичных репозиториях
   - Используйте переменные окружения в production

2. **Токен**
   - Автоматически обновляется
   - Можно отозвать через команду `revoke-google`
   - Можно отозвать в [Google Account Settings](https://myaccount.google.com/permissions)

3. **Права доступа**
   - Приложение запрашивает только необходимые разрешения
   - Можно ограничить доступ в Google Cloud Console

---

## 🐛 Устранение проблем

### Проблема: Ошибка "403: access_denied"

**Сообщение:**
```
Мы пока не проверили приложение "GCalSync". 
Ошибка 403: access_denied
```

**Причина:** Вы не добавлены в список тестировщиков.

**Решение:**
1. Откройте https://console.cloud.google.com/apis/credentials/consent
2. Выберите ваш проект
3. Прокрутите до секции "Test users"
4. Нажмите "+ ADD USERS"
5. Добавьте ваш email (которым входите в Google)
6. Сохраните
7. Удалите токен: `rm data/google_token.pickle`
8. Авторизуйтесь заново: `python -m src.main init`

**Подробнее:** См. файл `TROUBLESHOOTING_403.md`

---

### Проблема: Браузер не открывается

**Решение:**
- URL будет выведен в консоль
- Скопируйте и откройте вручную в браузере

### Проблема: "invalid_grant" ошибка

**Решение:**
```bash
# Удалить токен и авторизоваться заново
rm data/google_token.pickle
python -m src.main init --force
```

### Проблема: "Access blocked" при авторизации

**Причина:** Приложение не верифицировано Google

**Решение:**
1. В окне авторизации нажмите "Advanced"
2. Нажмите "Go to [Your App Name] (unsafe)"
3. Подтвердите разрешения

**Для production:** Пройдите процесс верификации в Google Cloud Console

### Проблема: Истек quota API

**Решение:**
- Проверьте квоты в [Google Cloud Console](https://console.cloud.google.com/apis/dashboard)
- Увеличьте лимиты или подождите сброса (обычно в полночь UTC)

---

## 📊 Логи

Логи сохраняются в `logs/gcalsync.log`

```bash
# Просмотр логов в реальном времени
tail -f logs/gcalsync.log

# Поиск ошибок
grep ERROR logs/gcalsync.log
```

---

## ✅ Что реализовано

- [x] OAuth 2.0 авторизация через браузер
- [x] Автоматическое обновление токенов
- [x] Сохранение токенов
- [x] Проверка подключения
- [x] Получение информации о календаре
- [x] Список всех календарей
- [x] Отзыв доступа
- [x] CLI команды
- [x] Логирование
- [x] Обработка ошибок

## 🚧 Что будет дальше

- [ ] Получение событий из календаря
- [ ] Создание событий
- [ ] Обновление событий
- [ ] Удаление событий
- [ ] Синхронизация с Lotus Notes

---

## 📝 Примеры использования

### Пример 1: Проверка авторизации

```bash
# Авторизоваться
python -m src.main init

# Проверить подключение
python -m src.main test-google

# Вывод:
# 🔌 Проверка подключения к Google Calendar...
# 
# ✅ Подключение успешно!
# 
# 📅 Календарь: Личный
# 🕐 Временная зона: Europe/Moscow
# 📊 Предстоящих событий: 5
```

### Пример 2: Работа с несколькими календарями

```bash
# Посмотреть список календарей
python -m src.main list-calendars

# Авторизоваться для конкретного календаря
python -m src.main init --calendar-id=work@group.calendar.google.com

# Проверить этот календарь
python -m src.main test-google --calendar-id=work@group.calendar.google.com
```

### Пример 3: Переавторизация

```bash
# Отозвать текущий доступ
python -m src.main revoke-google

# Авторизоваться заново
python -m src.main init --force
```

---

**Авторизация Google Calendar готова к использованию! 🎉**
