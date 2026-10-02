package com.nimbus.cashier

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a paywall document with already-priced packages. [PaywallView]
 * wraps it with Google Play and Cashier; use this directly to render a
 * paywall with your own purchase handling (or in previews).
 *
 * @param onAnswer called when a button carrying an `answer` is tapped, before its action runs.
 * @param onNavigate called when a button moves the flow to another screen.
 * @param screen draw this one screen and do not navigate (previews). Without
 *   it the flow opens on the initial screen and buttons move through it.
 * @param animate play effects, entrances and screen transitions (also off
 *   when the system's animator duration scale is 0).
 */
@Composable
fun PaywallContent(
    doc: PaywallDoc,
    appName: String,
    packages: List<PaywallPackageView>,
    onPurchase: (PaywallPackageView) -> Unit,
    modifier: Modifier = Modifier,
    isPurchasing: Boolean = false,
    onRestore: () -> Unit = {},
    onClose: (() -> Unit)? = null,
    onAnswer: ((PaywallAnswer) -> Unit)? = null,
    onNavigate: ((String) -> Unit)? = null,
    screen: String? = null,
    animate: Boolean = true,
) {
    val context = LocalContext.current
    val reduceMotion = remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val selected = rememberSaveable { mutableStateOf<String?>(null) }
    val screenId = rememberSaveable { mutableStateOf<String?>(null) }
    val history = rememberSaveable { mutableStateOf(ArrayList<String>()) }
    val tabs = rememberSaveable { mutableStateOf(HashMap<String, Int>()) }
    val uri = LocalUriHandler.current

    val flow = doc.flow
    val current = flow.firstOrNull { it.id == (screen ?: screenId.value ?: doc.initialScreenId) } ?: flow.firstOrNull()
        ?: PaywallDoc.Screen(id = "paywall", name = "Paywall")

    val nav = PaywallNav(
        go = { id ->
            if (screen == null && id != null && flow.any { it.id == id }) {
                val from = current.id
                if (from != id) history.value = ArrayList(history.value + from)
                screenId.value = id
                onNavigate?.invoke(id)
            }
        },
        back = {
            val last = history.value.lastOrNull()
            if (screen == null && last != null) {
                history.value = ArrayList(history.value.dropLast(1))
                screenId.value = last
                onNavigate?.invoke(last)
            }
        },
        openUrl = { url -> PaywallURL.safe(url)?.let { runCatching { uri.openUri(it) } } },
    )

    val density = LocalDensity.current
    // Sizes are points, as on iOS and the web: the text does not grow with the system font scale.
    CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
        val animates = animate && !reduceMotion
        val backdrop = PaywallRenderer(doc, appName, packages, isPurchasing, onPurchase, onRestore, onClose, onAnswer, nav, selected, tabs, animates, current)
        BoxWithConstraints(modifier.fillMaxSize()) {
            val viewport = maxHeight
            backdrop.Backdrop()
            if (doc.isSupported) {
                AnimatedContent(
                    targetState = current.id,
                    transitionSpec = {
                        if (!animates) ContentTransform(EnterTransition.None, ExitTransition.None)
                        else (slideInHorizontally(tween(300)) { it } + fadeIn(tween(300))) togetherWith fadeOut(tween(300))
                    },
                    label = "screen",
                ) { id ->
                    val s = flow.firstOrNull { it.id == id } ?: current
                    val r = PaywallRenderer(doc, appName, packages, isPurchasing, onPurchase, onRestore, onClose, onAnswer, nav, selected, tabs, animates, s)
                    CompositionLocalProvider(LocalPaywallColor provides r.textColor) {
                        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            r.ScreenBody(viewport)
                        }
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PlainText("Update the app to see this offer.", 15.0, doc.theme.font, color = backdrop.mutedColor)
                }
            }
            if (onClose != null) {
                Box(
                    Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.statusBars).padding(12.dp).size(32.dp)
                        .background(backdrop.surfaceColor, CircleShape).plainClickable(onClick = onClose)
                        .semantics { contentDescription = "Close" },
                    contentAlignment = Alignment.Center,
                ) { PaywallIcon("xmark", 15.dp, backdrop.mutedColor) }
            }
        }
    }
}

/** Navigation the buttons drive. */
internal class PaywallNav(val go: (String?) -> Unit, val back: () -> Unit, val openUrl: (String?) -> Unit)

/** A vertical or horizontal arrangement like SwiftUI's spacers: space between, never less than [min]. */
private class SpaceBetweenAtLeast(private val min: Dp) : Arrangement.HorizontalOrVertical {
    override val spacing: Dp get() = min

    private fun Density.place(total: Int, sizes: IntArray, out: IntArray) {
        val gapMin = min.roundToPx()
        val used = sizes.sum()
        val gap = if (sizes.size > 1) max(gapMin, (total - used) / (sizes.size - 1)) else 0
        var x = 0
        sizes.forEachIndexed { i, s -> out[i] = x; x += s + gap }
    }

    override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) = place(totalSize, sizes, outPositions)
    override fun Density.arrange(totalSize: Int, sizes: IntArray, layoutDirection: LayoutDirection, outPositions: IntArray) {
        place(totalSize, sizes, outPositions)
        if (layoutDirection == LayoutDirection.Rtl) {
            for (i in outPositions.indices) outPositions[i] = totalSize - outPositions[i] - sizes[i]
        }
    }
}

private fun vertical(justify: String?, gap: Dp): Arrangement.Vertical = when (justify) {
    "center" -> Arrangement.spacedBy(gap, Alignment.CenterVertically)
    "end" -> Arrangement.spacedBy(gap, Alignment.Bottom)
    "space_between" -> SpaceBetweenAtLeast(gap)
    else -> Arrangement.spacedBy(gap)
}

private fun horizontal(justify: String?, gap: Dp): Arrangement.Horizontal = when (justify) {
    "center" -> Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
    "end" -> Arrangement.spacedBy(gap, Alignment.End)
    "space_between" -> SpaceBetweenAtLeast(gap)
    else -> Arrangement.spacedBy(gap)
}

/** Whether a block holds a pager (which cannot be asked its natural size). */
private val PaywallDoc.Block.hasPager: Boolean get() = withDescendants.any { it.type == "carousel" }

/**
 * Draws one screen of a document. Built per recomposition with the current
 * state; [cardPackage] and [inRow] are set on copies for what sits inside
 * a plan card or a row.
 */
internal class PaywallRenderer(
    private val doc: PaywallDoc,
    private val appName: String,
    private val packages: List<PaywallPackageView>,
    private val isPurchasing: Boolean,
    private val onPurchase: (PaywallPackageView) -> Unit,
    private val onRestore: () -> Unit,
    private val onClose: (() -> Unit)?,
    private val onAnswer: ((PaywallAnswer) -> Unit)?,
    private val nav: PaywallNav,
    private val selected: MutableState<String?>,
    private val tabs: MutableState<HashMap<String, Int>>,
    private val animates: Boolean,
    private val screen: PaywallDoc.Screen,
    /** While drawing inside a plan card: its package. */
    private val cardPackage: String? = null,
    /** While drawing a horizontal stack's child: natural width instead of the full width. */
    private val inRow: Boolean = false,
) {
    private fun with(cardPackage: String? = this.cardPackage, inRow: Boolean = this.inRow) = PaywallRenderer(
        doc, appName, packages, isPurchasing, onPurchase, onRestore, onClose, onAnswer, nav, selected, tabs, animates, screen, cardPackage, inRow,
    )

    private val theme = doc.themeFor(screen)
    private val radius: Float = theme.radius.coerceIn(0.0, 32.0).toFloat()
    val textColor = pwColor(theme.text) ?: Color.Black
    val mutedColor = pwColor(theme.muted) ?: Color.Gray
    val surfaceColor = pwColor(theme.surface) ?: Color.Gray.copy(alpha = 0.12f)
    private val backgroundColor = pwColor(theme.background) ?: Color.White
    private val accentColor = pwColor(theme.accent) ?: Color(0xFFF04E28)
    private val accentTextColor = pwColor(theme.accentText) ?: Color.White
    private val family = theme.font

    /** Cards on a surface-coloured box take the page colour instead, or they would vanish into it. */
    private fun cardColor(onSurface: Boolean) = if (onSurface) backgroundColor else surfaceColor

    private fun style(onSurface: Boolean) = PaywallStyle(
        textColor, mutedColor, surfaceColor, backgroundColor, accentColor, accentTextColor, cardColor(onSurface), radius, family,
    )

    private val greedy: Modifier get() = if (inRow) Modifier else Modifier.fillMaxWidth()

    private val available = packages.map { it.id }

    private val currentId: String? = PaywallPlans.initialSelection(
        selected.value, doc.allBlocks.firstOrNull { it.type == "packages" }?.highlight,
        PaywallPlans.cards(doc.flow.flatMap { it.blocks }), available,
    )

    private val current: PaywallPackageView? = packages.firstOrNull { it.id == currentId }

    /** The package text here speaks of (the card's inside a plan card). */
    private val scopePackage: PaywallPackageView? get() = PaywallPlans.scope(cardPackage, current, packages)

    private fun resolve(text: String?): String = PaywallText.resolve(text ?: "", appName, scopePackage)

    private fun positive(v: Double?): Double? = v?.takeIf { it > 0 }

    /** Whether a block is drawn: a plan card only when offered; {{savings}} text only when there are some. */
    private fun isVisible(b: PaywallDoc.Block): Boolean {
        if (!PaywallPlans.isShown(b, available)) return false
        if (b.type in listOf("title", "text", "button")) return !PaywallText.hidesForSavings(b.text, scopePackage)
        return true
    }

    private data class Align(val horizontal: Alignment.Horizontal, val text: TextAlign, val box: Alignment)

    private fun alignment(b: PaywallDoc.Block): Align = when (b.align ?: screen.align) {
        "center" -> Align(Alignment.CenterHorizontally, TextAlign.Center, Alignment.TopCenter)
        "right" -> Align(Alignment.End, TextAlign.End, Alignment.TopEnd)
        else -> Align(Alignment.Start, TextAlign.Start, Alignment.TopStart)
    }

    // ── Screen ──────────────────────────────────────────────

    /** The screen's background colour or gradient, media, overlay and effect (behind the blocks). */
    @Composable
    fun Backdrop() {
        val s = screen
        val top = pwColor(s.background ?: theme.background) ?: Color.White
        val endHex = if (s.background != null) s.backgroundEnd else theme.backgroundEnd
        val end = pwColor(endHex)
        Box(Modifier.fillMaxSize().background(if (end != null) Brush.verticalGradient(listOf(top, end)) else Brush.verticalGradient(listOf(top, top))))
        val video = PaywallURL.safe(s.backgroundVideo)
        if (video != null) {
            PaywallVideo(video, fill = true, modifier = Modifier.fillMaxSize()) {
                if (PaywallURL.safe(s.backgroundImage) != null) PaywallImage(s.backgroundImage, Modifier.fillMaxSize())
            }
        } else if (PaywallURL.safe(s.backgroundImage) != null) {
            PaywallImage(s.backgroundImage, Modifier.fillMaxSize())
        }
        pwColor(s.backgroundOverlay)?.let { Box(Modifier.fillMaxSize().background(it)) }
        val effect = s.effect
        if (!effect.isNullOrEmpty()) {
            androidx.compose.runtime.key("${s.id}/$effect") {
                PaywallEffect(effect, theme.accent, accentColor, textColor, animates)
            }
        }
    }

    /** A screen: its blocks, then any bottom sheet pinned under them, edge to edge; at least [viewport] tall. */
    @Composable
    fun ScreenBody(viewport: Dp) {
        val s = screen
        val sheets = s.blocks.filter { it.type == "sheet" }
        val insets = WindowInsets.safeDrawing.asPaddingValues()
        val spacing = (s.spacing ?: 14.0)
        val content = s.blocks.filter { it.type != "sheet" }
        Layout(
            content = {
                Stack(content, bottom = if (sheets.isEmpty()) (s.paddingY ?: 20.0).dp + insets.calculateBottomPadding() else spacing.dp, insetTop = insets.calculateTopPadding())
                val topCount = s.blocks.size - sheets.size
                sheets.forEachIndexed { i, b ->
                    Box(Modifier.fillMaxWidth().entrance(s.entrance, "${s.id}/${topCount + i}", 70L * (topCount + i), animates)) {
                        LookBox(b.look, Alignment.CenterHorizontally, places = true) {
                            CompositionLocalProvider(LocalPaywallType provides LocalPaywallType.current.merged(b.look)) {
                                SheetBlock(b, bottomInset = insets.calculateBottomPadding())
                            }
                        }
                    }
                }
            },
        ) { measurables, c ->
            val width = c.maxWidth
            val sheetPlaceables = measurables.drop(1).map { it.measure(Constraints(minWidth = width, maxWidth = width)) }
            val sheetsHeight = sheetPlaceables.sumOf { it.height }
            val minStack = max(0, viewport.roundToPx() - sheetsHeight)
            val stack = measurables[0].measure(Constraints(minWidth = width, maxWidth = width, minHeight = minStack))
            layout(width, stack.height + sheetsHeight) {
                stack.place(0, 0)
                var y = stack.height
                sheetPlaceables.forEach { it.place(0, y); y += it.height }
            }
        }
    }

    /** A screen's blocks as the screen lays them out: padding, spacing, and where they sit vertically. */
    @Composable
    private fun Stack(blocks: List<PaywallDoc.Block>, bottom: Dp, insetTop: Dp) {
        val s = screen
        val spacing = (s.spacing ?: 14.0).dp
        val py = s.paddingY ?: 28.0
        val px = (s.paddingX ?: 22.0).dp
        val top = (if (onClose == null) py else if (blocks.firstOrNull()?.type == "header") 12.0 else max(52.0, py)).dp
        Column(
            Modifier.fillMaxWidth().padding(start = px, end = px, top = top + insetTop, bottom = bottom),
            verticalArrangement = vertical(s.justify ?: "start", spacing),
        ) {
            blocks.filter(::isVisible).forEachIndexed { i, b ->
                // Hero media bleeds to the screen's edges, and to its top when first.
                val hero = b.type in listOf("image", "video", "art") && b.resolvedVariant != "inline"
                Block(
                    b, onSurface = false,
                    modifier = (if (hero) Modifier.signedPadding(start = -px, end = -px, top = if (i == 0) -top else 0.dp) else Modifier)
                        .entrance(s.entrance, "${s.id}/$i", 70L * i, animates),
                )
            }
        }
    }

    // ── Blocks ──────────────────────────────────────────────

    /**
     * A block with its look. A plan card (a stack or custom box with a
     * package) is a radio button selecting its package, drawn only when
     * the offering has it; what is inside it speaks of its package.
     */
    @Composable
    fun Block(b: PaywallDoc.Block, onSurface: Boolean, modifier: Modifier = Modifier) {
        if (!isVisible(b)) return
        val pkg = b.`package`
        if (pkg.isNullOrEmpty() || (b.type != "stack" && b.type != "custom")) {
            StyledBlock(b, onSurface, modifier)
            return
        }
        val card = with(cardPackage = pkg)
        val on = pkg == currentId
        Box(
            modifier
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton) { selected.value = pkg }
                .semantics { this.selected = on },
        ) {
            card.StyledBlock(b, onSurface, Modifier)
            Box(Modifier.align(Alignment.TopCenter).straddleTop()) { card.CardBadge(b) }
        }
    }

    /** A plan card's badge: an accent pill centred on its top border. */
    @Composable
    private fun CardBadge(b: PaywallDoc.Block) {
        val text = b.badge
        if (text.isNullOrEmpty() || PaywallText.hidesForSavings(text, scopePackage)) return
        Box(Modifier.background(accentColor, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 3.dp)) {
            PlainText(resolve(text), 11.0, family, weight = 700, color = accentTextColor, maxLines = 1)
        }
    }

    @Composable
    private fun StyledBlock(b: PaywallDoc.Block, onSurface: Boolean, modifier: Modifier) {
        val look = effectiveLook(b)
        LookBox(look, alignment(b).horizontal, places = !inRow, modifier = modifier, intrinsics = !b.hasPager) {
            CompositionLocalProvider(LocalPaywallType provides LocalPaywallType.current.merged(look)) {
                BlockBody(b, onSurface)
            }
        }
    }

    /** `look`, with `look_selected` over it while this block's plan card is selected. */
    private fun effectiveLook(b: PaywallDoc.Block): PaywallDoc.Look? {
        var look = b.look
        val top = b.lookSelected
        if (top != null && cardPackage != null && cardPackage == currentId) look = (look ?: PaywallDoc.Look()).overlaid(top)
        // A custom box keeps its own corners unless the look sets some.
        if (b.type == "custom" && look != null && look.radius == null && look.corners == null) {
            look = look.copy(radius = positive(b.radius) ?: radius.toDouble())
        }
        return look
    }

    @Composable
    private fun BlockBody(b: PaywallDoc.Block, onSurface: Boolean) {
        val a = alignment(b)
        when (b.type) {
            "icon" -> {
                val fixed = (PaywallLookMath.size(b.look?.width, b.look?.widthPx) as? PaywallLookMath.Size.Fixed)?.px
                val px = fixed ?: mapOf("s" to 40.0, "m" to 56.0, "l" to 72.0, "xl" to 96.0)[b.size ?: "l"] ?: 72.0
                Box(greedy, contentAlignment = a.box) {
                    PaywallIconView(b.icon ?: "star", b.emoji, px, b.resolvedVariant, style(onSurface),
                        PaywallColor.hueShift(theme.accent, 30.0)?.let { Color(it.toInt()) } ?: accentColor, animates)
                }
            }
            "button" -> ButtonBlock(b, onSurface)
            "spacer" -> Spacer(Modifier.height((mapOf("s" to 8, "m" to 16, "l" to 32, "xl" to 48)[b.size ?: "m"] ?: 16).dp))
            "image" -> {
                val url = PaywallURL.safe(b.url)
                if (url != null) {
                    val hero = b.resolvedVariant != "inline"
                    val h = min(positive(b.height) ?: if (hero) 260.0 else 180.0, 480.0)
                    Media(b, h) {
                        PaywallImage(url, Modifier.fillMaxSize().clip(RoundedCornerShape(if (hero) 0.dp else radius.dp))) {
                            Box(Modifier.fillMaxSize().background(cardColor(onSurface)))
                        }
                    }
                }
            }
            "title" -> PaywallLabel(
                resolve(b.text), (mapOf("s" to 20.0, "m" to 24.0, "l" to 30.0, "xl" to 36.0)[b.size ?: "l"] ?: 30.0), family,
                greedy, weight = 700, align = a.text,
            )
            "text" -> PaywallLabel(
                resolve(b.text), (mapOf("s" to 13.0, "m" to 15.0, "l" to 17.0, "xl" to 19.0)[b.size ?: "m"] ?: 15.0), family,
                greedy, color = mutedColor, align = a.text,
            )
            "features" -> PaywallFeatures(b.items ?: emptyList(), b.resolvedVariant, style(onSurface), ::resolve)
            "packages" -> PackagesBlock(b, onSurface)
            "cta" -> Cta(b)
            "testimonial" -> PaywallTestimonial(b, style(onSurface), ::resolve)
            "footer" -> Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            ) {
                if (b.restore == true) Link("Restore purchases") { onRestore() }
                PaywallURL.safe(b.termsUrl)?.let { url -> Link("Terms") { nav.openUrl(url) } }
                PaywallURL.safe(b.privacyUrl)?.let { url -> Link("Privacy") { nav.openUrl(url) } }
            }
            "header" -> HeaderBlock(b, onSurface)
            "art" -> {
                val hero = b.resolvedVariant != "inline"
                val h = min(positive(b.height) ?: if (hero) 260.0 else 220.0, 480.0)
                Media(b, h) {
                    PaywallArtView(b.art ?: "", artPalette(), animates, Modifier.fillMaxSize().clip(RoundedCornerShape(if (hero) 0.dp else radius.dp)))
                }
            }
            "video" -> {
                val hero = b.resolvedVariant != "inline"
                val h = min(positive(b.height) ?: if (hero) 260.0 else 200.0, 480.0)
                Media(b, h) {
                    val shape = RoundedCornerShape(if (hero) 0.dp else radius.dp)
                    val still: @Composable () -> Unit = {
                        if (PaywallURL.safe(b.poster) != null) {
                            PaywallImage(b.poster, Modifier.fillMaxSize()) { VideoPlaceholder(onSurface) }
                        } else {
                            VideoPlaceholder(onSurface)
                        }
                    }
                    val url = PaywallURL.safe(b.url)
                    Box(Modifier.fillMaxSize().clip(shape)) {
                        if (url != null) PaywallVideo(url, fill = hero, modifier = Modifier.fillMaxSize(), placeholder = still) else still()
                    }
                }
            }
            "stack" -> ContainerStack(b, onSurface)
            "custom" -> CustomBox(b)
            "sheet" -> SheetBlock(b, bottomInset = 0.dp)
            "tabs" -> TabsBlock(b, onSurface)
            "switch" -> SwitchBlock(b, onSurface)
            "carousel" -> {
                val variant = b.resolvedVariant
                // A peek page is a fill card, so what is on it sits on the other colour.
                val pagesOnSurface = if (variant == "peek") !onSurface else onSurface
                val pages = b.children ?: emptyList()
                PaywallCarousel(
                    count = pages.size,
                    height = (b.height?.let { if (it > 0) min(it, 480.0) else 260.0 } ?: 260.0).dp,
                    interval = b.interval ?: 0, variant = variant, style = style(onSurface),
                ) { i -> Block(pages[i], pagesOnSurface) }
            }
            "countdown" -> PaywallCountdownView(resolve(b.text), b.until, b.minutes, b.resolvedVariant, style(onSurface))
            "timeline" -> PaywallTimeline(b.items ?: emptyList(), b.resolvedVariant, style(onSurface), ::resolve)
            "social_proof" -> PaywallSocialProof(b, style(onSurface), ::resolve)
            "award" -> PaywallAward(b, style(onSurface), ::resolve)
            else -> {}
        }
    }

    @Composable
    private fun Link(label: String, onClick: () -> Unit) {
        PlainText(label, 12.0, family, Modifier.plainClickable(onClick = onClick), color = mutedColor, underline = true)
    }

    @Composable
    private fun VideoPlaceholder(onSurface: Boolean) {
        Box(Modifier.fillMaxSize().background(cardColor(onSurface)), contentAlignment = Alignment.Center) {
            PaywallIcon("play.circle", 44.dp, mutedColor)
        }
    }

    /** Media at [height] with hero_fade's bottom 45% melting into the screen's background. */
    @Composable
    private fun Media(b: PaywallDoc.Block, height: Double, content: @Composable () -> Unit) {
        Box(Modifier.fillMaxWidth().height(height.dp)) {
            content()
            if (b.resolvedVariant == "hero_fade") {
                val bg = pwColor(screen.background ?: theme.background) ?: Color.White
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height((height * 0.45).dp).background(Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg))))
            }
        }
    }

    /** The colours art tokens name: A, AT, T, B (the screen's background), S, M. */
    private fun artPalette() = mapOf(
        "A" to theme.accent, "AT" to theme.accentText, "T" to theme.text, "B" to (screen.background ?: theme.background),
        "S" to theme.surface, "M" to theme.muted,
    )

    @Composable
    private fun Cta(b: PaywallDoc.Block) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val label = resolve(b.text).ifEmpty { "Continue" }
            val enabled = !isPurchasing && current != null
            Box(
                Modifier.fillMaxWidth().background(accentColor, RoundedCornerShape(max(radius, 10f).dp))
                    .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Button) { current?.let(onPurchase) }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                PaywallLabel(label, 17.0, family, Modifier.alpha(if (isPurchasing) 0f else 1f), weight = 700, color = accentTextColor, align = TextAlign.Center)
                if (isPurchasing) PaywallSpinner(accentTextColor)
            }
            val sub = b.subtext
            if (!sub.isNullOrEmpty()) PlainText(resolve(sub), 12.0, family, Modifier.fillMaxWidth(), color = mutedColor, align = TextAlign.Center)
        }
    }

    // ── Containers ──────────────────────────────────────────

    /** A child of a container, drawn exactly as it would be on the screen. */
    @Composable
    private fun Child(b: PaywallDoc.Block, onSurface: Boolean, inRow: Boolean = false, modifier: Modifier = Modifier) {
        with(inRow = inRow).Block(b, onSurface, modifier)
    }

    /** A stack's (or custom box's) children along its axis, spaced by gap and placed by justify. */
    @Composable
    private fun ContainerStack(b: PaywallDoc.Block, onSurface: Boolean) {
        val children = (b.children ?: emptyList()).filter(::isVisible)
        val gap = (positive(b.gap) ?: 12.0).dp
        val justify = b.justify ?: "start"
        val cross = b.cross ?: ""
        when (b.axis) {
            "layers" -> {
                val across = when (cross) { "center" -> Alignment.CenterHorizontally; "end" -> Alignment.End; else -> Alignment.Start }
                val down = when (justify) { "center" -> Alignment.CenterVertically; "end" -> Alignment.Bottom; else -> Alignment.Top }
                val place = androidx.compose.ui.BiasAlignment(
                    horizontalBias = when (across) { Alignment.CenterHorizontally -> 0f; Alignment.End -> 1f; else -> -1f },
                    verticalBias = when (down) { Alignment.CenterVertically -> 0f; Alignment.Bottom -> 1f; else -> -1f },
                )
                val sized = PaywallLookMath.size(b.look?.height, b.look?.heightPx)
                val tall = sized == PaywallLookMath.Size.Fill || sized is PaywallLookMath.Size.Fixed
                Box(Modifier.fillMaxWidth().then(if (tall) Modifier.fillMaxHeight() else Modifier), contentAlignment = place) {
                    children.forEach { c ->
                        if (cross == "stretch") Child(c, onSurface, modifier = Modifier.fillMaxWidth())
                        else Child(c, onSurface, inRow = true) // at its own width, so cross can place it
                    }
                }
            }
            "horizontal" -> {
                val down = when (cross) { "start" -> Alignment.Top; "end" -> Alignment.Bottom; else -> Alignment.CenterVertically }
                val stretch = cross == "stretch" && children.none { it.hasPager }
                Row(
                    greedy.then(if (stretch) Modifier.height(IntrinsicSize.Min) else Modifier),
                    horizontalArrangement = horizontal(justify, gap), verticalAlignment = down,
                ) {
                    children.forEach { c ->
                        // As the web's flex row: a child whose look fills shares the room; the rest take their natural width.
                        val fills = c.look?.width == "fill"
                        var m: Modifier = if (fills) Modifier.weight(1f) else Modifier
                        if (stretch) m = m.fillMaxHeight()
                        Child(c, onSurface, inRow = !fills, modifier = m)
                    }
                }
            }
            else -> {
                val across = when (cross) { "center" -> Alignment.CenterHorizontally; "end" -> Alignment.End; else -> Alignment.Start }
                Column(greedy, horizontalAlignment = across, verticalArrangement = vertical(justify, gap)) {
                    children.forEach { c ->
                        if (cross == "stretch") Child(c, onSurface, modifier = Modifier.fillMaxWidth()) else Child(c, onSurface)
                    }
                }
            }
        }
    }

    /** A custom box: the container on its own fill, padding, corners and border. */
    @Composable
    private fun CustomBox(b: PaywallDoc.Block) {
        // The look is the card's own style panel: what it sets replaces the card's padding, fill and border.
        val look = b.look
        val lookFill = pwColor(look?.background) != null || !look?.backgroundImage.isNullOrEmpty()
        val lookBorder = pwColor(look?.borderColor) != null && (look?.borderWidth ?: 0.0) > 0
        val pad = (positive(b.padding) ?: 16.0).dp
        val shape = RoundedCornerShape((positive(b.radius)?.toFloat() ?: radius).dp)
        val childOnSurface = if (lookFill) false else b.background?.let { it.isEmpty() || it.equals(theme.surface, ignoreCase = true) } ?: true
        var m = greedy
        if (!lookFill) m = m.background(pwColor(b.background) ?: surfaceColor, shape)
        val border = pwColor(b.border)
        if (!lookBorder && border != null) m = m.border(1.dp, border, shape)
        m = m.padding(horizontal = if (look?.paddingX != null) 0.dp else pad, vertical = if (look?.paddingY != null) 0.dp else pad)
        Box(m) { ContainerStack(b, childOnSurface) }
    }

    /** The bottom sheet: edge to edge, top corners rounded, a grabber on top. */
    @Composable
    private fun SheetBlock(b: PaywallDoc.Block, bottomInset: Dp) {
        val r = (positive(b.radius) ?: 28.0).dp
        val onSurface = b.background?.let { it.isEmpty() || it.equals(theme.surface, ignoreCase = true) } ?: true
        val pad = (positive(b.padding) ?: 22.0).dp
        Column(
            Modifier.fillMaxWidth().background(pwColor(b.background) ?: surfaceColor, RoundedCornerShape(topStart = r, topEnd = r))
                .padding(start = pad, end = pad, top = pad, bottom = pad + bottomInset),
            verticalArrangement = Arrangement.spacedBy((positive(b.gap) ?: 12.0).dp),
        ) {
            Box(Modifier.fillMaxWidth().signedPadding(top = (-10).dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(36.dp, 5.dp).background(mutedColor.copy(alpha = 0.35f), RoundedCornerShape(50)))
            }
            (b.children ?: emptyList()).filter(::isVisible).forEach { Child(it, onSurface) }
        }
    }

    /** Custom-drawn tabs (the same on every platform), then the selected tab's stack. pills | underline | outline */
    @Composable
    private fun TabsBlock(b: PaywallDoc.Block, onSurface: Boolean) {
        val children = b.children ?: emptyList()
        val selectedTab = min(tabs.value[b.id] ?: 0, max(children.size - 1, 0))
        val fill = cardColor(onSurface)
        fun open(i: Int) {
            tabs.value = HashMap(tabs.value).apply { put(b.id, i) }
            // Tiers: opening a tab of plan cards selects its first plan unless the selection is already on it.
            children.getOrNull(i)?.let { tab -> PaywallPlans.tierSelection(tab, currentId, available)?.let { selected.value = it } }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (b.resolvedVariant) {
                "underline" -> Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth()) {
                        children.forEachIndexed { i, c ->
                            val on = i == selectedTab
                            Column(
                                Modifier.weight(1f).plainClickable { open(i) }.semantics { this.selected = on },
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                PlainText(resolve(c.text), 15.0, family, weight = 600, color = if (on) textColor else mutedColor, maxLines = 1)
                                Box(Modifier.fillMaxWidth().height(2.5.dp).background(if (on) accentColor else Color.Transparent, RoundedCornerShape(50)))
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(mutedColor.copy(alpha = 0.25f)))
                }
                "outline" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    children.forEachIndexed { i, c ->
                        val on = i == selectedTab
                        Box(
                            Modifier.weight(1f).background(if (on) accentColor.copy(alpha = 0.1f) else Color.Transparent, RoundedCornerShape(50))
                                .border(1.5.dp, if (on) accentColor else mutedColor.copy(alpha = 0.35f), RoundedCornerShape(50))
                                .plainClickable { open(i) }.semantics { this.selected = on }.padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) { PlainText(resolve(c.text), 14.0, family, weight = 600, color = if (on) accentColor else mutedColor, maxLines = 1) }
                    }
                }
                else -> Row(Modifier.fillMaxWidth().background(fill, RoundedCornerShape(50)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    children.forEachIndexed { i, c ->
                        val on = i == selectedTab
                        Box(
                            Modifier.weight(1f).background(if (on) accentColor else Color.Transparent, RoundedCornerShape(50))
                                .plainClickable { open(i) }.semantics { this.selected = on }.padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) { PlainText(resolve(c.text), 14.0, family, weight = 600, color = if (on) accentTextColor else mutedColor, maxLines = 1) }
                    }
                }
            }
            children.getOrNull(selectedTab)?.let { Child(it, onSurface) }
        }
    }

    /**
     * A title row. plain: back chevron, the title centred; large: the
     * title big under the chevron; pill: the centred title in a capsule;
     * brand: chevron, an accent icon tile, title.
     */
    @Composable
    private fun HeaderBlock(b: PaywallDoc.Block, onSurface: Boolean) {
        val title = resolve(b.text)
        val fill = cardColor(onSurface)
        when (b.resolvedVariant) {
            "large" -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (b.back == true) BackButton(fill)
                if (title.isNotEmpty()) PaywallLabel(title, 28.0, family, weight = 700, tracking = -0.56)
            }
            "brand" -> Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 32.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (b.back == true) BackButton(fill)
                Box(Modifier.size(30.dp).background(accentColor, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                    PaywallIcon(b.icon ?: "sparkles", 18.dp, accentTextColor)
                }
                PaywallLabel(title, 17.0, family, Modifier.weight(1f), weight = 700, maxLines = 1)
            }
            else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (b.back == true) BackButton(fill) else Spacer(Modifier.size(32.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (b.resolvedVariant == "pill") {
                        if (title.isNotEmpty()) {
                            Box(Modifier.background(fill, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                                PaywallLabel(title, 14.0, family, weight = 600, maxLines = 1)
                            }
                        }
                    } else {
                        PaywallLabel(title, 16.0, family, weight = 600, maxLines = 1)
                    }
                }
                Spacer(Modifier.size(32.dp))
            }
        }
    }

    @Composable
    private fun BackButton(fill: Color) {
        Box(
            Modifier.size(32.dp).background(fill, CircleShape).plainClickable { nav.back() }.semantics { contentDescription = "Back" },
            contentAlignment = Alignment.Center,
        ) { PaywallIcon("chevron.left", 16.dp, LocalPaywallColor.current) }
    }

    /** Flips between the switch's two packages; on when its "on" package is selected. */
    @Composable
    private fun SwitchBlock(b: PaywallDoc.Block, onSurface: Boolean) {
        val isOn = PaywallSwitch.isOn(currentId, b.packageOn)
        val set = { on: Boolean -> PaywallSwitch.pkg(on, b.packageOn, b.packageOff)?.let { selected.value = it } }
        val labels: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PlainText(resolve(b.text), 15.0, family, weight = 600)
                val sub = b.subtext
                if (!sub.isNullOrEmpty()) PlainText(resolve(sub), 13.0, family, color = mutedColor)
            }
        }
        val card = RoundedCornerShape(radius.dp)
        when (b.resolvedVariant) {
            "checkbox" -> Row(
                Modifier.fillMaxWidth().background(cardColor(onSurface), card)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Checkbox) { set(!isOn) }
                    .semantics { this.selected = isOn }.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                val box = RoundedCornerShape(7.dp)
                Box(
                    Modifier.size(24.dp).then(if (isOn) Modifier.background(accentColor, box) else Modifier.border(2.dp, mutedColor.copy(alpha = 0.45f), box)),
                    contentAlignment = Alignment.Center,
                ) { if (isOn) PaywallIcon("check", 15.dp, Color.White) }
                labels(Modifier.weight(1f))
            }
            "plain" -> Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    labels(Modifier.weight(1f))
                    SwitchControl(isOn, resolve(b.text)) { set(it) }
                }
                Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(1.dp).background(mutedColor.copy(alpha = 0.2f)))
            }
            else -> Row(
                Modifier.fillMaxWidth().background(cardColor(onSurface), card).padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                labels(Modifier.weight(1f))
                SwitchControl(isOn, resolve(b.text)) { set(it) }
            }
        }
    }

    /** An iOS-style switch in the accent colour. */
    @Composable
    private fun SwitchControl(on: Boolean, label: String, onChange: (Boolean) -> Unit) {
        val t by animateFloatAsState(if (on) 1f else 0f, tween(200), label = "switch")
        val track = androidx.compose.ui.graphics.lerp(mutedColor.copy(alpha = 0.3f), accentColor, t)
        Box(
            Modifier.size(51.dp, 31.dp).background(track, RoundedCornerShape(50))
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Switch) { onChange(!on) }
                .semantics { contentDescription = label; this.selected = on }
                .padding(2.dp),
        ) {
            Box(Modifier.offset(x = 20.dp * t).size(27.dp).graphicsLayer { shadowElevation = 2.dp.toPx(); shape = CircleShape; clip = false }.background(Color.White, CircleShape))
        }
    }

    @Composable
    private fun ButtonBlock(b: PaywallDoc.Block, onSurface: Boolean) {
        val style = b.style ?: "primary"
        val label = resolve(b.text).ifEmpty { "Continue" }
        val press: () -> Unit = {
            val answer = b.answer
            if (!answer.isNullOrEmpty()) onAnswer?.invoke(PaywallAnswer(screen.id, screen.answerQuestion, answer))
            when (b.action) {
                "next" -> {
                    val all = doc.flow
                    val i = all.indexOfFirst { it.id == screen.id }
                    if (i >= 0 && i + 1 < all.size) nav.go(all[i + 1].id)
                }
                "back" -> nav.back()
                "screen" -> nav.go(b.target)
                "close" -> onClose?.invoke()
                "restore" -> onRestore()
                "url" -> nav.openUrl(b.url)
                else -> {}
            }
        }
        if (b.resolvedVariant == "option") {
            // A full-width choice row; the style does not apply.
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            val scale by animateFloatAsState(if (pressed) 0.98f else 1f, tween(150), label = "press")
            val shape = RoundedCornerShape(radius.dp)
            Row(
                Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }.background(cardColor(onSurface), shape)
                    .clickable(source, indication = null, role = Role.Button) { press() }.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                val emoji = b.emoji
                val icon = b.icon
                if (!emoji.isNullOrEmpty()) {
                    androidx.compose.foundation.text.BasicText(emoji, style = androidx.compose.ui.text.TextStyle(fontSize = androidx.compose.ui.unit.TextUnit(24f, androidx.compose.ui.unit.TextUnitType.Sp)))
                } else if (!icon.isNullOrEmpty()) {
                    Box(Modifier.size(36.dp).background(accentColor.copy(alpha = 0.12f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        PaywallIcon(icon, 20.dp, accentColor)
                    }
                }
                PaywallLabel(label, 16.0, family, Modifier.weight(1f), weight = 600, color = textColor)
                PaywallIcon("chevron.right", 18.dp, mutedColor)
            }
        } else if (style == "link") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PlainText(label, 14.0, family, Modifier.plainClickable { press() }.padding(6.dp), color = mutedColor, underline = true)
            }
        } else {
            // The variant is the shape; the style the colours.
            val shape = when (b.resolvedVariant) {
                "pill" -> RoundedCornerShape(50)
                "square" -> RoundedCornerShape(4.dp)
                else -> RoundedCornerShape(max(radius, 10f).dp)
            }
            val fill = when (style) { "primary" -> accentColor; "outline" -> Color.Transparent; else -> cardColor(onSurface) }
            var m = greedy.background(fill, shape)
            if (style == "outline") m = m.border(1.5.dp, accentColor, shape)
            Box(
                m.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { press() }
                    // Room either side when a look sizes it to fit.
                    .padding(horizontal = 20.dp, vertical = 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                PaywallLabel(
                    label, 16.0, family, weight = 700, align = TextAlign.Center,
                    color = when (style) { "primary" -> accentTextColor; "outline" -> accentColor; else -> textColor },
                )
            }
        }
    }

    @Composable
    private fun PackagesBlock(b: PaywallDoc.Block, onSurface: Boolean) {
        if (packages.isEmpty()) {
            PlainText("No plans are available right now.", 13.0, family, color = mutedColor)
            return
        }
        if (b.layout == "cards") {
            // Up to three a row, wrapping, every card the same width.
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                packages.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        for (i in 0 until 3) {
                            val p = row.getOrNull(i)
                            if (p != null) PackageOption(p, b.badges?.get(p.id), card = true, onSurface, Modifier.weight(1f))
                            else Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                packages.forEach { PackageOption(it, b.badges?.get(it.id), card = false, onSurface, Modifier.fillMaxWidth()) }
            }
        }
    }

    @Composable
    private fun PackageOption(p: PaywallPackageView, badge: String?, card: Boolean, onSurface: Boolean, modifier: Modifier) {
        val on = p.id == currentId
        val price = if (p.period.isEmpty()) p.price else "${p.price} / ${p.period}"
        val shape = RoundedCornerShape(radius.dp)
        Box(
            modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton) { selected.value = p.id }
                .semantics { this.selected = on },
        ) {
            val box = Modifier.fillMaxWidth().background(cardColor(onSurface), shape).border(2.dp, if (on) accentColor else Color.Transparent, shape)
            if (card) {
                Column(
                    box.padding(start = 10.dp, end = 10.dp, top = 18.dp, bottom = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PlainText(p.productName, 15.0, family, weight = 600, align = TextAlign.Center)
                    PlainText(price, 14.0, family, color = mutedColor, align = TextAlign.Center)
                }
            } else {
                Row(box.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlainText(p.productName, 15.0, family, Modifier.weight(1f), weight = 600)
                    PlainText(price, 14.0, family, color = mutedColor)
                }
            }
            if (!badge.isNullOrEmpty()) {
                Box(
                    Modifier.align(if (card) Alignment.TopCenter else Alignment.TopEnd)
                        .offset(x = if (card) 0.dp else (-12).dp, y = (-9).dp)
                        .background(accentColor, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
                ) { PlainText(badge, 11.0, family, weight = 700, color = accentTextColor, maxLines = 1) }
            }
        }
    }
}
