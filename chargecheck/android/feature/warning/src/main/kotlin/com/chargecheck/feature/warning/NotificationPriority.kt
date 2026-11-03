package com.chargecheck.feature.warning

/** 화면 밖 알림도 사용자에게 필요한 한 장을 먼저 정한다. 선언 순서가 우선순위다. */
enum class NotificationKind { WARNING, ROUTE, CONFIRM, ASK, PICK, NOTICE }

/**
 * 주행 중에는 위험 회피와 충전 필요, 사용자가 요청한 변경 진행 안내만 허용한다.
 * 실패 확인·현장 질문·충전기 번호는 정차 때 표시한다.
 */
fun selectNotificationKind(
    available: Set<NotificationKind>,
    isParked: Boolean,
    allowDrivingNotice: Boolean = false,
): NotificationKind? = NotificationKind.entries.firstOrNull { kind ->
    kind in available && (isParked || kind == NotificationKind.WARNING || kind == NotificationKind.ROUTE ||
        kind == NotificationKind.NOTICE && allowDrivingNotice)
}
