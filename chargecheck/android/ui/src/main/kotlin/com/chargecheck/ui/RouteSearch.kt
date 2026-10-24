package com.chargecheck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chargecheck.core.model.Place
import com.chargecheck.core.model.PublicState
import com.chargecheck.core.model.RouteCandidate
import com.chargecheck.core.model.RoutePlan

/** 지역 검색 상자의 상태. [message] = 검색 중·결과 없음·서버 오류 안내. */
data class SearchUi(
    val query: String = "",
    val results: List<Place> = emptyList(),
    val message: String? = null,
)

/** 지도 위 검색 상자 + 결과 목록(주차 중에만 보인다). */
@Composable
internal fun SearchBox(
    modifier: Modifier,
    search: SearchUi,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onPick: (Place) -> Unit,
) {
    val focus = LocalFocusManager.current
    Column(modifier.width(560.dp)) {
        OutlinedTextField(
            value = search.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel),
            placeholder = { Text("목적지·지역 검색 (예: 서울시청)", color = TextSub, fontSize = 20.sp) },
            textStyle = TextStyle(color = TextMain, fontSize = 20.sp),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); onSearch() }),
            trailingIcon = {
                Text(
                    "검색",
                    color = TextMain,
                    fontSize = 19.sp,
                    modifier = Modifier.clickable { focus.clearFocus(); onSearch() }.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TextMain,
                unfocusedBorderColor = Gray,
                cursorColor = TextMain,
            ),
        )
        if (search.message != null || search.results.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(14.dp)).background(Panel)
                    .verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
            ) {
                search.message?.let { Text(it, color = TextSub, fontSize = 18.sp, modifier = Modifier.padding(16.dp)) }
                search.results.forEach { p ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (p.stationId != null) "충전소" else "장소", color = TextSub, fontSize = 15.sp, modifier = Modifier.width(56.dp))
                            Text(p.name, color = TextMain, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (p.address.isNotBlank()) {
                            Text(
                                p.address, color = TextSub, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 56.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 목적지까지 가는 길의 충전소(오른쪽 패널). 차의 남은 주행 가능 거리로 충전이 필요한지 먼저 말하고,
 * 경로 순서대로 충전소·도착 예상 배터리·추천을 보여준다. 줄을 누르면 지도에 상세 카드, [들렀다 가기]는 경유지로 안내.
 */
@Composable
internal fun RoutePanel(
    modifier: Modifier,
    plan: RoutePlan,
    onSelect: (String) -> Unit,
    onGo: (String?) -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(Panel).padding(22.dp)) {
        Text("${plan.destination.name}까지 ${km(plan.routeKm)}", color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        val car = listOfNotNull(plan.rangeKm?.let { "남은 주행 ${km(it)}" }, plan.soc?.let { "배터리 $it%" })
        Text(car.joinToString(" · ").ifBlank { "차의 남은 주행 가능 거리를 못 읽었어요" }, color = TextSub, fontSize = 18.sp)
        Spacer(Modifier.height(12.dp))
        val (banner, color) = when {
            plan.rangeKm == null -> "가는 길의 믿을 만한 충전소예요" to Gray
            plan.needsCharge && plan.recommendedId == null -> "충전 없이는 못 가요 · 갈 수 있는 거리 안에 충전소가 없어요" to Red
            plan.needsCharge -> "충전 없이는 못 가요 · 추천 충전소에 들렀다 가세요" to Yellow
            else -> "충전 없이 갈 수 있어요" to Green
        }
        Text(banner, color = TextMain, fontSize = 19.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color).padding(12.dp))
        if (!plan.roadRoute) Text("도로 경로를 못 받아 직선 거리로 계산했어요", color = TextSub, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(10.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (plan.candidates.isEmpty()) Text("경로 1.5km 안에 쓸 수 있는 급속 충전소가 없어요", color = TextSub, fontSize = 18.sp)
            plan.candidates.forEach { c -> CandidateRow(c, c.stop.station.id == plan.recommendedId, onSelect, onGo) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Button(
                onClick = { onGo(null) },
                colors = ButtonDefaults.buttonColors(containerColor = TextMain, contentColor = Bg),
                shape = RoundedCornerShape(14.dp),
            ) { Text("바로 안내", fontSize = 20.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) }
            OutlinedButton(onClick = onClose, shape = RoundedCornerShape(14.dp)) {
                Text("닫기", color = TextMain, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
    }
}

/** "18.9km 지점 · 용산 전쟁기념관 [추천]" / "도착 배터리 약 12% · 경로에서 865m · 사용 가능 2/4" */
@Composable
private fun CandidateRow(c: RouteCandidate, recommended: Boolean, onSelect: (String) -> Unit, onGo: (String?) -> Unit) {
    val id = c.stop.station.id.value
    val st = c.stop.status
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (recommended) Bg else Panel)
            .clickable { onSelect(id) }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(14.dp).height(14.dp).clip(RoundedCornerShape(7.dp)).background(st.verdict.color()))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${km(c.alongKm)} 지점 · ${c.stop.station.name}", color = TextMain, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (recommended) {
                    Spacer(Modifier.width(8.dp))
                    Text("추천", color = Bg, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Green).padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
            val free = st.chargers.count { it.publicState == PublicState.AVAILABLE }
            val parts = listOfNotNull(
                c.arrivalSoc?.let { if (it <= 0) "도착 전 배터리 소진" else "도착 배터리 약 $it%" },
                "경로에서 ${c.detourM}m",
                st.chargers.takeIf { it.isNotEmpty() }?.let { "사용 가능 $free/${it.size}" },
            )
            Text(parts.joinToString(" · "), color = if (c.reachable) TextSub else Red, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        if (c.reachable) {
            OutlinedButton(onClick = { onGo(id) }, shape = RoundedCornerShape(12.dp)) { Text("들렀다 가기", color = TextMain, fontSize = 16.sp) }
        } else {
            Text("가기 어려움", color = Red, fontSize = 16.sp)
        }
    }
}

private fun km(v: Double) = if (v < 10) "%.1fkm".format(v) else "${Math.round(v)}km"
