package com.nimbus.cashier

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Build
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** SwiftUI's easeInOut. */
private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

// ── Screen effects ──────────────────────────────────────────

/**
 * An animated background drawn from the accent, behind a screen's
 * blocks: glow | aurora | orbs | grid | rays. Never takes touches;
 * [animates] false draws it at rest.
 */
@Composable
internal fun PaywallEffect(kind: String, accentHex: String, accent: Color, text: Color, animates: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "effect")
    @Composable
    fun phase(seconds: Double, delay: Double = 0.0, reverse: Boolean = true, linear: Boolean = false): Float {
        if (!animates) return 0f
        val v by transition.animateFloat(
            0f, 1f,
            infiniteRepeatable(
                tween((seconds * 1000).toInt(), easing = if (linear) LinearEasing else EaseInOut),
                if (reverse) RepeatMode.Reverse else RepeatMode.Restart,
                StartOffset((delay * 1000).toInt()),
            ),
            label = "phase",
        )
        return v
    }
    when (kind) {
        "glow" -> {
            val p = phase(6.0)
            Canvas(modifier.fillMaxSize()) {
                val s = 1 + 0.08f * p
                drawRect(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.38f), accent.copy(alpha = 0f)), Offset(size.width / 2, 0f), size.width * 0.85f * s),
                    alpha = 1 - 0.25f * p,
                )
            }
        }
        "aurora" -> {
            data class Blob(val x: Float, val y: Float, val hue: Double, val dx: Float, val dy: Float, val t: Double)
            val blobs = listOf(Blob(0.15f, 0.08f, 0.0, 0.10f, 0.06f, 14.0), Blob(0.85f, 0.22f, 40.0, -0.12f, 0.08f, 17.0), Blob(0.35f, 0.48f, -35.0, 0.08f, -0.10f, 12.0))
            val phases = blobs.map { phase(it.t) }
            val colours = blobs.map { b -> PaywallColor.hueShift(accentHex, b.hue)?.let { Color(it.toInt()) } ?: accent }
            Canvas(modifier.fillMaxSize()) {
                val w = size.width
                // A blurred disc: a radial fade stands in for SwiftUI's 60pt blur.
                val r = w * 0.75f / 2 + 60.dp.toPx()
                blobs.forEachIndexed { i, b ->
                    val c = Offset(w * b.x + w * b.dx * phases[i], size.height * b.y + w * b.dy * phases[i])
                    drawCircle(Brush.radialGradient(listOf(colours[i].copy(alpha = 0.4f), colours[i].copy(alpha = 0.2f), colours[i].copy(alpha = 0f)), c, r), r, c)
                }
            }
        }
        "orbs" -> {
            val orbs = listOf(
                floatArrayOf(0.12f, 0.14f, 18f, 0.35f, 4f, 0f), floatArrayOf(0.82f, 0.10f, 26f, 0.25f, 5.5f, 0.6f),
                floatArrayOf(0.68f, 0.34f, 10f, 0.5f, 4.5f, 1.2f), floatArrayOf(0.22f, 0.46f, 14f, 0.3f, 6f, 0.3f),
                floatArrayOf(0.90f, 0.58f, 20f, 0.22f, 5f, 0.9f), floatArrayOf(0.08f, 0.72f, 8f, 0.45f, 4.2f, 1.5f),
                floatArrayOf(0.60f, 0.82f, 16f, 0.28f, 6.5f, 0.4f),
            )
            val phases = orbs.map { phase(it[4].toDouble(), it[5].toDouble()) }
            Canvas(modifier.fillMaxSize()) {
                orbs.forEachIndexed { i, o ->
                    drawCircle(accent.copy(alpha = o[3]), o[2].dp.toPx() / 2, Offset(size.width * o[0], size.height * o[1] - 10.dp.toPx() * phases[i]))
                }
            }
        }
        "grid" -> Canvas(modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            val step = 24.dp.toPx()
            val line = text.copy(alpha = 0.07f)
            var x = 0f
            while (x <= size.width) { drawLine(line, Offset(x + 0.5f, 0f), Offset(x + 0.5f, size.height), 1.dp.toPx()); x += step }
            var y = 0f
            while (y <= size.height) { drawLine(line, Offset(0f, y + 0.5f), Offset(size.width, y + 0.5f), 1.dp.toPx()); y += step }
            drawRect(Brush.verticalGradient(0f to Color.Black, 0.6f to Color.Transparent), blendMode = BlendMode.DstIn)
        }
        "rays" -> {
            val p = phase(60.0, reverse = false, linear = true)
            Canvas(modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
                val w = size.width; val h = size.height
                val centre = Offset(w * 0.5f, -h * 0.1f)
                val ray = accent.copy(alpha = 0.12f)
                val wedge = 1f / 32
                val stops = ArrayList<Pair<Float, Color>>()
                for (i in 0 until 16) {
                    val s = i * 2 * wedge
                    stops += s to ray; stops += (s + wedge) to ray
                    stops += (s + wedge) to Color.Transparent; stops += (s + 2 * wedge) to Color.Transparent
                }
                rotate(360f * p, centre) {
                    val side = max(w, h) * 2.6f
                    drawRect(Brush.sweepGradient(*stops.toTypedArray(), center = centre), Offset(centre.x - side / 2, centre.y - side / 2), Size(side, side))
                }
                drawRect(Brush.radialGradient(listOf(Color.Black, Color.Transparent), centre, w * 1.1f), blendMode = BlendMode.DstIn)
            }
        }
        else -> {}
    }
}

// ── Entrance ────────────────────────────────────────────────

/**
 * Brings a top-level block in: from transparent (and lower, or smaller)
 * to rest, after [delayMillis]. At rest when [kind] is none or not animating.
 */
internal fun Modifier.entrance(kind: String?, key: Any, delayMillis: Long, animates: Boolean): Modifier = composed {
    val active = animates && kind in setOf("rise", "fade", "zoom")
    val progress = remember(key) { Animatable(if (active) 0f else 1f) }
    LaunchedEffect(key, active) {
        if (active && progress.value < 1f) {
            delay(delayMillis)
            // SwiftUI's spring(response: 0.55, dampingFraction: 0.85).
            progress.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 130f))
        } else if (!active) {
            progress.snapTo(1f)
        }
    }
    graphicsLayer {
        val v = progress.value
        alpha = v.coerceIn(0f, 1f)
        if (kind == "rise") translationY = (1 - v) * 18.dp.toPx()
        if (kind == "zoom") { val s = 0.92f + 0.08f * v; scaleX = s; scaleY = s }
    }
}

// ── Icon block ──────────────────────────────────────────────

/** The icon block: tile | glow | rings | gradient | plain. An emoji, when set, takes the glyph's place. */
@Composable
internal fun PaywallIconView(icon: String?, emoji: String?, size: Double, variant: String, style: PaywallStyle, accent2: Color, animates: Boolean) {
    val type = LocalPaywallType.current
    val hasEmoji = !emoji.isNullOrEmpty()
    val s = size.dp
    val transition = rememberInfiniteTransition(label = "icon")
    @Composable
    fun glyph(color: Color, scale: Double = 0.5, emojiScale: Double = 0.55) {
        if (hasEmoji) {
            BasicText(emoji ?: "", style = TextStyle(fontSize = (size * emojiScale).sp))
        } else {
            PaywallIcon(icon, (size * scale * 1.1).dp, pwColor(type.color) ?: color)
        }
    }
    @Composable
    fun forever(ms: Int, delayMs: Int = 0, reverse: Boolean = true, easeOut: Boolean = false): Float {
        if (!animates) return 0f
        val v by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = if (easeOut) EaseOut else EaseInOut),
            if (reverse) RepeatMode.Reverse else RepeatMode.Restart, StartOffset(delayMs)), label = "p")
        return v
    }
    val gradient = Brush.linearGradient(listOf(style.accent, accent2))
    when (variant) {
        "glow" -> {
            val p = forever(2400)
            val k = 1 + 0.06f * p
            Box(
                Modifier.size(s).graphicsLayer { scaleX = k; scaleY = k }
                    .drawBehind {
                        drawCircle(Brush.radialGradient(listOf(style.accent.copy(alpha = 0.45f), Color.Transparent), center + Offset(0f, 10.dp.toPx()), this.size.minDimension * 0.5f + 24.dp.toPx()))
                    }
                    .background(gradient, CircleShape),
                contentAlignment = Alignment.Center,
            ) { glyph(style.accentText) }
        }
        "rings" -> Box(Modifier.size(s), contentAlignment = Alignment.Center) {
            for (i in 0 until 2) {
                val p = forever(2400, delayMs = i * 1200, reverse = false, easeOut = true)
                // At rest one ring shows half-way out, so the variant still reads.
                val scale = if (animates) 1 + 0.9f * p else if (i == 0) 1.45f else 1f
                val alpha = if (animates) 0.6f * (1 - p) else if (i == 0) 0.3f else 0f
                Box(Modifier.size(s).graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }.border(2.dp, style.accent.copy(alpha = 0.5f), CircleShape))
            }
            Box(Modifier.size(s).background(style.fill, CircleShape), contentAlignment = Alignment.Center) { glyph(style.accent) }
        }
        "gradient" -> {
            val p = forever(3200)
            Box(
                Modifier.size(s).graphicsLayer {
                    translationY = -6.dp.toPx() * p
                    rotationZ = if (animates) -3f + 6f * p else 0f
                }
                    .shadow(12.dp, RoundedCornerShape(s * 0.28f), ambientColor = style.accent.copy(alpha = 0.35f), spotColor = style.accent.copy(alpha = 0.35f))
                    .background(gradient, RoundedCornerShape(s * 0.28f)),
                contentAlignment = Alignment.Center,
            ) { glyph(style.accentText) }
        }
        "plain" -> Box(Modifier.size(s), contentAlignment = Alignment.Center) { glyph(style.accent, 0.6, 0.8) }
        else -> Box(Modifier.size(s).background(style.fill, CircleShape), contentAlignment = Alignment.Center) { glyph(style.accent) }
    }
}

// ── Art ─────────────────────────────────────────────────────

/** Draws an illustration to cover its box (aspect-fill, centred). Animated shapes move only when [animates]. */
@Composable
internal fun PaywallArtView(name: String, palette: Map<String, String>, animates: Boolean, modifier: Modifier) {
    val shapes = PaywallArt.scenes[name] ?: emptyList()
    val moving = animates && shapes.any { it.anim != null }
    var time by remember { mutableFloatStateOf(0f) }
    if (moving) {
        LaunchedEffect(name) {
            val start = withFrameNanos { it }
            while (true) withFrameNanos { time = (it - start) / 1e9f }
        }
    }
    Canvas(modifier) {
        val scale = max(size.width / PaywallArt.WIDTH.toFloat(), size.height / PaywallArt.HEIGHT.toFloat())
        val t = if (moving) time.toDouble() else null
        withTransform({
            translate((size.width - PaywallArt.WIDTH.toFloat() * scale) / 2, (size.height - PaywallArt.HEIGHT.toFloat() * scale) / 2)
            scale(scale, scale, Offset.Zero)
        }) {
            shapes.forEach { drawArtShape(it, palette, scale, t) }
        }
    }
}

private fun artPath(s: PaywallArt.Shape): Path = Path().apply {
    when (s.t) {
        "rect" -> {
            val r = Rect((s.x ?: 0.0).toFloat(), (s.y ?: 0.0).toFloat(), ((s.x ?: 0.0) + (s.w ?: 0.0)).toFloat(), ((s.y ?: 0.0) + (s.h ?: 0.0)).toFloat())
            val rad = (s.r ?: 0.0).toFloat()
            if (rad > 0) addRoundRect(androidx.compose.ui.geometry.RoundRect(r, androidx.compose.ui.geometry.CornerRadius(minOf(rad, r.width / 2, r.height / 2))))
            else addRect(r)
        }
        "circle", "ellipse" -> {
            val b = PaywallArt.box(s)
            addOval(Rect(b[0].toFloat(), b[1].toFloat(), (b[0] + b[2]).toFloat(), (b[1] + b[3]).toFloat()))
        }
        "path" -> s.path.forEach { op ->
            when (op) {
                is PaywallArt.PathOp.Move -> moveTo(op.x.toFloat(), op.y.toFloat())
                is PaywallArt.PathOp.Line -> lineTo(op.x.toFloat(), op.y.toFloat())
                is PaywallArt.PathOp.Cubic -> cubicTo(op.x1.toFloat(), op.y1.toFloat(), op.x2.toFloat(), op.y2.toFloat(), op.x.toFloat(), op.y.toFloat())
                PaywallArt.PathOp.Close -> close()
            }
        }
    }
}

private fun DrawScope.drawArtShape(s: PaywallArt.Shape, palette: Map<String, String>, scale: Float, time: Double?) {
    val path = artPath(s)
    val box = PaywallArt.box(s)
    var opacity = 1f
    var dx = 0f
    var dy = 0f
    var spin = 0f
    if (time != null && s.anim != null) {
        val delay = s.delay ?: 0.0
        when (s.anim) {
            "twinkle" -> opacity = (1 - 0.75 * PaywallArt.swing(time, 2.2, delay)).toFloat()
            "float" -> dy = (-6 * PaywallArt.swing(time, 3.4, delay)).toFloat()
            "drift" -> dx = (14 * PaywallArt.swing(time, 9.0, delay)).toFloat()
            "spin" -> spin = ((time / 70) % 1 * 360).toFloat()
        }
    }
    val (cx, cy) = PaywallArt.centre(s)
    withTransform({
        translate(dx, dy)
        if (spin != 0f) rotate(spin, Offset((s.cx ?: 200.0).toFloat(), (s.cy ?: 130.0).toFloat()))
        s.rot?.takeIf { it != 0.0 }?.let { rotate(it.toFloat(), Offset(cx.toFloat(), cy.toFloat())) }
    }) {
        val blur = s.blur ?: 0.0
        val fill = s.fill
        if (fill != null) {
            val paint = Paint()
            when (fill) {
                is PaywallArt.Paint.Token -> {
                    if (fill.token == "none") return@withTransform
                    val c = PaywallArt.color(fill.token, palette) ?: return@withTransform
                    paint.color = Color(c.argb.toInt()).copy(alpha = (c.a * opacity).toFloat().coerceIn(0f, 1f))
                }
                is PaywallArt.Paint.Linear -> {
                    val stops = fill.stops.mapIndexed { i, t ->
                        (i.toFloat() / max(1, fill.stops.size - 1)) to (PaywallArt.color(t, palette)?.let { Color(it.argb.toInt()) } ?: Color.Transparent)
                    }
                    // CSS-like angle (180 = top → bottom) across the shape's box.
                    val rad = (fill.angle - 90) * PI / 180
                    val x0 = box[0]; val y0 = box[1]; val w = box[2]; val h = box[3]
                    val from = Offset((x0 + (0.5 - kotlin.math.cos(rad) / 2) * w).toFloat(), (y0 + (0.5 - kotlin.math.sin(rad) / 2) * h).toFloat())
                    val to = Offset((x0 + (0.5 + kotlin.math.cos(rad) / 2) * w).toFloat(), (y0 + (0.5 + kotlin.math.sin(rad) / 2) * h).toFloat())
                    Brush.linearGradient(*stops.toTypedArray(), start = from, end = to).applyTo(Size(w.toFloat(), h.toFloat()), paint, opacity)
                }
            }
            drawIntoCanvas { canvas ->
                val native = paint.asFrameworkPaint()
                native.isAntiAlias = true
                if (blur > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // SVG's stdDeviation blur/2 in canvas units, as the web draws it.
                    native.maskFilter = BlurMaskFilter((blur * 0.87).toFloat().coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL)
                }
                canvas.drawPath(path, paint)
            }
        }
        val stroke = s.stroke?.let { PaywallArt.color(it, palette) }
        if (stroke != null) {
            drawPath(path, Color(stroke.argb.toInt()), alpha = opacity, style = Stroke(width = (s.sw ?: 1.0).toFloat()))
        }
    }
}

// ── Carousel ────────────────────────────────────────────────

/**
 * Pages that swipe (and advance on their own every [interval] seconds).
 * dots: dots underneath; bars: story-style progress bars above; peek:
 * 84%-wide cards so the next page shows, dots underneath.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PaywallCarousel(
    count: Int,
    height: Dp,
    interval: Int,
    variant: String,
    style: PaywallStyle,
    page: @Composable (Int) -> Unit,
) {
    val pager = rememberPagerState { count }
    var peekIndex by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val index = if (variant == "peek") peekIndex else pager.currentPage
    fun go(i: Int) {
        if (variant == "peek") peekIndex = i else scope.launch { pager.animateScrollToPage(i) }
    }
    LaunchedEffect(interval, count, variant) {
        if (interval <= 0 || count <= 1) return@LaunchedEffect
        while (true) {
            delay(interval * 1000L)
            val next = ((if (variant == "peek") peekIndex else pager.currentPage) + 1) % count
            go(next)
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (variant == "bars" && count > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(count) { i ->
                    Box(Modifier.weight(1f).plainClickable { go(i) }.padding(vertical = 4.dp)) {
                        Box(Modifier.fillMaxWidth().height(3.dp).background(if (i == index) style.accent else style.muted.copy(alpha = 0.3f), RoundedCornerShape(50)))
                    }
                }
            }
        }
        if (variant == "peek") {
            PeekPager(count, peekIndex, height, style, { peekIndex = it }, page)
        } else {
            HorizontalPager(pager, Modifier.fillMaxWidth().height(height)) { i ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { page(i) }
            }
        }
        if (variant != "bars" && count > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                repeat(count) { i ->
                    Box(Modifier.size(7.dp).background(if (i == index) style.accent else style.muted.copy(alpha = 0.35f), CircleShape).plainClickable { go(i) })
                }
            }
        }
    }
}

/** Cards 84% of the width in a row, slid to the current one. */
@Composable
private fun PeekPager(count: Int, index: Int, height: Dp, style: PaywallStyle, onIndex: (Int) -> Unit, page: @Composable (Int) -> Unit) {
    val offset by animateFloatAsState(index.toFloat(), tween(300, easing = EaseInOut), label = "peek")
    var drag by remember { mutableFloatStateOf(0f) }
    Layout(
        content = {
            repeat(count) { i ->
                Box(Modifier.clip(style.card()).background(style.fill, style.card()).padding(16.dp), contentAlignment = Alignment.Center) { page(i) }
            }
        },
        modifier = Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(0.dp)).pointerInput(count, index) {
            detectHorizontalDragGestures(
                onDragStart = { drag = 0f },
                onDragEnd = {
                    if (abs(drag) > 40.dp.toPx()) onIndex(if (drag < 0) minOf(index + 1, count - 1) else maxOf(index - 1, 0))
                    drag = 0f
                },
            ) { _, amount -> drag += amount }
        },
    ) { measurables, c ->
        val w = c.maxWidth
        val cardW = (w * 0.84f).roundToInt()
        val gap = 12.dp.roundToPx()
        val h = c.maxHeight.takeIf { it != Constraints.Infinity } ?: height.roundToPx()
        val placeables = measurables.map { it.measure(Constraints.fixed(cardW, h)) }
        layout(w, h) {
            placeables.forEachIndexed { i, p -> p.place((i * (cardW + gap) - offset * (cardW + gap)).roundToInt(), 0) }
        }
    }
}

// ── Video ───────────────────────────────────────────────────

/**
 * A muted, looping, autoplaying video through the platform MediaPlayer on
 * a TextureView (no ExoPlayer dependency): aspect-fill when [fill], else
 * aspect-fit. Invisible until its first frame, so what is under it (a
 * poster) shows while it loads or when it cannot play.
 */
internal class PaywallVideoView(context: Context, private val url: String, private val fill: Boolean) :
    TextureView(context), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    var onPlaying: (() -> Unit)? = null

    init {
        surfaceTextureListener = this
        alpha = 0f
        isOpaque = false
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val s = Surface(st).also { surface = it }
        try {
            player = MediaPlayer().apply {
                setSurface(s)
                setDataSource(url)
                isLooping = true
                setVolume(0f, 0f)
                setOnPreparedListener { it.start(); fit() }
                setOnVideoSizeChangedListener { _, _, _ -> fit() }
                setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) { alpha = 1f; onPlaying?.invoke() }
                    false
                }
                setOnErrorListener { _, _, _ -> true }
                prepareAsync()
            }
        } catch (_: Exception) {
            release()
        }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = fit()
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { release(); return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    private fun fit() {
        val p = player ?: return
        val vw = try { p.videoWidth.toFloat() } catch (_: Exception) { 0f }
        val vh = try { p.videoHeight.toFloat() } catch (_: Exception) { 0f }
        val w = width.toFloat(); val h = height.toFloat()
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return
        val s = if (fill) max(w / vw, h / vh) else minOf(w / vw, h / vh)
        setTransform(Matrix().apply { setScale(vw * s / w, vh * s / h, w / 2, h / 2) })
    }

    fun pause() = runCatching { player?.pause() }
    fun resume() = runCatching { player?.start() }

    fun release() {
        runCatching { player?.release() }
        player = null
        surface?.release()
        surface = null
    }
}

/** A looping muted video filling [modifier]'s box; [placeholder] under it until it plays. */
@Composable
internal fun PaywallVideo(url: String, fill: Boolean, modifier: Modifier, placeholder: @Composable () -> Unit) {
    var playing by remember(url) { mutableStateOf(false) }
    Box(modifier) {
        if (!playing) Box(Modifier.matchParentSize()) { placeholder() }
        AndroidView(
            factory = { ctx -> PaywallVideoView(ctx, url, fill).also { it.onPlaying = { playing = true } } },
            modifier = Modifier.matchParentSize(),
            onRelease = { it.release() },
        )
    }
}
