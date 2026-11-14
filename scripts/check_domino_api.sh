#!/bin/bash
# Проверка доступности HCL Domino REST API

echo "🔍 Проверка HCL Domino REST API"
echo "=================================="
echo ""

# Запрос адреса сервера
read -p "Введите адрес Domino сервера (например, domino.company.com): " SERVER

if [ -z "$SERVER" ]; then
    echo "❌ Адрес сервера не указан"
    exit 1
fi

echo ""
echo "Сервер: $SERVER"
echo ""

# Проверка 1: Ping сервера
echo "1️⃣  Проверка доступности сервера..."
if ping -c 2 "$SERVER" > /dev/null 2>&1; then
    echo "✅ Сервер доступен"
else
    echo "⚠️  Сервер не отвечает на ping (может быть заблокирован ICMP)"
fi
echo ""

# Проверка 2: HTTP/HTTPS доступность
echo "2️⃣  Проверка HTTP доступа..."

# HTTPS
if curl -k -s -o /dev/null -w "%{http_code}" "https://$SERVER" | grep -q "200\|401\|403"; then
    echo "✅ HTTPS доступен: https://$SERVER"
    PROTO="https"
elif curl -s -o /dev/null -w "%{http_code}" "http://$SERVER" | grep -q "200\|401\|403"; then
    echo "✅ HTTP доступен: http://$SERVER"
    PROTO="http"
else
    echo "❌ HTTP/HTTPS недоступен"
    echo "Проверьте:"
    echo "  - Правильность адреса"
    echo "  - Firewall правила"
    echo "  - VPN подключение (если требуется)"
    exit 1
fi
echo ""

# Проверка 3: Domino REST API endpoints
echo "3️⃣  Проверка Domino REST API..."

# Распространенные endpoints
ENDPOINTS=(
    "/api/data"
    "/api/v1"
    "/api/calendar"
    "/names.nsf"
    "/mail.nsf"
)

for endpoint in "${ENDPOINTS[@]}"; do
    URL="${PROTO}://${SERVER}${endpoint}"
    HTTP_CODE=$(curl -k -s -o /dev/null -w "%{http_code}" "$URL")
    
    case $HTTP_CODE in
        200|201|204)
            echo "✅ $endpoint - Доступен (HTTP $HTTP_CODE)"
            ;;
        401)
            echo "🔐 $endpoint - Требует авторизацию (HTTP $HTTP_CODE)"
            ;;
        403)
            echo "🔒 $endpoint - Доступ запрещен (HTTP $HTTP_CODE)"
            ;;
        404)
            echo "❌ $endpoint - Не найден (HTTP $HTTP_CODE)"
            ;;
        *)
            echo "⚠️  $endpoint - HTTP $HTTP_CODE"
            ;;
    esac
done
echo ""

# Проверка 4: Domino версия (через заголовки)
echo "4️⃣  Определение версии Domino..."
HEADERS=$(curl -k -s -I "${PROTO}://${SERVER}" 2>/dev/null)

if echo "$HEADERS" | grep -i "server:" | grep -i "domino"; then
    echo "$HEADERS" | grep -i "server:"
elif echo "$HEADERS" | grep -i "lotus"; then
    echo "$HEADERS" | grep -i "lotus"
else
    echo "⚠️  Версия Domino не определена"
fi
echo ""

# Проверка 5: Тест с credentials (если есть)
echo "5️⃣  Тест авторизации (опционально)"
read -p "Есть ли у вас username для тестирования? (y/n): " HAS_CREDS

if [ "$HAS_CREDS" = "y" ]; then
    read -p "Username: " USERNAME
    read -sp "Password: " PASSWORD
    echo ""
    
    # Попробуем доступ к mail.nsf
    HTTP_CODE=$(curl -k -s -o /dev/null -w "%{http_code}" \
        -u "$USERNAME:$PASSWORD" \
        "${PROTO}://${SERVER}/mail.nsf")
    
    case $HTTP_CODE in
        200|201|204)
            echo "✅ Авторизация успешна!"
            ;;
        401)
            echo "❌ Неверные credentials"
            ;;
        403)
            echo "⚠️  Доступ запрещен (credentials верные, но нет прав)"
            ;;
        *)
            echo "⚠️  HTTP $HTTP_CODE"
            ;;
    esac
fi
echo ""

# Итоговая информация
echo "📋 Резюме"
echo "=================================="
echo "Сервер: $SERVER"
echo "Протокол: $PROTO"
echo ""
echo "Следующие шаги:"
echo "1. Если REST API доступен (HTTP 200/401):"
echo "   → Можно использовать REST вместо COM"
echo "   → Работает на Linux нативно"
echo ""
echo "2. Если требуется авторизация (HTTP 401):"
echo "   → Запросите credentials у администратора"
echo "   → Проверьте документацию Domino REST API"
echo ""
echo "3. Если API недоступен (HTTP 404):"
echo "   → Свяжитесь с администратором Domino"
echo "   → Возможно нужно включить REST API"
echo "   → Или используйте Windows VM для COM API"
echo ""
echo "Документация:"
echo "https://help.hcltechsw.com/domino/12.0.0/admin/rest_api.html"
