"""요청·응답 JSON (명세 §5.2). 필드 이름은 앱과 같은 camelCase."""
from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


Verdict = Literal["GREEN", "YELLOW", "RED", "GRAY"]
ReviewTag = Literal["CABLE_SHORT", "SPACE_NARROW", "HAS_ROOF", "SLOW_SPEED", "PAYMENT_ISSUE", "BLOCKED_BY_CAR"]


class ChargeEventIn(CamelModel):
    event_id: str = Field(min_length=8, max_length=64)
    station_id: str
    result: Literal["SUCCESS", "FAIL"]
    mode: Literal["LIVE", "RETRO", "MANUAL"]  # MANUAL = 상세 카드에서 운전자가 직접 표시
    soc_start: int = Field(ge=0, le=100)
    soc_end: int = Field(ge=0, le=100)
    minutes: int = Field(ge=0)
    occurred_at: datetime
    charger_id: str | None = None


class AttachChargerIn(CamelModel):
    charger_id: str = Field(min_length=1, max_length=8)


class AcceptedOut(CamelModel):
    accepted: bool
    reason: str | None = None


class ChargerStatusOut(CamelModel):
    charger_id: str
    charger_type: Literal["FAST", "SLOW"]
    connectors: list[str] = []         # 커넥터 방식(예: ["DC콤보"])
    output_kw: int | None = None       # 충전 용량
    public_state: Literal["AVAILABLE", "CHARGING", "OUT_OF_SERVICE", "UNKNOWN"]
    verdict: Verdict
    recent_fail_count: int
    last_success_at: datetime | None


class StationStatusOut(CamelModel):
    station_id: str
    verdict: Verdict
    reason_code: str
    message: str
    last_success_at: datetime | None
    recent_fail_count: int
    public_available: int | None
    public_total: int | None
    ok_count: int
    chargers: list[ChargerStatusOut]
    top_tags: list[ReviewTag]


class StationOut(CamelModel):
    station_id: str
    name: str
    lat: float
    lon: float
    charger_type: Literal["FAST", "SLOW", "UNKNOWN"]
    address: str = ""
    operator: str = ""
    operator_call: str = ""
    use_time: str = ""
    parking_free: bool | None = None
    limit_detail: str = ""
    # 결제 수단은 공공 API에 없다. 운영사 정보로 안내하고, 확인된 정보가 생기면 여기에 채운다.
    payment: str | None = None


class NearbyStationOut(StationOut):
    distance_m: int
    status: StationStatusOut


class AlternativeOut(CamelModel):
    station: StationOut
    status: StationStatusOut
    distance_m: int


class PlaceOut(CamelModel):
    name: str
    address: str = ""
    lat: float
    lon: float
    kind: Literal["PLACE", "STATION"]
    station_id: str | None = None


class RouteStationOut(CamelModel):
    station: StationOut
    status: StationStatusOut
    along_km: float          # 출발점에서 경로를 따라간 거리
    detour_m: int            # 경로에서 벗어난 거리
    reachable: bool          # 남은 주행 가능 거리 안(15% 여유)
    arrival_soc: int | None  # 도착 예상 배터리(%)


class AlongRouteOut(CamelModel):
    route_km: float
    source: Literal["ROAD", "STRAIGHT"]  # 도로 경로 / 경로 서버를 못 써서 직선 근사
    range_km: float | None
    needs_charge: bool                   # 남은 거리로 목적지까지 못 감(15% 여유 기준)
    route: list[tuple[float, float]]     # 지도에 그릴 경로 [(lat, lon)]
    candidates: list[RouteStationOut]    # 경로 위 충전소(5km 구간마다 가장 좋은 곳)
    recommended_id: str | None           # 추천 충전 지점


class ReviewIn(CamelModel):
    station_id: str
    tags: list[ReviewTag] = Field(min_length=1, max_length=6)
