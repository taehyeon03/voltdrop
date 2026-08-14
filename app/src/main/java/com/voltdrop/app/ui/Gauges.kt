package com.voltdrop.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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

// 게이지 원의 위치와 크기. 원을 정중앙에 두면 아래로 새는 물줄기가 그려질 자리가 없어
// 상자 밖으로 잘려 나가므로, 중심을 살짝 위로 올려 아래쪽 여백을 확보한다.
private const val GaugeCenterY = 0.44f
private const val GaugeRadius = 0.42f

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
    // 실측 W 는 1초마다 갱신되면서 값이 훌쩍 뛴다. 그 값을 색·진폭·기포 수에 바로 물리면
    // 1초마다 그림이 툭툭 바뀐다. 시각 표현에는 완만하게 따라가는 값을 쓴다
    // (숫자 표기는 원본 watts 를 그대로 쓰므로 표시 정확도는 그대로다).
    val smoothWatts by animateFloatAsState(
        targetValue = watts,
        animationSpec = tween(700, easing = EaseInOutStrong),
        label = "smoothWatts"
    )
    val fillColor = when {
        // 방전 중에도 같은 초록 계열을 쓴다. 회색으로 죽이면 새어 나가는 흰 물방울이
        // 배경에 묻혀 안 보인다 — 대비가 있어야 빠지는 게 눈에 들어온다.
        !connected -> Color(0xFF1C7A4C)
        tier == SpeedTier.THROTTLED -> tierColor(tier)
        else -> greenForFraction(smoothWatts / 30f)
    }
    val mode = driveModeFor(smoothWatts)
    val fill by animateFloatAsState(
        targetValue = socPercent / 100f,
        animationSpec = tween(900, easing = EaseInOutStrong),
        label = "fill"
    )

    // 전력이 높을수록 물결도, 기포도 빠르고 커진다
    val speed = (0.6f + (smoothWatts / 30f)).coerceIn(0.5f, 3f)

    // 애니메이션 위상은 "절대 시각 % 주기"로 구하면 안 된다. 전력(watts)이 1초마다 갱신되면서
    // 주기(4200/speed)가 바뀌는 순간 같은 시각이 전혀 다른 위상으로 매핑돼 그림이 툭 튄다.
    // 그래서 프레임 간격(dt)만큼 위상을 누적한다 — 속도가 변해도 위상 자체는 이어진다.
    //
    // 또한 이 State 를 컴포저블 본문에서 읽으면 매 프레임 리컴포지션이 일어나 오히려 버벅인다.
    // Canvas 의 onDraw 람다 안에서만 읽어야 리컴포지션 없이 다시 그리기만 발생한다.
    val intensity = (smoothWatts / 3f).coerceIn(0f, 1f)   // 방전 세기 (구멍/물줄기용)
    val speedState = rememberUpdatedState(speed)
    val intensityState = rememberUpdatedState(intensity)
    var wavePhaseAcc by remember { mutableStateOf(0f) }   // 라디안, 0..2PI 순환
    // 기포는 셋으로 나눠 서로 다른 속도로 올린다. 예전에는 위상 하나를 배속으로 곱해 썼는데,
    // 그 위상이 1에서 0으로 감기는 순간 (1 × 1.11) % 1 = 0.11 만큼 값이 튀어서 매 주기마다
    // 모든 기포가 한 번씩 순간이동했다. 배속별로 위상을 따로 누적하면 감기는 지점이
    // "기포가 위로 빠져나가고 아래에서 새로 올라오는" 자연스러운 자리와 정확히 일치한다.
    val riseAccs = remember { mutableStateListOf(0f, 0.37f, 0.71f) }
    var leakAcc by remember { mutableStateOf(0f) }        // 새는 물방울 위상 0..1
    var clockMs by remember { mutableStateOf(0L) }        // 주기가 고정된 것(글로우)용
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (true) {
            withFrameMillis { frameMs ->
                // 화면이 꺼졌다 켜지는 등으로 프레임이 길게 비면 한 번에 확 건너뛰지 않게 묶는다.
                val dt = (frameMs - last).coerceIn(0L, 64L)
                last = frameMs
                clockMs += dt
                val s = speedState.value
                wavePhaseAcc = (wavePhaseAcc + dt / (4200f / s) * 2f * PI.toFloat()) % (2f * PI.toFloat())
                for (i in riseAccs.indices) {
                    val jitter = 0.55f + i * 0.28f
                    riseAccs[i] = (riseAccs[i] + dt / (2600f / s) * jitter) % 1f
                }
                // 물방울 간격도 세기에 따라 빨라진다. 속도를 바꿀 때 위상이 튀지 않도록
                // 역시 누적식으로 굴린다 (절대 시각 % 주기 로 구하면 주기가 바뀌는 순간 점프한다).
                val dropCycle = 620f - intensityState.value * 240f
                leakAcc = (leakAcc + dt / dropCycle) % 1f
            }
        }
    }

    val wavePath = remember { Path() }
    val circlePath = remember { Path() }
    val streamPath = remember { Path() }
    val coreLightPath = remember { Path() }
    // 소수 그대로 들고 간다 — 정수로 자르면 개수가 바뀌는 순간 기포가 툭 나타난다.
    val bubbleCountF = (6f + smoothWatts / 2.2f).coerceIn(6f, 34f)
    // 모드가 바뀔 때 테두리 두께가 툭 끊기지 않고 스르륵 따라오게 한다.
    val targetRing = if (!connected) 2f else when (mode) {
        DriveMode.ECO -> 2f
        DriveMode.NORMAL -> 2.8f
        DriveMode.SPORT -> 4.2f
    }
    val animatedRing by animateFloatAsState(targetRing, label = "ringWidth")

    Box(modifier, contentAlignment = Alignment.Center) {
        // clipToBounds 가 없으면 게이지 밖으로 나간 물줄기·방울이 아래 카드 위에 그대로
        // 그려진다 (Compose Canvas 는 기본적으로 경계를 자르지 않는다). 물이 화면에 떠다니는
        // 것처럼 보여 고장난 듯한 인상을 준다.
        Canvas(Modifier.fillMaxSize().clipToBounds()) {
            // 시간 위상 — 전부 draw scope 안에서 읽는다 (리컴포지션 없이 redraw 만).
            // 물결·기포는 속도가 변해도 이어지도록 누적된 위상을 그대로 쓴다.
            val now = clockMs
            val livePhase = wavePhaseAcc
            // SPORT 모드 숨쉬는 글로우 — 주기가 고정이라 절대 시각으로 구해도 튀지 않는다.
            val glowHalfPeriod = 950f
            val glowT = (now % (glowHalfPeriod * 2).toLong().coerceAtLeast(1)) / glowHalfPeriod
            val glowAlpha = 0.14f + 0.26f * (1f - abs(glowT - 1f))
            // 물이 새는 동안 수면이 미세하게 오르내린다. 예전에는 톱니파(0 → 0.015 → 뚝 0)라
            // 주기마다 수면이 위로 툭 튀어올랐다 — 방전 화면이 특히 심하게 버벅여 보인 원인.
            // 사인파로 바꾸면 끝과 시작이 이어져 스냅이 없다.
            val leakDip = if (connected) 0f else
                (1f - cos(livePhase * 0.7f)) * 0.5f * 0.012f
            val displayFill = (fill - leakDip).coerceAtLeast(0f)

            val w = size.width
            val h = size.height
            val cx = w / 2f
            // 원을 정중앙이 아니라 살짝 위에 둔다. 정중앙 + 반지름 0.46 이면 원 아래로 남는
            // 공간이 10dp 남짓이라 새는 물줄기가 나오자마자 상자 밖으로 잘려 나갔다.
            val cy = h * GaugeCenterY
            val r = min(w, h) * GaugeRadius

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

                // 수위는 캔버스가 아니라 원의 위아래 끝을 기준으로 잡는다 — 원이 정중앙이
                // 아니라서 캔버스 기준으로 잡으면 50% 가 원의 절반과 어긋난다.
                val waterTop = (cy - r) + 2f * r * (1f - displayFill)
                val amp = (h * 0.018f) * (0.7f + smoothWatts / 40f).coerceIn(0.7f, 2f)

                // 뒤쪽 물결 — 느리고 흐리게
                drawWave(wavePath, w, h, waterTop, amp * 0.7f, livePhase * 0.65f + 1.1f,
                    fillColor.copy(alpha = 0.38f))
                // 앞쪽 물결 — 진하게
                drawWave(wavePath, w, h, waterTop, amp, livePhase, fillColor.copy(alpha = 0.88f))

                // 보글보글 기포 — 충전 중일 때만, 전력이 셀수록 더 많고 빠르게 올라온다.
                // 개수가 정수라 임계를 넘는 순간 기포 하나가 통째로 튀어나오면 그것도 끊김으로
                // 보인다. 마지막 기포는 소수부만큼 투명도로 서서히 들어오게 한다.
                if (connected && waterTop < h - 4f) {
                    val visible = ceil(bubbleCountF).toInt()
                    val edgeFade = bubbleCountF - floor(bubbleCountF)
                    for (i in 0 until visible) {
                        val xSeed = (i * 0.6180339f) % 1f
                        // 배속별로 따로 누적한 위상을 쓴다 — 감기는 지점이 곧 기포가
                        // 새로 올라오기 시작하는 자리라 튀지 않는다.
                        val t = (riseAccs[i % riseAccs.size] + i * 0.131f) % 1f
                        val by = h - (h - waterTop) * t
                        if (by < waterTop + 2f) continue
                        val wobble = sin(t * 8f + i) * w * 0.025f
                        val bx = (w * xSeed + wobble).coerceIn(w * 0.06f, w * 0.94f)
                        val br = (2.6f + (i % 3) * 1.7f).dp.toPx()
                        val countFade = if (i == visible - 1 && edgeFade > 0f) edgeFade else 1f
                        val a = (1f - t) * 0.6f * countFade
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

            // 새는 물 — 충전 안 할 때, 테두리에 뚫린 틈으로 물줄기가 끊기지 않고 흘러나간다.
            //
            // 방울 몇 개를 띄우면 "샌다"가 아니라 그냥 점이 움직이는 걸로 읽힌다. 실제 쏟아지는
            // 물은 (1) 끊김 없는 하나의 줄기고 (2) 나오는 데가 굵고 끝으로 갈수록 가늘어지며
            // (3) 중력으로 휘어 떨어지고 (4) 끝에서 방울로 부서진다. 그래서 점 대신 테이퍼드
            // 리본(양쪽 가장자리를 가진 채워진 Path)으로 그린다.
            //
            // 구멍은 링 위에 얹은 검은 점이 아니라 링 자체가 끊긴 "틈"이다 — 점으로 그리면
            // 물과 따로 노는 스티커처럼 보인다. 발원점은 안쪽 물결과 같은 위상으로 함께
            // 출렁여서 새는 물이 저 물에서 나온 것으로 읽히게 한다.
            //
            // 세기(방전 W)는 줄기 굵기·길이·속도에 더해 구멍 개수로도 드러난다.
            // 링에 낸 틈의 폭은 줄기 굵기에서 역산한다. 틈만 넓고 줄기가 가늘면 "구멍"이 아니라
            // 링이 그냥 끊어진 것처럼 보인다 — 줄기가 틈을 정확히 메워야 물이 그리로 빠져나가는
            // 걸로 읽힌다. 굵기 자체도 dp 고정이 아니라 원 크기에 비례시켜 게이지가 커지면 같이 커진다.
            val holeSpecs: List<Pair<Float, Float>>   // (각도, 링에서 비울 반각)
            if (!connected) {
                // 많이 빠질수록 구멍이 늘어난다 — 1개(잔잔) → 3개(펑펑).
                // 정수로 자르면 임계를 넘는 순간 줄기 하나가 통째로 튀어나와 끊겨 보인다.
                // 소수부를 그대로 들고 가서 마지막 구멍은 굵기·투명도로 서서히 열리게 한다.
                val holeCountF = (1f + intensity * 2.4f).coerceIn(1f, 3f)
                val visibleHoles = ceil(holeCountF).toInt()
                val edgeOpen = holeCountF - floor(holeCountF)
                // 구멍은 등간격으로 두면 안 된다 — 균일하게 벌어져 있으면 물이 새는 게 아니라
                // 디자인 패턴처럼 보인다. 간격도 굵기도 속도도 서로 어긋나게 둔다.
                val angles = listOf(31f, 74f, 126f).take(visibleHoles)
                // 구멍별 고유 성격: (굵기 배율, 속도 배율, 흔들림 배율)
                val traits = listOf(
                    Triple(1.15f, 0.92f, 1.0f),
                    Triple(0.72f, 1.18f, 1.45f),
                    Triple(0.94f, 1.03f, 0.75f)
                )
                val streamColor = lerp(fillColor, Color.White, 0.28f)
                val steps = 22
                // 표면 흔들림이 아래로 흘러가며 "흐르고 있다"를 만든다. 감기는 지점이 sin 안이라
                // 값이 이어진다.
                val flowPhase = livePhase * 2.4f
                val specs = mutableListOf<Pair<Float, Float>>()

                angles.forEachIndexed { hIdx, angleDeg ->
                    val (widthMul, speedMul, wobbleMul) = traits[hIdx]
                    // 마지막 구멍은 세기가 임계를 넘는 만큼만 열린다 (0 → 1 로 서서히).
                    val open = if (hIdx == visibleHoles - 1 && edgeOpen > 0f) edgeOpen else 1f
                    // 구멍마다 위상을 달리 줘서 똑같은 줄기가 복사된 것처럼 보이지 않게.
                    // 무리수 배수를 써서 세 줄기의 출렁임이 절대 같은 박자로 맞아떨어지지 않게 한다.
                    val seed = hIdx * 2.399f
                    // 안쪽 물결과 같은 livePhase 로 발원점을 흔든다 — 물이 출렁이는 만큼
                    // 새는 자리도 같이 흔들려야 한 몸으로 보인다.
                    val slosh = sin(livePhase * (0.8f + hIdx * 0.27f) + seed) * 2.2f * wobbleMul
                    val holeAngle = angleDeg + slosh
                    val rad = Math.toRadians(holeAngle.toDouble())
                    val dirX = cos(rad).toFloat()
                    val dirY = sin(rad).toFloat()
                    val holeX = cx + dirX * r
                    val holeY = cy + dirY * r

                    // 구멍이 여러 개면 하나당 나오는 양이 줄어든다 — 총량은 세기가 정한다.
                    val share = (0.62f + intensity * 0.38f) / (1f + (holeCountF - 1f) * 0.35f)

                    // 실제로 통에 난 구멍에서 새는 물은 옆으로 멀리 뻗지 않는다. 수압으로 살짝
                    // 밀려 나오자마자 중력이 이겨서 거의 곧장 떨어진다. 예전에는 초기 속도가
                    // 커서 옆으로 크게 휘는 뿔처럼 보였다 — 가로 속도를 줄이고 중력을 키운다.
                    val v0 = r * (0.035f + intensity * 0.035f) * (0.75f + share * 0.5f) * speedMul
                    val gravity = r * (0.34f + intensity * 0.14f) * (0.9f + hIdx * 0.13f)
                    // 출렁임에 맞춰 굵기도 살짝 맥동한다 — 수도꼭지처럼 일정하지 않게.
                    val pulse = 1f + sin(livePhase * (1.6f + hIdx * 0.4f) + seed) * 0.14f
                    val headW = r * (0.080f + intensity * 0.085f) * share * pulse * open * widthMul

                    // 링에서 비울 반각 = 줄기 반폭이 원주에서 차지하는 각 (+ 아주 약간의 여유)
                    val halfGapDeg = Math.toDegrees(atan((headW * 1.15f) / r).toDouble()).toFloat()
                    specs += holeAngle to halfGapDeg

                    fun pointAt(t: Float): Offset {
                        val x = holeX + dirX * v0 * t
                        val y = holeY + dirY * v0 * t + gravity * t * t
                        return Offset(x, y)
                    }
                    // t 지점의 속력. 낙하하며 빨라진다.
                    fun speedAt(t: Float): Float {
                        val vy = dirY * v0 + 2f * gravity * t
                        return hypot(dirX * v0, vy).coerceAtLeast(1f)
                    }

                    // 줄기 굵기는 유량 보존을 따른다: 단면적 × 속력 = 일정.
                    // 즉 떨어지며 빨라질수록 가늘어진다. 예전처럼 (1-t)^1.6 으로 0 까지 좁히면
                    // 칼끝처럼 뾰족해져 물이 아니라 뿔처럼 보인다 — 실제 물줄기는 끝까지
                    // 두께를 가지다가 방울로 끊어진다.
                    val speed0 = speedAt(0f)
                    // 줄기는 도중에 끊어지고(표면장력) 그 아래는 방울이 이어받는다.
                    val breakT = 0.62f
                    fun halfAt(t: Float, side: Float): Float {
                        // 유량보존 그대로 쓰면(속도에 반비례) 순식간에 실오라기처럼 가늘어진다.
                        // 제곱근을 씌워 완만하게 좁히고, 최소 굵기도 넉넉히 잡는다.
                        val base = headW * sqrt(speed0 / speedAt(t)).coerceIn(0.62f, 1f)
                        // 좌우 가장자리를 다른 위상으로 흔든다 (완전 대칭이면 고무호스처럼 보인다).
                        // 파장이 다른 두 파를 겹쳐 표면이 규칙적인 물결로 안 보이게 한다.
                        val wob = (sin(t * 5.5f - flowPhase + seed + side * 1.9f) * 0.16f +
                            sin(t * 11.3f - flowPhase * 1.7f + seed * 2.1f) * 0.07f) * base
                        // 끊어지는 지점 부근에서만 잘록해진다
                        val neck = 1f - 0.35f * (t / breakT).coerceIn(0f, 1f).pow(3f)
                        return base * neck + wob
                    }

                    streamPath.reset()
                    // 한쪽 가장자리를 따라 내려갔다가 반대쪽 가장자리를 따라 올라와서 닫는다.
                    val ribSteps = (steps * breakT).toInt().coerceAtLeast(6)
                    for (i in 0..ribSteps) {
                        val t = breakT * i / ribSteps
                        val p = pointAt(t)
                        val sp = speedAt(t)
                        val nx = -(dirY * v0 + 2f * gravity * t) / sp
                        val ny = (dirX * v0) / sp
                        val half = halfAt(t, 1f)
                        if (i == 0) streamPath.moveTo(p.x + nx * half, p.y + ny * half)
                        else streamPath.lineTo(p.x + nx * half, p.y + ny * half)
                    }
                    for (i in ribSteps downTo 0) {
                        val t = breakT * i / ribSteps
                        val p = pointAt(t)
                        val sp = speedAt(t)
                        val nx = -(dirY * v0 + 2f * gravity * t) / sp
                        val ny = (dirX * v0) / sp
                        val half = halfAt(t, -1f)
                        streamPath.lineTo(p.x - nx * half, p.y - ny * half)
                    }
                    streamPath.close()

                    // 물기둥은 위가 밝고 아래로 갈수록 살짝 옅다 — 유리관 같은 입체감.
                    val head = pointAt(0f)
                    val breakPoint = pointAt(breakT)
                    drawPath(
                        streamPath,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                streamColor.copy(alpha = 0.95f * open),
                                streamColor.copy(alpha = 0.78f * open)
                            ),
                            start = head, end = breakPoint
                        )
                    )

                    // 안쪽에 비치는 밝은 심. 실제 물줄기는 속이 비쳐서 가운데가 하얗게 도드라진다 —
                    // 이게 없으면 그냥 칠해진 띠로 보인다. 가운데보다 약간 한쪽으로 치우쳐 그린다.
                    coreLightPath.reset()
                    for (i in 0..ribSteps) {
                        val t = breakT * i / ribSteps
                        val p = pointAt(t)
                        val sp = speedAt(t)
                        val nx = -(dirY * v0 + 2f * gravity * t) / sp
                        val ny = (dirX * v0) / sp
                        val off = halfAt(t, 0f) * 0.18f      // 심의 중심선을 살짝 치우친다
                        val core = halfAt(t, 0f) * 0.30f
                        if (i == 0) coreLightPath.moveTo(p.x + nx * (off + core), p.y + ny * (off + core))
                        else coreLightPath.lineTo(p.x + nx * (off + core), p.y + ny * (off + core))
                    }
                    for (i in ribSteps downTo 0) {
                        val t = breakT * i / ribSteps
                        val p = pointAt(t)
                        val sp = speedAt(t)
                        val nx = -(dirY * v0 + 2f * gravity * t) / sp
                        val ny = (dirX * v0) / sp
                        val off = halfAt(t, 0f) * 0.18f
                        val core = halfAt(t, 0f) * 0.30f
                        coreLightPath.lineTo(p.x + nx * (off - core), p.y + ny * (off - core))
                    }
                    coreLightPath.close()
                    drawPath(coreLightPath, Color.White.copy(alpha = 0.30f * open))

                    // 끊어진 지점부터는 방울이 같은 궤적을 그대로 이어 떨어진다.
                    // 줄기와 같은 pointAt 을 쓰므로 방울이 궤도에서 벗어나 보이지 않는다.
                    // 위상은 누적값(leakAcc)에서 가져온다 — 절대 시각 % 주기로 구하면 세기가
                    // 바뀌는 순간 위치가 점프한다.
                    // 실제 물줄기는 끊어진 뒤 한 줄로 떨어지지 않고 크고 작은 방울로 흩뿌려진다.
                    // 방울마다 크기·옆으로 벌어지는 정도·낙하 위상을 달리해 물보라처럼 보이게 한다.
                    val dropHalf = halfAt(breakT, 0f)
                    // 방울은 줄기보다 밝게 — 어두운 배경 위에서 흩날리는 물은 빛을 받아 도드라진다.
                    // 줄기와 같은 색에 알파만 낮추면 배경에 섞여 탁한 점처럼 보인다.
                    val dropColor = lerp(fillColor, Color.White, 0.62f)
                    val dropCount = 9
                    for (i in 0 until dropCount) {
                        // 황금비 간격으로 흩어 위상이 규칙적으로 뭉치지 않게
                        val phase = (leakAcc + i * 0.6180339f + hIdx * 0.27f) % 1f
                        val t = breakT + (1f - breakT) * phase
                        val p = pointAt(t)
                        val sp = speedAt(t)
                        val nx = -(dirY * v0 + 2f * gravity * t) / sp
                        val ny = (dirX * v0) / sp
                        // 좌우로 넓게 튄다. 방울마다 벌어지는 정도를 크게 달리해 부채꼴로 퍼지게.
                        val lateral = ((i % 5) - 2) * (0.5f + (i % 3) * 0.55f)
                        val spread = lateral * dropHalf * 1.5f * phase
                        // 거의 끝까지 밝게 유지하다 마지막에만 사라진다 (제곱으로 죽이면 금방 탁해진다)
                        val a = (1f - phase * 0.75f).coerceIn(0f, 1f) * 0.95f * open
                        val sizeVar = when (i % 4) {                 // 큰 방울 사이에 잔방울
                            0 -> 1.2f
                            1 -> 0.45f
                            2 -> 0.85f
                            else -> 0.62f
                        }
                        drawCircle(
                            dropColor.copy(alpha = a),
                            radius = dropHalf * sizeVar,
                            center = Offset(p.x + nx * spread, p.y + ny * spread)
                        )
                    }
                }
                holeSpecs = specs
            } else {
                holeSpecs = emptyList()
            }

            // 테두리 링 — ECO는 가늘게, SPORT는 두껍고 진하게 (연결 안 되면 항상 가늘게).
            // 구멍 자리는 링을 그리지 않고 비워서 "뚫린 틈"으로 보이게 한다. 줄기 위에 덮어
            // 그리므로 물이 틈을 통과해 나가는 것처럼 읽힌다.
            val ringStroke = Stroke(width = animatedRing.dp.toPx())
            val ringColor = fillColor.copy(alpha = 0.7f)
            if (holeSpecs.isEmpty()) {
                drawCircle(ringColor, radius = r, center = Offset(cx, cy), style = ringStroke)
            } else {
                val sorted = holeSpecs.sortedBy { it.first }
                sorted.forEachIndexed { i, (angle, halfGap) ->
                    val next = sorted[(i + 1) % sorted.size]
                    val from = angle + halfGap
                    val to = next.first - next.second +
                        if (i == sorted.lastIndex) 360f else 0f
                    val sweepDeg = to - from
                    if (sweepDeg > 0f) {
                        drawArc(
                            color = ringColor,
                            startAngle = from, sweepAngle = sweepDeg, useCenter = false,
                            topLeft = Offset(cx - r, cy - r),
                            size = Size(r * 2, r * 2),
                            style = ringStroke
                        )
                    }
                }
            }
        }
    }
}

private fun buildCircle(path: Path, w: Float, h: Float) {
    path.reset()
    val cx = w / 2f
    val cy = h * GaugeCenterY
    val r = min(w, h) * GaugeRadius
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

    // 주행 모드 하나가 아크 두께와 파워밴드 강조를 함께 움직인다. 값마다 animateFloatAsState 를
    // 따로 두면 스펙이 어긋나 서로 다른 시점에 도착하면서 전환이 어긋나 보인다 —
    // 하나의 Transition 에 자식 애니메이션으로 묶어 항상 같이 도착하게 한다.
    val modeTransition = rememberTransition(
        transitionState = remember { MutableTransitionState(mode) }.apply { targetState = mode },
        label = "driveMode"
    )
    val arcWidth by modeTransition.animateFloat(
        transitionSpec = { tween(500) }, label = "arcWidth"
    ) { m ->
        when (m) {
            DriveMode.ECO -> 10f
            DriveMode.NORMAL -> 14f
            DriveMode.SPORT -> 19f
        }
    }
    val sportAmount by modeTransition.animateFloat(
        transitionSpec = { tween(500) }, label = "sportAmount"
    ) { m -> if (m == DriveMode.SPORT) 1f else 0f }

    val tickLabelPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            setColor(android.graphics.Color.argb(220, 126, 150, 136))
        }
    }

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
