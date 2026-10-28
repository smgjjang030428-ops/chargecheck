"""충전소 판정 compute_verdict (기획 §6). DB·시계에 의존하지 않는 순수 함수.

충전기 단위로 먼저 판정하고, 충전소는 그 결과를 모아서 정한다.
핵심 원칙: 충전기 한 대 고장으로 충전소 전체를 빨강으로 만들지 않는다.

빨강이 풀리는 신호(우리 사용자가 다시 시도하지 않아도 풀리게, 기획 §6):
- 누군가 충전에 성공(우리 기록)
- 공공 데이터에서 그 충전기가 '충전 중'으로 바뀜(우리 앱을 안 쓰는 차가 실제로 충전) → 성공과 같게 본다
- 운영사 상태가 '점검·운영 중지 → 사용 가능'으로 바뀜(수리됐을 수 있음) → 그 전 실패는 세지 않고 노랑으로 안내
- 시간: 마지막 실패가 OLD_FAIL_H시간 넘게 지나면 빨강 대신 노랑, LOOKBACK_H시간이면 사라짐
"""
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timedelta

from app.config import BLOCKED_NOTICE_MIN, GREEN_WINDOW_H, LOOKBACK_H, MAX_FAILS_PER_DEVICE_PER_DAY, OLD_FAIL_H, PUBLIC_STAT_MAP, SLOW_CHARGER_TYPES


@dataclass(frozen=True)
class EventIn:
    device_hash: str
    result: str  # SUCCESS / FAIL
    occurred_at: datetime
    charger_id: str | None = None
    reason: str | None = None   # FAIL 이유. BLOCKED는 충전기 실패로 세지 않는다


@dataclass(frozen=True)
class ChargerIn:
    charger_id: str
    chger_type: str   # 공공 API 코드
    public_stat: str  # 공공 API stat 코드
    last_charging_at: datetime | None = None  # 공공 데이터에서 '충전 중'으로 바뀐 마지막 시각
    repaired_at: datetime | None = None       # 공공 데이터에서 '점검·운영 중지 → 사용 가능'으로 바뀐 마지막 시각


@dataclass
class ChargerVerdict:
    charger_id: str
    charger_type: str  # FAST / SLOW
    public_state: str  # AVAILABLE / CHARGING / OUT_OF_SERVICE / UNKNOWN
    verdict: str
    reason_code: str
    recent_fail_count: int
    last_success_at: datetime | None
    last_fail_at: datetime | None = None
    # 실패를 지운 마지막 신호: USER_SUCCESS / PUBLIC_CHARGING / OPERATOR_STATUS. 없으면 None
    cleared_by: str | None = None


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
    notice: str | None = None
    unknown_fail_count: int = 0       # 충전기 번호 없는 실패(서로 다른 기기 수)
    unknown_last_fail_at: datetime | None = None


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


def _last_fail_after(events: list[EventIn], after: datetime | None) -> datetime | None:
    return max((e.occurred_at for e in events if e.result == "FAIL" and (after is None or e.occurred_at > after)), default=None)


def _in_window(t: datetime | None, now: datetime) -> datetime | None:
    """공공 데이터 신호는 판정 기간(LOOKBACK_H) 안의 것만 쓴다."""
    return t if t is not None and now - timedelta(hours=LOOKBACK_H) <= t <= now else None


def _latest(*times: datetime | None) -> datetime | None:
    return max((t for t in times if t is not None), default=None)


def _judge_charger(c: ChargerIn, own: list[EventIn], station_success: datetime | None, now: datetime) -> ChargerVerdict:
    state = public_state(c.public_stat)
    # 실패를 지우는 건 이 충전기에서 확인된 신호뿐이다. 번호 없는 성공(station_success)은 어느 충전기였는지 모르므로
    # 이 충전기의 실패를 지우지 않고, 남은 실패가 없을 때만 '최근 충전됨'으로 보여 준다.
    own_success = _last_success(own)
    public_charging = _in_window(c.last_charging_at, now)
    repaired = _in_window(c.repaired_at, now)
    reset = _latest(own_success, public_charging, repaired)
    fails = _distinct_fail_devices_after(own, reset)
    last_fail = _last_fail_after(own, reset)
    user_success = own_success if fails else _latest(own_success, station_success)
    last_success = _latest(user_success, public_charging)
    # 무엇이 실패를 지웠나(운영사 리포트의 '해결 이유')
    cleared_by = None
    if reset is not None:
        if reset == repaired:
            cleared_by = "OPERATOR_STATUS"
        elif reset == public_charging and reset != own_success:
            cleared_by = "PUBLIC_CHARGING"
        else:
            cleared_by = "USER_SUCCESS"

    if state == "OUT_OF_SERVICE":
        verdict, reason = "RED", "PUBLIC_OUT_OF_SERVICE"
    elif fails >= 2 and now - last_fail > timedelta(hours=OLD_FAIL_H):
        verdict, reason = "YELLOW", "OLD_FAILS"
    elif fails >= 2:
        verdict, reason = "RED", ("PUBLIC_SAYS_OK_BUT_FAILS" if state == "AVAILABLE" else "MULTI_FAIL")
    elif fails == 1:
        verdict, reason = "YELLOW", "FAIL_AFTER_SUCCESS"
    elif cleared_by == "OPERATOR_STATUS" and _distinct_fail_devices_after(own, last_success) > 0:
        verdict, reason = "YELLOW", "REPAIRED_UNCONFIRMED"
    elif last_success is not None and now - last_success <= timedelta(hours=GREEN_WINDOW_H):
        public = last_success == public_charging and last_success != user_success
        verdict, reason = "GREEN", ("PUBLIC_CHARGING" if public else "RECENT_SUCCESS")
    else:
        verdict, reason = "GRAY", "NO_RECENT_DATA"
    return ChargerVerdict(c.charger_id, charger_kind(c.chger_type), state, verdict, reason, fails, last_success, last_fail, cleared_by)


def _blocked_notice(events: list[EventIn], now: datetime) -> str | None:
    """일반차 주차 막힘은 충전기 고장이 아니라 잠깐의 상황이다. 최근 BLOCKED_NOTICE_MIN분 안 제보만 안내한다."""
    last = max((e.occurred_at for e in events if e.reason == "BLOCKED" and now - e.occurred_at <= timedelta(minutes=BLOCKED_NOTICE_MIN)), default=None)
    if last is None:
        return None
    return f"{max(1, int((now - last).total_seconds() // 60))}분 전 일반차 주차 막힘 제보"


def compute_verdict(chargers: list[ChargerIn], events: list[EventIn], now: datetime) -> StationVerdict:
    v = _compute(chargers, [e for e in events if e.reason != "BLOCKED"], now)
    v.notice = _blocked_notice(events, now)
    return v


def _compute(chargers: list[ChargerIn], events: list[EventIn], now: datetime) -> StationVerdict:
    events = _usable(events, now)
    by_charger: dict[str, list[EventIn]] = defaultdict(list)
    unassigned: list[EventIn] = []
    for e in events:
        (by_charger[e.charger_id] if e.charger_id else unassigned).append(e)

    # 번호 없는 SUCCESS는 "이 충전소 어딘가에서 충전됨"이다. 실패 기록이 없는 충전기의 최근 성공으로만 친다.
    unassigned_success = _last_success(unassigned)
    judged = [_judge_charger(c, by_charger.get(c.charger_id, []), unassigned_success, now) for c in chargers]

    total = len(chargers)
    states = [j.public_state for j in judged]
    public_available = states.count("AVAILABLE")
    bad = sum(1 for j in judged if j.verdict == "RED")
    yellow = sum(1 for j in judged if j.verdict == "YELLOW")
    ok_count = sum(1 for j in judged if j.verdict != "RED" and j.public_state != "OUT_OF_SERVICE")

    # 공공 데이터의 '충전 중'도 충전소 어딘가에서 충전됐다는 뜻이다
    user_station_success = _last_success(events)
    public_station_success = _latest(*(_in_window(c.last_charging_at, now) for c in chargers))
    station_success = _latest(user_station_success, public_station_success)
    unknown_fails = _distinct_fail_devices_after(unassigned, station_success)
    unknown_last = _last_fail_after(unassigned, station_success)
    # 번호 없는 실패도 오래되면(OLD_FAIL_H) 빨강까지는 만들지 않는다
    unknown_recent = unknown_fails if unknown_last is not None and now - unknown_last <= timedelta(hours=OLD_FAIL_H) else 0
    recent_fail_count = _distinct_fail_devices_after(events, station_success)
    # 번호 없는 실패도 어딘가의 충전기 하나로 보수적으로 뺀다
    ok_count = max(0, ok_count - unknown_fails)

    # 최근 실패가 결제 문제였으면 따로 알려 준다(운영사 회원카드·앱 문제일 수 있어 고장과 구분)
    payment = any(e.result == "FAIL" and e.reason == "PAYMENT" and (station_success is None or e.occurred_at > station_success) for e in events)

    def result(verdict: str, reason: str, message: str) -> StationVerdict:
        if payment and verdict in ("RED", "YELLOW"):
            message += " · 결제 실패 제보 포함"
        return StationVerdict(
            verdict, reason, message, station_success, recent_fail_count, public_available, total, ok_count, judged,
            unknown_fail_count=unknown_fails, unknown_last_fail_at=unknown_last,
        )

    if total > 0 and all(s == "OUT_OF_SERVICE" for s in states):
        return result("RED", "PUBLIC_OUT_OF_SERVICE", "운영사 점검·운영 중지")
    if bad + unknown_recent >= max(total, 2):
        if public_available > 0:
            return result("RED", "PUBLIC_SAYS_OK_BUT_FAILS", "사용 가능으로 표시되지만 최근 실제로는 충전이 안 됐어요")
        return result("RED", "MULTI_FAIL", "최근 여러 차가 충전에 실패했어요")
    if bad > 0 or unknown_fails > 0 or yellow > 0:
        # 오래된 실패·수리 신호만 남았으면 그 사실을 그대로 말한다
        soft = {j.reason_code for j in judged if j.verdict == "YELLOW"}
        if unknown_fails and not unknown_recent:
            soft.add("OLD_FAILS")
        if bad == 0 and not unknown_recent and soft <= {"OLD_FAILS", "REPAIRED_UNCONFIRMED"}:
            if soft == {"REPAIRED_UNCONFIRMED"}:
                return result("YELLOW", "REPAIRED_UNCONFIRMED", "운영사 상태가 '사용 가능'으로 바뀌었어요(수리됐을 수 있음, 확인 기록 없음)")
            last = _latest(unknown_last, *(j.last_fail_at for j in judged if j.reason_code == "OLD_FAILS"))
            hours = int((now - last).total_seconds() // 3600)
            return result("YELLOW", "OLD_FAILS", f"{hours}시간 전 실패 제보 · 이후 확인 기록 없음")
        if total >= 2:
            k = min(total, bad + yellow + unknown_fails)
            return result("YELLOW", "SOME_CHARGERS_FAIL", f"{total}대 중 {k}대에 최근 실패 기록")
        return result("YELLOW", "FAIL_AFTER_SUCCESS", "최근 충전 실패 제보 1건")
    if station_success is not None and now - station_success <= timedelta(hours=GREEN_WINDOW_H):
        minutes = int((now - station_success).total_seconds() // 60)
        if station_success != user_station_success:
            return result("GREEN", "PUBLIC_CHARGING", f"{minutes}분 전 다른 차가 충전함(운영사 데이터)")
        return result("GREEN", "RECENT_SUCCESS", f"{minutes}분 전 실제로 충전됨")
    return result("GRAY", "NO_RECENT_DATA", f"운영사 기준 사용 가능 {public_available}/{total} (실제 확인 기록 없음)")
