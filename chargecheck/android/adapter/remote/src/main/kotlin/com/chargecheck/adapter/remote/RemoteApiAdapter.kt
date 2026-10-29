package com.chargecheck.adapter.remote

import com.chargecheck.core.model.ChargeEvent
import com.chargecheck.core.model.ChargerStatus
import com.chargecheck.core.model.ChargerType
import com.chargecheck.core.model.DeviceId
import com.chargecheck.core.model.FailReason
import com.chargecheck.core.model.GeoPoint
import com.chargecheck.core.model.NearbyStation
import com.chargecheck.core.model.Place
import com.chargecheck.core.model.PublicState
import com.chargecheck.core.model.ReasonCode
import com.chargecheck.core.model.ReviewTag
import com.chargecheck.core.model.RouteCandidate
import com.chargecheck.core.model.RoutePlan
import com.chargecheck.core.model.Station
import com.chargecheck.core.model.StationId
import com.chargecheck.core.model.StationInfo
import com.chargecheck.core.model.StationStatus
import com.chargecheck.core.model.Verdict
import com.chargecheck.core.port.ChargeCheckApiPort
import com.chargecheck.core.port.DuplicateEventException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

/** 앱을 다시 켜도 남아야 하는 값(보내지 못한 기록, 마지막 충전소 목록). 앱에서는 SharedPreferences. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** 서버가 오류 코드로 답했다. [code]로 다시 보낼지 정한다. [retryAfterSec] = 429·503의 Retry-After(초). */
class HttpStatusException(val code: Int, val retryAfterSec: Long?, message: String) : IllegalStateException(message)

/** 서버가 2xx로 답했는데 받았는지(accepted)를 읽을 수 없다. 받았을 수도 있어 같은 eventId로 다시 보낸다. */
class UnclearResponseException(message: String) : IllegalStateException(message)

/** 보낸 결과에 따라 대기열에서 할 일. */
internal enum class SendOutcome { ACCEPTED, DUPLICATE, RETRY, HOLD_AUTH, REJECTED, UNCLEAR }

internal fun outcomeOf(r: Result<Unit>): SendOutcome {
    val e = r.exceptionOrNull() ?: return SendOutcome.ACCEPTED
    return when {
        e is DuplicateEventException -> SendOutcome.DUPLICATE
        e is IOException -> SendOutcome.RETRY
        e is UnclearResponseException -> SendOutcome.UNCLEAR
        e is HttpStatusException && (e.code == 408 || e.code == 429 || e.code >= 500) -> SendOutcome.RETRY
        e is HttpStatusException && (e.code == 401 || e.code == 403) -> SendOutcome.HOLD_AUTH
        e is HttpStatusException -> SendOutcome.REJECTED
        else -> SendOutcome.UNCLEAR
    }
}

/**
 * ChargeCheck 서버 REST API (기획문서/차지체크_앱_구동_기획.md §10).
 * 의존성을 늘리지 않으려고 HttpURLConnection + org.json(안드로이드 내장)으로 썼다.
 *
 * 오프라인(기획 §14): 서버에 닿지 못했거나 서버가 잠깐 오류를 낸 충전 기록은 [store]에 모아 두었다가 [flushOutbox]로
 * 같은 eventId 그대로 다시 보낸다. 서버는 같은 eventId를 두 번 저장하지 않는다. 주변 충전소는 마지막으로 받은 목록을
 * 대신 보여준다. 연결 상태는 [online].
 *
 * @param baseUrl 에뮬레이터에서 개발 PC의 서버는 "http://10.0.2.2:8000".
 * @param transport 테스트에서 HTTP 대신 쓴다. (method, path, body) → 응답 본문 또는 [HttpStatusException]·IOException.
 */
class RemoteApiAdapter(
    private val baseUrl: String,
    private val deviceId: DeviceId,
    private val appVersion: String = "0.1.0",
    private val store: KeyValueStore? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val transport: (suspend (String, String, JSONObject?) -> Result<String>)? = null,
) : ChargeCheckApiPort {

    private val _online = MutableStateFlow(true)

    /** 마지막 요청이 서버에 닿았는지. 응답 코드가 오류여도 닿았으면 true. */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val outboxLock = Mutex()

    /** 아직 서버가 받지 않은 충전 기록 수(다시 보낼 것 + 인증 문제로 멈춘 것). 서버가 거절한 기록은 세지 않는다. */
    fun pendingCount(): Int = outbox().count { it.state != STATE_REJECTED }

    /** 서버가 요청 형식 오류(400·404·422 등)로 거절해 더 보내지 않는 기록 수. 지우지 않고 남겨 둔다. */
    fun rejectedCount(): Int = outbox().count { it.state == STATE_REJECTED }

    /** 성공 = 서버가 새 기록으로 받음. 실패해도 다시 보낼 수 있는 기록은 대기열에 남는다. */
    override suspend fun postEvent(event: ChargeEvent): Result<Unit> {
        val body = eventJson(event)
        val r = sendEvent(body)
        val outcome = outcomeOf(r)
        if (outcome != SendOutcome.ACCEPTED && outcome != SendOutcome.DUPLICATE) {
            outboxLock.withLock {
                val items = outbox()
                if (items.none { it.eventId == event.eventId }) saveOutbox(items + after(Pending(body), outcome, r))
            }
        }
        return r
    }

    /** 모아 둔 기록 중 보낼 때가 된 것을 보낸다. 서버가 받았거나 중복이라 답한 것만 지운다. 새로 받아 준 건수를 돌려준다. */
    suspend fun flushOutbox(): Int = outboxLock.withLock {
        var sent = 0
        val left = outbox().mapNotNull { item ->
            if (item.state == STATE_REJECTED || item.nextAt > now()) return@mapNotNull item
            val r = sendEvent(item.body)
            when (val outcome = outcomeOf(r)) {
                SendOutcome.ACCEPTED -> { sent++; null }
                SendOutcome.DUPLICATE -> null
                else -> after(item, outcome, r)
            }
        }
        saveOutbox(left)
        sent
    }

    /** 보내지 못한 기록의 다음 상태. eventId·발생 시각·결과(body)는 그대로 둔다. */
    private fun after(item: Pending, outcome: SendOutcome, r: Result<Unit>): Pending {
        val attempts = item.attempts + 1
        val retryAfter = (r.exceptionOrNull() as? HttpStatusException)?.retryAfterSec?.takeIf { it > 0 }
        return when {
            outcome == SendOutcome.REJECTED -> item.copy(attempts = attempts, state = STATE_REJECTED)
            outcome == SendOutcome.HOLD_AUTH || (outcome == SendOutcome.UNCLEAR && attempts >= MAX_UNCLEAR_ATTEMPTS) ->
                item.copy(attempts = attempts, state = STATE_HELD, nextAt = now() + HOLD_MS)
            else -> item.copy(attempts = attempts, state = STATE_PENDING, nextAt = now() + (retryAfter?.times(1_000) ?: 0))
        }
    }

    private suspend fun sendEvent(body: JSONObject): Result<Unit> = call("POST", "/v1/events", body).mapCatching { text ->
        // 받음 = {"accepted": true}, 같은 eventId·같은 기기·충전소 2시간 안 기록 = {"accepted": false, "reason": "DUPLICATE"}
        val o = runCatching { JSONObject(text) }.getOrNull()
        if (o == null || !o.has("accepted")) throw UnclearResponseException("accepted 없음: ${text.take(80)}")
        if (!o.optBoolean("accepted")) throw DuplicateEventException()
    }

    /** 대기열 한 건. 예전 형식(본문 문자열만)도 읽는다. */
    private data class Pending(val body: JSONObject, val attempts: Int = 0, val nextAt: Long = 0, val state: String = STATE_PENDING) {
        val eventId: String get() = body.optString("eventId")
        fun toJson(): JSONObject = JSONObject().put("body", body).put("attempts", attempts).put("nextAt", nextAt).put("state", state)
    }

    private fun outbox(): List<Pending> {
        val a = store?.get(OUTBOX)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            when (val v = a.get(i)) {
                is JSONObject -> Pending(v.getJSONObject("body"), v.optInt("attempts"), v.optLong("nextAt"), v.optString("state", STATE_PENDING))
                is String -> runCatching { Pending(JSONObject(v)) }.getOrNull()
                else -> null
            }
        }
    }

    private fun saveOutbox(items: List<Pending>) =
        store?.put(OUTBOX, if (items.isEmpty()) null else JSONArray(items.map { it.toJson() }).toString())

    private fun eventJson(event: ChargeEvent) = JSONObject().apply {
        put("eventId", event.eventId)
        put("stationId", event.stationId.value)
        put("result", event.result.name)
        put("mode", event.mode.name)
        put("socStart", event.socStart)
        put("socEnd", event.socEnd)
        put("minutes", event.minutes)
        put("occurredAt", event.occurredAt.toString())
        event.chargerId?.let { put("chargerId", it) }
        event.reason?.let { put("reason", it.name) }
    }

    override suspend fun attachCharger(eventId: String, chargerId: String): Result<Unit> =
        updateEvent(eventId, chargerId, null)

    override suspend fun updateEvent(eventId: String, chargerId: String?, reason: FailReason?): Result<Unit> =
        call("PATCH", "/v1/events/${enc(eventId)}", JSONObject().apply {
            chargerId?.let { put("chargerId", it) }
            reason?.let { put("reason", it.name) }
        }).map { }

    override suspend fun stationStatus(id: StationId): Result<StationStatus> =
        call("GET", "/v1/stations/${enc(id.value)}/status").map { status(JSONObject(it)) }

    override suspend fun nearbyStations(at: GeoPoint, radiusM: Int): Result<List<Station>> =
        call("GET", "/v1/stations/nearby?lat=${at.lat}&lon=${at.lon}&radius=$radiusM").map { body ->
            JSONArray(body).objects().map { station(it) }
        }

    /** 마지막 [nearbyWithStatus]가 서버 응답 대신 저장해 둔 목록을 돌려줬는지. */
    @Volatile
    var nearbyFromCache: Boolean = false
        private set

    /** 주변 충전소 목록을 서버에서 마지막으로 받은 시각. */
    fun nearbyReceivedAt(): Instant? = store?.get(NEARBY_CACHE_AT)?.toLongOrNull()?.let(Instant::ofEpochMilli)

    /** 주변 충전소와 상세 정보·판정을 한 번에(지도 화면용). 서버에 닿지 못했거나 서버 오류면 마지막으로 받은 목록. */
    suspend fun nearbyWithStatus(at: GeoPoint, radiusM: Int): Result<List<NearbyStation>> {
        val r = call("GET", "/v1/stations/nearby?lat=${at.lat}&lon=${at.lon}&radius=$radiusM")
        r.getOrNull()?.let { body ->
            store?.put(NEARBY_CACHE, body)
            store?.put(NEARBY_CACHE_AT, now().toString())
        }
        val e = r.exceptionOrNull()
        val serverDown = e is IOException || (e is HttpStatusException && e.code >= 500)
        nearbyFromCache = r.isFailure
        val body = r.getOrNull() ?: store?.get(NEARBY_CACHE)?.takeIf { serverDown }
            ?: return Result.failure(r.exceptionOrNull() ?: IOException("no data"))
        return runCatching { JSONArray(body).objects().map { NearbyStation(station(it), info(it), status(it.getJSONObject("status"))) } }
    }

    /** 지역·장소 검색. [near]를 주면 이름이 맞는 충전소를 가까운 순으로 함께 준다. */
    suspend fun searchPlaces(query: String, near: GeoPoint?): Result<List<Place>> {
        val at = near?.let { "&lat=${it.lat}&lon=${it.lon}" }.orEmpty()
        return call("GET", "/v1/places/search?q=${enc(query)}$at").map { body ->
            JSONArray(body).objects().map {
                Place(
                    name = it.getString("name"),
                    address = it.optString("address"),
                    location = GeoPoint(it.getDouble("lat"), it.getDouble("lon")),
                    stationId = if (it.isNull("stationId")) null else StationId(it.getString("stationId")),
                )
            }
        }
    }

    /** 목적지까지 가는 길의 충전소. 차의 남은 주행 가능 거리·배터리를 주면 갈 수 있는지와 추천 지점이 붙는다. */
    suspend fun alongRoute(from: GeoPoint, to: Place, rangeKm: Double?, soc: Int?, roadKm: Double? = null): Result<RoutePlan> {
        val q = buildString {
            append("fromLat=${from.lat}&fromLon=${from.lon}&toLat=${to.location.lat}&toLon=${to.location.lon}")
            rangeKm?.let { append("&rangeKm=$it") }
            soc?.let { append("&soc=$it") }
            roadKm?.let { append("&roadKm=$it") }
        }
        return call("GET", "/v1/stations/along-route?$q").map { body ->
            val o = JSONObject(body)
            val path = o.getJSONArray("route")
            RoutePlan(
                destination = to,
                routeKm = o.getDouble("routeKm"),
                roadRoute = false, // 서버는 외부 경로 서버 없이 경로 근처(CORRIDOR)로 계산한다
                rangeKm = if (o.isNull("rangeKm")) null else o.getDouble("rangeKm"),
                soc = soc,
                needsCharge = o.getBoolean("needsCharge"),
                path = (0 until path.length()).map { i -> path.getJSONArray(i).let { GeoPoint(it.getDouble(0), it.getDouble(1)) } },
                candidates = o.getJSONArray("candidates").objects().map {
                    val s = it.getJSONObject("station")
                    RouteCandidate(
                        stop = NearbyStation(station(s), info(s), status(it.getJSONObject("status"))),
                        alongKm = it.getDouble("alongKm"),
                        detourM = it.getInt("detourM"),
                        reachable = it.getBoolean("reachable"),
                        arrivalSoc = it.intOrNull("arrivalSoc"),
                    )
                },
                recommendedId = if (o.isNull("recommendedId")) null else StationId(o.getString("recommendedId")),
            )
        }
    }

    override suspend fun alternatives(id: StationId, at: GeoPoint): Result<List<Pair<Station, StationStatus>>> =
        call("GET", "/v1/stations/${enc(id.value)}/alternatives?lat=${at.lat}&lon=${at.lon}").map { body ->
            JSONArray(body).objects().map { station(it.getJSONObject("station")) to status(it.getJSONObject("status")) }
        }

    override suspend fun postReview(stationId: StationId, tags: List<ReviewTag>): Result<Unit> =
        call("POST", "/v1/reviews", JSONObject().put("stationId", stationId.value).put("tags", JSONArray(tags.map { it.name }))).map { }

    private suspend fun call(method: String, path: String, body: JSONObject? = null): Result<String> =
        transport?.invoke(method, path, body) ?: http(method, path, body)

    private suspend fun http(method: String, path: String, body: JSONObject?): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 5_000
                readTimeout = 10_000
                setRequestProperty("X-Device-Id", deviceId.value)
                setRequestProperty("X-App-Version", appVersion)
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    try {
                        conn.outputStream.use { it.write(body.toString().toByteArray()) }
                    } catch (e: IOException) {
                        _online.value = false
                        throw e
                    }
                }
                val code = try {
                    conn.responseCode
                } catch (e: IOException) {
                    _online.value = false
                    throw e
                }
                _online.value = true
                val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code >= 400) throw HttpStatusException(code, conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull(), "HTTP $code $method $path: $text")
                text
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val OUTBOX = "outbox_events"
        const val NEARBY_CACHE = "nearby_cache"
        const val NEARBY_CACHE_AT = "nearby_cache_at"
        const val STATE_PENDING = "PENDING"
        /** 인증 오류(401·403)나 응답을 계속 못 읽어 잠시 멈춤. [HOLD_MS] 뒤 다시 보낸다. */
        const val STATE_HELD = "HELD"
        /** 요청 형식 오류로 서버가 거절. 다시 보내지 않지만 지우지도 않는다. */
        const val STATE_REJECTED = "REJECTED"
        const val HOLD_MS = 60 * 60 * 1_000L
        const val MAX_UNCLEAR_ATTEMPTS = 5
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

private fun JSONObject.instantOrNull(key: String): Instant? =
    if (isNull(key)) null else runCatching { Instant.parse(getString(key)) }.getOrNull()

private fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null

private fun station(o: JSONObject) = Station(
    id = StationId(o.getString("stationId")),
    name = o.getString("name"),
    location = GeoPoint(o.getDouble("lat"), o.getDouble("lon")),
    chargerType = enumOr(o.optString("chargerType"), ChargerType.UNKNOWN),
)

private fun info(o: JSONObject) = StationInfo(
    address = o.optString("address"),
    operator = o.optString("operator"),
    operatorCall = o.optString("operatorCall"),
    useTime = o.optString("useTime"),
    parkingFree = if (o.has("parkingFree") && !o.isNull("parkingFree")) o.getBoolean("parkingFree") else null,
    limitDetail = o.optString("limitDetail"),
    payment = if (o.has("payment") && !o.isNull("payment")) o.getString("payment") else null,
)

private fun status(o: JSONObject) = StationStatus(
    stationId = StationId(o.getString("stationId")),
    verdict = enumOr(o.getString("verdict"), Verdict.GRAY),
    reasonCode = enumOr(o.getString("reasonCode"), ReasonCode.NO_RECENT_DATA),
    lastSuccessAt = o.instantOrNull("lastSuccessAt"),
    recentFailCount = o.optInt("recentFailCount"),
    publicAvailable = o.intOrNull("publicAvailable"),
    publicTotal = o.intOrNull("publicTotal"),
    topTags = o.optJSONArray("topTags")?.let { a -> (0 until a.length()).mapNotNull { enumOrNull<ReviewTag>(a.getString(it)) } }.orEmpty(),
    message = o.optString("message"),
    okCount = o.intOrNull("okCount"),
    notice = if (o.has("notice") && !o.isNull("notice")) o.getString("notice") else null,
    chargers = o.optJSONArray("chargers")?.objects()?.map {
        ChargerStatus(
            chargerId = it.getString("chargerId"),
            chargerType = enumOr(it.optString("chargerType"), ChargerType.UNKNOWN),
            publicState = enumOr(it.optString("publicState"), PublicState.UNKNOWN),
            verdict = enumOr(it.optString("verdict"), Verdict.GRAY),
            recentFailCount = it.optInt("recentFailCount"),
            connectors = it.optJSONArray("connectors")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty(),
            outputKw = it.intOrNull("outputKw"),
        )
    }.orEmpty(),
)

/** 서버가 새 값을 보내도 앱이 죽지 않게, 모르는 값은 기본값으로. */
private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E = enumOrNull<E>(name) ?: default

private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? = enumValues<E>().firstOrNull { it.name == name }
