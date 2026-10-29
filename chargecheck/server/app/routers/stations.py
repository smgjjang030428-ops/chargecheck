from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db import get_session
from app.models import Station
from app.schemas import AlternativeOut, NearbyStationOut, StationStatusOut
from app.services.geo import bounding_box, distance_m
from app.services.status import station_out, station_status, utcnow

router = APIRouter(prefix="/v1/stations", tags=["stations"])

VERDICT_ORDER = {"GREEN": 0, "GRAY": 1, "YELLOW": 2, "RED": 3}
ALT_MAX_CHECK = 40  # 대체 충전소 후보로 판정해 볼 최대 개수(가까운 순)


def _within(session: Session, lat: float, lon: float, radius_m: int) -> list[tuple[Station, int]]:
    min_lat, max_lat, min_lon, max_lon = bounding_box(lat, lon, radius_m)
    rows = session.scalars(
        select(Station).where(Station.lat.between(min_lat, max_lat), Station.lon.between(min_lon, max_lon))
    ).all()
    found = [(s, int(distance_m(lat, lon, s.lat, s.lon))) for s in rows]
    return sorted((p for p in found if p[1] <= radius_m), key=lambda p: p[1])


@router.get("/nearby", response_model=list[NearbyStationOut])
def nearby(lat: float, lon: float, radius: int = Query(500, ge=50, le=10_000), session: Session = Depends(get_session)):
    """주변 충전소(최대 60개, 거리순)와 판정. 지도 화면이 한 번에 그리도록 상태를 같이 준다.
    승용차가 쓸 충전기가 없는 곳(버스전용만 있는 곳)은 뺀다."""
    now = utcnow()
    out = []
    for s, d in _within(session, lat, lon, radius):
        st = station_status(session, s.stat_id, now)
        if not st.chargers:
            continue
        out.append(NearbyStationOut(**station_out(s).model_dump(), distance_m=d, status=st))
        if len(out) == 60:
            break
    return out


@router.get("/{station_id}/status", response_model=StationStatusOut)
def status(station_id: str, session: Session = Depends(get_session)):
    if session.get(Station, station_id) is None:
        raise HTTPException(404, "충전소 없음")
    return station_status(session, station_id)


@router.get("/{station_id}/alternatives", response_model=list[AlternativeOut])
def alternatives(station_id: str, lat: float, lon: float, session: Session = Depends(get_session)):
    """대체 충전소: 5km 이내, 경고 대상(RED·YELLOW)이 아닌 곳 우선, 최대 3개.
    충전소가 많은 곳(판교 5km = 수백 곳)에서 전부 판정하면 배포 DB에서 20초 넘게 걸려 앱이 기다리지 못한다.
    그래서 가까운 순으로 [ALT_MAX_CHECK]곳까지만 판정하고, 초록이 3곳 모이면 거기서 멈춘다."""
    now = utcnow()
    candidates = []
    greens = 0
    for s, d in _within(session, lat, lon, 5_000):
        if s.stat_id == station_id:
            continue
        st = station_status(session, s.stat_id, now)
        candidates.append(AlternativeOut(station=station_out(s), status=st, distance_m=d))
        greens += st.verdict == "GREEN"
        if len(candidates) >= ALT_MAX_CHECK or greens >= 3:
            break
    candidates.sort(key=lambda a: (VERDICT_ORDER[a.status.verdict], a.distance_m))
    return [a for a in candidates if a.status.verdict != "RED"][:3]
