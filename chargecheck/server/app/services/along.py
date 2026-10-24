"""목적지까지 가는 길에 들를 수 있는 충전소 찾기.

1. 경로를 받는다(OSRM 도로 경로, 실패하면 직선).
2. 경로를 2km마다 찍어 근처(1.5km) 충전소를 DB에서 모은다.
3. 각 충전소를 경로에 투영해 '출발점에서 몇 km 지점인지', '경로에서 얼마나 벗어났는지'를 구한다.
4. 빨강(실패 많음)·버스전용·급속 없는 곳(fast_only)을 빼고, 5km 구간마다 가장 좋은 곳 하나만 남긴다.
5. 남은 주행 가능 거리로 갈 수 있는지(15% 여유)와 도착 예상 배터리를 붙이고, 추천 지점을 고른다.
"""
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import Station
from app.schemas import AlongRouteOut, RouteStationOut
from app.services import external
from app.services.geo import bounding_box
from app.services.route import cumulative, project, sample, straight
from app.services.status import station_out, station_status, utcnow

RESERVE = 0.85          # 남은 거리의 85%까지만 믿는다
SAMPLE_EVERY_M = 2_000
MAX_DETOUR_M = 1_500
BUCKET_KM = 5
VERDICT_RANK = {"GREEN": 0, "GRAY": 1, "YELLOW": 2, "RED": 3}


def _thin(route: list[tuple[float, float]], max_points: int = 200) -> list[tuple[float, float]]:
    if len(route) <= max_points:
        return route
    step = len(route) / max_points
    return [route[int(i * step)] for i in range(max_points)] + [route[-1]]


def along_route(
    session: Session,
    start: tuple[float, float],
    end: tuple[float, float],
    range_km: float | None,
    soc: int | None,
    fast_only: bool = True,
) -> AlongRouteOut:
    road = external.route(start[0], start[1], end[0], end[1])
    path, source = (road[0], "ROAD") if road and len(road[0]) >= 2 else (straight(start, end), "STRAIGHT")
    cum = cumulative(path)
    total_m = road[1] if road else cum[-1]

    # 2. 경로 근처 충전소 모으기
    found: dict[str, Station] = {}
    for p in sample(path, cum, SAMPLE_EVERY_M):
        min_lat, max_lat, min_lon, max_lon = bounding_box(p[0], p[1], MAX_DETOUR_M)
        for s in session.scalars(select(Station).where(Station.lat.between(min_lat, max_lat), Station.lon.between(min_lon, max_lon))):
            found[s.stat_id] = s

    # 3~4. 투영하고 거르기
    now = utcnow()
    by_bucket: dict[int, RouteStationOut] = {}
    for s in found.values():
        on = project(path, cum, (s.lat, s.lon))
        if on.detour_m > MAX_DETOUR_M:
            continue
        st = station_status(session, s.stat_id, now)
        usable = [c for c in st.chargers if c.public_state != "OUT_OF_SERVICE" and c.verdict != "RED"]
        if not usable or st.verdict == "RED":
            continue
        if fast_only and not any(c.charger_type == "FAST" for c in usable):
            continue
        along_km = round(on.along_m / 1000, 1)
        reachable = range_km is None or along_km <= range_km * RESERVE
        arrival = round(soc * (1 - along_km / range_km)) if soc is not None and range_km else None
        cand = RouteStationOut(
            station=station_out(s), status=st, along_km=along_km, detour_m=int(on.detour_m),
            reachable=reachable, arrival_soc=arrival,
        )
        bucket = int(along_km // BUCKET_KM)
        best = by_bucket.get(bucket)
        key = (VERDICT_RANK[st.verdict], -(st.ok_count or 0), on.detour_m)
        if best is None or key < (VERDICT_RANK[best.status.verdict], -(best.status.ok_count or 0), best.detour_m):
            by_bucket[bucket] = cand

    candidates = [by_bucket[b] for b in sorted(by_bucket)]
    needs_charge = range_km is not None and total_m / 1000 > range_km * RESERVE
    recommended = None
    if needs_charge:
        # 갈 수 있는 곳 중 초록 우선, 그다음 가장 멀리(한 번 충전으로 최대한 많이 가기)
        ok = [c for c in candidates if c.reachable]
        if ok:
            recommended = min(ok, key=lambda c: (c.status.verdict != "GREEN", -c.along_km)).station.station_id

    return AlongRouteOut(
        route_km=round(total_m / 1000, 1),
        source=source,
        range_km=range_km,
        needs_charge=needs_charge,
        route=_thin(path),
        candidates=candidates,
        recommended_id=recommended,
    )
