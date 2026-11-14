@echo off
REM Скрипт для настройки Google OAuth авторизации (Windows)

echo 🔐 Настройка Google Calendar API авторизации
echo.

REM Проверка наличия credentials файла
if not exist "config\google_credentials.json" (
    echo ❌ Файл config\google_credentials.json не найден!
    echo.
    echo Пожалуйста, выполните следующие шаги:
    echo 1. Перейдите в Google Cloud Console: https://console.cloud.google.com/
    echo 2. Создайте новый проект или выберите существующий
    echo 3. Включите Google Calendar API
    echo 4. Создайте OAuth 2.0 credentials (Desktop app)
    echo 5. Скачайте JSON файл и сохраните как config\google_credentials.json
    echo.
    pause
    exit /b 1
)

echo ✅ Файл credentials найден
echo.

REM Активация виртуального окружения
if exist "venv\Scripts\activate.bat" (
    call venv\Scripts\activate.bat
    echo ✅ Виртуальное окружение активировано
) else (
    echo ⚠️  Виртуальное окружение не найдено. Создаю...
    python -m venv venv
    call venv\Scripts\activate.bat
    pip install -r requirements.txt
)

echo.
echo 🚀 Запуск процесса авторизации...
echo Откроется браузер для входа в Google аккаунт
echo.

REM Запуск Python скрипта авторизации
python -c "import os; import pickle; from google_auth_oauthlib.flow import InstalledAppFlow; from google.auth.transport.requests import Request; SCOPES = ['https://www.googleapis.com/auth/calendar']; CREDENTIALS_FILE = 'config/google_credentials.json'; TOKEN_FILE = 'data/google_token.pickle'; creds = None; creds = pickle.load(open(TOKEN_FILE, 'rb')) if os.path.exists(TOKEN_FILE) else None; flow = InstalledAppFlow.from_client_secrets_file(CREDENTIALS_FILE, SCOPES) if not creds or not creds.valid else None; creds = flow.run_local_server(port=0) if flow else creds; pickle.dump(creds, open(TOKEN_FILE, 'wb')); print('✅ Авторизация успешна!')"

echo.
echo ✅ Готово!
pause
