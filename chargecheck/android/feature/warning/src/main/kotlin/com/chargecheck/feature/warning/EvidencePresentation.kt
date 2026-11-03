package com.chargecheck.feature.warning

import com.chargecheck.core.model.ReasonCode
import com.chargecheck.core.model.StationStatus
import java.time.Duration
import java.time.Instant

/** 앱 화면·주행 카드·알림에서 같은 판정 근거를 사용한다. 색만으로 근거를 추정하지 않는다. */
fun warningTitle(status: StationStatus): String = when (status.reasonCode) {
    ReasonCode.PUBLIC_OUT_OF_SERVICE -> "가는 충전소가 운영 중지예요"
    ReasonCode.PUBLIC_SAYS_OK_BUT_FAILS -> "사용 가능 표시와 다른 실패 제보가 있어요"
    ReasonCode.MULTI_FAIL -> "가는 충전소에 충전 실패 제보가 있어요"
    ReasonCode.FAIL_AFTER_SUCCESS -> "최근 충전 실패 제보가 있어요"
    ReasonCode.SOME_CHARGERS_FAIL -> "일부 충전기에 실패 제보가 있어요"
    ReasonCode.OLD_FAILS -> "이전 실패 뒤 확인 기록이 없어요"
    ReasonCode.REPAIRED_UNCONFIRMED -> "운영사 복구 후 충전 확인이 필요해요"
    ReasonCode.RECENT_SUCCESS, ReasonCode.PUBLIC_CHARGING -> "최근 충전 확인 근거가 있어요"
    ReasonCode.NO_RECENT_DATA -> "최근 실제 충전 확인 기록이 없어요"
}

/** 짧은 카드에서도 운영사 상태와 차량의 충전 결과를 구분한다. 과거 성공 시각을 실패 시각으로 사용하지 않는다. */
fun conciseEvidence(status: StationStatus, now: Instant = Instant.now()): String {
    val age = status.lastSuccessAt?.let { at ->
        val minutes = Duration.between(at, now).toMinutes().coerceAtLeast(0)
        " · " + if (minutes < 60) "${minutes}분 전" else "${minutes / 60}시간 전"
    }.orEmpty()
    val failures = if (status.recentFailCount > 0) " ${status.recentFailCount}건" else ""
    return when (status.reasonCode) {
        ReasonCode.RECENT_SUCCESS -> "차량 SOC로 충전 확인$age"
        ReasonCode.PUBLIC_CHARGING -> "운영사 충전 중 확인$age"
        ReasonCode.PUBLIC_OUT_OF_SERVICE -> "운영사 점검·운영 중지"
        ReasonCode.PUBLIC_SAYS_OK_BUT_FAILS -> "사용 가능 표시 · 충전 실패 제보$failures"
        ReasonCode.MULTI_FAIL, ReasonCode.FAIL_AFTER_SUCCESS -> "충전 실패 제보$failures"
        ReasonCode.SOME_CHARGERS_FAIL -> "일부 충전기 실패 제보"
        ReasonCode.OLD_FAILS -> "이전 실패 · 이후 확인 기록 없음"
        ReasonCode.REPAIRED_UNCONFIRMED -> "운영사 복구 · 충전 확인 전"
        ReasonCode.NO_RECENT_DATA -> "최근 확인 기록 없음"
    }
}
