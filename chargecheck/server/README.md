# ChargeCheck 서버

차량 앱이 보내는 충전 성공·실패 기록을 모으고, 공공데이터(한국환경공단 충전소 API)와 합쳐 충전소·충전기별 상태를 판정합니다. 설계는 `../../기획문서/앱구조_명세서_AI용.md` §5.

## 실행 (Windows, 로컬)

```bash
# 처음 한 번: 가상환경 + 패키지 (Python 3.12)
python -m venv .venv
.venv\Scripts\python -m pip install -r requirements.txt

# 공공 API 키 없이 시연 데이터로 채우기 (강남 일대 7곳, 25대)
.venv\Scripts\python -m app.jobs.public_sync --fixture tests/fixtures/charger_info_gangnam.json

# 서버 실행 → http://127.0.0.1:8000/docs 에서 API 확인
.venv\Scripts\python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

- 에뮬레이터 안의 앱은 `http://10.0.2.2:8000` 으로 이 서버에 접속합니다.
- 실제 공공 데이터를 쓰려면 `.env.example` 을 `.env` 로 복사하고 `PUBLIC_API_KEY` 를 넣습니다. 서버가 켜질 때 목록이 비어 있으면 전체 목록을, 이후 5분마다 상태 변경분을 가져옵니다. 직접 돌리려면 `python -m app.jobs.public_sync --info --status`.

## 테스트

```bash
.venv\Scripts\python -m pytest -q
```

| 파일 | 내용 |
| --- | --- |
| `tests/test_verdict.py` | 판정 규칙(충전기 단위 → 충전소 종합) |
| `tests/test_api.py` | REST API, 중복 방지, 충전기 번호 붙이기, 주변·대체 충전소 |
| `tests/test_public_sync.py` | 공공 API 페이지 읽기, 504 재시도, 상태 반영, 삭제 처리 |

## 구조

```text
app/
├─ main.py              FastAPI 앱, 공공 데이터 동기화 루프
├─ config.py            환경변수, 판정 기준, 공공 stat·충전기 종류 코드표
├─ models.py            station / charger / charge_event / review 테이블
├─ schemas.py           요청·응답 JSON (앱과 같은 camelCase)
├─ routers/             events(기록·충전기 번호), stations(상태·주변·대체), reviews
├─ services/verdict.py  판정 순수 함수 compute_verdict
└─ jobs/public_sync.py  공공 API 동기화 (+ fixture 반영)
```
