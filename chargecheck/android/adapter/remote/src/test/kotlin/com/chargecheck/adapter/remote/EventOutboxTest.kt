package com.chargecheck.adapter.remote

import com.chargecheck.core.model.ChargeEvent
import com.chargecheck.core.model.DetectMode
import com.chargecheck.core.model.ChargeResult
import com.chargecheck.core.model.DeviceId
import com.chargecheck.core.model.StationId
import com.chargecheck.core.port.DuplicateEventException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 서버 오류·연결 실패 때 충전 기록이 사라지지 않고 같은 eventId로 다시 보내지는지(작업지시서 R1). */
class EventOutboxTest {

    private val store = object : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }
    private var clock = 1_000_000L

    /** 서버가 차례로 돌려줄 결과. 보낸 본문을 [sent]에 남긴다. */
    private val replies = ArrayDeque<Result<String>>()
    private val sent = mutableListOf<JSONObject>()

    private fun adapter() = RemoteApiAdapter("http://test", DeviceId("device-12345678"), store = store, now = { clock }) { _, _, body ->
        body?.let { sent += JSONObject(it.toString()) }
        replies.removeFirst()
    }

    private fun http(code: Int, retryAfter: Long? = null) = Result.failure<String>(HttpStatusException(code, retryAfter, "HTTP $code"))
    private val accepted = Result.success("""{"accepted": true}""")
    private val duplicate = Result.success("""{"accepted": false, "reason": "DUPLICATE"}""")

    private val event = ChargeEvent(
        eventId = "ev-1", deviceId = DeviceId("device-12345678"), stationId = StationId("ST1"), result = ChargeResult.SUCCESS, mode = DetectMode.LIVE,
        socStart = 30, socEnd = 55, minutes = 20, occurredAt = Instant.parse("2026-10-29T03:00:00Z"),
    )

    @Test
    fun `503 다음 500이어도 남고, 202가 오면 그 건만 지운다 · eventId와 발생 시각은 그대로`() = runTest {
        val api = adapter()
        replies += http(503)
        api.postEvent(event)
        assertEquals(1, api.pendingCount())

        replies += http(500)
        assertEquals(0, api.flushOutbox())
        assertEquals(1, api.pendingCount())

        replies += accepted
        assertEquals(1, api.flushOutbox())
        assertEquals(0, api.pendingCount())
        assertEquals(setOf("ev-1"), sent.map { it.getString("eventId") }.toSet())
        assertEquals(setOf("2026-10-29T03:00:00Z"), sent.map { it.getString("occurredAt") }.toSet())
        assertEquals(setOf("SUCCESS"), sent.map { it.getString("result") }.toSet())
    }

    @Test
    fun `연결이 끊겨 응답을 못 받았으면 다시 보내고, 서버가 이미 받았다고(중복) 하면 끝낸다`() = runTest {
        val api = adapter()
        replies += Result.failure(IOException("timeout"))
        assertTrue(api.postEvent(event).isFailure)
        replies += duplicate
        assertEquals(0, api.flushOutbox()) // 새로 받은 건 아니다
        assertEquals(0, api.pendingCount())
    }

    @Test
    fun `처음 보낼 때 중복이면 대기열에 넣지 않는다`() = runTest {
        val api = adapter()
        replies += duplicate
        assertIs<DuplicateEventException>(api.postEvent(event).exceptionOrNull())
        assertEquals(0, api.pendingCount())
    }

    @Test
    fun `429는 Retry-After가 지나기 전에는 다시 보내지 않는다`() = runTest {
        val api = adapter()
        replies += http(429, retryAfter = 120)
        api.postEvent(event)
        clock += 60_000
        assertEquals(0, api.flushOutbox())
        assertEquals(1, sent.size) // 아직 다시 보내지 않음
        clock += 61_000
        replies += accepted
        assertEquals(1, api.flushOutbox())
        assertEquals(2, sent.size)
    }

    @Test
    fun `401은 성공으로 치거나 지우지 않고 한 시간 멈춘다`() = runTest {
        val api = adapter()
        replies += http(401)
        api.postEvent(event)
        assertEquals(1, api.pendingCount())
        assertEquals(0, api.flushOutbox())
        assertEquals(1, sent.size)
        clock += 61 * 60_000
        replies += accepted
        assertEquals(1, api.flushOutbox())
    }

    @Test
    fun `422는 다시 보내지 않지만 지우지도 않는다`() = runTest {
        val api = adapter()
        replies += http(422)
        api.postEvent(event)
        assertEquals(0, api.pendingCount())
        assertEquals(1, api.rejectedCount())
        clock += 24 * 60 * 60_000L
        api.flushOutbox()
        assertEquals(1, sent.size)
        assertEquals(1, api.rejectedCount())
    }

    @Test
    fun `응답에 accepted가 없으면 같은 eventId로 다시 보내고, 다섯 번째에는 멈춘다`() = runTest {
        val api = adapter()
        replies += Result.success("<html>proxy</html>")
        api.postEvent(event)
        repeat(4) {
            replies += Result.success("{}")
            api.flushOutbox()
        }
        assertEquals(5, sent.size)
        assertEquals(0, api.flushOutbox()) // 멈춤: 보내지 않음
        assertEquals(5, sent.size)
        assertEquals(1, api.pendingCount())
    }

    @Test
    fun `같은 eventId는 두 번 쌓지 않고, 앱을 다시 켜도(새 어댑터) 남아 있다`() = runTest {
        replies += http(503)
        adapter().postEvent(event)
        replies += http(503)
        adapter().postEvent(event)
        assertEquals(1, JSONArray(store.map.getValue("outbox_events")).length())

        val restarted = adapter()
        assertEquals(1, restarted.pendingCount())
        replies += accepted
        assertEquals(1, restarted.flushOutbox())
    }

    @Test
    fun `예전 형식(본문 문자열) 대기열도 읽어서 보낸다`() = runTest {
        store.put("outbox_events", JSONArray(listOf("""{"eventId":"old-1","stationId":"ST1"}""")).toString())
        val api = adapter()
        assertEquals(1, api.pendingCount())
        replies += accepted
        assertEquals(1, api.flushOutbox())
        assertEquals("old-1", sent.single().getString("eventId"))
    }
}
