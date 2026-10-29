package com.chargecheck.feature.warning

/**
 * 경고에 함께 나오는 두 충전소 이름(가려던 곳 → 대안)을 서로 구분되게 줄인다. 공공 데이터 이름은
 * "경기성남 SW드림타운 2"처럼 지역 낱말로 시작하는 일이 많아 앞에서부터 자르면 둘 다 "경기성남 SW…"가 된다
 * (에뮬레이터 2026-10-28). 1) 두 이름이 같은 첫 낱말로 시작하면 그 낱말을 뺀다. 2) 그래도 앞에서 자른 모양이
 * 같으면 뒤쪽(번호가 있는 쪽)을 남긴다.
 */
fun distinctNames(a: String, b: String, maxA: Int = Int.MAX_VALUE, maxB: Int = maxA): Pair<String, String> {
    val (x, y) = dropSharedFirstWord(a, b)
    val m = minOf(maxA, maxB)
    if (cut(x, m) != cut(y, m)) return cut(x, maxA) to cut(y, maxB)
    return cutTail(x, maxA) to cutTail(y, maxB)
}

private fun dropSharedFirstWord(a: String, b: String): Pair<String, String> {
    if (a == b || ' ' !in a || ' ' !in b) return a to b
    val x = a.substringAfter(' ').trim()
    val y = b.substringAfter(' ').trim()
    return if (a.substringBefore(' ') == b.substringBefore(' ') && x.isNotEmpty() && y.isNotEmpty()) x to y else a to b
}

private fun cut(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"

private fun cutTail(s: String, max: Int) = if (s.length <= max) s else "…" + s.takeLast(max - 1)
