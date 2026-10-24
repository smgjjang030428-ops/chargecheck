from fastapi import APIRouter, Depends, Response
from sqlalchemy.orm import Session

from app.db import get_session
from app.deps import device_hash
from app.models import Review
from app.schemas import AcceptedOut, ReviewIn
from app.services.status import utcnow

router = APIRouter(prefix="/v1/reviews", tags=["reviews"])


@router.post("", response_model=AcceptedOut, response_model_exclude_none=True, status_code=202)
def post_review(body: ReviewIn, response: Response, device: str = Depends(device_hash), session: Session = Depends(get_session)):
    session.add(Review(device_hash=device, stat_id=body.station_id, tags=list(body.tags), created_at=utcnow()))
    session.commit()
    return AcceptedOut(accepted=True)
