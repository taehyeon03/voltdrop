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
    SpeedTier.TRICKLE -> Color(0xFF2E8F5C)
    SpeedTier.NORMAL -> Color(0xFF1C7A4C)
    SpeedTier.FAST -> Color(0xFF0F5C3A)
    SpeedTier.SUPER -> Color(0xFF06331D)
    SpeedTier.TAPERING -> Color(0xFF1C7A4C)
    SpeedTier.THROTTLED -> Color(0xFFF5A524)
}

/** 전력 비율(0~1)에 따라 짙은 초록에서 더 짙은 초록으로. 옅은 색은 쓰지 않는다. */
internal fun greenForFraction(fraction: Float): Color =
    lerp(Color(0xFF2E8F5C), Color(0xFF04331D), fraction.coerceIn(0f, 1f))

// 값이 화면 안에서 이동/변형될 때 쓰는 강한 ease-in-out. 기본 제공 커브보다 또렷하게 느껴진다.
internal val EaseInOutStrong = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)

// 뭔가 나타나거나 사라질 때 쓰는 강한 ease-out. 빠르게 시작해서 부드럽게 자리잡는다 —
// 반대로 도는 ease-in은 사용자가 보고 있는 그 순간을 느리게 시작해서 굼떠 보인다.
internal val EaseOutStrong = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

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
    connected: Boolean,
    animate: Boolean,
    modifier: Modifier = Modifier
) {
    val fillColor = when {
        // 방전 중에도 같은 초록 계열을 쓴다. 회색으로 죽이면 새어 나가는 흰 물방울이
        // 배경에 묻혀 안 보인다 — 대비가 있어야 빠지는 게 눈에 들어온다.
        !connected -> Color(0xFF1C7A4C)
        tier == SpeedTier.THROTTLED -> tierColor(tier)
        else -> greenForFraction(watts / 30f)
    }
    val mode = driveModeFor(watts)
    val fill by animateFloatAsState(
        targetValue = socPercent / 100f,
        animationSpec = tween(900, easing = EaseInOutStrong),
        label = "fill"
    )

    // 전력이 높을수록 물결도, 기포도 빠르고 커진다
    val speed = (0.6f + (watts / 30f)).coerceIn(0.5f, 3f)

    // 무한 반복 애니메이션 여러 개를 따로 굴리면(rememberInfiniteTransition + animateFloat)
    // 각자 자기 주기 끝에서 값이 툭 0으로 리셋되고, 그 리셋 순간이 서로 안 맞아떨어지면서
    // 화면에 "뚝뚝 끊기는" 게 보인다. 그래서 절대 리셋되지 않는 시계 하나(elapsedMs)를 프레임마다
    // 흘려보내고, 모든 주기를 거기 나머지 연산만 걸어서 구한다.
    //
    // 중요: 이 State 를 컴포저블 본문에서 읽으면(예: val livePhase = f(elapsedMs)) 매 프레임마다
    // 리컴포지션이 통째로 일어나서 오히려 버벅인다. Canvas 의 onDraw 람다 안에서만 읽어야
    // 리컴포지션 없이 다시 그리기(redraw)만 발생한다 — 그래서 elapsedMs 자체만 여기서 들고,
    // 실제 위상 계산은 전부 Canvas 블록 안으로 옮겼다.
    var elapsedMs by remember { mutableStateOf(0L) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        val start = withFrameMillis { it } - elapsedMs
        while (true) {
            withFrameMillis { now -> elapsedMs = now - start }
        }
    }

    val wavePath = remember { Path() }
    val circlePath = remember { Path() }
    val streamPath = remember { Path() }
    val bubbleCount = (6 + (watts / 2.2f).toInt()).coerceIn(6, 34)
    // 모드가 바뀔 때 테두리 두께가 툭 끊기지 않고 스르륵 따라오게 한다.
    val targetRing = if (!connected) 2f else when (mode) {
        DriveMode.ECO -> 2f
        DriveMode.NORMAL -> 2.8f
        DriveMode.SPORT -> 4.2f
    }
    val animatedRing by animateFloatAsState(targetRing, label = "ringWidth")

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            // 시간 위상 계산 — 전부 draw scope 안에서 읽는다 (리컴포지션 없이 redraw 만).
            val now = elapsedMs
            val wavePeriod = 4200f / speed
            val livePhase = (now % wavePeriod.toLong().coerceAtLeast(1)) / wavePeriod * (2f * PI.toFloat())
            val risePeriod = 2600f / speed
            val liveRise = (now % risePeriod.toLong().coerceAtLeast(1)) / risePeriod
            // SPORT 모드 숨쉬는 글로우 — 삼각파라 왕복 지점도 매끈하다.
            val glowHalfPeriod = 950f
            val glowT = (now % (glowHalfPeriod * 2).toLong().coerceAtLeast(1)) / glowHalfPeriod
            val glowAlpha = 0.14f + 0.26f * (1f - abs(glowT - 1f))
            // 물이 새는 동안 수위도 아주 조금씩 출렁이며 줄어든다 — 실측치가 아니라 연출용 눈금.
            val leakDip = if (connected) 0f else {
                val dipPeriod = 1300f
                ((now % dipPeriod.toLong().coerceAtLeast(1)) / dipPeriod) * 0.015f
            }
            val displayFill = (fill - leakDip).coerceAtLeast(0f)

            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val r = min(w, h) * 0.46f

            // SPORT 모드 전용 — 숨쉬는 글로우 링. 원 바깥, 배경에만 번진다.
            if (connected && mode == DriveMode.SPORT) {
                listOf(1.14f, 1.28f, 1.42f).forEach { mult ->
                    val a = (glowAlpha * (1f - (mult - 1f) * 1.6f)).coerceIn(0f, 1f)
                    drawCircle(fillColor.copy(alpha = a), radius = r * mult, center = Offset(cx, cy))
                }
            }

            buildCircle(circlePath, w, h)

            clipPath(circlePath) {
                drawRect(fillColor.copy(alpha = 0.14f))

                val waterTop = h * (1f - displayFill)
                val amp = (h * 0.018f) * (0.7f + watts / 40f).coerceIn(0.7f, 2f)

                // 뒤쪽 물결 — 느리고 흐리게
                drawWave(wavePath, w, h, waterTop, amp * 0.7f, livePhase * 0.65f + 1.1f,
                    fillColor.copy(alpha = 0.38f))
                // 앞쪽 물결 — 진하게
                drawWave(wavePath, w, h, waterTop, amp, livePhase, fillColor.copy(alpha = 0.88f))

                // 보글보글 기포 — 충전 중일 때만, 전력이 셀수록 더 많고 빠르게 올라온다
                if (connected && waterTop < h - 4f) {
                    for (i in 0 until bubbleCount) {
                        val xSeed = (i * 0.6180339f) % 1f
                        val speedJitter = 0.55f + (i % 3) * 0.28f
                        val t = (liveRise * speedJitter + i * 0.131f) % 1f
                        val by = h - (h - waterTop) * t
                        if (by < waterTop + 2f) continue
                        val wobble = sin(t * 8f + i) * w * 0.025f
                        val bx = (w * xSeed + wobble).coerceIn(w * 0.06f, w * 0.94f)
                        val br = (2.6f + (i % 3) * 1.7f).dp.toPx()
                        val a = (1f - t) * 0.6f
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

            // 테두리 링 — ECO는 가늘게, SPORT는 두껍고 진하게 (연결 안 되면 항상 가늘게). 모드 전환 시 스르륵.
            drawCircle(fillColor.copy(alpha = 0.7f), radius = r,
                center = Offset(cx, cy), style = Stroke(width = animatedRing.dp.toPx()))

            // 새는 물 — 충전 안 할 때, 테두리 구멍에서 물줄기가 끊기지 않고 흘러나간다.
            //
            // 방울 몇 개를 띄우면 "샌다"가 아니라 그냥 점이 움직이는 걸로 읽힌다. 실제 쏟아지는
            // 물은 (1) 끊김 없는 하나의 줄기고 (2) 나오는 데가 굵고 끝으로 갈수록 가늘어지며
            // (3) 중력으로 휘어 떨어지고 (4) 끝에서 방울로 부서진다. 그래서 점 대신 테이퍼드
            // 리본(양쪽 가장자리를 가진 채워진 Path)으로 그린다.
            //
            // 세기(방전 W)는 줄기의 굵기·길이·초기 속도에 실린다. 숫자 안 봐도 얼마나 빠지는지 보이게.
            if (!connected) {
                val holeAngleDeg = 35f   // 3시~6시 사이, 오른쪽 아래로 샌다
                val rad = Math.toRadians(holeAngleDeg.toDouble())
                val dirX = cos(rad).toFloat()
                val dirY = sin(rad).toFloat()
                val holeX = cx + dirX * r
                val holeY = cy + dirY * r

                val intensity = (watts / 3f).coerceIn(0f, 1f)   // 방전 3W면 최대 세기
                val streamColor = lerp(fillColor, Color.White, 0.28f)

                // 줄기 궤적: 구멍 방향으로 쏘아진 뒤 중력으로 아래로 휜다.
                // 길이는 게이지 반지름 기준으로 묶어둔다 — 아래 카드 위까지 넘어가면 안 된다.
                val v0 = r * (0.16f + intensity * 0.16f)        // 초기 속도(=수평 도달 거리)
                val gravity = r * (0.22f + intensity * 0.12f)   // 아래로 당기는 정도
                val headW = (2.2f + intensity * 3.4f).dp.toPx() // 구멍 쪽 반폭
                val steps = 22

                // 표면 흔들림이 아래로 흘러가며 "흐르고 있다"를 만든다. 위상은 절대 리셋되지
                // 않는 시계에서 뽑아 쓰므로 주기 경계에서 튀지 않는다.
                val flowPhase = (now % 900L) / 900f * (2f * PI.toFloat())

                fun pointAt(t: Float): Offset {
                    val x = holeX + dirX * v0 * t
                    val y = holeY + dirY * v0 * t + gravity * t * t
                    return Offset(x, y)
                }

                streamPath.reset()
                // 한쪽 가장자리를 따라 내려갔다가 반대쪽 가장자리를 따라 올라와서 닫는다.
                for (i in 0..steps) {
                    val t = i / steps.toFloat()
                    val p = pointAt(t)
                    val half = headW * (1f - t * 0.72f)          // 끝으로 갈수록 가늘어진다
                    val wob = sin(t * 7f - flowPhase) * (1f - t) * 1.1f.dp.toPx()
                    // 진행 방향의 법선 — 굵기를 줄기와 직각으로 준다.
                    val tangentY = dirY * v0 + 2f * gravity * t
                    val len = hypot(dirX * v0, tangentY).coerceAtLeast(1f)
                    val nx = -tangentY / len
                    val ny = (dirX * v0) / len
                    val ox = nx * (half + wob)
                    val oy = ny * (half + wob)
                    if (i == 0) streamPath.moveTo(p.x + ox, p.y + oy)
                    else streamPath.lineTo(p.x + ox, p.y + oy)
                }
                for (i in steps downTo 0) {
                    val t = i / steps.toFloat()
                    val p = pointAt(t)
                    val half = headW * (1f - t * 0.72f)
                    val wob = sin(t * 7f - flowPhase) * (1f - t) * 1.1f.dp.toPx()
                    val tangentY = dirY * v0 + 2f * gravity * t
                    val len = hypot(dirX * v0, tangentY).coerceAtLeast(1f)
                    val nx = -tangentY / len
                    val ny = (dirX * v0) / len
                    streamPath.lineTo(p.x - nx * (half - wob), p.y - ny * (half - wob))
                }
                streamPath.close()
                drawPath(streamPath, streamColor.copy(alpha = 0.85f))

                // 줄기 끝에서 부서져 떨어지는 방울 — 줄기가 끝나는 지점에서 이어 받는다.
                val tail = pointAt(1f)
                val dropCycle = 620f - intensity * 240f
                for (i in 0 until 2) {
                    val dt = ((now % dropCycle.toLong().coerceAtLeast(1)) / dropCycle + i * 0.5f) % 1f
                    val dx = tail.x + dirX * v0 * dt * 0.3f
                    val dy = tail.y + (dirY * v0 * 0.3f + gravity * 0.55f * (1f + dt)) * dt
                    val a = (1f - dt) * 0.9f
                    drawCircle(
                        streamColor.copy(alpha = a),
                        radius = headW * (0.75f - dt * 0.3f),
                        center = Offset(dx, dy)
                    )
                }

                // 구멍 자국 — 줄기가 나오는 자리라 줄기 위에 덮어 그린다.
                drawCircle(Color(0xFF0B0E14), radius = 5.dp.toPx(), center = Offset(holeX, holeY))
                drawCircle(fillColor.copy(alpha = 0.9f), radius = 5.dp.toPx(),
                    center = Offset(holeX, holeY), style = Stroke(width = 1.5.dp.toPx()))
            }
        }
    }
}

private fun buildCircle(path: Path, w: Float, h: Float) {
    path.reset()
    val cx = w / 2f
    val cy = h / 2f
    val r = min(w, h) * 0.46f
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
    // 모드가 바뀔 때 두께·강조 밴드가 툭 끊기지 않고 부드럽게 넘어가게 한다.
    val targetArcWidth = when (mode) {
        DriveMode.ECO -> 10f
        DriveMode.NORMAL -> 14f
        DriveMode.SPORT -> 19f
    }
    val arcWidth by animateFloatAsState(targetArcWidth, label = "arcWidth")
    val tickLabelPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            setColor(android.graphics.Color.argb(220, 126, 150, 136))
        }
    }
    val sportAmount by animateFloatAsState(
        if (mode == DriveMode.SPORT) 1f else 0f,
        animationSpec = tween(500), label = "sportAmount"
    )

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height * 0.56f
            val radius = min(size.width, size.height) * 0.40f
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

            // SPORT 쪽으로 갈수록 번지는 파워 밴드. 실제 계기판 레드존과 같은 자리, 서서히 나타난다.
            if (sportAmount > 0.01f) {
                val bandStart = 0.76f
                drawArc(
                    color = tierColor(SpeedTier.SUPER).copy(alpha = 0.4f * sportAmount),
                    startAngle = startAngle + sweep * bandStart,
                    sweepAngle = sweep * (1f - bandStart),
                    useCenter = false,
                    topLeft = Offset(cx - radius, cy - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // 눈금 — ECO는 큰 눈금만 남겨 계기판 자체를 미니멀하게 비운다
            for (i in 0..10) {
                val major = i % 5 == 0
                if (!major && mode == DriveMode.ECO) continue
                val a = Math.toRadians((startAngle + sweep * i / 10f).toDouble())
                val inner = radius - if (major) 24.dp.toPx() else 17.dp.toPx()
                val outer = radius - 10.dp.toPx()
                drawLine(
                    color = if (major) Color(0xFF54637A) else Color(0xFF2C3949),
                    start = Offset(cx + cos(a).toFloat() * inner, cy + sin(a).toFloat() * inner),
                    end = Offset(cx + cos(a).toFloat() * outer, cy + sin(a).toFloat() * outer),
                    strokeWidth = if (major) 2.5.dp.toPx() else 1.5.dp.toPx()
                )
                // 실제 속도계처럼 큰 눈금 옆에 W 값을 적어둔다
                if (major) {
                    val labelR = radius + 16.dp.toPx()
                    val lx = cx + cos(a).toFloat() * labelR
                    val ly = cy + sin(a).toFloat() * labelR
                    tickLabelPaint.textSize = 11.sp.toPx()
                    drawContext.canvas.nativeCanvas.drawText(
                        (maxWatts * i / 10f).roundToInt().toString(),
                        lx, ly + 4.dp.toPx(),
                        tickLabelPaint
                    )
                }
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

            // 현재 전력 호 — ECO는 가늘고 차분하게, SPORT는 두껍고 강렬하게 (두께는 부드럽게 전환)
            val arcWidthPx = arcWidth.dp.toPx()
            if (sportAmount > 0.01f) {
                // 강조 글로우 — 실제 호보다 굵고 흐리게 한 번 더
                drawArc(
                    color = dynamicColor.copy(alpha = 0.28f * sportAmount),
                    startAngle = startAngle, sweepAngle = sweep * needle, useCenter = false,
                    topLeft = Offset(cx - radius, cy - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = arcWidthPx * 1.8f, cap = StrokeCap.Round)
                )
            }
            drawArc(
                brush = Brush.sweepGradient(listOf(dynamicColor.copy(alpha = 0.35f), dynamicColor)),
                startAngle = startAngle, sweepAngle = sweep * needle, useCenter = false,
                topLeft = Offset(cx - radius, cy - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = arcWidthPx, cap = StrokeCap.Round)
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
