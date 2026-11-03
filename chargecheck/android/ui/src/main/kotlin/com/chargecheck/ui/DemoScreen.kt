package com.chargecheck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chargecheck.core.model.ChargeEvent
import com.chargecheck.core.model.ChargeResult
import com.chargecheck.core.model.ChargerType
import com.chargecheck.core.model.Verdict
import com.chargecheck.core.port.VoiceIntent
import com.chargecheck.feature.session.ChargerPick
import com.chargecheck.feature.session.Effect
import com.chargecheck.feature.session.LogEntry
import com.chargecheck.feature.session.SessionState
import com.chargecheck.feature.session.label
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 시나리오 버튼 하나. */
data class DemoScenarioItem(val id: String, val title: String)

// 상태 색은 기획 §9 규칙대로 GREEN·YELLOW·RED·GRAY만 쓴다. 나머지는 무채색.
internal val Bg = Color(0xFF121314)
internal val Panel = Color(0xFF1E2022)
internal val TextMain = Color(0xFFECEDEE)
internal val TextSub = Color(0xFFB3BAC2)
internal val Green = Color(0xFF2E9E5B)
internal val Yellow = Color(0xFFD9A400)
internal val Red = Color(0xFFD64541)
internal val Gray = Color(0xFF6B7075)

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Seoul"))

/** 도착 전 경고 카드(WarningCard)에 보여줄 값. */
data class WarningUi(
    val stationName: String,
    val verdict: Verdict,
    val reason: String,
    val alternativeName: String?,
    val title: String = "가는 충전소에 주의가 필요해요",
    val alternativeEvidence: String? = null,
)

/**
 * demo flavor 화면. 왼쪽: 시나리오(DevMenu), 가운데: 현재 세션(ChargingScreen 축약), 오른쪽: 기록.
 * 질문이 있으면 아래에 AskSheet, 도착 전 경고가 있으면 위에 WarningCard가 뜬다.
 */
@Composable
fun DemoScreen(
    state: SessionState,
    question: Effect.Ask?,
    warning: WarningUi?,
    engineOn: Boolean,
    toast: String?,
    log: List<LogEntry>,
    uploads: List<ChargeEvent>,
    scenarios: List<DemoScenarioItem>,
    playingId: String?,
    clockText: String,
    speeds: List<Int>,
    speed: Int?,
    onSpeed: (Int?) -> Unit,
    onPlay: (String) -> Unit,
    onStop: () -> Unit,
    onAnswer: (VoiceIntent) -> Unit,
    onWarningChoice: (VoiceIntent) -> Unit,
    chargerPick: ChargerPick? = null,
    onPickCharger: (String?) -> Unit = {},
) {
    Box(Modifier.fillMaxSize().background(Bg).padding(24.dp)) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            ScenarioPanel(Modifier.weight(0.9f).fillMaxHeight(), scenarios, playingId, clockText, speeds, speed, onSpeed, onPlay, onStop)
            SessionPanel(Modifier.weight(1.3f).fillMaxHeight(), state, engineOn, toast, uploads)
            LogPanel(Modifier.weight(1f).fillMaxHeight(), log)
        }
        if (warning != null) {
            WarningCard(Modifier.align(Alignment.TopCenter).fillMaxWidth(0.62f).padding(top = 40.dp), warning, onWarningChoice)
        }
        if (question != null) {
            AskSheet(Modifier.align(Alignment.BottomCenter), question, onAnswer)
        } else if (chargerPick != null) {
            ChargerPickSheet(Modifier.align(Alignment.BottomCenter), chargerPick, onPickCharger)
        }
    }
}

@Composable
private fun ScenarioPanel(
    modifier: Modifier,
    scenarios: List<DemoScenarioItem>,
    playingId: String?,
    clockText: String,
    speeds: List<Int>,
    speed: Int?,
    onSpeed: (Int?) -> Unit,
    onPlay: (String) -> Unit,
    onStop: () -> Unit,
) {
    PanelBox(modifier) {
        Text("차지체크", color = TextMain, fontSize = 30.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold)
        Text("시연 모드 · 가짜 차량 데이터", color = TextSub, fontSize = 16.sp)
        Spacer(Modifier.height(20.dp))
        Text("시나리오", color = TextSub, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        scenarios.forEach { s ->
            val active = s.id == playingId
            Button(
                onClick = { onPlay(s.id) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (active) TextMain else Color(0xFF2B2E31),
                    contentColor = if (active) Bg else TextMain,
                ),
            ) { Text(s.title, fontSize = 16.sp, modifier = Modifier.padding(vertical = 6.dp)) }
        }
        Spacer(Modifier.weight(1f))
        Text("시간 배속 (다음 재생부터)", color = TextSub, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf<Int?>(null) + speeds).forEach { s ->
                val active = s == speed
                OutlinedButton(
                    onClick = { onSpeed(s) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (active) TextMain else Color.Transparent,
                        contentColor = if (active) Bg else TextMain,
                    ),
                ) { Text(if (s == null) "기본" else "×$s", fontSize = 15.sp) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("차량 시각 $clockText", color = TextSub, fontSize = 18.sp)
        if (playingId != null) {
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                Text("정지", color = TextMain, fontSize = 18.sp)
            }
        }
    }
}

@Composable
internal fun SessionPanel(modifier: Modifier, state: SessionState, engineOn: Boolean, toast: String?, uploads: List<ChargeEvent>) {
    val (stationName, soc) = when (state) {
        SessionState.Idle -> null to null
        is SessionState.Approaching -> state.station.name to null
        is SessionState.Candidate -> state.station.name to state.lastSoc?.pct
        is SessionState.Monitoring -> state.snap.stationName to state.lastSoc.pct
        is SessionState.Asking -> state.snap.stationName to state.lastSoc.pct
        is SessionState.Charging -> state.snap.stationName to state.lastSoc.pct
        is SessionState.Completed -> state.snap.stationName to null
        is SessionState.Failed -> state.snap.stationName to null
        is SessionState.Suspended -> state.snap.stationName to state.snap.soc
        is SessionState.AskingRetro -> state.snap.stationName to state.currentSoc
        is SessionState.Ended -> state.snap.stationName to null
    }
    val badge = when (state) {
        is SessionState.Charging, is SessionState.Completed -> Green
        is SessionState.Asking, is SessionState.AskingRetro -> Yellow
        is SessionState.Failed -> Red
        is SessionState.Ended -> if (state.result == ChargeResult.FAIL) Red else Green
        else -> Gray
    }
    PanelBox(modifier) {
        if (!engineOn) {
            Text(
                "시동 꺼짐 · 앱이 멈춘 상태 (이 동안의 충전은 다시 켤 때 비교합니다)",
                color = TextMain,
                fontSize = 18.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF2B2E31)).padding(12.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
        Text(stationName ?: "충전소로 가면 자동으로 시작돼요", color = TextSub, fontSize = 20.sp)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(18.dp).height(18.dp).clip(RoundedCornerShape(9.dp)).background(badge))
            Spacer(Modifier.width(12.dp))
            Text(state.label(), color = TextMain, fontSize = 34.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(32.dp))
        if (soc != null) {
            Text("배터리 $soc%", color = TextMain, fontSize = 56.sp, lineHeight = 73.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { soc / 100f },
                modifier = Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(9.dp)),
                color = if (badge == Gray) TextMain else badge,
                trackColor = Color(0xFF2B2E31),
            )
        }
        Spacer(Modifier.weight(1f))
        if (toast != null) {
            Text(toast, color = Green, fontSize = 20.sp)
            Spacer(Modifier.height(12.dp))
        }
        // 이번 실행에서 서버가 새로 받은 기록만 센다(누적 아님). 대기 중인 기록은 화면 위 배너에 따로
        if (uploads.isNotEmpty()) Text("이번 실행에서 서버가 받은 기록 ${uploads.size}건", color = TextSub, fontSize = 18.sp)
        uploads.takeLast(3).forEach { e ->
            val color = if (e.result == ChargeResult.SUCCESS) Green else Red
            Text(
                "${if (e.result == ChargeResult.SUCCESS) "정상" else "고장"} · ${e.mode} · SOC ${e.socStart}→${e.socEnd}% · ${timeFmt.format(e.occurredAt)}",
                color = color,
                fontSize = 18.sp,
            )
        }
    }
}

@Composable
internal fun LogPanel(modifier: Modifier, log: List<LogEntry>) {
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex) }
    PanelBox(modifier) {
        Text("판단 기록", color = TextSub, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(log) { e ->
                Row {
                    Text(timeFmt.format(e.at), color = TextSub, fontSize = 15.sp, modifier = Modifier.width(52.dp))
                    Text(e.text, color = TextMain, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
internal fun AskSheet(modifier: Modifier, question: Effect.Ask, onAnswer: (VoiceIntent) -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(question.question, color = TextMain, fontSize = 30.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val (yes, other) = if (question.retro) "응" to ("아니요" to VoiceIntent.NO) else "응, 안 돼요" to ("아직 연결 중" to VoiceIntent.WAIT)
            Button(
                onClick = { onAnswer(VoiceIntent.YES) },
                colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = TextMain),
                shape = RoundedCornerShape(14.dp),
            ) { Text(yes, fontSize = 22.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) }
            OutlinedButton(onClick = { onAnswer(other.second) }, shape = RoundedCornerShape(14.dp)) {
                Text(other.first, color = TextMain, fontSize = 22.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
internal fun WarningCard(modifier: Modifier, warning: WarningUi, onChoice: (VoiceIntent) -> Unit) {
    val color = when (warning.verdict) {
        Verdict.RED -> Red
        Verdict.YELLOW -> Yellow
        Verdict.GREEN -> Green
        Verdict.GRAY -> Gray
    }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(18.dp).height(18.dp).clip(RoundedCornerShape(9.dp)).background(color))
            Spacer(Modifier.width(12.dp))
            Text(warning.title, color = TextMain, fontSize = 32.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Text(warning.stationName, color = TextMain, fontSize = 24.sp)
        Spacer(Modifier.height(8.dp))
        Text(warning.reason, color = TextSub, fontSize = 20.sp)
        warning.alternativeName?.let { name ->
            Spacer(Modifier.height(24.dp))
            Text("대안 한 곳", color = TextSub, fontSize = 18.sp)
            Spacer(Modifier.height(6.dp))
            Text(name, color = TextMain, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
            warning.alternativeEvidence?.let { Text(it, color = TextSub, fontSize = 19.sp) }
        }
        Spacer(Modifier.height(20.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (warning.alternativeName != null) {
                Button(
                    onClick = { onChoice(VoiceIntent.SWITCH) },
                    colors = ButtonDefaults.buttonColors(containerColor = TextMain, contentColor = Bg),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("대안으로 바꾸기", fontSize = 22.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
                }
            }
            OutlinedButton(onClick = { onChoice(VoiceIntent.KEEP) }, shape = RoundedCornerShape(14.dp)) {
                Text("현재 경로 유지", color = TextMain, fontSize = 22.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
            }
        }
    }
}

/** 실패 기록 뒤 "몇 번 충전기였나요?" (주차 중에만). 번호를 알면 그 충전기만 실패로 기록돼 다른 충전기는 계속 추천된다. */
@Composable
internal fun ChargerPickSheet(modifier: Modifier, pick: ChargerPick, onPick: (String?) -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("몇 번 충전기였나요?", color = TextMain, fontSize = 30.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("충전기에 적힌 번호를 고르면 그 충전기만 실패로 기록돼요", color = TextSub, fontSize = 18.sp)
        Spacer(Modifier.height(20.dp))
        // 아파트 충전소는 100대까지 있다(서울 실데이터). 목록이 길면 스크롤한다.
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            pick.chargers.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    row.forEach { c ->
                        OutlinedButton(onClick = { onPick(c.chargerId) }, shape = RoundedCornerShape(14.dp)) {
                            val kind = if (c.chargerType == ChargerType.SLOW) "완속" else "급속"
                            Text("${c.chargerId}번 · $kind", color = TextMain, fontSize = 22.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onPick(null) },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3D41), contentColor = TextMain),
            shape = RoundedCornerShape(14.dp),
        ) { Text("모르겠어요", fontSize = 22.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) }
    }
}

@Composable
internal fun PanelBox(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(Panel).padding(24.dp)) { content() }
}
