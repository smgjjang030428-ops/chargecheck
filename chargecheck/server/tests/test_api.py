"""REST API 테스트 (명세 §5.2). 앱이 보내는 JSON 그대로 사용한다."""
import uuid
from datetime import datetime, timedelta, timezone


def headers(device: str = "device-aaaa-0001") -> dict:
    return {"X-Device-Id": device, "X-App-Version": "0.1.0"}


def event(result: str, minutes_ago: int = 5, station: str = "ME000003", charger: str | None = None) -> dict:
    t = datetime.now(timezone.utc) - timedelta(minutes=minutes_ago)
    body = {
        "eventId": str(uuid.uuid4()),
        "stationId": station,
        "result": result,
        "mode": "LIVE",
        "socStart": 42,
        "socEnd": 43 if result == "SUCCESS" else 42,
        "minutes": 12,
        "occurredAt": t.isoformat().replace("+00:00", "Z"),
    }
    if charger:
        body["chargerId"] = charger
    return body


def test_healthz(client):
    assert client.get("/healthz").json() == {"ok": True}


def test_기록_올리면_202_같은_eventId는_DUPLICATE(client):
    body = event("SUCCESS")
    assert client.post("/v1/events", json=body, headers=headers()).status_code == 202
    r = client.post("/v1/events", json=body, headers=headers())
    assert r.status_code == 200 and r.json() == {"accepted": False, "reason": "DUPLICATE"}


def test_같은_기기_같은_충전소는_2시간에_1건(client):
    client.post("/v1/events", json=event("FAIL", 50), headers=headers())
    r = client.post("/v1/events", json=event("FAIL", 10), headers=headers())
    assert r.json()["reason"] == "DUPLICATE"


def test_기기_ID가_없으면_거절(client):
    assert client.post("/v1/events", json=event("SUCCESS")).status_code == 422


def test_상태에_충전기별_판정과_대수가_나온다(client):
    st = client.get("/v1/stations/ME000003/status").json()
    assert st["verdict"] == "GRAY" and st["publicTotal"] == 6 and st["okCount"] == 6
    assert [c["chargerId"] for c in st["chargers"]] == ["01", "02", "03", "04", "05", "06"]
    assert {c["chargerType"] for c in st["chargers"]} == {"FAST", "SLOW"}
    c1, c5 = st["chargers"][0], st["chargers"][4]
    assert (c1["connectors"], c1["outputKw"], c1["publicState"]) == (["DC콤보"], 100, "AVAILABLE")
    assert (c5["connectors"], c5["outputKw"]) == (["AC완속"], 7)


def test_충전소_정보에_운영사_연락처_이용시간_주차가_나온다(client):
    r = client.get("/v1/stations/nearby", params={"lat": 37.4934, "lon": 127.0145, "radius": 100}).json()
    s = r[0]
    assert s["stationId"] == "ME000006"
    assert (s["operator"], s["operatorCall"], s["useTime"]) == ("시연용 운영사", "1600-0000", "평일 09:00~18:00")
    assert s["parkingFree"] is False and s["limitDetail"] == "방문객 2시간 무료 주차"
    assert s["payment"] is None  # 공공 데이터에 결제 수단 없음


def test_실패_뒤_충전기_번호를_붙이면_그_충전기만_실패로(client):
    # 두 대의 차가 각각 실패를 올리고, 나중에 둘 다 "02번"이라고 답함
    for device in ("device-aaaa-0001", "device-bbbb-0002"):
        body = event("FAIL", station="ME000003")
        client.post("/v1/events", json=body, headers=headers(device))
        assert client.patch(f"/v1/events/{body['eventId']}", json={"chargerId": "02"}, headers=headers(device)).status_code == 200
    st = client.get("/v1/stations/ME000003/status").json()
    assert (st["verdict"], st["reasonCode"]) == ("YELLOW", "SOME_CHARGERS_FAIL")
    assert st["message"] == "6대 중 1대에 최근 실패 기록"
    c2 = next(c for c in st["chargers"] if c["chargerId"] == "02")
    assert c2["verdict"] == "RED"


def test_다른_기기는_충전기_번호를_못_바꾼다(client):
    body = event("FAIL")
    client.post("/v1/events", json=body, headers=headers("device-aaaa-0001"))
    r = client.patch(f"/v1/events/{body['eventId']}", json={"chargerId": "01"}, headers=headers("device-zzzz-9999"))
    assert r.status_code == 404


def test_충전기_2대짜리는_두_차가_실패하면_빨강(client):
    client.post("/v1/events", json=event("FAIL", station="ME000004"), headers=headers("device-aaaa-0001"))
    client.post("/v1/events", json=event("FAIL", station="ME000004"), headers=headers("device-bbbb-0002"))
    st = client.get("/v1/stations/ME000004/status").json()
    assert (st["verdict"], st["reasonCode"]) == ("RED", "PUBLIC_SAYS_OK_BUT_FAILS")


def test_주변_충전소는_거리순이고_상태를_같이_준다(client):
    r = client.get("/v1/stations/nearby", params={"lat": 37.4979, "lon": 127.0276, "radius": 1500}).json()
    assert r[0]["stationId"] == "ME000001" and r[0]["distanceM"] == 0
    assert all("status" in s and "verdict" in s["status"] for s in r)
    assert [s["distanceM"] for s in r] == sorted(s["distanceM"] for s in r)


def test_대체_충전소는_빨강을_빼고_초록_먼저(client):
    client.post("/v1/events", json=event("FAIL", station="ME000004"), headers=headers("device-aaaa-0001"))
    client.post("/v1/events", json=event("FAIL", station="ME000004"), headers=headers("device-bbbb-0002"))
    client.post("/v1/events", json=event("SUCCESS", station="ME000008"), headers=headers("device-cccc-0003"))
    alts = client.get("/v1/stations/ME000001/alternatives", params={"lat": 37.4979, "lon": 127.0276}).json()
    ids = [a["station"]["stationId"] for a in alts]
    assert ids[0] == "ME000008" and "ME000004" not in ids and len(ids) <= 3


def test_버스전용_충전기는_대수와_지도에서_빠진다(client, seeded):
    from app.jobs.public_sync import upsert_charger_info
    bus = {"statNm": "버스 차고지", "statId": "BUS001", "chgerId": "01", "chgerType": "11", "lat": "37.4985", "lng": "127.0280", "stat": "2", "statUpdDt": "20261024120000"}
    with seeded() as s:
        upsert_charger_info(s, [bus])
    ids = [x["stationId"] for x in client.get("/v1/stations/nearby", params={"lat": 37.4979, "lon": 127.0276, "radius": 500}).json()]
    assert "BUS001" not in ids and "ME000001" in ids


def test_없는_충전소는_404(client):
    assert client.get("/v1/stations/NOPE/status").status_code == 404


def test_리뷰_태그가_상태에_나온다(client):
    client.post("/v1/reviews", json={"stationId": "ME000001", "tags": ["CABLE_SHORT", "HAS_ROOF"]}, headers=headers())
    st = client.get("/v1/stations/ME000001/status").json()
    assert set(st["topTags"]) == {"CABLE_SHORT", "HAS_ROOF"}
