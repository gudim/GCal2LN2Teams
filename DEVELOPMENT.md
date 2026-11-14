# Быстрый старт для разработчиков

## Клонирование и настройка

```bash
# Клонировать репозиторий
git clone https://github.com/yourusername/gcaltolotus.git
cd gcaltolotus

# Создать виртуальное окружение
python3 -m venv venv
source venv/bin/activate  # Linux/Mac
# или
venv\Scripts\activate  # Windows

# Установить зависимости для разработки
pip install -r requirements-dev.txt

# Установить пакет в режиме разработки
pip install -e .
```

## Структура проекта

```
src/
├── adapters/          # Адаптеры для Google и Lotus Notes
├── sync/              # Логика синхронизации
├── database/          # Модели и работа с БД
├── utils/             # Вспомогательные утилиты
└── main.py            # CLI интерфейс

config/                # Конфигурационные файлы
tests/                 # Тесты
scripts/               # Вспомогательные скрипты
```

## Основные команды

### Разработка

```bash
# Запустить линтер
flake8 src/

# Форматирование кода
black src/

# Сортировка импортов
isort src/

# Проверка типов
mypy src/

# Запустить все проверки
pre-commit run --all-files
```

### Тестирование

```bash
# Запустить все тесты
pytest

# С покрытием кода
pytest --cov=src --cov-report=html

# Конкретный тест
pytest tests/test_google_adapter.py -v
```

### Запуск приложения

```bash
# Инициализация
python -m src.main init

# Синхронизация (dry-run)
python -m src.main sync --dry-run

# Реальная синхронизация
python -m src.main sync

# Просмотр статуса
python -m src.main status
```

## Git workflow

1. Создать ветку для фичи: `git checkout -b feature/my-feature`
2. Внести изменения
3. Запустить тесты и линтеры
4. Закоммитить: `git commit -m "feat: add new feature"`
5. Создать Pull Request

## Соглашения о коммитах

Используем Conventional Commits:

- `feat:` - новая функциональность
- `fix:` - исправление бага
- `docs:` - изменения в документации
- `style:` - форматирование кода
- `refactor:` - рефакторинг кода
- `test:` - добавление тестов
- `chore:` - рутинные задачи

## Полезные ссылки

- [Google Calendar API](https://developers.google.com/calendar)
- [Lotus Notes API](https://help.hcltechsw.com/dom_designer/)
- [SQLAlchemy](https://www.sqlalchemy.org/)
