from collections.abc import Iterator

from sqlalchemy import create_engine, inspect, text
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from app.config import settings


class Base(DeclarativeBase):
    pass


def make_engine(url: str):
    # SQLite는 여러 스레드(동기화 작업 + 요청)에서 같이 쓰도록 허용한다.
    args = {"check_same_thread": False} if url.startswith("sqlite") else {}
    return create_engine(url, connect_args=args)


engine = make_engine(settings.database_url)
SessionLocal = sessionmaker(bind=engine, expire_on_commit=False)


# 테이블이 이미 있으면 create_all이 새 칼럼을 만들지 않는다. 마이그레이션 도구 없이 쓰는 동안의 최소 보정.
NEW_COLUMNS = {
    "charge_event": {"reason": "VARCHAR"},
    "charger": {"last_charging_at": "TIMESTAMP", "repaired_at": "TIMESTAMP"},
}
# PostgreSQL의 TIMESTAMP는 시간대가 없으므로 모델(DateTime(timezone=True))과 맞춘다.
PG_TYPES = {"TIMESTAMP": "TIMESTAMP WITH TIME ZONE"}


def ensure_columns(eng) -> None:
    insp = inspect(eng)
    pg = eng.dialect.name == "postgresql"
    with eng.begin() as conn:
        for table, cols in NEW_COLUMNS.items():
            if not insp.has_table(table):
                continue
            have = {c["name"] for c in insp.get_columns(table)}
            for name, sql_type in cols.items():
                if name not in have:
                    if pg:
                        sql_type = PG_TYPES.get(sql_type, sql_type)
                    conn.execute(text(f"ALTER TABLE {table} ADD COLUMN {name} {sql_type}"))


def get_session() -> Iterator[Session]:
    with SessionLocal() as session:
        yield session
