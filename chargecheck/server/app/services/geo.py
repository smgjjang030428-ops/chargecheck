from math import asin, cos, radians, sin, sqrt

EARTH_RADIUS_M = 6_371_000.0


def distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """두 좌표 사이 거리(m). 하버사인 공식. 앱의 GeoPoint.distanceMetersTo와 같다."""
    d_lat = radians(lat2 - lat1)
    d_lon = radians(lon2 - lon1)
    a = sin(d_lat / 2) ** 2 + cos(radians(lat1)) * cos(radians(lat2)) * sin(d_lon / 2) ** 2
    return 2 * EARTH_RADIUS_M * asin(sqrt(a))


def bounding_box(lat: float, lon: float, radius_m: float) -> tuple[float, float, float, float]:
    """DB에서 먼저 거르기 위한 사각형(min_lat, max_lat, min_lon, max_lon)."""
    d_lat = radius_m / 111_000.0
    d_lon = radius_m / (111_000.0 * max(cos(radians(lat)), 0.01))
    return lat - d_lat, lat + d_lat, lon - d_lon, lon + d_lon
