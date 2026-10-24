import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.orm import sessionmaker

from app import db
from app.db import Base, make_engine
from app.jobs.public_sync import items_of, upsert_charger_info

FIXTURE = Path(__file__).parent / "fixtures" / "charger_info_gangnam.json"


@pytest.fixture()
def session_factory(tmp_path):
    engine = make_engine(f"sqlite:///{tmp_path / 'test.db'}")
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine, expire_on_commit=False)


@pytest.fixture()
def seeded(session_factory):
    with session_factory() as s:
        upsert_charger_info(s, items_of(json.loads(FIXTURE.read_text(encoding="utf-8"))))
    return session_factory


@pytest.fixture()
def client(seeded, monkeypatch):
    from app.main import app

    def override():
        with seeded() as s:
            yield s

    app.dependency_overrides[db.get_session] = override
    monkeypatch.setattr("app.config.settings.SYNC_ENABLED", False)
    with TestClient(app) as c:
        yield c
    app.dependency_overrides.clear()
