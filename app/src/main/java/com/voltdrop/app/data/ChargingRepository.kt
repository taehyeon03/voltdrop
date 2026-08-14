package com.voltdrop.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 앱의 심장. 여기서 배터리 소모가 결정된다.
 *
 * 샘플링 주기를 상황에 맞춰 바꾼다:
 *
 *   화면 켜짐 + 앱 보는 중  ->  1초    (게이지가 살아 움직여야 하니까)
 *   화면 꺼짐 + 충전 중     ->  30초   (기록만 남기면 되니까)
 *   충전 안 함              ->  0회    (아예 멈춘다. 타이머도 없앤다.)
 *
 * 화면이 꺼진 상태에서 30초에 한 번 sysfs 를 읽는 비용은 하루 종일 합쳐도
 * 화면 1초 켜는 것보다 적다. 백그라운드 서비스는 충전기가 꽂혀 있을 때만 살아 있고,
 * 뽑는 순간 스스로 종료된다. wakelock 은 쓰지 않는다.
 */
class ChargingRepository(
    context: Context,
    private val scope: CoroutineScope
) {
    private val sampler = BatterySampler(context)

    init {
        DischargeTracker.init(context)
        ChargerRegistry.init(context)
        SessionStore.init(context)
    }

    private val _state = MutableStateFlow(ChargingState())
    val state: StateFlow<ChargingState> = _state.asStateFlow()

    private val buffer = ArrayDeque<Sample>()   // 최근 값만. 파일에 매번 쓰지 않는다.
    private val throttles = mutableListOf<ThrottleEvent>()
    private var sessionStart: Sample? = null
    private var sessionPeak = 0f
    private var peakAtMs = 0L
    private var loop: Job? = null
    private var capacityMah = 4000
    private var dischargeSummary: DischargeSummary? = null
    private var currentChargerId: String? = null
    private var lastRegisteredPeak = 0f

    companion object {
        const val FOREGROUND_MS = 1_000L
        const val BACKGROUND_MS = 30_000L
        private const val BUFFER_LIMIT = 240      // 1초 간격 기준 4분치
        private const val THROTTLE_DROP = 0.22f   // 22% 넘게 떨어지면 사건으로 본다
        private const val HOT_C = 38f
    }

    /** 앱 화면이 앞에 나올 때 */
    fun startForeground() = restart(FOREGROUND_MS)

    /** 화면이 꺼지거나 앱이 뒤로 갈 때 */
    fun startBackground() = restart(BACKGROUND_MS)

    /** 앱이 뒤로 갈 때. 루프만 멈춘다 — 충전기가 꽂혀 있어도 호출된다. */
    fun stop() {
        loop?.cancel()
        loop = null
        finishSession()
        _state.value = ChargingState(calibrated = sampler.isCalibrated)
    }

    /**
     * 진짜로 충전기를 뽑았을 때만 호출된다. 세션을 마무리하고, 지금 잔량과 시각을
     * "다음에 꽂을 때 얼마나 줄었는지" 계산할 기준점으로 남긴다. 새 측정은 하지 않는다 —
     * 이미 읽은 sticky broadcast 값 하나 저장하는 것뿐이라 비용이 없다.
     */
    private fun disconnect(s: Sample) {
        loop?.cancel()
        loop = null
        finishSession()
        DischargeTracker.recordDisconnect(s.socPercent, s.timeMs)
        // 잔량은 방금 읽은 마지막 값을 그대로 남긴다 — 0%로 리셋하면 뽑자마자
        // 게이지가 빈 것처럼 보인다. 새 측정은 아니다, 이미 읽은 값 재사용이다.
        _state.value = ChargingState(
            socPercent = s.socPercent,
            temperatureC = s.temperatureC,
            calibrated = sampler.isCalibrated
        )
    }

    private fun restart(intervalMs: Long) {
        loop?.cancel()
        loop = scope.launch {
            capacityMah = sampler.designCapacityMah()
            while (true) {
                tick()
                delay(intervalMs)
            }
        }
    }

    private fun tick() {
        val s = sampler.read() ?: return

        if (!s.isCharging || s.plug == PlugType.NONE) {
            if (sessionStart != null) {
                disconnect(s)
            } else {
                // 충전 안 하는 동안에도, 화면을 보고 있을 때만 도는 이 루프에서
                // 이미 읽은 sticky 값이니 잔량과 지금 빠지는 전력까지 그대로 보여준다.
                // 방전 중엔 charging-time 보정이 안 걸려 있을 수 있어 부호가 안 맞을 수 있다 —
                // 화면에서는 절대값만 쓴다.
                _state.value = ChargingState(
                    socPercent = s.socPercent,
                    watts = s.watts,
                    amps = s.amps,
                    temperatureC = s.temperatureC,
                    calibrated = sampler.isCalibrated
                )
            }
            return
        }

        if (sessionStart == null) {
            sessionStart = s
            throttles.clear()
            buffer.clear()
            sessionPeak = 0f
            currentChargerId = null
            lastRegisteredPeak = 0f
            dischargeSummary = DischargeTracker.summarize(s.socPercent, s.timeMs)
        }

        buffer.addLast(s)
        while (buffer.size > BUFFER_LIMIT) buffer.removeFirst()

        val w = smoothedWatts()
        if (w > sessionPeak) {
            sessionPeak = w
            peakAtMs = s.timeMs
        }

        detectThrottle(s, w)

        // 충전기는 충전을 시작하자마자 등록한다. 예전처럼 뽑을 때만 기록하면 충전 내내
        // "처음 보는 충전기"로 뜨고, 세션 중 앱이 죽으면 아예 남지 않는다.
        // 첫 등록만 세션 수를 올리고, 이후에는 피크가 의미 있게 갱신될 때 값만 고친다.
        if (sessionPeak >= 0.5f && (currentChargerId == null || sessionPeak > lastRegisteredPeak * 1.15f)) {
            currentChargerId = ChargerRegistry.upsert(
                plug = s.plug, peak = sessionPeak, voltMv = s.voltageMv, ramp = rampSeconds(),
                countAsNewSession = currentChargerId == null,
                existingId = currentChargerId
            )
            lastRegisteredPeak = sessionPeak
        }

        _state.value = ChargingState(
            connected = true,
            socPercent = s.socPercent,
            watts = w,
            amps = s.amps,
            volts = s.volts,
            temperatureC = s.temperatureC,
            plug = s.plug,
            tier = classify(w, s),
            sessionPeakWatts = sessionPeak,
            minutesRemaining = estimateMinutes(s, w),
            charger = ChargerRegistry.byId(currentChargerId),
            throttles = throttles.toList(),
            recentWatts = buffer.map { it.watts },
            calibrated = sampler.isCalibrated,
            lastDischarge = dischargeSummary
        )
    }

    /** 커널 전류값은 튀는 편이라 최근 5개 중앙값을 쓴다. 평균보다 스파이크에 강하다. */
    private fun smoothedWatts(): Float {
        val last = buffer.takeLast(5).map { it.watts }.sorted()
        if (last.isEmpty()) return 0f
        return last[last.size / 2]
    }

    private fun rampSeconds(): Int {
        val start = sessionStart ?: return 0
        return ((peakAtMs - start.timeMs) / 1000).toInt().coerceAtLeast(0)
    }

    /**
     * 전력이 떨어졌을 때, 왜 떨어졌는지 구분한다.
     * 이 구분이 이 앱의 핵심이다. 대부분의 앱은 "느려짐" 하나로 뭉뚱그린다.
     *
     *   80% 이상에서 감속  -> 정상이다. 리튬 배터리는 원래 CV 구간에서 전류를 줄인다.
     *   온도가 오르며 감속 -> 발열 제한. 케이스를 벗기거나 선풍기를 대면 회복된다.
     *   온도도 잔량도 그대로인데 감속 -> 충전기나 케이블 쪽 한계다.
     */
    private fun detectThrottle(s: Sample, nowWatts: Float) {
        if (sessionPeak < 3f || nowWatts >= sessionPeak * (1f - THROTTLE_DROP)) return
        // 같은 원인으로 90초 안에 또 기록하지 않는다
        throttles.lastOrNull()?.let { if (s.timeMs - it.timeMs < 90_000) return }

        val tempRising = buffer.size > 10 &&
                s.temperatureC - buffer[buffer.size - 10].temperatureC > 0.4f

        val cause = when {
            s.socPercent >= 80 -> ThrottleEvent.Cause.SOC_TAPER
            s.temperatureC >= HOT_C || tempRising -> ThrottleEvent.Cause.HEAT
            else -> ThrottleEvent.Cause.SOURCE_LIMIT
        }

        throttles += ThrottleEvent(
            timeMs = s.timeMs,
            socPercent = s.socPercent,
            temperatureC = s.temperatureC,
            wattsBefore = sessionPeak,
            wattsAfter = nowWatts,
            cause = cause
        )
    }

    private fun classify(w: Float, s: Sample): SpeedTier {
        val throttledNow = throttles.lastOrNull()
            ?.takeIf { s.timeMs - it.timeMs < 120_000 && it.cause == ThrottleEvent.Cause.HEAT }
        return when {
            throttledNow != null -> SpeedTier.THROTTLED
            s.socPercent >= 80 && w < sessionPeak * 0.7f -> SpeedTier.TAPERING
            w >= 25f -> SpeedTier.SUPER
            w >= 12f -> SpeedTier.FAST
            w >= 5f -> SpeedTier.NORMAL
            else -> SpeedTier.TRICKLE
        }
    }

    /**
     * 완충까지 남은 시간.
     * 남은 %를 그냥 나누면 80% 이후가 항상 틀린다. 뒷구간은 전류가 절반 이하로 떨어지니까
     * 구간을 나눠서 각각 다른 속도로 계산한다.
     */
    private fun estimateMinutes(s: Sample, w: Float): Int? {
        if (w <= 0.1f || s.socPercent >= 100) return null
        val mahPerMin = (s.amps * 1000f) / 60f
        if (mahPerMin <= 0.1f) return null

        val toEighty = ((80 - s.socPercent).coerceAtLeast(0) / 100f) * capacityMah
        val afterEighty = ((100 - maxOf(s.socPercent, 80)) / 100f) * capacityMah

        val fast = toEighty / mahPerMin
        val slow = afterEighty / (mahPerMin * 0.42f)   // CV 구간 실측 평균비
        return (fast + slow).roundToInt().coerceIn(1, 24 * 60)
    }

    private fun finishSession() {
        val start = sessionStart ?: return
        val last = buffer.lastOrNull() ?: return
        val avgW = if (buffer.isEmpty()) 0f else buffer.map { it.watts }.average().toFloat()
        val minutes = ((last.timeMs - start.timeMs) / 60000f)

        // 세션이 끝났으니 최종 피크로 값만 갱신한다 — 세션 수는 시작할 때 이미 올렸다.
        val chargerId = ChargerRegistry.upsert(
            plug = start.plug, peak = sessionPeak, voltMv = last.voltageMv, ramp = rampSeconds(),
            countAsNewSession = false, existingId = currentChargerId
        )

        SessionStore.append(
            ChargingSession(
                startMs = start.timeMs,
                endMs = last.timeMs,
                startSoc = start.socPercent,
                endSoc = last.socPercent,
                plug = start.plug,
                chargerId = chargerId,
                peakWatts = sessionPeak,
                avgWatts = avgW,
                peakTempC = buffer.maxOfOrNull { it.temperatureC } ?: 0f,
                minutesAbove40C = buffer.count { it.temperatureC >= 40f } * (minutes / buffer.size.coerceAtLeast(1)).toInt(),
                minutesAtFull = buffer.count { it.socPercent >= 100 } * (minutes / buffer.size.coerceAtLeast(1)).toInt(),
                throttles = throttles.toList(),
                energyInWh = avgW * (minutes / 60f)
            )
        )
        sessionStart = null
        currentChargerId = null
        lastRegisteredPeak = 0f
    }
}

/**
 * 충전기 지문 저장소.
 *
 * 메모리에만 두면 앱을 껐다 켤 때마다 "처음 보는 충전기"가 된다 — 이 앱의 핵심 기능이
 * 매번 리셋되는 셈이라 SharedPreferences 에 JSON 으로 남긴다. 항목이 수십 개 수준이라
 * 이 정도면 충분하다(Room 은 과하다).
 */
object ChargerRegistry {
    private const val PREFS = "voltdrop_chargers"
    private const val KEY = "list"
    private var prefs: android.content.SharedPreferences? = null
    private val known = mutableListOf<ChargerFingerprint>()

    /** 목록이 바뀔 때마다 올라가는 값. UI 가 이걸 보고 다시 읽는다. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        val raw = prefs?.getString(KEY, null) ?: return
        known.clear()
        runCatching {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                known += ChargerFingerprint(
                    id = o.getString("id"),
                    nickname = o.getString("nickname"),
                    plug = PlugType.valueOf(o.getString("plug")),
                    peakWatts = o.getDouble("peak").toFloat(),
                    peakVoltageMv = o.getInt("volt"),
                    rampSeconds = o.getInt("ramp"),
                    sessionCount = o.getInt("count"),
                    bestPeakWatts = o.getDouble("best").toFloat()
                )
            }
        }
        _revision.value++
    }

    private fun save() {
        val arr = org.json.JSONArray()
        known.forEach { c ->
            arr.put(
                org.json.JSONObject().apply {
                    put("id", c.id); put("nickname", c.nickname); put("plug", c.plug.name)
                    put("peak", c.peakWatts.toDouble()); put("volt", c.peakVoltageMv)
                    put("ramp", c.rampSeconds); put("count", c.sessionCount)
                    put("best", c.bestPeakWatts.toDouble())
                }
            )
        }
        prefs?.edit()?.putString(KEY, arr.toString())?.apply()
        _revision.value++
    }

    fun match(plug: PlugType, peak: Float, voltMv: Int, ramp: Int): ChargerFingerprint? =
        known.firstOrNull {
            it.plug == plug &&
                    abs(it.peakWatts - peak) < maxOf(2f, peak * 0.18f) &&
                    abs(it.peakVoltageMv - voltMv) < 250
        }

    fun byId(id: String?): ChargerFingerprint? = known.firstOrNull { it.id == id }

    /**
     * 충전기를 알아보거나 새로 등록한다. 예전에는 뽑을 때만 기록해서, 충전 중에는 계속
     * "처음 보는 충전기"로 보이고 세션 도중 앱이 죽으면 아예 남지 않았다. 이제는 충전을
     * 시작하면 바로 등록하고(countAsNewSession=true), 이후 피크가 갱신될 때마다 값만 고친다.
     *
     * @return 이 세션이 쓰는 충전기 id
     */
    fun upsert(
        plug: PlugType, peak: Float, voltMv: Int, ramp: Int,
        countAsNewSession: Boolean, existingId: String? = null
    ): String? {
        if (peak < 0.5f) return existingId
        val existing = byId(existingId) ?: match(plug, peak, voltMv, ramp)
        if (existing != null) {
            known[known.indexOf(existing)] = existing.copy(
                sessionCount = existing.sessionCount + if (countAsNewSession) 1 else 0,
                peakWatts = if (countAsNewSession) peak else (existing.peakWatts * 3 + peak) / 4,
                peakVoltageMv = voltMv,
                rampSeconds = ramp,
                bestPeakWatts = maxOf(existing.bestPeakWatts, peak)
            )
            save()
            return existing.id
        }
        val id = "chg_${System.currentTimeMillis().toString().takeLast(8)}"
        known += ChargerFingerprint(
            id = id,
            nickname = defaultName(plug, peak),
            plug = plug, peakWatts = peak, peakVoltageMv = voltMv,
            rampSeconds = ramp, sessionCount = 1, bestPeakWatts = peak
        )
        save()
        return id
    }

    fun rename(id: String, name: String) {
        known.indexOfFirst { it.id == id }.takeIf { it >= 0 }
            ?.let { known[it] = known[it].copy(nickname = name); save() }
    }

    fun all(): List<ChargerFingerprint> = known.toList()

    fun clear() {
        known.clear()
        prefs?.edit()?.remove(KEY)?.apply()
        _revision.value++
    }

    private fun defaultName(plug: PlugType, peak: Float) = when {
        plug.isWireless -> "무선 패드 ${peak.roundToInt()}W"
        peak >= 40 -> "고출력 어댑터 ${peak.roundToInt()}W"
        peak >= 20 -> "PD 어댑터 ${peak.roundToInt()}W"
        peak >= 8 -> "일반 어댑터 ${peak.roundToInt()}W"
        else -> "저속 포트 ${peak.roundToInt()}W"
    }
}

/** 세션 기록. 충전기 목록과 같은 이유로 디스크에 남긴다. */
object SessionStore {
    private const val PREFS = "voltdrop_sessions"
    private const val KEY = "list"
    private const val LIMIT = 200
    private var prefs: android.content.SharedPreferences? = null
    private val sessions = mutableListOf<ChargingSession>()

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        val raw = prefs?.getString(KEY, null) ?: return
        sessions.clear()
        runCatching {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                sessions += ChargingSession(
                    startMs = o.getLong("start"),
                    endMs = if (o.isNull("end")) null else o.getLong("end"),
                    startSoc = o.getInt("ssoc"),
                    endSoc = o.getInt("esoc"),
                    plug = PlugType.valueOf(o.getString("plug")),
                    chargerId = if (o.isNull("chg")) null else o.getString("chg"),
                    peakWatts = o.getDouble("peak").toFloat(),
                    avgWatts = o.getDouble("avg").toFloat(),
                    peakTempC = o.getDouble("temp").toFloat(),
                    minutesAbove40C = o.getInt("hot"),
                    minutesAtFull = o.getInt("full"),
                    // 스로틀 상세는 세션 요약에 필요 없어 저장하지 않는다 — 발열 임계 통계만
                    // 쓰는데, 그건 아래 heatTempC 하나로 충분하다.
                    throttles = emptyList(),
                    energyInWh = o.getDouble("wh").toFloat()
                )
            }
        }
        _revision.value++
    }

    private fun save() {
        val arr = org.json.JSONArray()
        sessions.forEach { s ->
            arr.put(
                org.json.JSONObject().apply {
                    put("start", s.startMs); put("end", s.endMs ?: org.json.JSONObject.NULL)
                    put("ssoc", s.startSoc); put("esoc", s.endSoc); put("plug", s.plug.name)
                    put("chg", s.chargerId ?: org.json.JSONObject.NULL)
                    put("peak", s.peakWatts.toDouble()); put("avg", s.avgWatts.toDouble())
                    put("temp", s.peakTempC.toDouble()); put("hot", s.minutesAbove40C)
                    put("full", s.minutesAtFull); put("wh", s.energyInWh.toDouble())
                }
            )
        }
        prefs?.edit()?.putString(KEY, arr.toString())?.apply()
        _revision.value++
    }

    fun append(s: ChargingSession) {
        sessions += s
        while (sessions.size > LIMIT) sessions.removeAt(0)
        save()
    }

    fun recent(n: Int = 30): List<ChargingSession> = sessions.takeLast(n)

    fun clear() {
        sessions.clear()
        prefs?.edit()?.remove(KEY)?.apply()
        _revision.value++
    }
}

/**
 * 충전기를 뽑은 시점의 잔량·시각만 기억해뒀다가, 다음에 다시 꽂았을 때 그 사이
 * 얼마나 소모됐는지 계산한다. 이 두 값(뽑을 때 한 번, 꽂을 때 한 번)만 있으면 되므로
 * 그 사이 배터리를 따로 폴링하지 않는다 — 충전 안 할 때 이 앱은 완전히 잠들어 있다.
 */
object DischargeTracker {
    private const val PREFS = "voltdrop_discharge"
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun recordDisconnect(soc: Int, timeMs: Long) {
        prefs?.edit()?.putInt("soc", soc)?.putLong("time", timeMs)?.apply()
    }

    fun summarize(nowSoc: Int, nowMs: Long): DischargeSummary? {
        val p = prefs ?: return null
        val soc = p.getInt("soc", -1)
        val time = p.getLong("time", 0L)
        if (soc < 0 || time <= 0L) return null
        val drained = soc - nowSoc
        val minutes = ((nowMs - time) / 60000).toInt()
        if (drained <= 0 || minutes < 1) return null
        return DischargeSummary(fromSoc = soc, toSoc = nowSoc, minutes = minutes, drainedPercent = drained)
    }
}
