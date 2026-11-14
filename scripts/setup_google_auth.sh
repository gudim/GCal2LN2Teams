#!/bin/bash
# Скрипт для настройки Google OAuth авторизации

echo "🔐 Настройка Google Calendar API авторизации"
echo ""

# Проверка наличия credentials файла
if [ ! -f "config/google_credentials.json" ]; then
    echo "❌ Файл config/google_credentials.json не найден!"
    echo ""
    echo "Пожалуйста, выполните следующие шаги:"
    echo "1. Перейдите в Google Cloud Console: https://console.cloud.google.com/"
    echo "2. Создайте новый проект или выберите существующий"
    echo "3. Включите Google Calendar API"
    echo "4. Создайте OAuth 2.0 credentials (Desktop app)"
    echo "5. Скачайте JSON файл и сохраните как config/google_credentials.json"
    echo ""
    exit 1
fi

echo "✅ Файл credentials найден"
echo ""

# Активация виртуального окружения
if [ -d "venv" ]; then
    source venv/bin/activate
    echo "✅ Виртуальное окружение активировано"
else
    echo "⚠️  Виртуальное окружение не найдено. Создаю..."
    python3 -m venv venv
    source venv/bin/activate
    pip install -r requirements.txt
fi

echo ""
echo "🚀 Запуск процесса авторизации..."
echo "Откроется браузер для входа в Google аккаунт"
echo ""

# Запуск скрипта авторизации
python3 << 'PYTHON_SCRIPT'
import os
import pickle
from google_auth_oauthlib.flow import InstalledAppFlow
from google.auth.transport.requests import Request

SCOPES = ['https://www.googleapis.com/auth/calendar']
CREDENTIALS_FILE = 'config/google_credentials.json'
TOKEN_FILE = 'data/google_token.pickle'

def main():
    creds = None
    
    # Проверяем существующий токен
    if os.path.exists(TOKEN_FILE):
        print("📄 Найден существующий токен")
        with open(TOKEN_FILE, 'rb') as token:
            creds = pickle.load(token)
    
    # Если токен невалидный или отсутствует
    if not creds or not creds.valid:
        if creds and creds.expired and creds.refresh_token:
            print("🔄 Обновление токена...")
            creds.refresh(Request())
        else:
            print("🌐 Запуск OAuth flow...")
            flow = InstalledAppFlow.from_client_secrets_file(
                CREDENTIALS_FILE, SCOPES)
            creds = flow.run_local_server(port=0)
        
        # Сохраняем токен
        with open(TOKEN_FILE, 'wb') as token:
            pickle.dump(creds, token)
        
        print("✅ Токен сохранен в", TOKEN_FILE)
    else:
        print("✅ Токен уже валидный")
    
    print("")
    print("🎉 Авторизация успешно завершена!")
    print("Теперь вы можете использовать gcalsync для синхронизации")

if __name__ == '__main__':
    main()
PYTHON_SCRIPT

echo ""
echo "✅ Готово!"
