"""한국환경공단 전기차 충전소 API 동기화 (기획 §10).

- 충전소·충전기 목록: getChargerInfo (시·도 코드별, 페이지 단위)
- 상태 변경분:       getChargerStatus (period분 이내 변경분)

사용:
    python -m app.jobs.public_sync --info            # 목록 전체 (PUBLIC_API_KEY 필요)
    python -m app.jobs.public_sync --status          # 상태 변경분
    python -m app.jobs.public_sync --fixture FILE    # 키 없이 저장된 응답(JSON)으로 채우기
"""
import argparse
import json
import logging
import time
from collections.abc import Iterator
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx
from sqlalchemy import Column, DateTime, MetaData, String, Table, and_, case, delete, func, or_, select, update
from sqlalchemy.orm import Session

from app.config import PUBLIC_STAT_MAP, SLOW_CHARGER_TYPES, settings
from app.db import Base, SessionLocal, engine
from app.models import Charger, Station

log = logging.getLogger("public_sync")
# httpx는 요청 URL을 INFO로 남기는데 URL에 serviceKey가 들어 있다. 키가 로그에 남지 않게 막는다.
logging.getLogger("httpx").setLevel(logging.WARNING)
logging.getLogger("httpcore").setLevel(logging.WARNING)
KST = timezone(timedelta(hours=9))  # 한국은 서머타임이 없어 고정 오프셋으로 충분하다
PAGE_SIZE = 9999


def parse_kst(value: str | None) -> datetime | None:
    """'20261024154523'(KST) → UTC datetime."""
    if not value or len(value) < 14:
        return None
    try:
        return datetime.strptime(value[:14], "%Y%m%d%H%M%S").replace(tzinfo=KST).astimezone(timezone.utc)
    except ValueError:
        return None


def plausible_coords(lat: float, lon: float) -> bool:
    """한국 범위 밖이거나 (33.0, 125.0)·(37.5, 127.0)처럼 소수 첫째 자리까지만 있는 자리표시 좌표는 버린다.
    실제 서울 데이터에서 확인한 문제(2026-10-24): 12,937곳 중 13곳."""
    if not (33.0 <= lat <= 38.7 and 124.5 <= lon <= 132.0):
        return False
    return not (round(lat, 1) == lat and round(lon, 1) == lon)


def _int_or_none(value) -> int | None:
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return None


def items_of(payload: dict) -> list[dict]:
    """응답 JSON에서 item 목록을 꺼낸다. 결과가 1건이면 dict로 오는 경우도 처리한다."""
    items = (payload.get("items") or {}).get("item") or []
    return [items] if isinstance(items, dict) else list(items)


def fetch_all(client: httpx.Client, operation: str, params: dict) -> list[dict]:
    """페이지를 끝까지 읽어 한 목록으로. 상태 변경분처럼 작은 응답에 쓴다."""
    return [row for batch in iter_pages(client, operation, params) for row in batch]


def iter_pages(client: httpx.Client, operation: str, params: dict) -> Iterator[list[dict]]:
    """페이지를 하나씩 돌려준다(전국 목록 52만 대를 한꺼번에 메모리에 올리지 않기 위해).
    5xx·타임아웃은 3번까지 다시 시도한다(실제로 504가 가끔 온다)."""
    seen = 0
    page = 1
    while True:
        query = {"serviceKey": settings.PUBLIC_API_KEY, "pageNo": page, "numOfRows": PAGE_SIZE, "dataType": "JSON", **params}
        payload = None
        for attempt in range(3):
            try:
                r = client.get(f"{settings.PUBLIC_API_BASE}/{operation}", params=query, timeout=30)
                if r.status_code >= 500:
                    raise httpx.HTTPStatusError(f"{r.status_code}", request=r.request, response=r)
                r.raise_for_status()
                payload = r.json()
                break
            except (httpx.HTTPError, ValueError) as e:
                # 예외 문장에 URL(=키)이 섞일 수 있어 종류만 남긴다
                log.warning("%s 실패(%d회째): %s", operation, attempt + 1, type(e).__name__)
                time.sleep(2 * (attempt + 1))
        if payload is None:
            raise RuntimeError(f"{operation} 호출 실패")
        batch = items_of(payload)
        seen += len(batch)
        if batch:
            yield batch
        total = int(payload.get("totalCount") or 0)
        if not batch or seen >= total:
            return
        page += 1


def _insert(session: Session, model):
    """PostgreSQL·SQLite 둘 다 되는 INSERT … ON CONFLICT DO UPDATE."""
    if session.get_bind().dialect.name == "postgresql":
        from sqlalchemy.dialects.postgresql import insert
    else:
        from sqlalchemy.dialects.sqlite import insert
    return insert(model)


def _chunks(items: list, size: int = 1000) -> Iterator[list]:
    for i in range(0, len(items), size):
        yield items[i:i + size]


def _upsert(session: Session, model, rows: list[dict], keys: list[str]) -> None:
    for chunk in _chunks(rows):
        stmt = _insert(session, model).values(chunk)
        update = {c: stmt.excluded[c] for c in chunk[0] if c not in keys}
        session.execute(stmt.on_conflict_do_update(index_elements=keys, set_=update))


def upsert_charger_info(session: Session, rows: list[dict], refresh_kinds: bool = True) -> tuple[int, int]:
    """getChargerInfo 행들을 충전소·충전기 테이블에 한꺼번에 반영한다(전국 52만 대 대비).
    반환: (충전소 수, 충전기 수). 페이지마다 부를 때는 refresh_kinds=False로 두고 끝에 refresh_station_kinds를 부른다."""
    now = datetime.now(timezone.utc)
    stations: dict[str, dict] = {}
    chargers: dict[tuple[str, str], dict] = {}
    deleted: list[tuple[str, str]] = []
    bad: set[str] = set()
    for row in rows:
        stat_id, chger_id = row.get("statId"), row.get("chgerId")
        if not stat_id or not chger_id:
            continue
        if row.get("delYn") == "Y":
            deleted.append((stat_id, chger_id))
            continue
        try:
            lat, lon = float(row["lat"]), float(row["lng"])
        except (KeyError, TypeError, ValueError):
            continue
        if not plausible_coords(lat, lon):
            bad.add(stat_id)
            continue
        if stat_id not in stations:
            stations[stat_id] = dict(
                stat_id=stat_id,
                name=row.get("statNm", ""),
                address=row.get("addr", ""),
                lat=lat,
                lon=lon,
                operator=row.get("busiNm", ""),
                operator_call=row.get("busiCall", ""),
                use_time=row.get("useTime", ""),
                parking_free=row.get("parkingFree", ""),
                limit_detail=row.get("limitDetail", "") if row.get("limitYn") == "Y" else "",
                charger_type="SLOW",
                updated_at=now,
            )
        if row.get("chgerType") not in SLOW_CHARGER_TYPES:
            stations[stat_id]["charger_type"] = "FAST"
        chargers[(stat_id, chger_id)] = dict(
            stat_id=stat_id,
            chger_id=chger_id,
            chger_type=row.get("chgerType", ""),
            output_kw=_int_or_none(row.get("output")),
            public_stat=str(row.get("stat", "9")),
            stat_upd_dt=parse_kst(row.get("statUpdDt")),
        )
    bad -= stations.keys()  # 같은 충전소의 다른 행에 정상 좌표가 있으면 살린다

    if stations:
        _upsert(session, Station, list(stations.values()), ["stat_id"])
    if chargers:
        _upsert(session, Charger, list(chargers.values()), ["stat_id", "chger_id"])
    for stat_id, chger_id in deleted:
        session.execute(delete(Charger).where(Charger.stat_id == stat_id, Charger.chger_id == chger_id))
    # 자리표시 좌표 충전소는 예전에 저장된 것까지 지운다(지도에 엉뚱한 위치로 나오지 않게)
    for chunk in _chunks(sorted(bad)):
        session.execute(delete(Charger).where(Charger.stat_id.in_(chunk)))
        session.execute(delete(Station).where(Station.stat_id.in_(chunk)))
    if refresh_kinds:
        refresh_station_kinds(session)
    session.commit()
    if bad:
        log.info("좌표가 자리표시값인 충전소 %d곳은 건너뜀", len(bad))
    return len(stations), len(chargers)


def refresh_station_kinds(session: Session) -> None:
    """충전소 급속/완속을 저장된 충전기 전체로 다시 정한다(페이지 경계로 나뉜 충전소 대비)."""
    fast = select(Charger.stat_id).where(Charger.stat_id == Station.stat_id, Charger.chger_type.not_in(SLOW_CHARGER_TYPES)).exists()
    session.execute(update(Station).values(charger_type=case((fast, "FAST"), else_="SLOW")))


def _codes(state: str) -> list[str]:
    return [code for code, s in PUBLIC_STAT_MAP.items() if s == state]


# 상태 변경분을 잠시 담는 임시 테이블. 접속마다 따로 생기고 반영 뒤 지운다.
_incoming = Table(
    "incoming_status", MetaData(),
    Column("stat_id", String), Column("chger_id", String), Column("stat", String),
    Column("upd", DateTime(timezone=True)), Column("at", DateTime(timezone=True)),
    prefixes=["TEMPORARY"],
)


def apply_status(session: Session, rows: list[dict]) -> int:
    """getChargerStatus 변경분을 이미 아는 충전기에만 반영한다. 반환: 반영한 충전기 수.

    전국 변경분은 5분마다 1만 건이 넘는다. 충전기마다 조회하면 배포 DB(Neon 무료, 월 5GB 전송) 응답이
    하루 수 GB가 돼 한도를 넘었다(2026-11-05). 그래서 변경분을 임시 테이블에 한꺼번에 넣고
    UPDATE … FROM 한 번으로 반영한다. DB에서 앱 서버로 돌아오는 데이터가 거의 없다."""
    now = datetime.now(timezone.utc)
    latest: dict[tuple[str, str], dict] = {}
    for row in rows:
        key = (row.get("statId"), row.get("chgerId"))
        if not key[0] or not key[1]:
            continue
        upd = parse_kst(row.get("statUpdDt"))
        # 같은 충전기가 두 번 오면 최신 것만(UPDATE … FROM은 한 행에 한 번만 반영된다)
        prev = latest.get(key)
        if prev and prev["upd"] and (upd is None or upd < prev["upd"]):
            continue
        latest[key] = dict(stat_id=key[0], chger_id=key[1], stat=str(row.get("stat", "")), upd=upd, at=upd or now)
    if not latest:
        return 0

    c, i = Charger.__table__.c, _incoming.c
    conn = session.connection()
    _incoming.drop(conn, checkfirst=True)
    _incoming.create(conn)
    for chunk in _chunks(list(latest.values()), 500):
        conn.execute(_incoming.insert().values(chunk))  # 여러 행을 문장 하나로 보낸다
    charging, out, usable = _codes("CHARGING"), _codes("OUT_OF_SERVICE"), _codes("AVAILABLE") + _codes("CHARGING")
    stat = case((i.stat == "", c.public_stat), else_=i.stat)
    result = conn.execute(
        update(Charger.__table__)
        .where(c.stat_id == i.stat_id, c.chger_id == i.chger_id)
        # 늦게 도착한 옛 상태는 무시
        .where(or_(i.upd.is_(None), c.stat_upd_dt.is_(None), i.upd >= c.stat_upd_dt))
        .values(
            public_stat=stat,
            stat_upd_dt=func.coalesce(i.upd, c.stat_upd_dt),
            # 바뀐 순간을 남긴다: '충전 중'이 됨 = 누군가 실제로 충전, '점검 → 사용 가능' = 수리됐을 수 있음
            last_charging_at=case((and_(stat == "3", c.public_stat.not_in(charging)), i.at), else_=c.last_charging_at),
            repaired_at=case((and_(c.public_stat.in_(out), stat.in_(usable)), i.at), else_=c.repaired_at),
        )
    )
    _incoming.drop(conn)
    session.commit()
    return result.rowcount


def sync_info(session: Session, client: httpx.Client) -> tuple[int, int]:
    """PUBLIC_ZCODES의 시·도별(또는 ALL = 전국) 목록을 페이지 단위로 받아 바로 저장한다."""
    zcodes = [z.strip() for z in settings.PUBLIC_ZCODES.split(",") if z.strip()]
    params_list = [{}] if any(z.upper() == "ALL" for z in zcodes) else [{"zcode": z} for z in zcodes]
    n_stations = n_chargers = 0
    for params in params_list:
        for i, batch in enumerate(iter_pages(client, "getChargerInfo", params), start=1):
            s, c = upsert_charger_info(session, batch, refresh_kinds=False)
            n_stations, n_chargers = n_stations + s, n_chargers + c
            log.info("목록 %s %d페이지: 누적 충전기 %d대", params.get("zcode", "전국"), i, n_chargers)
    refresh_station_kinds(session)
    session.commit()
    # 충전소가 여러 페이지에 걸치면 두 번 셀 수 있어 실제 개수는 DB에서 다시 센다
    n_stations = session.scalar(select(func.count()).select_from(Station))
    return n_stations, n_chargers


def sync_status(session: Session, client: httpx.Client, period_min: int = 10) -> int:
    return apply_status(session, fetch_all(client, "getChargerStatus", {"period": period_min}))


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    parser = argparse.ArgumentParser()
    parser.add_argument("--info", action="store_true")
    parser.add_argument("--status", action="store_true")
    parser.add_argument("--fixture", type=Path)
    args = parser.parse_args()
    Base.metadata.create_all(engine)
    with SessionLocal() as session:
        if args.fixture:
            rows = items_of(json.loads(args.fixture.read_text(encoding="utf-8")))
            log.info("fixture 반영: 충전소 %d곳, 충전기 %d대", *upsert_charger_info(session, rows))
            return
        if not settings.PUBLIC_API_KEY:
            raise SystemExit("PUBLIC_API_KEY가 없습니다. server/.env에 넣어 주세요(.env.example 참고).")
        with httpx.Client() as client:
            if args.info:
                log.info("목록 동기화: 충전소 %d곳, 충전기 %d대", *sync_info(session, client))
            if args.status:
                log.info("상태 동기화: %d대 변경", sync_status(session, client))


if __name__ == "__main__":
    main()
