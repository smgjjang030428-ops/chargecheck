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
import com.chargecheck.ui.withRo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 앱 화면이 안 보일 때(운전자가 기본 내비를 보는 중) 카드를 내비 위 알림(헤드업)으로 띄운다. 기획 §9.
 * 화면이 보이면 같은 카드가 화면 안에 나오므로 알림은 지운다. 버튼은 최대 2개, 누르면 [CardActionReceiver]가 처리한다.
 *
 * 띄우는 카드(우선순위 순서는 화면과 같다): 도착 전 경고 > 출발 확인 > 현장 질문 > 가는 길 충전소, 주차 중 충전기 번호.
 * 짧은 안내(8초): 경유지 추가, 실패 기록(번호 질문이 없을 때만), 정상 기록(처음 3번만), 목표 충전량 도달.
 */
internal class CardNotifier(private val context: Context, private val graph: RealGraph) {

    private val nm = context.getSystemService(NotificationManager::class.java)
    private val settings = AppSettings(context)

    fun start() {
        createChannels(context)
        val visible = AppVisibility.foreground
        // 같은 카드를 다시 그리지 않도록 '카드 내용'이 바뀔 때만 알림을 고친다
        show(ID_WARNING, combine(graph.warning.warning, visible) { w, fg ->
            w?.takeUnless { fg }?.let { warn ->
                val alt = warn.alternative
                Card(
                    "가는 충전소에 최근 실패 기록이 있어요",
                    "${warn.station.name} · ${warn.reasonText}",
                    listOfNotNull(
                        alt?.let { "${it.name.withRo()} 바꾸기" to "${KIND_WARNING}:${VoiceIntent.SWITCH}" },
                        "그대로" to "${KIND_WARNING}:${VoiceIntent.KEEP}",
                    ),
                )
            }
        })
        show(ID_CONFIRM, combine(graph.session.confirm, visible) { c, fg ->
            c?.takeIf { it.visible && !fg }?.let {
                Card(it.question, "답하면 다음 차가 헛걸음하지 않아요", listOf("응, 안 됐어요" to "$KIND_CONFIRM:yes", "아니요" to "$KIND_CONFIRM:no"))
            }
        })
        show(ID_ASK, combine(graph.session.question, visible) { q, fg ->
            q?.takeUnless { fg }?.let {
                val buttons = if (it.retro) {
                    listOf("응" to "$KIND_ASK:${VoiceIntent.YES}", "아니요" to "$KIND_ASK:${VoiceIntent.NO}")
                } else {
                    listOf("응, 안 돼요" to "$KIND_ASK:${VoiceIntent.YES}", "아직 연결 중" to "$KIND_ASK:${VoiceIntent.WAIT}")
                }
                Card(it.question, "차지체크", buttons)
            }
        })
        show(ID_ROUTE, combine(graph.plan, visible) { p, fg ->
            p?.takeUnless { fg || !settings.routeCards }?.toCard()?.let { card ->
                Card(card.title, card.detail, listOfNotNull(("들렀다 가기" to "$KIND_ROUTE:go").takeIf { card.canGo }, "괜찮아요" to "$KIND_ROUTE:no"))
            }
        })
        // 실패 뒤 "몇 번 충전기였나요?": 차가 서 있을 때만. 2대면 번호 버튼, 많으면 [모르겠어요] + 눌러서 앱에서 고르기
        val parked = graph.drivingState.isParked()
        show(ID_PICK, combine(graph.session.chargerPick, visible, parked) { pick, fg, isParked ->
            pick?.takeIf { !fg && isParked }?.let {
                val buttons = if (it.chargers.size == 2) {
                    it.chargers.map { c -> "${c.chargerId}번" to "$KIND_PICK:${c.chargerId}" }
                } else {
                    listOf("모르겠어요" to "$KIND_PICK:")
                }
                Card("충전 실패를 기록했어요 · 몇 번 충전기였나요?", "${it.stationName} · 눌러서 고르거나 글레오에게 '15번이었어'라고 말해 주세요", buttons)
            }
        })
        graph.scope.launch {
            graph.notices.collect { text -> notice(text) }
        }
        // 충전 성공·실패 기록, 목표 충전량 도달 같은 결과 안내도 내비 위에 잠깐
        graph.scope.launch {
            graph.session.toast.collect { text ->
                if (text == null) return@collect
                // 정상 충전 안내는 처음 SUCCESS_NOTICE_LIMIT번만(앱이 일하고 있다는 걸 알 만큼). 그다음은 조용히 기록만
                if (text == ChargeSessionMachine.TOAST_SUCCESS) {
                    if (settings.successNotices >= SUCCESS_NOTICE_LIMIT) return@collect
                    settings.successNotices += 1
                }
                notice(text, sessionStationName())
            }
        }
    }

    private fun notice(text: String, detail: String? = null) {
        if (!AppVisibility.foreground.value && settings.overlayCards) {
            post(ID_NOTICE, Card(text, listOfNotNull("차지체크", detail).joinToString(" · "), emptyList()), timeoutMs = 8_000)
        }
    }

    /** 지금 세션의 충전소 이름("이 충전소에…" 안내에 붙인다). */
    private fun sessionStationName(): String? {
        val id = graph.session.state.value.stationId() ?: return null
        return graph.stations.value.firstOrNull { it.station.id == id }?.station?.name
    }

    private data class Card(val title: String, val text: String, val buttons: List<Pair<String, String>>)

    private fun show(id: Int, cards: Flow<Card?>) {
        graph.scope.launch {
            cards.distinctUntilChanged().collect { card ->
                if (card == null || !settings.overlayCards) nm.cancel(id) else post(id, card)
            }
        }
    }

    private fun post(id: Int, card: Card, timeoutMs: Long = 0) {
        val n = Notification.Builder(context, CHANNEL_CARDS)
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
        const val ID_ROUTE = 10
        const val ID_WARNING = 11
        const val ID_CONFIRM = 12
        const val ID_ASK = 13
        const val ID_NOTICE = 14
        const val ID_PICK = 15
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
