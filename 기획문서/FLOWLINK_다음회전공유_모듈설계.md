# FLOWLINK 다음 회전 공유 모듈 설계

2026-11-07 · 원본: [Claude 문서](https://claude.ai/artifact/8jpcp9ypxh96Y5xt1EyY7q)

## 개요

FLOWLINK는 내 차의 **다음 회전**을 같은 방향으로 가는 주변 차에 미리 알리고, **방향지시등**과 맞으면 '확정'으로 올려 내비 화면 위 배너로 띄웁니다. 차선 단위 합류 협상은 지금 SDK로 할 수 없어서 범위에서 뺐습니다.

| 판단 | 이 설계에서 다루는 방식 |
| --- | --- |
| 위치·진행 방향 | NaviHelper `CurrentLocationInfo`(위도, 경도, heading)를 그대로 씀 |
| 경로 공유 | 경로 전체 대신 `onTBTInfo`의 첫 회전 하나(종류 + 남은 거리)만 보냄 |
| 목적지 비공개 | 300m 안에 들어온 회전만 보내서 목적지가 드러나지 않음 |
| 방향지시등 | Vehicle `TurnSignal`(OFF / LEFT / RIGHT)로 확정 여부 판단 |
| 차량 간 통신 | 자체 WebSocket 중계 서버. 지연은 '미리 알리기'로 흡수 |
| 주행 중 표시 | 내비 위 상단 배너 하나. `updateOccupiedRegion(Rect)`로 내비에 그 영역을 알려 가리지 않게 함 |
| 내비 자체 화면에 그리기 | SDK에 없음. `CautionPoint`는 내비가 앱에 주는 정보라 앱이 넣을 수 없음 |
| 주변 차량 지도 | 정차 중에만 앱 화면에 표시 |
| 음성 | 기본 꺼짐. 설정에서 켜면 Android `TextToSpeech`로 한 마디 |
| 차선 식별, 센서, V2X | 범위 밖. PT에서 한계로 명시 |

첫 사용 시나리오는 초대 코드로 묶은 2~5대의 **동행 모드**입니다. 참여 차량이 적어도 바로 쓸모가 있기 때문입니다. 익명 주변 공유는 2단계에서 다룹니다.

## 모듈 구성

차지체크와 같은 포트·어댑터 구조를 씁니다. 판단 로직은 전부 순수 Kotlin 모듈에 두어 SDK와 에뮬레이터 없이 테스트합니다. 의존성은 위에서 아래로만 흐르고, core는 SDK를 모릅니다.

```text
:app  (조립만 담당. real / fake flavor가 어떤 어댑터를 꽂을지 고름)
  │
  ▼
[Android 모듈]
  :ui               배너 내용, 정차 지도
  :adapter:pleos    NaviHelper, TurnSignal, 배너
  :adapter:android  TTS(선택), 상단 알림
  :adapter:remote   WebSocket 클라이언트 ─────────────┐
  │                                                  │ WebSocket
  ▼                                                  │
[순수 Kotlin(JVM) 모듈: Android 없이 빌드·테스트]       │
  :feature:intent     내 의도 만들기 · 확신도 판정       │
  :feature:awareness  주변 의도 보관 · 관련성 필터 · 알림 억제
  :feature:convoy     동행 방 만들기·참여, 초대 코드     │
  │                                                  │
  ▼                                                  │
  :core:port   NaviPort, TurnSignalPort, RelayPort,   │
               AlertDisplayPort, SpeechPort, DrivingStatePort
  :core:model  Maneuver, Signal, Intent, Confidence,  │
               IntentPacket, PeerIntent, Alert        │
                                                     ▼
server/relay  FastAPI WebSocket 중계. 격자·방향으로 내보낼 대상만 고름, 저장 없음
```

intent와 awareness 두 모듈이 이 기능의 핵심입니다. 어댑터는 포트를 구현하기만 하고, 기존 FLOWLINK의 `core`(합류 시뮬레이션)는 `:sim`으로 떼어 시연용 가상 차량 공급에만 씁니다.

## 모듈별 상세

모듈 하나는 책임 하나만 맡습니다. 아래 표의 클래스 이름이 그대로 파일 이름이 됩니다.

| 모듈 | 책임 | 주요 클래스 | 입력 → 출력 |
| --- | --- | --- | --- |
| `:core:model` | 도메인 타입. 로직 없음 | `Maneuver`, `Side`, `Signal`, `Confidence`, `Intent`, `IntentPacket`, `PeerIntent`, `Alert` | — |
| `:core:port` | 바깥 세계와의 경계 | `NaviPort`, `TurnSignalPort`, `RelayPort`, `AlertDisplayPort`, `SpeechPort`, `DrivingStatePort`, `ClockPort` | — |
| `:feature:intent` | 내 차의 의도를 만들어 보냄 | `ManeuverMapper`, `IntentResolver`, `IntentPublisher`, `TripId` | TBT + 위치 + 방향지시등 → `IntentPacket` |
| `:feature:awareness` | 받은 의도 중 내게 의미 있는 것만 골라 알림 | `PeerBook`, `RelevanceFilter`, `AlertPolicy`, `AlertPhraser` | `PeerIntent` + 내 위치 → `Alert?` |
| `:feature:convoy` | 동행 방 만들기·참여·나가기 | `ConvoySession`, `InviteCode` | 초대 코드 → 방 채널 |
| `:adapter:pleos` | Pleos SDK를 포트로 번역, 내비 위 배너 | `PleosNaviAdapter`, `PleosTurnSignalAdapter`, `PleosDrivingStateAdapter`, `NaviBannerOverlay` | SDK 콜백 → `Flow`, `Alert` → 배너 |
| `:adapter:android` | 기기 기능 | `AndroidSpeech`(선택, 기본 꺼짐), `HeadsUpFallback`, `SystemClock` | `Alert` → 알림 |
| `:adapter:remote` | 중계 서버 연결 | `WsRelayClient`(OkHttp, 재연결·백오프) | 패킷 ↔ JSON |
| `:adapter:fake` | 에뮬레이터 시연용 가짜 주행 | `ScriptedDrive`, `FakeTurnSignal`, `LoopbackRelay` | 각본 → 포트 |
| `:ui` | Compose 화면 | `BannerView`(배너 내용), `ParkedMapScreen`, `ConvoyScreen` | `Alert`, 참여자 → 화면 |
| `:app` | 조립 | `RealGraph`, `FakeGraph`, `FlowlinkService`(전경 서비스) | — |

핵심 포트는 실제 SDK 시그니처를 보고 정했습니다.

```kotlin
// :core:port
interface NaviPort {
    fun position(): Flow<Fix>              // CurrentLocationInfo(lat, lon, heading)
    fun nextManeuver(): Flow<NextTurn?>    // onTBTInfo(List<TBTInfo>)의 첫 항목
}
interface TurnSignalPort {
    fun signal(): Flow<Signal>             // registerTurnSignalState → OFF / LEFT / RIGHT
}
interface RelayPort {
    fun join(channel: Channel): Flow<PeerIntent>   // Channel = Convoy(code) | Area
    suspend fun send(packet: IntentPacket)
    fun health(): StateFlow<LinkState>
}
interface AlertDisplayPort {               // 주행 중 기본 출력: 내비 위 배너
    fun show(alert: Alert)
    fun clear(id: String)
}
interface SpeechPort { fun say(alert: Alert) }   // 선택. 설정에서 켜야 동작
interface DrivingStatePort { fun isParked(): Flow<Boolean> }
```

- `ManeuverMapper`는 SDK `TurnType` 43개를 앱의 `Maneuver` 8개(LEFT, RIGHT, U_TURN, EXIT_IC, ENTER_IC, KEEP_LEFT, KEEP_RIGHT, OTHER)와 방향 `Side`로 줄입니다. `STRAIGHT`와 `UNKNOWN`은 보내지 않습니다.
- 속도는 SDK에서 바로 받지 못해서 연속된 `Fix` 두 개의 거리·시간으로 어댑터에서 계산합니다.
- 기존 FLOWLINK `Voice.kt`의 반복 억제는 `AlertPolicy`로, `AndroidSpeech.kt`는 `:adapter:android`로, `RelayClient.kt`는 `WsRelayClient`로 옮깁니다.

## 데이터 흐름과 메시지 규격

패킷 하나는 200바이트 안팎이고, 목적지·경로·차량 식별자는 담지 않습니다. 한 차에서 다른 차 화면까지 가는 순서는 아래와 같습니다.

1. `PleosNaviAdapter`가 위치·진행 방향과 다음 회전을, `PleosTurnSignalAdapter`가 방향지시등을 `Flow`로 낸다.
2. `IntentResolver`가 세 흐름을 합쳐 `Intent`와 `Confidence`를 정한다.
3. `IntentPublisher`가 좌표를 반올림하고 1초 주기로, 방향지시등이나 판정이 바뀌면 즉시 `RelayPort.send`.
4. 서버가 같은 채널(동행 방 또는 격자+방향)의 다른 차에 그대로 전달한다. 저장하지 않는다.
5. 받는 쪽 `PeerBook`이 아이디별 최신 패킷만 보관하고 3초가 지나면 버린다.
6. `RelevanceFilter` → `AlertPolicy`를 통과한 것만 `AlertDisplayPort.show`로 내비 위 배너에 뜬다. 음성을 켠 경우에만 `SpeechPort.say`도 호출한다.

### 송신 패킷 `IntentPacket`

| 필드 | 타입 | 예 | 규칙 |
| --- | --- | --- | --- |
| `v` | int | 1 | 규격 버전 |
| `tid` | string | `k3f9a2` | 주행마다 새로 만드는 임시 ID. 차량·계정과 연결 없음 |
| `seq` | long | 1042 | 순서 번호. 낮거나 같으면 받는 쪽이 버림 |
| `lat`, `lon` | double | 37.50012, 127.03681 | 소수점 4자리(약 10m)로 반올림 |
| `hdg` | int | 92 | 진행 방향(도), 5도 단위 |
| `spd` | int | 54 | km/h. 위치 변화로 계산 |
| `mv` | enum? | `RIGHT` | 다음 회전. 300m 밖이면 없음 |
| `dist` | int? | 120 | 회전까지 남은 거리(m), 10m 단위 |
| `sig` | enum | `RIGHT` | OFF / LEFT / RIGHT |
| `conf` | enum | `CONFIRMED` | CONFIRMED / PLANNED / SIGNAL_ONLY |
| `ts` | long | — | 보낸 시각(ms). 받는 쪽 지연 측정에만 씀 |

### 서버 중계 규칙

- 동행 채널: `/ws/convoy/{code}`. 방 안의 다른 차 전부에게 전달합니다. 최대 5대, 코드는 6자리이고 12시간 뒤 만료됩니다.
- 주변 채널: `/ws/area`. 패킷 좌표로 geohash 6자리(약 1.2km × 0.6km) 칸을 구하고, 그 칸과 이웃 8칸에서 진행 방향이 ±30° 안인 차에만 전달합니다.
- 연결당 초당 5개를 넘기면 버립니다. 로그에는 개수와 지연만 남기고 좌표는 남기지 않습니다.
- 차지체크와 같은 FastAPI로 만들고, DB를 쓰지 않아 Neon 전송량에 영향이 없습니다.

## 핵심 로직

내비 배너의 강조 표시는 **확정** 판정이면서, 내 앞쪽 같은 방향에 있고, 아직 알리지 않은 회전일 때만 나갑니다. 세 단계 모두 순수 함수이고, 값은 `FlowlinkConfig` 한 곳에 모읍니다.

### 1. 확신도 판정 `IntentResolver`

| 다음 회전 (300m 안) | 방향지시등 | 판정 | 보내는 것 |
| --- | --- | --- | --- |
| 있음, 방향 같음 | 같은 쪽, 0.5초 이상 유지 | `CONFIRMED` | 회전 + 거리 |
| 있음 | 꺼짐 | `PLANNED` | 회전 + 거리 |
| 없음 또는 직진 | 켜짐, 1초 이상 유지 | `SIGNAL_ONLY` | 방향지시등만 (차선 변경 가능성) |
| 있음 | 반대쪽 | 어긋남 | 회전 빼고 위치만 |
| 없음 | 꺼짐 | — | 위치만 (동행 모드) / 보내지 않음 (주변 모드) |

- 회전의 방향: `TURN_RIGHT`, `TURN_SHARP_R`, `TURN_KEEP_R`, `TURN_EXIT_IC_R` → 오른쪽. U턴은 왼쪽입니다.
- 회전을 지나면(남은 거리가 다시 늘거나 TBT 첫 항목이 바뀜) 판정을 바로 지웁니다.
- 방향지시등 값이 잠깐 튀는 것은 유지 시간(0.5초, 1초)으로 걸러냅니다.

### 2. 관련성 필터 `RelevanceFilter`

상대 위치를 내 진행 방향에 투영해 앞뒤 거리(along)와 옆 거리(cross)를 구합니다.

| 조건 | 동행 모드 | 주변 모드 |
| --- | --- | --- |
| 패킷 나이 | 3초 이하 | 3초 이하 |
| 진행 방향 차이 | 제한 없음 | 20° 이하 |
| 앞뒤 거리 | 0~1,000m 앞 | 30~300m 앞 |
| 옆 거리 | 제한 없음 | 20m 이하 (같은 도로일 가능성) |
| 같은 회전 제외 | 내 다음 회전과 같으면 "따라가세요"로 짧게 | 종류가 같고 지점 차이가 50m 안이면 알리지 않음 |

동행 모드에서 '같은 회전'은 "앞차와 같은 길로 간다"는 뜻이라, 이때는 짧게 따라가라고만 하고 경로가 갈릴 때는 따로 알립니다.

### 3. 알림 억제 `AlertPolicy`

- `CONFIRMED`는 강조 배너(색 + 회전 아이콘)로, `PLANNED`는 회색 일반 배너로 띄웁니다. `SIGNAL_ONLY`는 동행 모드에서만 띄웁니다.
- 같은 차의 같은 회전은 배너를 한 번만 띄웁니다. 키는 `tid + mv + 회전 지점(50m 격자)`입니다. `PLANNED`가 `CONFIRMED`로 올라가면 같은 배너의 모양만 바꿉니다.
- 배너는 한 번에 하나만 띄웁니다. 더 가까운 확정 알림이 오면 교체하고, 4초 뒤나 상대 패킷이 3초 동안 없으면 내립니다.
- 정차 중(`DrivingStatePort.isParked`)에는 배너 대신 앱의 지도 화면에 표시합니다.
- 음성은 기본 꺼짐입니다. 켜면 `CONFIRMED`에만 8초 간격으로 말하고, 오디오 포커스는 `MAY_DUCK`으로 잠깐 가져와 내비 안내를 끊지 않습니다.

### 내비 위 배너 `NaviBannerOverlay`

주행 중 알림은 내비 화면 상단에 얇은 배너로 뜨고, 내비는 그 영역을 피해 자기 화면을 그립니다.

1. 배너는 `WindowManager`의 `TYPE_APPLICATION_OVERLAY` 창(폭 전체, 높이 약 88dp, 터치 통과)입니다. 권한은 `SYSTEM_ALERT_WINDOW`입니다.
2. 배너를 띄울 때 `NaviHelper.getInternalApi().updateOccupiedRegion(Rect)`로 그 영역을 내비에 알리고, 내릴 때 빈 `Rect`로 되돌립니다.
3. 내용은 회전 아이콘, "앞쪽 약 150m · 우회전", 확신도 표시 세 가지만 둡니다. 버튼은 두지 않아 조작이 필요 없습니다.
4. 오버레이 권한이 없거나 `getInternalApi()` 호출이 막히면 `HeadsUpFallback`(상단 알림)으로 대신하고, 앱 화면에 그 사실을 표시합니다.

`getInternalApi()`는 이름이 'Internal'이라 외부 앱에 허용되는지 문서로 확인하지 못했습니다. 에뮬레이터에서 먼저 확인해야 합니다.

### 안내 문구 `AlertPhraser`

| 상황 | 배너 문구 | 표시 |
| --- | --- | --- |
| 주변, 우회전 확정 | 앞쪽 약 150m · 같은 방향 차량 우회전 | 강조 |
| 주변, IC 진출 확정 | 앞쪽 차량 · 오른쪽으로 빠짐 | 강조 |
| 동행, 같은 길 | 앞차 우회전 · 따라가세요 | 강조 |
| 동행, 길이 갈림 | 앞차 우회전 · 내 경로는 직진 | 강조 |
| 회전 예정(방향지시등 꺼짐) | 앞쪽 약 250m · 우회전 예정 | 회색 |

거리는 50m 단위로 반올림하고 "약"을 붙입니다. "내 바로 앞차"처럼 차선을 안다는 표현은 쓰지 않습니다.

## 단계별 구현 계획과 시연 각본

1단계만 끝내도 동행 모드 시연이 가능합니다. 각 단계는 '완료 기준'을 통과해야 다음으로 넘어갑니다.

| 단계 | 만드는 모듈 | 완료 기준 |
| --- | --- | --- |
| 0. 뼈대 | `:core:model`, `:core:port`, `:adapter:fake`, 기존 `core` → `:sim` 이동 | JVM 모듈 빌드·테스트 통과 |
| 1. 동행 모드 | `:feature:intent`(PLANNED까지), `:feature:convoy`, `:feature:awareness`, `:adapter:remote`, `:adapter:android`, `server/relay` 동행 채널, `:ui` 카드·정차 지도 | 에뮬레이터 2대에서 다음 회전이 3초 안에 상대 내비 위 배너로 뜨고, 내비가 배너 영역을 피함 |
| 2. 확정 판단 | `PleosTurnSignalAdapter`, `IntentResolver`의 CONFIRMED / SIGNAL_ONLY / 어긋남 | 방향지시등 변경이 1초 안에 판정에 반영, 어긋남은 안내하지 않음 |
| 3. 주변 공유 | `server/relay` 주변 채널(geohash + 방향), 주변 모드 필터 | 가상 차량 20대 부하에서 반대 방향 차량이 하나도 전달되지 않음 |
| 구상만 | V2X, 차선 단위 판단 | PT에서 "현재 SDK로는 불가"로 명시 |

### 시연 각본 (약 40초)

에뮬레이터 A(앞차)와 B(뒤차) 두 대 또는 A + `ScriptedDrive` 가상 차량으로 진행합니다.

1. A가 동행 방을 만들고 B가 초대 코드로 들어온다.
2. A의 경로에서 "300m 앞 우회전" → B 화면에 '예정' 카드가 조용히 뜬다.
3. A가 오른쪽 방향지시등을 켜면 '확정' → B의 내비 위에 "앞차 우회전 · 따라가세요" 강조 배너가 뜬다.
4. A가 왼쪽 방향지시등을 켜면(어긋남) 안내가 나가지 않는다. 잘못된 알림을 막는 장면.
5. A의 통신을 끊으면 3초 뒤 B에서 A 표시가 사라진다.

에뮬레이터에서 Vehicle 방향지시등 값을 바꿀 수 있는지는 아직 확인하지 못했습니다. 바꿀 수 없으면 `FakeTurnSignal` 버튼으로 대신하고, 영상에 자막으로 밝힙니다.

## 테스트·검증 계획과 리스크

판단 로직은 JUnit으로, 연결은 실제 WebSocket으로, SDK는 에뮬레이터에서 확인합니다. 아래 표에서 확인하지 못한 것은 PT 자료에도 '미확인'으로 남깁니다.

| 층 | 검증 내용 | 방법 |
| --- | --- | --- |
| `:feature:intent` | 판정표 5개 행, TurnType 43개 매핑, 방향지시등 튀는 값 무시, 회전 통과 후 해제 | JUnit, 가짜 시계 |
| `:feature:awareness` | 앞뒤·옆 거리 경계값, 반대 방향 제외, 같은 회전 제외, 3초 만료, 8초 간격, 중복 억제 | JUnit, 좌표 표본 |
| `server/relay` | 동행 방 격리, geohash 이웃 칸 전달, 방향 ±30° 필터, 초당 5개 제한 | pytest + 실제 WebSocket 클라이언트 여러 개 |
| 끝간 | 보낸 후 상대 카드까지 지연(목표 1초 안), 끊김 후 재연결 | `LoopbackRelay` + 실제 서버, 로그의 `ts` 비교 |
| SDK | `onTBTInfo`가 모의 주행에서 오는지, `TurnSignal` 권한과 값 변경 방법 | Pleos 에뮬레이터, adb 로그 |

### 리스크

| 리스크 | 영향 | 대응 |
| --- | --- | --- |
| `getInternalApi().updateOccupiedRegion`을 외부 앱이 쓸 수 없음 | 배너가 내비 화면 일부를 가림 | 배너를 상단 88dp로 제한하고 4초 뒤 내림. 첫 작업으로 에뮬레이터에서 확인 |
| Pleos가 오버레이 창(`SYSTEM_ALERT_WINDOW`)을 막거나 주행 중 가림 | 내비 위 배너가 안 뜸 | `HeadsUpFallback` 상단 알림으로 대체, 필요하면 음성 설정을 켬 |
| 에뮬레이터에서 방향지시등 값을 바꿀 수 없음 | 2단계 시연이 실제 SDK 값이 아님 | `FakeTurnSignal`로 시연하고 자막으로 명시. 어댑터 코드는 실제 SDK 시그니처로 작성 |
| `onTBTInfo`가 길안내 중에만 옴 | 길안내 없이 달리는 차는 회전 정보를 보낼 수 없음 | 이때는 방향지시등만(`SIGNAL_ONLY`) 보냄. 동행 모드는 길안내 켜기를 안내 |
| GPS 오차로 옆 도로 차를 같은 도로로 봄 | 주변 모드에서 틀린 알림 | 방향 20° + 옆 거리 20m + 확정만 강조. 문구는 "같은 방향 차량"까지만 |
| 참여 차량이 적음 | 주변 모드의 가치가 약함 | 동행 모드를 먼저 내세움 |
| 배너가 너무 자주 뜸 | 운전자 시선 분산 | 한 번에 하나, 회전당 1회, 4초 뒤 내림, 설정에서 끄기 |
| 위치 정보 노출 | 개인정보 심사 | 주행별 임시 ID, 10m 반올림, 서버 저장 없음, 공유는 기본 꺼짐 + 동의 후 켜기 |
