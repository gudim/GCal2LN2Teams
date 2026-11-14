# ✅ Чек-лист для тестирования Google OAuth авторизации

## Перед началом

- [ ] У вас есть Google аккаунт
- [ ] Вы находитесь в директории `~/gcaltolotus`
- [ ] Есть доступ к интернету

---

## Шаг 1: Получение Google Credentials

### 1.1 Создать проект в Google Cloud Console
- [ ] Открыть https://console.cloud.google.com/
- [ ] Нажать "Select a project" → "New Project"
- [ ] Название: "GCalSync" (или любое)
- [ ] Нажать "Create"

### 1.2 Включить Google Calendar API
- [ ] В навигации: "APIs & Services" → "Library"
- [ ] Найти "Google Calendar API"
- [ ] Нажать на API
- [ ] Нажать "Enable"
- [ ] Дождаться активации

### 1.3 Создать OAuth 2.0 Credentials
- [ ] "APIs & Services" → "Credentials"
- [ ] Нажать "+ CREATE CREDENTIALS"
- [ ] Выбрать "OAuth client ID"
- [ ] Если попросит - настроить OAuth consent screen:
  - [ ] User Type: External
  - [ ] App name: "GCalSync"
  - [ ] User support email: ваш email
  - [ ] Developer contact: ваш email
  - [ ] Нажать "Save and Continue"
  - [ ] Scopes: пропустить (Skip)
  - [ ] Test users: добавить свой email
  - [ ] Нажать "Save and Continue"
- [ ] Application type: **Desktop app**
- [ ] Name: "GCalSync Desktop"
- [ ] Нажать "Create"
- [ ] Нажать "Download JSON" (иконка скачивания)
- [ ] Сохранить файл

### 1.4 Поместить credentials в проект
```bash
# Создать директорию config
mkdir -p config

# Скопировать скачанный файл
cp ~/Downloads/client_secret_*.json config/google_credentials.json

# Проверить
ls -la config/google_credentials.json
```
- [ ] Файл `config/google_credentials.json` существует

---

## Шаг 2: Подготовка окружения

### 2.1 Виртуальное окружение
```bash
# Создать (если еще не создано)
python3 -m venv venv

# Активировать
source venv/bin/activate

# Проверить
which python  # Должно быть: .../gcaltolotus/venv/bin/python
```
- [ ] Виртуальное окружение активировано
- [ ] В промпте появилось `(venv)`

### 2.2 Установка зависимостей
```bash
# Обновить pip
pip install --upgrade pip

# Установить зависимости
pip install google-auth google-auth-oauthlib google-auth-httplib2 google-api-python-client click
```
- [ ] Все пакеты установлены без ошибок
- [ ] Можно импортировать: `python -c "import google.auth; print('OK')"`

---

## Шаг 3: Первая авторизация

### Вариант A: Через CLI (рекомендуется)

```bash
python -m src.main init
```

**Ожидаемое поведение:**
- [ ] Выводится сообщение "🔐 Начало процесса авторизации..."
- [ ] Автоматически открывается браузер
- [ ] В браузере страница "Sign in with Google"
- [ ] После входа: страница разрешений для приложения
- [ ] Возможно предупреждение "This app isn't verified"
  - [ ] Нажмите "Advanced"
  - [ ] Нажмите "Go to GCalSync (unsafe)"
- [ ] Выбираете Google аккаунт
- [ ] Предоставляете разрешения (Allow)
- [ ] Страница успеха: "The authentication flow has completed"
- [ ] В консоли: "✅ Авторизация успешно завершена!"
- [ ] Показывается информация о календаре
- [ ] Создан файл `data/google_token.pickle`

### Вариант B: Через тестовый скрипт

```bash
python scripts/test_google_auth.py
```

**Те же шаги, что и в Варианте A**

### Вариант C: Автоматический тест

```bash
./scripts/quick_test.sh
```

- [ ] Скрипт завершился успешно
- [ ] Выведено "✅ Тест завершен успешно!"

---

## Шаг 4: Проверка работы

### 4.1 Проверка подключения
```bash
python -m src.main test-google
```

**Ожидается:**
- [ ] "✅ Подключение успешно!"
- [ ] Показано название календаря
- [ ] Показана временная зона
- [ ] Показано количество предстоящих событий

### 4.2 Список календарей
```bash
python -m src.main list-calendars
```

**Ожидается:**
- [ ] Показан список всех ваших календарей
- [ ] Указан PRIMARY календарь
- [ ] Для каждого показан ID и права доступа

### 4.3 Проверка токена
```bash
ls -la data/google_token.pickle
```
- [ ] Файл существует
- [ ] Размер > 0 байт
- [ ] Создан недавно (в момент авторизации)

---

## Шаг 5: Повторная авторизация

### 5.1 Проверка автоматического использования токена
```bash
# Запустить снова - браузер НЕ должен открыться
python -m src.main test-google
```
- [ ] Браузер НЕ открылся
- [ ] Использован существующий токен
- [ ] Подключение успешно

### 5.2 Принудительная переавторизация
```bash
python -m src.main init --force
```
- [ ] Браузер открылся снова
- [ ] Можно авторизоваться заново
- [ ] Токен обновлен

---

## Шаг 6: Отзыв доступа

### 6.1 Через CLI
```bash
python -m src.main revoke-google
```
- [ ] Подтверждаете отзыв (y)
- [ ] "✅ Доступ успешно отозван"
- [ ] Файл `data/google_token.pickle` удален

### 6.2 Через Google Account
- [ ] Открыть https://myaccount.google.com/permissions
- [ ] Найти приложение "GCalSync"
- [ ] Нажать "Remove access"
- [ ] Подтвердить

---

## Проверка логов

```bash
# Посмотреть логи
cat logs/gcalsync.log

# В реальном времени
tail -f logs/gcalsync.log
```

**Что должно быть в логах:**
- [ ] Записи об авторизации
- [ ] Успешное подключение
- [ ] Нет ERROR записей (или они ожидаемые)

---

## Устранение проблем

### Браузер не открывается
- [ ] URL выведен в консоли
- [ ] Скопировали и открыли вручную

### Ошибка "invalid_grant"
```bash
rm data/google_token.pickle
python -m src.main init --force
```
- [ ] Токен удален
- [ ] Переавторизация прошла успешно

### Ошибка импорта модулей
```bash
# Переустановить зависимости
pip install --force-reinstall google-auth google-auth-oauthlib google-auth-httplib2 google-api-python-client
```
- [ ] Переустановка успешна
- [ ] Импорт работает

### "This app isn't verified"
- [ ] Нажали "Advanced"
- [ ] Нажали "Go to ... (unsafe)"
- [ ] Это нормально для dev приложений

---

## Финальная проверка

### Все команды работают:
- [ ] `python -m src.main init` - авторизация
- [ ] `python -m src.main test-google` - проверка
- [ ] `python -m src.main list-calendars` - список
- [ ] `python -m src.main revoke-google` - отзыв

### Файлы созданы:
- [ ] `config/google_credentials.json` - credentials
- [ ] `data/google_token.pickle` - токен (после авторизации)
- [ ] `logs/gcalsync.log` - логи

### Логи чистые:
- [ ] Нет критических ошибок
- [ ] OAuth flow завершен успешно
- [ ] Подключение к календарю работает

---

## 🎉 Готово!

Если все пункты отмечены ✅, то Google OAuth 2.0 SSO полностью работает!

### Что дальше?

Теперь можно:
1. Добавить методы для работы с событиями (list, create, update, delete)
2. Реализовать Lotus Notes адаптер
3. Создать движок синхронизации

### Полезные команды:

```bash
# Посмотреть помощь
python -m src.main --help

# Помощь по команде
python -m src.main init --help

# Запустить в debug режиме
LOG_LEVEL=DEBUG python -m src.main test-google
```

---

**Дата:** 17 ноября 2025  
**Статус:** ✅ Google OAuth реализован и готов к использованию
