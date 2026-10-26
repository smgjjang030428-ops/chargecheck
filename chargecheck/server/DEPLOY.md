# 서버 배포 (Render + Neon, 무료)

차량 앱이 인터넷 어디서든 접속할 수 있는 HTTPS 서버를 만든다. 둘 다 무료 요금제로 충분하다.

| 역할 | 서비스 | 무료 조건 |
| --- | --- | --- |
| 서버(FastAPI) | Render 웹 서비스 | 월 750시간(한 대를 한 달 내내 켜도 약 744시간), 15분간 요청이 없으면 잠듦 |
| 깨우기 | UptimeRobot | 5분마다 주소를 불러 잠들지 않게 함. 모니터 50개까지 무료 |
| DB(PostgreSQL) | Neon | 0.5GB, 만료 없음. 전국 데이터는 약 90MB |

> 계정 만들기와 비밀값(인증키·DB 주소) 붙여넣기는 직접 한다. 비밀값은 채팅·커밋에 남기지 않는다.

## 1. DB 만들기 (Neon)

1. https://neon.tech 에 GitHub 계정으로 가입한다.
2. 새 프로젝트: 이름 `chargecheck`, 지역 **AWS Asia Pacific (Singapore)**, Postgres 버전은 기본값.
3. 대시보드의 **Connection string**(`postgresql://…neon.tech/…?sslmode=require`)을 복사해 둔다. 이것이 `DATABASE_URL`이다.

## 2. 로컬 전국 DB를 Neon으로 옮기기 (1분 이내)

서버에서 전국 목록을 처음부터 받으면 5분 넘게 걸리고 무료 서버 메모리가 빠듯하다. 이미 받은 로컬 DB를 옮긴다.

```bash
.venv\Scripts\python -m pip install -r requirements.txt
.venv\Scripts\python -m app.jobs.copy_db --from sqlite:///./chargecheck_real.db --to "여기에_Neon_주소"
```

`station: 100,096행`, `charger: 521,372행`이 나오고 `완료`로 끝나면 된다. 대상 DB가 비어 있어야 한다.

## 3. 서버 만들기 (Render)

1. https://render.com 에 GitHub 계정으로 가입하고 `chargecheck` 저장소 접근을 허락한다.
2. **New → Blueprint** → 이 저장소를 고른다. 저장소 맨 위의 `render.yaml`대로 `chargecheck` 서비스가 준비된다.
3. 직접 넣어야 하는 값 두 개를 붙여넣는다.
   - `DATABASE_URL`: 1단계의 Neon 주소
   - `PUBLIC_API_KEY`: 공공데이터포털 일반 인증키(로컬 `.env`의 값)
4. **Apply**. 빌드가 끝나면 `https://chargecheck.onrender.com`(이름이 겹치면 뒤에 글자가 붙는다) 같은 주소가 생긴다.
5. 확인: 주소 뒤에 `/healthz` → `{"ok":true}`, `/docs` → API 목록.

`DEVICE_SALT`, `OPERATOR_KEY`는 Render가 임의 값으로 만든다. 서비스의 **Environment** 탭에서 `OPERATOR_KEY` 값을 보고, 운영사 화면은 `https://주소/operator?key=그_값` 으로 연다.

## 4. 잠들지 않게 하기 (UptimeRobot)

잠들면 공공 데이터 5분 동기화와 운영사 알림 자동 해제도 멈춘다. 저장소가 비공개라 GitHub Actions로 깨우면 무료 시간(월 2,000분)을 넘으므로 외부 무료 서비스를 쓴다.

1. https://uptimerobot.com 에 가입한다.
2. **New monitor** → 종류 **HTTP(s)**, 주소 `https://chargecheck.onrender.com/healthz`(3단계의 실제 주소), 간격 **5분**.
3. 저장하면 상태가 **Up**으로 바뀌는지 본다. 서버가 멈추면 메일로도 알려 준다.

## 5. 앱이 배포 서버를 보게 하기

release 빌드는 `https://chargecheck.onrender.com`을 쓴다. 실제 주소가 다르면 `chargecheck/android/local.properties`에 한 줄 추가한다(이 파일은 커밋되지 않는다).

```properties
chargecheck.serverUrl=https://실제-주소.onrender.com
```

debug 빌드는 그대로 개발 PC 서버(`http://10.0.2.2:8000`)를 본다.

## 확인한 것 (2026-10-26, 로컬 PostgreSQL 16)

- 전국 DB 복사 15초(충전소 100,096곳·충전기 521,372대, PostgreSQL 90MB)
- 주변 충전소 60곳 조회 0.09초, 실패 2건 → 판정·운영사 알림 열림 → 성공 1건 → 자동 해제(USER_SUCCESS)
- 공공 상태 5분 동기화가 PostgreSQL에 반영됨, 운영사 API는 키 없이 401
- Render·Neon 실제 배포는 계정이 필요해 아직 하지 않음

## 주의

- 무료 Render는 배포 직후·깨어날 때 첫 요청이 30~50초 걸릴 수 있다. 앱은 실패한 기록을 보관했다가 다시 보낸다.
- `DEVICE_SALT`를 바꾸면 같은 차를 다른 기기로 센다. 처음 만든 값을 유지한다.
- 시연 뒤 데이터를 지우려면 Neon에서 `charge_event`, `fault_alert`, `review` 테이블만 비운다.
