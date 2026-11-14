"""
Главная точка входа в приложение
"""

import click
from pathlib import Path
import sys
import os

# Добавляем путь к модулям
sys.path.insert(0, str(Path(__file__).parent.parent))

# Импортируем относительно src
try:
    from src.adapters.google_calendar import GoogleCalendarAdapter
    from src.adapters.domino_rest import DominoRestAdapter
    from src.utils.logger import get_logger, setup_logging
except ImportError:
    # Fallback для запуска из директории src
    from .adapters.google_calendar import GoogleCalendarAdapter
    from .adapters.domino_rest import DominoRestAdapter
    from .utils.logger import get_logger, setup_logging

logger = get_logger(__name__)


@click.group()
@click.version_option(version="1.0.0")
def cli():
    """Google Calendar to Lotus Notes Sync - CLI"""
    pass


@cli.command()
@click.option('--google-credentials', type=click.Path(exists=True), 
              help='Путь к файлу Google credentials JSON')
@click.option('--calendar-id', default='primary', 
              help='ID календаря Google (по умолчанию: primary)')
@click.option('--force', is_flag=True,
              help='Принудительная повторная авторизация')
def init(google_credentials, calendar_id, force):
    """Инициализация и первоначальная настройка Google Calendar"""
    click.echo("🚀 Инициализация Google Calendar to Lotus Notes Sync...")
    click.echo("")
    
    # Определяем пути
    if google_credentials:
        creds_file = google_credentials
    else:
        creds_file = "config/google_credentials.json"
    
    token_file = "data/google_token.pickle"
    
    # Проверяем наличие credentials файла
    if not os.path.exists(creds_file):
        click.echo("❌ Файл Google credentials не найден!")
        click.echo("")
        click.echo("� Инструкция по получению credentials:")
        click.echo("1. Перейдите в Google Cloud Console:")
        click.echo("   https://console.cloud.google.com/")
        click.echo("2. Создайте новый проект или выберите существующий")
        click.echo("3. Включите Google Calendar API:")
        click.echo("   APIs & Services → Library → Google Calendar API → Enable")
        click.echo("4. Создайте OAuth 2.0 credentials:")
        click.echo("   APIs & Services → Credentials → Create Credentials → OAuth client ID")
        click.echo("   Application type: Desktop app")
        click.echo(f"5. Скачайте JSON файл и сохраните как: {creds_file}")
        click.echo("")
        sys.exit(1)
    
    click.echo(f"✅ Найден файл credentials: {creds_file}")
    click.echo("")
    
    try:
        # Создаем адаптер
        adapter = GoogleCalendarAdapter(
            credentials_file=creds_file,
            token_file=token_file,
            calendar_id=calendar_id
        )
        
        # Запускаем авторизацию
        click.echo("🔐 Начало процесса авторизации...")
        click.echo("📱 Откроется браузер для входа в Google аккаунт")
        click.echo("")
        
        if force:
            click.echo("⚠️  Принудительная повторная авторизация")
        
        adapter.authenticate(force_reauth=force)
        
        click.echo("")
        click.echo("✅ Авторизация успешно завершена!")
        click.echo(f"🔑 Токен сохранен в: {token_file}")
        click.echo("")
        
        # Тестируем подключение
        click.echo("🧪 Тестирование подключения к Google Calendar...")
        info = adapter.test_connection()
        
        click.echo("")
        click.echo("📊 Информация о календаре:")
        click.echo(f"   Название: {info['calendar_name']}")
        click.echo(f"   ID: {info['calendar_id']}")
        click.echo(f"   Временная зона: {info['timezone']}")
        click.echo(f"   Предстоящих событий: {info['upcoming_events_count']}")
        click.echo("")
        click.echo("🎉 Инициализация завершена успешно!")
        
    except FileNotFoundError as e:
        click.echo(f"❌ Ошибка: {e}", err=True)
        sys.exit(1)
    except Exception as e:
        click.echo(f"❌ Ошибка при авторизации: {e}", err=True)
        logger.exception("Ошибка инициализации")
        sys.exit(1)


@cli.command()
@click.option('--verbose', is_flag=True, help='Детальная информация')
def status(verbose):
    """Показать статус синхронизации"""
    click.echo("📊 Статус синхронизации:")
    
    # TODO: Реализовать показ статуса
    click.echo("Последняя синхронизация: Никогда")
    click.echo("Статус: Не настроено")


@cli.group()
def conflicts():
    """Управление конфликтами"""
    pass


@conflicts.command('list')
def conflicts_list():
    """Показать список конфликтов"""
    click.echo("📋 Список конфликтов:")
    
    # TODO: Реализовать показ конфликтов
    click.echo("Конфликтов не найдено")


@conflicts.command('resolve')
@click.option('--id', type=int, required=True, help='ID конфликта')
@click.option('--resolution', type=click.Choice(['google-wins', 'lotus-wins', 'manual']),
              required=True, help='Стратегия разрешения')
def conflicts_resolve(id, resolution):
    """Разрешить конфликт"""
    click.echo(f"✅ Разрешение конфликта #{id} стратегией: {resolution}")
    
    # TODO: Реализовать разрешение конфликта


@cli.group()
def config():
    """Управление конфигурацией"""
    pass


@config.command('show')
def config_show():
    """Показать текущую конфигурацию"""
    click.echo("⚙️  Текущая конфигурация:")
    
    # TODO: Реализовать показ конфигурации


@config.command('set')
@click.argument('key')
@click.argument('value')
def config_set(key, value):
    """Установить параметр конфигурации"""
    click.echo(f"✅ Установлено: {key} = {value}")
    
    # TODO: Реализовать установку параметра


@cli.command('daemon')
def daemon():
    """Запустить в режиме демона (фоновый процесс)"""
    click.echo("🔄 Запуск в режиме демона...")
    
    # TODO: Реализовать режим демона
    click.echo("Демон запущен. Нажмите Ctrl+C для остановки.")


@cli.command('test-google')
@click.option('--calendar-id', default='primary', help='ID календаря')
def test_google(calendar_id):
    """Проверить подключение к Google Calendar"""
    click.echo("🔌 Проверка подключения к Google Calendar...")
    click.echo("")
    
    creds_file = "config/google_credentials.json"
    token_file = "data/google_token.pickle"
    
    if not os.path.exists(token_file):
        click.echo("❌ Токен не найден. Сначала выполните: gcalsync init")
        sys.exit(1)
    
    try:
        adapter = GoogleCalendarAdapter(
            credentials_file=creds_file,
            token_file=token_file,
            calendar_id=calendar_id
        )
        
        adapter.authenticate()
        info = adapter.test_connection()
        
        click.echo("✅ Подключение успешно!")
        click.echo("")
        click.echo(f"📅 Календарь: {info['calendar_name']}")
        click.echo(f"🕐 Временная зона: {info['timezone']}")
        click.echo(f"📊 Предстоящих событий: {info['upcoming_events_count']}")
        
    except Exception as e:
        click.echo(f"❌ Ошибка: {e}", err=True)
        sys.exit(1)


@cli.command('list-calendars')
def list_calendars():
    """Показать список всех доступных календарей Google"""
    click.echo("📋 Получение списка календарей Google...")
    click.echo("")
    
    creds_file = "config/google_credentials.json"
    token_file = "data/google_token.pickle"
    
    if not os.path.exists(token_file):
        click.echo("❌ Токен не найден. Сначала выполните: gcalsync init")
        sys.exit(1)
    
    try:
        adapter = GoogleCalendarAdapter(
            credentials_file=creds_file,
            token_file=token_file
        )
        
        adapter.authenticate()
        calendars = adapter.list_calendars()
        
        if not calendars:
            click.echo("📭 Календари не найдены")
            return
        
        click.echo(f"Найдено календарей: {len(calendars)}")
        click.echo("")
        
        for i, cal in enumerate(calendars, 1):
            primary = " [PRIMARY]" if cal.get('primary') else ""
            selected = " ✓" if cal.get('selected') else ""
            
            click.echo(f"{i}. {cal['summary']}{primary}{selected}")
            click.echo(f"   ID: {cal['id']}")
            click.echo(f"   Доступ: {cal['access_role']}")
            click.echo(f"   Временная зона: {cal.get('timezone', 'N/A')}")
            if cal.get('description'):
                click.echo(f"   Описание: {cal['description']}")
            click.echo("")
        
    except Exception as e:
        click.echo(f"❌ Ошибка: {e}", err=True)
        sys.exit(1)


@cli.command('google-events')
@click.option('--calendar-id', default='primary', help='ID календаря')
@click.option('--days-back', default=7, help='Сколько дней назад смотреть')
@click.option('--days-forward', default=30, help='Сколько дней вперед смотреть')
@click.option('--limit', default=50, help='Максимум событий')
def google_events(calendar_id, days_back, days_forward, limit):
    """Показать события из Google Calendar"""
    creds_file = "config/google_credentials.json"
    token_file = "data/google_token.pickle"
    
    if not os.path.exists(token_file):
        click.echo("❌ Токен не найден. Выполните: python -m src.main init")
        sys.exit(1)
    
    try:
        from datetime import datetime, timedelta
        
        adapter = GoogleCalendarAdapter(
            credentials_file=creds_file,
            token_file=token_file,
            calendar_id=calendar_id
        )
        adapter.authenticate()
        
        # Расширенный диапазон дат
        start_date = datetime.now() - timedelta(days=days_back)
        end_date = datetime.now() + timedelta(days=days_forward)
        
        click.echo(f"📅 Поиск событий в календаре: {calendar_id}")
        click.echo(f"   Период: {start_date.strftime('%Y-%m-%d')} - {end_date.strftime('%Y-%m-%d')}")
        click.echo("")
        
        events = adapter.list_events(
            time_min=start_date.isoformat() + 'Z',
            time_max=end_date.isoformat() + 'Z',
            max_results=limit
        )
        
        if not events:
            click.echo("📭 Событий не найдено")
            click.echo("")
            click.echo("💡 Попробуйте:")
            click.echo("   1. Проверить календарь в веб-интерфейсе Google Calendar")
            click.echo("   2. Указать другой календарь: --calendar-id='...'")
            click.echo(f"   3. Расширить диапазон: --days-back=30 --days-forward=90")
        else:
            click.echo(f"✅ Найдено событий: {len(events)}")
            click.echo("")
            
            for i, event in enumerate(events, 1):
                summary = event.get('summary', 'Без названия')
                start = event.get('start', {})
                end = event.get('end', {})
                location = event.get('location', '')
                description = event.get('description', '')
                
                # Извлекаем дату/время
                start_time = start.get('dateTime', start.get('date', 'N/A'))
                end_time = end.get('dateTime', end.get('date', 'N/A'))
                
                if 'T' in start_time:
                    dt = datetime.fromisoformat(start_time.replace('Z', '+00:00'))
                    start_str = dt.strftime('%d.%m.%Y %H:%M')
                else:
                    start_str = start_time
                
                if 'T' in end_time:
                    dt = datetime.fromisoformat(end_time.replace('Z', '+00:00'))
                    end_str = dt.strftime('%d.%m.%Y %H:%M')
                else:
                    end_str = end_time
                
                click.echo(f"{i}. {summary}")
                click.echo(f"   📅 Начало: {start_str}")
                click.echo(f"   ⏰ Конец: {end_str}")
                if location:
                    click.echo(f"   📍 Место: {location}")
                if description:
                    desc_short = description[:100] + '...' if len(description) > 100 else description
                    click.echo(f"   📝 {desc_short}")
                click.echo("")
        
    except Exception as e:
        click.echo(f"❌ Ошибка: {e}", err=True)
        logger.exception("Ошибка получения событий")
        sys.exit(1)


@cli.command('test-google')
def revoke_google():
    """Отозвать доступ к Google Calendar и удалить токен"""
    click.echo("🔐 Отзыв доступа к Google Calendar...")
    
    token_file = "data/google_token.pickle"
    
    if not os.path.exists(token_file):
        click.echo("⚠️  Токен не найден, нечего отзывать")
        return
    
    if not click.confirm('Вы уверены, что хотите отозвать доступ?'):
        click.echo("Отменено")
        return
    
    try:
        creds_file = "config/google_credentials.json"
        adapter = GoogleCalendarAdapter(
            credentials_file=creds_file,
            token_file=token_file
        )
        
        adapter.authenticate()
        adapter.revoke_access()
        
        click.echo("✅ Доступ успешно отозван")
        click.echo("💡 Для повторной авторизации выполните: gcalsync init --force")
        
    except Exception as e:
        click.echo(f"❌ Ошибка: {e}", err=True)
        sys.exit(1)


@cli.command('test-domino')
@click.option('--server', required=True, help='URL Domino сервера')
@click.option('--username', required=True, help='Имя пользователя')
@click.option('--password', prompt=True, hide_input=True, help='Пароль')
@click.option('--no-verify-ssl', is_flag=True, help='Не проверять SSL сертификат')
def test_domino(server, username, password, no_verify_ssl):
    """Проверить подключение к Domino REST API"""
    click.echo("🔌 Проверка подключения к Domino REST API...")
    click.echo("")
    
    try:
        # Создаем адаптер
        adapter = DominoRestAdapter(
            server_url=server,
            username=username,
            password=password,
            verify_ssl=not no_verify_ssl
        )
        
        # Подключаемся
        click.echo("🔗 Подключение к Domino...")
        adapter.connect()
        click.echo("✅ Подключение успешно!")
        click.echo("")
        
        # Тестируем
        click.echo("🧪 Тестирование подключения...")
        info = adapter.test_connection()
        
        click.echo("")
        click.echo("📊 Информация о Domino:")
        click.echo(f"   👤 Пользователь: {info['username']}")
        click.echo(f"   📧 Email: {info['email']}")
        click.echo(f"   🌐 DN: {info['distinguished_name']}")
        click.echo(f"   🖥️  Сервер: {info['server']}")
        click.echo(f"   🔗 Events URL: {info['events_url']}")
        click.echo(f"   📅 Записей в календаре: {info['calendar_entries']}")
        click.echo(f"   🔌 API: {info['api_type']}")
        click.echo("")
        
        # Отключаемся
        adapter.disconnect()
        
        click.echo("🎉 Тест завершен успешно!")
        
    except Exception as e:
        click.echo(f"❌ Ошибка: {e}", err=True)
        logger.exception("Ошибка при тестировании Domino")
        sys.exit(1)


@cli.command('domino-events')
@click.option('--server', required=True, help='URL Domino сервера')
@click.option('--username', required=True, help='Имя пользователя')
@click.option('--password', prompt=True, hide_input=True, help='Пароль')
@click.option('--no-verify-ssl', is_flag=True, help='Не проверять SSL сертификат')
@click.option('--limit', default=10, help='Количество событий')
def domino_events(server, username, password, no_verify_ssl, limit):
    """Показать события из Domino календаря"""
    try:
        adapter = DominoRestAdapter(
            server_url=server,
            username=username,
            password=password,
            verify_ssl=not no_verify_ssl
        )
        
        adapter.connect()
        
        click.echo(f"📅 Получение {limit} событий...")
        events = adapter.list_events(limit=limit)
        
        if not events:
            click.echo("Нет событий в календаре")
        else:
            click.echo(f"\n📋 Найдено событий: {len(events)}\n")
            
            for i, event in enumerate(events, 1):
                summary = event.get('summary', 'Без темы')
                start = event.get('start', {})
                end = event.get('end', {})
                location = event.get('location', '')
                
                click.echo(f"{i}. {summary}")
                click.echo(f"   📅 Начало: {start.get('date')} {start.get('time')}")
                click.echo(f"   ⏰ Конец: {end.get('date')} {end.get('time')}")
                if location:
                    click.echo(f"   📍 Место: {location}")
                click.echo("")
        
        adapter.disconnect()
        
    except Exception as e:
        click.echo(f"❌ Ошибка: {e}", err=True)
        logger.exception("Ошибка при получении событий")
        sys.exit(1)


if __name__ == '__main__':
    cli()
