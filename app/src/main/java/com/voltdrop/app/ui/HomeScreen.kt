package com.voltdrop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voltdrop.app.data.*
import kotlin.math.roundToInt

private val Bg = Color(0xFF060C09)
private val Surface = Color(0xFF0F1D16)
private val TextMain = Color(0xFFE8F5EC)
private val TextMuted = Color(0xFF7E9688)

/** 카드 배경에 옅은 초록 테두리를 둘러 유리 패널 느낌을 낸다. */
private fun Modifier.glassPanel(radius: Dp = 14.dp): Modifier =
    this.clip(RoundedCornerShape(radius))
        .background(Surface.copy(alpha = 0.92f))
        .border(1.dp, Color(0xFF244434), RoundedCornerShape(radius))

enum class GaugeMode { DROP, DASH }

@Composable
fun HomeScreen(state: ChargingState, screenOn: Boolean) {
    var mode by remember { mutableStateOf(GaugeMode.DROP) }
    val color = tierColor(state.tier)
    val ceiling = maxOf(state.sessionPeakWatts * 1.25f, 30f)
    // 안드로이드는 "이 폰의 최대 충전 출력" 스펙을 알려주지 않는다. 대신 지금까지
    // 실측된 역대 최고 W(등록된 충전기들 + 이번 세션)를 기준선으로 쓴다.
    val registryPeak = remember { ChargerRegistry.all().maxOfOrNull { it.bestPeakWatts } ?: 0f }
    val allTimePeakWatts = maxOf(registryPeak, state.sessionPeakWatts)
    val wattsText = if (state.connected && !state.calibrated) "측정 중" else String.format("%.1f", state.watts)

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        StatusBar(state)

        state.lastDischarge?.let {
            Spacer(Modifier.height(10.dp))
            DischargeSummaryCard(it)
        }

        Spacer(Modifier.height(8.dp))

        Box(Modifier.fillMaxWidth().height(320.dp)) {
            when (mode) {
                GaugeMode.DROP -> {
                    WaterDropGauge(
                        socPercent = state.socPercent,
                        watts = state.watts,
                        tier = state.tier,
                        animate = screenOn && state.connected,
                        modifier = Modifier.fillMaxSize()
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Text(
                            wattsText,
                            color = TextMain, fontSize = 54.sp, fontWeight = FontWeight.Bold
                        )
                        Text("와트", color = TextMuted, fontSize = 13.sp, letterSpacing = 3.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("${state.socPercent}%  ·  ${state.tier.label}",
                            color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        ModeBadge(driveModeFor(state.watts), color)
                    }
                }
                // 계기판은 바늘 축(허브)이 화면 중앙보다 아래에 있어서, 숫자는 그 밑
                // 빈 공간(바늘이 열려 있는 아래쪽 부채꼴)에 따로 배치해 겹치지 않게 한다.
                GaugeMode.DASH -> {
                    DashboardGauge(
                        watts = state.watts,
                        maxWatts = ceiling,
                        peakWatts = state.sessionPeakWatts,
                        tier = state.tier,
                        throttles = state.throttles,
                        modifier = Modifier.fillMaxSize()
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)
                    ) {
                        Text(
                            wattsText,
                            color = TextMain, fontSize = 40.sp, fontWeight = FontWeight.Bold
                        )
                        Text("와트", color = TextMuted, fontSize = 12.sp, letterSpacing = 3.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("${state.socPercent}%  ·  ${state.tier.label}",
                            color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        ModeBadge(driveModeFor(state.watts), color)
                    }
                }
            }
        }

        ModeToggle(mode) { mode = it }
        Spacer(Modifier.height(18.dp))

        if (allTimePeakWatts >= 1f) {
            PeakRatioBar(state.watts, allTimePeakWatts)
            Spacer(Modifier.height(10.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("완충까지",
                state.minutesRemaining?.let { "${it / 60}시간 ${it % 60}분" } ?: "—",
                Modifier.weight(1f))
            Metric("배터리 온도", String.format("%.1f°C", state.temperatureC),
                Modifier.weight(1f),
                valueColor = if (state.temperatureC >= 40f) Color(0xFFF5A524) else TextMain)
            Metric("전류", String.format("%.2fA", state.amps), Modifier.weight(1f))
        }

        Spacer(Modifier.height(10.dp))
        ChargerCard(state)

        Spacer(Modifier.height(10.dp))
        HabitReportCard()

        Spacer(Modifier.height(10.dp))
        ChargerLeaderboard(state.charger?.id)

        if (state.throttles.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            ThrottleCard(state.throttles, state.sessionPeakWatts)
        }

        if (!state.calibrated && state.connected) {
            Spacer(Modifier.height(10.dp))
            Text(
                "전류 센서 보정 중입니다. 1분쯤 꽂아두면 이 기기에 맞는 단위를 스스로 찾습니다.",
                color = TextMuted, fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun StatusBar(state: ChargingState) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50))
                .background(if (state.connected) tierColor(state.tier) else TextMuted))
            Spacer(Modifier.width(8.dp))
            Text(if (state.connected) "충전 중" else "충전기 없음",
                color = TextMain, fontSize = 13.sp, letterSpacing = 1.sp)
        }
        Text(
            if (state.plug.isWireless) "무선 · ${state.plug.label}" else state.plug.label,
            color = TextMuted, fontSize = 13.sp
        )
    }
}

/** 저속/일반·고속/초고속을 ECO·NORMAL·SPORT 배지로 보여준다. */
@Composable
private fun ModeBadge(mode: DriveMode, color: Color) {
    Text(
        mode.label,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

/**
 * "이 폰이 받을 수 있는 최대 출력"을 안드로이드는 알려주지 않는다. 그래서 실측값을 쓴다 —
 * 지금까지 알아본 충전기들의 역대 최고 W. 새 값이 나올 때마다 자연히 갱신된다.
 */
@Composable
private fun PeakRatioBar(current: Float, peak: Float) {
    val ratio = (current / peak).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().glassPanel().padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("역대 최고 대비", color = TextMuted, fontSize = 11.sp, letterSpacing = 1.sp)
            Text("${(ratio * 100).roundToInt()}%  ·  실측 최고 ${peak.roundToInt()}W",
                color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(50))
            .background(Color(0xFF17281D))) {
            Box(
                Modifier.fillMaxWidth(ratio).fillMaxHeight()
                    .clip(RoundedCornerShape(50)).background(greenForFraction(ratio))
            )
        }
    }
}

@Composable
private fun DischargeSummaryCard(d: DischargeSummary) {
    Column(Modifier.fillMaxWidth().glassPanel().padding(14.dp)) {
        Text("지난 충전 이후", color = TextMuted, fontSize = 11.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "${d.minutes / 60}시간 ${d.minutes % 60}분 동안 ${d.drainedPercent}% 소모  (${d.fromSoc}% → ${d.toSoc}%)",
            color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ModeToggle(mode: GaugeMode, onChange: (GaugeMode) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Surface).padding(3.dp)
    ) {
        listOf(GaugeMode.DROP to "물방울", GaugeMode.DASH to "계기판").forEach { (m, label) ->
            val active = m == mode
            Text(
                label,
                color = if (active) TextMain else TextMuted,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (active) Color(0xFF1E2A3D) else Color.Transparent)
                    .clickable { onChange(m) }
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier,
                   valueColor: Color = TextMain) {
    Column(
        modifier.glassPanel().padding(14.dp)
    ) {
        Text(label, color = TextMuted, fontSize = 11.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(5.dp))
        Text(value, color = valueColor, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChargerCard(state: ChargingState) {
    val c = state.charger
    Column(
        Modifier.fillMaxWidth().glassPanel().padding(16.dp)
    ) {
        Text("이 충전기", color = TextMuted, fontSize = 11.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        Text(c?.nickname ?: "처음 보는 충전기입니다",
            color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        if (c != null) {
            val drop = c.degradation(state.sessionPeakWatts)
            Text(
                if (drop > 0.15f)
                    "평소보다 ${(drop * 100).roundToInt()}% 낮게 들어옵니다. 케이블이나 포트를 확인해 보세요."
                else
                    "${c.sessionCount}번째 사용 · 최고 ${c.bestPeakWatts.roundToInt()}W 기록",
                color = if (drop > 0.15f) Color(0xFFF5A524) else TextMuted, fontSize = 13.sp
            )
        } else {
            Text("몇 번 더 쓰면 이름을 붙여서 기억해 둡니다.", color = TextMuted, fontSize = 13.sp)
        }
    }
}

/**
 * 충전 습관 리포트. ChargingService가 30초~1초 간격으로 조용히 쌓아온 세션 기록을
 * 요약해서 보여준다. 새 측정을 하지 않는다 — 이미 있는 SessionStore 를 읽기만 한다.
 *
 * 스트레스 지수 = 발열 노출 40% + 완충 방치 40% + 밤샘 비율 30% 가중합.
 * 어느 한 습관이 지배적인지에 따라 코멘트가 바뀐다.
 */
@Composable
private fun HabitReportCard() {
    val sessions = remember { SessionStore.recent(30) }
    if (sessions.isEmpty()) return

    val fullSessions = sessions.filter { it.endSoc >= 100 }
    val avgFullMin = if (fullSessions.isEmpty()) 0
        else fullSessions.map { it.minutesAtFull }.average().roundToInt()

    val totalMin = sessions.sumOf { ((it.endMs ?: it.startMs) - it.startMs) / 60000.0 }
    val hotMin = sessions.sumOf { it.minutesAbove40C }
    val hotRatio = if (totalMin > 0) (hotMin / totalMin * 100).roundToInt() else 0

    val overnightCount = sessions.count { isOvernight(it) }
    val overnightRatio = overnightCount * 100 / sessions.size

    // 발열 제한이 걸렸던 순간들의 온도 평균 — 이 폰이 대략 몇 도부터 감속하는지
    val heatTemps = sessions.flatMap { it.throttles }
        .filter { it.cause == ThrottleEvent.Cause.HEAT }
        .map { it.temperatureC }
    val avgHeatTemp = if (heatTemps.isEmpty()) null else heatTemps.average().toFloat()

    val stress = stressScore(hotRatio, avgFullMin, overnightRatio)

    Column(
        Modifier.fillMaxWidth().glassPanel().padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("충전 습관 · 최근 ${sessions.size}회", color = TextMuted, fontSize = 11.sp, letterSpacing = 1.sp)
            Text("스트레스 지수 $stress", color = stressColor(stress), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HabitStat("완충후 방치", "평균 ${avgFullMin}분", Modifier.weight(1f))
            HabitStat("40°C 초과", "${hotRatio}% 시간", Modifier.weight(1f))
            HabitStat("밤샘 충전", "${overnightRatio}% 세션", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Text(stressTip(hotRatio, avgFullMin, overnightRatio), color = TextMuted, fontSize = 12.sp)
        if (avgHeatTemp != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "이 폰은 보통 ${String.format("%.1f", avgHeatTemp)}°C 부근부터 발열 제한이 걸립니다",
                color = TextMuted, fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun HabitStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = TextMuted, fontSize = 10.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun isOvernight(s: ChargingSession): Boolean {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = s.startMs }
    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val durationMin = ((s.endMs ?: s.startMs) - s.startMs) / 60000
    return (hour >= 22 || hour < 6) && durationMin > 120
}

private fun stressScore(hotRatio: Int, avgFullMin: Int, overnightRatio: Int): Int {
    val hotPart = hotRatio * 0.4f
    val fullPart = (avgFullMin / 6f).coerceAtMost(40f)     // 240분 방치 = 40점 만점
    val nightPart = overnightRatio * 0.3f
    return (hotPart + fullPart + nightPart).roundToInt().coerceIn(0, 100)
}

private fun stressColor(score: Int) = when {
    score >= 60 -> Color(0xFFF5A524)
    score >= 30 -> Color(0xFF4CC38A)
    else -> Color(0xFF8FD9B6)
}

private fun stressTip(hotRatio: Int, avgFullMin: Int, overnightRatio: Int): String = when {
    hotRatio >= 20 && hotRatio >= overnightRatio -> "발열 노출이 잦습니다. 케이스를 벗기거나 두꺼운 거치대를 피해보세요."
    avgFullMin >= 60 -> "완충 후에도 평균 ${avgFullMin}분씩 꽂아둡니다. 배터리 수명엔 이게 발열보다 더 나쁩니다."
    overnightRatio >= 40 -> "충전의 ${overnightRatio}%가 밤샘입니다. 완충 방치 시간이 느는 주 원인입니다."
    else -> "최근 충전 습관은 양호합니다."
}

/** 지금까지 알아본 충전기들을 최고 출력 순으로 줄 세운다. */
@Composable
private fun ChargerLeaderboard(activeId: String?) {
    val chargers = remember { ChargerRegistry.all() }.sortedByDescending { it.bestPeakWatts }
    if (chargers.size < 2) return

    Column(
        Modifier.fillMaxWidth().glassPanel().padding(16.dp)
    ) {
        Text("충전기 순위", color = TextMuted, fontSize = 11.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(10.dp))
        chargers.take(5).forEachIndexed { i, c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", color = TextMuted, fontSize = 12.sp, modifier = Modifier.width(18.dp))
                    Text(c.nickname,
                        color = if (c.id == activeId) TextMain else TextMuted,
                        fontSize = 13.sp,
                        fontWeight = if (c.id == activeId) FontWeight.SemiBold else FontWeight.Normal)
                }
                Text("${c.bestPeakWatts.roundToInt()}W · ${c.sessionCount}회", color = TextMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ThrottleCard(events: List<ThrottleEvent>, peak: Float) {
    Column(
        Modifier.fillMaxWidth().glassPanel().padding(16.dp)
    ) {
        Text("속도가 떨어진 지점", color = TextMuted, fontSize = 11.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(10.dp))
        events.takeLast(4).forEach { ev ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${ev.socPercent}% · ${String.format("%.1f", ev.temperatureC)}°C",
                    color = TextMain, fontSize = 13.sp)
                Text(
                    "${ev.cause.label} · ${peak.roundToInt()}W → ${ev.wattsAfter.roundToInt()}W",
                    color = when (ev.cause) {
                        ThrottleEvent.Cause.HEAT -> Color(0xFFF5A524)
                        ThrottleEvent.Cause.SOC_TAPER -> Color(0xFF8B7BE8)
                        else -> TextMuted
                    },
                    fontSize = 13.sp
                )
            }
        }
    }
}
