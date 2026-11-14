#!/usr/bin/env python3
"""
Простой скрипт для тестирования Google OAuth авторизации
"""

import sys
from pathlib import Path

# Добавляем путь к src
sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

from adapters.google_calendar import GoogleCalendarAdapter
from utils.logger import setup_logging

def main():
    # Настраиваем логирование
    setup_logging(log_level="INFO")
    
    print("=" * 60)
    print("Google Calendar OAuth 2.0 Авторизация - Тест")
    print("=" * 60)
    print()
    
    # Пути к файлам
    credentials_file = "config/google_credentials.json"
    token_file = "data/google_token.pickle"
    
    # Проверяем наличие credentials
    if not Path(credentials_file).exists():
        print("❌ ОШИБКА: Файл credentials не найден!")
        print(f"   Ожидается: {credentials_file}")
        print()
        print("📝 Получите credentials в Google Cloud Console:")
        print("   1. https://console.cloud.google.com/")
        print("   2. APIs & Services → Credentials")
        print("   3. Create Credentials → OAuth client ID → Desktop app")
        print("   4. Скачайте JSON и сохраните как config/google_credentials.json")
        return 1
    
    print(f"✅ Файл credentials найден: {credentials_file}")
    print()
    
    # Создаем адаптер
    adapter = GoogleCalendarAdapter(
        credentials_file=credentials_file,
        token_file=token_file,
        calendar_id='primary'
    )
    
    print("🔐 Начало процесса авторизации...")
    print("📱 Откроется браузер для входа в Google аккаунт")
    print("   (если браузер не открылся, скопируйте URL из консоли)")
    print()
    
    try:
        # Запускаем авторизацию
        adapter.authenticate()
        
        print()
        print("=" * 60)
        print("✅ АВТОРИЗАЦИЯ УСПЕШНА!")
        print("=" * 60)
        print()
        
        # Тестируем подключение
        print("🧪 Тестирование подключения к календарю...")
        info = adapter.test_connection()
        
        print()
        print("📊 Информация о календаре:")
        print(f"   📅 Название: {info['calendar_name']}")
        print(f"   🆔 ID: {info['calendar_id']}")
        print(f"   🕐 Временная зона: {info['timezone']}")
        print(f"   📈 Предстоящих событий: {info['upcoming_events_count']}")
        print()
        
        # Показываем список календарей
        print("📋 Доступные календари:")
        calendars = adapter.list_calendars()
        for i, cal in enumerate(calendars, 1):
            primary = " [PRIMARY]" if cal.get('primary') else ""
            print(f"   {i}. {cal['summary']}{primary}")
        print()
        
        print("=" * 60)
        print("🎉 ВСЕ ТЕСТЫ ПРОШЛИ УСПЕШНО!")
        print("=" * 60)
        print()
        print(f"💡 Токен сохранен в: {token_file}")
        print("   При следующем запуске браузер не понадобится")
        print()
        
        return 0
        
    except KeyboardInterrupt:
        print()
        print("⚠️  Прервано пользователем")
        return 1
    except Exception as e:
        print()
        print("=" * 60)
        print("❌ ОШИБКА!")
        print("=" * 60)
        print(f"   {e}")
        print()
        import traceback
        traceback.print_exc()
        return 1


if __name__ == '__main__':
    sys.exit(main())
