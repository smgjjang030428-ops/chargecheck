"""테이블 (명세 §5.3). PostgreSQL·SQLite 둘 다 되게 배열 대신 JSON을 쓴다."""
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


class ChargeEvent(Base):
    __tablename__ = "charge_event"
    event_id: Mapped[str] = mapped_column(String, primary_key=True)
    device_hash: Mapped[str] = mapped_column(String, index=True)
    stat_id: Mapped[str] = mapped_column(String, index=True)
    chger_id: Mapped[str | None] = mapped_column(String, nullable=True)
    result: Mapped[str] = mapped_column(String)   # SUCCESS / FAIL
    mode: Mapped[str] = mapped_column(String)     # LIVE / RETRO
    soc_start: Mapped[int] = mapped_column(Integer)
    soc_end: Mapped[int] = mapped_column(Integer)
    minutes: Mapped[int] = mapped_column(Integer)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    received_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))

    __table_args__ = (Index("ix_event_stat_time", "stat_id", "occurred_at"),)


class Review(Base):
    __tablename__ = "review"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    device_hash: Mapped[str] = mapped_column(String)
    stat_id: Mapped[str] = mapped_column(String, index=True)
    tags: Mapped[list] = mapped_column(JSON)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
