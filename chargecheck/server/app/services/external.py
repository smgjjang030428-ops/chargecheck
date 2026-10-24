"""외부 지도 서비스(개발·시연용 무료 서버).

- 장소 검색: OpenStreetMap Nominatim (이용 정책: 앱을 알 수 있는 User-Agent, 초당 1회 이하, 대량 사용 금지)
- 경로: OSRM 공개 데모 서버 (대량 사용 금지)

출시 전에는 이용 허락된 상용 API(카카오·네이버·티맵 등)로 바꾼다. 둘 다 실패해도 앱이 멈추지 않게 None을 돌려준다.
"""
import logging
import threading
import time
from functools import lru_cache

import httpx

log = logging.getLogger("external")
USER_AGENT = "ChargeCheck/0.1 (Hyundai app contest prototype; https://github.com/smgjjang030428-ops/chargecheck)"
NOMINATIM = "https://nominatim.openstreetmap.org/search"
OSRM = "https://router.project-osrm.org/route/v1/driving"

_lock = threading.Lock()
_last_call = 0.0


def _polite_wait() -> None:
    """Nominatim 정책: 초당 1회 이하."""
    global _last_call
    with _lock:
        wait = 1.0 - (time.monotonic() - _last_call)
        if wait > 0:
            time.sleep(wait)
        _last_call = time.monotonic()


@lru_cache(maxsize=512)
def search_places(query: str) -> tuple[dict, ...] | None:
    """장소 이름 → [{name, address, lat, lon}] (한국만). 같은 검색어는 캐시한다."""
    _polite_wait()
    try:
        r = httpx.get(
            NOMINATIM,
            params={"q": query, "format": "jsonv2", "countrycodes": "kr", "limit": 6, "accept-language": "ko"},
            headers={"User-Agent": USER_AGENT},
            timeout=8,
        )
        r.raise_for_status()
        return tuple(
            {"name": x.get("name") or x["display_name"].split(",")[0], "address": x.get("display_name", ""),
             "lat": float(x["lat"]), "lon": float(x["lon"])}
            for x in r.json()
        )
    except (httpx.HTTPError, ValueError, KeyError) as e:
        log.warning("장소 검색 실패: %s", type(e).__name__)
        return None


@lru_cache(maxsize=256)
def route(a_lat: float, a_lon: float, b_lat: float, b_lon: float) -> tuple[list[tuple[float, float]], float] | None:
    """도로 경로 꼭짓점 [(lat, lon)]과 거리(m). 실패하면 None(호출한 쪽이 직선으로 대신)."""
    try:
        r = httpx.get(
            f"{OSRM}/{a_lon},{a_lat};{b_lon},{b_lat}",
            params={"overview": "simplified", "geometries": "geojson"},
            headers={"User-Agent": USER_AGENT},
            timeout=10,
        )
        r.raise_for_status()
        best = r.json()["routes"][0]
        return [(lat, lon) for lon, lat in best["geometry"]["coordinates"]], float(best["distance"])
    except (httpx.HTTPError, ValueError, KeyError, IndexError) as e:
        log.warning("경로 계산 실패: %s", type(e).__name__)
        return None
