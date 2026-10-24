"""충전소 판정 compute_verdict (명세 §5.4). DB·시계에 의존하지 않는 순수 함수.

충전기 단위로 먼저 판정하고, 충전소는 그 결과를 모아서 정한다.
핵심 원칙: 충전기 한 대 고장으로 충전소 전체를 빨강으로 만들지 않는다.
"""
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timedelta

from app.config import GREEN_WINDOW_H, LOOKBACK_H, MAX_FAILS_PER_DEVICE_PER_DAY, PUBLIC_STAT_MAP, SLOW_CHARGER_TYPES


@dataclass(frozen=True)
class EventIn:
    device_hash: str
    result: str  # SUCCESS / FAIL
    occurred_at: datetime
    charger_id: str | None = None


@dataclass(frozen=True)
class ChargerIn:
    charger_id: str
    chger_type: str   # 공공 API 코드
    public_stat: str  # 공공 API stat 코드


@dataclass
class ChargerVerdict:
    charger_id: str
    charger_type: str  # FAST / SLOW
    public_state: str  # AVAILABLE / CHARGING / OUT_OF_SERVICE / UNKNOWN
    verdict: str
    reason_code: str
    recent_fail_count: int
    last_success_at: datetime | None


@dataclass
class StationVerdict:
    verdict: str
    reason_code: str
    message: str
    last_success_at: datetime | None
    recent_fail_count: int
    public_available: int
    public_total: int
    ok_count: int
    chargers: list[ChargerVerdict] = field(default_factory=list)


def public_state(stat_code: str) -> str:
    return PUBLIC_STAT_MAP.get(stat_code, "UNKNOWN")


def charger_kind(chger_type: str) -> str:
    return "SLOW" if chger_type in SLOW_CHARGER_TYPES else "FAST"


def _usable(events: list[EventIn], now: datetime) -> list[EventIn]:
    """최근 24시간만, 하루 FAIL이 너무 많은 기기의 FAIL은 뺀다(악용 방지)."""
    recent = [e for e in events if now - timedelta(hours=LOOKBACK_H) <= e.occurred_at <= now]
    fails_per_device = Counter(e.device_hash for e in recent if e.result == "FAIL")
    noisy = {d for d, n in fails_per_device.items() if n > MAX_FAILS_PER_DEVICE_PER_DAY}
    return sorted((e for e in recent if not (e.result == "FAIL" and e.device_hash in noisy)), key=lambda e: e.occurred_at)


def _last_success(events: list[EventIn]) -> datetime | None:
    return max((e.occurred_at for e in events if e.result == "SUCCESS"), default=None)


def _distinct_fail_devices_after(events: list[EventIn], after: datetime | None) -> int:
    return len({e.device_hash for e in events if e.result == "FAIL" and (after is None or e.occurred_at > after)})


def _judge_charger(c: ChargerIn, own: list[EventIn], station_success: datetime | None, now: datetime) -> ChargerVerdict:
    state = public_state(c.public_stat)
    own_success = _last_success(own)
    last_success = max((t for t in (own_success, station_success) if t is not None), default=None)
    fails = _distinct_fail_devices_after(own, last_success)

    if state == "OUT_OF_SERVICE":
        verdict, reason = "RED", "PUBLIC_OUT_OF_SERVICE"
    elif fails >= 2:
        verdict, reason = "RED", ("PUBLIC_SAYS_OK_BUT_FAILS" if state == "AVAILABLE" else "MULTI_FAIL")
    elif fails == 1:
        verdict, reason = "YELLOW", "FAIL_AFTER_SUCCESS"
    elif last_success is not None and now - last_success <= timedelta(hours=GREEN_WINDOW_H):
        verdict, reason = "GREEN", "RECENT_SUCCESS"
    else:
        verdict, reason = "GRAY", "NO_RECENT_DATA"
    return ChargerVerdict(c.charger_id, charger_kind(c.chger_type), state, verdict, reason, fails, last_success)


def compute_verdict(chargers: list[ChargerIn], events: list[EventIn], now: datetime) -> StationVerdict:
    events = _usable(events, now)
    by_charger: dict[str, list[EventIn]] = defaultdict(list)
    unassigned: list[EventIn] = []
    for e in events:
        (by_charger[e.charger_id] if e.charger_id else unassigned).append(e)

    # 번호 없는 SUCCESS는 "이 충전소 어딘가에서 충전됨"이라 모든 충전기의 최근 성공으로 친다.
    unassigned_success = _last_success(unassigned)
    judged = [_judge_charger(c, by_charger.get(c.charger_id, []), unassigned_success, now) for c in chargers]

    total = len(chargers)
    states = [j.public_state for j in judged]
    public_available = states.count("AVAILABLE")
    bad = sum(1 for j in judged if j.verdict == "RED")
    yellow = sum(1 for j in judged if j.verdict == "YELLOW")
    ok_count = sum(1 for j in judged if j.verdict != "RED" and j.public_state != "OUT_OF_SERVICE")

    station_success = _last_success(events)
    unknown_fails = _distinct_fail_devices_after(unassigned, station_success)
    recent_fail_count = _distinct_fail_devices_after(events, station_success)
    # 번호 없는 실패도 어딘가의 충전기 하나로 보수적으로 뺀다
    ok_count = max(0, ok_count - unknown_fails)

    def result(verdict: str, reason: str, message: str) -> StationVerdict:
        return StationVerdict(verdict, reason, message, station_success, recent_fail_count, public_available, total, ok_count, judged)

    if total > 0 and all(s == "OUT_OF_SERVICE" for s in states):
        return result("RED", "PUBLIC_OUT_OF_SERVICE", "운영사 점검·운영 중지")
    if bad + unknown_fails >= max(total, 2):
        if public_available > 0:
            return result("RED", "PUBLIC_SAYS_OK_BUT_FAILS", "사용 가능으로 표시되지만 최근 실제로는 충전이 안 됐어요")
        return result("RED", "MULTI_FAIL", "최근 여러 차가 충전에 실패했어요")
    if bad > 0 or unknown_fails > 0 or yellow > 0:
        if total >= 2:
            k = min(total, bad + yellow + unknown_fails)
            return result("YELLOW", "SOME_CHARGERS_FAIL", f"{total}대 중 {k}대에 최근 실패 기록")
        return result("YELLOW", "FAIL_AFTER_SUCCESS", "최근 충전 실패 제보 1건")
    if station_success is not None and now - station_success <= timedelta(hours=GREEN_WINDOW_H):
        minutes = int((now - station_success).total_seconds() // 60)
        return result("GREEN", "RECENT_SUCCESS", f"{minutes}분 전 실제로 충전됨")
    return result("GRAY", "NO_RECENT_DATA", f"운영사 기준 사용 가능 {public_available}/{total} (실제 확인 기록 없음)")
