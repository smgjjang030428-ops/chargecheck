import hashlib

from fastapi import Header, HTTPException

from app.config import settings


def device_hash(x_device_id: str = Header(..., alias="X-Device-Id")) -> str:
    """원본 기기 ID는 저장하지 않고 솔트를 섞은 SHA-256만 쓴다(명세 §5.3)."""
    if not 8 <= len(x_device_id) <= 64:
        raise HTTPException(400, "X-Device-Id 형식 오류")
    return hashlib.sha256((x_device_id + settings.DEVICE_SALT).encode()).hexdigest()
