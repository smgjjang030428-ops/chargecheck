package com.chargecheck.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/** 운전자가 고르는 설정 세 가지와 처음 동의. (기획 §9, §11) */
internal class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("chargecheck", Context.MODE_PRIVATE)

    /** 처음 실행 때 '무엇을 모으는지' 화면에서 동의했는지. 동의 전에는 아무것도 기록·전송하지 않는다. */
    var consented: Boolean
        get() = prefs.getBoolean("consented", false)
        set(v) = prefs.edit().putBoolean("consented", v).apply()

    /** 내비 목적지까지 충전이 필요할 때 '가는 길 충전소' 카드. */
    var routeCards: Boolean
        get() = prefs.getBoolean("route_cards", true)
        set(v) = prefs.edit().putBoolean("route_cards", v).apply()

    /** 충전소로 갈 때 최근 실패가 있으면 도착 전 경고. */
    var arrivalWarning: Boolean
        get() = prefs.getBoolean("arrival_warning", true)
        set(v) = prefs.edit().putBoolean("arrival_warning", v).apply()

    /** 정상 충전 안내 팝업을 띄운 횟수. 처음 몇 번만 띄우고 그다음은 조용히 기록한다. */
    var successNotices: Int
        get() = prefs.getInt("success_notices", 0)
        set(v) = prefs.edit().putInt("success_notices", v).apply()

    /** 앱 화면이 안 보일 때(내비를 보는 중) 카드를 알림으로 띄울지. */
    var overlayCards: Boolean
        get() = prefs.getBoolean("overlay_cards", true)
        set(v) = prefs.edit().putBoolean("overlay_cards", v).apply()
}

/**
 * 시동이 켜지면(부팅) 바로 시작해서 화면 없이 계속 돈다. 운전자는 기본 내비만 써도 된다.
 * 목적지 감지·충전 기록·도착 전 경고는 모두 [RealGraph]에서, 카드는 [CardNotifier]가 알림으로 띄운다.
 */
class ChargeCheckService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        val ongoing = Notification.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_chargecheck)
            .setContentTitle("차지체크")
            .setContentText("충전 결과를 자동으로 기록하고 있어요")
            .setContentIntent(openAppIntent(this))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_SERVICE, ongoing, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID_SERVICE, ongoing)
        }
        RealGraph.get(this).startNotifier(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** 동의했을 때만 시작한다. */
        fun start(context: Context) {
            if (!AppSettings(context).consented) return
            ContextCompat.startForegroundService(context, Intent(context, ChargeCheckService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ChargeCheckService::class.java))
        }
    }
}

/** 시동(부팅) → 서비스 시작. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            ChargeCheckService.start(context)
        }
    }
}

internal const val CHANNEL_SERVICE = "service"
internal const val CHANNEL_CARDS = "cards"
internal const val ID_SERVICE = 1

internal fun createChannels(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, "백그라운드 기록", NotificationManager.IMPORTANCE_MIN))
    // 내비 화면 위에 잠깐 뜨는 카드(헤드업). 소리는 한 번만 짧게.
    nm.createNotificationChannel(NotificationChannel(CHANNEL_CARDS, "주행 카드", NotificationManager.IMPORTANCE_HIGH))
}

internal fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
    context,
    0,
    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)
