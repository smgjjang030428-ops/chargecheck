"""DB에서 충전소 하나의 이벤트·충전기를 읽어 compute_verdict로 판정한다."""
from collections import Counter
from datetime import datetime, timedelta, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import BUS_ONLY_TYPES, CONNECTOR_MAP, LOOKBACK_H
from app.models import ChargeEvent, Charger, Review, Station
from app.schemas import ChargerStatusOut, StationOut, StationStatusOut
from app.services.verdict import ChargerIn, EventIn, compute_verdict


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


def station_status(session: Session, stat_id: str, now: datetime | None = None) -> StationStatusOut:
    now = now or utcnow()
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
        [ChargerIn(c.chger_id, c.chger_type, c.public_stat) for c in chargers],
        [EventIn(e.device_hash, e.result, as_utc(e.occurred_at), e.chger_id) for e in events],
        now,
    )
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
    )
