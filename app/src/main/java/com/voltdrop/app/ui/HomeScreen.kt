package com.voltdrop.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voltdrop.app.data.*
import kotlinx.coroutines.launch
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

private enum class Screen(val label: String) {
    HOME("홈"), HABITS("충전 습관"), CHARGERS("충전기")
}

/**
 * 상단 점 3개 → 옆에서 드로어가 나와 홈 / 충전 습관 / 충전기 화면을 오간다.
 * 리포트·순위 카드들은 사라진 게 아니라 각자 자기 화면으로 옮겨졌다 — 한 페이지에
 * 다 쌓아두던 걸 나눈 것뿐이다.
 */
@Composable
fun HomeScreen(state: ChargingState, screenOn: Boolean) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Surface) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "VOLTDROP", color = TextMuted, fontSize = 12.sp, letterSpacing = 3.sp,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(Modifier.height(16.dp))
                Screen.entries.forEach { s ->
                    NavigationDrawerItem(
                        label = { Text(s.label) },
                        selected = screen == s,
                        onClick = { screen = s; scope.launch { drawerState.close() } },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = Color(0xFF1E3A2A),
                            unselectedContainerColor = Color.Transparent,
                            selectedTextColor = TextMain,
                            unselectedTextColor = TextMuted
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    ) {
        Column(Modifier.fillMaxSize().background(Bg)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(screen.label, color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "⋮", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { scope.launch { drawerState.open() } }
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (screen) {
                    Screen.HOME -> HomeContent(state, screenOn)
                    Screen.HABITS -> HabitsContent(state)
                    Screen.CHARGERS -> ChargersContent(state)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun HomeContent(state: ChargingState, screenOn: Boolean) {
    var mode by remember { mutableStateOf(GaugeMode.DROP) }
    val color = tierColor(state.tier)
    val ceiling = maxOf(state.sessionPeakWatts * 1.25f, 30f)
    // 안드로이드는 "이 폰의 최대 충전 출력" 스펙을 알려주지 않는다. 대신 지금까지
    // 실측된 역대 최고 W(등록된 충전기들 + 이번 세션)를 기준선으로 쓴다.
    val registryPeak = remember { ChargerRegistry.all().maxOfOrNull { it.bestPeakWatts } ?: 0f }
    val allTimePeakWatts = maxOf(registryPeak, state.sessionPeakWatts)
    val calibrating = state.connected && !state.calibrated
    val wattsAbs = kotlin.math.abs(state.watts)
    val wattsText = if (calibrating) "측정 중" else String.format("%.1f", wattsAbs)
    // 충전 안 할 땐 세션 최고치가 없어서 계기판 눈금을 30W대로 잡으면 바늘이 안 움직인다 —
    // 방전 전력은 훨씬 작으니 따로 작은 눈금을 쓴다.
    val dashCeiling = if (state.connected) ceiling else 8f
    val dashTierLabel = when {
        calibrating -> "측정 중"
        state.connected -> state.tier.label
        else -> "방전 중"
    }
    // 보정 전 원시값은 단위(µA/mA)가 안 맞을 수 있어 바늘이 미쳐 날뛴다 — 보정 끝날 때까지 0에 둔다.
    val dashWatts = if (calibrating) 0f else wattsAbs

    ModeToggle(mode) { mode = it }
    Spacer(Modifier.height(14.dp))

    StatusBar(state)

    // 처음 나타날 때 툭 튀지 않게 — 위에서 살짝 내려오면서 페이드인. 나타나는 것이라 ease-out.
    AnimatedVisibility(
        visible = state.lastDischarge != null,
        enter = fadeIn(tween(320, easing = EaseOutStrong)) +
            slideInVertically(tween(320, easing = EaseOutStrong)) { -it / 4 },
        exit = fadeOut(tween(180))
    ) {
        state.lastDischarge?.let {
            Column {
                Spacer(Modifier.height(10.dp))
                DischargeSummaryCard(it)
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    Box(Modifier.fillMaxWidth().height(320.dp)) {
        // 물방울 <-> 계기판 전환이 툭 끊기지 않고 부드럽게 크로스페이드 되게 한다. 나타나고
        // 사라지는 전환이라 ease-out.
        Crossfade(targetState = mode, animationSpec = tween(380, easing = EaseOutStrong), label = "gaugeMode") { m ->
            when (m) {
                GaugeMode.DROP -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    WaterDropGauge(
                        socPercent = state.socPercent,
                        watts = state.watts,
                        tier = state.tier,
                        connected = state.connected,
                        animate = screenOn,
                        modifier = Modifier.fillMaxSize()
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            wattsText,
                            color = TextMain, fontSize = 54.sp, fontWeight = FontWeight.Bold
                        )
                        Text("와트", color = TextMuted, fontSize = 13.sp, letterSpacing = 3.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${state.socPercent}%  ·  $dashTierLabel",
                            color = if (state.connected) color else TextMuted,
                            fontSize = 15.sp, fontWeight = FontWeight.Medium
                        )
                    }
                }
                // 계기판은 캔버스 위에 숫자를 얹지 않는다 — 다이얼은 다이얼로만 두고,
                // 디지털 리드아웃은 아래 별도 패널(ClusterReadout)로 완전히 분리한다.
                GaugeMode.DASH -> DashboardGauge(
                    watts = dashWatts,
                    maxWatts = dashCeiling,
                    peakWatts = if (state.connected) state.sessionPeakWatts else 0f,
                    tier = state.tier,
                    throttles = if (state.connected) state.throttles else emptyList(),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    if (mode == GaugeMode.DASH) {
        Spacer(Modifier.height(12.dp))
        ClusterReadout(wattsText, state.socPercent, dashTierLabel, driveModeFor(wattsAbs), color)
    }

    Spacer(Modifier.height(18.dp))

    AnimatedVisibility(
        visible = allTimePeakWatts >= 1f,
        enter = fadeIn(tween(320, easing = EaseOutStrong)) +
            slideInVertically(tween(320, easing = EaseOutStrong)) { it / 4 },
        exit = fadeOut(tween(180))
    ) {
        Column {
            PeakRatioBar(state.watts, allTimePeakWatts)
            Spacer(Modifier.height(10.dp))
        }
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

    if (!state.calibrated && state.connected) {
        Spacer(Modifier.height(10.dp))
        Text(
            "전류 센서 보정 중입니다. 1분쯤 꽂아두면 이 기기에 맞는 단위를 스스로 찾습니다.",
            color = TextMuted, fontSize = 12.sp
        )
    }
}

@Composable
private fun HabitsContent(state: ChargingState) {
    val hasSessions = remember { SessionStore.recent(30).isNotEmpty() }
    if (!hasSessions) {
        EmptyHint("충전 습관 리포트", "완충하거나 충전기를 뽑으면 세션이 하나씩 쌓이고, 그때부터 리포트가 채워집니다.")
        return
    }
    HabitReportCard()
    if (state.throttles.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        ThrottleCard(state.throttles, state.sessionPeakWatts)
    }
}

@Composable
private fun ChargersContent(state: ChargingState) {
    ChargerCard(state)
    Spacer(Modifier.height(10.dp))
    val hasMulti = remember { ChargerRegistry.all().size >= 2 }
    if (hasMulti) {
        ChargerLeaderboard(state.charger?.id)
    } else {
        EmptyHint("충전기 순위", "충전기를 두 개 이상 써보면 출력을 비교해서 줄 세워 보여줍니다.")
    }
}

@Composable
private fun EmptyHint(title: String, body: String) {
    Column(Modifier.fillMaxWidth().glassPanel().padding(16.dp)) {
        Text(title, color = TextMuted, fontSize = 11.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        Text(body, color = TextMuted, fontSize = 13.sp)
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

/**
 * 계기판 아래 붙는 디지털 리드아웃 — 다이얼과 분리된 별도 패널이라 바늘·눈금을 가리지 않는다.
 * 현대·제네시스 고성능 모델 클러스터가 주행 모드에 따라 서체 굵기·기울임·강조선으로
 * 표현을 바꾸는 것과 같은 방식: 글자로 "SPORT"라 적지 않고 타이포그래피 자체가 달라진다.
 */
@Composable
private fun ClusterReadout(watts: String, socPercent: Int, tierLabel: String, mode: DriveMode, color: Color) {
    val accentHeight = when (mode) { DriveMode.ECO -> 2.dp; DriveMode.NORMAL -> 3.dp; DriveMode.SPORT -> 5.dp }
    val numberSize = when (mode) { DriveMode.ECO -> 34.sp; DriveMode.NORMAL -> 38.sp; DriveMode.SPORT -> 46.sp }
    val numberWeight = if (mode == DriveMode.ECO) FontWeight.SemiBold else FontWeight.Black
    val italic = if (mode == DriveMode.SPORT) FontStyle.Italic else FontStyle.Normal

    Column(Modifier.fillMaxWidth().glassPanel(18.dp)) {
        Box(Modifier.fillMaxWidth().height(accentHeight).background(color.copy(alpha = 0.85f)))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(watts, color = TextMain, fontSize = numberSize,
                    fontWeight = numberWeight, fontStyle = italic)
                Text("WATT", color = TextMuted, fontSize = 10.sp, letterSpacing = 3.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("$socPercent%", color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(tierLabel, color = TextMuted, fontSize = 12.sp)
            }
        }
    }
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
            // 선택 표시가 툭 바뀌지 않게 색을 애니메이션한다. 애니메이션되는 색은
            // background() 대신 drawBehind 로 칠해서 그리기 단계에서만 갱신되게 한다.
            val pill by animateColorAsState(
                targetValue = if (active) Color(0xFF1E3A2A) else Color.Transparent,
                animationSpec = tween(220, easing = EaseOutStrong),
                label = "togglePill"
            )
            val labelColor by animateColorAsState(
                targetValue = if (active) TextMain else TextMuted,
                animationSpec = tween(220, easing = EaseOutStrong),
                label = "toggleLabel"
            )
            Text(
                label,
                color = labelColor,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .drawBehind { drawRect(pill) }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c?.nickname ?: "처음 보는 충전기입니다",
                color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (c != null) {
                Spacer(Modifier.width(8.dp))
                PlugTag(c.plug)
            }
        }
        Spacer(Modifier.height(4.dp))
        if (c != null) {
            val drop = c.degradation(state.sessionPeakWatts)
            Text(
                if (drop > 0.15f)
                    "평소보다 ${(drop * 100).roundToInt()}% 낮게 들어옵니다. 케이블이나 포트를 확인해 보세요."
                else
                    "최고 ${c.bestPeakWatts.roundToInt()}W 기록",
                color = if (drop > 0.15f) Color(0xFFF5A524) else TextMuted, fontSize = 13.sp
            )
        } else {
            Text("몇 번 더 쓰면 이름을 붙여서 기억해 둡니다.", color = TextMuted, fontSize = 13.sp)
        }
    }
}

/** 유선/무선 구분 태그. */
@Composable
private fun PlugTag(plug: PlugType) {
    Text(
        if (plug.isWireless) "무선" else "유선",
        color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF17281D))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
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
        Spacer(Modifier.height(8.dp))
        // 숫자보다 막대 하나가 더 빨리 읽힌다 — 낮을수록 좋다는 것만 색으로 바로 보이게.
        Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(50))
            .background(Color(0xFF17281D))) {
            Box(
                Modifier.fillMaxWidth(stress / 100f).fillMaxHeight()
                    .clip(RoundedCornerShape(50)).background(stressColor(stress))
            )
        }
        Spacer(Modifier.height(16.dp))
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
        Spacer(Modifier.height(16.dp))
        Text("최근 충전 속도", color = TextMuted, fontSize = 11.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(10.dp))
        SessionBarChart(sessions.takeLast(10))
    }
}

/** 최근 세션의 피크 W를 막대로 줄 세운다 — 숫자 안 읽어도 어느 충전이 빨랐는지 한눈에 보인다. */
@Composable
private fun SessionBarChart(sessions: List<ChargingSession>) {
    if (sessions.isEmpty()) return
    val maxPeak = maxOf(sessions.maxOf { it.peakWatts }, 1f)
    Row(
        Modifier.fillMaxWidth().height(56.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        sessions.forEach { s ->
            val frac = (s.peakWatts / maxPeak).coerceIn(0.06f, 1f)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(frac)
                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    .background(greenForFraction(frac))
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
                    Spacer(Modifier.width(6.dp))
                    PlugTag(c.plug)
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
