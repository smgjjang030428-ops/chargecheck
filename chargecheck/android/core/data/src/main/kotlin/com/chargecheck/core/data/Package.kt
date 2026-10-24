package com.chargecheck.core.data

// M0 뼈대. 구현 예정: Room(upload_queue, station_cache, session_log), DataStore(settings, snapshot), UploadQueue (§4.12)
// 의존 규칙: 이 모듈은 core 모듈만 의존한다. 다른 feature/adapter 모듈을 import하지 않는다.