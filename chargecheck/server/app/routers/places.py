from fastapi import APIRouter, Depends, Query
from sqlalchemy import and_, or_, select
from sqlalchemy.orm import Session

from app.db import get_session
from app.models import Station
from app.schemas import AlongRouteOut, PlaceOut
from app.services.along import along_route
from app.services.geo import distance_m

router = APIRouter(tags=["places"])


@router.get("/v1/places/search", response_model=list[PlaceOut])
def search(
    q: str = Query(min_length=1, max_length=60),
    lat: float | None = None,
    lon: float | None = None,
    session: Session = Depends(get_session),
):
    """충전소·지역 찾기. 외부 지도 서비스 없이 우리 DB(전국 충전소 이름·주소)에서 찾는다.
    "판교 드림타운"처럼 여러 낱말이면 낱말마다 이름이나 주소에 들어 있어야 한다.
    내 위치(lat, lon)를 주면 가까운 순. 일반 목적지(서울시청 등)는 차의 내비에서 정한다."""
    words = [w for w in q.split() if w][:4]
    cond = and_(*(or_(Station.name.like(f"%{w}%"), Station.address.like(f"%{w}%")) for w in words))
    rows = session.scalars(select(Station).where(cond).limit(300)).all()
    if lat is not None and lon is not None:
        rows = sorted(rows, key=lambda s: distance_m(lat, lon, s.lat, s.lon))
    return [PlaceOut(kind="STATION", name=s.name, address=s.address, lat=s.lat, lon=s.lon, station_id=s.stat_id) for s in rows[:10]]


@router.get("/v1/stations/along-route", response_model=AlongRouteOut)
def along(
    from_lat: float = Query(alias="fromLat"),
    from_lon: float = Query(alias="fromLon"),
    to_lat: float = Query(alias="toLat"),
    to_lon: float = Query(alias="toLon"),
    range_km: float | None = Query(None, alias="rangeKm", gt=0),
    soc: int | None = Query(None, ge=0, le=100),
    fast_only: bool = Query(True, alias="fastOnly"),
    road_km: float | None = Query(None, alias="roadKm", gt=0),
    session: Session = Depends(get_session),
):
    """목적지까지 가는 길 근처의 충전소. 차의 남은 주행 가능 거리(rangeKm)·배터리(soc)를 주면 갈 수 있는지·도착 배터리·추천 지점을 함께 준다.
    roadKm = 내비가 알려 준 목적지까지 남은 도로 거리(있으면 더 정확)."""
    return along_route(session, (from_lat, from_lon), (to_lat, to_lon), range_km, soc, fast_only, road_km)
