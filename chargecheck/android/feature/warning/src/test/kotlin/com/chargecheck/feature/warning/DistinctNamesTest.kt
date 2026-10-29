package com.chargecheck.feature.warning

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DistinctNamesTest {

    @Test
    fun `같은 지역 낱말로 시작하면 빼서 번호까지 보이게 한다`() {
        assertEquals("SW드림타운 2" to "SW드림타운 1", distinctNames("경기성남 SW드림타운 2", "경기성남 SW드림타운 1", 10, 8))
    }

    @Test
    fun `앞부분이 같아 잘린 모양이 같으면 뒤쪽을 남긴다`() {
        assertEquals("…스퀘어지하2층" to "…스퀘어지하3층", distinctNames("판교아이스퀘어지하2층", "판교아이스퀘어지하3층", 8))
    }

    @Test
    fun `이미 구분되면 앞에서부터 자른다`() {
        assertEquals("서초 급속…" to "강남 급속…", distinctNames("서초 급속충전소", "강남 급속충전소", 6))
        assertEquals("서초 급속충전소" to "강남 급속충전소", distinctNames("서초 급속충전소", "강남 급속충전소"))
    }
}
