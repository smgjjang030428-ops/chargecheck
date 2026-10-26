"""테이블 (기획 §11). PostgreSQL·SQLite 둘 다 되게 배열 대신 JSON을 쓴다."""
from datetime import datetime

from sqlalchemy import JSON, DateTime, Float, ForeignKey, Index, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base


class Station(Base):
    __tablename__ = "station"
    stat_id: Mapped[str] = mapped_column(String, primary_key=True)
    name: Mapped[str] = mapped_column(String)
    address: Mapped[str] = mapped_column(String, default="")
    lat: Mapped[float] = mapped_column(Float)
    lon: Mapped[float] = mapped_column(Float)
    operator: Mapped[str] = mapped_column(String, default="")
    operator_call: Mapped[str] = mapped_column(String, default="")   # 운영기관 연락처
    use_time: Mapped[str] = mapped_column(String, default="")        # 이용 가능 시간
    parking_free: Mapped[str] = mapped_column(String, default="")    # Y / N / ""
    limit_detail: Mapped[str] = mapped_column(String, default="")    # 이용자 제한(예: 입주민 전용)
    charger_type: Mapped[str] = mapped_column(String, default="UNKNOWN")  # FAST / SLOW
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))

    __table_args__ = (Index("ix_station_lat_lon", "lat", "lon"),)


class Charger(Base):
    __tablename__ = "charger"
    stat_id: Mapped[str] = mapped_column(String, ForeignKey("station.stat_id"), primary_key=True)
    chger_id: Mapped[str] = mapped_column(String, primary_key=True)
    chger_type: Mapped[str] = mapped_column(String, default="")      # 공공 API 코드 그대로
    output_kw: Mapped[int | None] = mapped_column(Integer, nullable=True)  # 충전 용량(kW)
    public_stat: Mapped[str] = mapped_column(String, default="9")    # 공공 API stat 코드 그대로
    stat_upd_dt: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    # 상태가 바뀐 순간(동기화 때 기록). 빨강을 자동으로 푸는 신호로 쓴다(기획 §6).
    last_charging_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)  # → 충전 중
    repaired_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)       # 점검·운영 중지 → 사용 가능


class ChargeEvent(Base):
    __tablename__ = "charge_event"
    event_id: Mapped[str] = mapped_column(String, primary_key=True)
    device_hash: Mapped[str] = mapped_column(String, index=True)
    stat_id: Mapped[str] = mapped_column(String, index=True)
    chger_id: Mapped[str | None] = mapped_column(String, nullable=True)
    result: Mapped[str] = mapped_column(String)   # SUCCESS / FAIL
    mode: Mapped[str] = mapped_column(String)     # LIVE / RETRO / MANUAL
    # 실패 이유(운전자가 말하거나 고른 것). NOT_CHARGING / BROKEN / PAYMENT / BLOCKED / OTHER, 모르면 None
    reason: Mapped[str | None] = mapped_column(String, nullable=True)
    soc_start: Mapped[int] = mapped_column(Integer)
    soc_end: Mapped[int] = mapped_column(Integer)
    minutes: Mapped[int] = mapped_column(Integer)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    received_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))

    __table_args__ = (Index("ix_event_stat_time", "stat_id", "occurred_at"),)


class FaultAlert(Base):
    """운영사 고장 알림 한 건(기획 §6 고장 신고 루프). 충전기 하나(번호 모르면 "")마다 열린 알림은 하나.

    OPEN(실패 제보 쌓임) → IN_REPAIR(운영사가 점검·운영 중지로 바꿈) → RESOLVED(충전 성공·충전 중·상태 복구·기간 만료).
    기기 정보는 담지 않는다(서로 다른 기기 수만).
    """
    __tablename__ = "fault_alert"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    stat_id: Mapped[str] = mapped_column(String, index=True)
    chger_id: Mapped[str] = mapped_column(String, default="")
    operator: Mapped[str] = mapped_column(String, default="", index=True)
    status: Mapped[str] = mapped_column(String, default="OPEN", index=True)  # OPEN / IN_REPAIR / RESOLVED
    opened_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    last_fail_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    fail_devices: Mapped[int] = mapped_column(Integer, default=0)
    reasons: Mapped[dict] = mapped_column(JSON, default=dict)  # 실패 이유별 건수 {"PAYMENT": 1, "UNKNOWN": 2}
    acked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    in_repair_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    resolved_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    # USER_SUCCESS / PUBLIC_CHARGING / OPERATOR_STATUS / EXPIRED
    resolution: Mapped[str | None] = mapped_column(String, nullable=True)


class Review(Base):
    __tablename__ = "review"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    device_hash: Mapped[str] = mapped_column(String)
    stat_id: Mapped[str] = mapped_column(String, index=True)
    tags: Mapped[list] = mapped_column(JSON)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
