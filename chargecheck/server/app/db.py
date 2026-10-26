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


engine = make_engine(settings.DATABASE_URL)
SessionLocal = sessionmaker(bind=engine, expire_on_commit=False)


# 테이블이 이미 있으면 create_all이 새 칼럼을 만들지 않는다. 마이그레이션 도구 없이 쓰는 동안의 최소 보정.
NEW_COLUMNS = {
    "charge_event": {"reason": "VARCHAR"},
    "charger": {"last_charging_at": "DATETIME", "repaired_at": "DATETIME"},
}


def ensure_columns(eng) -> None:
    insp = inspect(eng)
    with eng.begin() as conn:
        for table, cols in NEW_COLUMNS.items():
            if not insp.has_table(table):
                continue
            have = {c["name"] for c in insp.get_columns(table)}
            for name, sql_type in cols.items():
                if name not in have:
                    conn.execute(text(f"ALTER TABLE {table} ADD COLUMN {name} {sql_type}"))


def get_session() -> Iterator[Session]:
    with SessionLocal() as session:
        yield session
