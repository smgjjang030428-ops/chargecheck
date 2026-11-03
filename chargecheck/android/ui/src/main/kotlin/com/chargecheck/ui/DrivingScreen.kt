package com.chargecheck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chargecheck.core.model.Verdict
import com.chargecheck.core.port.VoiceIntent
import com.chargecheck.feature.session.SessionState

/** 주행 중 화면의 큰 글자·큰 버튼 기준. Pleos 가이드의 "큰 터치 영역·높은 대비·텍스트 최소화"를 Android for Cars 권장값으로 옮겼다. */
private val DrivingButtonHeight = 96.dp
private val DrivingTitle = 40.sp
private val DrivingBody = 28.sp

/**
 * 주행 중(CarUxRestrictions가 DO를 요구할 때) 보여주는 화면. (Pleos "주행 안전을 고려한 설계")
 * - 한 번에 하나의 정보: 도착 전 경고가 있으면 경고만, 없으면 현재 상태 한 줄만.
 * - 버튼은 최대 2개, 높이 96dp 이상. 지도·목록·스크롤·애니메이션·키보드 없음.
 * - 한 줄 글자 수를 짧게 유지하고 넘치면 자른다(UX_RESTRICTIONS_LIMIT_STRING_LENGTH 대비).
 * - "충전이 안 되나요?" 질문은 주행 중에 띄우지 않는다. 정차하면 AskSheet로 다시 보인다.
 * - 충전 실패 여부와 충전기 번호는 다음 정차 때 묻는다. 주행 중에는 기록 수집을 요구하지 않는다.
 * - 내비 목적지가 바뀌고 충전이 필요하면 [route] 카드(15초, 버튼 2개). 경고가 먼저다.
 */
@Composable
fun DrivingScreen(
    state: SessionState,
    warning: WarningUi?,
    onWarningChoice: (VoiceIntent) -> Unit,
    route: RouteCardUi? = null,
    onRoute: (Boolean) -> Unit = {},
) {
    Box(Modifier.fillMaxSize().background(Bg).padding(32.dp)) {
        Text(
            "주행 중 · 지도와 상세 정보는 정차하면 열려요",
            color = TextSub,
            fontSize = 24.sp, lineHeight = 31.sp,
            modifier = Modifier.align(Alignment.TopStart),
        )
        Column(Modifier.align(Alignment.Center).fillMaxWidth(0.85f)) {
            if (warning != null) {
                DrivingWarning(warning, onWarningChoice)
            } else if (route != null) {
                DrivingRoute(route, onRoute)
            } else {
                DrivingStatus(state)
            }
        }
    }
}

@Composable
private fun DrivingStatus(state: SessionState) {
    val station = when (state) {
        is SessionState.Approaching -> state.station.name
        is SessionState.Candidate -> state.station.name
        SessionState.Idle, is SessionState.Ended -> null
        is SessionState.Monitoring -> state.snap.stationName
        is SessionState.Asking -> state.snap.stationName
        is SessionState.Charging -> state.snap.stationName
        is SessionState.Completed -> state.snap.stationName
        is SessionState.Failed -> state.snap.stationName
        is SessionState.Suspended -> state.snap.stationName
        is SessionState.AskingRetro -> state.snap.stationName
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Gray))
        Spacer(Modifier.size(20.dp))
            Text("기본 내비로 계속 안내받으세요", color = TextMain, fontSize = DrivingTitle, lineHeight = 52.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (station != null) {
        Spacer(Modifier.height(16.dp))
        Text(station, color = TextSub, fontSize = DrivingBody, lineHeight = 36.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 주행 중 경로 카드. [들렀다 가기] = 추천 충전소를 경유지로, [괜찮아요] = 그대로. 갈 수 있는 곳이 없으면 [확인]만. */
@Composable
private fun DrivingRoute(route: RouteCardUi, onRoute: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Panel).padding(36.dp)) {
        Text(route.title, color = TextMain, fontSize = DrivingTitle, lineHeight = 52.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        Text(route.detail, color = TextSub, fontSize = DrivingBody, lineHeight = 36.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (route.canGo) {
                Button(
                    onClick = { onRoute(true) },
                    modifier = Modifier.weight(1.4f).heightIn(min = DrivingButtonHeight),
                    shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TextMain, contentColor = Bg),
                ) { Text("들렀다 가기", fontSize = DrivingBody, lineHeight = 36.sp, fontWeight = FontWeight.Bold) }
            }
            Button(
                onClick = { onRoute(false) },
                modifier = Modifier.weight(1f).heightIn(min = DrivingButtonHeight),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3D41), contentColor = TextMain),
            ) { Text(if (route.canGo) "괜찮아요" else "확인", fontSize = DrivingBody, lineHeight = 36.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun DrivingWarning(warning: WarningUi, onChoice: (VoiceIntent) -> Unit) {
    val badge = if (warning.verdict == Verdict.RED) Red else Yellow
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Panel).padding(36.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(badge))
            Spacer(Modifier.size(20.dp))
            Text(warning.title, color = TextMain, fontSize = DrivingTitle, lineHeight = 52.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(12.dp))
        Text(warning.stationName, color = TextSub, fontSize = DrivingBody, lineHeight = 36.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        Text(warning.reason, color = TextSub, fontSize = 23.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        warning.alternativeName?.let { name ->
            Spacer(Modifier.height(20.dp))
            Text("대안 · $name", color = TextMain, fontSize = DrivingBody, lineHeight = 36.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            warning.alternativeEvidence?.let { Text(it, color = TextSub, fontSize = 23.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (warning.alternativeName != null) {
                Button(
                    onClick = { onChoice(VoiceIntent.SWITCH) },
                    modifier = Modifier.weight(1.4f).heightIn(min = DrivingButtonHeight),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TextMain, contentColor = Bg),
                ) { Text("대안으로 바꾸기", fontSize = DrivingBody, lineHeight = 36.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            Button(
                onClick = { onChoice(VoiceIntent.KEEP) },
                modifier = Modifier.weight(1f).heightIn(min = DrivingButtonHeight),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3D41), contentColor = TextMain),
            ) { Text("현재 경로 유지", fontSize = DrivingBody, lineHeight = 36.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
