# apk

1차 제출용 release APK입니다. 팀명: 바다사람(BDSR)

| 파일 | 내용 |
| --- | --- |
| `BDSR_ChargeCheck_release.apk` | real 빌드, release 서명(APK Signature Scheme v2), 배포 서버 `https://chargecheck-ap2h.onrender.com` 접속 |

설치: `adb install apk/BDSR_ChargeCheck_release.apk` 또는 Pleos 에뮬레이터 창에 끌어다 놓기.
debug 빌드가 깔려 있으면 서명이 달라 설치가 거부되니 먼저 지웁니다: `adb uninstall com.chargecheck.app`
