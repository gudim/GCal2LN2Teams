# Настройка интеграции с HCL Notes

## Системные требования

### Linux (Wine)
- Wine 6.0 или новее
- HCL Notes установлен через Wine
- Python 3.8+

## Установка зависимостей

```bash
# Активируйте виртуальное окружение
source venv/bin/activate

# Установите pywin32 для работы с COM через Wine
pip install pywin32
```

## Ваша конфигурация

Путь к HCL Notes:
```
~/.wine/drive_c/HCL/Notes/nlnotes.exe
```

Запуск клиента вручную:
```bash
wine ~/.wine/drive_c/HCL/Notes/nlnotes.exe
```

## Использование

### 1. Проверка информации

Показывает информацию о Wine и HCL Notes:

```bash
python -m src.main lotus-info --notes-path=~/.wine/drive_c/HCL/Notes
```

### 2. Тестирование подключения

Полная проверка подключения к HCL Notes:

```bash
python -m src.main test-lotus --notes-path=~/.wine/drive_c/HCL/Notes
```

С паролем к ID файлу:

```bash
python -m src.main test-lotus \
    --notes-path=~/.wine/drive_c/HCL/Notes \
    --password=your_password
```

### 3. Автоматический скрипт

Запустите готовый скрипт для тестирования:

```bash
./scripts/test_hcl_notes.sh
```

## Что проверяется

Команда `test-lotus` выполняет следующие проверки:

1. ✅ **Wine доступен** - проверка установки Wine
2. ✅ **HCL Notes установлен** - поиск nlnotes.exe или notes.exe
3. ✅ **Клиент запущен** - проверка процесса, автозапуск если нужно
4. ✅ **COM подключение** - подключение к Lotus.NotesSession
5. ✅ **База данных** - открытие mail.nsf
6. ✅ **Календарь** - доступ к Calendar view

## Поддерживаемые версии

Адаптер автоматически определяет тип установки:

- **HCL Notes** (новые версии) - использует `nlnotes.exe`
- **IBM Lotus Notes** (старые версии) - использует `notes.exe`

## Расположения установки

Адаптер автоматически ищет Notes в следующих директориях:

### HCL Notes (приоритет)
- `~/.wine/drive_c/HCL/Notes`
- `~/.wine/drive_c/Program Files/HCL/Notes`
- `~/.wine/drive_c/Program Files (x86)/HCL/Notes`

### IBM Lotus Notes
- `~/.wine/drive_c/Program Files/IBM/Lotus/Notes`
- `~/.wine/drive_c/Program Files (x86)/IBM/Lotus/Notes`
- `~/.wine/drive_c/Lotus/Notes`

## Устранение проблем

### Wine не найден

```bash
# Установите Wine
sudo apt update
sudo apt install wine64 wine32
```

### HCL Notes не обнаружен

Укажите путь вручную:

```bash
python -m src.main test-lotus --notes-path=/путь/к/notes
```

### Клиент не запускается

Запустите вручную перед тестированием:

```bash
wine ~/.wine/drive_c/HCL/Notes/nlnotes.exe
```

### Ошибка COM подключения

1. Убедитесь, что клиент запущен
2. Проверьте, что вы авторизованы в Notes
3. Проверьте доступ к базе mail.nsf

### Требуется пароль

Если Notes запрашивает пароль к ID файлу:

```bash
python -m src.main test-lotus \
    --notes-path=~/.wine/drive_c/HCL/Notes \
    --password=ваш_пароль
```

## Конфигурация

Для постоянной конфигурации создайте файл `config.yaml`:

```yaml
lotus_notes:
  wine_path: /usr/bin/wine
  notes_path: ~/.wine/drive_c/HCL/Notes
  password: null  # или укажите пароль
  server: ""  # для локальной базы
  database: mail.nsf
```

## Следующие шаги

После успешного подключения вы сможете:

1. Получать список событий календаря
2. Создавать новые события
3. Обновлять существующие события
4. Удалять события
5. Синхронизировать с Google Calendar

## Примеры вывода

### Успешное подключение

```
🔌 Проверка подключения к Lotus Notes...

🍷 Проверка Wine...
✅ Wine доступен

📁 Проверка установки Lotus Notes...
✅ Lotus Notes найден: ~/.wine/drive_c/HCL/Notes

🔍 Проверка запущенного клиента...
✅ Notes клиент уже запущен

🔗 Подключение к Lotus Notes через COM API...
✅ Подключение успешно!

🧪 Тестирование подключения...

📊 Информация о Lotus Notes:
   👤 Пользователь: John Doe/Company
   📦 Версия Notes: Release 12.0.1
   🖥️  Платформа: Linux
   📂 База данных: Local Mail
   🌐 Сервер: 
   📅 Записей в календаре: 45
   🍷 Режим: Wine

🎉 Тест завершен успешно!
```

## Дополнительные ресурсы

- [HCL Notes Documentation](https://help.hcltechsw.com/notes/)
- [Wine HQ](https://www.winehq.org/)
- [Lotus Notes COM API Reference](https://help.hcltechsw.com/dom_designer/)
