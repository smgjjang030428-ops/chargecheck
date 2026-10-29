# ChargeCheck (차지체크)

> **충전할 때마다 차가 '실제로 충전됐는지' 자동으로 기록하고, 고장 난 충전소로 가는 운전자에게 도착 전에 대체 충전소를 알려주는 Pleos Connect 차량용 앱**

현대자동차그룹 차량용 앱 개발 공모전 출품작입니다. 팀명: **바다사람(BDSR)**

전기차가 충전소에 도착하면 앱은 구동 배터리 잔량(SOC)이 실제로 오르는지 지켜봅니다.
- **SOC가 오르면:** "정상 충전"으로 자동 기록합니다.
- **SOC가 오르지 않으면:** 운전자에게 한 번 확인한 뒤 "고장"으로 기록합니다.
- **기록이 모이면:** 서버가 충전소마다 🟢/🟡/🔴 상태를 판정합니다. 내비 목적지나 경유지가 고장 의심 충전소이면, 도착 전에 내비 위에 경고를 띄우고 대체 충전소로 한 번에 바꿔 줍니다.

| 기능 | 내용 |
| --- | --- |
| F1 자동 정상 기록 | SOC 상승으로 충전 성공을 자동 기록 (실시간 LIVE / 재시동 시 비교 RETRO) |
| F2 고장 확인 후 기록 | SOC가 오르지 않으면 운전자에게 한 번 묻고(버튼 또는 글레오 음성), "응"일 때만 실패로 기록 |
| F3 도착 전 경고 | 목적지·경유지 충전소가 고장 의심이면 내비 위에 카드로 알리고, **바꾸기**를 누르면 그 자리를 대체 충전소로 교체 (목적지는 그대로) |
| F4 태그 평가 | 주차 중에만, 충전 후 태그로 간단 평가 (선택) |
| 운영사 알림 | 고장 의심이 생기면 운영사 리포트에 올리고, 이후 성공 기록이 오면 자동 해제 |

---

## 1. APK 위치

| 파일 | 위치 | 상태 |
| --- | --- | --- |
| release APK (서명됨) | [`apk/BDSR_ChargeCheck_release.apk`](apk/BDSR_ChargeCheck_release.apk) | ✅ 2026-10-29 에뮬레이터 설치·실행 확인 |

APK 용량이 GitHub 제한을 넘으면 [Releases](../../releases) 페이지에 올리고 이 표에 링크를 적습니다.

---

## 2. 실행 방법

### APK 설치 (심사용, 권장)

1. Pleos Connect 에뮬레이터(Android Automotive, API 34)를 실행합니다.
2. APK 파일을 에뮬레이터 창에 끌어다 놓거나, 아래 명령으로 설치합니다.
   ```bash
   adb install apk/BDSR_ChargeCheck_release.apk
   ```
3. 앱 목록에서 **차지체크**를 실행합니다.

release APK는 배포 서버 **https://chargecheck-ap2h.onrender.com** 에 접속합니다. 별도 키·서버 설치 없이 바로 동작합니다.
- 공공데이터 API 키는 서버에만 있고, 앱과 저장소에는 없습니다.
- 서버는 Render(무료)에서 돌고, UptimeRobot이 5분마다 깨워 잠들지 않게 합니다. 그래도 첫 요청이 늦으면 30~50초 기다리면 됩니다.
- 서버 상태 확인: [`/healthz`](https://chargecheck-ap2h.onrender.com/healthz), API 목록: [`/docs`](https://chargecheck-ap2h.onrender.com/docs)

### 소스에서 빌드

```bash
git clone https://github.com/smgjjang030428-ops/chargecheck.git
cd chargecheck/chargecheck/android

# 단위 테스트 (충전 판단, 도착 전 경고)
./gradlew :feature:session:test :feature:warning:testDebugUnitTest

# 실차·에뮬레이터용 앱 (Pleos SDK 사용, 배포 서버 접속)
./gradlew assembleRealRelease

# 시연용 앱 (가짜 차량 데이터 + 시나리오 재생, 서버 불필요)
./gradlew assembleDemoDebug
```

Windows에서는 `./gradlew` 대신 `gradlew.bat`을 씁니다.

| 빌드 | 접속 서버 | 비고 |
| --- | --- | --- |
| `realRelease` | 배포 서버(HTTPS) | 심사용. 개발 버튼·판단 기록은 숨김 |
| `realDebug` | 개발 PC 서버 `http://10.0.2.2:8000` | 개발용. 아래처럼 서버를 직접 켜야 함. 개발 버튼(목적지 설정 등)과 판단 기록이 보임 |
| `demoDebug` | 없음(가짜 데이터) | 시연 영상용. 미리 만든 충전 시나리오(성공·실패·자리 비움 등)를 재생 |

서명 키가 없으면 `realRelease`는 서명되지 않은 APK로 만들어집니다. 서명하려면 `local.properties`에 키 정보를 넣습니다(아래 설정 4번).

### 서버를 직접 켜기 (`realDebug`용, 선택)

```bash
cd chargecheck/server
python -m venv .venv && .venv\Scripts\python -m pip install -r requirements.txt
copy .env.example .env      # PUBLIC_API_KEY에 본인 공공데이터 키를 넣는다
.venv\Scripts\python -m app.jobs.public_sync --fixture tests/fixtures/charger_info_gangnam.json   # 키 없이 시연 데이터만
.venv\Scripts\python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

자세한 내용은 [`chargecheck/server/README.md`](chargecheck/server/README.md), 배포 방법은 [`chargecheck/server/DEPLOY.md`](chargecheck/server/DEPLOY.md)에 있습니다.

---

## 3. 테스트 환경 및 필요한 설정

### 환경

| 항목 | 버전 |
| --- | --- |
| JDK | 17 이상 (Android Studio 내장 JBR로 확인) |
| Gradle | 9.1.0 (Wrapper 포함, 별도 설치 불필요) |
| Kotlin / AGP | 2.2.20 / 8.13.0 |
| Android | compileSdk 36, minSdk 28, targetSdk 34 |
| 에뮬레이터 | Pleos Connect Emulator (Android Automotive OS, API 34, 2560×1440) |
| 서버 | Python 3.12, FastAPI, PostgreSQL(Neon) / 로컬은 SQLite |
| 개발 OS | Windows 10 |

### 설정

1. **Android SDK:** Android Studio로 `chargecheck/android` 폴더를 열면 자동으로 잡힙니다. 명령줄에서 빌드할 때는 `ANDROID_HOME` 환경변수나 `local.properties`의 `sdk.dir`이 필요합니다.
   - SDK가 없으면 순수 Kotlin 모듈(`core:*`, `feature:session`)만 빌드·테스트됩니다.
2. **Pleos 에뮬레이터:** Android Studio SDK Manager에서 Pleos Connect Emulator 저장소를 추가하고 API 34 system image를 설치합니다 ([Pleos 시작 가이드](https://document.pleos.ai/docs/pleos-only/getting-started)). Pleos SDK는 공개 Maven 저장소에서 받으므로 빌드에 키가 필요 없습니다.
3. **비밀값:** 저장소에 올리지 않습니다. APK 설치와 `realRelease`·`demo` 빌드는 비밀값 없이 동작합니다.
   - **서버를 직접 켤 때만:** 공공데이터포털 충전소 API 키가 필요합니다. [공공데이터포털](https://www.data.go.kr/data/15076352/openapi.do)에서 **본인 계정으로 활용신청(자동승인, 무료)** 한 뒤 `chargecheck/server/.env`의 `PUBLIC_API_KEY`에 넣습니다.
   - 예시 파일: 앱 `chargecheck/android/local.properties.example`, 서버 `chargecheck/server/.env.example`
4. **release 서명 (선택):** 키 파일과 비밀번호를 `chargecheck/android/local.properties`에만 넣습니다.
   ```properties
   chargecheck.keystore=C:/Users/<사용자>/.chargecheck/release.jks
   chargecheck.keystorePassword=...
   chargecheck.keyAlias=chargecheck
   chargecheck.keyPassword=...
   ```
5. **에뮬레이터에서 상황 만들기 (`real` 빌드):** Pleos Virtual Console에서 SOC와 기어 상태를 바꿔 충전 성공·실패를 재현합니다.
   - **Energy > Battery SOC** 슬라이더: SOC 값을 바꿉니다.
   - **Motion > Gear Selection**: 기어 상태(주차 등)를 바꿉니다.
   - 도착 전 경고는 내비에서 고장 의심 충전소를 목적지나 경유지로 정하면 뜹니다.

### 테스트 현황 (2026-10-29)

| 대상 | 결과 |
| --- | --- |
| 충전 판단 `ChargeSessionMachine`·`SessionCoordinator` | ✅ 70개 통과 |
| 도착 전 경고 `ArrivalWarningCoordinator`·`RouteAdvice`·`DistinctNames` | ✅ 17개 통과 |
| 서버 판정·API·공공 동기화·경로 (pytest) | ✅ 64개 통과 |
| 에뮬레이터 확인 | ✅ 내비 위 경고 카드, 경유지 교체(목적지 유지), 대체 충전소 이름 구분 |
| 배포 서버 | ✅ 상태 확인, 주변 충전소 조회, 운영사 API 키 없이 차단(401) |

> ⚠️ 프로젝트 경로에 한글이 있으면 Windows에서 Gradle 테스트가 실행되지 않습니다. 영문 경로에 clone하세요.

---

## 저장소 구조

```text
├─ README.md                 ← 지금 보는 파일
├─ render.yaml               서버 배포 설정(Render)
├─ apk/                      release APK (제출용)
├─ 시연영상/                  시연 장면 캡처와 촬영 순서
├─ chargecheck/android/      차량 앱 코드 (Kotlin, Jetpack Compose)
│  ├─ app/                   진입점, flavor(real/demo) 조립
│  ├─ ui/                    Compose 화면
│  ├─ core/                  데이터 모델·인터페이스·설정값
│  ├─ feature/               충전 판단(session), 도착 전 경고(warning)
│  └─ adapter/               Pleos SDK·Android·서버·가짜 데이터 구현
├─ chargecheck/server/       서버 (FastAPI): 판정, 대체 충전소, 운영사 알림, 공공 데이터 동기화
└─ 기획문서/                  앱 구동 기획(기준 문서)
```

자세한 설계는 [`기획문서/차지체크_앱_구동_기획.md`](기획문서/차지체크_앱_구동_기획.md)에 있습니다.
