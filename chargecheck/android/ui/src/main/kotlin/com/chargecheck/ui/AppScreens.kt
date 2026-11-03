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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 처음 한 번: 무엇을 모으고 무엇을 안 모으는지 보여주고 동의를 받는다(기획 §11).
 * 동의 전에는 기록도 전송도 하지 않는다. 주차 중에만 보인다.
 */
@Composable
fun ConsentScreen(onAgree: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Bg).padding(48.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(0.7f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("차지체크", color = TextMain, fontSize = 40.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold)
            Text("충전할 때마다 차가 결과를 자동으로 남겨, 다음 차가 고장 난 충전기로 헛걸음하지 않게 해요.", color = TextMain, fontSize = 24.sp)
            Spacer(Modifier.height(4.dp))
            Fact("모으는 것", "충전소 · 충전 결과(됨/안 됨) · 충전 전후 배터리 % · 시각")
            Fact("모으지 않는 것", "이름 · 전화번호 · 차량 번호 · 이동 경로")
            Fact("기기 구분", "설치할 때 만든 임의 번호(서버에는 바꾼 값으로만 저장)")
            Fact("운전 중", "카드 한 장, 버튼 2개까지. 설정에서 언제든 끌 수 있어요")
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onAgree,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Green),
            ) { Text("동의하고 시작", fontSize = 26.sp, lineHeight = 34.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row {
        Text(label, color = TextSub, fontSize = 22.sp, modifier = Modifier.width(180.dp))
        Text(value, color = TextMain, fontSize = 22.sp)
    }
}

/** 설정 한 줄. */
data class SettingItem(val title: String, val detail: String, val on: Boolean, val onChange: (Boolean) -> Unit)

/** 설정 세 가지 + 기록 그만두기. 주차 중 화면 위에 덮는다. */
@Composable
fun SettingsSheet(items: List<SettingItem>, pending: Int, onWithdraw: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth(0.6f).clip(RoundedCornerShape(24.dp)).background(Panel).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("설정", color = TextMain, fontSize = 32.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold)
            items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = TextMain, fontSize = 24.sp)
                        Text(item.detail, color = TextSub, fontSize = 18.sp)
                    }
                    Switch(
                        checked = item.on,
                        onCheckedChange = item.onChange,
                        colors = SwitchDefaults.colors(checkedTrackColor = Green),
                    )
                }
            }
            if (pending > 0) Text("서버에 아직 못 보낸 기록 ${pending}건 · 연결되면 자동으로 보내요", color = TextSub, fontSize = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = onWithdraw, shape = RoundedCornerShape(14.dp)) {
                    Text("기록 그만두기", color = Red, fontSize = 22.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onClose, shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                    Text("닫기", fontSize = 22.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
                }
            }
        }
    }
}
