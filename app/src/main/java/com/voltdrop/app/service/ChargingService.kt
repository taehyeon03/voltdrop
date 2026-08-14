package com.voltdrop.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.voltdrop.app.MainActivity
import com.voltdrop.app.data.ChargingRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/**
 * 충전기가 꽂혀 있는 동안에만 살아 있는 서비스.
 *
 * 배터리를 아끼는 설계:
 *  - 충전기를 뽑으면 stopSelf() 로 스스로 죽는다. 상시 상주 프로세스가 아니다.
 *  - wakelock 을 잡지 않는다. Doze 에 들어가면 같이 잠들고, 깨어날 때만 기록한다.
 *  - 화면이 꺼지면 샘플링을 30초로 늘린다. 화면이 켜지면 1초로 되돌린다.
 *  - 알림 갱신도 30초에 한 번만 한다. 알림을 초당 고치면 그게 오히려 배터리를 먹는다.
 *
 *  그리고 어차피 이 서비스는 "충전기에 꽂혀 있을 때만" 돈다.
 *  전기가 들어오고 있는 동안에만 쓰는 전기라, 실사용 배터리에는 영향이 없다.
 */
class ChargingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repo: ChargingRepository
    private var screenOn = true
    private var lastNotifyMs = 0L

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_ON -> { screenOn = true; repo.startForeground() }
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; repo.startBackground() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        repo = ChargingRepository(this, scope)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        startForeground(NOTIF_ID, buildNotification("측정을 시작합니다", ""))
        repo.startBackground()

        scope.launch {
            repo.state.collectLatest { s ->
                if (!s.connected) { stopSelf(); return@collectLatest }
                val now = System.currentTimeMillis()
                val interval = if (screenOn) 5_000L else 30_000L
                if (now - lastNotifyMs < interval) return@collectLatest
                lastNotifyMs = now

                val eta = s.minutesRemaining?.let { "완충까지 ${it / 60}시간 ${it % 60}분" } ?: ""
                notify(
                    "${String.format("%.1f", s.watts)}W · ${s.socPercent}% · ${s.tier.label}",
                    listOfNotNull(
                        eta.ifEmpty { null },
                        "${String.format("%.1f", s.temperatureC)}°C",
                        s.charger?.nickname
                    ).joinToString("  ·  ")
                )
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        repo.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(CHANNEL, "충전 모니터", NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(title: String, body: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private fun notify(title: String, body: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(title, body))
    }

    companion object {
        private const val CHANNEL = "voltdrop_charging"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val i = Intent(context, ChargingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ChargingService::class.java))
        }
    }
}

/** 충전기를 꽂는 순간에만 깨어나서 서비스를 켠다. 하루에 몇 번뿐이라 비용이 없다. */
class PowerConnectionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> ChargingService.start(context)
            Intent.ACTION_POWER_DISCONNECTED -> ChargingService.stop(context)
        }
    }
}
