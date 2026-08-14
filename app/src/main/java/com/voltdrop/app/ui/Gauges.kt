package com.voltdrop.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voltdrop.app.data.SpeedTier
import com.voltdrop.app.data.ThrottleEvent
import kotlin.math.*

/**
 * 속도 등급이 색을 정한다. 숫자를 안 읽어도 색만 보고 상태를 알 수 있게.
 * 항상 초록 — 전력이 셀수록 짙어진다. 발열 제한(주황)만 진짜 경고라서 예외로 둔다.
 * CV 구간(마무리)은 고장이 아니라 정상 동작이라 굳이 다른 색을 쓰지 않는다.
 */
fun tierColor(tier: SpeedTier): Color = when (tier) {
    SpeedTier.TRICKLE -> Color(0xFF6FD79E)
    SpeedTier.NORMAL -> Color(0xFF2EB872)
    SpeedTier.FAST -> Color(0xFF139458)
    SpeedTier.SUPER -> Color(0xFF075C36)
    SpeedTier.TAPERING -> Color(0xFF2EB872)
    SpeedTier.THROTTLED -> Color(0xFFF5A524)
}

/** 전력 비율(0~1)에 따라 옅은 초록에서 짙은 초록으로. 게이지 안에서 연속적으로 진해지는 용도. */
internal fun greenForFraction(fraction: Float): Color =
    lerp(Color(0xFF7FE3A8), Color(0xFF074A2A), fraction.coerceIn(0f, 1f))

/** 저속=ECO, 일반/고속=NORMAL, 초고속=SPORT. 차량 주행 모드처럼 게이지 표현 자체가 달라진다. */
enum class DriveMode(val label: String) { ECO("ECO"), NORMAL("NORMAL"), SPORT("SPORT") }

fun driveModeFor(watts: Float): DriveMode = when {
    watts >= 25f -> DriveMode.SPORT
    watts >= 12f -> DriveMode.NORMAL
    else -> DriveMode.ECO
}

/**
 * 원형 물 게이지 — 동그란 테두리 안에 수위가 차오르는, 흔한 충전 위젯 스타일.
 *
 * 물 높이 = 배터리 잔량, 물결 속도 = 지금 들어오는 전력.
 * 두 겹의 사인파가 서로 다른 속도로 흘러서 겹칠 때 밀도가 생긴다.
 *
 * 배터리 배려: 애니메이션은 화면이 켜져 있고 이 화면이 보일 때만 돈다.
 * 프레임마다 새 Path 를 만들지 않고 미리 만든 Path 를 reset 해서 재사용한다.
 */
@Composable
fun WaterDropGauge(
    socPercent: Int,
    watts: Float,
    tier: SpeedTier,
    animate: Boolean,
    modifier: Modifier = Modifier
) {
    val fillColor = when (tier) {
        SpeedTier.THROTTLED -> tierColor(tier)
        else -> greenForFraction(watts / 30f)
    }
    val mode = driveModeFor(watts)
    val fill by animateFloatAsState(
        targetValue = socPercent / 100f,
        animationSpec = tween(900, easing = FastOutSlowInEasing),
        label = "fill"
    )

    // 전력이 높을수록 물결도, 기포도 빠르고 커진다
    val speed = (0.6f + (watts / 30f)).coerceIn(0.5f, 3f)
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween((4200 / speed).toInt(), easing = LinearEasing)
        ),
        label = "phase"
    )
    val rise by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween((2600 / speed).toInt(), easing = LinearEasing)
        ),
        label = "rise"
    )
    // SPORT 모드일 때만 의미가 생기는 숨쉬는 글로우. 나머지 모드에선 그려지지 않는다.
    val glowAlpha by transition.animateFloat(
        initialValue = 0.14f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(950, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )
    val livePhase = if (animate) phase else 0f
    val liveRise = if (animate) rise else 0f

    val wavePath = remember { Path() }
    val circlePath = remember { Path() }
    val bubbleCount = (4 + (watts / 5f).toInt()).coerceIn(4, 16)

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val r = min(w, h) * 0.42f

            // SPORT 모드 전용 — 숨쉬는 글로우 링. 원 바깥, 배경에만 번진다.
            if (mode == DriveMode.SPORT) {
                listOf(1.14f, 1.28f, 1.42f).forEach { mult ->
                    val a = (glowAlpha * (1f - (mult - 1f) * 1.6f)).coerceIn(0f, 1f)
                    drawCircle(fillColor.copy(alpha = a), radius = r * mult, center = Offset(cx, cy))
                }
            }

            buildCircle(circlePath, w, h)

            clipPath(circlePath) {
                drawRect(fillColor.copy(alpha = 0.14f))

                val waterTop = h * (1f - fill)
                val amp = (h * 0.018f) * (0.7f + watts / 40f).coerceIn(0.7f, 2f)

                // 뒤쪽 물결 — 느리고 흐리게
                drawWave(wavePath, w, h, waterTop, amp * 0.7f, livePhase * 0.65f + 1.1f,
                    fillColor.copy(alpha = 0.38f))
                // 앞쪽 물결 — 진하게
                drawWave(wavePath, w, h, waterTop, amp, livePhase, fillColor.copy(alpha = 0.88f))

                // 보글보글 기포 — 전력이 셀수록 더 많고 빠르게 올라온다
                if (waterTop < h - 4f) {
                    for (i in 0 until bubbleCount) {
                        val xSeed = (i * 0.6180339f) % 1f
                        val speedJitter = 0.55f + (i % 3) * 0.28f
                        val t = (liveRise * speedJitter + i * 0.131f) % 1f
                        val by = h - (h - waterTop) * t
                        if (by < waterTop + 2f) continue
                        val wobble = sin(t * 8f + i) * w * 0.025f
                        val bx = (w * xSeed + wobble).coerceIn(w * 0.06f, w * 0.94f)
                        val br = (1.4f + (i % 3) * 0.9f).dp.toPx()
                        val a = (1f - t) * 0.55f
                        drawCircle(Color.White.copy(alpha = a), radius = br, center = Offset(bx, by))
                    }
                }

                // 유리 하이라이트 — 위쪽에 옅게 도는 반사광, 투명한 유리 느낌
                drawOval(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.24f), Color.Transparent),
                        center = Offset(w * 0.34f, h * 0.24f),
                        radius = w * 0.55f
                    ),
                    topLeft = Offset(0f, 0f),
                    size = Size(w, h)
                )
            }

            // 테두리 링 — ECO는 가늘게, SPORT는 두껍고 진하게
            val ringWidth = when (mode) {
                DriveMode.ECO -> 2f
                DriveMode.NORMAL -> 2.8f
                DriveMode.SPORT -> 4.2f
            }.dp.toPx()
            drawCircle(fillColor.copy(alpha = 0.7f), radius = r,
                center = Offset(cx, cy), style = Stroke(width = ringWidth))
        }
    }
}

private fun buildCircle(path: Path, w: Float, h: Float) {
    path.reset()
    val cx = w / 2f
    val cy = h / 2f
    val r = min(w, h) * 0.42f
    path.addOval(androidx.compose.ui.geometry.Rect(cx - r, cy - r, cx + r, cy + r))
}

private fun DrawScope.drawWave(
    path: Path, w: Float, h: Float,
    top: Float, amp: Float, phase: Float, color: Color
) {
    path.reset()
    val len = w / 1.35f
    path.moveTo(0f, top)
    var x = 0f
    while (x <= w) {
        val y = top + amp * sin((x / len) * 2 * PI.toFloat() + phase)
        path.lineTo(x, y)
        x += 4f
    }
    path.lineTo(w, h); path.lineTo(0f, h); path.close()
    drawPath(path, color)
}

/**
 * 계기판 게이지.
 *
 * 다른 앱과 다른 점: 눈금 위에 이번 세션의 **최고 전력 자리에 흰 핀**이 박히고,
 * **스로틀이 걸린 지점마다 주황 눈금**이 남는다.
 * 바늘이 지금 어디 있는지뿐 아니라, 왜 거기까지밖에 못 갔는지가 한눈에 보인다.
 */
@Composable
fun DashboardGauge(
    watts: Float,
    maxWatts: Float,
    peakWatts: Float,
    tier: SpeedTier,
    throttles: List<ThrottleEvent>,
    modifier: Modifier = Modifier
) {
    val color = tierColor(tier)
    val needle by animateFloatAsState(
        targetValue = (watts / maxWatts).coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow),
        label = "needle"
    )
    // 바늘·호 색은 속도에 맞춰 연속적으로 짙어진다. 발열 제한만 경고색을 고정한다.
    val dynamicColor = when (tier) {
        SpeedTier.THROTTLED -> color
        else -> greenForFraction(needle)
    }
    val mode = driveModeFor(watts)
    val density = LocalDensity.current

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height * 0.55f
            val radius = min(size.width, size.height) * 0.37f
            val startAngle = 150f
            val sweep = 240f

            // 바탕 호
            drawArc(
                color = Color(0xFF1B2433),
                startAngle = startAngle, sweepAngle = sweep, useCenter = false,
                topLeft = Offset(cx - radius, cy - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round)
            )

            // 눈금
            for (i in 0..10) {
                val major = i % 5 == 0
                val a = Math.toRadians((startAngle + sweep * i / 10f).toDouble())
                val inner = radius - if (major) 24.dp.toPx() else 17.dp.toPx()
                val outer = radius - 10.dp.toPx()
                drawLine(
                    color = if (major) Color(0xFF54637A) else Color(0xFF2C3949),
                    start = Offset(cx + cos(a).toFloat() * inner, cy + sin(a).toFloat() * inner),
                    end = Offset(cx + cos(a).toFloat() * outer, cy + sin(a).toFloat() * outer),
                    strokeWidth = if (major) 2.5.dp.toPx() else 1.5.dp.toPx()
                )
            }

            // 스로틀 지점 각인
            throttles.forEach { ev ->
                val frac = (ev.wattsAfter / maxWatts).coerceIn(0f, 1f)
                val a = Math.toRadians((startAngle + sweep * frac).toDouble())
                val c = when (ev.cause) {
                    ThrottleEvent.Cause.HEAT -> Color(0xFFF5A524)
                    ThrottleEvent.Cause.SOC_TAPER -> Color(0xFF8B7BE8)
                    ThrottleEvent.Cause.SOURCE_LIMIT -> Color(0xFF7A8699)
                }
                drawLine(
                    color = c,
                    start = Offset(cx + cos(a).toFloat() * (radius - 30.dp.toPx()),
                        cy + sin(a).toFloat() * (radius - 30.dp.toPx())),
                    end = Offset(cx + cos(a).toFloat() * (radius + 4.dp.toPx()),
                        cy + sin(a).toFloat() * (radius + 4.dp.toPx())),
                    strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round
                )
            }

            // 현재 전력 호 — ECO는 가늘고 차분하게, SPORT는 두껍고 강렬하게
            val arcWidth = when (mode) {
                DriveMode.ECO -> 10f
                DriveMode.NORMAL -> 14f
                DriveMode.SPORT -> 19f
            }.dp.toPx()
            if (mode == DriveMode.SPORT) {
                // 강조 글로우 — 실제 호보다 굵고 흐리게 한 번 더
                drawArc(
                    color = dynamicColor.copy(alpha = 0.28f),
                    startAngle = startAngle, sweepAngle = sweep * needle, useCenter = false,
                    topLeft = Offset(cx - radius, cy - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = arcWidth * 1.8f, cap = StrokeCap.Round)
                )
            }
            drawArc(
                brush = Brush.sweepGradient(listOf(dynamicColor.copy(alpha = 0.35f), dynamicColor)),
                startAngle = startAngle, sweepAngle = sweep * needle, useCenter = false,
                topLeft = Offset(cx - radius, cy - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = arcWidth, cap = StrokeCap.Round)
            )

            // 세션 최고 기록 핀
            if (peakWatts > 0f) {
                val pf = (peakWatts / maxWatts).coerceIn(0f, 1f)
                val a = Math.toRadians((startAngle + sweep * pf).toDouble())
                drawCircle(
                    color = Color(0xFFE8EDF5),
                    radius = 3.5.dp.toPx(),
                    center = Offset(cx + cos(a).toFloat() * radius, cy + sin(a).toFloat() * radius)
                )
            }

            // 바늘
            val na = startAngle + sweep * needle
            rotate(na, pivot = Offset(cx, cy)) {
                drawPath(
                    Path().apply {
                        moveTo(cx, cy - 4.dp.toPx())
                        lineTo(cx + radius - 22.dp.toPx(), cy - 1.2.dp.toPx())
                        lineTo(cx + radius - 22.dp.toPx(), cy + 1.2.dp.toPx())
                        lineTo(cx, cy + 4.dp.toPx())
                        close()
                    },
                    color = dynamicColor
                )
            }
            drawCircle(Color(0xFF0B0E14), radius = 9.dp.toPx(), center = Offset(cx, cy))
            drawCircle(dynamicColor, radius = 9.dp.toPx(), center = Offset(cx, cy),
                style = Stroke(width = 2.dp.toPx()))
        }
    }
}
