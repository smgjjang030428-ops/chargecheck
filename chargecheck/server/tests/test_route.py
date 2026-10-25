"""경로 근처 충전소·충전소 검색 테스트. 외부 지도·경로 서버는 쓰지 않는다."""
from app.services.route import cumulative, project, sample, straight

# fixture 충전소: 서초(37.4919,127.0076) · 강남(37.4979,127.0276) · 역삼(37.5006,127.0364) · 선릉(37.5045,127.0490)
WEST = (37.4930, 127.0000)
EAST = (37.5050, 127.0560)


def test_투영하면_경로를_따라간_거리와_벗어난_거리가_나온다():
    route = [(37.0, 127.0), (37.0, 127.01)]  # 동쪽으로 약 890m
    cum = cumulative(route)
    on = project(route, cum, (37.001, 127.005))  # 가운데에서 북쪽 약 111m
    assert 430 < on.along_m < 460 and 100 < on.detour_m < 120


def test_경로를_일정_간격으로_찍는다():
    route = straight((37.0, 127.0), (37.0, 127.1), steps=4)
    pts = sample(route, cumulative(route), 2000)
    assert len(pts) >= 5 and pts[0] == route[0] and pts[-1] == route[-1]


def test_가는_길의_충전소를_거리순으로_주고_빨강은_뺀다(client):
    # 서초(2대)를 빨강으로 만든다
    for d in ("device-aaaa-0001", "device-bbbb-0002"):
        client.post("/v1/events", json={"eventId": f"evt-{d}", "stationId": "ME000004", "result": "FAIL", "mode": "LIVE",
                                         "socStart": 40, "socEnd": 40, "minutes": 10, "occurredAt": "2026-10-25T00:00:00Z"},
                    headers={"X-Device-Id": d})
    r = client.get("/v1/stations/along-route", params={"fromLat": WEST[0], "fromLon": WEST[1], "toLat": EAST[0], "toLon": EAST[1]}).json()
    assert r["source"] == "CORRIDOR" and 6 < r["routeKm"] < 9   # 직선 약 5.1km × 1.3
    ids = [c["station"]["stationId"] for c in r["candidates"]]
    assert "ME000004" not in ids                      # 빨강 제외
    assert "ME000006" not in ids                      # 완속만 있는 교대는 급속 모드에서 제외
    assert [c["alongKm"] for c in r["candidates"]] == sorted(c["alongKm"] for c in r["candidates"])
    assert r["needsCharge"] is False and r["recommendedId"] is None


def test_남은_거리가_모자라면_갈_수_있는_곳_중에서_추천한다(client):
    r = client.get("/v1/stations/along-route", params={
        "fromLat": WEST[0], "fromLon": WEST[1], "toLat": EAST[0], "toLon": EAST[1], "rangeKm": 4, "soc": 20,
    }).json()
    assert r["needsCharge"] is True
    reachable = [c for c in r["candidates"] if c["reachable"]]
    assert reachable and all(c["alongKm"] <= 4 * 0.85 for c in reachable)
    assert r["recommendedId"] in {c["station"]["stationId"] for c in reachable}
    assert all(c["arrivalSoc"] is not None and c["arrivalSoc"] <= 20 for c in r["candidates"])


def test_충전소_지역_검색은_우리_DB에서_가까운_순(client):
    r = client.get("/v1/places/search", params={"q": "강남", "lat": 37.4979, "lon": 127.0276}).json()
    assert r and all(x["kind"] == "STATION" for x in r)
    assert r[0]["stationId"] == "ME000001"  # 이름에 '강남' + 가장 가까움


def test_여러_낱말은_모두_들어_있어야_한다(client):
    assert client.get("/v1/places/search", params={"q": "강남 없는낱말"}).json() == []


def test_내비가_알려준_도로_거리를_쓰면_환산이_바뀐다(client):
    p = {"fromLat": WEST[0], "fromLon": WEST[1], "toLat": EAST[0], "toLon": EAST[1], "rangeKm": 9, "soc": 30}
    plain = client.get("/v1/stations/along-route", params=p).json()
    road = client.get("/v1/stations/along-route", params={**p, "roadKm": 9.5}).json()
    assert plain["needsCharge"] is False and road["needsCharge"] is True   # 9.5 > 9 × 0.85
    assert road["routeKm"] == 9.5
