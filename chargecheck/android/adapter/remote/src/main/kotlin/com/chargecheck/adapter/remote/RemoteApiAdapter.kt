package com.chargecheck.adapter.remote

import com.chargecheck.core.model.ChargeEvent
import com.chargecheck.core.model.ChargerStatus
import com.chargecheck.core.model.ChargerType
import com.chargecheck.core.model.DeviceId
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

/**
 * ChargeCheck 서버 REST API (문서/앱구조_명세서_AI용.md §5.2).
 * 의존성을 늘리지 않으려고 HttpURLConnection + org.json(안드로이드 내장)으로 썼다.
 *
 * @param baseUrl 에뮬레이터에서 개발 PC의 서버는 "http://10.0.2.2:8000".
 */
class RemoteApiAdapter(
    private val baseUrl: String,
    private val deviceId: DeviceId,
    private val appVersion: String = "0.1.0",
) : ChargeCheckApiPort {

    override suspend fun postEvent(event: ChargeEvent): Result<Unit> = call("POST", "/v1/events", JSONObject().apply {
        put("eventId", event.eventId)
        put("stationId", event.stationId.value)
        put("result", event.result.name)
        put("mode", event.mode.name)
        put("socStart", event.socStart)
        put("socEnd", event.socEnd)
        put("minutes", event.minutes)
        put("occurredAt", event.occurredAt.toString())
        event.chargerId?.let { put("chargerId", it) }
    }).map { }

    override suspend fun attachCharger(eventId: String, chargerId: String): Result<Unit> =
        call("PATCH", "/v1/events/${enc(eventId)}", JSONObject().put("chargerId", chargerId)).map { }

    override suspend fun stationStatus(id: StationId): Result<StationStatus> =
        call("GET", "/v1/stations/${enc(id.value)}/status").map { status(JSONObject(it)) }

    override suspend fun nearbyStations(at: GeoPoint, radiusM: Int): Result<List<Station>> =
        call("GET", "/v1/stations/nearby?lat=${at.lat}&lon=${at.lon}&radius=$radiusM").map { body ->
            JSONArray(body).objects().map { station(it) }
        }

    /** 주변 충전소와 상세 정보·판정을 한 번에(지도 화면용). */
    suspend fun nearbyWithStatus(at: GeoPoint, radiusM: Int): Result<List<NearbyStation>> =
        call("GET", "/v1/stations/nearby?lat=${at.lat}&lon=${at.lon}&radius=$radiusM").map { body ->
            JSONArray(body).objects().map { NearbyStation(station(it), info(it), status(it.getJSONObject("status"))) }
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
    suspend fun alongRoute(from: GeoPoint, to: Place, rangeKm: Double?, soc: Int?): Result<RoutePlan> {
        val q = buildString {
            append("fromLat=${from.lat}&fromLon=${from.lon}&toLat=${to.location.lat}&toLon=${to.location.lon}")
            rangeKm?.let { append("&rangeKm=$it") }
            soc?.let { append("&soc=$it") }
        }
        return call("GET", "/v1/stations/along-route?$q").map { body ->
            val o = JSONObject(body)
            val path = o.getJSONArray("route")
            RoutePlan(
                destination = to,
                routeKm = o.getDouble("routeKm"),
                roadRoute = o.getString("source") == "ROAD",
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

    private suspend fun call(method: String, path: String, body: JSONObject? = null): Result<String> = withContext(Dispatchers.IO) {
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
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = conn.responseCode
                val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                check(code < 400) { "HTTP $code $method $path: $text" }
                text
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
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
