"""공공 API 동기화 테스트. 실제 서버 대신 httpx.MockTransport로 응답을 흉내 낸다."""
import httpx

from app.jobs import public_sync
from app.models import Charger, Station


def info_row(stat_id: str, chger_id: str, stat: str = "2", chger_type: str = "04", del_yn: str = "N") -> dict:
    return {"statNm": f"충전소 {stat_id}", "statId": stat_id, "chgerId": chger_id, "chgerType": chger_type, "addr": "서울",
            "lat": "37.5012", "lng": "127.0396", "busiNm": "운영사", "stat": stat, "statUpdDt": "20261024120000", "delYn": del_yn}


def page(items: list[dict], total: int) -> dict:
    return {"resultCode": "00", "totalCount": total, "items": {"item": items}}


def test_목록을_여러_페이지_끝까지_읽고_504는_다시_시도한다(seeded, monkeypatch):
    monkeypatch.setattr(public_sync, "PAGE_SIZE", 2)
    monkeypatch.setattr(public_sync.time, "sleep", lambda s: None)
    monkeypatch.setattr("app.config.settings.PUBLIC_ZCODES", "11")
    calls = []
    rows = [info_row("T1", "01"), info_row("T1", "02", chger_type="02"), info_row("T2", "01", stat="4")]

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(dict(request.url.params))
        if len(calls) == 1:
            return httpx.Response(504, json={"err": "SERVICETIMEOUT_ERROR"})
        p = int(request.url.params["pageNo"])
        return httpx.Response(200, json=page(rows[(p - 1) * 2: p * 2], len(rows)))

    with seeded() as s, httpx.Client(transport=httpx.MockTransport(handler)) as client:
        n_stations, n_chargers = public_sync.sync_info(s, client)
        assert (n_stations, n_chargers) == (2, 3)
        assert s.get(Station, "T1").charger_type == "FAST"
        assert s.get(Charger, ("T2", "01")).public_stat == "4"
    assert calls[0]["zcode"] == "11" and calls[0]["dataType"] == "JSON"
    assert len(calls) == 3  # 504 1번 + 페이지 2개


def test_상태_변경분은_아는_충전기에만_반영하고_옛_상태는_무시(seeded):
    with seeded() as s:
        changed = public_sync.apply_status(s, [
            {"statId": "ME000001", "chgerId": "01", "stat": "3", "statUpdDt": "20261024130000"},
            {"statId": "ME000001", "chgerId": "02", "stat": "2", "statUpdDt": "20261024110000"},  # fixture(12:00)보다 옛것
            {"statId": "UNKNOWN", "chgerId": "01", "stat": "2", "statUpdDt": "20261024130000"},
        ])
        assert changed == 1
        assert s.get(Charger, ("ME000001", "01")).public_stat == "3"
        assert s.get(Charger, ("ME000001", "02")).public_stat == "3"  # 그대로


def test_삭제된_충전기는_지운다(seeded):
    with seeded() as s:
        public_sync.upsert_charger_info(s, [info_row("ME000001", "04", del_yn="Y")])
        assert s.get(Charger, ("ME000001", "04")) is None


def test_자리표시_좌표는_건너뛴다(seeded):
    bad = info_row("BAD1", "01") | {"lat": "33.0", "lng": "125.0"}
    good = info_row("OK1", "01") | {"lat": "37.4979", "lng": "127.0276"}
    with seeded() as s:
        n_stations, _ = public_sync.upsert_charger_info(s, [bad, good])
        assert n_stations == 1
        assert s.get(Station, "BAD1") is None and s.get(Station, "OK1") is not None


def test_KST_시각을_UTC로_바꾼다():
    t = public_sync.parse_kst("20261024154523")
    assert t.isoformat() == "2026-10-24T06:45:23+00:00"
    assert public_sync.parse_kst("") is None
