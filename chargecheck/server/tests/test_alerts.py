"""운영사 고장 알림 테스트 (기획 §6 고장 신고 루프): 실패 → 알림 → 점검 → 상태 복구·충전 중·충전 성공 → 자동 해제."""
from datetime import datetime, timedelta, timezone

from app.jobs import public_sync
from app.services import alerts
from tests.test_api import event, headers

KST = timezone(timedelta(hours=9))
STATION = "ME000004"  # 충전기 2대(01, 02)


def kst_now() -> str:
    return datetime.now(KST).strftime("%Y%m%d%H%M%S")


def two_fails(client, charger: str | None = "01"):
    client.post("/v1/events", json=event("FAIL", 20, STATION, charger), headers=headers("device-aaaa-0001"))
    client.post("/v1/events", json=event("FAIL", 10, STATION, charger), headers=headers("device-bbbb-0002"))


def set_public(seeded, stat: str, charger: str = "01"):
    with seeded() as s:
        public_sync.apply_status(s, [{"statId": STATION, "chgerId": charger, "stat": stat, "statUpdDt": kst_now()}])
        alerts.refresh_open(s)


def test_두_차가_같은_충전기에서_실패하면_운영사_알림이_열린다(client):
    two_fails(client)
    [a] = client.get("/v1/operator/alerts").json()
    assert (a["stationId"], a["chargerId"], a["status"], a["failDevices"]) == (STATION, "01", "OPEN", 2)
    assert a["reasons"] == {"UNKNOWN": 2}
    assert a["stationName"]
    assert "device" not in str(a)  # 기기 정보는 내보내지 않는다


def test_누가_충전에_성공하면_알림이_풀린다(client):
    two_fails(client)
    client.post("/v1/events", json=event("SUCCESS", 1, STATION, "01"), headers=headers("device-cccc-0003"))
    assert client.get("/v1/operator/alerts").json() == []
    [a] = client.get("/v1/operator/alerts?status=resolved").json()
    assert (a["status"], a["resolution"]) == ("RESOLVED", "USER_SUCCESS")
    assert a["minutesToResolve"] >= 0


def test_공공_데이터가_충전_중으로_바뀌면_알림이_풀리고_초록(client, seeded):
    two_fails(client)
    set_public(seeded, "3")
    [a] = client.get("/v1/operator/alerts?status=resolved").json()
    assert a["resolution"] == "PUBLIC_CHARGING"
    st = client.get(f"/v1/stations/{STATION}/status").json()
    assert st["verdict"] == "GREEN"


def test_운영사가_점검으로_바꾸면_점검_중_다시_사용가능이면_해결(client, seeded):
    two_fails(client)
    set_public(seeded, "5")
    [a] = client.get("/v1/operator/alerts").json()
    assert a["status"] == "IN_REPAIR" and a["inRepairAt"]
    set_public(seeded, "2")
    [a] = client.get("/v1/operator/alerts?status=resolved").json()
    assert a["resolution"] == "OPERATOR_STATUS"
    st = client.get(f"/v1/stations/{STATION}/status").json()
    assert (st["verdict"], st["reasonCode"]) == ("YELLOW", "REPAIRED_UNCONFIRMED")


def test_번호_모르는_실패가_쌓이면_충전소_단위_알림(client):
    two_fails(client, charger=None)
    [a] = client.get("/v1/operator/alerts").json()
    assert a["chargerId"] is None


def test_확인함_CSV_요약(client):
    two_fails(client)
    [a] = client.get("/v1/operator/alerts").json()
    acked = client.post(f"/v1/operator/alerts/{a['alertId']}/ack").json()
    assert acked["ackedAt"]
    csv = client.get("/v1/operator/alerts.csv")
    assert csv.text.startswith("﻿알림ID")
    assert STATION in csv.text
    [s] = client.get("/v1/operator/summary").json()
    assert (s["open"], s["inRepair"]) == (1, 0)
    assert client.get("/operator").status_code == 200


def test_새_알림은_운영사_웹훅으로_보낸다(client, monkeypatch):
    sent = []
    monkeypatch.setattr("app.config.settings.OPERATOR_WEBHOOK_URL", "https://example.test/hook")
    monkeypatch.setattr("app.services.alerts.httpx.post", lambda url, **kw: sent.append((url, kw["json"])))
    two_fails(client)
    [(url, body)] = sent
    assert url == "https://example.test/hook"
    assert (body["stationId"], body["chargerId"], body["failDevices"]) == (STATION, "01", 2)


def test_운영사_키를_설정하면_키_없이는_리포트를_볼_수_없다(client, monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "OPERATOR_KEY", "op-key-for-test")
    assert client.get("/v1/operator/alerts").status_code == 401
    assert client.get("/v1/operator/alerts", headers={"X-Operator-Key": "wrong"}).status_code == 401
    assert client.get("/v1/operator/alerts", headers={"X-Operator-Key": "op-key-for-test"}).status_code == 200
    assert client.get("/v1/operator/alerts.csv?key=op-key-for-test").status_code == 200
    assert client.get("/operator").status_code == 200  # 화면 자체는 열리고, 데이터는 키가 있어야 받는다
    assert client.get("/healthz").status_code == 200   # 차량 앱이 쓰는 API는 키와 무관


def test_호스팅이_주는_postgres_주소를_psycopg_주소로_바꾼다():
    from app.config import Settings
    assert Settings(DATABASE_URL="postgres://u:p@h/db").database_url == "postgresql+psycopg://u:p@h/db"
    assert Settings(DATABASE_URL="postgresql://u:p@h/db").database_url == "postgresql+psycopg://u:p@h/db"
    assert Settings(DATABASE_URL="sqlite:///./x.db").database_url == "sqlite:///./x.db"
