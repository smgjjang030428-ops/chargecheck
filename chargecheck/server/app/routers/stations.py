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


def _within(session: Session, lat: float, lon: float, radius_m: int) -> list[tuple[Station, int]]:
    min_lat, max_lat, min_lon, max_lon = bounding_box(lat, lon, radius_m)
    rows = session.scalars(
        select(Station).where(Station.lat.between(min_lat, max_lat), Station.lon.between(min_lon, max_lon))
    ).all()
    found = [(s, int(distance_m(lat, lon, s.lat, s.lon))) for s in rows]
    return sorted((p for p in found if p[1] <= radius_m), key=lambda p: p[1])


@router.get("/nearby", response_model=list[NearbyStationOut])
def nearby(lat: float, lon: float, radius: int = Query(500, ge=50, le=10_000), session: Session = Depends(get_session)):
    """주변 충전소(최대 30개, 거리순)와 판정. 지도 화면이 한 번에 그리도록 상태를 같이 준다."""
    now = utcnow()
    return [
        NearbyStationOut(**station_out(s).model_dump(), distance_m=d, status=station_status(session, s.stat_id, now))
        for s, d in _within(session, lat, lon, radius)[:30]
    ]


@router.get("/{station_id}/status", response_model=StationStatusOut)
def status(station_id: str, session: Session = Depends(get_session)):
    if session.get(Station, station_id) is None:
        raise HTTPException(404, "충전소 없음")
    return station_status(session, station_id)


@router.get("/{station_id}/alternatives", response_model=list[AlternativeOut])
def alternatives(station_id: str, lat: float, lon: float, session: Session = Depends(get_session)):
    """대체 충전소: 5km 이내, 경고 대상(RED·YELLOW)이 아닌 곳 우선, 최대 3개."""
    now = utcnow()
    candidates = []
    for s, d in _within(session, lat, lon, 5_000):
        if s.stat_id == station_id:
            continue
        st = station_status(session, s.stat_id, now)
        candidates.append(AlternativeOut(station=station_out(s), status=st, distance_m=d))
    candidates.sort(key=lambda a: (VERDICT_ORDER[a.status.verdict], a.distance_m))
    return [a for a in candidates if a.status.verdict != "RED"][:3]
