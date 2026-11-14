# Использование Domino REST API

## ✅ У вас ЕСТЬ доступ к Domino REST API!

Ваш сервер: `https://domino.example.com`

API endpoint: `https://domino.example.com/api/calendar`

## Тестирование подключения

### Попробуйте разные форматы username

Domino может требовать разные форматы авторизации:

#### 1. Distinguished Name (рекомендуется)

```bash
python -m src.main test-domino \
  --server https://domino.example.com \
  --username "Ivan Ivanov/ORG" \
  --no-verify-ssl
```

#### 2. Email

```bash
python -m src.main test-domino \
  --server https://domino.example.com \
  --username "user@example.com" \
  --no-verify-ssl
```

#### 3. Short name

```bash
python -m src.main test-domino \
  --server https://domino.example.com \
  --username "IIvanov" \
  --no-verify-ssl
```

#### 4. Полный DN

```bash
python -m src.main test-domino \
  --server https://domino.example.com \
  --username "CN=Ivan Ivanov/O=ORG" \
  --no-verify-ssl
```

## Проверка через curl

Попробуйте разные credentials:

```bash
# Distinguished Name
curl -k -u "Ivan Ivanov/ORG:YOUR_PASSWORD" \
  https://domino.example.com/api/calendar

# Email
curl -k -u "user@example.com:YOUR_PASSWORD" \
  https://domino.example.com/api/calendar

# Full DN
curl -k -u "CN=Ivan Ivanov/O=ORG:YOUR_PASSWORD" \
  https://domino.example.com/api/calendar
```

Какой формат вернет HTTP 200 - тот и используйте.

## После успешного подключения

### Просмотр событий

```bash
python -m src.main domino-events \
  --server https://domino.example.com \
  --username "CORRECT_USERNAME" \
  --no-verify-ssl \
  --limit 10
```

### Синхронизация с Google Calendar

После того как оба адаптера работают:

```bash
python -m src.main sync \
  --source domino \
  --target google
```

## Преимущества Domino REST API

✅ **Работает на Linux нативно** - не нужен Wine
✅ **Быстрее чем COM** - прямые HTTP запросы
✅ **Надежнее** - стандартный REST протокол
✅ **Безопаснее** - HTTPS, современная авторизация
✅ **Проще отлаживать** - можно тестировать через curl/Postman

## Структура ответа API

Ваш календарь возвращает:

```json
{
  "owner": {
    "displayName": "Ivan Ivanov",
    "distinguishedName": "Ivan Ivanov/ORG",
    "homeServer": "DOMINO/ORG",
    "email": "user@example.com"
  },
  "links": [
    {
      "rel": "events",
      "href": "/mail/iivanov.nsf/api/calendar/events"
    },
    {
      "rel": "invitations",
      "href": "/mail/iivanov.nsf/api/calendar/invitations"
    }
  ]
}
```

## URLs

- **Calendar Info**: `/api/calendar`
- **Events**: `/mail/iivanov.nsf/api/calendar/events`
- **Invitations**: `/mail/iivanov.nsf/api/calendar/invitations`
- **Specific Event**: `/mail/iivanov.nsf/api/calendar/events/{id}`

## Полная функциональность

REST адаптер поддерживает:

- ✅ Список событий
- ✅ Получение события по ID
- ✅ Создание события
- ✅ Обновление события
- ✅ Удаление события
- ✅ Фильтрация по дате
- ✅ Приглашения

Все работает **БЕЗ Wine**!

## Следующие шаги

1. Найдите правильный формат username через curl
2. Протестируйте через `test-domino`
3. Посмотрите события через `domino-events`
4. Настройте синхронизацию с Google

## Сравнение: COM vs REST

| Параметр | COM через Wine | REST API |
|----------|----------------|----------|
| Работает на Linux | ❌ Нет | ✅ Да |
| Скорость | Медленно | Быстро |
| Надежность | Низкая | Высокая |
| Отладка | Сложно | Легко |
| Зависимости | Wine, VBScript | Только Python + requests |
| **Рекомендация** | ❌ Не использовать | ✅ **ИСПОЛЬЗОВАТЬ** |

REST API - это идеальное решение для вашего случая!
