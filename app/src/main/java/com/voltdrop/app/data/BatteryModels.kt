package com.voltdrop.app.data

/** 한 번의 순간 측정값. 메모리에만 쌓이고, 세션이 끝날 때만 저장된다. */
data class Sample(
    val timeMs: Long,
    val socPercent: Int,          // 0~100
    val chargeCounterUah: Long,   // 배터리에 실제로 들어온 누적 전하 (µAh)
    val currentUa: Long,          // 보정된 전류 (µA, 충전 중 +)
    val voltageMv: Int,           // 배터리 전압 (mV)
    val temperatureC: Float,      // 배터리 온도 (°C)
    val plug: PlugType,
    val isCharging: Boolean
) {
    /** 배터리 기준 입력 전력(W). 어댑터 출력이 아니라 셀에 실제로 들어가는 값. */
    val watts: Float
        get() = (currentUa / 1_000_000f) * (voltageMv / 1000f)

    val amps: Float get() = currentUa / 1_000_000f
    val volts: Float get() = voltageMv / 1000f
}

enum class PlugType(val label: String) {
    NONE("미연결"),
    AC("유선 어댑터"),
    USB("USB 포트"),
    WIRELESS("무선"),
    DOCK("독");

    val isWireless get() = this == WIRELESS
}

/** 충전 속도 등급 — 화면 색과 문구를 정하는 기준. */
enum class SpeedTier(val label: String) {
    TRICKLE("완속"),        // < 5W
    NORMAL("일반"),         // 5~12W
    FAST("고속"),           // 12~25W
    SUPER("초고속"),        // 25W 이상
    TAPERING("감속"),       // 80% 이상, 자연 감속 (CV 구간)
    THROTTLED("발열 제한")  // 열 때문에 눌린 상태
}

/** 전력이 눈에 띄게 떨어진 사건 하나. 계기판 위에 눈금으로 각인된다. */
data class ThrottleEvent(
    val timeMs: Long,
    val socPercent: Int,
    val temperatureC: Float,
    val wattsBefore: Float,
    val wattsAfter: Float,
    val cause: Cause
) {
    enum class Cause(val label: String) {
        HEAT("발열"),
        SOC_TAPER("잔량 구간"),
        SOURCE_LIMIT("충전기 한계")
    }

    val dropRatio: Float get() = if (wattsBefore <= 0f) 0f else 1f - (wattsAfter / wattsBefore)
}

/**
 * 충전기 지문. 같은 충전기를 다시 꽂으면 알아본다.
 * 전압 / 피크 전력 / 상승 시간 조합이 충전기+케이블마다 꽤 뚜렷하게 다르다.
 */
data class ChargerFingerprint(
    val id: String,
    val nickname: String,        // "집 65W PD", "차 시거잭" — 사용자가 붙인다
    val plug: PlugType,
    val peakWatts: Float,
    val peakVoltageMv: Int,
    val rampSeconds: Int,        // 꽂고 나서 피크까지 걸린 시간
    val sessionCount: Int,
    val bestPeakWatts: Float     // 역대 최고. 지금이 이보다 한참 낮으면 케이블 의심.
) {
    /** 이번 세션이 이 충전기의 평소 실력보다 얼마나 못 나오는지 (0~1) */
    fun degradation(currentPeak: Float): Float =
        if (bestPeakWatts <= 0f) 0f else (1f - currentPeak / bestPeakWatts).coerceIn(0f, 1f)
}

/** 충전 한 판의 요약. 습관 리포트는 이것들을 모아서 만든다. */
data class ChargingSession(
    val startMs: Long,
    val endMs: Long?,
    val startSoc: Int,
    val endSoc: Int,
    val plug: PlugType,
    val chargerId: String?,
    val peakWatts: Float,
    val avgWatts: Float,
    val peakTempC: Float,
    val minutesAbove40C: Int,
    val minutesAtFull: Int,        // 100% 도달 후에도 꽂아둔 시간
    val throttles: List<ThrottleEvent>,
    val energyInWh: Float
)

/**
 * 지난번 충전기를 뽑은 뒤부터 이번에 다시 꽂을 때까지 배터리가 얼마나 줄었는지.
 * 새 측정을 하지 않는다 — 뽑을 때 sticky broadcast 로 읽은 잔량 하나, 꽂을 때 하나, 그 차이일 뿐이다.
 */
data class DischargeSummary(
    val fromSoc: Int,
    val toSoc: Int,
    val minutes: Int,
    val drainedPercent: Int
)

/** 화면에 그려질 현재 상태 전부. */
data class ChargingState(
    val connected: Boolean = false,
    val socPercent: Int = 0,
    val watts: Float = 0f,
    val amps: Float = 0f,
    val volts: Float = 0f,
    val temperatureC: Float = 0f,
    val plug: PlugType = PlugType.NONE,
    val tier: SpeedTier = SpeedTier.NORMAL,
    val sessionPeakWatts: Float = 0f,
    val minutesRemaining: Int? = null,
    val charger: ChargerFingerprint? = null,
    val throttles: List<ThrottleEvent> = emptyList(),
    val recentWatts: List<Float> = emptyList(),   // 스파크라인용 (최근 120포인트)
    val calibrated: Boolean = false,
    val lastDischarge: DischargeSummary? = null
)
