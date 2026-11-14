"""
Database models for configuration storage
"""
from sqlalchemy import create_engine, Column, Integer, String, Text, Boolean, DateTime
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker
from datetime import datetime
import os

from ..utils.logger import get_logger

logger = get_logger(__name__)

# Создаем базу данных
DATABASE_URL = os.getenv("DATABASE_URL", "sqlite:///data/config.db")

engine = create_engine(
    DATABASE_URL,
    connect_args={"check_same_thread": False} if "sqlite" in DATABASE_URL else {}
)

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

Base = declarative_base()


class Config(Base):
    """Модель для хранения конфигурации"""
    
    __tablename__ = "config"
    
    id = Column(Integer, primary_key=True, index=True)
    key = Column(String(100), unique=True, index=True, nullable=False)
    value = Column(Text, nullable=True)
    encrypted = Column(Boolean, default=False)
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
    
    def __repr__(self):
        return f"<Config(key='{self.key}', encrypted={self.encrypted})>"


def init_db():
    """Инициализация базы данных"""
    from pathlib import Path
    
    # Создаем директорию data если не существует
    db_path = Path(DATABASE_URL.replace("sqlite:///", ""))
    db_path.parent.mkdir(parents=True, exist_ok=True)
    
    logger.info(f"Инициализация базы данных: {DATABASE_URL}")
    Base.metadata.create_all(bind=engine)
    logger.info("✅ База данных инициализирована")


def get_db():
    """Dependency для получения сессии БД"""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
