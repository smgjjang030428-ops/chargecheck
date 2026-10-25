# ChargeCheck (차지체크) — 앱 아키텍처 명세

> 이 문서는 사람과 AI 코딩 도구가 그대로 따라 구현할 수 있도록 쓴 **단일 기준 문서(Single Source of Truth)** 입니다.
> 모든 이름(모듈, 클래스, 이벤트, API 경로, DB 컬럼, 설정값)은 이 문서에 적힌 그대로 사용합니다.
> 확인되지 않은 사실은 `⚠️ UNVERIFIED`로 표시했습니다. 구현 중 사실이 확인되면 이 문서를 먼저 고치세요.
>
> 작성일: 2026-10-24 · 대상: 현대자동차그룹 차량용 앱 개발 공모전 (Pleos Connect)
>
> **파일 위치**: 이 문서는 `기획문서/앱구조_명세서_AI용.md`에 있다. 이 문서에 나오는 코드 경로(`android/`, `server/`, `tools/`)는 모두 프로젝트 최상위의 `chargecheck/` 폴더 아래 경로다. 예: `android/feature/session` = `chargecheck/android/feature/session`. 코드 폴더 경로에는 한글을 쓰지 않는다(Windows에서 Gradle 테스트가 실패함).

---

## 0. AI 에이전트를 위한 요약

- **무엇**: Pleos Connect(Android Automotive OS 기반 현대차그룹 인포테인먼트)용 차량 앱 + 백엔드 서버.
- **핵심 아이디어**: 전기차가 충전소에서 **배터리 잔량(SOC)이 실제로 올랐는지**로 충전 성공/실패를 자동 기록하고, 그 기록을 모아 다음 운전자에게 "이 충전소 지금 실제로 되나요?"를 알려준다.
- **구성 요소 3개**
  1. `android/` — 차량 앱 (Kotlin, Jetpack Compose, Hilt, Coroutines/Flow, Room, DataStore)
  2. `server/` — 백엔드 (Python 3.12, FastAPI, SQLAlchemy 2, PostgreSQL 16, APScheduler)
  3. `tools/simulator/` — 여러 대의 가짜 차량이 서버에 기록을 보내는 시뮬레이터 (Python)
- **가장 중요한 설계 원칙**
  1. 기능 코드는 Pleos SDK를 직접 import하지 않는다. 모든 외부 의존성은 `core:port` 인터페이스 뒤에 숨긴다.
  2. 충전 판단 로직은 **순수 함수 상태 머신**(`ChargeSessionMachine.reduce`)이다. 부수 효과는 `Effect`로 반환한다.
  3. 충전 중에 차량(인포테인먼트)이 꺼져 있을 수 있다. 그래서 **실시간 감지(LIVE)** 와 **도착/출발 비교(RETRO)** 두 경로를 모두 구현한다.
  4. 잔량만으로 "고장"을 확정하지 않는다. 실패 기록은 **반드시 운전자 확인 1회** 후에만 남긴다. 성공 기록은 자동.
  5. 서버에는 좌표를 보내지 않는다. 충전소 ID·결과·시각·SOC만 보낸다. 기기 ID는 앱 설치 시 생성한 임의 UUID.
- **빌드 변형(flavor)**: `real`(Pleos/Android 어댑터) / `demo`(가짜 어댑터 + 시나리오 재생). 시연 영상은 `demo`로 찍는다.

---

## 1. 용어

| 용어 | 뜻 |
| --- | --- |
| SOC | State of Charge. 구동 배터리 잔량(%) |
| 충전소(Station) | 공공 API의 `statId` 하나. 여러 충전기를 가진 장소 |
| 충전기(Charger) | 공공 API의 `statId` + `chgerId` 조합 |
| 세션(ChargeSession) | 한 충전소에 도착해서 떠날 때까지 한 번의 충전 시도 |
| LIVE 판정 | 차량이 켜진 채로 SOC 변화를 실시간으로 보고 판정 |
| RETRO 판정 | 도착 시 저장한 SOC와 다음 시동 시 SOC를 비교해 사후 판정 |
| Verdict | 충전소의 종합 상태: `GREEN`/`YELLOW`/`RED`/`GRAY` |
| 이벤트(ChargeEvent) | 서버로 보내는 성공/실패 기록 1건 |

---

## 2. 기능 목록과 우선순위

| ID | 기능 | 우선순위 | 완료 기준(Acceptance) |
| --- | --- | --- | --- |
| F1 | 자동 정상 기록 | P0 | LIVE: 온사이트 후 SOC가 기준 대비 `SUCCESS_DELTA_LIVE_PCT` 이상 오르면 SUCCESS 이벤트 1건 업로드. RETRO: 재시동 시 SOC 상승폭 ≥ `SUCCESS_DELTA_RETRO_PCT`면 SUCCESS(mode=RETRO) 1건 업로드 |
| F2 | 고장 확인 후 기록 | P0 | SOC 정체가 `stallTimeout(chargerType)` 이상이면 질문. "응"이면 FAIL 1건, "아직"이면 타이머 연장, 무응답이면 기록 없음. RETRO도 동일 질문(과거형) |
| F3 | 도착 전 경고 | P0 | 내비 목적지/경유지가 충전소이고 Verdict가 YELLOW/RED면 음성+카드로 알림. "바꿔줘"면 대체 충전소를 경유지로 추가 |
| F4 | 태그 평가 | P1 | 주차 상태에서만, 세션 종료 시 태그 선택(선택 사항). 서버에 리뷰 업로드 |
| F5 | 충전 완료 알림 | P2 | LIVE 중 SOC ≥ 목표치면 알림 1회 |
| F0 | 첫 실행 동의 | P0 | 데이터 수집 동의 전에는 업로드하지 않음 |
| DEV | 데모 메뉴 | P0(demo) | `demo` flavor에서 시나리오 선택·재생 |

---

## 3. 시스템 구성

```mermaid
flowchart LR
  subgraph Car["차량 (Pleos Connect)"]
    APP["ChargeCheck 차량 앱"]
    SDK["Pleos SDK<br/>Vehicle State · NaviHelper"]
    NAVI["기본 내비 앱"]
  end
  subgraph Cloud["클라우드"]
    API["ChargeCheck API (FastAPI)"]
    DB[("PostgreSQL")]
    SYNC["PublicDataSync 작업"]
  end
  GOV["한국환경공단<br/>전기차 충전소 API"]
  SIM["시뮬레이터(시연용)"]

  APP <--> SDK
  SDK <--> NAVI
  APP -- "이벤트 업로드 / 상태 조회 (HTTPS)" --> API
  API <--> DB
  SYNC -- "5분마다 상태 동기화" --> GOV
  SYNC --> DB
  SIM -- "가짜 이벤트" --> API
```

---

## 4. 차량 앱 (`android/`)

### 4.1 기술 스택

| 항목 | 선택 | 비고 |
| --- | --- | --- |
| 언어 | Kotlin 2.x | |
| 최소/컴파일 SDK | minSdk 28, compileSdk 34 | Pleos 시작 가이드 기준 |
| 빌드 | Gradle 8.x, Kotlin DSL, version catalog | |
| UI | Jetpack Compose (Material3) | Pleos UI 디자인 가이드의 버튼 크기 준수 |
| DI | Hilt | flavor별 어댑터 바인딩 |
| 비동기 | Coroutines + Flow | SDK 콜백은 `callbackFlow`로 감싼다 |
| 로컬 저장 | Room(세션·업로드 큐), DataStore(설정·스냅샷) | |
| 네트워크 | Retrofit + OkHttp + kotlinx.serialization | |
| 백그라운드 | Foreground Service(`ChargeCheckService`) | 앱이 화면에 없어도 SOC·내비 이벤트 수신 |
| 테스트 | JUnit5, kotlinx-coroutines-test, Turbine | 상태 머신은 100% 단위 테스트 |
| Pleos SDK | `ai.pleos.playground:Vehicle:2.3.1`, `ai.pleos.playground:NaviHelper:3.0.5` | Maven: `https://nexus-playground.pleos.ai/repository/maven-releases/` |

### 4.2 Gradle 모듈 구조

```text
android/
├─ app/                         # MainActivity, Compose NavHost, Hilt 진입점, ChargeCheckService
├─ core/
│  ├─ model/                    # 순수 데이터 클래스 (안드로이드 의존 없음)
│  ├─ port/                     # 외부 의존 인터페이스 (VehiclePort 등)
│  ├─ config/                   # ChargeCheckConfig (모든 임계값)
│  └─ data/                     # Room DB, DataStore, Repository, UploadQueue
├─ feature/
│  ├─ session/                  # F1·F2: ChargeSessionMachine(순수) + SessionCoordinator
│  ├─ warning/                  # F3: ArrivalWarningCoordinator
│  ├─ review/                   # F4: ReviewViewModel
│  └─ complete/                 # F5: CompletionNotifier
├─ ui/                          # Compose 화면과 디자인 컴포넌트
├─ adapter/
│  ├─ pleos/                    # Pleos Vehicle·NaviHelper 구현 (real flavor)
│  ├─ android/                  # Android TTS·STT·Location·CarUxRestrictions 구현 (real flavor)
│  ├─ remote/                   # Retrofit ChargeCheckApi 구현
│  └─ fake/                     # 가짜 구현 + ScenarioPlayer (demo flavor, 테스트)
└─ gradle/libs.versions.toml
```

**의존 규칙 (반드시 지킴)**

```text
core:model  ← (아무것도 의존하지 않음)
core:port   → core:model
core:config → core:model
core:data   → core:model, core:port, core:config
feature:*   → core:*            (adapter:* 금지, Pleos SDK 금지)
ui          → core:*, feature:*
adapter:*   → core:model, core:port   (feature:* 금지)
app         → 전부 (DI 조립만 담당)
```

### 4.3 패키지 이름

`com.chargecheck.<module>` 형식. 예: `com.chargecheck.feature.session`, `com.chargecheck.adapter.pleos`.

### 4.4 도메인 모델 (`core:model`)

```kotlin
package com.chargecheck.core.model

import java.time.Instant

@JvmInline value class StationId(val value: String)        // 공공 API statId
@JvmInline value class DeviceId(val value: String)         // 설치 시 생성한 UUID

data class GeoPoint(val lat: Double, val lon: Double)

enum class ChargerType { FAST, SLOW, UNKNOWN }

data class Station(
    val id: StationId,
    val name: String,
    val location: GeoPoint,
    val chargerType: ChargerType,       // 충전소 안에 급속이 하나라도 있으면 FAST
)

enum class Verdict { GREEN, YELLOW, RED, GRAY }

data class StationStatus(
    val stationId: StationId,
    val verdict: Verdict,
    val reasonCode: ReasonCode,
    val lastSuccessAt: Instant?,
    val recentFailCount: Int,           // 마지막 SUCCESS 이후 서로 다른 기기의 FAIL 수
    val publicAvailable: Int?,          // 공공 API 기준 사용 가능 충전기 수
    val publicTotal: Int?,
    val topTags: List<ReviewTag>,
)

enum class ReasonCode {
    RECENT_SUCCESS,                     // GREEN
    FAIL_AFTER_SUCCESS,                 // YELLOW
    MULTI_FAIL,                         // RED
    PUBLIC_OUT_OF_SERVICE,              // RED (공공 API 운영중지·점검)
    PUBLIC_SAYS_OK_BUT_FAILS,           // RED, 차별화 메시지
    NO_RECENT_DATA,                     // GRAY
}

enum class ChargeResult { SUCCESS, FAIL }
enum class DetectMode { LIVE, RETRO, MANUAL }  // MANUAL = 상세 카드에서 운전자가 직접 표시

data class ChargeEvent(
    val eventId: String,                // UUID, 서버 멱등성 키
    val deviceId: DeviceId,
    val stationId: StationId,
    val result: ChargeResult,
    val mode: DetectMode,
    val socStart: Int,
    val socEnd: Int,
    val minutes: Int,
    val occurredAt: Instant,
)

enum class ReviewTag { CABLE_SHORT, SPACE_NARROW, HAS_ROOF, SLOW_SPEED, PAYMENT_ISSUE, BLOCKED_BY_CAR }

data class SocSample(val pct: Int, val at: Instant)

/** 시동이 꺼져도 남는 도착 스냅샷 (DataStore에 저장) */
data class ArrivalSnapshot(
    val stationId: StationId,
    val stationName: String,
    val chargerType: ChargerType,
    val soc: Int,
    val at: Instant,
    val location: GeoPoint?,
)
```

### 4.5 포트 인터페이스 (`core:port`)

```kotlin
package com.chargecheck.core.port

interface VehiclePort {
    /** 배터리 잔량(%) 변화 스트림. Pleos: EvBattery.registerEvBatteryStateOfCharge */
    fun soc(): Flow<SocSample>
    suspend fun currentSoc(): Int?
    /** 목표 충전량(%) — 없으면 null. ⚠️ UNVERIFIED: Pleos에서 읽기 API 존재 여부 */
    suspend fun chargeTargetPct(): Int?
    /** 남은 주행 가능 거리(km). Pleos: Odometer.getRangeRemainingDistance(미터, 2026-10-25 에뮬레이터 확인), 권한 CAR_DRIVE */
    suspend fun remainingRangeKm(): Double? = null
}

interface DrivingStatePort {
    /** true = 주차/정차(화면 조작 허용), false = 주행(제한). Android CarUxRestrictions 기반 */
    fun isParked(): Flow<Boolean>
}

interface NaviPort {
    /** 기본 내비의 목적지·경유지 변화 */
    fun routeTargets(): Flow<List<RouteTarget>>
    /** 목적지·경유지 도착 */
    fun arrivals(): Flow<RouteTarget>
    suspend fun addWaypoint(target: RouteTarget): Result<Unit>
    /** 기본 내비에 목적지를 넘겨 길안내 시작(지도 화면 [여기로 안내]). Pleos: NaviHelper.requestRoute */
    suspend fun setDestination(target: RouteTarget): Result<Unit>
}
data class RouteTarget(val name: String, val location: GeoPoint, val poiId: String?)

interface LocationPort {
    fun locations(): Flow<GeoPoint>
    suspend fun lastKnown(): GeoPoint?
}

interface VoicePort {
    suspend fun say(text: String)
    /** 인식 결과를 Intent로 분류. 타임아웃·오류는 null */
    suspend fun ask(question: String, timeoutSec: Int): VoiceIntent?
}
enum class VoiceIntent { YES, WAIT, NO, SWITCH, KEEP }

interface ChargeCheckApiPort {
    suspend fun postEvent(event: ChargeEvent): Result<Unit>
    suspend fun stationStatus(id: StationId): Result<StationStatus>
    suspend fun nearbyStations(at: GeoPoint, radiusM: Int): Result<List<Station>>
    suspend fun alternatives(id: StationId, at: GeoPoint): Result<List<Pair<Station, StationStatus>>>
    suspend fun postReview(stationId: StationId, tags: List<ReviewTag>): Result<Unit>
}

interface ClockPort { fun now(): Instant }
```

### 4.6 설정값 (`core:config`)

```kotlin
// 구현: chargecheck/android/core/config/.../ChargeCheckConfig.kt
data class ChargeCheckConfig(
    val onsiteRadiusM: Int = 50,                          // 충전소 좌표에서 이 거리 안이면 '현장'
    val onsiteMinStop: Duration = Duration.ofMinutes(5),  // 이만큼 정차해야 세션 시작
    val leftRadiusM: Int = 150,                           // 이만큼 벗어나면 세션 종료
    val successDeltaLivePct: Int = 1,                     // LIVE: 기준 대비 +1%p면 성공
    val successDeltaRetroPct: Int = 2,                    // RETRO: 재시동 시 +2%p 이상이면 성공
    val stallTimeoutFast: Duration = Duration.ofMinutes(10),  // 급속: 이만큼 SOC 정체면 질문
    val stallTimeoutSlow: Duration = Duration.ofMinutes(40),  // 완속·미상: 길게
    val waitExtension: Duration = Duration.ofMinutes(10),     // "아직 연결 중" 답하면 연장
    val retroMinElapsed: Duration = Duration.ofMinutes(15),   // RETRO 실패 질문 최소 경과
    val retroMaxElapsed: Duration = Duration.ofHours(24),     // 이보다 오래되면 스냅샷 폐기
    val attemptMinStop: Duration = Duration.ofMinutes(2),     // 이만큼 머물다 잔량 변화 없이 출발하면 실패 확인(T23)
    val confirmWindow: Duration = Duration.ofHours(2),        // 출발 때 못 받은 확인을 다음 주차 때 다시 물을 수 있는 시간
    val confirmDrivingShow: Duration = Duration.ofSeconds(45),// 주행 화면 확인 카드 표시 시간
    val socRetryInterval: Duration = Duration.ofMinutes(1),   // SOC 미확인 시 재확인 간격
    val askTimeoutSec: Int = 8,
    val dedupWindow: Duration = Duration.ofHours(2),      // 서버와 동일
    val greenWindow: Duration = Duration.ofHours(3),      // 서버 판정 규칙과 동일
    val grayWindow: Duration = Duration.ofHours(24),
) {
    fun stallTimeout(type: ChargerType): Duration
    companion object { val DEFAULT: ChargeCheckConfig }
}
```

모든 임계값은 이 클래스에서만 읽는다. 숫자를 코드 곳곳에 하드코딩하지 않는다. 테스트에서 값을 바꿔 끼울 수 있도록 data class로 둔다.

### 4.7 충전 세션 상태 머신 (`feature:session`) — 핵심

```mermaid
stateDiagram-v2
  [*] --> Idle
  Idle --> Approaching: RouteTargetIsStation
  Idle --> Candidate: StoppedNearStation
  Approaching --> Candidate: Arrived
  Candidate --> Monitoring: Stopped ≥ 5분 (스냅샷 저장)
  Candidate --> Idle: Moved
  Monitoring --> Charging: SOC ≥ 기준+1 (SUCCESS 업로드)
  Monitoring --> Asking: 정체 ≥ stallTimeout
  Monitoring --> Suspended: AppStopped (차량 꺼짐)
  Asking --> Monitoring: "아직" (타이머 연장)
  Asking --> Failed: "응" (FAIL 업로드)
  Charging --> Charging: SOC 상승 (속도 갱신)
  Charging --> Completed: SOC ≥ 목표 (F5 알림)
  Charging --> Ended: Moved
  Completed --> Ended: Moved
  Failed --> Ended: Moved
  Suspended --> Charging: Resumed, Δ ≥ 2 (SUCCESS RETRO 업로드)
  Suspended --> AskingRetro: Resumed, Δ < 1, 경과 ≥ 15분
  Suspended --> Idle: Resumed, 스냅샷 만료/이동/+1%p만
  Suspended --> Monitoring: Resumed, 15분 미만 (현장 계속 감시)
  Asking --> Charging: SOC ≥ 기준+1 (SUCCESS 업로드)
  Monitoring --> Idle: 결과 없이 150m 이동
  AskingRetro --> Failed: "응" (FAIL RETRO 업로드)
  AskingRetro --> Idle: "아니"/무응답
  Asking --> Idle: 무응답/아니 (기록 없음)
  Ended --> Idle: 리뷰 표시(주차 시)
```

**구현 형태 (순수 함수)** — 구현: `chargecheck/android/feature/session/.../SessionTypes.kt`, `ChargeSessionMachine.kt`

```kotlin
package com.chargecheck.feature.session

sealed interface SessionState {
    data object Idle : SessionState
    data class Approaching(val station: Station) : SessionState
    data class Candidate(val station: Station, val stoppedAt: Instant, val lastSoc: SocSample? = null) : SessionState
    data class Monitoring(val snap: ArrivalSnapshot, val lastSoc: SocSample, val stallDeadline: Instant) : SessionState
    data class Asking(val snap: ArrivalSnapshot, val lastSoc: SocSample) : SessionState
    data class Charging(val snap: ArrivalSnapshot, val lastSoc: SocSample) : SessionState  // snap.successSent = true
    data class Completed(val snap: ArrivalSnapshot) : SessionState
    data class Failed(val snap: ArrivalSnapshot) : SessionState
    data class Suspended(val snap: ArrivalSnapshot) : SessionState
    data class AskingRetro(val snap: ArrivalSnapshot, val currentSoc: Int, val resumedAt: Instant) : SessionState
    data class Ended(val snap: ArrivalSnapshot, val result: ChargeResult?) : SessionState
}

sealed interface SessionInput {
    data class RouteTargetIsStation(val station: Station) : SessionInput
    data class StoppedNearStation(val station: Station, val at: Instant) : SessionInput
    data class Arrived(val station: Station, val at: Instant) : SessionInput
    data class Moved(val distanceFromStationM: Int) : SessionInput
    data class Soc(val sample: SocSample) : SessionInput
    data class Tick(val now: Instant) : SessionInput
    data class Answer(val intent: VoiceIntent?, val at: Instant) : SessionInput   // null = 무응답
    data object AppStopped : SessionInput
    data class AppResumed(val soc: Int?, val location: GeoPoint?, val now: Instant) : SessionInput
    data class TargetReached(val pct: Int) : SessionInput
    data object ReviewDone : SessionInput
}

sealed interface Effect {
    data class SaveSnapshot(val snap: ArrivalSnapshot) : Effect
    data object ClearSnapshot : Effect
    data class Upload(val event: ChargeEvent) : Effect
    data class Ask(val question: String, val retro: Boolean) : Effect
    data class Say(val text: String) : Effect
    data class ShowToast(val text: String) : Effect
    data class ScheduleTick(val at: Instant) : Effect
    data object NotifyComplete : Effect
    data class OfferReview(val stationId: StationId) : Effect
}

data class Transition(val state: SessionState, val effects: List<Effect> = emptyList())

fun interface IdFactory { fun newEventId(): String }   // core:port

class ChargeSessionMachine(
    private val deviceId: DeviceId,
    private val ids: IdFactory,
    private val cfg: ChargeCheckConfig = ChargeCheckConfig.DEFAULT,
) {
    fun reduce(state: SessionState, input: SessionInput): Transition
}
```

`ArrivalSnapshot`에는 `successSent: Boolean`이 있다. 앱이 꺼졌다 켜져도 같은 세션에서 SUCCESS를 두 번 보내지 않기 위해 스냅샷과 함께 저장한다.

**전이 규칙 표 (테스트 케이스와 1:1 대응)**

| # | 현재 상태 | 입력 | 조건 | 다음 상태 | Effect |
| --- | --- | --- | --- | --- | --- |
| T1 | Idle | RouteTargetIsStation | — | Approaching | — |
| T2 | Idle/Approaching | StoppedNearStation / Arrived | 거리 ≤ 50m | Candidate | ScheduleTick(+5분) |
| T3 | Candidate | Tick | 정차 ≥ 5분, SOC 읽힘 | Monitoring | SaveSnapshot, ScheduleTick(stallDeadline). SOC 아직 없으면 ScheduleTick(+1분) |
| T4 | Candidate | Moved | > 50m | Idle | — |
| T5 | Monitoring | Soc | pct ≥ snap.soc + 1 | Charging (snap.successSent=true) | Upload(SUCCESS, LIVE), SaveSnapshot(successSent=true), ShowToast |
| T6 | Monitoring | Tick | now ≥ stallDeadline | Asking | Ask("충전이 안 되나요?", retro=false) |
| T7 | Asking | Answer(YES) | — | Failed | Upload(FAIL, LIVE), ClearSnapshot, Say("기록했어요. 다른 충전소를 찾아볼까요?") |
| T8 | Asking | Answer(WAIT) | — | Monitoring | ScheduleTick(answer.at + 10분) |
| T9 | Asking | Answer(null/NO) | — | Idle | ClearSnapshot |
| T10 | Monitoring/Asking/Charging/Completed | AppStopped | — | Suspended | (스냅샷은 이미 저장됨) |
| T11 | Suspended | AppResumed | 경과 ≤ 24h, 거리 ≤ 150m 또는 위치 미상, soc − snap.soc ≥ 2 | Charging | Upload(SUCCESS, RETRO), SaveSnapshot(successSent=true), ShowToast |
| T12 | Suspended | AppResumed | 경과 ≥ 15분, soc − snap.soc < 1 | AskingRetro | Ask("아까 ○○에서 충전이 안 됐나요?", retro=true) |
| T13 | Suspended | AppResumed | 경과 > 24h, 150m 넘게 이동, SOC 미상 | Idle | ClearSnapshot |
| T13b | Suspended | AppResumed | 경과 < 15분, SOC 상승 < 2 | Monitoring | ScheduleTick(now + stallTimeout) (현장에서 계속 지켜봄) |
| T13c | Suspended | AppResumed | 경과 ≥ 15분, SOC +1%p만 | Idle | ClearSnapshot (판단 보류) |
| T14 | AskingRetro | Answer(YES) | — | Failed | Upload(FAIL, RETRO), ClearSnapshot |
| T15 | AskingRetro | Answer(null/NO) 또는 150m 넘게 이동 | — | Idle | ClearSnapshot |
| T16 | Charging | Soc | — | Charging | (lastSoc 갱신) |
| T17 | Charging | TargetReached | — | Completed | NotifyComplete |
| T18 | Charging/Completed/Failed | Moved | > 150m | Ended | OfferReview(주차 시에만 UI가 표시) |
| T19 | Ended | ReviewDone (또는 다른 입력) | — | Idle (다른 입력이면 Idle에서 이어서 처리) | ClearSnapshot |
| T20 | Suspended | AppResumed | snap.successSent = true | Charging | Upload 없음 — 같은 세션에서 두 번째 SUCCESS 금지 |
| T21 | Asking | Soc | pct ≥ snap.soc + 1 | Charging | T5와 같음 (질문 중 충전 시작) |
| T22 | Monitoring/Asking/AskingRetro | Moved | > 150m (Departing을 못 받은 경우의 대비) | Idle | ClearSnapshot, **ConfirmLater**(FAIL 기록을 운전자 확인에 맡김) |
| T23 | Candidate | Departing | 정차 ≥ `attemptMinStop`(2분), SOC 읽음 | Idle | ConfirmLater(LIVE). 더 짧거나 SOC 모름 → 그냥 Idle |
| T24 | Monitoring/Asking | Departing | — (이미 5분 정차를 채움) | Idle | ClearSnapshot, ConfirmLater(LIVE) |
| T25 | AskingRetro | Departing | — | Idle | ClearSnapshot, ConfirmLater(RETRO) |

**잔량 변화 없이 출발했을 때의 실패 확인 (2026-10-25 추가)**
꽂았는데 안 돼서 몇 분 만에 다시 출발하는 경우가 가장 흔한데, 예전 설계는 질문(급속 10분, RETRO 15분)이 뜨기 전에 떠나면 기록 없이 지웠다. 이제는 다음처럼 확인한다. 원칙(FAIL은 운전자 YES 뒤에만, 무응답은 기록 안 함)은 그대로다.
1. `Departing` = 주차 → 주행(`DrivingStatePort.isParked()` true → false). 차는 아직 서 있으므로 물어도 안전하다.
2. 상태 머신은 FAIL `ChargeEvent`를 미리 만들어 `Effect.ConfirmLater(event, stationName)`로 넘기고 세션은 Idle로 끝낸다(새 충전소 세션이 바로 시작될 수 있도록 세션과 분리).
3. `SessionCoordinator.confirm`(`FailConfirm`)이 들고 있다가: 출발 직후 `DrivingScreen`에 "충전이 안 됐나요? [안 됐어요] [아니요]"를 45초 → 못 누르면 숨기고, 다음 주차(2시간 안)에 `AskSheet`로 "아까 ○○에서 충전이 안 됐나요?"를 한 번 더 → 그래도 답 없이 출발하거나 2시간이 지나면 버린다.
4. [안 됐어요]/[응]이면 그 기록을 올리고, 충전기가 2대 이상이면 "몇 번 충전기였나요?"로 이어진다.
5. 상세 카드 **[여기 충전 안 됐어요]**(mode=MANUAL): 지금 세션의 충전소이거나 차가 150m 안에 있을 때만 보인다(허위 기록 방지). 같은 기기·충전소 2시간 중복은 서버가 받지 않고(`accepted: false`) 앱은 "2시간 안에 이미 기록됨"으로 남긴다.

`SessionCoordinator`(Android 쪽)는 포트의 Flow를 `SessionInput`으로 바꿔 `reduce`에 넣고, 나온 `Effect`를 실행한다. 상태는 `StateFlow<SessionState>`로 UI에 노출한다.

### 4.8 충전소 판별 (`StationResolver`)

1. 내비 목적지·경유지(`RouteTarget`)가 서버 충전소 목록의 어떤 충전소와 100m 이내면 그 충전소 (1순위).
2. 아니면 `LocationPort.lastKnown()` 기준 반경 `ONSITE_RADIUS_M` 안의 충전소. 지하 주차장 대비 "마지막으로 GPS가 잡힌 위치"를 쓴다.
3. 후보가 2개 이상이면 주차 상태에서 목록으로 고르게 하고, 주행 중이면 기록하지 않는다.
4. 후보가 없으면 세션을 시작하지 않는다.

충전소 목록은 `nearbyStations(at, 500)`로 받아 Room에 캐시한다(TTL 24h).

### 4.9 도착 전 경고 (`feature:warning`, F3)

```mermaid
sequenceDiagram
  participant N as NaviPort
  participant W as ArrivalWarningCoordinator
  participant A as ChargeCheckApiPort
  participant V as VoicePort
  participant UI as WarningCard
  N->>W: routeTargets() 변경 (목적지 = 충전소)
  W->>A: stationStatus(id)
  A-->>W: verdict = RED, reason = PUBLIC_SAYS_OK_BUT_FAILS
  W->>A: alternatives(id, 현재 위치)
  A-->>W: [가까운 GREEN 충전소]
  W->>UI: 카드 표시 (주행 중: 버튼 2개만)
  W->>V: ask("가는 충전소에 최근 실패 기록이 있어요. 바꿀까요?")
  V-->>W: SWITCH
  W->>N: addWaypoint(대체 충전소)
```

- 경고는 목적지가 바뀔 때 1회, 도착 5km 전 1회(재조회)만. 반복 알림 금지.
- GREEN이면 말하지 않고 홈 카드만 갱신한다.

### 4.10 화면 (`ui`)

| 화면 | 언제 | 주행 중 허용 | 구성 |
| --- | --- | --- | --- |
| `ConsentScreen` | 최초 실행 | ✕ | 수집 항목(충전소 ID, 결과, SOC, 시각), 동의 버튼 |
| `HomeScreen` | 앱 열기 | 제한(읽기만) | 차 일러스트 + 상태 한 줄, 목적지 충전소 카드, 최근 기록 3개 |
| `WarningCard` | F3 | ○ (버튼 2개) | 상태 배지, 충전소 이름, 이유 한 줄, [근처 충전소로 바꾸기] [그대로] |
| `ChargingScreen` | Monitoring/Charging | ✕(주차 전용) | SOC 막대, 10분당 상승폭, "정상 1건 기록" 토스트 |
| `AskSheet` | Asking/AskingRetro, 다음 주차 때의 실패 확인 | ✕(주차 전용) | 질문 + [응, 안 돼요] [아직 연결 중] / 과거형 [응] [아니요] |
| 주행 화면 확인 카드 | 잔량 변화 없이 출발 직후 45초 | ○ (버튼 2개) | "충전이 안 됐나요?" + 충전소 이름, [안 됐어요] [아니요] (경고가 있으면 경고가 먼저) |
| `ReviewSheet` | Ended + 주차 | ✕ | 태그 칩 6개, 건너뛰기 |
| `DevMenuScreen` | demo flavor | ✕ | 시나리오 목록, 재생/정지, 시간 배속 |
| `StationMapScreen` | real flavor, 주차 중 | ✕(주차 전용) | 주변 충전소 지도(우리 판정 색 핀), 핀 → 상세 카드 → [여기로 안내](`NaviPort.setDestination`) |
| 지역 검색 상자 | 지도 위(주차 중) | ✕ | 목적지·지역 검색 → 결과 목록(장소 / 충전소) |
| `RoutePanel` (가는 길 충전소) | 검색 결과 선택(주차 중) | ✕ | "○○까지 22.8km · 남은 주행 8km · 배터리 8%", 충전 필요 배너(초록: 충전 없이 갈 수 있음 / 노랑: 추천 충전소에 들르기 / 빨강: 갈 수 있는 곳 없음), 경로 순 충전소 줄(지점 km · 도착 예상 배터리 · 경로 이탈 m · 사용 가능 n/m · 추천 · 가기 어려움), [들렀다 가기](목적지 + 경유지) [바로 안내] [닫기]. 지도에는 경로선·목적지·추천 핀(크게) |
| 충전소 상세 카드 | 핀 선택(주차 중) | ✕ | 판정 문장, **충전 타입**(급속 100kW 4대 · 완속 7kW 2대), **커넥터**(DC콤보 · AC완속), **실시간**(사용 가능 5 · 충전 중 1), 충전기별 줄(번호·종류·kW·커넥터·공공 상태·우리 실패 기록), **결제**, 이용(시간·주차 무료 여부·이용 제한) |

- 결제 수단은 공공 API에 필드가 없다(2026-10-24 확인). 지어내지 않고 "공공 데이터에 결제 정보 없음 · 운영사 확인: {운영기관} {연락처}"로 안내한다. 서버 `StationOut.payment`는 확인된 정보가 생기면 채우는 자리(지금은 null).
- 커넥터 방식은 공공 `chgerType` 코드를 서버 `CONNECTOR_MAP`으로 바꾼다. ⚠️ 코드표는 활용가이드로 최종 확인 필요.

- **운전자 경험 개편(2026-10-25 2차, 구현·에뮬레이터 확인)** — 원칙: 확인된 기능만 필수, 모르는 건 있으면 쓰는 기능, 운전자는 평소대로.
  - **내비 목적지 자동 감지**: `NaviPort.destination()`이 바뀌면 `RealGraph.onDestination`이 판단한다. 목적지가 충전소면 F3에 맡기고, 이미 충전소가 경유지로 들어 있으면 생략, 아니면 `/v1/stations/along-route`(내비 남은 거리 `remainDistance`를 `roadKm`으로) → `shouldShowRouteCard`(feature:warning)가 true일 때만 카드. 충전 없이 갈 수 있으면 조용히 있는다. 길안내 재시작 때 내비가 잠깐 '목적지 없음'을 보내므로 마지막 목적지를 잊지 않는다.
  - **카드**: 주차 중엔 `RoutePanel`([들렀다 가기]=경유지만 추가, [괜찮아요]), 주행 중엔 `DrivingScreen` 경로 카드 — 패널에 답하지 않고 **출발한 순간부터 15초**, 같은 카드는 한 번만. 우선순위: 경고 > 출발 확인 > 경로 카드. '괜찮아요' 2번 연속이면 정말 못 갈 때만 띄움.
  - **검색창 = 충전소·지역 찾기**: 우리 DB(이름·주소, 낱말 AND)만. 결과를 고르면 그 주변 충전소를 보여주고 상세 카드를 연다. 충전소가 안 나오면 [내비에서 '…' 목적지 찾기](`NaviPort.openSearch` → NaviHelper `requestNavigationScreen(SearchPlace)`, 에뮬레이터 미확인).
  - **충전소 짝짓기**(`matchStation`): POI ID → 100m 안 하나 → 여럿이면 이름이 겹치는 곳(‘충전소’ 같은 일반 낱말 제외), 애매하면 null(엉뚱한 경고 방지).
  - **에뮬레이터 내비**: 부팅 직후엔 모든 요청에 "navi not ready". **내비 앱을 한 번 앞으로 띄우면 준비**되고, 그 뒤엔 목적지·경유지 읽기와 실제 경로 안내(경유지 포함)가 동작한다(10/25 확인).
- **글레오 AI 연동(Gleo Interface, 2026-10-25 구현)**: `app/src/real/assets/schema.json`(기능 3개, component=service) + `GleoActionReceiver`(액션 = 기능 이름, `ACTION_PARAM` JSON) → `GleoActions` → `APP_ACTION_RESPONSE`(`functionName`, `status` success/asking/fail, 영어 `message`, `data`) 6초 안에.
  - `CHECK_CHARGERS`(scope route/nearby/destination, min_kw, free_parking, connector) / `ADD_CHARGER_STOP`(방금 추천한 곳, 10분) / `REPORT_CHARGER_PROBLEM`(problem, charger_number). 케이블 짧음·느림은 리뷰 태그, 나머지는 실패 기록(같은 충전소 2시간 안이면 새로 올리지 않고 이유·번호를 고침).
  - 버튼과 같은 기능을 부른다(입력 방법이 하나 더 있을 뿐). 디버그 빌드에 "글레오 흉내" 버튼 3개(같은 형식의 브로드캐스트).
  - **에뮬레이터 확인**: 글레오 에이전트(`[GAA]`, `ai.pleos.gleoai.model.provider`)가 부팅 때 우리 `schema.json`을 읽었고, 우리 `APP_ACTION_RESPONSE`를 받아 해석했다(글레오가 먼저 부른 액션이 아니라 Sleeping 상태라 거절). 실제 음성 호출과 승인 조건은 미확인.

- **지역 검색 + 가는 길 충전소(2026-10-25 구현·에뮬레이터 확인)**: 목적지를 고르면 차의 현재 위치·남은 주행 가능 거리·SOC를 서버 `/v1/stations/along-route`에 보내고, 경로 위 믿을 만한 급속 충전소를 받는다. 기본 내비도 주행 가능 거리를 넘으면 충전소를 넣어 주지만, 우리는 **실패 기록이 많은 곳(RED)·운영 중지만 있는 곳을 빼고** 판정 색과 도착 배터리로 고르게 하는 것이 차이다. [들렀다 가기] = `setDestination(목적지)` → 1.5초 뒤 `addWaypoint(충전소)`. 에뮬레이터 기본 내비는 경로를 그리지 않아(기존 확인 사항) SDK 호출 성공까지만 확인.

- 상태 색: GREEN=초록, YELLOW=노랑, RED=빨강, GRAY=회색. 다른 곳에 색을 쓰지 않는다.
- 주행 제한은 `DrivingStatePort.isParked()`로 판단하고, 제한 중에는 키보드·긴 글·목록 스크롤을 숨긴다(Pleos 주행 규제 가이드).
- **지도 + 기본 내비 혼합(2026-10-24 결정)**: 주차·출발 전에는 우리 지도에서 판정 색을 보고 충전소를 고르고, 길안내는 Pleos 기본 내비가 한다. 주행 중에는 기본 내비만 쓰고 우리 앱은 목적지를 받아 경고한다.
- 지도 타일은 지금 osmdroid + OpenStreetMap(키·구글 서비스 불필요)이다. OSM 공용 타일은 개발·시연용이고, 출시 전에는 이용 허락된 상용 타일(카카오·네이버 등)로 바꾼다. 외부 지도 SDK 사용 가능 여부는 Pleos 심사 기준 확인 필요.
- **주행 중 화면(2026-10-24 구현·에뮬레이터 확인)**
  - DO(Distraction Optimization)를 선언하지 않은 앱은 주행 중 Pleos가 화면 전체를 "주행 중에는 사용할 수 없습니다"로 막는다(Pleos 「지역별 주행 규제 정책」: DO 미선언 시 Drive = 사용 불가).
  - `MainActivity`에 `<meta-data android:name="distractionOptimized" android:value="true" />`를 넣으면 에뮬레이터에서 D 기어에도 우리 화면이 보인다(car_service `DO: true`, UxR 80 = NO_SETUP 64 + NO_VIDEO 16).
  - 주행 여부는 기어가 아니라 Pleos 「주행 규제 개발 가이드」대로 `CarUxRestrictionsManager`(`isRequiresDistractionOptimization`)로 판단한다 → `adapter:android`의 `CarUxRestrictionsAdapter`(`useLibrary("android.car")`).
  - 주행 중에는 `DrivingScreen`만 보인다: 한 번에 정보 하나(경고가 있으면 경고, 없으면 상태 한 줄), 버튼 최대 2개·높이 96dp·글자 28~40sp, 지도·목록·스크롤·애니메이션·키보드 없음, 긴 글은 한 줄로 자름. "충전이 안 되나요?" 질문은 주행 중 띄우지 않고 정차 후 AskSheet로 보인다. 예외는 출발 직후 실패 확인 카드(45초, 버튼 2개, §4.7 T23~T25).
  - 주행 중 [바꾸기]를 누르면 `addWaypoint`가 실행되고 Pleos 내비가 앞으로 나와 새 경로를 안내한다(확인).
  - 근거: Pleos 「앱 카테고리」에서 **Mobility Services** 카테고리는 주행 중 "전기차 충전소 찾기 및 경유지 설정"을 제한적으로 허용할 수 있다고 명시. 우리 Pleos 프로젝트 카테고리는 지금 "지도/내비게이션"이므로 **Mobility Services로 바꾸는 것을 검토**한다.
  - ⚠️ 실제 출시 차량에서도 개발 빌드의 DO 선언이 그대로 인정되는지, 심사 조건이 무엇인지는 10/28 설명회에서 확인한다.

### 4.11 음성 (`VoicePort`)

| 질문 | YES로 분류 | WAIT | NO | SWITCH | KEEP |
| --- | --- | --- | --- | --- | --- |
| 충전이 안 되나요? | 응, 네, 안 돼, 고장 | 아직, 잠깐, 연결 중 | 아니, 돼 | — | — |
| 아까 충전 안 됐나요? | 응, 네, 안 됐어 | — | 아니, 됐어 | — | — |
| 바꿀까요? | — | — | — | 응, 바꿔, 바꿔줘 | 아니, 그대로 |

- 인식 실패·타임아웃은 `null`(= 무응답). 무응답은 절대 FAIL로 기록하지 않는다.
- 화면 버튼은 항상 함께 띄운다(음성이 안 되는 환경 대비).

### 4.12 로컬 저장 (`core:data`)

| 저장소 | 내용 |
| --- | --- |
| DataStore `settings` | `deviceId`, `consentGiven`, `voiceEnabled` |
| DataStore `snapshot` | `ArrivalSnapshot?` (시동 꺼짐 대비, 상태 머신 Effect로만 쓰기) |
| Room `upload_queue` | 업로드 실패한 `ChargeEvent`/리뷰. 네트워크 복구 시 재시도(지수 백오프, 최대 24h) |
| Room `station_cache` | `Station` + 마지막 `StationStatus`, TTL 24h |
| Room `session_log` | 최근 세션 20건 (홈 화면 '최근 기록') |

### 4.13 권한 (AndroidManifest)

```xml
<uses-permission android:name="pleos.car.permission.CAR_ENERGY" />        <!-- EvBattery SOC -->
<uses-permission android:name="pleos.car.permission.CAR_DRIVE" />         <!-- Odometer 남은 주행 가능 거리 -->
<uses-permission android:name="pleos.car.permission.NAVI_ROUTE" />        <!-- 목적지·이벤트 조회 -->
<uses-permission android:name="pleos.car.permission.NAVI_ROUTE_SEARCH" /> <!-- 경유지 추가 -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### 4.14 어댑터 매핑 (`adapter:*`)

| 포트 | real flavor 구현 | 사용하는 실제 API | demo flavor 구현 |
| --- | --- | --- | --- |
| VehiclePort | `PleosVehicleAdapter` ✅ | `Vehicle(context).initialize()`, `getEvBattery()` → `registerEvBatteryStateOfCharge(EvBatteryStateOfChargeListener)`, `getEvBatteryStateOfCharge(onComplete, onFailed)` | `FakeVehicle` |
| DrivingStatePort | `CarUxRestrictionsAdapter` ✅ | Android `CarUxRestrictionsManager.registerListener` → `isRequiresDistractionOptimization` (Pleos 주행 규제 개발 가이드) | `FakeDrivingState` |
| NaviPort | `PleosNaviAdapter` ✅ | `NaviHelper.initialize(onSuccess, onError)`, 리스너 `onDestinationInfo/onWayPointInfo/onDestinationArrived/onWayPointArrived/onRouteStarted/onRoutingCompleted`, 요청 `getDestinationInfo/getWaypointInfo`, `requestRoute(RouteInfo)`, `addWaypoint(RequestWaypointInfo)` | `FakeNavi` |
| LocationPort | `PleosNaviAdapter` ✅ (NaviHelper `onCurrentLocationInfo`) | `getCurrentLocationInfo()` | `FakeLocation` |
| VoicePort | `AndroidVoiceAdapter` | `android.speech.tts.TextToSpeech`, `SpeechRecognizer` (ko-KR) | `FakeVoice`(시나리오의 답 사용) |
| ChargeCheckApiPort | `RemoteApiAdapter` | Retrofit → §5 API | `RemoteApiAdapter`(시연용 서버) 또는 `FakeApi` |

- 2026-10-24 Pleos 에뮬레이터 + Virtual Console에서 확인한 사실 (Vehicle 2.3.2, NaviHelper 3.0.5):
  - 권한: SOC는 `pleos.car.permission.CAR_ENERGY`, 기어는 `CAR_DRIVE`.
  - Virtual Console(`adb forward tcp:8088 tcp:8088` → `http://127.0.0.1:8088/`)의 Battery SOC 슬라이더를 바꾸면 앱 리스너에 수 초 안에 `onEvBatteryStateOfChargeUpdated`가 온다. 등록 직후 현재 값도 한 번 온다.
  - 목표 충전량을 **읽는** API는 없다(`setEvChargePercentLimit`·지원 범위 조회만 있음) → `chargeTargetPct()`는 null, real flavor에서 F5 충전 완료 알림은 아직 동작하지 않는다.
  - 길안내가 없으면 `onDestinationInfo`가 이름 "" · 좌표 (0, 0)으로 온다(무시).
  - 에뮬레이터에서는 안내 시작 때 `onDestinationChanged`가 오지 않고 `onRouteStarted`·`onRouteEnded` 등이 온다. 그래서 이 이벤트들과 10초 주기 조회로 목적지를 다시 읽는다.
  - `requestRoute`로 넘긴 POI ID(`ME000007` 등)가 `onDestinationInfo.poiId`·경유지 `poiId`로 그대로 돌아온다 → 충전소 판별이 정확하다.
  - 에뮬레이터 내비는 실제로 달리지 않으므로 도착 이벤트는 재현하지 못했다. debug 빌드의 "개발용: 목적지 도착 처리" 버튼으로 대신한다.
- `getEvChargeSwitchState`는 사용하지 않는다. AAOS 표준의 `EV_CHARGE_SWITCH`는 "사용자가 요청한 충전 시작·중지 설정"이며 실제 충전 상태가 아니다.
- 표준 AAOS `EV_CHARGE_STATE`·`EV_CHARGE_PORT_CONNECTED`를 일반 앱이 읽을 수 있다면 `VehiclePort`에 `chargeState()`를 추가해 정체 판정을 보강한다. ⚠️ UNVERIFIED
- Pleos TTS/STT/FusedLocation SDK가 열리면 해당 어댑터만 교체한다.

### 4.15 시나리오 재생 (demo)

시나리오는 `adapter/fake/.../ScenarioPlayer.kt`의 `DemoScenarios`에 Kotlin으로 정의한다(2026-10-24 구현. JSON 파일 방식은 시나리오를 앱 밖에서 바꿔야 할 때 다시 검토).

```kotlin
Scenario(
    id = "live_fail_then_switch",
    station = gangnamFast, speed = 60, targetPct = 80,
    steps = listOf(
        Step.RouteTargetStep(0), Step.Arrive(m(1)), Step.Soc(m(1), 42),
        Step.Answer(m(20), VoiceIntent.YES),          // 화면 버튼을 먼저 누르면 무시됨
        Step.Move(m(23), 300), Step.RouteTargetStep(m(24), yeoksamFast),
    ),
    verdicts = mapOf(yeoksamFast.id to (Verdict.GREEN to ReasonCode.RECENT_SUCCESS)),  // FakeApi 판정
)
```

- 단계 종류: `RouteTargetStep`(내비 목적지), `Arrive`, `Soc`, `Move`(충전소에서 m만큼 이동), `Power`(시동 끔·켬), `Answer`(FakeVoice 자리).
- `Power`와 `Answer`는 Fake 포트 밖의 일이라 `ScenarioPlayer`가 `Control` 신호로 내보내고, app 모듈이 `SessionCoordinator.appStopped()/appResumed()/answer()`와 `ArrivalWarningCoordinator.choose()`에 연결한다. adapter가 feature를 import하지 않기 위해서다.
- 시동이 꺼진 동안(`running = false`) 들어오는 포트 이벤트는 버리고, 다시 켤 때 `VehiclePort.currentSoc()`로 RETRO 비교를 한다.

필수 시나리오 6개(모두 구현, 에뮬레이터 재생 확인): `live_success`, `live_fail_then_switch`, `live_wait_then_success`, `retro_success`, `retro_fail`, `warning_red_switch`.
`t`는 시뮬레이션 초, `speed`는 배속(DevMenu에서 ×30·×60·×120·×240으로 바꿀 수 있음). `ScenarioPlayer`가 `FakeClock`과 각 Fake 포트에 값을 흘려보낸다.

---

## 5. 서버 (`server/`)

### 5.1 기술 스택

Python 3.12, FastAPI, Pydantic v2, SQLAlchemy 2 + Alembic, PostgreSQL 16, APScheduler(동기화 작업), httpx, pytest. 배포는 Docker 1개 컨테이너 + 관리형 PostgreSQL.

- 2026-10-24 구현 메모: 개발 PC에 Docker가 없어 로컬은 **SQLite**(`DATABASE_URL=sqlite:///./chargecheck.db`)로 돌린다. 테이블은 SQLAlchemy로 PostgreSQL·SQLite 둘 다 되게 쓰고(배열 대신 JSON), 배포 때 `DATABASE_URL`만 PostgreSQL로 바꾼다. 동기화 주기 작업은 APScheduler 대신 FastAPI lifespan의 asyncio 루프로 시작했다(의존성 최소화). Alembic은 첫 배포 전에 붙인다.

```text
server/
├─ app/
│  ├─ main.py               # FastAPI 앱, 라우터 등록
│  ├─ config.py             # 환경변수 (DATABASE_URL, PUBLIC_API_KEY, ...)
│  ├─ db.py                 # 엔진, 세션
│  ├─ models.py             # SQLAlchemy 테이블
│  ├─ schemas.py            # Pydantic 요청/응답
│  ├─ routers/{events,stations,reviews}.py
│  ├─ services/verdict.py   # 순수 함수 compute_verdict()
│  ├─ services/geo.py       # 거리 계산(haversine)
│  └─ jobs/public_sync.py   # 한국환경공단 API 동기화
├─ alembic/
├─ tests/
└─ Dockerfile
```

### 5.2 REST API

모든 요청 헤더: `X-Device-Id: <uuid>`, `X-App-Version: 1.0.0`. 응답은 JSON, 시각은 ISO-8601 UTC.

| 메서드 | 경로 | 요청 | 응답 |
| --- | --- | --- | --- |
| POST | `/v1/events` | `ChargeEventIn` | `202 {"accepted": true}` / 중복이면 `200 {"accepted": false, "reason": "DUPLICATE"}` |
| GET | `/v1/stations/{stationId}/status` | — | `StationStatusOut` |
| GET | `/v1/stations/nearby?lat=&lon=&radius=500` | — | `StationOut[]` (최대 30개, 거리순) |
| GET | `/v1/stations/{stationId}/alternatives?lat=&lon=` | — | `{station: StationOut, status: StationStatusOut, distanceM: int}[]` (GREEN 우선, 최대 3개, 5km 이내) |
| POST | `/v1/reviews` | `{stationId, tags: ReviewTag[]}` | `202` |
| PATCH | `/v1/events/{eventId}` | `{"chargerId": "02"}` | `200` — 실패 기록 뒤 운전자가 고른 충전기 번호를 붙인다(같은 기기만) |
| GET | `/v1/places/search?q=&lat=&lon=` | — | `PlaceOut[]` — **충전소·지역 찾기(우리 DB만, 10/25 개편)**: 낱말마다 이름이나 주소에 들어 있는 충전소 최대 10곳(가까운 순). `kind`는 STATION. 일반 목적지는 차의 내비가 담당 |
| GET | `/v1/stations/along-route?fromLat&fromLon&toLat&toLon&rangeKm&soc&fastOnly=true&roadKm=` | — | `AlongRouteOut` (아래). `roadKm` = 내비가 알려 준 남은 도로 거리 |
| GET | `/healthz` | — | `200 {"ok": true}` |

**가는 길 충전소 (2026-10-25 추가 → 같은 날 외부 경로 서버 제거, `services/along.py`)**
1. 외부 지도·경로 서버를 쓰지 않는다(`source: CORRIDOR`). 실제 길은 차의 내비가 짠다.
2. 출발지~목적지 직선을 2km마다 찍어 띠(직선 20km 미만 1.5km, 이상 3km) 안 충전소를 모으고, 직선에 투영한 거리를 도로 거리로 환산해 `alongKm`으로 쓴다(`roadKm`이 있으면 그 비율, 없으면 × 1.3).
3. 판정 RED, 쓸 수 있는 충전기 없음(운영 중지·RED만), 버스 전용, (`fastOnly`) 급속 없음은 뺀다. 5km 구간마다 가장 좋은 곳 하나(갈 수 있음 → 판정 색 → 빈 충전기 있음 → 정상 충전기 수 → 이탈 거리).
4. `reachable` = `alongKm ≤ rangeKm × 0.85`, `arrivalSoc` = `soc × (1 − alongKm / rangeKm)`, `needsCharge` = 경로 길이 > `rangeKm × 0.85`.
5. 충전이 필요하면 `recommendedId` = 갈 수 있는 곳 중 GREEN 우선, 빈 충전기 있는 곳 우선, 그다음 가장 먼 곳(한 번 충전으로 최대한 멀리).

**실패 이유 (2026-10-25)**: `ChargeEventIn.reason` = NOT_CHARGING / BROKEN / PAYMENT / BLOCKED / OTHER(선택). `PATCH /v1/events/{id}`는 `chargerId`·`reason` 중 하나 이상을 붙이거나 고친다. 판정: **BLOCKED(일반차 주차 막힘)는 실패 수에서 빼고** `StationStatusOut.notice`("20분 전 일반차 주차 막힘 제보", 60분)로만 알린다. PAYMENT는 실패로 세되 문구에 "결제 실패 제보 포함". 기존 DB에는 시작할 때 `ensure_columns`가 `reason` 칼럼을 더한다.

```jsonc
// AlongRouteOut
{ "routeKm": 9.5, "source": "ROAD", "rangeKm": 8.0, "needsCharge": true,
  "route": [[37.4979, 127.0276], ...],
  "candidates": [ { "station": StationOut, "status": StationStatusOut, "alongKm": 0.9, "detourM": 168, "reachable": true, "arrivalSoc": 7 } ],
  "recommendedId": "..." }
```

**충전기 단위 기록 (2026-10-24 추가)**: 충전소에는 충전기가 여러 대 있다. 차는 SOC만 알 뿐 몇 번 충전기에 꽂았는지 모르므로, 실패 기록은 충전기 번호 없이 바로 올리고, 주차 중에 "몇 번 충전기였나요?"(건너뛰기 가능)에 답하면 `PATCH`로 번호를 붙인다. 정상 기록은 묻지 않는다. `chargerId`는 공공 API의 `chgerId`(예: "01", "02")와 같다.

```jsonc
// ChargeEventIn
{
  "eventId": "6f1c...-uuid",
  "stationId": "ME000001",
  "result": "SUCCESS",
  "mode": "LIVE",              // LIVE / RETRO / MANUAL(상세 카드에서 직접 표시)
  "socStart": 42,
  "socEnd": 47,
  "minutes": 6,
  "occurredAt": "2026-11-10T08:15:00Z",
  "chargerId": null            // 선택. 보통은 나중에 PATCH로 붙는다
}

// StationStatusOut
{
  "stationId": "ME000001",
  "verdict": "YELLOW",
  "reasonCode": "SOME_CHARGERS_FAIL",
  "message": "6대 중 1대에 최근 실패 기록 (5대 정상 또는 기록 없음)",
  "lastSuccessAt": "2026-11-10T05:02:00Z",
  "recentFailCount": 2,
  "publicAvailable": 3,
  "publicTotal": 6,
  "okCount": 5,                // 실패 판정·운영중지가 아닌 충전기 수
  "chargers": [
    { "chargerId": "01", "chargerType": "FAST", "connectors": ["DC콤보"], "outputKw": 100, "publicState": "AVAILABLE", "verdict": "GREEN", "recentFailCount": 0, "lastSuccessAt": "2026-11-10T05:02:00Z" },
    { "chargerId": "02", "chargerType": "FAST", "publicState": "AVAILABLE", "verdict": "RED",   "recentFailCount": 2, "lastSuccessAt": null }
  ],
  "topTags": ["CABLE_SHORT"]
}

// StationOut (nearby 응답의 각 항목은 이것 + distanceM + status)
{
  "stationId": "ME000003", "name": "역삼 급속충전소", "lat": 37.5006, "lon": 127.0364, "chargerType": "FAST",
  "address": "서울특별시 강남구 테헤란로 152",
  "operator": "운영기관명", "operatorCall": "1600-0000",
  "useTime": "24시간 이용가능", "parkingFree": false, "limitDetail": "",
  "payment": null              // 공공 데이터에 없음
}
```

### 5.3 데이터베이스

```mermaid
erDiagram
  STATION ||--o{ CHARGER : has
  STATION ||--o{ CHARGE_EVENT : receives
  STATION ||--o{ REVIEW : receives
  STATION {
    text stat_id PK
    text name
    text address
    float lat
    float lon
    text operator
    text charger_type
    timestamptz updated_at
  }
  CHARGER {
    text stat_id FK
    text chger_id
    text chger_type
    text public_stat
    timestamptz stat_upd_dt
  }
  CHARGE_EVENT {
    uuid event_id PK
    text device_hash
    text stat_id FK
    text result
    text mode
    int soc_start
    int soc_end
    int minutes
    timestamptz occurred_at
    timestamptz received_at
    text chger_id "NULL 가능"
  }
  REVIEW {
    bigserial id PK
    text device_hash
    text stat_id FK
    text[] tags
    timestamptz created_at
  }
```

- `CHARGER` 기본키: (`stat_id`, `chger_id`).
- `device_hash` = SHA-256(`X-Device-Id` + 서버 솔트). 원본 기기 ID는 저장하지 않는다.
- 인덱스: `charge_event(stat_id, occurred_at desc)`, `station(lat, lon)`.
- 좌표·이동 경로는 저장하지 않는다(개인위치정보 최소화).

### 5.4 판정 규칙 `compute_verdict` (서버 단일 구현)

입력: 최근 24시간 이벤트(시간순), 공공 API 충전기 상태 목록, 현재 시각. **충전기 단위로 먼저 판정하고, 충전소는 그 결과를 모아서 정한다(2026-10-24 개정).**

```text
[충전기 판정]  (그 충전기 번호가 붙은 이벤트 + 충전기 번호 없는 SUCCESS)
1. if public == OUT_OF_SERVICE:                     → RED,    PUBLIC_OUT_OF_SERVICE
2. lastSuccess = 이 충전기 SUCCESS 또는 번호 없는 SUCCESS 중 가장 최근
3. failsAfter  = lastSuccess 이후 이 충전기 FAIL의 서로 다른 device_hash 수
4. if failsAfter ≥ 2: RED (public AVAILABLE이면 PUBLIC_SAYS_OK_BUT_FAILS, 아니면 MULTI_FAIL)
5. if failsAfter == 1:                              → YELLOW, FAIL_AFTER_SUCCESS
6. if lastSuccess within 3h:                        → GREEN,  RECENT_SUCCESS
7. else                                             → GRAY,   NO_RECENT_DATA

[충전소 판정]
a. total = 공공 API 충전기 수, bad = RED 충전기 수, unknownFails = 충전소 마지막 SUCCESS 이후 번호 없는 FAIL의 서로 다른 기기 수
b. okCount = RED도 아니고 OUT_OF_SERVICE도 아닌 충전기 수 (YELLOW는 아직 정상으로 셈) − unknownFails (번호 없는 실패는 보수적으로 1대씩 뺌, 0 미만은 0)
c. if total > 0 and 모든 충전기가 OUT_OF_SERVICE:  → RED,    PUBLIC_OUT_OF_SERVICE
d. if bad + unknownFails ≥ max(total, 2):            → RED,    (공공 사용가능 > 0이면 PUBLIC_SAYS_OK_BUT_FAILS, 아니면 MULTI_FAIL)
e. if bad > 0 or unknownFails > 0 or YELLOW 충전기 있음:
      total ≥ 2 → YELLOW, SOME_CHARGERS_FAIL  ("{total}대 중 {k}대에 최근 실패 기록")
      total ≤ 1 → YELLOW, FAIL_AFTER_SUCCESS
f. if 충전소 lastSuccess within 3h:                 → GREEN,  RECENT_SUCCESS
g. else                                             → GRAY,   NO_RECENT_DATA
```

- 핵심: **충전기 한 대 고장으로 충전소 전체를 빨강으로 만들지 않는다.** 빨강은 실패가 모든 충전기를 덮을 만큼 쌓였을 때만.
- 도착 전 경고(F3)는 RED, 또는 YELLOW 중 `SOME_CHARGERS_FAIL`이 아닌 것에만 띄운다. 일부 충전기만 실패면 경고로 운전을 방해하지 않고 카드에 "6대 중 1대 실패 기록"만 보인다.

| reasonCode | 운전자에게 보이는 문장 |
| --- | --- |
| RECENT_SUCCESS | "{n}분 전 실제로 충전됨" |
| FAIL_AFTER_SUCCESS | "최근 충전 실패 제보 1건" |
| SOME_CHARGERS_FAIL | "{total}대 중 {k}대에 최근 실패 기록" |
| MULTI_FAIL | "최근 여러 차가 충전에 실패했어요" |
| PUBLIC_SAYS_OK_BUT_FAILS | "사용 가능으로 표시되지만 최근 실제로는 충전이 안 됐어요" |
| PUBLIC_OUT_OF_SERVICE | "운영사 점검·운영 중지" |
| NO_RECENT_DATA | "운영사 기준 사용 가능 {a}/{t} (실제 확인 기록 없음)" |

**중복·악용 방지**: 같은 `device_hash` + `stat_id`는 `DEDUP_WINDOW_H`(2h) 안에 이벤트 1건만 인정. 한 기기의 FAIL이 하루 10건을 넘으면 그날 FAIL은 판정에서 제외.

### 5.5 공공 데이터 동기화 (`jobs/public_sync.py`)

| 작업 | 주기 | 호출 | 비고 |
| --- | --- | --- | --- |
| 충전소·충전기 목록 전체 | 매일 04:00 | `getChargerInfo` | ✅ 2026-10-24 전국 실데이터로 확인(100,096곳·521,372대, 약 5분, 페이지 단위 일괄 저장). `PUBLIC_ZCODES=ALL`이면 지역 코드 없이 전국 |
| 상태 변경분 | 5분마다 | `GET https://apis.data.go.kr/B552584/EvCharger/getChargerStatus?serviceKey=...&period=5&dataType=JSON` | `stat`, `statUpdDt` 확인됨 |

- 상태 코드(`stat`)·충전기 타입(`chgerType`)은 「OpenAPI활용가이드 v1.25」 §3.2·§3.3으로 확정했다(2026-10-24). stat: 0 알수없음, 1 통신이상, 2 충전대기, 3 충전중, 4 운영중지, 5 점검중, 6 예약중, 9 상태미확인. chgerType: 01 DC차데모 … 10 DC콤보+NACS, 11 DC콤보2(버스전용). 결제 수단 필드는 없다. 표는 `config.py`의 `PUBLIC_STAT_MAP`·`CONNECTOR_MAP`.
- 실데이터에서 찾은 처리: 버스전용(11) 충전기는 판정·지도에서 제외(서울 922대), 좌표가 자리표시값((33.0,125.0)·(37.5,127.0) 등)인 충전소는 저장하지 않음(21곳), 아파트 충전소는 한 곳에 100대까지 있음(충전기 선택 목록은 스크롤).
- 서울 실데이터 분포(2026-10-24): 충전대기 55,589 · 충전중 15,232 · 상태미확인 3,599 · 통신이상 695 · 점검중 309 · 운영중지 46. 완속(02) 69,399대, 출력 7kW 51,715대·3kW 15,793대.
- 호출 실패 시 마지막 값을 유지하고 `stat_upd_dt`로 신선도를 판단한다.

---

## 6. 시뮬레이터 (`tools/simulator/`)

- `simulate.py --server URL --station ME000001 --devices 5 --pattern broken|healthy|recovered`
- `broken`: 5대 중 3대가 FAIL → 해당 충전소 RED(PUBLIC_SAYS_OK_BUT_FAILS) 되는지 확인
- `recovered`: FAIL 2건 뒤 SUCCESS 1건 → GREEN 복귀 확인
- 시연 영상에서 "다른 차의 기록이 내 차 경고로 뜨는 장면"을 만들 때 사용

---

## 7. 핵심 흐름 시퀀스

### 7.1 LIVE 성공

```mermaid
sequenceDiagram
  participant Car as VehiclePort
  participant S as SessionCoordinator
  participant M as ChargeSessionMachine
  participant API as Server
  Car->>S: soc(42) / 충전소 50m 내 정차 5분
  S->>M: Tick → Monitoring (SaveSnapshot)
  Car->>S: soc(43)
  S->>M: Soc(43)
  M-->>S: Charging, Upload(SUCCESS, LIVE), ShowToast
  S->>API: POST /v1/events
```

### 7.2 RETRO 실패 (충전 중 차량이 꺼진 경우)

```mermaid
sequenceDiagram
  participant S as SessionCoordinator
  participant M as ChargeSessionMachine
  participant D as SnapshotStore
  participant V as VoicePort
  participant API as Server
  Note over S: 도착 → Monitoring, 스냅샷(SOC 42%) 저장
  S->>M: AppStopped → Suspended
  Note over S: (차량 꺼짐 40분)
  S->>D: 스냅샷 읽기
  S->>M: AppResumed(soc=42, now)
  M-->>S: AskingRetro, Ask("아까 ○○에서 충전이 안 됐나요?")
  S->>V: ask(...)
  V-->>S: YES
  S->>M: Answer(YES)
  M-->>S: Failed, Upload(FAIL, RETRO), ClearSnapshot
  S->>API: POST /v1/events
```

---

## 8. 구현 순서 (마일스톤)

**진행 순서 (2026-10-24 확정): M0 → M1 → M3 → M5 → M2 → M4 → M6 → M7**

- 가장 불확실한 Pleos SDK 연결(M5)을 먼저 확인하고, 그 결과로 서버(M2) 데이터를 정한 뒤, 데이터가 굳은 다음 화면(M4)을 한 번에 만든다. UI를 먼저 만들면 데이터가 바뀔 때마다 화면을 다시 고치게 되기 때문이다.
- M5·M2는 마감일까지 안 되면 가짜 데이터·가짜 서버로 넘어가 M4를 시작한다(1차 시연은 demo flavor로도 가능).
- 10/28 설명회에서 "차량 앱의 외부 서버 통신 제약"을 확인한 뒤 M2를 시작한다.
- 차량이 없으므로 M5는 Pleos Connect Emulator + Virtual Console(SOC 슬라이더, 기어)로 실제 SDK 연결을 검증한다.
- 접수양식(기획서, 필수 제출물)은 순서와 별개로 11/10까지 병행한다.

| 순서 | 단계 | 기간(목표) | 산출물 | 완료 확인 | 상태 |
| --- | --- | --- | --- | --- | --- |
| 1 | M0 | 10/24 | Pleos 가입·프로젝트, 공공 API 키, 저장소 생성, Gradle 뼈대(모든 모듈 빈 껍데기) | `./gradlew build` 성공 | ✅ |
| 2 | M1 | 10/24 | `core:model/port/config`, `ChargeSessionMachine` + 전이표 T1–T22 단위 테스트 | 테스트 39개 통과 | ✅ |
| 3 | M3 | ~10/27 | `adapter:fake` + `ScenarioPlayer` + `DevMenuScreen`, `SessionCoordinator` | 시나리오 6개가 에뮬레이터에서 재생 | ✅ 6/6 에뮬레이터 확인 (F3 도착 전 경고 기본 동작 포함) |
| 4 | M5 | 10/27–11/01 | `adapter:pleos`(SOC, NaviHelper) 에뮬레이터 연동, Virtual Console로 SOC 변경 확인 | real flavor에서 LIVE 성공 기록 | ✅ Virtual Console SOC로 LIVE 정상 기록 확인 + 지도 화면·내비 목적지 연동 (도착은 개발용 버튼) + 주행 중 화면(DO 선언, CarUxRestrictions) |
| 5 | M2 | 11/01–11/06 | 서버: `/v1/events`, `/status`, `compute_verdict` + 테스트, 공공 API 동기화(픽스처로 먼저) | pytest 통과, Docker 기동 | ✅ pytest 29개, 충전기 단위 판정, 앱 연결·에뮬레이터 확인 (Docker·실제 공공 키 동기화는 미확인) |
| 6 | M4 | 11/06–11/12 | UI: Home, ChargingScreen, AskSheet, WarningCard, Consent | 스크린샷 | ⏳ |
| 7 | M6 | 11/12–11/14 | 시뮬레이터 + 시연 영상, GitHub README, `apk/팀명_ChargeCheck_release.apk` | 1차 제출(마감 11/15) | ⏳ |
| 8 | M7 | 11/16–12/02 | F3 대체 충전소, F4, 실패 재시도, 안정화 | 2차 PT용 동작 앱 | ⏳ |

---

## 9. 테스트 전략

- **상태 머신**: 전이표 T1–T22 각각 1개 이상 테스트(`chargecheck/android/feature/session/src/test/.../ChargeSessionMachineTest.kt`, 39개 통과).
- **코디네이터**: `SessionCoordinatorTest`(포트 → 상태 머신 → Effect, LIVE·RETRO·재출발 5개), `ArrivalWarningCoordinatorTest`(경고·바꾸기·그대로 3개). 전체 47개 통과. 상태 머신은 순수 함수라 고정된 `Instant`만으로 시간 경과를 표현한다.
- **판정 규칙**: `compute_verdict` 표 기반 테스트(규칙 4–9 각각).
- **계약 테스트**: Android `RemoteApiAdapter`와 서버 스키마가 같은 JSON 예시(§5.2)로 직렬화/역직렬화 되는지.
- **시나리오 테스트**: 시나리오 6개를 `demo` flavor 계측 테스트로 자동 실행.
- **수동**: 에뮬레이터 + Virtual Console에서 Energy > Battery SOC 슬라이더, Motion > Gear Selection으로 LIVE 성공/실패 확인.

---

## 10. 확인되지 않은 것 (⚠️ UNVERIFIED 모음)

| 항목 | 영향 | 대안 |
| --- | --- | --- |
| 시동이 꺼진 동안 Pleos 앱이 실행되는지 | LIVE 판정 범위 | RETRO 경로로 커버 (설계 반영됨) |
| ~~`EvBattery` 메서드 시그니처·갱신 빈도~~ | — | ✅ 2026-10-24 확인 (§4.14) |
| 주행 중(D) 우리 화면 표시: 에뮬레이터는 DO 선언으로 ✅. 실차·심사에서 인정 조건 | F3 경고 표시 | 10/28 설명회 질문(카테고리 Mobility Services 변경 포함). 안 되면 알림 |
| 외부 지도 SDK·타일(OSM/카카오/네이버) 사용 허용 여부 | 지도 화면 | 10/28 설명회 질문. 안 되면 지도 없이 목록 화면 |
| 실제 주행 시 `onDestinationArrived` 동작 | 도착 감지 | 에뮬레이터에서 재현 불가. 정차 + 반경(StoppedNearStation)으로 보완 |
| 표준 AAOS `EV_CHARGE_STATE` 읽기 가능 여부 | 감지 속도 | 가능하면 `VehiclePort.chargeState()` 추가 |
| Pleos 에뮬레이터에서 Android TTS/STT/Location 동작 | 음성·위치 | 버튼 UI, NaviHelper 위치 이벤트 |
| ~~공공 API `stat` 코드 의미, `getChargerInfo` 필드~~ | — | ✅ 활용가이드 v1.25 + 서울 실데이터로 확인 (§5.5) |
| 실차 `getRangeRemainingDistance` 단위(에뮬레이터는 미터) | 가는 길 충전소 | 2,000 미만이면 km로 보는 방어 코드. 실차·설명회 확인 |
| 차량 앱의 외부 서버 통신에 대한 Pleos 심사 제약 | 서버 구조 | 10/28 설명회에서 질문 |
| 개인위치정보 관련 법적 절차(실서비스 시) | 사업화 | 좌표 미전송·해시 ID로 최소화, 법률 검토 |

---

## 11. 참고 링크

- Pleos 시작 가이드: https://document.pleos.ai/docs/pleos-only/getting-started
- Vehicle SDK 권한: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission
- NaviHelper 이벤트: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners
- Virtual Console: https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide
- 주행 안전 설계: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline
- 공공 API(충전소 정보): https://www.data.go.kr/data/15076352/openapi.do
- AAOS 차량 속성: https://developer.android.com/reference/android/car/VehiclePropertyIds
- 공모전 제출 방법: https://appdevchallenge.auto-hmg.io/guide/submission
