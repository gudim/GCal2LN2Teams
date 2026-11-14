#!/bin/bash
# Установка службы systemd для Linux

SERVICE_NAME="gcalsync"
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
WORKING_DIR="$(pwd)"
USER="$(whoami)"
VENV_PYTHON="${WORKING_DIR}/venv/bin/python"

echo "🔧 Установка службы gcalsync для systemd"
echo ""

# Проверка прав root
if [ "$EUID" -ne 0 ]; then 
    echo "❌ Пожалуйста, запустите скрипт с правами root (sudo)"
    exit 1
fi

# Проверка наличия виртуального окружения
if [ ! -f "$VENV_PYTHON" ]; then
    echo "❌ Виртуальное окружение не найдено в $WORKING_DIR/venv"
    echo "Пожалуйста, сначала создайте виртуальное окружение:"
    echo "  python3 -m venv venv"
    echo "  source venv/bin/activate"
    echo "  pip install -r requirements.txt"
    exit 1
fi

echo "📝 Создание файла службы: $SERVICE_FILE"

# Создание файла службы
cat > "$SERVICE_FILE" << EOF
[Unit]
Description=Google Calendar to Lotus Notes Sync Service
After=network.target

[Service]
Type=simple
User=$USER
WorkingDirectory=$WORKING_DIR
Environment="PATH=$WORKING_DIR/venv/bin"
ExecStart=$VENV_PYTHON -m src.main daemon
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

echo "✅ Файл службы создан"
echo ""

# Перезагрузка конфигурации systemd
echo "🔄 Перезагрузка конфигурации systemd..."
systemctl daemon-reload

echo "✅ Служба установлена!"
echo ""
echo "Доступные команды:"
echo "  sudo systemctl enable $SERVICE_NAME   # Включить автозапуск"
echo "  sudo systemctl start $SERVICE_NAME    # Запустить службу"
echo "  sudo systemctl stop $SERVICE_NAME     # Остановить службу"
echo "  sudo systemctl status $SERVICE_NAME   # Проверить статус"
echo "  sudo journalctl -u $SERVICE_NAME -f   # Просмотр логов"
echo ""

# Предложение включить автозапуск
read -p "Включить автозапуск службы? (y/n) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    systemctl enable $SERVICE_NAME
    echo "✅ Автозапуск включен"
fi

read -p "Запустить службу сейчас? (y/n) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    systemctl start $SERVICE_NAME
    echo "✅ Служба запущена"
    echo ""
    systemctl status $SERVICE_NAME
fi
