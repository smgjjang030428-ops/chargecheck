"""로컬 DB(SQLite)를 배포 DB(PostgreSQL)로 옮긴다. 전국 목록을 서버에서 다시 받는 것보다 빠르고, 쌓인 기록·운영사 알림도 함께 옮겨진다.

    .venv\Scripts\python -m app.jobs.copy_db --from sqlite:///./chargecheck_real.db --to "postgresql://..."

대상 DB는 비어 있어야 한다(같은 키가 있으면 멈춘다). 주소는 로그에 남기지 않는다.
"""
import argparse
from datetime import timezone

from sqlalchemy import DateTime as SADateTime, func, insert, select

from app import models  # noqa: F401  테이블 등록
from app.config import Settings
from app.db import Base, ensure_columns, make_engine

BATCH = 5000


def _url(raw: str) -> str:
    return Settings(DATABASE_URL=raw).database_url


def _utc(row: dict, tz_cols: list[str]) -> dict:
    # SQLite는 시간대 없이 UTC로 저장돼 있다. PostgreSQL에는 UTC임을 붙여서 넣는다.
    for c in tz_cols:
        v = row.get(c)
        if v is not None and v.tzinfo is None:
            row[c] = v.replace(tzinfo=timezone.utc)
    return row


def copy(src_url: str, dst_url: str) -> dict[str, int]:
    src, dst = make_engine(_url(src_url)), make_engine(_url(dst_url))
    ensure_columns(src)
    Base.metadata.create_all(dst)
    ensure_columns(dst)
    counts = {}
    with src.connect() as s, dst.begin() as d:
        for table in Base.metadata.sorted_tables:  # 외래 키 순서(station → charger → …)
            tz_cols = [c.name for c in table.columns if isinstance(c.type, SADateTime)]
            n = 0
            batch = []
            for row in s.execution_options(yield_per=BATCH).execute(select(table)).mappings():
                batch.append(_utc(dict(row), tz_cols))
                if len(batch) >= BATCH:
                    d.execute(insert(table), batch)
                    n += len(batch)
                    batch = []
                    print(f"  {table.name}: {n:,}", end="\r", flush=True)
            if batch:
                d.execute(insert(table), batch)
                n += len(batch)
            counts[table.name] = n
            print(f"  {table.name}: {n:,}행")
        # PostgreSQL의 자동 증가 번호를 옮긴 마지막 번호 뒤로 맞춘다
        if dst.dialect.name == "postgresql":
            for table in Base.metadata.sorted_tables:
                pk = list(table.primary_key.columns)
                if len(pk) == 1 and pk[0].autoincrement is not False and pk[0].type.python_type is int:
                    top = d.scalar(select(func.max(pk[0])))
                    if top:
                        d.exec_driver_sql(
                            f"SELECT setval(pg_get_serial_sequence('{table.name}', '{pk[0].name}'), {int(top)})")
    return counts


def main() -> None:
    p = argparse.ArgumentParser(description="SQLite → PostgreSQL 복사")
    p.add_argument("--from", dest="src", required=True)
    p.add_argument("--to", dest="dst", required=True)
    a = p.parse_args()
    copy(a.src, a.dst)
    print("완료")


if __name__ == "__main__":
    main()
