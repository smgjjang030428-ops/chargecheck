"""DB에서 충전소 하나의 이벤트·충전기를 읽어 compute_verdict로 판정한다."""
from collections import Counter
from datetime import datetime, timedelta, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import BUS_ONLY_TYPES, CONNECTOR_MAP, LOOKBACK_H
from app.models import ChargeEvent, Charger, Review, Station
from app.schemas import ChargerStatusOut, StationOut, StationStatusOut
from app.services.verdict import ChargerIn, EventIn, StationVerdict, compute_verdict


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def as_utc(t: datetime) -> datetime:
    """SQLite는 시간대를 저장하지 않으므로 읽을 때 UTC로 붙인다."""
    return t if t.tzinfo else t.replace(tzinfo=timezone.utc)


def station_out(s: Station) -> StationOut:
    return StationOut(
        station_id=s.stat_id,
        name=s.name,
        lat=s.lat,
        lon=s.lon,
        charger_type=s.charger_type,
        address=s.address,
        operator=s.operator,
        operator_call=s.operator_call,
        use_time=s.use_time,
        parking_free={"Y": True, "N": False}.get(s.parking_free),
        limit_detail=s.limit_detail,
    )


def _opt_utc(t: datetime | None) -> datetime | None:
    return as_utc(t) if t is not None else None


def station_verdict(session: Session, stat_id: str, now: datetime) -> tuple[list[Charger], list[ChargeEvent], StationVerdict]:
    """충전소 하나의 충전기(버스전용 제외)·최근 기록을 읽어 판정한다. 상태 API와 운영사 알림이 같이 쓴다."""
    chargers = [
        c for c in session.scalars(select(Charger).where(Charger.stat_id == stat_id).order_by(Charger.chger_id)).all()
        if c.chger_type not in BUS_ONLY_TYPES
    ]
    events = session.scalars(
        select(ChargeEvent).where(
            ChargeEvent.stat_id == stat_id,
            ChargeEvent.occurred_at >= now - timedelta(hours=LOOKBACK_H),
        )
    ).all()
    v = compute_verdict(
        [ChargerIn(c.chger_id, c.chger_type, c.public_stat, _opt_utc(c.last_charging_at), _opt_utc(c.repaired_at)) for c in chargers],
        [EventIn(e.device_hash, e.result, as_utc(e.occurred_at), e.chger_id, e.reason) for e in events],
        now,
    )
    return chargers, list(events), v


def station_status(session: Session, stat_id: str, now: datetime | None = None) -> StationStatusOut:
    now = now or utcnow()
    chargers, _, v = station_verdict(session, stat_id, now)
    rows = {c.chger_id: c for c in chargers}
    reviews = session.scalars(select(Review.tags).where(Review.stat_id == stat_id, Review.created_at >= now - timedelta(days=30))).all()
    top_tags = [t for t, _ in Counter(tag for tags in reviews for tag in tags).most_common(3)]
    return StationStatusOut(
        station_id=stat_id,
        verdict=v.verdict,
        reason_code=v.reason_code,
        message=v.message,
        last_success_at=v.last_success_at,
        recent_fail_count=v.recent_fail_count,
        public_available=v.public_available if chargers else None,
        public_total=v.public_total if chargers else None,
        ok_count=v.ok_count,
        chargers=[
            ChargerStatusOut(
                charger_id=c.charger_id,
                charger_type=c.charger_type,
                connectors=CONNECTOR_MAP.get(rows[c.charger_id].chger_type, []),
                output_kw=rows[c.charger_id].output_kw,
                public_state=c.public_state,
                verdict=c.verdict,
                recent_fail_count=c.recent_fail_count,
                last_success_at=c.last_success_at,
            )
            for c in v.chargers
        ],
        top_tags=top_tags,
        notice=v.notice,
    )
