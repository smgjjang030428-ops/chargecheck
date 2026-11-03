package com.chargecheck.feature.warning

import com.chargecheck.core.model.ReasonCode
import com.chargecheck.core.model.StationId
import com.chargecheck.core.model.StationStatus
import com.chargecheck.core.model.Verdict
import java.time.Instant
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EvidencePresentationTest {
    private val now = Instant.parse("2026-11-02T03:00:00Z")
    private fun status(reason: ReasonCode, fails: Int = 0) = StationStatus(
        StationId("A"), Verdict.RED, reason, now.minusSeconds(12 * 60), fails, 1, 2, emptyList(),
    )

    @Test
    fun `운영사 점검을 차량 실패 기록으로 안내하지 않는다`() {
        val s = status(ReasonCode.PUBLIC_OUT_OF_SERVICE)
        assertEquals("가는 충전소가 운영 중지예요", warningTitle(s))
        assertEquals("운영사 점검·운영 중지", conciseEvidence(s, now))
        assertFalse(conciseEvidence(s, now).contains("12분")) // 과거 성공 시각은 점검 시각이 아니다
    }

    @Test
    fun `동일한 초록이라도 운영사 근거와 차량 근거를 구분한다`() {
        assertEquals("운영사 충전 중 확인 · 12분 전", conciseEvidence(status(ReasonCode.PUBLIC_CHARGING).copy(verdict = Verdict.GREEN), now))
        assertEquals("차량 SOC로 충전 확인 · 12분 전", conciseEvidence(status(ReasonCode.RECENT_SUCCESS).copy(verdict = Verdict.GREEN), now))
    }

    @Test
    fun `실패 건수와 확인 불가를 색으로 추정하지 않는다`() {
        assertEquals("충전 실패 제보 2건", conciseEvidence(status(ReasonCode.MULTI_FAIL, 2), now))
        assertEquals("최근 확인 기록 없음", conciseEvidence(status(ReasonCode.NO_RECENT_DATA), now))
        assertEquals("충전 실패 제보", conciseEvidence(status(ReasonCode.MULTI_FAIL), now))
    }

    @Test
    fun `성공 시각이 없으면 임의의 시각을 만들지 않는다`() {
        assertEquals("차량 SOC로 충전 확인", conciseEvidence(status(ReasonCode.RECENT_SUCCESS).copy(lastSuccessAt = null), now))
    }
}
