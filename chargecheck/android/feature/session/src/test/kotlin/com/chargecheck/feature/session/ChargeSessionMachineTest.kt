package com.chargecheck.feature.session

import com.chargecheck.core.config.ChargeCheckConfig
import com.chargecheck.core.model.ArrivalSnapshot
import com.chargecheck.core.model.ChargeResult
import com.chargecheck.core.model.ChargerType
import com.chargecheck.core.model.DetectMode
import com.chargecheck.core.model.DeviceId
import com.chargecheck.core.model.GeoPoint
import com.chargecheck.core.model.SocSample
import com.chargecheck.core.model.Station
import com.chargecheck.core.model.StationId
import com.chargecheck.core.port.VoiceIntent
import com.chargecheck.feature.session.SessionInput.Answer
import com.chargecheck.feature.session.SessionInput.AppResumed
import com.chargecheck.feature.session.SessionInput.AppStopped
import com.chargecheck.feature.session.SessionInput.Arrived
import com.chargecheck.feature.session.SessionInput.Departing
import com.chargecheck.feature.session.SessionInput.Moved
import com.chargecheck.feature.session.SessionInput.ReviewDone
import com.chargecheck.feature.session.SessionInput.RouteTargetIsStation
import com.chargecheck.feature.session.SessionInput.Soc
import com.chargecheck.feature.session.SessionInput.StoppedNearStation
import com.chargecheck.feature.session.SessionInput.TargetReached
import com.chargecheck.feature.session.SessionInput.Tick
import com.chargecheck.feature.session.SessionState.Approaching
import com.chargecheck.feature.session.SessionState.Asking
import com.chargecheck.feature.session.SessionState.AskingRetro
import com.chargecheck.feature.session.SessionState.Candidate
import com.chargecheck.feature.session.SessionState.Charging
import com.chargecheck.feature.session.SessionState.Completed
import com.chargecheck.feature.session.SessionState.Ended
import com.chargecheck.feature.session.SessionState.Failed
import com.chargecheck.feature.session.SessionState.Idle
import com.chargecheck.feature.session.SessionState.Monitoring
import com.chargecheck.feature.session.SessionState.Suspended
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 전이 번호(T1…)는 기획문서/차지체크_앱_구동_기획.md §5 전이표와 같다. */
class ChargeSessionMachineTest {

    private val t0: Instant = Instant.parse("2026-11-10T08:00:00Z")
    private fun at(min: Long): Instant = t0.plus(Duration.ofMinutes(min))

    private val fast = Station(StationId("ME000001"), "강남 급속충전소", GeoPoint(37.4979, 127.0276), ChargerType.FAST)
    private val slow = Station(StationId("ME000002"), "아파트 완속충전소", GeoPoint(37.5000, 127.0300), ChargerType.SLOW)
    private val cfg = ChargeCheckConfig.DEFAULT

    private var nextId = 0
    private lateinit var machine: ChargeSessionMachine

    @BeforeEach
    fun setUp() {
        nextId = 0
        machine = ChargeSessionMachine(DeviceId("device-1"), { "evt-${++nextId}" }, cfg)
    }

    // ------------------------------------------------------------ 도우미

    private fun run(start: SessionState, vararg inputs: SessionInput): Pair<SessionState, List<Effect>> {
        var state = start
        val effects = mutableListOf<Effect>()
        for (input in inputs) {
            val t = machine.reduce(state, input)
            state = t.state
            effects += t.effects
        }
        return state to effects
    }

    private fun snapOf(station: Station, soc: Int, arrived: Instant = t0, successSent: Boolean = false) =
        ArrivalSnapshot(station.id, station.name, station.chargerType, soc, arrived, station.location, successSent)

    /** 도착(t0) → SOC 42% → 5분 정차 → Monitoring */
    private fun monitoring(station: Station = fast, soc: Int = 42): Monitoring {
        val (state, _) = run(Idle, Arrived(station, t0), Soc(SocSample(soc, t0)), Tick(at(5)))
        return assertIs<Monitoring>(state)
    }

    private fun List<Effect>.uploads() = filterIsInstance<Effect.Upload>().map { it.event }
    private fun List<Effect>.confirms() = filterIsInstance<Effect.ConfirmLater>()

    // ------------------------------------------------------------ 시작

    @Nested
    inner class Start {
        @Test
        fun `T1 목적지가 충전소면 Approaching`() {
            val t = machine.reduce(Idle, RouteTargetIsStation(fast))
            assertEquals(Approaching(fast), t.state)
            assertTrue(t.effects.isEmpty())
        }

        @Test
        fun `T2 도착하면 Candidate가 되고 5분 뒤 Tick 예약`() {
            val t = machine.reduce(Approaching(fast), Arrived(fast, t0))
            assertEquals(Candidate(fast, t0), t.state)
            assertEquals(listOf(Effect.ScheduleTick(at(5))), t.effects)
        }

        @Test
        fun `T2 내비 없이 충전소 근처에 정차해도 Candidate`() {
            val t = machine.reduce(Idle, StoppedNearStation(slow, t0))
            assertEquals(Candidate(slow, t0), t.state)
        }

        @Test
        fun `T3 5분 정차하고 SOC를 읽었으면 Monitoring과 스냅샷 저장`() {
            val (state, effects) = run(Idle, Arrived(fast, t0), Soc(SocSample(42, at(1))), Tick(at(5)))
            val m = assertIs<Monitoring>(state)
            assertEquals(42, m.snap.soc)
            assertEquals(at(5) + cfg.stallTimeoutFast, m.stallDeadline)
            assertTrue(effects.contains(Effect.SaveSnapshot(m.snap)))
            assertTrue(effects.contains(Effect.ScheduleTick(m.stallDeadline)))
        }

        @Test
        fun `T3 5분이 안 됐으면 그대로`() {
            val (state, _) = run(Idle, Arrived(fast, t0), Soc(SocSample(42, t0)), Tick(at(4)))
            assertIs<Candidate>(state)
        }

        @Test
        fun `T3 SOC를 아직 못 읽었으면 1분 뒤 다시 확인`() {
            val (state, effects) = run(Candidate(fast, t0), Tick(at(5)))
            assertIs<Candidate>(state)
            assertEquals(listOf(Effect.ScheduleTick(at(6))), effects)
        }

        @Test
        fun `T4 Candidate에서 50m 밖으로 이동하면 Idle`() {
            assertEquals(Idle, machine.reduce(Candidate(fast, t0), Moved(80)).state)
            assertIs<Candidate>(machine.reduce(Candidate(fast, t0), Moved(30)).state)
        }
    }

    // ------------------------------------------------------------ LIVE

    @Nested
    inner class Live {
        @Test
        fun `T5 SOC가 1퍼센트포인트 오르면 SUCCESS LIVE 업로드`() {
            val (state, effects) = run(monitoring(), Soc(SocSample(43, at(8))))
            val c = assertIs<Charging>(state)
            assertTrue(c.snap.successSent)
            val e = effects.uploads().single()
            assertEquals(ChargeResult.SUCCESS, e.result)
            assertEquals(DetectMode.LIVE, e.mode)
            assertEquals(42, e.socStart)
            assertEquals(43, e.socEnd)
            assertEquals(8, e.minutes)
            assertEquals("evt-1", e.eventId)
            assertTrue(effects.any { it is Effect.ShowToast })
            assertTrue(effects.contains(Effect.SaveSnapshot(c.snap)))
        }

        @Test
        fun `T5 SOC가 그대로면 업로드하지 않는다`() {
            val (state, effects) = run(monitoring(), Soc(SocSample(42, at(7))))
            assertIs<Monitoring>(state)
            assertTrue(effects.uploads().isEmpty())
        }

        @Test
        fun `T6 급속은 10분 정체하면 질문`() {
            val m = monitoring()
            assertIs<Monitoring>(machine.reduce(m, Tick(at(14))).state)
            val t = machine.reduce(m, Tick(at(15)))
            assertIs<Asking>(t.state)
            assertEquals(listOf(Effect.Ask(ChargeSessionMachine.QUESTION_LIVE, retro = false)), t.effects)
        }

        @Test
        fun `T6 완속은 40분을 기다린다`() {
            val m = monitoring(slow)
            assertIs<Monitoring>(machine.reduce(m, Tick(at(44))).state)
            assertIs<Asking>(machine.reduce(m, Tick(at(45))).state)
        }

        @Test
        fun `T7 응이라고 하면 FAIL LIVE 업로드하고 스냅샷 삭제`() {
            val (state, effects) = run(monitoring(), Tick(at(15)), Answer(VoiceIntent.YES, at(16)))
            assertIs<Failed>(state)
            val e = effects.uploads().single()
            assertEquals(ChargeResult.FAIL, e.result)
            assertEquals(DetectMode.LIVE, e.mode)
            assertTrue(effects.contains(Effect.ClearSnapshot))
            assertTrue(effects.any { it is Effect.Say })
        }

        @Test
        fun `T8 아직이라고 하면 10분 연장`() {
            val (state, effects) = run(monitoring(), Tick(at(15)), Answer(VoiceIntent.WAIT, at(16)))
            val m = assertIs<Monitoring>(state)
            assertEquals(at(26), m.stallDeadline)
            assertTrue(effects.contains(Effect.ScheduleTick(at(26))))
            assertTrue(effects.uploads().isEmpty())
        }

        @Test
        fun `T8 연장 후 충전되면 SUCCESS`() {
            val (state, effects) = run(monitoring(), Tick(at(15)), Answer(VoiceIntent.WAIT, at(16)), Soc(SocSample(43, at(20))))
            assertIs<Charging>(state)
            assertEquals(ChargeResult.SUCCESS, effects.uploads().single().result)
        }

        @Test
        fun `T9 무응답이면 기록하지 않는다`() {
            val (state, effects) = run(monitoring(), Tick(at(15)), Answer(null, at(16)))
            assertEquals(Idle, state)
            assertTrue(effects.uploads().isEmpty())
            assertTrue(effects.contains(Effect.ClearSnapshot))
        }

        @Test
        fun `T9 아니라고 해도 기록하지 않는다`() {
            val (state, effects) = run(monitoring(), Tick(at(15)), Answer(VoiceIntent.NO, at(16)))
            assertEquals(Idle, state)
            assertTrue(effects.uploads().isEmpty())
        }

        @Test
        fun `T21 질문 중에 SOC가 오르면 SUCCESS`() {
            val (state, effects) = run(monitoring(), Tick(at(15)), Soc(SocSample(43, at(16))))
            assertIs<Charging>(state)
            assertEquals(ChargeResult.SUCCESS, effects.uploads().single().result)
        }

        @Test
        fun `T22 결과 없이 떠나면 바로 기록하지 않고 확인을 맡긴다`() {
            val (state, effects) = run(monitoring(), Moved(200))
            assertEquals(Idle, state)
            assertTrue(effects.uploads().isEmpty())
            assertTrue(effects.contains(Effect.ClearSnapshot))
            assertEquals(ChargeResult.FAIL, effects.confirms().single().event.result)
        }
    }

    // ------------------------------------------------------------ 잔량 변화 없이 출발 (T23~T25)

    @Nested
    inner class Departure {
        @Test
        fun `T24 지켜보던 중 출발하면 FAIL을 올리지 않고 확인을 맡긴다`() {
            val (state, effects) = run(monitoring(), Departing(at(8)))
            assertEquals(Idle, state)
            assertTrue(effects.uploads().isEmpty())
            val c = effects.confirms().single()
            assertEquals(fast.name, c.stationName)
            assertEquals(Triple(ChargeResult.FAIL, DetectMode.LIVE, 42), Triple(c.event.result, c.event.mode, c.event.socEnd))
            assertEquals(at(8), c.event.occurredAt)
        }

        @Test
        fun `T23 세션 시작 전이라도 2분 넘게 있다가 출발하면 확인`() {
            val (state, effects) = run(Idle, Arrived(fast, t0), Soc(SocSample(42, t0)), Departing(at(3)))
            assertEquals(Idle, state)
            assertEquals(at(3), effects.confirms().single().event.occurredAt)
        }

        @Test
        fun `T23 잠깐 들렀다 가면 묻지 않는다`() {
            val (_, effects) = run(Idle, Arrived(fast, t0), Soc(SocSample(42, t0)), Departing(at(1)))
            assertTrue(effects.confirms().isEmpty())
        }

        @Test
        fun `T23 잔량을 못 읽었으면 묻지 않는다`() {
            val (_, effects) = run(Idle, Arrived(fast, t0), Departing(at(3)))
            assertTrue(effects.confirms().isEmpty())
        }

        @Test
        fun `T24 충전이 안 되나요 질문에 답하지 않고 출발해도 확인을 맡긴다`() {
            val asking = assertIs<Asking>(run(monitoring(), Tick(at(15))).first)
            val (state, effects) = run(asking, Departing(at(16)))
            assertEquals(Idle, state)
            assertEquals(1, effects.confirms().size)
        }

        @Test
        fun `T25 재시동 질문에 답하지 않고 출발하면 RETRO로 확인을 맡긴다`() {
            val retro = assertIs<AskingRetro>(run(Suspended(snapOf(fast, 42)), AppResumed(42, fast.location, at(40))).first)
            val c = run(retro, Departing(at(41))).second.confirms().single()
            assertEquals(DetectMode.RETRO, c.event.mode)
        }

        @Test
        fun `충전이 확인된 뒤 출발하면 확인하지 않는다`() {
            val charging = assertIs<Charging>(run(monitoring(), Soc(SocSample(43, at(7)))).first)
            val (state, effects) = run(charging, Departing(at(20)))
            assertIs<Charging>(state)
            assertTrue(effects.confirms().isEmpty())
        }

        @Test
        fun `꽂았는데 안 돼서 3분 만에 시동 켠 채로 떠나는 시나리오`() {
            val (_, effects) = run(
                Idle,
                Arrived(fast, t0),
                Soc(SocSample(42, t0)),
                Soc(SocSample(42, at(2))),
                Departing(at(3)),
                Moved(300),
            )
            assertTrue(effects.uploads().isEmpty())
            assertEquals(1, effects.confirms().size)
        }

        @Test
        fun `시동 끄고 꽂았다가 8분 만에 돌아와 떠나는 시나리오`() {
            val (_, effects) = run(
                Idle,
                Arrived(fast, t0),
                Soc(SocSample(42, t0)),
                Tick(at(5)),
                AppStopped,
                AppResumed(42, fast.location, at(8)), // 15분 전이라 T13b: 다시 지켜봄
                Departing(at(9)),
            )
            assertTrue(effects.uploads().isEmpty())
            assertEquals(1, effects.confirms().size)
        }
    }

    // ------------------------------------------------------------ RETRO

    @Nested
    inner class Retro {
        private val suspended = Suspended(snapOf(fast, 42))

        @Test
        fun `T10 Monitoring 중 앱이 꺼지면 Suspended`() {
            val m = monitoring()
            assertEquals(Suspended(m.snap), machine.reduce(m, AppStopped).state)
        }

        @Test
        fun `T11 꺼진 동안 2퍼센트포인트 이상 오르면 SUCCESS RETRO`() {
            val (state, effects) = run(suspended, AppResumed(55, fast.location, at(40)))
            val c = assertIs<Charging>(state)
            assertTrue(c.snap.successSent)
            val e = effects.uploads().single()
            assertEquals(ChargeResult.SUCCESS, e.result)
            assertEquals(DetectMode.RETRO, e.mode)
            assertEquals(55, e.socEnd)
            assertEquals(40, e.minutes)
            assertTrue(effects.contains(Effect.SaveSnapshot(c.snap)))
        }

        @Test
        fun `T12 15분 넘게 지났는데 그대로면 과거형 질문`() {
            val t = machine.reduce(suspended, AppResumed(42, fast.location, at(40)))
            assertIs<AskingRetro>(t.state)
            assertEquals(listOf(Effect.Ask(ChargeSessionMachine.questionRetro(fast.name), retro = true)), t.effects)
        }

        @Test
        fun `T13 하루가 지난 스냅샷은 버린다`() {
            val t = machine.reduce(suspended, AppResumed(42, fast.location, at(60 * 25)))
            assertEquals(Idle, t.state)
            assertEquals(listOf(Effect.ClearSnapshot), t.effects)
        }

        @Test
        fun `T13 멀리 이동했으면 버린다`() {
            val far = GeoPoint(37.6000, 127.1000)
            assertEquals(Idle, machine.reduce(suspended, AppResumed(42, far, at(40))).state)
        }

        @Test
        fun `T13 SOC를 못 읽으면 버린다`() {
            assertEquals(Idle, machine.reduce(suspended, AppResumed(null, fast.location, at(40))).state)
        }

        @Test
        fun `T13 위치를 모르면 같은 곳으로 보고 판단한다`() {
            assertIs<AskingRetro>(machine.reduce(suspended, AppResumed(42, null, at(40))).state)
        }

        @Test
        fun `T13b 금방 다시 켜지면 현장에서 계속 지켜본다`() {
            val t = machine.reduce(suspended, AppResumed(42, fast.location, at(10)))
            val m = assertIs<Monitoring>(t.state)
            assertEquals(at(10) + cfg.stallTimeoutFast, m.stallDeadline)
            assertTrue(t.effects.uploads().isEmpty())
        }

        @Test
        fun `T13c 1퍼센트포인트만 오르면 판단 보류`() {
            val t = machine.reduce(suspended, AppResumed(43, fast.location, at(40)))
            assertEquals(Idle, t.state)
            assertTrue(t.effects.uploads().isEmpty())
        }

        @Test
        fun `T14 과거형 질문에 응이면 FAIL RETRO`() {
            val (state, effects) = run(suspended, AppResumed(42, fast.location, at(40)), Answer(VoiceIntent.YES, at(41)))
            assertIs<Failed>(state)
            val e = effects.uploads().single()
            assertEquals(ChargeResult.FAIL, e.result)
            assertEquals(DetectMode.RETRO, e.mode)
            assertEquals(42, e.socEnd)
            assertTrue(effects.contains(Effect.ClearSnapshot))
        }

        @Test
        fun `T15 과거형 질문에 무응답이면 기록하지 않는다`() {
            val (state, effects) = run(suspended, AppResumed(42, fast.location, at(40)), Answer(null, at(41)))
            assertEquals(Idle, state)
            assertTrue(effects.uploads().isEmpty())
        }
    }

    // ------------------------------------------------------------ 충전 이후

    @Nested
    inner class AfterCharging {
        private val charging = Charging(snapOf(fast, 42, successSent = true), SocSample(43, at(8)))

        @Test
        fun `T16 충전 중 SOC 갱신`() {
            val t = machine.reduce(charging, Soc(SocSample(60, at(20))))
            assertEquals(60, assertIs<Charging>(t.state).lastSoc.pct)
            assertTrue(t.effects.isEmpty())
        }

        @Test
        fun `T17 목표 도달하면 완료 알림`() {
            val t = machine.reduce(charging, TargetReached(80))
            assertIs<Completed>(t.state)
            assertEquals(listOf(Effect.NotifyComplete), t.effects)
        }

        @Test
        fun `T18 충전 후 떠나면 Ended와 리뷰 제안`() {
            val t = machine.reduce(charging, Moved(200))
            assertEquals(Ended(charging.snap, ChargeResult.SUCCESS), t.state)
            assertEquals(listOf(Effect.OfferReview(fast.id)), t.effects)
        }

        @Test
        fun `T18 실패 후 떠나도 Ended`() {
            val t = machine.reduce(Failed(snapOf(fast, 42)), Moved(200))
            assertEquals(ChargeResult.FAIL, assertIs<Ended>(t.state).result)
        }

        @Test
        fun `T19 리뷰를 마치면 Idle과 스냅샷 삭제`() {
            val t = machine.reduce(Ended(charging.snap, ChargeResult.SUCCESS), ReviewDone)
            assertEquals(Idle, t.state)
            assertEquals(listOf(Effect.ClearSnapshot), t.effects)
        }

        @Test
        fun `T19 리뷰 전에 새 목적지가 오면 초기화 후 처리`() {
            val t = machine.reduce(Ended(charging.snap, ChargeResult.SUCCESS), RouteTargetIsStation(slow))
            assertEquals(Approaching(slow), t.state)
            assertEquals(Effect.ClearSnapshot, t.effects.first())
        }

        @Test
        fun `T20 SUCCESS 보낸 세션은 다시 켜져도 또 보내지 않는다`() {
            val (state, effects) = run(charging, AppStopped, AppResumed(70, fast.location, at(60)))
            assertIs<Charging>(state)
            assertTrue(effects.uploads().isEmpty())
        }

        @Test
        fun `T20 한 세션 전체에서 SUCCESS는 1건뿐`() {
            val (_, effects) = run(
                monitoring(),
                Soc(SocSample(43, at(8))),
                Soc(SocSample(45, at(12))),
                AppStopped,
                AppResumed(60, fast.location, at(50)),
                Soc(SocSample(61, at(52))),
            )
            assertEquals(1, effects.uploads().count { it.result == ChargeResult.SUCCESS })
        }
    }

    // ------------------------------------------------------------ 시나리오

    @Nested
    inner class Scenarios {
        @Test
        fun `LIVE 성공 시나리오 - 도착부터 떠날 때까지`() {
            val (state, effects) = run(
                Idle,
                RouteTargetIsStation(fast),
                Arrived(fast, t0),
                Soc(SocSample(42, t0)),
                Tick(at(5)),
                Soc(SocSample(43, at(7))),
                TargetReached(80),
                Moved(300),
                ReviewDone,
            )
            assertEquals(Idle, state)
            assertEquals(listOf(ChargeResult.SUCCESS), effects.uploads().map { it.result })
            assertTrue(effects.contains(Effect.NotifyComplete))
            assertTrue(effects.contains(Effect.OfferReview(fast.id)))
        }

        @Test
        fun `RETRO 실패 시나리오 - 꽂고 자리를 비웠다가 돌아옴`() {
            val (state, effects) = run(
                Idle,
                Arrived(fast, t0),
                Soc(SocSample(42, t0)),
                Tick(at(5)),
                AppStopped,
                AppResumed(42, fast.location, at(45)),
                Answer(VoiceIntent.YES, at(46)),
                Moved(300),
            )
            assertIs<Ended>(state)
            val e = effects.uploads().single()
            assertEquals(ChargeResult.FAIL to DetectMode.RETRO, e.result to e.mode)
        }
    }
}
