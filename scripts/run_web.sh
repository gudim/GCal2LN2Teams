#!/bin/bash
# Запуск веб-интерфейса

cd "$(dirname "$0")/.."

echo "🚀 Запуск веб-интерфейса Google Calendar to Domino Sync..."
echo ""

# Активируем виртуальное окружение
if [ -d "venv" ]; then
    source venv/bin/activate
    echo "✅ Виртуальное окружение активировано"
else
    echo "❌ Виртуальное окружение не найдено. Создайте его: python -m venv venv"
    exit 1
fi

# Запускаем uvicorn
echo ""
echo "🌐 Веб-интерфейс запускается на http://localhost:8000"
echo "📖 Документация API: http://localhost:8000/docs"
echo ""
echo "Для остановки нажмите Ctrl+C"
echo ""

python -m uvicorn src.web.app:app --host 0.0.0.0 --port 8000 --reload
