"""한국환경공단 전기차 충전소 API 동기화 (명세 §5.5).

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
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import SLOW_CHARGER_TYPES, settings
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
    """페이지를 끝까지 읽는다. 5xx·타임아웃은 3번까지 다시 시도한다(실제로 504가 가끔 온다)."""
    rows: list[dict] = []
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
        rows.extend(batch)
        total = int(payload.get("totalCount") or 0)
        if not batch or len(rows) >= total:
            return rows
        page += 1


def upsert_charger_info(session: Session, rows: list[dict]) -> tuple[int, int]:
    """getChargerInfo 행들을 충전소·충전기 테이블에 반영한다. 반환: (충전소 수, 충전기 수)."""
    now = datetime.now(timezone.utc)
    skipped_coords = 0
    stations: dict[str, list[dict]] = {}
    for row in rows:
        if row.get("delYn") == "Y":
            charger = session.get(Charger, (row["statId"], row["chgerId"]))
            if charger is not None:
                session.delete(charger)
            continue
        stations.setdefault(row["statId"], []).append(row)

    for stat_id, chargers in stations.items():
        first = chargers[0]
        try:
            lat, lon = float(first["lat"]), float(first["lng"])
        except (KeyError, TypeError, ValueError):
            continue
        if not plausible_coords(lat, lon):
            skipped_coords += 1
            # 예전에 저장된 같은 충전소가 있으면 지운다(지도에 엉뚱한 위치로 나오지 않게)
            for old in session.scalars(select(Charger).where(Charger.stat_id == stat_id)).all():
                session.delete(old)
            if (old_station := session.get(Station, stat_id)) is not None:
                session.delete(old_station)
            continue
        kinds = {("SLOW" if c.get("chgerType") in SLOW_CHARGER_TYPES else "FAST") for c in chargers}
        station = session.get(Station, stat_id) or Station(stat_id=stat_id)
        station.name = first.get("statNm", "")
        station.address = first.get("addr", "")
        station.lat, station.lon = lat, lon
        station.operator = first.get("busiNm", "")
        station.operator_call = first.get("busiCall", "")
        station.use_time = first.get("useTime", "")
        station.parking_free = first.get("parkingFree", "")
        station.limit_detail = first.get("limitDetail", "") if first.get("limitYn") == "Y" else ""
        station.charger_type = "FAST" if "FAST" in kinds else "SLOW"
        station.updated_at = now
        session.merge(station)
        for c in chargers:
            charger = session.get(Charger, (stat_id, c["chgerId"])) or Charger(stat_id=stat_id, chger_id=c["chgerId"])
            charger.chger_type = c.get("chgerType", "")
            charger.output_kw = _int_or_none(c.get("output"))
            charger.public_stat = str(c.get("stat", "9"))
            charger.stat_upd_dt = parse_kst(c.get("statUpdDt"))
            session.merge(charger)
    session.commit()
    if skipped_coords:
        log.info("좌표가 자리표시값인 충전소 %d곳은 건너뜀", skipped_coords)
    return len(stations) - skipped_coords, sum(len(v) for v in stations.values())


def apply_status(session: Session, rows: list[dict]) -> int:
    """getChargerStatus 변경분을 이미 아는 충전기에만 반영한다. 반환: 바뀐 충전기 수."""
    changed = 0
    for row in rows:
        charger = session.get(Charger, (row.get("statId"), row.get("chgerId")))
        if charger is None:
            continue
        upd = parse_kst(row.get("statUpdDt"))
        prev = charger.stat_upd_dt if charger.stat_upd_dt is None or charger.stat_upd_dt.tzinfo else charger.stat_upd_dt.replace(tzinfo=timezone.utc)
        if upd is not None and prev is not None and upd < prev:
            continue  # 늦게 도착한 옛 상태는 무시
        charger.public_stat = str(row.get("stat", charger.public_stat))
        charger.stat_upd_dt = upd or charger.stat_upd_dt
        changed += 1
    session.commit()
    return changed


def sync_info(session: Session, client: httpx.Client) -> tuple[int, int]:
    rows: list[dict] = []
    for zcode in [z.strip() for z in settings.PUBLIC_ZCODES.split(",") if z.strip()]:
        rows.extend(fetch_all(client, "getChargerInfo", {"zcode": zcode}))
    return upsert_charger_info(session, rows)


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
