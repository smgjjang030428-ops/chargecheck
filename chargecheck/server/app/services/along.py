"""목적지까지 가는 길에 들를 수 있는 충전소 찾기. 외부 지도·경로 서버를 쓰지 않는다.

실제 길은 차의 내비가 짜므로, 여기서는 '어디쯤에서 충전하면 되는지'만 고른다.
1. 출발지~목적지를 잇는 직선을 2km마다 찍고, 그 주변 띠(짧은 길 1.5km, 긴 길 3km) 안 충전소를 모은다.
2. 각 충전소를 직선에 투영해 '얼마나 가서 있는지'를 구하고, 도로 거리로 환산한다
   (내비가 알려 준 남은 거리 roadKm가 있으면 그 비율, 없으면 직선 × 1.3).
3. 빨강(실패 많음)·버스전용·급속 없는 곳(fast_only)을 빼고, 5km 구간마다 가장 좋은 곳 하나만 남긴다.
4. 남은 주행 가능 거리로 갈 수 있는지(15% 여유)와 도착 예상 배터리를 붙이고, 추천 지점을 고른다.
"""
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import Station
from app.schemas import AlongRouteOut, RouteStationOut
from app.services.geo import bounding_box
from app.services.route import cumulative, project, sample, straight
from app.services.status import station_out, station_status, utcnow

RESERVE = 0.85          # 남은 거리의 85%까지만 믿는다
SAMPLE_EVERY_M = 2_000
BUCKET_KM = 5
ROAD_FACTOR = 1.3       # 내비 거리를 모를 때 직선 → 도로 거리 어림
VERDICT_RANK = {"GREEN": 0, "GRAY": 1, "YELLOW": 2, "RED": 3}


def _no_free(c: RouteStationOut) -> bool:
    """운영사 정보상 지금 빈 충전기가 하나도 없음(모두 충전 중·점검). 도착할 때 기다릴 가능성이 크다."""
    return c.status.public_available == 0


def _rank(c: RouteStationOut) -> tuple:
    return (not c.reachable, VERDICT_RANK[c.status.verdict], _no_free(c), -(c.status.ok_count or 0), c.detour_m)


def corridor_m(straight_m: float) -> int:
    """짧은 길은 좁게(엉뚱하게 돌아가지 않게), 긴 길은 넓게(도로가 직선에서 벗어나므로)."""
    return 1_500 if straight_m < 20_000 else 3_000


def along_route(
    session: Session,
    start: tuple[float, float],
    end: tuple[float, float],
    range_km: float | None,
    soc: int | None,
    fast_only: bool = True,
    road_km: float | None = None,
) -> AlongRouteOut:
    path = straight(start, end)
    cum = cumulative(path)
    straight_m = cum[-1]
    total_m = road_km * 1000 if road_km else straight_m * ROAD_FACTOR
    factor = min(2.0, max(1.0, total_m / straight_m)) if straight_m > 0 else 1.0
    width = corridor_m(straight_m)

    # 1. 띠 안 충전소 모으기
    found: dict[str, Station] = {}
    for p in sample(path, cum, SAMPLE_EVERY_M):
        min_lat, max_lat, min_lon, max_lon = bounding_box(p[0], p[1], width)
        for s in session.scalars(select(Station).where(Station.lat.between(min_lat, max_lat), Station.lon.between(min_lon, max_lon))):
            found[s.stat_id] = s

    # 2~3. 투영하고 거르기
    now = utcnow()
    by_bucket: dict[int, RouteStationOut] = {}
    for s in found.values():
        on = project(path, cum, (s.lat, s.lon))
        if on.detour_m > width:
            continue
        st = station_status(session, s.stat_id, now)
        usable = [c for c in st.chargers if c.public_state != "OUT_OF_SERVICE" and c.verdict != "RED"]
        if not usable or st.verdict == "RED":
            continue
        if fast_only and not any(c.charger_type == "FAST" for c in usable):
            continue
        along_km = round(on.along_m * factor / 1000, 1)
        reachable = range_km is None or along_km <= range_km * RESERVE
        arrival = round(soc * (1 - along_km / range_km)) if soc is not None and range_km else None
        cand = RouteStationOut(
            station=station_out(s), status=st, along_km=along_km, detour_m=int(on.detour_m),
            reachable=reachable, arrival_soc=arrival,
        )
        bucket = int(along_km // BUCKET_KM)
        best = by_bucket.get(bucket)
        # 같은 구간이면 갈 수 있는 곳 → 판정 색 → 정상 충전기 수 → 덜 돌아가는 곳 순
        if best is None or _rank(cand) < _rank(best):
            by_bucket[bucket] = cand

    candidates = [by_bucket[b] for b in sorted(by_bucket)]
    needs_charge = range_km is not None and total_m / 1000 > range_km * RESERVE
    recommended = None
    if needs_charge:
        # 갈 수 있는 곳 중 초록 우선, 빈 충전기가 있는 곳 우선, 그다음 가장 멀리(한 번 충전으로 최대한 많이 가기)
        ok = [c for c in candidates if c.reachable]
        if ok:
            recommended = min(ok, key=lambda c: (c.status.verdict != "GREEN", _no_free(c), -c.along_km)).station.station_id

    return AlongRouteOut(
        route_km=round(total_m / 1000, 1),
        source="CORRIDOR",
        range_km=range_km,
        needs_charge=needs_charge,
        route=[start, end],
        candidates=candidates,
        recommended_id=recommended,
    )
