"""compute_verdict 규칙 테스트 (기획 §6). 규칙 이름의 번호(충전기 1~7, 충전소 a~g)는 이전 명세의 번호다."""
from datetime import datetime, timedelta, timezone

from app.services.verdict import ChargerIn, EventIn, compute_verdict

NOW = datetime(2026, 11, 10, 12, 0, tzinfo=timezone.utc)


def ago(minutes: int) -> datetime:
    return NOW - timedelta(minutes=minutes)


def chargers(*stats: str) -> list[ChargerIn]:
    """stats: 충전기마다 공공 stat 코드. 번호는 01, 02 ..."""
    return [ChargerIn(f"{i + 1:02d}", "04", s) for i, s in enumerate(stats)]


def fail(device: str, minutes_ago: int, charger: str | None = None) -> EventIn:
    return EventIn(device, "FAIL", ago(minutes_ago), charger)


def ok(device: str, minutes_ago: int, charger: str | None = None) -> EventIn:
    return EventIn(device, "SUCCESS", ago(minutes_ago), charger)


def test_g_기록이_없으면_GRAY와_공공_사용가능_대수():
    v = compute_verdict(chargers("2", "3", "2"), [], NOW)
    assert (v.verdict, v.reason_code) == ("GRAY", "NO_RECENT_DATA")
    assert (v.public_available, v.public_total, v.ok_count) == (2, 3, 3)
    assert "2/3" in v.message


def test_f_3시간_안_정상_기록이면_GREEN():
    v = compute_verdict(chargers("2", "2"), [ok("a", 30)], NOW)
    assert (v.verdict, v.reason_code) == ("GREEN", "RECENT_SUCCESS")
    assert v.message == "30분 전 실제로 충전됨"
    # 번호 없는 정상 기록은 실패 기록이 없는 충전기의 최근 성공으로 친다
    assert {c.verdict for c in v.chargers} == {"GREEN"}


def test_번호_없는_성공은_다른_충전기의_실패를_지우지_않는다():
    events = [fail("a", 50, "02"), fail("b", 40, "02"), ok("c", 10)]
    v = compute_verdict(chargers("2", "2", "2"), events, NOW)
    c2 = next(c for c in v.chargers if c.charger_id == "02")
    assert (c2.verdict, c2.reason_code, c2.cleared_by) == ("RED", "PUBLIC_SAYS_OK_BUT_FAILS", None)
    assert {c.verdict for c in v.chargers if c.charger_id != "02"} == {"GREEN"}
    assert (v.verdict, v.reason_code) == ("YELLOW", "SOME_CHARGERS_FAIL")  # 충전소 전체가 초록이 되지 않는다


def test_번호_있는_성공은_그_충전기의_실패를_지운다():
    events = [fail("a", 50, "02"), fail("b", 40, "02"), ok("c", 10, "02")]
    c2 = next(c for c in compute_verdict(chargers("2", "2"), events, NOW).chargers if c.charger_id == "02")
    assert (c2.verdict, c2.cleared_by) == ("GREEN", "USER_SUCCESS")


def test_핵심_6대_중_1대가_두_번_실패해도_충전소는_빨강이_아니라_노랑():
    events = [fail("a", 50, "02"), fail("b", 20, "02")]
    v = compute_verdict(chargers("2", "2", "2", "2", "2", "2"), events, NOW)
    assert (v.verdict, v.reason_code) == ("YELLOW", "SOME_CHARGERS_FAIL")
    assert v.message == "6대 중 1대에 최근 실패 기록"
    assert v.ok_count == 5
    c2 = next(c for c in v.chargers if c.charger_id == "02")
    assert (c2.verdict, c2.reason_code) == ("RED", "PUBLIC_SAYS_OK_BUT_FAILS")


def test_d_실패가_모든_충전기를_덮으면_빨강():
    events = [fail("a", 50, "01"), fail("b", 40, "01"), fail("c", 30, "02"), fail("d", 20, "02")]
    v = compute_verdict(chargers("2", "2"), events, NOW)
    assert (v.verdict, v.reason_code) == ("RED", "PUBLIC_SAYS_OK_BUT_FAILS")
    assert v.ok_count == 0


def test_d_번호_모르는_실패도_대수만큼_쌓이면_빨강():
    v = compute_verdict(chargers("2", "2"), [fail("a", 40), fail("b", 10)], NOW)
    assert v.verdict == "RED" and v.ok_count == 0


def test_d_공공이_사용가능_0이면_MULTI_FAIL():
    v = compute_verdict(chargers("3", "3"), [fail("a", 40), fail("b", 10)], NOW)
    assert (v.verdict, v.reason_code) == ("RED", "MULTI_FAIL")


def test_e_충전기_1대짜리_충전소는_실패_1건이면_FAIL_AFTER_SUCCESS():
    v = compute_verdict(chargers("2"), [fail("a", 10)], NOW)
    assert (v.verdict, v.reason_code) == ("YELLOW", "FAIL_AFTER_SUCCESS")


def test_c_모든_충전기가_운영중지면_빨강():
    v = compute_verdict(chargers("4", "5"), [], NOW)
    assert (v.verdict, v.reason_code) == ("RED", "PUBLIC_OUT_OF_SERVICE")


def test_1_운영중지_충전기_하나는_그_충전기만_빨강_충전소는_노랑():
    v = compute_verdict(chargers("2", "4"), [], NOW)
    assert v.verdict == "YELLOW" and v.ok_count == 1


def test_실패_뒤_정상_기록이_오면_회복():
    events = [fail("a", 90, "01"), fail("b", 80, "01"), ok("c", 30, "01")]
    v = compute_verdict(chargers("2", "2"), events, NOW)
    assert (v.verdict, v.reason_code) == ("GREEN", "RECENT_SUCCESS")


def test_같은_기기의_실패는_한_번만_센다():
    v = compute_verdict(chargers("2", "2", "2"), [fail("a", 50, "01"), fail("a", 10, "01")], NOW)
    c1 = next(c for c in v.chargers if c.charger_id == "01")
    assert c1.verdict == "YELLOW" and c1.recent_fail_count == 1


def test_24시간_지난_기록은_보지_않는다():
    v = compute_verdict(chargers("2", "2"), [fail("a", 25 * 60), fail("b", 26 * 60)], NOW)
    assert v.verdict == "GRAY"


def test_하루_실패_10건_넘는_기기는_판정에서_뺀다():
    spam = [fail("spammer", m) for m in range(5, 60, 5)]  # 11건
    v = compute_verdict(chargers("2", "2"), spam, NOW)
    assert v.verdict == "GRAY"


# ------------------------------------------------------------ 실패 이유 (10/25)

def test_일반차_주차_막힘은_실패로_세지_않고_안내만():
    blocked = [EventIn(d, "FAIL", ago(20), "01", "BLOCKED") for d in ("a", "b")]
    v = compute_verdict(chargers("2"), blocked, NOW)
    assert v.verdict == "GRAY" and v.recent_fail_count == 0
    assert v.notice == "20분 전 일반차 주차 막힘 제보"


def test_주차_막힘_안내는_1시간이_지나면_사라진다():
    v = compute_verdict(chargers("2"), [EventIn("a", "FAIL", ago(70), "01", "BLOCKED")], NOW)
    assert v.notice is None


def test_결제_실패는_실패로_세고_문구로_구분():
    v = compute_verdict(chargers("2"), [EventIn("a", "FAIL", ago(10), "01", "PAYMENT")], NOW)
    assert v.verdict == "YELLOW"
    assert v.message.endswith("결제 실패 제보 포함")


# --- 사용자가 다시 시도하지 않아도 빨강이 풀리는 신호 (기획 §6 고장 신고 루프) ---

def test_마지막_실패가_6시간_넘으면_빨강_대신_노랑():
    v = compute_verdict(chargers("2", "2"), [fail("a", 7 * 60, "01"), fail("b", 7 * 60, "01"), fail("c", 7 * 60, "02"), fail("d", 7 * 60, "02")], NOW)
    assert (v.verdict, v.reason_code) == ("YELLOW", "OLD_FAILS")
    assert v.message.startswith("7시간 전 실패 제보")


def test_공공_데이터에서_충전_중으로_바뀌면_실패를_지운다():
    cs = [ChargerIn("01", "04", "3", last_charging_at=ago(10)), ChargerIn("02", "04", "2")]
    v = compute_verdict(cs, [fail("a", 60, "01"), fail("b", 50, "01")], NOW)
    assert (v.verdict, v.reason_code) == ("GREEN", "PUBLIC_CHARGING")
    assert "다른 차가 충전함" in v.message
    assert v.chargers[0].cleared_by == "PUBLIC_CHARGING"


def test_운영사_상태가_점검에서_사용가능으로_바뀌면_수리됐을_수_있음():
    cs = [ChargerIn("01", "04", "2", repaired_at=ago(10))]
    v = compute_verdict(cs, [fail("a", 60, "01"), fail("b", 50, "01")], NOW)
    assert (v.verdict, v.reason_code) == ("YELLOW", "REPAIRED_UNCONFIRMED")
    assert v.chargers[0].cleared_by == "OPERATOR_STATUS"


def test_수리_신호_뒤에_또_실패하면_다시_센다():
    cs = [ChargerIn("01", "04", "2", repaired_at=ago(30))]
    v = compute_verdict(cs, [fail("a", 60, "01"), fail("b", 20, "01"), fail("c", 10, "01")], NOW)
    assert (v.chargers[0].verdict, v.chargers[0].recent_fail_count) == ("RED", 2)


def test_하루_넘게_멈춰_있는_충전_중_표시는_믿지_않는다():
    cs = [ChargerIn("01", "04", "3", last_charging_at=ago(30 * 60))]
    v = compute_verdict(cs, [fail("a", 60, "01"), fail("b", 50, "01")], NOW)
    assert v.chargers[0].verdict == "RED"
