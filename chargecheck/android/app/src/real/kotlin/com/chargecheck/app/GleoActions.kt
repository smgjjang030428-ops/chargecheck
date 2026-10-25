package com.chargecheck.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.chargecheck.core.model.FailReason
import com.chargecheck.core.model.NearbyStation
import com.chargecheck.core.model.Place
import com.chargecheck.core.model.ReviewTag
import com.chargecheck.core.model.Station
import com.chargecheck.core.model.Verdict
import com.chargecheck.core.model.distanceMetersTo
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

private const val TAG = "ChargeCheck/Gleo"

/** Gleo Interface 대답 액션(Pleos 문서 "Code Details with Examples"). */
private const val GLEO_RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"

/** 글레오는 7초 안에 대답이 없으면 취소한다. 그 전에 실패라도 돌려준다. */
private const val REPLY_TIMEOUT_MS = 6_000L

/** 글레오에게 돌려줄 대답. status = success / asking / fail, message = 짧은 영어 문장(장소 이름은 한국어 그대로). */
internal data class GleoReply(val status: String, val message: String, val data: JSONArray? = null)

/**
 * 글레오 AI가 운전자 말을 바꿔 보낸 기능 3개를 처리한다(assets/schema.json).
 * 버튼과 같은 기능을 부른다: 경유지 추가 = RealGraph.addStop, 고장 기록 = SessionCoordinator.reportFail.
 * 글레오는 운전자가 말을 걸 때만 부르므로, 먼저 알릴 일은 카드·알림음이 맡는다.
 */
internal class GleoActions(private val g: RealGraph) {

    suspend fun handle(function: String, p: JSONObject): GleoReply = when (function) {
        "CHECK_CHARGERS" -> check(p)
        "ADD_CHARGER_STOP" -> addStop(p)
        "REPORT_CHARGER_PROBLEM" -> report(p)
        else -> GleoReply("fail", "ChargeCheck does not support $function.")
    }

    // ------------------------------------------------------------ CHECK_CHARGERS

    private suspend fun check(p: JSONObject): GleoReply {
        val filter = Filter(p.optDouble("min_kw", 0.0).toInt(), p.optBoolean("free_parking", false), p.optString("connector", "any"))
        return when (p.optString("scope", "route")) {
            "nearby" -> nearby(filter)
            "destination" -> destination()
            else -> route(filter)
        }
    }

    private suspend fun route(filter: Filter): GleoReply {
        val dest = g.currentDestination()
            ?: return GleoReply("asking", "No destination is set in the navigation. Set a destination first, or ask for chargers nearby.")
        val plan = g.planRoute(Place(dest.name, "", dest.location), dest.remainM).getOrElse {
            return GleoReply("fail", "Could not reach the ChargeCheck server.")
        }
        val ok = plan.candidates.filter { filter.accepts(it.stop) }
        val pick = ok.firstOrNull { it.stop.station.id == plan.recommendedId }
            ?: ok.filter { it.reachable }.minByOrNull { rank(it.stop.status.verdict) }
        val range = if (plan.needsCharge) "You may not reach ${dest.name} without charging." else "You can reach ${dest.name} without charging."
        if (pick == null) return GleoReply("success", "$range There is no reliable fast charger matching the request along the way.", top(ok.map { it.stop }))
        remember(pick.stop.station)
        val arrival = pick.arrivalSoc?.let { " Expected battery on arrival is about $it%." }.orEmpty()
        return GleoReply(
            "success",
            "$range The best stop is ${pick.stop.station.name}, about ${pick.alongKm.roundToInt()} km ahead. ${verdictSentence(pick.stop)}$arrival",
            top(ok.map { it.stop }),
        )
    }

    private suspend fun nearby(filter: Filter): GleoReply {
        val car = g.navi.lastKnown() ?: DEFAULT_AREA
        val list = g.stations.value.filter { filter.accepts(it) && it.status.verdict != Verdict.RED }
            .sortedWith(compareBy({ rank(it.status.verdict) }, { it.station.location.distanceMetersTo(car) }))
        val best = list.firstOrNull() ?: return GleoReply("success", "There is no reliable charger matching the request nearby.")
        remember(best.station)
        val m = best.station.location.distanceMetersTo(car).roundToInt()
        return GleoReply("success", "The most reliable charger nearby is ${best.station.name}, ${distanceText(m)} away. ${verdictSentence(best)}", top(list))
    }

    private suspend fun destination(): GleoReply {
        val dest = g.currentDestination() ?: return GleoReply("asking", "No destination is set in the navigation.")
        val station = g.resolve(dest) ?: return GleoReply("success", "The destination ${dest.name} is not a charging station ChargeCheck knows.")
        val status = g.api.stationStatus(station.id).getOrElse { return GleoReply("fail", "Could not reach the ChargeCheck server.") }
        val item = NearbyStation(station, g.stations.value.firstOrNull { it.station.id == station.id }?.info ?: com.chargecheck.core.model.StationInfo(), status)
        return GleoReply("success", "${station.name}: ${verdictSentence(item)}")
    }

    // ------------------------------------------------------------ ADD_CHARGER_STOP

    private suspend fun addStop(p: JSONObject): GleoReply {
        val station = recent() ?: run {
            // 방금 추천한 곳이 없으면 같은 기준으로 다시 고른다
            val r = if (p.optString("scope", "route") == "nearby") nearby(Filter()) else route(Filter())
            if (r.status != "success") return r
            recent()
        } ?: return GleoReply("success", "There is no reliable charger to add as a stop.")
        return if (g.addStop(station)) GleoReply("success", "Added ${station.name} as a waypoint.")
        else GleoReply("fail", "The navigation did not accept the waypoint.")
    }

    // ------------------------------------------------------------ REPORT_CHARGER_PROBLEM

    private suspend fun report(p: JSONObject): GleoReply {
        val station = g.stationHere()
            ?: return GleoReply("fail", "The car is not at a charging station, so nothing was recorded.")
        val problem = p.optString("problem", "NOT_CHARGING")
        val charger = p.optString("charger_number", "").filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.padStart(2, '0')

        // 케이블 짧음·느림은 고장이 아니라 충전소 특징(리뷰 태그)
        val tag = when (problem) {
            "CABLE_SHORT" -> ReviewTag.CABLE_SHORT
            "SLOW" -> ReviewTag.SLOW_SPEED
            else -> null
        }
        if (tag != null) {
            g.api.postReview(station.id, listOf(tag))
            g.session.note("글레오: ${station.name} 리뷰 태그 $tag")
            return GleoReply("success", "Noted for ${station.name}: ${if (tag == ReviewTag.CABLE_SHORT) "the cable is short" else "charging is slow"}.")
        }

        val reason = runCatching { FailReason.valueOf(problem) }.getOrDefault(FailReason.NOT_CHARGING)
        val last = g.session.lastFail
        val sameVisit = last != null && last.stationId == station.id && Duration.between(last.occurredAt, Instant.now()) < Duration.ofHours(2)
        // 같은 충전소의 실패를 이미 올렸으면(출발 확인·버튼·앞선 말) 새로 올리지 않고 이유·번호를 고친다
        val ok = if (sameVisit) g.session.amendLastFail(charger, reason) else g.session.reportFail(station, reason, charger)
        g.refreshStations()
        if (!ok) return GleoReply("fail", "Could not save the report. It will not be counted.")
        val what = charger?.let { "charger ${it.trimStart('0')}" } ?: "the charger"
        val verb = if (sameVisit) "Updated the report" else "Recorded"
        return GleoReply("success", "$verb: $what at ${station.name}, ${reasonText(reason)}. Other drivers will be warned. If it was a different problem, just say it again.")
    }

    // ------------------------------------------------------------ 도우미

    /** 방금(10분 안) 추천한 충전소. "거기 들렀다 가자"의 '거기'. */
    private fun remember(station: Station) {
        lastSuggestion = station to Instant.now()
    }

    private fun recent(): Station? = lastSuggestion?.takeIf { Duration.between(it.second, Instant.now()) < Duration.ofMinutes(10) }?.first

    private data class Filter(val minKw: Int = 0, val freeParking: Boolean = false, val connector: String = "any") {
        fun accepts(s: NearbyStation): Boolean {
            if (freeParking && s.info.parkingFree != true) return false
            val usable = s.status.chargers.filter { it.verdict != Verdict.RED }
            if (minKw > 0 && usable.none { (it.outputKw ?: 0) >= minKw }) return false
            val name = CONNECTORS[connector] ?: return true
            return usable.any { c -> c.connectors.any { it.contains(name) } }
        }
    }

    private fun rank(v: Verdict) = when (v) {
        Verdict.GREEN -> 0
        Verdict.GRAY -> 1
        Verdict.YELLOW -> 2
        Verdict.RED -> 3
    }

    private fun verdictSentence(s: NearbyStation): String {
        val st = s.status
        val free = st.chargers.count { it.publicState == com.chargecheck.core.model.PublicState.AVAILABLE }
        val base = when (st.verdict) {
            Verdict.GREEN -> st.lastSuccessAt?.let { "A car charged there successfully ${Duration.between(it, Instant.now()).toMinutes()} minutes ago." }
                ?: "It was confirmed working recently."
            Verdict.GRAY -> "No recent check-ins; the operator shows $free of ${st.chargers.size} chargers available."
            Verdict.YELLOW -> "Some chargers had recent failures, but others are usable."
            Verdict.RED -> "Several cars failed to charge there recently."
        }
        return base + (st.notice?.let { " Note: a non-EV car was reported blocking it recently." } ?: "")
    }

    private fun reasonText(r: FailReason) = when (r) {
        FailReason.NOT_CHARGING -> "not charging"
        FailReason.BROKEN -> "broken"
        FailReason.PAYMENT -> "payment failed"
        FailReason.BLOCKED -> "blocked by a parked car (not counted as a charger fault)"
        FailReason.OTHER -> "other problem"
    }

    private fun distanceText(m: Int) = if (m < 1000) "$m m" else "%.1f km".format(m / 1000.0)

    /** 글레오가 더 말할 때 쓸 상위 3곳(이름·판정). */
    private fun top(list: List<NearbyStation>) = JSONArray().apply {
        list.take(3).forEach { put(JSONObject().put("name", it.station.name).put("verdict", it.status.verdict.name)) }
    }

    companion object {
        private var lastSuggestion: Pair<Station, Instant>? = null
        private val CONNECTORS = mapOf("DC_COMBO" to "DC콤보", "CHADEMO" to "DC차데모", "AC3" to "AC3상", "AC_SLOW" to "AC완속")
    }
}

/**
 * 글레오가 보내는 Gleo Action 수신기(화면 없이 처리). 기능 이름 = 인텐트 액션, 말 내용 = ACTION_PARAM(JSON).
 * 처리 결과는 APP_ACTION_RESPONSE 브로드캐스트로 돌려준다(글레오가 이 문장으로 대답을 만든다).
 */
class GleoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val function = intent.action ?: return
        val params = runCatching { JSONObject(intent.getStringExtra("ACTION_PARAM") ?: "{}") }.getOrDefault(JSONObject())
        val pending = goAsync()
        val g = RealGraph.get(context)
        g.scope.launch {
            val reply = withTimeoutOrNull(REPLY_TIMEOUT_MS) { runCatching { GleoActions(g).handle(function, params) }.getOrNull() }
                ?: GleoReply("fail", "ChargeCheck could not finish in time.")
            context.sendBroadcast(Intent(GLEO_RESPONSE).apply {
                putExtra("packageName", context.packageName)
                putExtra("response", JSONObject().apply {
                    put("functionName", function)
                    put("status", reply.status)
                    put("message", reply.message)
                    reply.data?.let { put("data", it.toString()) }
                }.toString())
            })
            Log.d(TAG, "$function $params → ${reply.status}: ${reply.message}")
            g.session.note("글레오 대답(${function}): ${reply.message}")
            pending.finish()
        }
    }
}
