package com.voltdrop.app.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.math.abs

/**
 * 측정 엔진.
 *
 * 배터리 소모를 아끼기 위한 두 가지 원칙:
 *
 * 1) ACTION_BATTERY_CHANGED 는 sticky broadcast 다. registerReceiver(null, filter) 로
 *    "지금 시스템이 이미 들고 있는 마지막 값"을 그냥 읽어온다. 새 프로세스를 깨우지도,
 *    센서를 돌리지도 않는다. 폴링 비용이 사실상 0에 수렴한다.
 *
 * 2) 전류/전압은 BatteryManager 의 kernel sysfs 값을 읽는 것이라 IPC 한 번이 전부다.
 *    별도 wakelock 도, 네트워크도, 위치도 쓰지 않는다.
 */
class BatterySampler(private val context: Context) {

    private val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    /**
     * CURRENT_NOW 단위 보정 계수.
     *
     * 문제: 제조사마다 이 값이 µA 인 곳도, mA 인 곳도 있고, 충전 중에 음수로 주는 기기도 있다.
     * 이걸 그대로 믿으면 어떤 폰에서는 27W 가, 다른 폰에서는 0.027W 가 뜬다.
     *
     * 해결: CHARGE_COUNTER(µAh) 는 단위가 표준이라 믿을 수 있다. 이 값의 변화량으로
     * 실제 평균 전류를 역산해서 CURRENT_NOW 와 비교하고, 배율과 부호를 스스로 찾아낸다.
     * 최초 1회 약 60초면 끝나고, 결과는 SharedPreferences 에 남는다.
     */
    private var scale: Float
    private var sign: Int
    var isCalibrated: Boolean private set

    private val prefs = context.getSharedPreferences("voltdrop_cal", Context.MODE_PRIVATE)

    private var calRefCounter = 0L
    private var calRefTime = 0L
    private var calRawSum = 0.0
    private var calRawCount = 0

    init {
        scale = prefs.getFloat("scale", 1f)
        sign = prefs.getInt("sign", 1)
        isCalibrated = prefs.getBoolean("done", false)
    }

    /** 지금 이 순간의 측정값 하나. 실패하면 null. */
    fun read(): Sample? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scaleMax = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val soc = if (level < 0) 0 else (level * 100 / scaleMax)

        val pluggedRaw = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val plug = when (pluggedRaw) {
            BatteryManager.BATTERY_PLUGGED_AC -> PlugType.AC
            BatteryManager.BATTERY_PLUGGED_USB -> PlugType.USB
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> PlugType.WIRELESS
            8 /* BATTERY_PLUGGED_DOCK, API 33+ */ -> PlugType.DOCK
            else -> PlugType.NONE
        }

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            .let { if (it > 10_000) it / 1000 else it }   // 일부 기기는 µV 로 준다

        val rawCurrent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toLong()
        val counter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)

        val now = System.currentTimeMillis()
        if (!isCalibrated && charging) feedCalibration(rawCurrent, counter, now)

        val currentUa = (rawCurrent * scale * sign).toLong()

        return Sample(
            timeMs = now,
            socPercent = soc,
            chargeCounterUah = counter,
            currentUa = currentUa,
            voltageMv = voltageMv,
            temperatureC = tempC,
            plug = plug,
            isCharging = charging
        )
    }

    /** CHARGE_COUNTER 변화량을 정답지로 삼아 배율과 부호를 스스로 찾는다. */
    private fun feedCalibration(raw: Long, counter: Long, now: Long) {
        if (calRefTime == 0L) {
            calRefTime = now; calRefCounter = counter
            calRawSum = 0.0; calRawCount = 0
            return
        }
        calRawSum += raw.toDouble(); calRawCount++

        val elapsedSec = (now - calRefTime) / 1000.0
        val deltaUah = counter - calRefCounter
        // 60초 이상 모였고, 전하가 의미 있게 늘었을 때만 판단
        if (elapsedSec < 60 || deltaUah <= 0 || calRawCount < 5) return

        val trueUa = deltaUah * 3600.0 / elapsedSec          // µAh -> µA
        val avgRaw = calRawSum / calRawCount
        if (abs(avgRaw) < 1.0) { calRefTime = 0L; return }

        val ratio = trueUa / abs(avgRaw)
        scale = when {
            ratio > 300 -> 1000f   // 커널이 mA 로 준다
            ratio > 3 -> ratio.toFloat()
            else -> 1f             // µA 그대로
        }
        sign = if (avgRaw < 0) -1 else 1   // 충전 중인데 음수면 뒤집는다
        isCalibrated = true

        prefs.edit()
            .putFloat("scale", scale)
            .putInt("sign", sign)
            .putBoolean("done", true)
            .apply()
    }

    /** 배터리 설계 용량(mAh). 완충 예상 시간 계산에 쓴다. */
    fun designCapacityMah(): Int {
        val counter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val soc = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (soc > 5) ((counter / 1000.0) * 100 / soc).toInt() else 4000
    }

    /**
     * 충전기를 꽂거나 뽑는 순간만 알려주는 리시버.
     * 이 두 이벤트는 하루에 몇 번 안 오기 때문에 상시 등록해도 부담이 없다.
     * (BATTERY_CHANGED 를 상시 등록하는 것과는 비용이 완전히 다르다.)
     */
    class PlugReceiver(private val onChange: (Boolean) -> Unit) : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> onChange(true)
                Intent.ACTION_POWER_DISCONNECTED -> onChange(false)
            }
        }

        companion object {
            fun filter() = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
        }
    }
}
