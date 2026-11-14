#!/bin/bash
# Быстрая проверка Google OAuth авторизации

echo "🚀 Google Calendar OAuth Test"
echo "=============================="
echo ""

# Проверка виртуального окружения
if [ ! -d "venv" ]; then
    echo "📦 Создание виртуального окружения..."
    python3 -m venv venv
fi

echo "🔄 Активация виртуального окружения..."
source venv/bin/activate

# Проверка зависимостей
echo "📚 Проверка зависимостей..."
pip install -q google-auth google-auth-oauthlib google-auth-httplib2 google-api-python-client click

echo ""
echo "🔐 Проверка наличия credentials..."

if [ ! -f "config/google_credentials.json" ]; then
    echo ""
    echo "❌ ОШИБКА: Файл config/google_credentials.json не найден!"
    echo ""
    echo "📝 Инструкция:"
    echo "1. Откройте: https://console.cloud.google.com/"
    echo "2. Создайте OAuth 2.0 credentials (Desktop app)"
    echo "3. Скачайте JSON и сохраните как config/google_credentials.json"
    echo ""
    echo "Или создайте директорию config и поместите туда файл:"
    echo "  mkdir -p config"
    echo "  cp /путь/к/скачанному/файлу.json config/google_credentials.json"
    echo ""
    exit 1
fi

echo "✅ Credentials найден"
echo ""
echo "🧪 Запуск теста авторизации..."
echo "📱 Откроется браузер для входа в Google..."
echo ""

# Запуск теста
python scripts/test_google_auth.py

exit_code=$?

if [ $exit_code -eq 0 ]; then
    echo ""
    echo "✅ Тест завершен успешно!"
    echo ""
    echo "💡 Теперь вы можете использовать:"
    echo "   python -m src.main init          # Авторизация"
    echo "   python -m src.main test-google   # Проверка подключения"
    echo "   python -m src.main list-calendars # Список календарей"
else
    echo ""
    echo "❌ Тест завершился с ошибкой"
fi

exit $exit_code
