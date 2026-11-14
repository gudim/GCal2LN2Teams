"""
Модель для истории синхронизации событий (связи оригинал -> копия и двойники)
"""
from sqlalchemy import Boolean, Column, DateTime, Integer, String, Text, event, inspect
from sqlalchemy.sql import func

from .models import Base


class SyncHistory(Base):
    """Связь между событиями двух систем"""
    __tablename__ = "sync_history"

    id = Column(Integer, primary_key=True, autoincrement=True)
    event_key = Column(String, nullable=False, index=True, unique=True)  # см. Link.key()
    source_system = Column(String, nullable=False)  # 'google', 'domino' или 'teams'
    target_system = Column(String, nullable=False)
    source_event_id = Column(String, nullable=True)
    target_event_id = Column(String, nullable=True)
    synced_at = Column(DateTime(timezone=True), server_default=func.now())
    kind = Column(String, nullable=True)  # copy | twin | distinct; NULL = copy (старые строки)
    snapshot = Column(Text, nullable=True)  # JSON {"source": {...}, "target": {...}}
    kept = Column(Boolean, nullable=True)  # одна сторона удалена, другую оставили
    # ID календаря Google, к которому относится связь с Google; NULL = primary (старые строки)
    google_calendar = Column(String, nullable=True)

    def __repr__(self):
        return f"<SyncHistory('{self.event_key}', {self.source_system}→{self.target_system})>"


# Колонки, добавленные позже: в существующую таблицу дописываем их при старте
_ADDED_COLUMNS = {
    "kind": "VARCHAR", "snapshot": "TEXT", "kept": "BOOLEAN", "google_calendar": "VARCHAR",
}


@event.listens_for(Base.metadata, "after_create")
def _add_missing_columns(target, connection, **kw):
    """create_all не меняет существующие таблицы: добавляем недостающие колонки сами."""
    existing = {c["name"] for c in inspect(connection).get_columns(SyncHistory.__tablename__)}
    for name, sql_type in _ADDED_COLUMNS.items():
        if name not in existing:
            connection.exec_driver_sql(
                f"ALTER TABLE {SyncHistory.__tablename__} ADD COLUMN {name} {sql_type}"
            )
