#!/bin/bash
# Полный интеграционный тест - Google Calendar + Domino REST API

set -e

echo "🧪 Полный интеграционный тест"
echo "======================================"
echo ""

# Активируем виртуальное окружение
if [ -d "venv" ]; then
    source venv/bin/activate
else
    echo "❌ Виртуальное окружение не найдено"
    exit 1
fi

# Тест 1: Google Calendar
echo "1️⃣  Тестирование Google Calendar..."
if [ -f "credentials/token.json" ]; then
    python -m src.main test-google
    echo "✅ Google Calendar работает"
else
    echo "⚠️  Google Calendar не настроен (запустите: python -m src.main init)"
fi
echo ""

# Тест 2: Domino REST API
echo "2️⃣  Тестирование Domino REST API..."
echo "Введите данные для подключения:"
read -p "Domino Server URL [https://domino.example.com]: " SERVER
SERVER=${SERVER:-https://domino.example.com}

read -p "Username [user@example.com]: " USERNAME
USERNAME=${USERNAME:-user@example.com}

read -sp "Password: " PASSWORD
echo ""

python -m src.main test-domino \
    --server "$SERVER" \
    --username "$USERNAME" \
    --password "$PASSWORD" \
    --no-verify-ssl

echo ""
echo "✅ Domino REST API работает"
echo ""

echo "🎉 Интеграционный тест завершен успешно!"
echo ""
echo "Следующие шаги:"
echo "  1. Запустите синхронизацию: python -m src.main sync"
echo "  2. Или посмотрите события: python -m src.main domino-events ..."

