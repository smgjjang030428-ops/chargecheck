"""ChargeCheck 서버 (명세 §5). 실행: uvicorn app.main:app --reload --host 0.0.0.0 --port 8000"""
import asyncio
import logging
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI
from sqlalchemy import func, select

from app.config import settings
from app.db import Base, SessionLocal, engine
from app.jobs.public_sync import sync_info, sync_status
from app.models import Station
from app.routers import events, places, reviews, stations

log = logging.getLogger("chargecheck")


def _sync_once(full: bool) -> None:
    with SessionLocal() as session, httpx.Client() as client:
        if full:
            log.info("공공 목록 동기화: 충전소 %d곳, 충전기 %d대", *sync_info(session, client))
        log.info("공공 상태 동기화: %d대 변경", sync_status(session, client))


async def _sync_loop() -> None:
    """키가 있으면 시작할 때 목록이 비어 있을 때만 전체 동기화, 이후 STATUS_SYNC_MINUTES마다 상태 변경분."""
    with SessionLocal() as session:
        empty = session.scalar(select(func.count()).select_from(Station)) == 0
    full = empty
    while True:
        try:
            await asyncio.to_thread(_sync_once, full)
            full = False
        except Exception:  # 동기화 실패는 서버를 멈추지 않는다. 마지막 값을 유지한다.
            log.exception("공공 데이터 동기화 실패")
        await asyncio.sleep(settings.STATUS_SYNC_MINUTES * 60)


@asynccontextmanager
async def lifespan(_: FastAPI):
    Base.metadata.create_all(engine)
    task = None
    if settings.SYNC_ENABLED and settings.PUBLIC_API_KEY:
        task = asyncio.create_task(_sync_loop())
    else:
        log.info("공공 데이터 동기화 꺼짐(PUBLIC_API_KEY 없음 또는 SYNC_ENABLED=false)")
    yield
    if task:
        task.cancel()


app = FastAPI(title="ChargeCheck", version="0.1.0", lifespan=lifespan)
app.include_router(events.router)
app.include_router(stations.router)
app.include_router(reviews.router)
app.include_router(places.router)


@app.get("/healthz")
def healthz():
    return {"ok": True}
