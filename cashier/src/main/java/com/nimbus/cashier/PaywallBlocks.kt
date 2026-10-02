package com.nimbus.cashier

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale

/** `count` accent stars. */
@Composable
internal fun PaywallStars(count: Int, size: Double, color: androidx.compose.ui.graphics.Color) {
    Row(horizontalArrangement = Arrangement.spacedBy((size * 0.15).dp)) {
        repeat(count.coerceIn(0, 5)) { PaywallIcon("star", (size * 1.1).dp, color, filled = true) }
    }
}

/** Overlapping avatar circles: up to five images, or four accent-tinted placeholders. */
@Composable
internal fun PaywallAvatars(images: List<String>, size: Double, ring: androidx.compose.ui.graphics.Color, style: PaywallStyle) {
    val urls = images.take(5).mapNotNull { PaywallURL.safe(it) }
    Row(horizontalArrangement = Arrangement.spacedBy((-size * 0.28).dp)) {
        if (urls.isEmpty()) {
            listOf(1f, 0.8f, 0.6f, 0.4f).forEach { a ->
                Box(
                    Modifier.size(size.dp).clip(CircleShape).background(style.accent.copy(alpha = a)).border(2.dp, ring, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { PaywallIcon("person", (size * 0.45).dp, style.accentText) }
            }
        } else {
            urls.forEach { url ->
                PaywallImage(url, Modifier.size(size.dp).clip(CircleShape).border(2.dp, ring, CircleShape)) {
                    Box(Modifier.fillMaxSize().background(style.surface))
                }
            }
        }
    }
}

// ── features: list | checks | grid | cards ─────────────────

@Composable
internal fun PaywallFeatures(items: List<PaywallDoc.Item>, variant: String, style: PaywallStyle, resolve: (String?) -> String) {
    @Composable
    fun detail(item: PaywallDoc.Item) {
        val t = item.text
        if (!t.isNullOrEmpty()) PlainText(resolve(t), 13.0, style.family, color = style.muted)
    }

    @Composable
    fun row(item: PaywallDoc.Item, tile: androidx.compose.ui.graphics.Color) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(34.dp).background(tile, style.card(minOf(style.radius, 12f))), contentAlignment = Alignment.Center) {
                PaywallIcon(item.icon, 18.dp, style.accent)
            }
            Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PlainText(resolve(item.title), 15.0, style.family, weight = 600)
                detail(item)
            }
        }
    }

    @Composable
    fun tile(item: PaywallDoc.Item, modifier: Modifier) {
        Column(modifier.background(style.fill, style.card()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PaywallIcon(item.icon, 24.dp, style.accent)
            PlainText(resolve(item.title), 14.0, style.family, weight = 600)
            if (!item.text.isNullOrEmpty()) PlainText(resolve(item.text), 12.0, style.family, color = style.muted)
        }
    }

    when (variant) {
        "checks" -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PaywallIcon("check", 20.dp, style.accent)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        PlainText(resolve(item.title), 15.0, style.family)
                        detail(item)
                    }
                }
            }
        }
        "grid" -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    tile(pair[0], Modifier.weight(1f).fillMaxHeight())
                    if (pair.size > 1) tile(pair[1], Modifier.weight(1f).fillMaxHeight()) else Spacer(Modifier.weight(1f))
                }
            }
        }
        "cards" -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { item ->
                Box(Modifier.fillMaxWidth().background(style.fill, style.card()).padding(horizontal = 14.dp, vertical = 12.dp)) {
                    row(item, style.accent.copy(alpha = 0.12f))
                }
            }
        }
        else -> Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.forEach { row(it, style.fill) }
        }
    }
}

// ── testimonial: card | quote | bubble ──────────────────────

@Composable
internal fun PaywallTestimonial(b: PaywallDoc.Block, style: PaywallStyle, resolve: (String?) -> String) {
    val author = resolve(b.author)
    val rating = b.rating ?: 0
    when (b.resolvedVariant) {
        "quote" -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (rating > 0) PaywallStars(rating, 14.0, style.accent)
            Box(Modifier.height(34.dp).padding(top = 4.dp)) {
                PlainText("“", 48.0, "serif", weight = 700, color = style.accent)
            }
            PlainText(resolve(b.text), 18.0, style.family, align = TextAlign.Center)
            if (author.isNotEmpty()) PlainText("— $author", 13.0, style.family, Modifier.padding(top = 4.dp), color = style.muted)
        }
        "bubble" -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val r = style.radius.dp
            Column(
                Modifier.fillMaxWidth().background(style.fill, RoundedCornerShape(r, r, r, 4.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (rating > 0) PaywallStars(rating, 13.0, style.accent)
                PlainText(resolve(b.text), 15.0, style.family)
            }
            if (author.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).background(style.accent, CircleShape), contentAlignment = Alignment.Center) {
                        PlainText(author.take(1).uppercase(Locale.getDefault()), 13.0, style.family, weight = 700, color = style.accentText)
                    }
                    PlainText(author, 13.0, style.family, color = style.muted)
                }
            }
        }
        else -> Column(
            Modifier.fillMaxWidth().background(style.fill, style.card()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (rating > 0) PlainText("★".repeat(minOf(rating, 5)), 15.0, style.family, color = style.accent)
            PlainText(resolve(b.text), 15.0, style.family)
            if (author.isNotEmpty()) PlainText("— $author", 13.0, style.family, color = style.muted)
        }
    }
}

// ── timeline: line | cards | horizontal ─────────────────────

@Composable
internal fun PaywallTimeline(items: List<PaywallDoc.Item>, variant: String, style: PaywallStyle, resolve: (String?) -> String) {
    @Composable
    fun icon(item: PaywallDoc.Item, size: Double, background: androidx.compose.ui.graphics.Color) {
        Box(Modifier.size(size.dp).background(background, CircleShape), contentAlignment = Alignment.Center) {
            PaywallIcon(item.icon, (size * 0.5).dp, style.accent)
        }
    }

    @Composable
    fun text(item: PaywallDoc.Item, titleSize: Double, textSize: Double, center: Boolean, modifier: Modifier = Modifier) {
        Column(modifier, horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val align = if (center) TextAlign.Center else TextAlign.Start
            PlainText(resolve(item.title), titleSize, style.family, weight = 600, align = align)
            if (!item.text.isNullOrEmpty()) PlainText(resolve(item.text), textSize, style.family, color = style.muted, align = align)
        }
    }

    val rail = style.muted.copy(alpha = 0.3f)
    when (variant) {
        "cards" -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().background(style.fill, style.card()).padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    icon(item, 32.0, style.accent.copy(alpha = 0.15f))
                    text(item, 15.0, 13.0, false)
                }
            }
        }
        "horizontal" -> Row(Modifier.fillMaxWidth()) {
            items.forEachIndexed { i, item ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        // The rail runs through the circle centres, not beyond the first or last.
                        Row(Modifier.fillMaxWidth()) {
                            Box(Modifier.weight(1f).height(2.dp).background(if (i == 0) androidx.compose.ui.graphics.Color.Transparent else rail))
                            Box(Modifier.weight(1f).height(2.dp).background(if (i == items.size - 1) androidx.compose.ui.graphics.Color.Transparent else rail))
                        }
                        icon(item, 32.0, style.fill)
                    }
                    text(item, 13.0, 11.0, true, Modifier.padding(horizontal = 4.dp))
                }
            }
        }
        else -> Column(Modifier.fillMaxWidth()) {
            items.forEachIndexed { i, item ->
                val last = i == items.size - 1
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        icon(item, 28.0, style.accent.copy(alpha = 0.15f))
                        if (!last) Box(Modifier.width(2.dp).weight(1f).background(rail))
                    }
                    text(item, 15.0, 13.0, false, Modifier.weight(1f).padding(top = 4.dp, bottom = if (last) 0.dp else 18.dp))
                }
            }
        }
    }
}

// ── social_proof: avatars | rating | badge ──────────────────

@Composable
internal fun PaywallSocialProof(b: PaywallDoc.Block, style: PaywallStyle, resolve: (String?) -> String) {
    val left = b.align == "left"
    val right = b.align == "right"
    val textAlign = if (left) TextAlign.Start else if (right) TextAlign.End else TextAlign.Center
    val across = if (left) Alignment.Start else if (right) Alignment.End else Alignment.CenterHorizontally
    val subtext = resolve(b.subtext)
    val rating = b.rating ?: 0
    Column(Modifier.fillMaxWidth(), horizontalAlignment = across) {
        when (b.resolvedVariant) {
            "rating" -> Column(horizontalAlignment = across, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (rating > 0) PaywallStars(rating, 22.0, style.accent)
                PlainText(resolve(b.text), 17.0, style.family, weight = 700, align = textAlign)
                if (subtext.isNotEmpty()) PlainText(subtext, 13.0, style.family, color = style.muted, align = textAlign)
            }
            "badge" -> Row(
                Modifier.background(style.fill, RoundedCornerShape(50)).padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                PaywallAvatars(b.images ?: emptyList(), 24.0, style.fill, style)
                PlainText(resolve(b.text), 13.0, style.family, weight = 600, maxLines = 2)
            }
            else -> Column(horizontalAlignment = across, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.padding(bottom = 2.dp)) { PaywallAvatars(b.images ?: emptyList(), 36.0, style.background, style) }
                if (rating > 0) PaywallStars(rating, 13.0, style.accent)
                PlainText(resolve(b.text), 15.0, style.family, weight = 600, align = textAlign)
                if (subtext.isNotEmpty()) PlainText(subtext, 13.0, style.family, color = style.muted, align = textAlign)
            }
        }
    }
}

// ── award: laurel | badge | ribbon ──────────────────────────

@Composable
internal fun PaywallAward(b: PaywallDoc.Block, style: PaywallStyle, resolve: (String?) -> String) {
    val subtext = resolve(b.subtext)
    val across = when (b.align) { "left" -> Alignment.Start; "right" -> Alignment.End; else -> Alignment.CenterHorizontally }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = across) {
        when (b.resolvedVariant) {
            "badge" -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(56.dp).background(style.accent.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                    PaywallIcon("trophy", 30.dp, style.accent)
                }
                PlainText(resolve(b.text), 17.0, style.family, weight = 700, align = TextAlign.Center)
                if (subtext.isNotEmpty()) PlainText(subtext, 12.0, style.family, color = style.muted, align = TextAlign.Center)
            }
            "ribbon" -> Row(
                Modifier.background(style.accent, RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                PaywallIcon("star", 17.dp, style.accentText, filled = true)
                PlainText(resolve(b.text), 14.0, style.family, weight = 700, color = style.accentText, maxLines = 1)
                if (subtext.isNotEmpty()) PlainText("· $subtext", 13.0, style.family, color = style.accentText.copy(alpha = 0.8f), maxLines = 1)
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PaywallLaurel(false, style.text.copy(alpha = 0.7f))
                Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PlainText(resolve(b.text), 17.0, style.family, weight = 600, align = TextAlign.Center)
                    if (subtext.isNotEmpty()) PlainText(subtext, 12.0, style.family, color = style.muted, align = TextAlign.Center)
                }
                PaywallLaurel(true, style.text.copy(alpha = 0.7f))
            }
        }
    }
}

// ── countdown: boxes | labeled | inline | banner ────────────

/** HH : MM : SS to a moment, ticking every second. */
@Composable
internal fun PaywallCountdownView(label: String, until: String?, minutes: Int?, variant: String, style: PaywallStyle) {
    // When the countdown was first shown: "minutes" count from here.
    val start = rememberSaveable { System.currentTimeMillis() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000 - System.currentTimeMillis() % 1000)
            now = System.currentTimeMillis()
        }
    }
    val (h, m, s) = PaywallCountdown.remaining(PaywallCountdown.target(until, minutes, start), now)
    val time = String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    when (variant) {
        "inline" -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                Modifier.background(style.fill, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                PaywallIcon("clock", 17.dp, style.accent)
                if (label.isNotEmpty()) PlainText(label, 13.0, style.family, color = style.muted)
                PlainText(time, 15.0, style.family, weight = 700, color = style.text, tabular = true)
            }
        }
        "banner" -> Row(
            Modifier.fillMaxWidth().background(style.accent, style.card()).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            PlainText(label, 13.0, style.family, Modifier.weight(1f), weight = 600, color = style.accentText)
            PlainText(time, 20.0, style.family, weight = 700, color = style.accentText, tabular = true)
        }
        else -> {
            val units = variant == "labeled"
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (label.isNotEmpty()) {
                    PlainText(label.uppercase(Locale.getDefault()), 12.0, style.family, weight = 600, color = style.muted, tracking = 0.6)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    @Composable
                    fun digits(n: Int, unit: String?) {
                        Column(
                            Modifier.defaultMinSize(minWidth = 52.dp).background(style.fill, style.card(minOf(style.radius, 12f)))
                                .padding(vertical = if (unit == null) 10.dp else 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            PlainText(String.format(Locale.US, "%02d", n), 24.0, style.family, weight = 700, color = style.text, tabular = true)
                            if (unit != null) PlainText(unit, 10.0, style.family, color = style.muted)
                        }
                    }
                    @Composable
                    fun colon() = PlainText(":", 22.0, style.family, weight = 700, color = style.muted)
                    digits(h, if (units) "hours" else null)
                    colon()
                    digits(m, if (units) "min" else null)
                    colon()
                    digits(s, if (units) "sec" else null)
                }
            }
        }
    }
}
