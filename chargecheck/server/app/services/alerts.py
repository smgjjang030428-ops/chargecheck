"""운영사 고장 알림(기획 §6 고장 신고 루프).

실패 제보 쌓임 → 알림 열림(운영사에 알림) → 운영사가 점검으로 바꿈(IN_REPAIR) → 수리 → 공공 데이터 상태 복구·충전 중·충전 성공 → 자동 해제.
판정(compute_verdict)을 그대로 쓰므로, 앱에서 빨강이 풀리는 순간 알림도 풀린다.
"""
import logging
from collections import Counter
from datetime import datetime, timedelta

import httpx
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import LOOKBACK_H, settings
from app.models import FaultAlert, Station
from app.services.status import as_utc, station_verdict, utcnow

log = logging.getLogger("chargecheck.alerts")

# 우리 사용자들의 실패 제보로 고장이 의심되는 충전기(운영사가 아직 모를 수 있는 것)
SUSPECT = {"MULTI_FAIL", "PUBLIC_SAYS_OK_BUT_FAILS", "OLD_FAILS"}
# 충전기 번호 없는 실패가 이만큼(서로 다른 기기) 쌓이면 충전소 단위로 알린다
UNKNOWN_CHARGER_ALERT = 2


def refresh_station(session: Session, stat_id: str, now: datetime | None = None) -> list[FaultAlert]:
    """충전소 하나의 알림을 판정에 맞춘다. 새로 열린 알림을 돌려준다(웹훅으로 보낼 것)."""
    now = now or utcnow()
    _, events, v = station_verdict(session, stat_id, now)
    station = session.get(Station, stat_id)
    open_alerts = {a.chger_id: a for a in session.scalars(
        select(FaultAlert).where(FaultAlert.stat_id == stat_id, FaultAlert.status != "RESOLVED")
    )}
    judged = {c.charger_id: c for c in v.chargers}

    # 지금 문제가 있는 충전기: 키 = 충전기 번호, 번호 모르는 실패는 ""
    faulty: dict[str, tuple[int, datetime | None]] = {
        c.charger_id: (c.recent_fail_count, c.last_fail_at) for c in v.chargers if c.reason_code in SUSPECT
    }
    if v.unknown_fail_count >= UNKNOWN_CHARGER_ALERT:
        faulty[""] = (v.unknown_fail_count, v.unknown_last_fail_at)

    opened: list[FaultAlert] = []
    for key, (devices, last_fail) in faulty.items():
        a = open_alerts.get(key)
        if a is None:
            a = FaultAlert(stat_id=stat_id, chger_id=key, operator=station.operator if station else "", status="OPEN", opened_at=now)
            session.add(a)
            opened.append(a)
        a.fail_devices = devices
        a.last_fail_at = last_fail
        a.reasons = _reasons(events, key, now)

    for key, a in open_alerts.items():
        if key in faulty:
            continue
        c = judged.get(key)
        if c is not None and c.public_state == "OUT_OF_SERVICE":
            # 운영사가 점검·운영 중지로 바꿈 = 알림을 받고 고치는 중
            if a.status != "IN_REPAIR":
                a.status, a.in_repair_at = "IN_REPAIR", now
            continue
        a.status, a.resolved_at = "RESOLVED", now
        a.resolution = _resolution(a, c.cleared_by if c else None, c.last_success_at if c else v.last_success_at)
    session.commit()
    for a in opened:
        _notify(a, station)
    return opened


def refresh_open(session: Session, now: datetime | None = None) -> int:
    """열려 있는 알림이 있는 충전소를 모두 다시 본다(공공 상태 변경·기간 만료 반영). 주기적으로 부른다."""
    stat_ids = set(session.scalars(select(FaultAlert.stat_id).where(FaultAlert.status != "RESOLVED")))
    for stat_id in stat_ids:
        refresh_station(session, stat_id, now)
    return len(stat_ids)


def _resolution(a: FaultAlert, cleared_by: str | None, last_success: datetime | None) -> str:
    """무엇이 알림을 풀었나. 마지막 실패보다 뒤의 신호여야 그 신호 덕분이다. 아니면 기간이 지나 사라진 것."""
    if cleared_by == "OPERATOR_STATUS":
        return cleared_by
    after_fail = last_success is not None and (a.last_fail_at is None or last_success > as_utc(a.last_fail_at))
    if after_fail:
        return cleared_by or "USER_SUCCESS"
    return "EXPIRED"


def _reasons(events, key: str, now: datetime) -> dict:
    since = now - timedelta(hours=LOOKBACK_H)
    return dict(Counter(
        e.reason or "UNKNOWN" for e in events
        if e.result == "FAIL" and e.reason != "BLOCKED" and (e.chger_id or "") == key and as_utc(e.occurred_at) >= since
    ))


def _notify(a: FaultAlert, station: Station | None) -> None:
    """설정된 운영사 웹훅으로 새 알림을 보낸다. 실패해도 판정·기록에는 영향이 없다."""
    if not settings.OPERATOR_WEBHOOK_URL:
        return
    try:
        httpx.post(settings.OPERATOR_WEBHOOK_URL, timeout=3, json={
            "alertId": a.id,
            "stationId": a.stat_id,
            "stationName": station.name if station else "",
            "address": station.address if station else "",
            "operator": a.operator,
            "chargerId": a.chger_id or None,
            "failDevices": a.fail_devices,
            "reasons": a.reasons,
            "lastFailAt": a.last_fail_at.isoformat() if a.last_fail_at else None,
        })
    except httpx.HTTPError:
        log.warning("운영사 웹훅 전송 실패: 알림 %s", a.id)
