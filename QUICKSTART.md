# 🚀 Быстрый старт

Это краткое руководство поможет вам быстро настроить и запустить синхронизацию между Google Calendar и Lotus Notes.

## ⚡ За 5 минут

### Шаг 1: Установка

**Linux:**
```bash
cd ~/Development
git clone https://github.com/yourusername/gcaltolotus.git
cd gcaltolotus
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
pip install -e .
```

**Windows:**
```cmd
cd C:\Projects
git clone https://github.com/yourusername/gcaltolotus.git
cd gcaltolotus
python -m venv venv
venv\Scripts\activate
pip install -r requirements.txt
pip install -e .
```

### Шаг 2: Получить Google Credentials

1. Перейдите на https://console.cloud.google.com/
2. Создайте новый проект
3. Включите **Google Calendar API**
4. Создайте **OAuth 2.0 credentials** (Desktop app)
5. Скачайте JSON и сохраните как `config/google_credentials.json`

### Шаг 3: Настроить конфигурацию

```bash
cp config/config.yaml.example config/config.yaml
cp .env.example .env
```

Отредактируйте `config/config.yaml` при необходимости.

### Шаг 4: Авторизация Google

**Linux:**
```bash
bash scripts/setup_google_auth.sh
```

**Windows:**
```cmd
scripts\setup_google_auth.bat
```

Откроется браузер для входа в Google аккаунт.

### Шаг 5: Проверить Lotus Notes

```bash
gcalsync test-lotus
```

Убедитесь, что Lotus Notes запущен и доступен.

### Шаг 6: Первая синхронизация

```bash
# Пробный прогон (без изменений)
gcalsync sync --dry-run

# Реальная синхронизация
gcalsync sync
```

## 🎯 Основные команды

```bash
# Инициализация
gcalsync init

# Синхронизация
gcalsync sync                           # Полная синхронизация
gcalsync sync --dry-run                 # Тест без изменений
gcalsync sync --direction=google-to-lotus  # Только Google → Lotus

# Статус
gcalsync status                         # Общий статус
gcalsync status --verbose               # Детальная информация

# Конфликты
gcalsync conflicts list                 # Список конфликтов
gcalsync conflicts resolve --id=1 --resolution=google-wins

# Настройки
gcalsync config show                    # Текущие настройки
gcalsync config set sync_interval 30    # Изменить интервал
```

## 🔄 Автоматическая синхронизация

### Linux (systemd)

```bash
# Установить службу
sudo bash scripts/install_service.sh

# Управление
sudo systemctl start gcalsync
sudo systemctl enable gcalsync
sudo systemctl status gcalsync
```

### Windows (Task Scheduler)

1. Откройте Task Scheduler
2. Create Basic Task
3. Trigger: Повторять каждые 15 минут
4. Action: Start a program
   - Program: `C:\Projects\gcaltolotus\venv\Scripts\python.exe`
   - Arguments: `-m src.main sync`
   - Start in: `C:\Projects\gcaltolotus`

## 📚 Дополнительная информация

- **Полная документация:** [README.md](README.md)
- **План проекта:** [PROJECT_PLAN.md](PROJECT_PLAN.md)
- **Разработчикам:** [DEVELOPMENT.md](DEVELOPMENT.md)

## ❓ Возникли проблемы?

См. раздел [Устранение неполадок](README.md#-устранение-неполадок) в README.md

## 🆘 Получить помощь

- [Issues](https://github.com/yourusername/gcaltolotus/issues)
- [Discussions](https://github.com/yourusername/gcaltolotus/discussions)

---

**Happy syncing! 📅✨**
