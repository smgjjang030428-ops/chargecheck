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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chargecheck.core.model.Verdict

/** 주차 중: 현재 내비의 판단을 먼저, 주변 탐색과 집계는 그다음에 보여 준다. */
@Composable
internal fun SituationOverviewPanel(
    modifier: Modifier,
    overview: OverviewUi,
    recent: List<MapStation>,
    toast: String?,
    onSelect: (String?) -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(Panel)
            .verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(if (overview.routeName != null) "내비가 고른 충전소" else "도착 전 충전소 확인", color = TextSub, fontSize = 19.sp)
        if (overview.routeName != null) {
            Text(overview.routeName, color = TextMain, fontSize = 34.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val color = when (overview.routeVerdict) {
                    Verdict.GREEN -> Green
                    Verdict.YELLOW -> Yellow
                    Verdict.RED -> Red
                    else -> Gray
                }
                Box(Modifier.size(14.dp).clip(RoundedCornerShape(7.dp)).background(color))
                Text(overview.routeTitle ?: "현재 상태를 확인하고 있어요", color = TextMain, fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium)
            }
            overview.routeEvidence?.let { Text(it, color = TextSub, fontSize = 21.sp) }
            Text("최근 충전 확인이 현재 빈자리나 충전 성공을 보장하지는 않아요", color = TextSub, fontSize = 17.sp)
        } else {
            Text("내비가 고른 충전소를\n미리 확인해 드려요", color = TextMain, fontSize = 34.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
            Text("목적지는 평소처럼 기본 내비에 설정하세요. 주의가 필요할 때 대안을 알려드려요.", color = TextSub, fontSize = 21.sp)
        }
        overview.headline?.let {
            Text(it, color = TextMain, fontSize = 19.sp)
        }
        HorizontalDivider(color = Gray.copy(alpha = 0.35f), modifier = Modifier.padding(vertical = 6.dp))
        Text("주변의 최근 확인 기록", color = TextMain, fontSize = 23.sp, fontWeight = FontWeight.Medium)
        if (recent.isEmpty()) {
            Text("아직 최근 충전 확인 기록이 없어요. 지도에서 운영사 상태를 따로 확인할 수 있어요.", color = TextSub, fontSize = 19.sp)
        } else {
            recent.take(3).forEach { station ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSelect(station.id) }.padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(station.name, color = TextMain, fontSize = 23.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(station.reason, color = TextSub, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        overview.area?.let { Text(it, color = TextSub, fontSize = 16.sp) }
        Text("미확인은 고장이 아니라 최근 확인 기록이 없다는 뜻이에요", color = TextSub, fontSize = 16.sp)
        if (toast != null) Text(toast, color = TextMain, fontSize = 20.sp)
        overview.pending?.let { Text(it, color = TextMain, fontSize = 19.sp) }
        Spacer(Modifier.height(4.dp))
        Text("충전 결과는 자동으로 기록해요 · 실패 확인은 정차할 때", color = TextSub, fontSize = 16.sp)
    }
}
