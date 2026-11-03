"""2차 PT·시연 영상용 로컬 서버에 시연 기록을 넣는다. 배포 서버에는 쓰지 않는다(주소가 로컬이 아니면 멈춤).

그날 데이터와 상관없이 같은 장면이 나오게 한다(각본: 시연영상_촬영자료_2026-11-03/03_촬영_대본/04_2차PT_에뮬레이터_시연각본.md).
  - 경유지 교체(차 위치 판교): 경기성남 SW드림타운 1·2·3을 빨강으로.
    충전기마다 서로 다른 차 2대의 실패 기록 → 모든 충전기를 덮어 빨강.
    세 곳은 좌표가 같아, 내비 충전소 목록의 "SW드림타운 3 전기차충전소"가 어느 것과 짝지어져도 빨강이 되게 셋 다 넣는다.
    대안: 경기기업성장센터(200m 옆)에 35분 전 충전 성공.
  - 목적지 교체(강남): 아이온빌딩은 DB 복사본의 운영사 상태가 '점검·운영 중지'라 이미 빨강(동기화를 꺼서 고정).
    대안: 아이온커뮤니케이션즈(같은 건물)에 35분 전 충전 성공.

    # 실데이터 DB 복사본으로 로컬 서버를 켠 뒤(공공 동기화는 끔)
    copy chargecheck_real.db chargecheck_pt.db
    set DATABASE_URL=sqlite:///./chargecheck_pt.db& set SYNC_ENABLED=false& .venv\\Scripts\\python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
    .venv\\Scripts\\python -m app.jobs.pt_demo_seed

실패는 6시간이 지나면 노랑으로 내려가므로 시연 직전에 다시 실행한다(실행마다 다른 기기 ID를 쓴다).
"""
import argparse
import json
import sys
import time
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from urllib.parse import urlparse

SW = (37.412980949, 127.09438949)
AION = (37.49819, 127.03266)
RED = {"EV012096": "경기성남 SW드림타운 1", "EV012097": "경기성남 SW드림타운 2", "EV018054": "경기성남 SW드림타운 3"}
GREEN = {"EASENGC2": "경기기업성장센터", "OS300001": "아이온커뮤니케이션즈"}
# 시연에서 빨강이어야 하는 곳과 대안을 물을 위치
TARGETS = {"EV018054": ("경기성남 SW드림타운 3", SW), "OS300141": ("아이온빌딩", AION)}
LOCAL_HOSTS = {"127.0.0.1", "localhost", "10.0.2.2"}


def call(base: str, method: str, path: str, device: str | None = None, body: dict | None = None):
    req = urllib.request.Request(base + path, method=method, data=json.dumps(body).encode() if body else None)
    req.add_header("Content-Type", "application/json")
    if device:
        req.add_header("X-Device-Id", device)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read() or b"null")


def event(station: str, result: str, minutes_ago: int, charger: str | None, soc: tuple[int, int]) -> dict:
    return {
        "eventId": uuid.uuid4().hex,
        "stationId": station,
        "result": result,
        "mode": "LIVE",
        "socStart": soc[0],
        "socEnd": soc[1],
        "minutes": 12,
        "occurredAt": (datetime.now(timezone.utc) - timedelta(minutes=minutes_ago)).isoformat(),
        "chargerId": charger,
        **({"reason": "NOT_CHARGING"} if result == "FAIL" else {}),
    }


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--base", default="http://127.0.0.1:8000")
    args = p.parse_args()
    if urlparse(args.base).hostname not in LOCAL_HOSTS:
        sys.exit("로컬 서버에만 시연 기록을 넣는다: " + args.base)
    run = time.strftime("%m%d%H%M%S")

    for sid, name in RED.items():
        chargers = [c["chargerId"] for c in call(args.base, "GET", f"/v1/stations/{sid}/status")["chargers"]]
        for c in chargers:
            for k, ago in enumerate((35, 20)):
                call(args.base, "POST", "/v1/events", f"pt-demo-{run}-{sid}-{c}-{k}", event(sid, "FAIL", ago, c, (41, 41)))
        print(f"{name}: 충전기 {len(chargers)}대에 실패 기록 2건씩")
    for sid, name in GREEN.items():
        call(args.base, "POST", "/v1/events", f"pt-demo-{run}-{sid}-ok", event(sid, "SUCCESS", 35, None, (38, 52)))
        print(f"{name}: 35분 전 충전 성공 기록")

    ok = True
    for sid, (name, (lat, lon)) in TARGETS.items():
        s = call(args.base, "GET", f"/v1/stations/{sid}/status")
        alts = call(args.base, "GET", f"/v1/stations/{sid}/alternatives?lat={lat}&lon={lon}")
        alt = f"{alts[0]['station']['name']} ({alts[0]['status']['verdict']})" if alts else "없음"
        print(f"  → {name}: {s['verdict']} · {s['message']} / 대안 {alt}")
        ok = ok and s["verdict"] == "RED" and bool(alts) and alts[0]["status"]["verdict"] == "GREEN"
    print("시연 준비 완료" if ok else "확인 필요: 빨강이 아니거나 대안이 초록이 아님")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
