# ChargeCheck (차지체크) — 차량 앱

충전할 때마다 차가 '실제로 충전됐는지'를 자동 기록하고, 다음 운전자에게 도착 전에 알려주는 Pleos Connect 차량용 앱입니다.
설계 기준 문서는 `바탕화면\hyundai-contest\기획문서\차지체크_앱_구동_기획.md`(이 폴더 기준 `../../기획문서/차지체크_앱_구동_기획.md`)입니다. 이름·규칙은 그 문서를 따릅니다.

## 어떻게 동작하나

운전자는 앱을 설치하고 처음 한 번 동의만 하면 됩니다. 그다음은 기본 내비만 씁니다.

- 시동(부팅)이 켜지면 `ChargeCheckService`가 화면 없이 돈다: 내비 목적지 감지, 충전 결과 기록, 도착 전 경고.
- 앱 화면이 안 보이면(내비를 보는 중) 카드와 결과 안내를 **내비 위 팝업**으로 띄운다(`CardNotifier`). 버튼은 최대 2개.
- 앱을 직접 열면 충전소 지도·상세·판단 기록 화면이 나온다. 주행 중에는 카드 한 장짜리 주행 화면만.

## 모듈

```text
app/              MainActivity, flavor(real/demo), 백그라운드 서비스·내비 위 팝업·글레오 연결   [Android]
ui/               Compose 화면 (지도, 주행 화면, 동의·설정)                               [Android]
core/model/       데이터 클래스                                                          [순수 Kotlin]
core/port/        외부 의존 인터페이스                                                   [순수 Kotlin]
core/config/      ChargeCheckConfig (모든 임계값)                                        [순수 Kotlin]
feature/session/  ChargeSessionMachine + SessionCoordinator (충전 기록 판단)             [순수 Kotlin]
feature/warning/  도착 전 경고, 경로 카드 규칙, 충전소 짝짓기                            [Android]
adapter/pleos/    Pleos Vehicle·NaviHelper 구현                                          [Android]
adapter/android/  주행 제한(CarUxRestrictions)                                           [Android]
adapter/remote/   서버 API (못 보낸 기록 대기열, 마지막 충전소 목록)                     [Android]
adapter/fake/     시연·테스트용 가짜 구현 + 시나리오 재생                                [Android]
```

의존 규칙: `feature`와 `adapter`는 `core`만 안다. 서로 import하지 않는다. 둘을 잇는 것은 `app`뿐이다.

빌드 2종: **real**(Pleos SDK + 서버 `../server`), **demo**(가짜 데이터로 시나리오 재생, 앱 이름 "차지체크 시연").

**[순수 Kotlin]** 모듈은 Android SDK 없이도 빌드·테스트됩니다. **[Android]** 모듈은 Android SDK가 있을 때만
(`ANDROID_HOME` 환경변수 또는 `local.properties`의 `sdk.dir`) 빌드에 포함됩니다.

## 실행

```bash
# 테스트
./gradlew test

# 빌드
./gradlew assembleRealDebug assembleDemoDebug
```

에뮬레이터에서 real 빌드를 확인하는 방법(서버 실행, Virtual Console, 디버그 버튼)은 기획 §13에 있습니다.

## 알려진 문제

- **Windows에서 프로젝트 경로에 한글이 있으면 테스트가 실행되지 않는다.** 컴파일은 되지만 테스트 워커가
  `ClassNotFoundException`을 낸다. 영문 경로(예: `바탕화면\hyundai-contest`)에 clone해서 쓴다.
- 비밀값(Pleos 클라이언트 시크릿, 공공 API 키)은 커밋하지 말고 `local.properties`나 환경변수에 둔다.
