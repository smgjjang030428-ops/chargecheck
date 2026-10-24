from fastapi import APIRouter, Depends, Query
from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from app.db import get_session
from app.models import Station
from app.schemas import AlongRouteOut, PlaceOut
from app.services import external
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
    """지역·장소 검색. 일반 장소(지도 서비스) + 이름·주소가 맞는 충전소(우리 DB).
    충전소는 내 위치(lat, lon)를 주면 가까운 순."""
    places: list[PlaceOut] = []
    for p in external.search_places(q.strip()) or ():
        # 같은 이름이 300m 안에 또 나오면(역 출구·건물 동 등) 하나만 남긴다
        if not any(x.name == p["name"] and distance_m(x.lat, x.lon, p["lat"], p["lon"]) < 300 for x in places):
            places.append(PlaceOut(kind="PLACE", **p))
    like = f"%{q.strip()}%"
    rows = session.scalars(select(Station).where(or_(Station.name.like(like), Station.address.like(like))).limit(200)).all()
    if lat is not None and lon is not None:
        rows = sorted(rows, key=lambda s: distance_m(lat, lon, s.lat, s.lon))
    stations = [PlaceOut(kind="STATION", name=s.name, address=s.address, lat=s.lat, lon=s.lon, station_id=s.stat_id) for s in rows[:6]]
    return places + stations


@router.get("/v1/stations/along-route", response_model=AlongRouteOut)
def along(
    from_lat: float = Query(alias="fromLat"),
    from_lon: float = Query(alias="fromLon"),
    to_lat: float = Query(alias="toLat"),
    to_lon: float = Query(alias="toLon"),
    range_km: float | None = Query(None, alias="rangeKm", gt=0),
    soc: int | None = Query(None, ge=0, le=100),
    fast_only: bool = Query(True, alias="fastOnly"),
    session: Session = Depends(get_session),
):
    """목적지까지 가는 길의 충전소. 차의 남은 주행 가능 거리(rangeKm)와 배터리(soc)를 주면 갈 수 있는지·도착 배터리·추천 지점을 함께 준다."""
    return along_route(session, (from_lat, from_lon), (to_lat, to_lon), range_km, soc, fast_only)
