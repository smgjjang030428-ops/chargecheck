"""운영사 고장 리포트(기획 §6 고장 신고 루프). 기기 정보는 내보내지 않는다(서로 다른 기기 수만).

- GET  /v1/operator/alerts       알림 목록(운영사·상태로 거르기)
- GET  /v1/operator/alerts.csv   같은 목록을 CSV로
- POST /v1/operator/alerts/{id}/ack  운영사가 확인함
- GET  /v1/operator/summary      운영사별 열린 알림 수·해결까지 걸린 시간
- GET  /operator                 운영사용 웹 화면

OPERATOR_KEY를 설정하면 /v1/operator/* 는 X-Operator-Key 헤더(또는 ?key=)가 맞아야 한다.
웹 화면은 /operator?key=… 로 열면 그 키를 붙여 부른다.
"""
import csv
import io
import secrets
from collections import defaultdict
from datetime import timedelta
from pathlib import Path
from statistics import median

from fastapi import APIRouter, Depends, Header, HTTPException, Query
from fastapi.responses import HTMLResponse, Response
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import settings
from app.db import get_session
from app.models import Charger, FaultAlert, Station
from app.schemas import AlertOut, OperatorSummaryOut
from app.services.status import as_utc, utcnow
from app.services.verdict import public_state



def operator_key(
    x_operator_key: str | None = Header(None, alias="X-Operator-Key"),
    key: str | None = Query(None),
) -> None:
    expected = settings.OPERATOR_KEY
    if expected and not secrets.compare_digest((x_operator_key or key or "").encode(), expected.encode()):
        raise HTTPException(401, "운영사 접근 키가 필요합니다")


router = APIRouter(tags=["operator"])
api = APIRouter(tags=["operator"], dependencies=[Depends(operator_key)])

PAGE = Path(__file__).resolve().parent.parent / "static" / "operator.html"


def _query(session: Session, operator: str | None, status: str, limit: int) -> list[AlertOut]:
    q = select(FaultAlert, Station).join(Station, Station.stat_id == FaultAlert.stat_id, isouter=True)
    if operator:
        q = q.where(FaultAlert.operator == operator)
    if status == "open":
        q = q.where(FaultAlert.status != "RESOLVED")
    elif status == "resolved":
        q = q.where(FaultAlert.status == "RESOLVED")
    rows = session.execute(q.order_by(FaultAlert.opened_at.desc()).limit(limit)).all()
    out = []
    for a, s in rows:
        charger = session.get(Charger, (a.stat_id, a.chger_id)) if a.chger_id else None
        minutes = None
        if a.resolved_at is not None:
            minutes = int((as_utc(a.resolved_at) - as_utc(a.opened_at)).total_seconds() // 60)
        out.append(AlertOut(
            alert_id=a.id,
            station_id=a.stat_id,
            station_name=s.name if s else "",
            address=s.address if s else "",
            operator=a.operator,
            charger_id=a.chger_id or None,
            public_state=public_state(charger.public_stat) if charger else None,
            status=a.status,
            opened_at=as_utc(a.opened_at),
            last_fail_at=as_utc(a.last_fail_at) if a.last_fail_at else None,
            fail_devices=a.fail_devices,
            reasons=a.reasons or {},
            acked_at=as_utc(a.acked_at) if a.acked_at else None,
            in_repair_at=as_utc(a.in_repair_at) if a.in_repair_at else None,
            resolved_at=as_utc(a.resolved_at) if a.resolved_at else None,
            resolution=a.resolution,
            minutes_to_resolve=minutes,
        ))
    return out


@api.get("/v1/operator/alerts", response_model=list[AlertOut])
def list_alerts(
    operator: str | None = None,
    status: str = Query("open", pattern="^(open|resolved|all)$"),
    limit: int = Query(200, ge=1, le=1000),
    session: Session = Depends(get_session),
):
    return _query(session, operator, status, limit)


@api.get("/v1/operator/alerts.csv")
def alerts_csv(
    operator: str | None = None,
    status: str = Query("all", pattern="^(open|resolved|all)$"),
    session: Session = Depends(get_session),
):
    buf = io.StringIO()
    w = csv.writer(buf)
    w.writerow(["알림ID", "충전소ID", "충전소", "주소", "운영사", "충전기", "상태", "열린 시각", "마지막 실패", "실패 기기 수", "실패 이유", "확인", "점검 시작", "해결", "해결 이유", "해결까지(분)"])
    for a in _query(session, operator, status, 1000):
        w.writerow([
            a.alert_id, a.station_id, a.station_name, a.address, a.operator, a.charger_id or "모름", a.status,
            a.opened_at.isoformat(), a.last_fail_at.isoformat() if a.last_fail_at else "", a.fail_devices,
            " ".join(f"{k}:{v}" for k, v in a.reasons.items()), a.acked_at.isoformat() if a.acked_at else "",
            a.in_repair_at.isoformat() if a.in_repair_at else "", a.resolved_at.isoformat() if a.resolved_at else "",
            a.resolution or "", a.minutes_to_resolve if a.minutes_to_resolve is not None else "",
        ])
    # 엑셀에서 한글이 깨지지 않게 BOM을 붙인다
    return Response("﻿" + buf.getvalue(), media_type="text/csv; charset=utf-8",
                    headers={"Content-Disposition": "attachment; filename=chargecheck_alerts.csv"})


@api.post("/v1/operator/alerts/{alert_id}/ack", response_model=AlertOut)
def ack_alert(alert_id: int, session: Session = Depends(get_session)):
    a = session.get(FaultAlert, alert_id)
    if a is None:
        raise HTTPException(404, "알림 없음")
    if a.acked_at is None:
        a.acked_at = utcnow()
        session.commit()
    return next(x for x in _query(session, None, "all", 1000) if x.alert_id == alert_id)


@api.get("/v1/operator/summary", response_model=list[OperatorSummaryOut])
def summary(days: int = Query(7, ge=1, le=90), session: Session = Depends(get_session)):
    since = utcnow() - timedelta(days=days)
    stats: dict[str, dict] = defaultdict(lambda: {"open": 0, "in_repair": 0, "resolved": 0, "minutes": []})
    for a in session.scalars(select(FaultAlert)):
        s = stats[a.operator or "(운영사 모름)"]
        if a.status == "OPEN":
            s["open"] += 1
        elif a.status == "IN_REPAIR":
            s["in_repair"] += 1
        elif a.resolved_at is not None and as_utc(a.resolved_at) >= since:
            s["resolved"] += 1
            if a.resolution != "EXPIRED":
                s["minutes"].append((as_utc(a.resolved_at) - as_utc(a.opened_at)).total_seconds() / 60)
    return sorted(
        (OperatorSummaryOut(
            operator=op, open=s["open"], in_repair=s["in_repair"], resolved=s["resolved"],
            median_minutes_to_resolve=int(median(s["minutes"])) if s["minutes"] else None,
        ) for op, s in stats.items()),
        key=lambda x: (-(x.open + x.in_repair), x.operator),
    )


@router.get("/operator", response_class=HTMLResponse, include_in_schema=False)
def operator_page():
    return PAGE.read_text(encoding="utf-8")
