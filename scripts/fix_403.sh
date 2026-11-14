#!/bin/bash
# Быстрое решение ошибки 403: access_denied

echo "============================================"
echo "  Решение ошибки 403: access_denied"
echo "============================================"
echo ""
echo "❌ Проблема: При авторизации появляется ошибка 403"
echo ""
echo "✅ Решение: Добавьте себя в тестировщики"
echo ""
echo "📋 Пошаговая инструкция:"
echo ""
echo "1. Откройте в браузере:"
echo "   https://console.cloud.google.com/apis/credentials/consent"
echo ""
echo "2. Выберите ваш проект (GCalSync)"
echo ""
echo "3. Прокрутите вниз до 'Test users'"
echo ""
echo "4. Нажмите '+ ADD USERS'"
echo ""
echo "5. Введите ваш email (которым входите в Google)"
echo ""
echo "6. Нажмите 'Save'"
echo ""
echo "7. Запустите команды ниже:"
echo ""

# Показываем команды для копирования
cat << 'EOF'
# Удалить старый токен (если есть)
rm -f data/google_token.pickle

# Запустить авторизацию заново
python -m src.main init

EOF

echo ""
echo "📖 Подробная инструкция: TROUBLESHOOTING_403.md"
echo ""
echo "💡 Важно: Используйте тот же email при входе!"
echo ""
