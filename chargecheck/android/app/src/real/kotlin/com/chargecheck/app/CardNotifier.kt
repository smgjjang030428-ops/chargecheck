package com.chargecheck.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.chargecheck.core.port.VoiceIntent
import com.chargecheck.feature.session.ChargeSessionMachine
import com.chargecheck.feature.session.stationId
import com.chargecheck.feature.warning.distinctNames
import com.chargecheck.feature.warning.conciseEvidence
import com.chargecheck.feature.warning.warningTitle
import com.chargecheck.feature.warning.NotificationKind
import com.chargecheck.feature.warning.selectNotificationKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 앱 화면이 안 보일 때(운전자가 기본 내비를 보는 중) 카드를 내비 위 알림(헤드업)으로 띄운다. 기획 §9.
 * 화면이 보이면 같은 카드가 화면 안에 나오므로 알림은 지운다. 버튼은 최대 2개, 누르면 [CardActionReceiver]가 처리한다.
 *
 * 후보를 모두 모은 뒤 도착 전 경고 > 충전 필요 > 정차 중 확인·질문·번호 > 짧은 안내 순으로 한 장만 표시한다.
 * 주행 중에는 데이터 수집 질문과 일반 결과 안내를 숨기고, 사용자가 요청한 내비 변경 진행·결과만 허용한다.
 *
 * 주행 중에는 내비 길 안내 위에 뜨므로(위치는 Pleos가 정한다) 한눈(2초)에 읽히게 제목·내용을 한 줄로 짧게 쓰고,
 * 버튼 카드도 누르지 않으면 사라진다: 가는 길 충전소 15초, 도착 전 경고 30초. 정차 중 질문은 남겨 둔다.
 */
internal class CardNotifier(private val context: Context, private val graph: RealGraph) {

    private val nm = context.getSystemService(NotificationManager::class.java)
    private val settings = AppSettings(context)
    private val notice = MutableStateFlow<Notice?>(null)
    private var parkedNow = false

    fun start() {
        createChannels(context)
        val visible = AppVisibility.foreground
        // 같은 카드를 다시 그리지 않도록 '카드 내용'이 바뀔 때만 알림을 고친다
        val parked = graph.drivingState.isParked()
        val warningCards = combine(graph.warning.warning, parked) { w, isParked ->
            w?.let { warn ->
                val alt = warn.alternative
                // 두 이름이 똑같이 잘리지 않게 함께 줄인다("경기성남 SW…" → "SW드림타운 2"/"SW드림타운 1").
                // 이름은 내용 줄(두 줄까지)에 14자씩 쓰고 버튼은 "대안으로 바꾸기"로 고정한다(버튼 폭이 좁아 이름을 넣으면 잘리고 줄이 바뀜)
                val (stationName, altName) = alt?.let { distinctNames(warn.station.name, it.name, 14, 14) } ?: (short(warn.station.name, 14) to null)
                val altText = altName?.let { a -> " → $a" + (warn.alternativeStatus?.let { " · ${conciseEvidence(it)}" } ?: "") }.orEmpty()
                Slot(NotificationKind.WARNING, ID_WARNING, Card(
                    warningTitle(warn.status),
                    "$stationName · ${conciseEvidence(warn.status)}$altText",
                    listOfNotNull(
                        altName?.let { "대안으로 바꾸기" to "${KIND_WARNING}:${VoiceIntent.SWITCH}" },
                        "현재 경로 유지" to "${KIND_WARNING}:${VoiceIntent.KEEP}",
                    ),
                    timeoutMs = if (isParked) 0 else WARNING_MS,
                ))
            }
        }
        val confirmCards = graph.session.confirm.combine(parked) { c, isParked ->
            c?.takeIf { it.visible && isParked }?.let {
                Slot(NotificationKind.CONFIRM, ID_CONFIRM, Card("충전이 안 됐나요?", short(it.stationName), listOf("안 됐어요" to "$KIND_CONFIRM:yes", "아니요" to "$KIND_CONFIRM:no")))
            }
        }
        val askCards = combine(graph.session.question, parked) { q, isParked ->
            q?.takeIf { isParked }?.let {
                val buttons = if (it.retro) {
                    listOf("응" to "$KIND_ASK:${VoiceIntent.YES}", "아니요" to "$KIND_ASK:${VoiceIntent.NO}")
                } else {
                    listOf("응, 안 돼요" to "$KIND_ASK:${VoiceIntent.YES}", "아직 연결 중" to "$KIND_ASK:${VoiceIntent.WAIT}")
                }
                Slot(NotificationKind.ASK, ID_ASK, Card(if (it.retro) "아까 충전이 안 됐나요?" else "충전이 안 되나요?", sessionStationName()?.let { n -> short(n) } ?: "차지체크", buttons))
            }
        }
        val routeCards = combine(graph.plan, parked) { p, isParked ->
            p?.takeIf { settings.routeCards }?.let { plan ->
                val rec = plan.candidates.firstOrNull { it.stop.station.id == plan.recommendedId }
                val timeout = if (isParked) 0L else ROUTE_MS
                if (rec == null) {
                    Slot(NotificationKind.ROUTE, ID_ROUTE, Card("충전소 확인이 필요해요", "현재 조건에서 도달 가능한 충전소를 찾지 못했어요", listOf("확인" to "$KIND_ROUTE:no"), timeout))
                } else {
                    Slot(NotificationKind.ROUTE, ID_ROUTE, Card(
                        "가는 길에 충전이 필요해요",
                        "${rec.alongKm.toInt()}km 앞 ${short(rec.stop.station.name)} · ${conciseEvidence(rec.stop.status)}",
                        listOf("들렀다 가기" to "$KIND_ROUTE:go", "괜찮아요" to "$KIND_ROUTE:no"),
                        timeout,
                    ))
                }
            }
        }
        // 실패 뒤 "몇 번 충전기였나요?": 차가 서 있을 때만. 2대면 번호 버튼, 많으면 [모르겠어요] + 눌러서 앱에서 고르기
        val pickCards = combine(graph.session.chargerPick, parked) { pick, isParked ->
            pick?.takeIf { isParked }?.let {
                val buttons = if (it.chargers.size == 2) {
                    it.chargers.map { c -> "${c.chargerId}번" to "$KIND_PICK:${c.chargerId}" }
                } else {
                    listOf("모르겠어요" to "$KIND_PICK:")
                }
                Slot(NotificationKind.PICK, ID_PICK, Card("실패 기록 · 몇 번 충전기였나요?", "${short(it.stationName)} · 누르면 목록에서 골라요", buttons))
            }
        }
        // 독립 알림으로 발행하지 않는다. 후보 중 한 장을 선택하고 나머지 ID는 항상 취소한다.
        val candidates = combine(listOf(warningCards, routeCards, confirmCards, askCards, pickCards)) { it.filterNotNull() }
        graph.scope.launch {
            combine(candidates, notice, visible, parked) { cards, shortNotice, fg, isParked ->
                parkedNow = isParked
                if (fg || !settings.overlayCards) null else {
                    val all = cards + listOfNotNull(shortNotice?.slot)
                    val kind = selectNotificationKind(all.map { it.kind }.toSet(), isParked, shortNotice?.allowDriving == true)
                    all.firstOrNull { it.kind == kind }
                }
            }.distinctUntilChanged().collect { selected ->
                CARD_IDS.filter { it != selected?.id }.forEach(nm::cancel)
                if (selected != null) post(selected.id, selected.card, selected.card.timeoutMs,
                    if (selected.kind == NotificationKind.NOTICE) CHANNEL_NOTICES else CHANNEL_CARDS)
            }
        }
        graph.scope.launch {
            graph.notices.collect { text -> notice(text, allowDriving = true) }
        }
        // 충전 성공·실패 기록, 목표 충전량 도달 같은 결과 안내도 내비 위에 잠깐
        graph.scope.launch {
            graph.session.toast.collect { text ->
                if (text == null) return@collect
                // 정상 충전 안내는 처음 SUCCESS_NOTICE_LIMIT번만(앱이 일하고 있다는 걸 알 만큼). 그다음은 조용히 기록만
                if (text == ChargeSessionMachine.TOAST_SUCCESS) {
                    if (!parkedNow) return@collect
                    if (settings.successNotices >= SUCCESS_NOTICE_LIMIT) return@collect
                    settings.successNotices += 1
                }
                notice(text, sessionStationName())
            }
        }
    }

    /** 결과 안내(무음, 8초). 앱 안 문장을 그대로 쓰지 않고 한 줄로 줄인다. */
    private fun notice(text: String, detail: String? = null, allowDriving: Boolean = false) {
        if (!AppVisibility.foreground.value && settings.overlayCards) {
            val title = when {
                text == ChargeSessionMachine.TOAST_SUCCESS -> "정상 충전 1건 기록"
                text.startsWith("경유지에 ") -> "경유지 추가 · 내비가 새 길로 안내"
                text.contains("충전 실패를 기록") -> "충전 실패 기록 · 다음 차에 알려요"
                else -> text
            }
            val sub = detail ?: WAYPOINT.find(text)?.groupValues?.get(1) ?: text.substringBefore(":", "").ifEmpty { null }
            val line = listOfNotNull("차지체크", sub?.let { short(it) }).joinToString(" · ")
            val item = Notice(Slot(NotificationKind.NOTICE, ID_NOTICE, Card(title, line, emptyList(), NOTICE_MS)), allowDriving)
            notice.value = item
            graph.scope.launch {
                delay(NOTICE_MS)
                if (notice.value === item) notice.value = null
            }
        }
    }

    /** 긴 충전소 이름은 줄인다(주행 중 한 줄에 들어가게). */
    private fun short(name: String, max: Int = 14) = if (name.length <= max) name else name.take(max - 1) + "…"

    /** 지금 세션의 충전소 이름("이 충전소에…" 안내에 붙인다). */
    private fun sessionStationName(): String? {
        val id = graph.session.state.value.stationId() ?: return null
        return graph.stations.value.firstOrNull { it.station.id == id }?.station?.name
    }

    private data class Card(val title: String, val text: String, val buttons: List<Pair<String, String>>, val timeoutMs: Long = 0)
    private data class Slot(val kind: NotificationKind, val id: Int, val card: Card)
    private data class Notice(val slot: Slot, val allowDriving: Boolean)

    private fun post(id: Int, card: Card, timeoutMs: Long = 0, channel: String = CHANNEL_CARDS) {
        val n = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_chargecheck)
            .setContentTitle(card.title)
            .setContentText(card.text)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setContentIntent(openAppIntent(context))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .apply { if (timeoutMs > 0) setTimeoutAfter(timeoutMs) }
        card.buttons.forEachIndexed { i, (label, action) ->
            val intent = Intent(context, CardActionReceiver::class.java).setAction(ACTION_CARD).putExtra(EXTRA_CHOICE, action).putExtra(EXTRA_ID, id)
            val pi = PendingIntent.getBroadcast(context, id * 10 + i, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            n.addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_chargecheck), label, pi).build())
        }
        nm.notify(id, n.build())
    }

    companion object {
        const val SUCCESS_NOTICE_LIMIT = 3
        const val ROUTE_MS = 15_000L
        const val WARNING_MS = 30_000L
        const val NOTICE_MS = 8_000L
        private val WAYPOINT = Regex("^경유지에 (.+) 추가")
        const val ID_ROUTE = 10
        const val ID_WARNING = 11
        const val ID_CONFIRM = 12
        const val ID_ASK = 13
        const val ID_NOTICE = 14
        const val ID_PICK = 15
        private val CARD_IDS = listOf(ID_WARNING, ID_ROUTE, ID_CONFIRM, ID_ASK, ID_PICK, ID_NOTICE)
        const val KIND_ROUTE = "route"
        const val KIND_WARNING = "warning"
        const val KIND_CONFIRM = "confirm"
        const val KIND_ASK = "ask"
        const val KIND_PICK = "pick"
        const val ACTION_CARD = "com.chargecheck.app.CARD"
        const val EXTRA_CHOICE = "choice"
        const val EXTRA_ID = "id"
    }
}

/** 알림 카드의 버튼. "종류:선택" 문자열을 받아 화면 버튼과 같은 함수를 부른다. */
class CardActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val (kind, choice) = intent.getStringExtra(CardNotifier.EXTRA_CHOICE)?.split(":", limit = 2)?.takeIf { it.size == 2 } ?: return
        context.getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra(CardNotifier.EXTRA_ID, 0))
        val pending = goAsync()
        val g = RealGraph.get(context)
        g.scope.launch {
            try {
                when (kind) {
                    CardNotifier.KIND_ROUTE -> if (choice == "go") g.addRecommendedStop() else g.dismissRoute()
                    CardNotifier.KIND_WARNING -> g.warning.choose(VoiceIntent.valueOf(choice))
                    CardNotifier.KIND_CONFIRM -> g.session.answerConfirm(choice == "yes")
                    CardNotifier.KIND_ASK -> g.session.answer(VoiceIntent.valueOf(choice))
                    CardNotifier.KIND_PICK -> g.session.pickCharger(choice.ifEmpty { null })
                }
                g.session.note("알림 카드 버튼: $kind/$choice")
            } finally {
                pending.finish()
            }
        }
    }
}
