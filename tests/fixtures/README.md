# Ответы API для тестов адаптеров

Откуда взяты файлы:

- `domino_events.json`, `domino_event_details.json`, `domino_calendar.json` — **по форме реального
  сервера** Domino 14.5.1 (Calendar API 14.5.1.v01_00), имена, адреса и описания обезличены:
  - `domino_events.json` — ответ `GET <db>/api/calendar/events?since&before&start&count`:
    только сводка (`x-lotus-summarydataonly`), без `description` и `last-modified`, время в UTC,
    all-day с исключающей датой конца, многодневное all-day — отдельная запись на каждый день
    с одним `href`, отменённый экземпляр — `status: "cancelled"`. Событие с `tzid` в списке
    синтетическое (реальный список отдаёт UTC) — проверяет пропуск неподдерживаемого времени.
  - `domino_event_details.json` — словарь `href -> ответ GET <href>`: `{"timezones", "events"}`,
    время с `tzid`, `description` с `\r\n`, `last-modified` вида `20261007T050045Z`,
    у meeting — `attendees` и `organizer`. Ключ-словарь — удобство тестов, не формат сервера.
  - `domino_calendar.json` — ответ `GET /api/calendar` (владелец календаря).
- `google_*.json`: Google Calendar API v3, `events.list` — **по документации**.
- `graph_*.json`: MS Graph `GET /me/calendarView` с `Prefer: outlook.timezone="UTC"` и
  `$expand=singleValueExtendedProperties(...)` — **по документации** (HTML-тело одного события
  проверяет перевод в текст).

Заменяйте файлы по документации реальными ответами (с вычищенными личными данными), когда они
появятся: формат, который реально присылает сервер, и есть то, что должны проверять эти тесты.
