package com.nimbus.cashier

import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.roundToInt

// ── Colours, fonts, style ───────────────────────────────────

/** A hex colour as a Compose colour; null for anything unreadable. */
internal fun pwColor(hex: String?): Color? = PaywallColor.parse(hex)?.let { Color(it.toInt()) }

internal fun pwFamily(name: String): FontFamily = when (name) {
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    // Android has no rounded system face: "rounded" draws the default.
    else -> FontFamily.Default
}

/**
 * The theme as one block sees it: colours, radius, font, and `fill` (the
 * surface, or the page colour when the block sits on a surface-coloured
 * sheet or card).
 */
internal data class PaywallStyle(
    val text: Color,
    val muted: Color,
    val surface: Color,
    val background: Color,
    val accent: Color,
    val accentText: Color,
    val fill: Color,
    val radius: Float,
    val family: String,
) {
    fun card(r: Float? = null): Shape = RoundedCornerShape((r ?: radius).dp)
}

/** The inherited text colour (SwiftUI's foregroundColor). */
internal val LocalPaywallColor = compositionLocalOf { Color.Black }

/** The inherited look type (a container's type is its children's default). */
internal val LocalPaywallType = compositionLocalOf { PaywallLookMath.Type() }

/** A run of text drawn with the block's defaults and the inherited look type. */
@Composable
internal fun PaywallLabel(
    text: String,
    size: Double,
    family: String,
    modifier: Modifier = Modifier,
    weight: Int = 400,
    color: Color? = null,
    tracking: Double = 0.0,
    align: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    val type = LocalPaywallType.current
    val f = PaywallLookMath.font(size, weight, family, type)
    val c = pwColor(type.color) ?: color ?: LocalPaywallColor.current
    BasicText(
        text, modifier,
        style = TextStyle(
            color = c, fontSize = f.size.sp, fontWeight = FontWeight(f.weight), fontFamily = pwFamily(f.family),
            fontStyle = if (f.italic) FontStyle.Italic else FontStyle.Normal, letterSpacing = tracking.sp, textAlign = align,
        ),
        maxLines = maxLines, overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

/** Text in the block's own font (no look type), the inherited colour unless given. */
@Composable
internal fun PlainText(
    text: String,
    size: Double,
    family: String,
    modifier: Modifier = Modifier,
    weight: Int = 400,
    color: Color? = null,
    align: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    tracking: Double = 0.0,
    underline: Boolean = false,
    tabular: Boolean = false,
) {
    BasicText(
        text, modifier,
        style = TextStyle(
            color = color ?: LocalPaywallColor.current, fontSize = size.sp, fontWeight = FontWeight(weight),
            fontFamily = pwFamily(family), textAlign = align, letterSpacing = tracking.sp,
            textDecoration = if (underline) androidx.compose.ui.text.style.TextDecoration.Underline else null,
            fontFeatureSettings = if (tabular) "tnum" else null,
        ),
        maxLines = maxLines, overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

// ── Icons: the closed set, as the web's Lucide strokes ──────

internal object PaywallIcons {
    /** 24×24 stroke paths (Lucide, ISC licence), the same the web SDK draws. */
    val paths: Map<String, String> = mapOf(
        "check" to "M20 6 9 17l-5-5",
        "star" to "M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z",
        "bolt" to "M13 2 3 14h9l-1 8 10-12h-9l1-8z",
        "lock" to "M5 11h14v10H5zM7 11V7a5 5 0 0 1 10 0v4",
        "cloud" to "M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9z",
        "heart" to "M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7z",
        "sparkles" to "M12 3l1.9 5.8L20 11l-6.1 2.2L12 19l-1.9-5.8L4 11l6.1-2.2L12 3zM5 3v4M3 5h4M19 17v4M17 19h4",
        "infinity" to "M12 12c-2-2.67-4-4-6-4a4 4 0 1 0 0 8c2 0 4-1.33 6-4zm0 0c2 2.67 4 4 6 4a4 4 0 0 0 0-8c-2 0-4 1.33-6 4z",
        "shield" to "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z",
        "crown" to "M2 4l3 12h14l3-12-6 7-4-7-4 7-6-7zM5 20h14",
        "gift" to "M20 12v10H4V12M2 7h20v5H2zM12 22V7M12 7H7.5a2.5 2.5 0 0 1 0-5C11 2 12 7 12 7zM12 7h4.5a2.5 2.5 0 0 0 0-5C13 2 12 7 12 7z",
        "clock" to "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM12 6v6l4 2",
        "chart" to "M3 3v18h18M18 17V9M13 17V5M8 17v-3",
        "bell" to "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9M10.3 21a1.94 1.94 0 0 0 3.4 0",
        "download" to "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3",
        "people" to "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75",
        "mail" to "M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM22 6l-10 7L2 6",
        "target" to "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM12 18a6 6 0 1 0 0-12 6 6 0 0 0 0 12zM12 14a2 2 0 1 0 0-4 2 2 0 0 0 0 4z",
        "trophy" to "M6 9H4.5a2.5 2.5 0 0 1 0-5H6M18 9h1.5a2.5 2.5 0 0 0 0-5H18M4 22h16M10 14.66V17c0 .55-.47.98-.97 1.21C7.85 18.75 7 20.24 7 22M14 14.66V17c0 .55.47.98.97 1.21C16.15 18.75 17 20.24 17 22M18 2H6v7a6 6 0 0 0 12 0V2Z",
        "rocket" to "M4.5 16.5c-1.5 1.26-2 5-2 5s3.74-.5 5-2c.71-.84.7-2.13-.09-2.91a2.18 2.18 0 0 0-2.91-.09zM12 15l-3-3a22 22 0 0 1 2-3.95A12.88 12.88 0 0 1 22 2c0 2.72-.78 7.5-6 11a22.35 22.35 0 0 1-4 2zM9 12H4s.55-3.03 2-4c1.62-1.08 5 0 5 0M12 15v5s3.03-.55 4-2c1.08-1.62 0-5 0-5",
    )

    /** Glyphs the renderer itself uses. */
    val chrome: Map<String, String> = mapOf(
        "xmark" to "M18 6 6 18M6 6l12 12",
        "chevron.left" to "M15 18l-6-6 6-6",
        "chevron.right" to "m9 18 6-6-6-6",
        "play" to "M6 3l14 9-14 9V3z",
        "play.circle" to "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM10 8l6 4-6 4V8z",
        "person" to "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z",
    )

    /** A name from the icon set, else "check" (as iOS). */
    fun path(name: String?): String = paths[name] ?: chrome[name] ?: paths.getValue("check")

    private val cache = HashMap<Pair<String, Boolean>, ImageVector>()

    fun vector(name: String?, filled: Boolean = false): ImageVector = synchronized(cache) {
        val d = path(name)
        cache.getOrPut(d to filled) {
            ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
                .addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    fill = if (filled) SolidColor(Color.Black) else null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                .build()
        }
    }
}

/** One icon of the set, tinted, [size] square. */
@Composable
internal fun PaywallIcon(name: String?, size: Dp, color: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    Image(
        painter = rememberVectorPainter(PaywallIcons.vector(name, filled)),
        contentDescription = null,
        colorFilter = ColorFilter.tint(color),
        modifier = modifier.size(size),
    )
}

// ── Modifiers ───────────────────────────────────────────────

/** Padding that may be negative (a negative side pulls the block out, its slot shrinks). */
internal fun Modifier.signedPadding(start: Dp = 0.dp, top: Dp = 0.dp, end: Dp = 0.dp, bottom: Dp = 0.dp): Modifier =
    if (start == 0.dp && top == 0.dp && end == 0.dp && bottom == 0.dp) this else layout { m, c ->
        val l = start.roundToPx(); val t = top.roundToPx(); val r = end.roundToPx(); val b = bottom.roundToPx()
        val inner = c.grow(-(l + r), -(t + b))
        val p = m.measure(inner)
        val w = c.constrainWidth(max(0, p.width + l + r))
        val h = c.constrainHeight(max(0, p.height + t + b))
        layout(w, h) { p.place(l, t) }
    }

private fun Constraints.grow(horizontal: Int, vertical: Int): Constraints {
    fun add(v: Int, d: Int) = if (v == Constraints.Infinity) v else max(0, v + d)
    val maxW = add(maxWidth, horizontal)
    val maxH = add(maxHeight, vertical)
    return Constraints(
        minWidth = add(minWidth, horizontal).coerceAtMost(maxW), maxWidth = maxW,
        minHeight = add(minHeight, vertical).coerceAtMost(maxH), maxHeight = maxH,
    )
}

/** A tap target without ripple (SwiftUI's plain button style). */
internal fun Modifier.plainClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick)
}

/** Moves a view up by half its height without taking room (a badge on a card's top edge). */
internal fun Modifier.straddleTop(): Modifier = layout { m, c ->
    val p = m.measure(c)
    layout(p.width, p.height) { p.place(0, -p.height / 2) }
}

/** An offset shadow cast by [shape] (API 28+ in hardware; earlier Android draws none). */
internal fun Modifier.castShadow(shape: Shape, color: Color, x: Dp, y: Dp, blur: Dp): Modifier = drawBehind {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@drawBehind
    val path = outlinePath(shape.createOutline(size, layoutDirection, this))
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        this.color = color.toArgb()
        setShadowLayer(max(0.01f, blur.toPx() / 2), x.toPx(), y.toPx(), color.toArgb())
    }
    drawIntoCanvas { it.nativeCanvas.drawPath(path.asAndroidPath(), paint) }
}

internal fun outlinePath(o: Outline): Path = when (o) {
    is Outline.Rectangle -> Path().apply { addRect(o.rect) }
    is Outline.Rounded -> Path().apply { addRoundRect(o.roundRect) }
    is Outline.Generic -> o.path
}

/** Draws [image] to cover the whole draw area (aspect-fill, centred). */
internal fun DrawScope.drawCover(image: ImageBitmap) {
    val sw = image.width.toFloat(); val sh = image.height.toFloat()
    if (sw <= 0 || sh <= 0 || size.width <= 0 || size.height <= 0) return
    val scale = max(size.width / sw, size.height / sh)
    val w = (size.width / scale); val h = (size.height / scale)
    drawImage(
        image,
        srcOffset = IntOffset(((sw - w) / 2).roundToInt().coerceAtLeast(0), ((sh - h) / 2).roundToInt().coerceAtLeast(0)),
        srcSize = IntSize(w.roundToInt().coerceAtMost(image.width), h.roundToInt().coerceAtMost(image.height)),
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
    )
}

// ── Look box ────────────────────────────────────────────────

/**
 * A look's box around a block: margin → placement → opacity → shadow →
 * fill (colour, gradient, image; clipped to the corners) → border → size
 * → padding. With no look, just the content.
 *
 * [intrinsics] is false when the block holds something that cannot be
 * asked its natural width (a pager), so "fit" leaves it as it is.
 */
@Composable
internal fun LookBox(
    look: PaywallDoc.Look?,
    align: Alignment.Horizontal,
    places: Boolean,
    modifier: Modifier = Modifier,
    intrinsics: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (look == null) {
        Box(modifier) { content() }
        return
    }
    val c = PaywallLookMath.corners(look)
    val shape = RoundedCornerShape(c[0].dp, c[1].dp, c[2].dp, c[3].dp)
    val width = PaywallLookMath.size(look.width, look.widthPx)
    val height = PaywallLookMath.size(look.height, look.heightPx)
    val top = pwColor(look.background)
    val end = pwColor(look.backgroundEnd)
    val image = rememberPaywallImage(look.backgroundImage)
    val hasFill = top != null || PaywallURL.safe(look.backgroundImage) != null
    val shadow = pwColor(look.shadowColor)
    val opacity = look.opacity?.let { if (it >= 1 && it <= 100) it / 100 else 1.0 } ?: 1.0
    val border = pwColor(look.borderColor)
    val bw = look.borderWidth ?: 0.0

    var m = modifier.signedPadding(
        start = (look.marginX ?: 0.0).dp, end = (look.marginX ?: 0.0).dp,
        top = (look.marginY ?: 0.0).dp, bottom = (look.marginY ?: 0.0).dp,
    )
    if (places && (width == PaywallLookMath.Size.Fit || width is PaywallLookMath.Size.Fixed)) {
        m = m.fillMaxWidth().wrapContentWidth(align)
    }
    if (opacity < 1) m = m.alpha(opacity.toFloat())
    if (hasFill && shadow != null) {
        m = m.castShadow(shape, shadow, (look.shadowX ?: 0.0).dp, (look.shadowY ?: 0.0).dp, (look.shadowBlur ?: 0.0).dp)
    }
    if (top != null) m = m.background(if (end != null) Brush.verticalGradient(listOf(top, end)) else SolidColor(top), shape)
    if (image != null) {
        m = m.drawBehind {
            val path = outlinePath(shape.createOutline(size, layoutDirection, this))
            clipPath(path) { drawCover(image) }
        }
    }
    if (border != null && bw > 0) m = m.border(bw.dp, border, shape)
    m = when (width) {
        PaywallLookMath.Size.Fit -> if (intrinsics) m.width(IntrinsicSize.Max) else m
        PaywallLookMath.Size.Fill -> m.fillMaxWidth()
        is PaywallLookMath.Size.Fixed -> m.width(width.px.dp)
        null -> m
    }
    m = when (height) {
        PaywallLookMath.Size.Fill -> m.fillMaxHeight()
        is PaywallLookMath.Size.Fixed -> m.height(height.px.dp).wrapContentHeight()
        else -> m
    }
    m = m.padding(horizontal = max(look.paddingX ?: 0.0, 0.0).dp, vertical = max(look.paddingY ?: 0.0, 0.0).dp)
    Box(m, contentAlignment = if (width is PaywallLookMath.Size.Fixed) Alignment.TopCenter else Alignment.TopStart) { content() }
}

// ── Images ──────────────────────────────────────────────────

/**
 * A small image loader (no dependency): https downloads, decoded at most
 * ~1600 px on the long side, kept in a memory cache.
 */
internal object PaywallImages {
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = max(1, value.width * value.height * 4 / 1024)
    }

    fun cached(url: String?): ImageBitmap? = url?.let { synchronized(cache) { cache.get(it) } }

    suspend fun load(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        cached(url)?.let { return@withContext it }
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            val bytes = try {
                if (conn.responseCode !in 200..299) return@withContext null
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@withContext null
            bmp.asImageBitmap().also { synchronized(cache) { cache.put(url, it) } }
        } catch (_: Exception) {
            null
        }
    }
}

/** The image at [url] once loaded (null while loading, or when the URL is not allowed). */
@Composable
internal fun rememberPaywallImage(url: String?): ImageBitmap? {
    val safe = PaywallURL.safe(url)
    val image by produceState(PaywallImages.cached(safe), safe) {
        if (value == null && safe != null) value = PaywallImages.load(safe)
    }
    return image
}

/** An aspect-filled image in its box, [placeholder] until it loads. */
@Composable
internal fun PaywallImage(url: String?, modifier: Modifier, placeholder: @Composable () -> Unit = {}) {
    val image = rememberPaywallImage(url)
    Box(modifier) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Box(Modifier.matchParentSize()) { placeholder() }
        }
    }
}

// ── Small drawings ──────────────────────────────────────────

/** An indeterminate spinner (no Material). */
@Composable
internal fun PaywallSpinner(color: Color, size: Dp = 22.dp) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Canvas(Modifier.size(size)) {
        rotate(angle) {
            drawArc(color, 0f, 270f, false, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                topLeft = Offset(2.dp.toPx(), 2.dp.toPx()), size = Size(this.size.width - 4.dp.toPx(), this.size.height - 4.dp.toPx()))
        }
    }
}

/** One side of a laurel wreath (the web's leaves along an arc). */
@Composable
internal fun PaywallLaurel(right: Boolean, color: Color) {
    Canvas(Modifier.size(width = 20.dp, height = 40.dp)) {
        val s = size.width / 24f
        withTransform({
            if (right) scale(-1f, 1f, pivot = center)
            scale(s, s, pivot = Offset.Zero)
        }) {
            val stem = Path().apply { moveTo(18f, 45f); cubicTo(6f, 38f, 4f, 18f, 12f, 3f) }
            drawPath(stem, color, style = Stroke(width = 1.6f, cap = StrokeCap.Round))
            for ((x, y, a) in listOf(Triple(16f, 42f, 60f), Triple(10f, 34f, 40f), Triple(7f, 25f, 20f), Triple(8f, 16f, 0f), Triple(11f, 8f, -20f))) {
                for (side in listOf(-1, 1)) {
                    val cx = x + side * 3.2f
                    rotate(a + side * 35f, pivot = Offset(cx, y)) {
                        drawOval(color, topLeft = Offset(cx - 3.4f, y - 1.6f), size = Size(6.8f, 3.2f))
                    }
                }
            }
        }
    }
}
