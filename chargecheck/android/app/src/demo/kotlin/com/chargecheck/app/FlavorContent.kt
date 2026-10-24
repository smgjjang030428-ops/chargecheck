package com.chargecheck.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.chargecheck.adapter.fake.Control
import com.chargecheck.adapter.fake.DemoScenarios
import com.chargecheck.adapter.fake.FakeApi
import com.chargecheck.adapter.fake.FakeClock
import com.chargecheck.adapter.fake.FakeLocation
import com.chargecheck.adapter.fake.FakeNavi
import com.chargecheck.adapter.fake.FakeVehicle
import com.chargecheck.adapter.fake.ScenarioPlayer
import com.chargecheck.core.model.DeviceId
import com.chargecheck.core.model.Station
import com.chargecheck.core.port.RouteTarget
import com.chargecheck.core.port.VoiceIntent
import com.chargecheck.feature.session.ChargeSessionMachine
import com.chargecheck.feature.session.SessionCoordinator
import com.chargecheck.feature.warning.ArrivalWarningCoordinator
import com.chargecheck.ui.DemoScenarioItem
import com.chargecheck.ui.DemoScreen
import com.chargecheck.ui.WarningUi
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** demo flavor: Fake 어댑터 + ScenarioPlayer를 SessionCoordinator·ArrivalWarningCoordinator에 연결한다. 연결은 app 모듈에서만 한다. */
private class DemoGraph {
    val clock = FakeClock(DEMO_START)
    val vehicle = FakeVehicle()
    val navi = FakeNavi()
    val location = FakeLocation()
    val api = FakeApi()
    val player = ScenarioPlayer(clock, vehicle, navi, location, api)

    private val resolve: (RouteTarget) -> Station? = { target -> DemoScenarios.allStations.firstOrNull { it.id.value == target.poiId } }

    val session = SessionCoordinator(
        machine = ChargeSessionMachine(DeviceId(UUID.randomUUID().toString()), { UUID.randomUUID().toString() }),
        vehicle = vehicle,
        navi = navi,
        location = location,
        api = api,
        clock = clock,
        resolveStation = resolve,
    )
    val warning = ArrivalWarningCoordinator(navi, api, location, resolve, log = session::note)

    /** 화면 버튼과 시나리오의 자동 응답이 같은 길로 들어온다. 경고가 떠 있으면 경고에 대한 답이다. */
    suspend fun answer(intent: VoiceIntent) {
        if (warning.warning.value != null) warning.choose(intent) else session.answer(intent)
    }

    suspend fun control(c: Control) = when (c) {
        is Control.Power -> if (c.on) session.appResumed() else session.appStopped()
        is Control.Answer -> answer(c.intent)
    }

    companion object {
        val DEMO_START: Instant = Instant.parse("2026-11-10T08:00:00Z")
        val SPEEDS = listOf(30, 60, 120, 240)
    }
}

private val clockFmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"))

@Composable
fun FlavorContent() {
    val graph = remember { DemoGraph() }
    val scope = rememberCoroutineScope()
    var speed by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            graph.player.stop()
            graph.session.stop()
            graph.warning.stop()
        }
    }

    val state by graph.session.state.collectAsState()
    val question by graph.session.question.collectAsState()
    val chargerPick by graph.session.chargerPick.collectAsState()
    val running by graph.session.running.collectAsState()
    val toast by graph.session.toast.collectAsState()
    val log by graph.session.log.collectAsState()
    val warning by graph.warning.warning.collectAsState()
    val uploads by graph.api.events.collectAsState()
    val playing by graph.player.playing.collectAsState()
    val now by graph.clock.time.collectAsState()

    DemoScreen(
        state = state,
        question = question,
        warning = warning?.let { WarningUi(it.station.name, it.status.verdict, it.reasonText, it.alternative?.name) },
        engineOn = running,
        toast = toast,
        log = log,
        uploads = uploads,
        scenarios = DemoScenarios.all.map { DemoScenarioItem(it.id, it.title) },
        playingId = playing?.id,
        clockText = clockFmt.format(now),
        speeds = DemoGraph.SPEEDS,
        speed = speed,
        onSpeed = { speed = it },
        onPlay = { id ->
            val scenario = DemoScenarios.all.first { it.id == id }
            graph.player.stop()
            graph.session.reset()
            graph.warning.reset()
            graph.api.clear()
            graph.session.start(scope, graph.clock.time)
            graph.warning.start(scope)
            graph.player.play(scope, scenario, DemoGraph.DEMO_START, speed ?: scenario.speed) { graph.control(it) }
        },
        onStop = { graph.player.stop() },
        onAnswer = { intent -> scope.launch { graph.answer(intent) } },
        onWarningChoice = { intent -> scope.launch { graph.warning.choose(intent) } },
        chargerPick = chargerPick,
        onPickCharger = { id -> scope.launch { graph.session.pickCharger(id) } },
    )
}
