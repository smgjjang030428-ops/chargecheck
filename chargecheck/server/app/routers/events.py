from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException, Response
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import DEDUP_WINDOW_H
from app.db import get_session
from app.deps import device_hash
from app.models import ChargeEvent
from app.schemas import AcceptedOut, AttachChargerIn, ChargeEventIn
from app.services.status import utcnow

router = APIRouter(prefix="/v1/events", tags=["events"])


@router.post("", response_model=AcceptedOut, response_model_exclude_none=True)
def post_event(body: ChargeEventIn, response: Response, device: str = Depends(device_hash), session: Session = Depends(get_session)):
    # 멱등성: 같은 eventId는 한 번만(앱이 재시도해도 안전)
    if session.get(ChargeEvent, body.event_id) is not None:
        return AcceptedOut(accepted=False, reason="DUPLICATE")
    # 같은 기기·충전소는 2시간에 1건만 인정(명세 §5.4 중복·악용 방지)
    window_start = body.occurred_at - timedelta(hours=DEDUP_WINDOW_H)
    dup = session.scalars(
        select(ChargeEvent.event_id).where(
            ChargeEvent.device_hash == device,
            ChargeEvent.stat_id == body.station_id,
            ChargeEvent.occurred_at > window_start,
            ChargeEvent.occurred_at <= body.occurred_at + timedelta(hours=DEDUP_WINDOW_H),
        )
    ).first()
    if dup is not None:
        return AcceptedOut(accepted=False, reason="DUPLICATE")
    session.add(
        ChargeEvent(
            event_id=body.event_id,
            device_hash=device,
            stat_id=body.station_id,
            chger_id=body.charger_id,
            result=body.result,
            mode=body.mode,
            reason=body.reason if body.result == "FAIL" else None,
            soc_start=body.soc_start,
            soc_end=body.soc_end,
            minutes=body.minutes,
            occurred_at=body.occurred_at,
            received_at=utcnow(),
        )
    )
    session.commit()
    response.status_code = 202
    return AcceptedOut(accepted=True)


@router.patch("/{event_id}", response_model=AcceptedOut, response_model_exclude_none=True)
def attach_charger(event_id: str, body: AttachChargerIn, device: str = Depends(device_hash), session: Session = Depends(get_session)):
    """실패 기록 뒤 운전자가 고른 충전기 번호·이유를 붙이거나 고친다. 기록을 올린 같은 기기만 가능."""
    event = session.get(ChargeEvent, event_id)
    if event is None or event.device_hash != device:
        raise HTTPException(404, "기록 없음")
    if body.charger_id is None and body.reason is None:
        raise HTTPException(422, "chargerId 또는 reason이 필요해요")
    if body.charger_id is not None:
        event.chger_id = body.charger_id
    if body.reason is not None and event.result == "FAIL":
        event.reason = body.reason
    session.commit()
    return AcceptedOut(accepted=True)
