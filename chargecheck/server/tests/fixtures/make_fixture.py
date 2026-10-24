"""getChargerInfo 응답과 같은 모양의 시연·테스트용 데이터를 만든다(이름·ID는 가상).

실제 응답 필드(2026-10-24 직접 호출로 확인): statNm, statId, chgerId, chgerType, addr, lat, lng, useTime,
busiId, busiNm, stat, statUpdDt, lastTsdt, lastTedt, nowTsdt, powerType, output, method, zcode, zscode, kind,
kindDetail, parkingFree, note, limitYn, limitDetail, delYn, delDetail, trafficYn, year, floorNum, floorType, maker

    python tests/fixtures/make_fixture.py   → tests/fixtures/charger_info_gangnam.json
"""
import json
from pathlib import Path

# (statId, 이름, 주소, lat, lng, [(chgerId, chgerType, stat)])
STATIONS = [
    ("ME000001", "강남 급속충전소", "서울특별시 강남구 강남대로 396", 37.4979, 127.0276, [("01", "04", "2"), ("02", "04", "3"), ("03", "04", "2"), ("04", "07", "2")]),
    ("ME000003", "역삼 급속충전소", "서울특별시 강남구 테헤란로 152", 37.5006, 127.0364, [("01", "04", "2"), ("02", "04", "2"), ("03", "04", "2"), ("04", "04", "3"), ("05", "02", "2"), ("06", "02", "2")]),
    ("ME000004", "서초 급속충전소", "서울특별시 서초구 서초대로 301", 37.4919, 127.0076, [("01", "04", "2"), ("02", "04", "2")]),
    ("ME000005", "선릉 급속충전소", "서울특별시 강남구 선릉로 525", 37.5045, 127.0490, [("01", "04", "2"), ("02", "04", "2"), ("03", "04", "3")]),
    ("ME000006", "교대 완속충전소", "서울특별시 서초구 서초중앙로 117", 37.4934, 127.0145, [("01", "02", "2"), ("02", "02", "2"), ("03", "02", "4")]),
    ("ME000007", "신논현 급속충전소", "서울특별시 강남구 봉은사로 102", 37.5046, 127.0250, [("01", "04", "2"), ("02", "04", "2"), ("03", "04", "2")]),
    ("ME000008", "양재 급속충전소", "서울특별시 서초구 남부순환로 2621", 37.4847, 127.0342, [("01", "04", "2"), ("02", "04", "2"), ("03", "04", "2"), ("04", "04", "2")]),
]


def rows():
    for stat_id, name, addr, lat, lng, chargers in STATIONS:
        for chger_id, chger_type, stat in chargers:
            yield {
                "statNm": name, "statId": stat_id, "chgerId": chger_id, "chgerType": chger_type,
                "addr": addr, "addrDetail": "", "location": "", "lat": str(lat), "lng": str(lng),
                "useTime": "평일 09:00~18:00" if stat_id == "ME000006" else "24시간 이용가능", "busiId": "TS", "bnm": "시연용", "busiNm": "시연용 운영사", "busiCall": "1600-0000",
                "stat": stat, "statUpdDt": "20261024120000", "lastTsdt": "", "lastTedt": "", "nowTsdt": "",
                "powerType": "", "output": {"02": "7", "07": "22"}.get(chger_type, "100"), "method": "단독",
                "zcode": "11", "zscode": "11680", "kind": "", "kindDetail": "", "parkingFree": "Y" if stat_id in ("ME000001", "ME000008") else "N", "note": "",
                "limitYn": "Y" if stat_id == "ME000006" else "N", "limitDetail": "방문객 2시간 무료 주차" if stat_id == "ME000006" else "", "delYn": "N", "delDetail": "", "trafficYn": "N",
                "year": "2024", "floorNum": "0", "floorType": "F", "maker": "",
            }


if __name__ == "__main__":
    items = list(rows())
    payload = {"resultCode": "00", "resultMsg": "NORMAL SERVICE.", "totalCount": len(items), "pageNo": 1, "numOfRows": len(items), "items": {"item": items}}
    out = Path(__file__).with_name("charger_info_gangnam.json")
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"{out.name}: 충전소 {len(STATIONS)}곳, 충전기 {len(items)}대")
