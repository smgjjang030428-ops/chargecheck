"""환경변수 설정. 비밀값은 .env 또는 환경변수로만 준다(저장소에 올리지 않음)."""
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # 로컬 개발은 SQLite, 배포는 PostgreSQL URL로 바꾼다.
    DATABASE_URL: str = "sqlite:///./chargecheck.db"
    # 공공데이터포털 일반 인증키(디코딩 형태 그대로). 없으면 동기화 작업을 건너뛴다.
    PUBLIC_API_KEY: str = ""
    PUBLIC_API_BASE: str = "https://apis.data.go.kr/B552584/EvCharger"
    # 동기화할 시·도 코드(행정구역코드 앞 2자리). 11 = 서울. 쉼표로 여러 개.
    PUBLIC_ZCODES: str = "11"
    # 기기 ID 해시용 솔트. 운영에서는 반드시 바꾼다.
    DEVICE_SALT: str = "dev-salt-change-me"
    SYNC_ENABLED: bool = True
    STATUS_SYNC_MINUTES: int = 5


settings = Settings()

# 판정 기준(명세 §5.4). 앱의 ChargeCheckConfig와 같은 값.
GREEN_WINDOW_H = 3
LOOKBACK_H = 24
DEDUP_WINDOW_H = 2
MAX_FAILS_PER_DEVICE_PER_DAY = 10

# 공공 API stat 코드 → 상태. 출처: 한국환경공단_전기자동차 충전소 정보_OpenAPI활용가이드 v1.25 §3.2 (2026-10-24 확인)
PUBLIC_STAT_MAP = {
    "0": "UNKNOWN",         # 알수없음
    "1": "UNKNOWN",         # 통신이상
    "2": "AVAILABLE",       # 충전대기
    "3": "CHARGING",        # 충전중
    "4": "OUT_OF_SERVICE",  # 운영중지
    "5": "OUT_OF_SERVICE",  # 점검중
    "6": "CHARGING",        # 예약중(지금은 쓸 수 없으므로 사용 중과 같게 본다)
    "9": "UNKNOWN",         # 상태미확인
}

# 공공 API chgerType 코드 중 완속. 나머지는 급속으로 본다.
SLOW_CHARGER_TYPES = {"02", "08"}

# 승용차가 쓸 수 없는 충전기(버스전용). 판정·대수·지도에서 뺀다. 서울에 922대(2026-10-24).
BUS_ONLY_TYPES = {"11"}

# 공공 API chgerType 코드 → 커넥터 방식. 출처: 같은 활용가이드 §3.3 (2026-10-24 확인)
CONNECTOR_MAP = {
    "01": ["DC차데모"],
    "02": ["AC완속"],
    "03": ["DC차데모", "AC3상"],
    "04": ["DC콤보"],
    "05": ["DC차데모", "DC콤보"],
    "06": ["DC차데모", "AC3상", "DC콤보"],
    "07": ["AC3상"],
    "08": ["DC콤보(완속)"],
    "09": ["NACS"],
    "10": ["DC콤보", "NACS"],
    "11": ["DC콤보2(버스전용)"],
}
