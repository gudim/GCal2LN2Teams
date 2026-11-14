# Тестовые скрипты

## Доступные тесты

### 1. Полный интеграционный тест

Тестирует Google Calendar + Domino REST API:

```bash
./scripts/test_full_integration.sh
```

### 2. Быстрый тест Google Calendar

```bash
./scripts/quick_test.sh
```

### 3. Проверка Domino API

Интерактивная проверка доступности Domino REST API:

```bash
./scripts/check_domino_api.sh
```

### 4. Настройка Google OAuth

```bash
./scripts/setup_google_auth.sh
```

## Через CLI

### Google Calendar

```bash
# Инициализация
python -m src.main init

# Тест
python -m src.main test-google

# Список календарей
python -m src.main list-calendars
```

### Domino REST API

```bash
# Тест подключения
python -m src.main test-domino \
  --server https://domino.example.com \
  --username "YOUR_USERNAME" \
  --no-verify-ssl

# Список событий
python -m src.main domino-events \
  --server https://domino.example.com \
  --username "YOUR_USERNAME" \
  --no-verify-ssl \
  --limit 10
```

## Удаленные скрипты

Следующие скрипты удалены, так как VBS мост не работает на Linux:

- ❌ `test_vbs_bridge.sh` - тест VBScript моста
- ❌ `test_hcl_notes.sh` - тест HCL Notes через Wine
- ❌ `notes_bridge.vbs` - VBScript для COM API

**Вместо этого используйте Domino REST API** - работает нативно на Linux!
