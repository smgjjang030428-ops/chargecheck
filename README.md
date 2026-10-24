# ChargeCheck (차지체크)

> **충전할 때마다 차가 '실제로 충전됐는지' 자동으로 기록하고, 다음 운전자에게 도착 전에 알려주는 Pleos Connect 차량용 앱**

현대자동차그룹 차량용 앱 개발 공모전 출품작입니다.

전기차가 충전소에 도착하면 앱이 구동 배터리 잔량(SOC)이 실제로 오르는지 지켜봅니다.
- **SOC가 오르면:** "정상 충전"으로 자동 기록합니다.
- **SOC가 오르지 않으면:** 운전자에게 한 번 확인한 뒤 "고장"으로 기록합니다.
- **기록을 모은 뒤:** 내비 목적지가 고장이 잦은 충전소라면 도착 전에 음성과 카드로 미리 알려줍니다.

| 기능 | 내용 |
| --- | --- |
| F1 자동 정상 기록 | SOC 상승으로 충전 성공을 자동 기록 (실시간 LIVE / 재시동 시 비교 RETRO) |
| F2 고장 확인 후 기록 | SOC가 오르지 않으면 운전자에게 한 번 묻고, "응"일 때만 실패로 기록 |
| F3 도착 전 경고 | 목적지 충전소가 노랑/빨강 상태면 알리고, 대체 충전소를 경유지로 추가 |
| F4 태그 평가 | 주차 중에만, 충전 후 태그로 간단 평가 (선택) |
| F5 충전 완료 알림 | 목표 SOC에 도달하면 알림 |

---

## 1. APK 위치

| 파일 | 위치 | 상태 |
| --- | --- | --- |
| release APK | [`apk/팀명_ChargeCheck_release.apk`](apk/) | ⏳ 1차 제출 전(11/14) 업로드 예정 |

APK 용량이 GitHub 제한을 넘으면 [Releases](../../releases) 페이지에 올리고 이 표에 링크를 적습니다.

---

## 2. 실행 방법

### APK 설치 (심사용)

1. Pleos Connect 에뮬레이터(Android Automotive, API 34)를 실행합니다.
2. APK 파일을 에뮬레이터 창에 끌어다 놓거나, 아래 명령으로 설치합니다.
   ```bash
   adb install apk/팀명_ChargeCheck_release.apk
   ```
3. 앱 목록에서 **차지체크**를 실행합니다.

### 소스에서 빌드

```bash
git clone https://github.com/smgjjang030428-ops/chargecheck.git
cd chargecheck/chargecheck/android

# 충전 판단 상태 머신 단위 테스트 (Android SDK 불필요)
./gradlew :feature:session:test

# 시연용 앱 빌드 (가짜 차량 데이터 + 시나리오 재생)
./gradlew assembleDemoDebug

# 실차·에뮬레이터용 앱 빌드 (Pleos SDK 사용)
./gradlew assembleRealRelease
```

Windows에서는 `./gradlew` 대신 `gradlew.bat`을 씁니다.

### 빌드 종류 (flavor)

| flavor | 용도 | 차량 데이터 |
| --- | --- | --- |
| `demo` | 시연 영상·심사 | 가짜 어댑터가 미리 만든 충전 시나리오(성공·실패·자리 비움 등)를 재생 |
| `real` | 에뮬레이터·실차 | Pleos Vehicle SDK(SOC), NaviHelper(목적지)에서 실제 값을 읽음 |

---

## 3. 테스트 환경 및 필요한 설정

### 환경

| 항목 | 버전 |
| --- | --- |
| JDK | 17 이상 (Temurin 25로 테스트 확인) |
| Gradle | 9.1.0 (Wrapper 포함, 별도 설치 불필요) |
| Kotlin / AGP | 2.2.20 / 8.13.0 |
| Android | compileSdk 34, minSdk 28, targetSdk 34 |
| 에뮬레이터 | Pleos Connect Emulator (Android Automotive OS, API 34 system image) |
| IDE | Android Studio 최신 버전 |

### 설정

1. **Android SDK:** Android Studio로 `chargecheck/android` 폴더를 열면 자동으로 잡힙니다. 명령줄에서 빌드할 때는 `ANDROID_HOME` 환경변수나 `local.properties`의 `sdk.dir`이 필요합니다.
   - SDK가 없으면 순수 Kotlin 모듈(`core:*`, `feature:session`)만 빌드·테스트됩니다.
2. **Pleos 에뮬레이터:** Android Studio SDK Manager에서 Pleos Connect Emulator 저장소를 추가하고 API 34 system image를 설치합니다 ([Pleos 시작 가이드](https://document.pleos.ai/docs/pleos-only/getting-started)).
3. **비밀값:** 비밀값은 저장소에 올리지 않습니다. `demo` 빌드는 비밀값 없이 동작합니다.
   - **앱(`real` 빌드):** Pleos 클라이언트 정보를 `chargecheck/android/local.properties`에 넣습니다. 이 파일은 `.gitignore`로 제외돼 있습니다.
   - **서버:** 공공데이터포털 충전소 API 키를 환경변수 `PUBLIC_API_KEY`로 줍니다.
   - **팀원 설정 방법:** 키는 저장소에 없습니다. 아래 예시 파일을 복사해서 값을 채웁니다.
     - 앱: `chargecheck/android/local.properties.example` → `local.properties` (Pleos 값은 Pleos 프로젝트의 **팀 관리**에 초대받으면 프로젝트 정보 화면에서 직접 복사할 수 있습니다)
     - 서버: `chargecheck/server/.env.example` → `.env`
     - 공공 API 키는 [공공데이터포털](https://www.data.go.kr/data/15076352/openapi.do)에서 **본인 계정으로 활용신청(자동승인, 무료)** 하면 각자 하루 1,000건씩 쓸 수 있습니다. 한 키를 같이 쓰면 한도를 나눠 쓰게 됩니다.
4. **에뮬레이터에서 상황 만들기 (`real` 빌드):** Pleos Virtual Console에서 SOC와 기어 상태를 바꿔 충전 성공·실패를 재현합니다.
   - **Energy > Battery SOC** 슬라이더: SOC 값을 바꿉니다.
   - **Motion > Gear Selection**: 기어 상태(주차 등)를 바꿉니다.

### 테스트 현황

| 대상 | 결과 |
| --- | --- |
| `ChargeSessionMachine` 전이표 T1–T22 단위 테스트 | ✅ 39개 통과 (2026-10-24) |

> ⚠️ 프로젝트 경로에 한글이 있으면 Windows에서 Gradle 테스트가 실행되지 않습니다. 영문 경로에 clone하세요.

---

## 저장소 구조

```text
├─ README.md                 ← 지금 보는 파일
├─ apk/                      release APK (제출용)
├─ chargecheck/android/      차량 앱 코드 (Kotlin, Jetpack Compose)
│  ├─ app/                   진입점, flavor(real/demo) 조립
│  ├─ ui/                    Compose 화면
│  ├─ core/                  데이터 모델·인터페이스·설정값
│  ├─ feature/               충전 판단(session), 도착 전 경고, 평가, 완료 알림
│  └─ adapter/               Pleos SDK·Android·서버·가짜 데이터 구현
└─ 기획문서/                  앱 구조 명세서, 다이어그램 PDF
```

자세한 설계는 [`기획문서/앱구조_명세서_AI용.md`](기획문서/앱구조_명세서_AI용.md)에 있습니다.
