"""경로 위 충전소 계산(순수 함수). 경로는 [(lat, lon), …] 꼭짓점 목록."""
from dataclasses import dataclass
from math import cos, radians

from app.services.geo import distance_m

Point = tuple[float, float]


@dataclass(frozen=True)
class OnRoute:
    along_m: float   # 출발점에서 경로를 따라간 거리
    detour_m: float  # 경로에서 떨어진 거리(직선)


def cumulative(route: list[Point]) -> list[float]:
    """꼭짓점마다 출발점부터의 누적 거리(m)."""
    out = [0.0]
    for a, b in zip(route, route[1:]):
        out.append(out[-1] + distance_m(a[0], a[1], b[0], b[1]))
    return out


def project(route: list[Point], cum: list[float], p: Point) -> OnRoute:
    """점 p를 경로의 가장 가까운 선분에 투영한다(짧은 구간이라 평면 근사)."""
    best = OnRoute(float("inf"), float("inf"))
    k = cos(radians(p[0]))
    for i, (a, b) in enumerate(zip(route, route[1:])):
        ax, ay, bx, by = a[1] * k, a[0], b[1] * k, b[0]
        px, py = p[1] * k, p[0]
        dx, dy = bx - ax, by - ay
        seg2 = dx * dx + dy * dy
        t = 0.0 if seg2 == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / seg2))
        q = (ay + t * dy, (ax + t * dx) / k)
        d = distance_m(p[0], p[1], q[0], q[1])
        if d < best.detour_m:
            best = OnRoute(cum[i] + t * (cum[i + 1] - cum[i]), d)
    return best


def sample(route: list[Point], cum: list[float], every_m: float) -> list[Point]:
    """경로를 every_m 간격으로 찍은 점들(충전소 후보를 DB에서 고를 때 쓴다)."""
    if not route:
        return []
    pts = [route[0]]
    target = every_m
    for i, (a, b) in enumerate(zip(route, route[1:])):
        while target <= cum[i + 1]:
            seg = cum[i + 1] - cum[i]
            t = 0.0 if seg == 0 else (target - cum[i]) / seg
            pts.append((a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])))
            target += every_m
    pts.append(route[-1])
    return pts


def straight(a: Point, b: Point, steps: int = 20) -> list[Point]:
    """경로 서버를 못 쓸 때의 직선 경로(대략값)."""
    return [(a[0] + (b[0] - a[0]) * i / steps, a[1] + (b[1] - a[1]) * i / steps) for i in range(steps + 1)]
