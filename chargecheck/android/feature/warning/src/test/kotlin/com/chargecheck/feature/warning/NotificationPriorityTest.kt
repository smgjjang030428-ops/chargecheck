package com.chargecheck.feature.warning

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationPriorityTest {
    @Test
    fun `경고와 기록 질문이 겹치면 경고 한 장만 선택한다`() {
        assertEquals(NotificationKind.WARNING, selectNotificationKind(NotificationKind.entries.toSet(), isParked = false))
        assertEquals(NotificationKind.WARNING, selectNotificationKind(NotificationKind.entries.toSet(), isParked = true))
    }

    @Test
    fun `충전 필요 안내는 기록 수집 질문보다 우선한다`() {
        assertEquals(NotificationKind.ROUTE, selectNotificationKind(setOf(NotificationKind.ROUTE, NotificationKind.CONFIRM, NotificationKind.ASK), isParked = true))
    }

    @Test
    fun `주행 중 데이터 수집 질문과 일반 결과 안내는 표시하지 않는다`() {
        assertNull(selectNotificationKind(setOf(NotificationKind.CONFIRM, NotificationKind.ASK, NotificationKind.PICK, NotificationKind.NOTICE), isParked = false))
    }

    @Test
    fun `정차하면 보류된 실패 확인을 선택하고 그 다음 질문을 처리한다`() {
        val available = setOf(NotificationKind.CONFIRM, NotificationKind.ASK, NotificationKind.PICK)
        assertEquals(NotificationKind.CONFIRM, selectNotificationKind(available, isParked = true))
        assertEquals(NotificationKind.ASK, selectNotificationKind(available - NotificationKind.CONFIRM, isParked = true))
        assertEquals(NotificationKind.PICK, selectNotificationKind(setOf(NotificationKind.PICK), isParked = true))
    }

    @Test
    fun `사용자가 요청한 내비 변경 결과는 주행 중에도 허용하되 경고보다 앞서지 않는다`() {
        assertEquals(NotificationKind.NOTICE, selectNotificationKind(setOf(NotificationKind.NOTICE), false, allowDrivingNotice = true))
        assertEquals(NotificationKind.WARNING, selectNotificationKind(setOf(NotificationKind.NOTICE, NotificationKind.WARNING), false, allowDrivingNotice = true))
    }
}
