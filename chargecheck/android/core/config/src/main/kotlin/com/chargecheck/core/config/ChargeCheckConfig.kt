package com.chargecheck.core.config

import com.chargecheck.core.model.ChargerType
import java.time.Duration

/**
 * 모든 임계값은 여기서만 읽는다. 숫자를 코드 곳곳에 하드코딩하지 않는다.
 * 테스트에서 값을 바꿔 끼울 수 있도록 data class로 둔다. 실제 앱은 [DEFAULT]를 쓴다.
 */
data class ChargeCheckConfig(
    /** 충전소 좌표에서 이 거리 안이면 '현장'. */
    val onsiteRadiusM: Int = 50,
    /** 이만큼 정차해야 세션 시작. */
    val onsiteMinStop: Duration = Duration.ofMinutes(5),
    /** 이만큼 벗어나면 세션 종료. */
    val leftRadiusM: Int = 150,
    /** LIVE: 기준 대비 이만큼(%p) 오르면 성공. */
    val successDeltaLivePct: Int = 1,
    /** RETRO: 재시동 시 이만큼(%p) 이상 올랐으면 성공. */
    val successDeltaRetroPct: Int = 2,
    /** 급속: 이만큼 SOC가 그대로면 질문. */
    val stallTimeoutFast: Duration = Duration.ofMinutes(10),
    /** 완속·미상: 1%에 수 분 걸리므로 길게. */
    val stallTimeoutSlow: Duration = Duration.ofMinutes(40),
    /** "아직 연결 중" 답하면 연장. */
    val waitExtension: Duration = Duration.ofMinutes(10),
    /** RETRO 실패 질문을 하려면 최소 경과 시간. */
    val retroMinElapsed: Duration = Duration.ofMinutes(15),
    /** 이보다 오래된 스냅샷은 폐기. */
    val retroMaxElapsed: Duration = Duration.ofHours(24),
    /** SOC를 아직 못 읽었을 때 다시 확인하는 간격. */
    val socRetryInterval: Duration = Duration.ofMinutes(1),
    val askTimeoutSec: Int = 8,
    /** 같은 기기·충전소 이벤트 중복 방지(서버와 동일). */
    val dedupWindow: Duration = Duration.ofHours(2),
    val greenWindow: Duration = Duration.ofHours(3),
    val grayWindow: Duration = Duration.ofHours(24),
) {
    fun stallTimeout(type: ChargerType): Duration = when (type) {
        ChargerType.FAST -> stallTimeoutFast
        ChargerType.SLOW, ChargerType.UNKNOWN -> stallTimeoutSlow
    }

    companion object {
        val DEFAULT = ChargeCheckConfig()
    }
}
