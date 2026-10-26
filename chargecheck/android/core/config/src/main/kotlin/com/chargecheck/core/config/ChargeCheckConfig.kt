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
    /** 내비 없이 충전구를 열었을 때 이 거리 안 충전소를 찾는다(좌표 오차·큰 주차장을 감안해 onsiteRadiusM보다 넓게). */
    val portRadiusM: Int = 80,
    /** 가장 가까운 두 충전소의 거리 차이가 이보다 작으면 어느 쪽인지 애매하다고 본다(실패는 묻지 않는다). */
    val ambiguousGapM: Int = 20,
    /** 같은 충전소에서 실패 확인에 '아니요'를 이만큼 답하면 그곳에서는 더 묻지 않는다(집·회사 등). */
    val confirmMuteAfterNo: Int = 2,
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
    /** 이만큼 머물렀다가 잔량 변화 없이 출발하면 '꽂아 봤는데 안 됨'일 수 있어 확인한다. 더 짧으면 그냥 들른 것으로 본다. */
    val attemptMinStop: Duration = Duration.ofMinutes(2),
    /** 출발할 때 못 받은 실패 확인을 다음 주차 때 다시 물을 수 있는 시간. */
    val confirmWindow: Duration = Duration.ofHours(2),
    /** 주행 중 확인 카드를 보여주는 시간. 지나면 숨기고 다음 주차 때 다시 묻는다. */
    val confirmDrivingShow: Duration = Duration.ofSeconds(45),
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
