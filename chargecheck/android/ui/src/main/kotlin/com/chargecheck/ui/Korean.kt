package com.chargecheck.ui

/** 받침 유무에 맞춰 "로"/"으로"를 붙인다. ㄹ 받침은 "로"(예: 서울로). 한글이 아니면 "로". */
internal fun String.withRo(): String {
    val last = lastOrNull() ?: return this
    if (last !in '가'..'힣') return "${this}로"
    val jong = (last - '가') % 28
    return if (jong == 0 || jong == 8) "${this}로" else "${this}으로"
}
