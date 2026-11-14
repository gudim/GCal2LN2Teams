# 📅 Google Calendar ↔ HCL Lotus Notes Sync

Кроссплатформенное приложение на Python для двусторонней синхронизации событий между Google Calendar и HCL Lotus Notes.

![Python Version](https://img.shields.io/badge/python-3.8+-blue.svg)
![Platform](https://img.shields.io/badge/platform-Windows%20%7C%20Linux-lightgrey.svg)
![License](https://img.shields.io/badge/license-MIT-green.svg)

---

## 🌟 Основные возможности

- ✅ **Двусторонняя синхронизация** между Google Calendar и Lotus Notes
- 🔄 **Автоматическая синхронизация** по расписанию
- ⚡ **Инкрементальные обновления** - синхронизируются только изменения
- 🔍 **Умное разрешение конфликтов** с несколькими стратегиями
- 🪟 **Кроссплатформенность** - работает на Windows и Linux
- 📊 **Детальное логирование** всех операций
- 🛡️ **Безопасность** - шифрование токенов и credentials
- 🎯 **Гибкая настройка** через YAML конфигурацию
- 📱 **CLI интерфейс** для управления синхронизацией

---

## 📋 Содержание

- [Требования](#-требования)
- [Установка](#-установка)
  - [Windows](#windows)
  - [Linux](#linux)
- [Первоначальная настройка](#-первоначальная-настройка)
- [Использование](#-использование)
- [Конфигурация](#-конфигурация)
- [Разрешение конфликтов](#-разрешение-конфликтов)
- [Автоматизация](#-автоматизация)
- [Устранение неполадок](#-устранение-неполадок)
- [FAQ](#-faq)

---

## 🔧 Требования

### Общие требования
- Python 3.8 или выше
- Активный аккаунт Google с доступом к Google Calendar
- Установленный HCL Lotus Notes (версия 8.5+)

### Платформо-специфичные требования

#### Windows
- Windows 7 SP1 / Windows Server 2008 R2 или выше
- Lotus Notes должен быть установлен и настроен
- Права администратора для установки службы (опционально)

#### Linux
- Ubuntu 20.04+, Debian 10+, или другой современный дистрибутив
- Wine 5.0+ (для работы с Lotus Notes через COM API)
- Альтернативно: доступ к Domino REST API

---

## 📦 Установка

### Windows

#### Шаг 1: Установка Python

1. Скачайте Python с [python.org](https://www.python.org/downloads/)
2. Запустите установщик
3. ✅ Отметьте "Add Python to PATH"
4. Нажмите "Install Now"

#### Шаг 2: Клонирование репозитория

```cmd
cd C:\Projects
git clone https://github.com/yourusername/gcaltolotus.git
cd gcaltolotus
```

#### Шаг 3: Создание виртуального окружения

```cmd
python -m venv venv
venv\Scripts\activate
```

#### Шаг 4: Установка зависимостей

```cmd
pip install --upgrade pip
pip install -r requirements.txt
```

#### Шаг 5: Установка пакета

```cmd
pip install -e .
```

---

### Linux

#### Шаг 1: Установка Python и зависимостей

```bash
# Ubuntu/Debian
sudo apt update
sudo apt install python3 python3-pip python3-venv git

# Fedora/RHEL
sudo dnf install python3 python3-pip git
```

#### Шаг 2: Установка Wine (для работы с Lotus Notes)

```bash
# Ubuntu/Debian
sudo dpkg --add-architecture i386
sudo apt update
sudo apt install wine64 wine32

# Fedora
sudo dnf install wine

# Проверка установки
wine --version
```

#### Шаг 3: Клонирование репозитория

```bash
cd ~/Development
git clone https://github.com/yourusername/gcaltolotus.git
cd gcaltolotus
```

#### Шаг 4: Создание виртуального окружения

```bash
python3 -m venv venv
source venv/bin/activate
```

#### Шаг 5: Установка зависимостей

```bash
pip install --upgrade pip
pip install -r requirements.txt
```

#### Шаг 6: Установка пакета

```bash
pip install -e .
```

---

## 🚀 Первоначальная настройка

### 1. Настройка Google Calendar API

#### Создание проекта в Google Cloud Console

1. Перейдите в [Google Cloud Console](https://console.cloud.google.com/)
2. Создайте новый проект или выберите существующий
3. Включите **Google Calendar API**:
   - Навигация → APIs & Services → Library
   - Найдите "Google Calendar API"
   - Нажмите "Enable"

4. Создайте OAuth 2.0 credentials:
   - APIs & Services → Credentials
   - Create Credentials → OAuth client ID
   - Application type: **Desktop app**
   - Скачайте JSON файл с credentials

5. Сохраните файл как `config/google_credentials.json`

#### Первая авторизация

```bash
# Запустите скрипт настройки Google авторизации
gcalsync init --google-credentials=config/google_credentials.json
```

Откроется браузер для авторизации. После успешной авторизации токен будет сохранен в `data/google_token.pickle`.

---

### 2. Настройка Lotus Notes

#### Windows

Lotus Notes должен быть установлен и настроен. Убедитесь, что:
- Notes клиент запущен хотя бы раз
- Настроен доступ к почтовой базе данных (mail.nsf)
- У вас есть пароль к ID файлу

Проверка подключения:

```cmd
gcalsync test-lotus
```

#### Linux (через Wine)

1. Установите Lotus Notes в Wine:

```bash
# Скачайте установщик Notes для Windows
# Установите через Wine
wine setup.exe
```

2. Настройте переменные окружения:

```bash
export WINEPREFIX="$HOME/.wine"
export NOTES_PATH="$HOME/.wine/drive_c/Program Files/IBM/Notes"
```

3. Обновите конфигурацию в `config/config.yaml`:

```yaml
lotus:
  platform: "linux"
  wine_path: "/usr/bin/wine"
  notes_path: "~/.wine/drive_c/Program Files/IBM/Notes"
```

4. Проверка:

```bash
gcalsync test-lotus
```

---

### 3. Базовая конфигурация

Создайте файл конфигурации:

```bash
cp config/config.yaml.example config/config.yaml
```

Отредактируйте `config/config.yaml`:

```yaml
sync:
  direction: "bidirectional"  # google-to-lotus, lotus-to-google, bidirectional
  interval_minutes: 15
  conflict_resolution: "latest_wins"  # manual, google_wins, lotus_wins, latest_wins

google:
  credentials_file: "config/google_credentials.json"
  token_file: "data/google_token.pickle"
  calendar_id: "primary"

lotus:
  platform: "auto"  # auto определит платформу автоматически
  server: ""        # пусто для локальной базы
  database: ""      # пусто для mail.nsf по умолчанию
```

---

## 💻 Использование

### Основные команды

#### Инициализация

```bash
# Первоначальная настройка
gcalsync init

# С указанием credentials файла
gcalsync init --google-credentials=/path/to/credentials.json
```

#### Синхронизация

```bash
# Пробный прогон (без фактических изменений)
gcalsync sync --dry-run

# Синхронизация Google → Lotus
gcalsync sync --direction=google-to-lotus

# Синхронизация Lotus → Google
gcalsync sync --direction=lotus-to-google

# Двусторонняя синхронизация
gcalsync sync --direction=bidirectional

# Синхронизация с подробным логом
gcalsync sync --verbose
```

#### Просмотр статуса

```bash
# Общий статус
gcalsync status

# Детальная информация
gcalsync status --verbose

# Статистика синхронизации
gcalsync stats
```

#### Управление конфликтами

```bash
# Список неразрешенных конфликтов
gcalsync conflicts list

# Детальная информация о конфликте
gcalsync conflicts show --id=123

# Разрешить конфликт
gcalsync conflicts resolve --id=123 --resolution=google-wins

# Разрешить все конфликты одной стратегией
gcalsync conflicts resolve-all --resolution=latest-wins
```

#### Настройки

```bash
# Просмотр текущих настроек
gcalsync config show

# Изменение настройки
gcalsync config set sync_interval 30
gcalsync config set conflict_resolution manual

# Сброс к настройкам по умолчанию
gcalsync config reset
```

---

## ⚙️ Конфигурация

### Структура config.yaml

```yaml
# Общие настройки приложения
app:
  name: "Google Calendar to Lotus Notes Sync"
  version: "1.0.0"
  log_level: "INFO"  # DEBUG, INFO, WARNING, ERROR

# Настройки синхронизации
sync:
  # Направление синхронизации
  direction: "bidirectional"
  # google-to-lotus: только Google → Lotus
  # lotus-to-google: только Lotus → Google
  # bidirectional: двусторонняя
  
  # Интервал автоматической синхронизации (минуты)
  interval_minutes: 15
  
  # Размер пакета для обработки событий
  batch_size: 100
  
  # Стратегия разрешения конфликтов
  conflict_resolution: "manual"
  # manual: требует ручного разрешения
  # google_wins: приоритет Google Calendar
  # lotus_wins: приоритет Lotus Notes
  # latest_wins: приоритет последнего изменения
  # merge: попытка объединить изменения
  
  # Фильтры событий
  filters:
    # Синхронизировать события за последние N дней
    sync_past_days: 7
    
    # Синхронизировать события на N дней вперед
    sync_future_days: 365
    
    # Исключить события "весь день"
    exclude_all_day_events: false
    
    # Исключить события, от которых вы отказались
    exclude_declined_events: true
    
    # Синхронизировать только определенные календари
    calendar_filter: []  # пусто = все календари

# Google Calendar настройки
google:
  credentials_file: "config/google_credentials.json"
  token_file: "data/google_token.pickle"
  calendar_id: "primary"  # ID конкретного календаря или "primary"
  
  # API квоты и ограничения
  max_requests_per_minute: 60
  retry_attempts: 3
  retry_delay_seconds: 5

# Lotus Notes настройки
lotus:
  # Платформа: auto, windows, linux
  platform: "auto"
  
  # Сервер (пусто для локального)
  server: ""
  
  # База данных (пусто для mail.nsf пользователя)
  database: ""
  
  # Настройки для Linux (Wine)
  wine_path: "/usr/bin/wine"
  notes_path: "~/.wine/drive_c/Program Files/IBM/Notes"
  
  # Таймауты (секунды)
  connection_timeout: 30
  operation_timeout: 60

# База данных
database:
  # Путь к SQLite БД
  path: "data/sync.db"
  
  # Автоматическое резервное копирование
  backup_enabled: true
  backup_interval_days: 7
  backup_keep_count: 10

# Логирование
logging:
  # Файл логов
  file: "logs/gcalsync.log"
  
  # Максимальный размер файла (MB)
  max_size_mb: 10
  
  # Количество резервных копий логов
  backup_count: 5
  
  # Формат логов
  format: "%(asctime)s - %(name)s - %(levelname)s - %(message)s"
  
  # Использовать цветные логи в консоли
  colored_console: true

# Уведомления (опционально)
notifications:
  enabled: false
  
  # Email уведомления
  email:
    smtp_server: "smtp.gmail.com"
    smtp_port: 587
    use_tls: true
    from_address: "notifications@example.com"
    to_address: "admin@example.com"
    username: ""
    password: ""  # Используйте переменные окружения!
  
  # Уведомлять о событиях
  notify_on:
    - errors
    - conflicts
    # - sync_complete
    # - warnings
```

### Использование переменных окружения

Создайте файл `.env`:

```bash
cp .env.example .env
```

Отредактируйте `.env`:

```bash
# Google
GOOGLE_CREDENTIALS_FILE=config/google_credentials.json
GOOGLE_TOKEN_FILE=data/google_token.pickle

# Lotus Notes (если нужен пароль)
LOTUS_PASSWORD=your_secure_password

# Email уведомления
SMTP_USERNAME=your_email@gmail.com
SMTP_PASSWORD=your_app_password

# Уровень логирования
LOG_LEVEL=INFO
```

---

## 🔄 Разрешение конфликтов

### Типы конфликтов

Конфликт возникает, когда одно и то же событие было изменено в обеих системах с момента последней синхронизации.

### Стратегии разрешения

#### 1. Manual (Ручное)
```bash
gcalsync config set conflict_resolution manual
```
- Требует ручного вмешательства
- Конфликты сохраняются в БД
- Пользователь решает, какую версию сохранить

#### 2. Google Wins
```bash
gcalsync config set conflict_resolution google_wins
```
- Всегда приоритет версии из Google Calendar
- Изменения в Lotus перезаписываются

#### 3. Lotus Wins
```bash
gcalsync config set conflict_resolution lotus_wins
```
- Всегда приоритет версии из Lotus Notes
- Изменения в Google перезаписываются

#### 4. Latest Wins (Рекомендуется)
```bash
gcalsync config set conflict_resolution latest_wins
```
- Приоритет у последнего изменения (по timestamp)
- Автоматическое разрешение большинства конфликтов

#### 5. Merge (Экспериментально)
```bash
gcalsync config set conflict_resolution merge
```
- Попытка объединить изменения
- Может не работать для всех полей

### Ручное разрешение конфликтов

```bash
# Посмотреть список конфликтов
gcalsync conflicts list

# Результат:
# ID  | Event          | Google Updated      | Lotus Updated       | Status
# ----|----------------|---------------------|---------------------|--------
# 1   | Team Meeting   | 2025-11-17 10:00    | 2025-11-17 10:05    | Pending
# 2   | Lunch with Bob | 2025-11-17 12:00    | 2025-11-17 12:03    | Pending

# Посмотреть детали конфликта
gcalsync conflicts show --id=1

# Результат покажет детали обеих версий события

# Разрешить конфликт
gcalsync conflicts resolve --id=1 --resolution=google-wins
# или
gcalsync conflicts resolve --id=1 --resolution=lotus-wins
```

---

## ⏰ Автоматизация

### Windows Service

#### Установка службы

```cmd
# От имени администратора
gcalsync service install

# Запуск службы
gcalsync service start

# Проверка статуса
gcalsync service status

# Остановка
gcalsync service stop

# Удаление
gcalsync service uninstall
```

#### Настройка через Task Scheduler

Альтернативный способ:

1. Откройте Task Scheduler
2. Создайте новую задачу:
   - **Name:** Google Calendar Sync
   - **Trigger:** Повторять каждые 15 минут
   - **Action:** 
     - Program: `C:\Projects\gcaltolotus\venv\Scripts\python.exe`
     - Arguments: `-m gcalsync sync`
     - Start in: `C:\Projects\gcaltolotus`

---

### Linux systemd Service

#### Создание service файла

```bash
sudo nano /etc/systemd/system/gcalsync.service
```

Содержимое:

```ini
[Unit]
Description=Google Calendar to Lotus Notes Sync Service
After=network.target

[Service]
Type=simple
User=YOUR_USERNAME
WorkingDirectory=/home/YOUR_USERNAME/Development/gcaltolotus
Environment="PATH=/home/YOUR_USERNAME/Development/gcaltolotus/venv/bin"
ExecStart=/home/YOUR_USERNAME/Development/gcaltolotus/venv/bin/python -m gcalsync daemon
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

#### Управление службой

```bash
# Перезагрузить конфигурацию systemd
sudo systemctl daemon-reload

# Включить автозапуск
sudo systemctl enable gcalsync

# Запустить службу
sudo systemctl start gcalsync

# Проверить статус
sudo systemctl status gcalsync

# Просмотр логов
sudo journalctl -u gcalsync -f

# Остановить службу
sudo systemctl stop gcalsync

# Отключить автозапуск
sudo systemctl disable gcalsync
```

---

### Cron (Linux альтернатива)

```bash
# Редактировать crontab
crontab -e

# Добавить задачу (каждые 15 минут)
*/15 * * * * cd /home/YOUR_USERNAME/Development/gcaltolotus && ./venv/bin/python -m gcalsync sync >> logs/cron.log 2>&1
```

---

## 🔍 Устранение неполадок

### Проблемы с Google Calendar API

#### Ошибка: "invalid_grant"
```
Решение:
1. Удалите файл data/google_token.pickle
2. Запустите: gcalsync init --google-credentials=config/google_credentials.json
3. Пройдите авторизацию заново
```

#### Ошибка: "Quota exceeded"
```
Решение:
1. Увеличьте интервал синхронизации в config.yaml
2. Проверьте квоты в Google Cloud Console
3. Используйте batch_size меньше в конфигурации
```

---

### Проблемы с Lotus Notes

#### Windows: "Не удается подключиться к Lotus Notes"
```
Проверьте:
1. Lotus Notes запущен
2. Вы вошли в Notes клиент
3. База mail.nsf доступна
4. Python запущен с правами пользователя Notes

Решение:
- Запустите Notes клиент вручную
- Войдите со своим ID и паролем
- Попробуйте снова: gcalsync test-lotus
```

#### Linux: "Wine не может запустить Notes"
```
Проверьте:
1. Wine установлен: wine --version
2. Notes установлен в Wine
3. Путь к Notes правильный в config.yaml

Решение:
- Переустановите Notes в Wine
- Проверьте переменные окружения WINEPREFIX
- Используйте Domino REST API (если доступно)
```

---

### Проблемы с синхронизацией

#### События дублируются
```
Причина: Нарушена связь в базе данных

Решение:
1. Остановите синхронизацию
2. Проверьте БД: gcalsync db check
3. Очистите дубликаты: gcalsync db cleanup
4. Запустите полную ресинхронизацию: gcalsync sync --full
```

#### Конфликты не разрешаются автоматически
```
Проверьте:
- Стратегия в config.yaml: conflict_resolution
- Логи: logs/gcalsync.log

Решение:
- Разрешите вручную: gcalsync conflicts resolve --id=X
- Или измените стратегию: gcalsync config set conflict_resolution latest_wins
```

---

### Проблемы с производительностью

#### Синхронизация работает медленно
```
Оптимизация:
1. Уменьшите диапазон дат в filters
2. Увеличьте batch_size
3. Отключите ненужные календари
4. Используйте инкрементальную синхронизацию

В config.yaml:
filters:
  sync_past_days: 3  # вместо 7
  sync_future_days: 90  # вместо 365
```

---

### Логи и диагностика

#### Включить детальное логирование

```bash
# Временно
gcalsync sync --verbose --log-level=DEBUG

# Постоянно в config.yaml
app:
  log_level: "DEBUG"
```

#### Просмотр логов

```bash
# Linux
tail -f logs/gcalsync.log

# Windows
Get-Content logs\gcalsync.log -Wait -Tail 50
```

#### Экспорт диагностической информации

```bash
gcalsync diagnostics > diagnostics.txt
```

---

## ❓ FAQ

### Общие вопросы

**Q: Работает ли синхронизация в реальном времени?**  
A: Нет, синхронизация происходит с заданным интервалом (по умолчанию 15 минут). Для более частой синхронизации измените `sync_interval_minutes` в конфигурации.

**Q: Могу ли я синхронизировать несколько календарей?**  
A: Да, укажите несколько calendar_id в конфигурации или запустите несколько экземпляров с разными конфигами.

**Q: Синхронизируются ли вложения к событиям?**  
A: В текущей версии - нет. Это планируется в будущих обновлениях.

**Q: Что происходит при удалении события?**  
A: При двусторонней синхронизации удаление в одной системе приведет к удалению в другой.

**Q: Безопасны ли мои данные?**  
A: Да, все credentials шифруются. Данные передаются только между вашим компьютером, Google и Lotus Notes сервером.

### Технические вопросы

**Q: Можно ли запустить на сервере без GUI?**  
A: Да, используйте режим daemon: `gcalsync daemon`

**Q: Поддерживается ли прокси?**  
A: Да, настройте через переменные окружения HTTP_PROXY и HTTPS_PROXY.

**Q: Можно ли откатить синхронизацию?**  
A: Да, если включено резервное копирование БД. Используйте: `gcalsync db restore --backup=TIMESTAMP`

---

## 📞 Поддержка

### Получение помощи

- 📖 [Документация проекта](PROJECT_PLAN.md)
- 🐛 [Сообщить о проблеме](https://github.com/yourusername/gcaltolotus/issues)
- 💬 [Обсуждения](https://github.com/yourusername/gcaltolotus/discussions)

### Вклад в проект

Приветствуются pull requests! См. [CONTRIBUTING.md](CONTRIBUTING.md) для деталей.

---

## 📄 Лицензия

Этот проект распространяется под лицензией MIT. См. файл [LICENSE](LICENSE) для деталей.

---

## 🙏 Благодарности

- Google Calendar API Team
- HCL Software (Lotus Notes/Domino)
- Сообщество Python
- Все контрибьюторы проекта

---

**Версия:** 1.0.0  
**Последнее обновление:** 17 ноября 2025  
**Автор:** @gudim

---

## 🗺️ Дорожная карта

### v1.1 (планируется)
- [ ] Web интерфейс для управления
- [ ] Поддержка Domino REST API
- [ ] Синхронизация вложений
- [ ] Мобильное приложение для мониторинга

### v1.2 (в разработке идей)
- [ ] Поддержка других календарей (Outlook, iCal)
- [ ] Умные уведомления
- [ ] Синхронизация задач (To-Do lists)
- [ ] Webhooks для real-time обновлений

---

## 📊 Статус проекта

🚧 **В активной разработке**

Текущая версия: `1.0.0-alpha`  
Стабильность: `Экспериментальная`  
Готовность к production: `Нет (тестирование)`

---

**Happy syncing! 📅✨**
