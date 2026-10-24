# ChargeCheck (차지체크) — 차량 앱

충전할 때마다 차가 '실제로 충전됐는지'를 자동 기록하고, 다음 운전자에게 도착 전에 알려주는 Pleos Connect 차량용 앱입니다.
설계 기준 문서는 `바탕화면\hyundai-contest\기획문서\앱구조_명세서_AI용.md`(이 폴더 기준 `../../기획문서/앱구조_명세서_AI용.md`)입니다. 이름·규칙은 그 문서를 따릅니다.

## 현재 진행 단계

진행 순서: **M0 → M1 → M3 → M5 → M2 → M4 → M6 → M7** (이유와 기간은 명세서 §8)

| 순서 | 단계 | 내용 | 목표 | 상태 |
| --- | --- | --- | --- | --- |
| 1 | M0 | Gradle 멀티 모듈 뼈대, 모든 모듈 껍데기 | 10/24 | ✅ |
| 2 | M1 | `core:model` · `core:port` · `core:config`, `ChargeSessionMachine` + 전이표 T1–T22 단위 테스트 | 10/24 | ✅ |
| 3 | M3 | Fake 어댑터 + 시나리오 재생 + SessionCoordinator (+ F3 도착 전 경고 기본) | ~10/27 | ✅ 시나리오 6/6, 테스트 47개 |
| 4 | M5 | Pleos SDK 연결(배터리 SOC, NaviHelper), 에뮬레이터 + Virtual Console 검증 + 충전소 지도 | 10/27–11/01 | ✅ (+ 주행 중 화면, 실차 인정 조건은 10/28 확인) |
| 5 | M2 | 서버(`../server`) + 충전기 단위 기록·판정 | 11/01–11/06 | ✅ (실제 공공 키 동기화·배포는 남음) |
| 6 | M4 | 화면: Home, ChargingScreen, AskSheet, WarningCard, Consent | 11/06–11/12 | ⏳ |
| 7 | M6 | 시연 영상, README, release APK → 1차 제출 | 11/12–11/14 | ⏳ |
| 8 | M7 | 대체 충전소, 태그 평가, 실패 재시도, 안정화 → 2차 PT | 11/16–12/02 | ⏳ |

## 모듈

```text
app/              MainActivity, flavor(real/demo), DI 조립            [Android]
ui/               Compose 화면                                         [Android]
core/model/       데이터 클래스                                         [순수 Kotlin]
core/port/        외부 의존 인터페이스                                  [순수 Kotlin]
core/config/      ChargeCheckConfig (모든 임계값)                       [순수 Kotlin]
core/data/        Room, DataStore, 업로드 큐                            [Android]
feature/session/  ChargeSessionMachine (F1·F2·F5)                      [순수 Kotlin]
feature/warning/  도착 전 경고 (F3)                                     [Android]
feature/review/   태그 평가 (F4)                                        [Android]
feature/complete/ 충전 완료 알림 (F5)                                   [Android]
adapter/pleos/    Pleos Vehicle·NaviHelper 구현                         [Android]
adapter/android/  TTS·STT·위치·주행 규제 구현                           [Android]
adapter/remote/   서버 API 구현                                         [Android]
adapter/fake/     시연·테스트용 가짜 구현                               [Android]
```

의존 규칙: `feature`와 `adapter`는 `core`만 안다. 서로 import하지 않는다. 둘을 잇는 것은 `app`뿐이다.

**[순수 Kotlin]** 모듈은 Android SDK 없이도 빌드·테스트됩니다. **[Android]** 모듈은 Android SDK가 있을 때만
(`ANDROID_HOME` 환경변수 또는 `local.properties`의 `sdk.dir`) 빌드에 포함됩니다. Android Studio로 열면 자동으로 포함됩니다.

## 실행

```bash
# 상태 머신 테스트 (Android SDK 불필요)
./gradlew :feature:session:test

# 전체 빌드 (Android Studio 또는 ANDROID_HOME 필요)
./gradlew assembleDemoDebug
```

## 처음 받은 팀원이 할 일

1. Android Studio(최신)로 이 `android` 폴더를 연다.
2. SDK Manager에서 Pleos Connect Emulator 저장소를 추가하고 API 34 system image를 설치한다(Pleos 시작 가이드 참고).
3. `./gradlew :feature:session:test`로 테스트가 통과하는지 확인한다.
4. 맡은 모듈의 `Package.kt`에 적힌 '구현 예정' 내용을 앱구조_명세서_AI용.md의 해당 절을 보며 구현한다.

## 알려진 문제

- **Windows에서 프로젝트 경로에 한글이 있으면 테스트가 실행되지 않는다.** 컴파일은 되지만 테스트 워커가
  `ClassNotFoundException`을 낸다. 영문 경로(예: `바탕화면\hyundai-contest`)에서는 `:feature:session:test` 39개가 모두 통과한다(2026-10-24 확인).
  저장소는 영문 경로에 clone해서 쓴다.

## 확인 필요 (⚠️)

- 라이브러리 버전(`gradle/libs.versions.toml`)은 2026-10 기준 작성. Android 모듈은 아직 실제로 빌드해 보지 않았다.
- Pleos SDK는 Maven 저장소 접근 권한이 필요할 수 있다(Pleos 프로젝트 생성 후 확인).
- 비밀값(Pleos 클라이언트 시크릿, 공공 API 키)은 커밋하지 말고 `local.properties`나 환경변수에 둔다.
