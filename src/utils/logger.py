"""
Модуль логирования для приложения
"""

import logging
import sys
from pathlib import Path
from logging.handlers import RotatingFileHandler
from typing import Optional

try:
    import colorlog
    HAS_COLORLOG = True
except ImportError:
    HAS_COLORLOG = False


def get_logger(name: str, log_level: str = "INFO") -> logging.Logger:
    """
    Получить настроенный логгер
    
    Args:
        name: Имя логгера (обычно __name__)
        log_level: Уровень логирования (DEBUG, INFO, WARNING, ERROR, CRITICAL)
    
    Returns:
        logging.Logger: Настроенный логгер
    """
    logger = logging.getLogger(name)
    
    # Если логгер уже настроен, возвращаем его
    if logger.handlers:
        return logger
    
    # Устанавливаем уровень
    level = getattr(logging, log_level.upper(), logging.INFO)
    logger.setLevel(level)
    
    # Формат логов
    if HAS_COLORLOG:
        # Цветной формат для консоли
        formatter = colorlog.ColoredFormatter(
            '%(log_color)s%(asctime)s - %(name)s - %(levelname)s - %(message)s',
            datefmt='%Y-%m-%d %H:%M:%S',
            log_colors={
                'DEBUG': 'cyan',
                'INFO': 'green',
                'WARNING': 'yellow',
                'ERROR': 'red',
                'CRITICAL': 'red,bg_white',
            }
        )
    else:
        # Обычный формат
        formatter = logging.Formatter(
            '%(asctime)s - %(name)s - %(levelname)s - %(message)s',
            datefmt='%Y-%m-%d %H:%M:%S'
        )
    
    # Консольный обработчик
    console_handler = logging.StreamHandler(sys.stdout)
    console_handler.setFormatter(formatter)
    logger.addHandler(console_handler)
    
    # Файловый обработчик (опционально)
    try:
        log_dir = Path("logs")
        log_dir.mkdir(exist_ok=True)
        
        file_handler = RotatingFileHandler(
            log_dir / "gcalsync.log",
            maxBytes=10 * 1024 * 1024,  # 10 MB
            backupCount=5
        )
        
        file_formatter = logging.Formatter(
            '%(asctime)s - %(name)s - %(levelname)s - %(message)s',
            datefmt='%Y-%m-%d %H:%M:%S'
        )
        file_handler.setFormatter(file_formatter)
        logger.addHandler(file_handler)
    except Exception:
        # Если не удалось создать файловый логгер, продолжаем без него
        pass
    
    return logger


def setup_logging(log_level: str = "INFO", log_file: Optional[str] = None) -> None:
    """
    Настройка глобального логирования
    
    Args:
        log_level: Уровень логирования
        log_file: Путь к файлу логов (опционально)
    """
    level = getattr(logging, log_level.upper(), logging.INFO)
    
    # Базовая конфигурация
    logging.basicConfig(
        level=level,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
        datefmt='%Y-%m-%d %H:%M:%S'
    )
    
    if log_file:
        handler = RotatingFileHandler(
            log_file,
            maxBytes=10 * 1024 * 1024,
            backupCount=5
        )
        formatter = logging.Formatter(
            '%(asctime)s - %(name)s - %(levelname)s - %(message)s',
            datefmt='%Y-%m-%d %H:%M:%S'
        )
        handler.setFormatter(formatter)
        logging.getLogger().addHandler(handler)
