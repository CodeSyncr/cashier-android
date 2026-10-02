package com.nimbus.cashier

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A paywall designed in the Cashier console: the same document the web
 * editor previews, the web SDK renders and the iOS SDK draws in SwiftUI.
 * [PaywallView] draws it natively with Compose.
 *
 * Decoding is lenient: unknown fields are ignored, an unreadable `look` or
 * screen theme is dropped (never the document), and a field of the wrong
 * type reads as absent. Only a document without a numeric `version` or a
 * `theme` object is refused ([parse] returns null).
 */
data class PaywallDoc(
    val version: Int,
    val theme: Theme,
    /** The screen the flow opens on. */
    val initial: String? = null,
    /** The flow. A version-1 document has [blocks] instead; [flow] reads either. */
    val screens: List<Screen>? = null,
    val blocks: List<Block>? = null,
    /** The language the block texts are written in; null or "" means "en". */
    val defaultLocale: String? = null,
    /** Extra locales that have translations ("es", "pt-BR", …). */
    val locales: List<String> = emptyList(),
    /** locale → key → text (see [localize]). */
    val strings: Map<String, Map<String, String>> = emptyMap(),
) {
    companion object {
        /** The schema version this SDK understands; a newer document shows a fallback. */
        const val SUPPORTED_VERSION = 2

        /** Reads a document; null when it is not one. */
        @JvmStatic
        fun parse(json: String): PaywallDoc? = try {
            fromJson(JSONObject(json))
        } catch (_: Exception) {
            null
        }

        /** Reads a document; null when it is not one. */
        @JvmStatic
        fun fromJson(o: JSONObject): PaywallDoc? {
            val version = o.opt("version") as? Number ?: return null
            if (version.toDouble() != floor(version.toDouble())) return null
            val themeJson = o.opt("theme") as? JSONObject ?: return null
            val theme = Theme.lenient(themeJson)
            val strings = mutableMapOf<String, Map<String, String>>()
            (o.opt("strings") as? JSONObject)?.let { all ->
                all.keys().forEach { loc ->
                    val tr = all.opt(loc) as? JSONObject ?: return@forEach
                    val m = mutableMapOf<String, String>()
                    tr.keys().forEach { k -> (tr.opt(k) as? String)?.let { m[k] = it } }
                    strings[loc] = m
                }
            }
            val doc = PaywallDoc(
                version = version.toInt(),
                theme = theme,
                initial = o.str("initial"),
                screens = (o.opt("screens") as? JSONArray)?.objects()?.map { Screen.fromJson(it) },
                blocks = (o.opt("blocks") as? JSONArray)?.objects()?.map { Block.fromJson(it) },
                defaultLocale = o.str("default_locale"),
                locales = (o.opt("locales") as? JSONArray).strs(),
                strings = strings,
            )
            return doc.withIds()
        }
    }

    /** Every screen of the flow, a version-1 document read as one. */
    val flow: List<Screen>
        get() = screens?.takeIf { it.isNotEmpty() } ?: listOf(Screen(id = "paywall", name = "Paywall", blocks = blocks ?: emptyList()))

    /** The id of the screen the flow opens on. */
    val initialScreenId: String
        get() {
            val all = flow
            if (initial != null && all.any { it.id == initial }) return initial
            return all.firstOrNull()?.id ?: "paywall"
        }

    /** Every block of every screen, containers' children included. */
    val allBlocks: List<Block> get() = flow.flatMap { it.allBlocks }

    /** Whether this SDK can draw the document. */
    val isSupported: Boolean get() = version <= SUPPORTED_VERSION

    /**
     * The theme a screen is drawn with: its own where it has one, field by
     * field (an unreadable colour or font falls back to the document's),
     * else the document's.
     */
    fun themeFor(screen: Screen): Theme {
        val own = screen.theme ?: return theme
        fun colour(mine: String, theirs: String) = if (PaywallColor.parse(mine) != null) mine else theirs
        val end = own.backgroundEnd?.takeIf { it.isNotEmpty() }?.let { if (PaywallColor.parse(it) != null) it else theme.backgroundEnd }
        return Theme(
            background = colour(own.background, theme.background), backgroundEnd = end,
            surface = colour(own.surface, theme.surface), text = colour(own.text, theme.text),
            muted = colour(own.muted, theme.muted), accent = colour(own.accent, theme.accent),
            accentText = colour(own.accentText, theme.accentText),
            radius = if (own.radius.isFinite() && own.radius >= 0) own.radius else theme.radius,
            font = if (own.font in PaywallLookMath.FONTS) own.font else theme.font,
        )
    }

    /**
     * This document with the texts of the best translation for
     * [requestedLocale] swapped in (a copy; see [PaywallLocalization]).
     */
    fun localize(requestedLocale: String): PaywallDoc = PaywallLocalization.localize(this, requestedLocale)

    /**
     * Gives every screen and block an id, as the server's Normalize does
     * (it always has; this only guards hand-written documents). Block ids
     * are unique across the flow.
     */
    private fun withIds(): PaywallDoc {
        val takenBlocks = mutableSetOf<String>()
        val allIds = (screens.orEmpty().flatMap { it.allBlocks } + blocks.orEmpty().flatMap { it.withDescendants }).map { it.id }.toSet()
        fun fresh(prefix: String, taken: (String) -> Boolean): String {
            var n = 1
            while (taken("$prefix-$n")) n++
            return "$prefix-$n"
        }
        fun fix(list: List<Block>): List<Block> = list.map { b ->
            var id = b.id
            if (id.isEmpty() || id in takenBlocks) {
                val typ = b.type.replace('_', '-').takeIf { Regex("^[a-z0-9_-]{1,32}$").matches(it) } ?: "block"
                id = fresh(typ) { it in takenBlocks || it in allIds }
            }
            takenBlocks += id
            b.copy(id = id, children = b.children?.let { fix(it) })
        }
        val screenSeen = mutableSetOf<String>()
        val fixedScreens = screens?.mapIndexed { i, s ->
            var id = s.id
            if (id.isEmpty() || id in screenSeen) id = fresh("screen") { c -> c in screenSeen || screens.any { it.id == c } }
            screenSeen += id
            s.copy(id = id, name = s.name.ifBlank { "Screen ${i + 1}" }, blocks = fix(s.blocks))
        }
        return copy(screens = fixedScreens, blocks = blocks?.let { fix(it) })
    }

    /** One step of the flow. */
    data class Screen(
        val id: String,
        val name: String,
        /** The question this screen's answer buttons answer; falls back to [name]. */
        val question: String? = null,
        val background: String? = null,
        val backgroundEnd: String? = null,
        val paddingX: Double? = null,
        val paddingY: Double? = null,
        val spacing: Double? = null,
        /** start | center | end | space_between */
        val justify: String? = null,
        /** left | center */
        val align: String? = null,
        val blocks: List<Block> = emptyList(),
        /** glow | aurora | orbs | grid | rays */
        val effect: String? = null,
        /** rise | fade | zoom */
        val entrance: String? = null,
        val backgroundImage: String? = null,
        val backgroundVideo: String? = null,
        val backgroundOverlay: String? = null,
        /** This screen's own theme; null when it has none or it could not be read. */
        val theme: Theme? = null,
    ) {
        /** Every block on the screen, containers' children included (depth first). */
        val allBlocks: List<Block> get() = blocks.flatMap { it.withDescendants }

        /** What answers on this screen are grouped under: the question, else the name. */
        val answerQuestion: String get() = question?.takeIf { it.isNotEmpty() } ?: name

        companion object {
            internal fun fromJson(o: JSONObject) = Screen(
                id = o.str("id") ?: "",
                name = o.str("name") ?: "",
                question = o.str("question"),
                background = o.str("background"),
                backgroundEnd = o.str("background_end"),
                paddingX = o.num("padding_x"),
                paddingY = o.num("padding_y"),
                spacing = o.num("spacing"),
                justify = o.str("justify"),
                align = o.str("align"),
                blocks = (o.opt("blocks") as? JSONArray)?.objects()?.map { Block.fromJson(it) } ?: emptyList(),
                effect = o.str("effect"),
                entrance = o.str("entrance"),
                backgroundImage = o.str("background_image"),
                backgroundVideo = o.str("background_video"),
                backgroundOverlay = o.str("background_overlay"),
                theme = (o.opt("theme") as? JSONObject)?.let { Theme.strict(it) },
            )
        }
    }

    data class Theme(
        val background: String,
        val backgroundEnd: String? = null,
        val surface: String,
        val text: String,
        val muted: String,
        val accent: String,
        val accentText: String,
        val radius: Double,
        /** system | rounded | serif | mono */
        val font: String,
    ) {
        companion object {
            /** The server's default theme (paywall.DefaultTheme). */
            val DEFAULT = Theme("#FFFFFF", null, "#F4F4F6", "#111114", "#6B6B76", "#F04E28", "#FFFFFF", 16.0, "system")

            /** A document's theme: missing fields take the server's defaults. */
            internal fun lenient(o: JSONObject) = Theme(
                background = o.str("background") ?: DEFAULT.background,
                backgroundEnd = o.str("background_end"),
                surface = o.str("surface") ?: DEFAULT.surface,
                text = o.str("text") ?: DEFAULT.text,
                muted = o.str("muted") ?: DEFAULT.muted,
                accent = o.str("accent") ?: DEFAULT.accent,
                accentText = o.str("accent_text") ?: DEFAULT.accentText,
                radius = o.num("radius") ?: DEFAULT.radius,
                font = o.str("font") ?: DEFAULT.font,
            )

            /** A screen's theme: every field present and of the right type, else null. */
            internal fun strict(o: JSONObject): Theme? = try {
                fun s(k: String) = o.opt(k) as? String ?: throw IllegalArgumentException(k)
                Theme(
                    background = s("background"),
                    backgroundEnd = o.optStrict<String>("background_end"),
                    surface = s("surface"), text = s("text"), muted = s("muted"), accent = s("accent"),
                    accentText = s("accent_text"),
                    radius = (o.opt("radius") as? Number ?: throw IllegalArgumentException("radius")).toDouble(),
                    font = s("font"),
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    data class Item(val icon: String, val title: String, val text: String? = null) {
        companion object {
            internal fun fromJson(o: JSONObject) = Item(o.str("icon") ?: "", o.str("title") ?: "", o.str("text"))
        }
    }

    data class Block(
        val id: String,
        val type: String,
        val text: String? = null,
        val subtext: String? = null,
        val align: String? = null,
        val size: String? = null,
        val url: String? = null,
        val height: Double? = null,
        val items: List<Item>? = null,
        val layout: String? = null,
        val highlight: String? = null,
        val badges: Map<String, String>? = null,
        val author: String? = null,
        val rating: Int? = null,
        val restore: Boolean? = null,
        val termsUrl: String? = null,
        val privacyUrl: String? = null,
        val icon: String? = null,
        /** Button: next | back | screen | close | restore | url */
        val action: String? = null,
        /** Button with action "screen": the screen id. */
        val target: String? = null,
        /** Button: primary | secondary | outline | link */
        val style: String? = null,
        /** Button: recorded as the screen's answer when tapped, before its action. */
        val answer: String? = null,
        /** Containers (stack, custom, sheet, tabs, carousel): the blocks inside. */
        val children: List<Block>? = null,
        /** stack, custom: vertical | horizontal | layers */
        val axis: String? = null,
        val justify: String? = null,
        val gap: Double? = null,
        val padding: Double? = null,
        val background: String? = null,
        val border: String? = null,
        val radius: Double? = null,
        val interval: Int? = null,
        val poster: String? = null,
        val back: Boolean? = null,
        val packageOn: String? = null,
        val packageOff: String? = null,
        val until: String? = null,
        val minutes: Int? = null,
        val images: List<String>? = null,
        val variant: String? = null,
        val emoji: String? = null,
        /** stack, custom: start | center | end | stretch */
        val cross: String? = null,
        /** Fine-tuning of this block's box and type; null when absent or unreadable. */
        val look: Look? = null,
        /** stack, custom: makes the box a plan card for this package id. */
        val `package`: String? = null,
        /** Plan card: a pill on its top edge. */
        val badge: String? = null,
        /** art: which built-in illustration. */
        val art: String? = null,
        /** Laid over [look] while this block's plan card is selected. */
        val lookSelected: Look? = null,
    ) {
        /** The variant to draw: this block's if this SDK knows it, else the type's default. */
        val resolvedVariant: String get() = PaywallVariants.resolve(type, variant)

        /** This block and every block inside it (depth first). */
        val withDescendants: List<Block> get() = listOf(this) + (children ?: emptyList()).flatMap { it.withDescendants }

        companion object {
            internal fun fromJson(o: JSONObject): Block = Block(
                id = o.str("id") ?: "",
                type = (o.str("type") ?: "").trim().lowercase(Locale.ROOT),
                text = o.str("text"), subtext = o.str("subtext"), align = o.str("align"), size = o.str("size"),
                url = o.str("url"), height = o.num("height"),
                items = (o.opt("items") as? JSONArray)?.objects()?.map { Item.fromJson(it) },
                layout = o.str("layout"), highlight = o.str("highlight"),
                badges = (o.opt("badges") as? JSONObject)?.let { b ->
                    val m = linkedMapOf<String, String>()
                    b.keys().forEach { k -> (b.opt(k) as? String)?.let { m[k] = it } }
                    m
                },
                author = o.str("author"), rating = o.num("rating")?.toInt(), restore = o.bool("restore"),
                termsUrl = o.str("terms_url"), privacyUrl = o.str("privacy_url"), icon = o.str("icon"),
                action = o.str("action"), target = o.str("target"), style = o.str("style"), answer = o.str("answer"),
                children = (o.opt("children") as? JSONArray)?.objects()?.map { fromJson(it) },
                axis = o.str("axis"), justify = o.str("justify"), gap = o.num("gap"), padding = o.num("padding"),
                background = o.str("background"), border = o.str("border"), radius = o.num("radius"),
                interval = o.num("interval")?.toInt(), poster = o.str("poster"), back = o.bool("back"),
                packageOn = o.str("package_on"), packageOff = o.str("package_off"),
                until = o.str("until"), minutes = o.num("minutes")?.toInt(),
                images = (o.opt("images") as? JSONArray)?.strs(),
                variant = o.str("variant"), emoji = o.str("emoji"), cross = o.str("cross"),
                look = (o.opt("look") as? JSONObject)?.let { Look.strict(it) },
                `package` = o.str("package"), badge = o.str("badge"), art = o.str("art"),
                lookSelected = (o.opt("look_selected") as? JSONObject)?.let { Look.strict(it) },
            )
        }
    }

    /** A block's style panel: its box, then its type. null, 0 and "" mean "as the design has it". */
    data class Look(
        /** fit | fill | fixed */
        val width: String? = null,
        val widthPx: Double? = null,
        val height: String? = null,
        val heightPx: Double? = null,
        val paddingX: Double? = null,
        val paddingY: Double? = null,
        val marginX: Double? = null,
        val marginY: Double? = null,
        val background: String? = null,
        val backgroundEnd: String? = null,
        val backgroundImage: String? = null,
        val radius: Double? = null,
        /** top-left, top-right, bottom-right, bottom-left */
        val corners: List<Double>? = null,
        val borderColor: String? = null,
        val borderWidth: Double? = null,
        val shadowColor: String? = null,
        val shadowX: Double? = null,
        val shadowY: Double? = null,
        val shadowBlur: Double? = null,
        /** 1–100; 0 or null is fully opaque. */
        val opacity: Double? = null,
        val font: String? = null,
        val weight: String? = null,
        val fontSize: Double? = null,
        val color: String? = null,
        val italic: Boolean? = null,
    ) {
        /** This look with every field [top] sets laid over it. */
        fun overlaid(top: Look?): Look {
            if (top == null) return this
            return Look(
                width = top.width ?: width, widthPx = top.widthPx ?: widthPx,
                height = top.height ?: height, heightPx = top.heightPx ?: heightPx,
                paddingX = top.paddingX ?: paddingX, paddingY = top.paddingY ?: paddingY,
                marginX = top.marginX ?: marginX, marginY = top.marginY ?: marginY,
                background = top.background ?: background, backgroundEnd = top.backgroundEnd ?: backgroundEnd,
                backgroundImage = top.backgroundImage ?: backgroundImage,
                radius = top.radius ?: radius, corners = top.corners ?: corners,
                borderColor = top.borderColor ?: borderColor, borderWidth = top.borderWidth ?: borderWidth,
                shadowColor = top.shadowColor ?: shadowColor, shadowX = top.shadowX ?: shadowX,
                shadowY = top.shadowY ?: shadowY, shadowBlur = top.shadowBlur ?: shadowBlur,
                opacity = top.opacity ?: opacity, font = top.font ?: font, weight = top.weight ?: weight,
                fontSize = top.fontSize ?: fontSize, color = top.color ?: color, italic = top.italic ?: italic,
            )
        }

        companion object {
            /** Every present field of the right type, else null (never the document). */
            internal fun strict(o: JSONObject): Look? = try {
                Look(
                    width = o.optStrict("width"), widthPx = o.optNum("width_px"),
                    height = o.optStrict("height"), heightPx = o.optNum("height_px"),
                    paddingX = o.optNum("padding_x"), paddingY = o.optNum("padding_y"),
                    marginX = o.optNum("margin_x"), marginY = o.optNum("margin_y"),
                    background = o.optStrict("background"), backgroundEnd = o.optStrict("background_end"),
                    backgroundImage = o.optStrict("background_image"), radius = o.optNum("radius"),
                    corners = if (o.isNull("corners")) null else (o.opt("corners") as? JSONArray ?: throw IllegalArgumentException("corners"))
                        .let { a -> (0 until a.length()).map { (a.get(it) as? Number ?: throw IllegalArgumentException("corner")).toDouble() } },
                    borderColor = o.optStrict("border_color"), borderWidth = o.optNum("border_width"),
                    shadowColor = o.optStrict("shadow_color"), shadowX = o.optNum("shadow_x"),
                    shadowY = o.optNum("shadow_y"), shadowBlur = o.optNum("shadow_blur"), opacity = o.optNum("opacity"),
                    font = o.optStrict("font"), weight = o.optStrict("weight"), fontSize = o.optNum("font_size"),
                    color = o.optStrict("color"), italic = o.optStrict("italic"),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

// ── JSON helpers (lenient: a wrong type reads as absent) ─────────────────

private fun JSONObject.str(key: String): String? = opt(key) as? String
private fun JSONObject.num(key: String): Double? = (opt(key) as? Number)?.toDouble()
private fun JSONObject.bool(key: String): Boolean? = opt(key) as? Boolean
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { opt(it) as? JSONObject }
private fun JSONArray?.strs(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it) as? String }

/** Strict: absent or null is null, the wrong type throws. */
private inline fun <reified T> JSONObject.optStrict(key: String): T? {
    if (isNull(key)) return null
    return opt(key) as? T ?: throw IllegalArgumentException(key)
}

private fun JSONObject.optNum(key: String): Double? {
    if (isNull(key)) return null
    return (opt(key) as? Number ?: throw IllegalArgumentException(key)).toDouble()
}

/** One package as the paywall shows it, prices already localized. */
data class PaywallPackageView @JvmOverloads constructor(
    val id: String,
    val productName: String,
    /** "$4.99" */
    val price: String,
    /** "month", "year", … or "" for a one-time purchase. */
    val period: String,
    /** "$3.33", or "" when not meaningful. */
    val pricePerMonth: String,
    /** "7-day", or "" when there is no trial. */
    val trial: String,
    /** "17%" saved per month against the shortest plan of a month or more; "" when not meaningful. */
    val savings: String = "",
)

/** An answer the user gave on a question screen (a button with an `answer`). */
data class PaywallAnswer(
    val screenId: String,
    /** The screen's question, else its name. */
    val question: String,
    val answer: String,
)

/** {{variables}} in paywall text. */
internal object PaywallText {
    private val variable = Regex("""\{\{\s*([a-z_]+)\s*\}\}""")
    private val savingsVariable = Regex("""\{\{\s*savings\s*\}\}""")

    /** Text that speaks of {{savings}} is not shown at all when its package saves nothing. */
    fun hidesForSavings(text: String?, pkg: PaywallPackageView?): Boolean {
        if (text.isNullOrEmpty()) return false
        return savingsVariable.containsMatchIn(text) && (pkg?.savings ?: "").isEmpty()
    }

    /** Fills {{variables}}; unknown names become "". Same rules as the web and iOS SDKs. */
    fun resolve(text: String, appName: String, pkg: PaywallPackageView?): String {
        val values = mapOf(
            "app_name" to appName,
            "product_name" to (pkg?.productName ?: ""),
            "price" to (pkg?.price ?: ""),
            "period" to (pkg?.period ?: ""),
            "price_per_month" to (pkg?.pricePerMonth ?: ""),
            "trial" to (pkg?.trial ?: ""),
            "savings" to (pkg?.savings ?: ""),
        )
        var out = variable.replace(text) { m -> values[m.groupValues[1]] ?: "" }
        while (out.contains("  ")) out = out.replace("  ", " ")
        for (p in listOf(",", ".", ":", ";", "!", "?")) out = out.replace(" $p", p)
        return out.trim(' ', '\t')
    }
}

/** A countdown's arithmetic. */
internal object PaywallCountdown {
    fun parse(s: String): Date? {
        // RFC 3339, any number of fractional digits, Z or an offset.
        val m = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(\.\d+)?(Z|[+-]\d{2}:\d{2})$""", RegexOption.IGNORE_CASE).find(s) ?: return null
        val (base, frac, zone) = m.destructured
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val d = fmt.parse(base + if (zone.equals("z", true)) "Z" else zone) ?: return null
            val millis = if (frac.isEmpty()) 0L else ("0$frac".toDouble() * 1000).roundToInt().toLong()
            Date(d.time + millis)
        } catch (_: Exception) {
            null
        }
    }

    /** When the countdown ends: `until` if set (and readable), else `minutes` after [startMillis]. */
    fun target(until: String?, minutes: Int?, startMillis: Long): Long? {
        if (!until.isNullOrEmpty()) return parse(until)?.time
        if (minutes != null && minutes > 0) return startMillis + minutes * 60_000L
        return null
    }

    /** Hours, minutes and seconds left, never below zero. */
    fun remaining(target: Long?, nowMillis: Long): Triple<Int, Int, Int> {
        if (target == null) return Triple(0, 0, 0)
        val left = max(0L, Math.floorDiv(target - nowMillis, 1000L)).toInt()
        return Triple(left / 3600, left % 3600 / 60, left % 60)
    }
}

/** A switch block toggles between two packages. */
internal object PaywallSwitch {
    fun isOn(selected: String?, packageOn: String?): Boolean = selected != null && selected == packageOn
    fun pkg(on: Boolean, packageOn: String?, packageOff: String?): String? = if (on) packageOn else packageOff
}

/** Each block type's designs, the first being the default (the server's `paywall.Variants`). */
internal object PaywallVariants {
    val table: Map<String, List<String>> = mapOf(
        "header" to listOf("plain", "large", "pill", "brand"),
        "tabs" to listOf("pills", "underline", "outline"),
        "switch" to listOf("card", "plain", "checkbox"),
        "button" to listOf("rounded", "pill", "square", "option"),
        "icon" to listOf("tile", "glow", "rings", "gradient", "plain"),
        "image" to listOf("inline", "hero", "hero_fade"),
        "video" to listOf("inline", "hero", "hero_fade"),
        "art" to listOf("inline", "hero", "hero_fade"),
        "carousel" to listOf("dots", "bars", "peek"),
        "countdown" to listOf("boxes", "labeled", "inline", "banner"),
        "timeline" to listOf("line", "cards", "horizontal"),
        "social_proof" to listOf("avatars", "rating", "badge"),
        "testimonial" to listOf("card", "quote", "bubble"),
        "features" to listOf("list", "checks", "grid", "cards"),
        "award" to listOf("laurel", "badge", "ribbon"),
    )

    fun resolve(type: String, variant: String?): String {
        val known = table[type] ?: emptyList()
        if (variant != null && variant in known) return variant
        return known.firstOrNull() ?: ""
    }
}

/** Plan cards: which card to select, and what each saves. */
internal object PaywallPlans {
    private fun isCard(b: PaywallDoc.Block) = !b.`package`.isNullOrEmpty() && (b.type == "stack" || b.type == "custom")

    /** Every plan card's package id in document order. */
    fun cards(blocks: List<PaywallDoc.Block>): List<String> =
        blocks.flatMap { it.withDescendants }.filter { isCard(it) }.map { it.`package`!! }

    /** Theirs if still offered; else the highlight; else the first plan card on offer; else the first package. */
    fun initialSelection(selected: String?, highlight: String?, cards: List<String>, available: List<String>): String? {
        if (selected != null && selected in available) return selected
        if (highlight != null && highlight in available) return highlight
        return cards.firstOrNull { it in available } ?: available.firstOrNull()
    }

    /** Whether a block is drawn: a plan card only when its package is offered. */
    fun isShown(b: PaywallDoc.Block, available: List<String>): Boolean = !isCard(b) || b.`package` in available

    /** The package text speaks of: inside a plan card its package, elsewhere the selected one. */
    fun scope(card: String?, selected: PaywallPackageView?, packages: List<PaywallPackageView>): PaywallPackageView? =
        card?.let { id -> packages.firstOrNull { it.id == id } } ?: selected

    /** When a tab opens: its first plan card on offer, unless the selection is already one of them; null leaves it. */
    fun tierSelection(tab: PaywallDoc.Block, selected: String?, available: List<String>): String? {
        val ids = cards(listOf(tab)).filter { it in available }
        val first = ids.firstOrNull() ?: return null
        if (selected != null && selected in ids) return null
        return first
    }

    /** One plan for [savings]: its price and length in months (0 one-time, weeks 0.25). */
    data class Plan(val id: String, val price: Double, val months: Double)

    /**
     * "{pct}%" per package id: per month, against the shortest plan of at
     * least a month; "" for that plan, one-time purchases, and savings under 1%.
     */
    fun savings(plans: List<Plan>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        plans.forEach { if (it.id !in out) out[it.id] = "" }
        val ref = plans.filter { it.months >= 1 }.minByOrNull { it.months } ?: return out
        if (ref.price <= 0) return out
        val refPerMonth = ref.price / ref.months
        for (p in plans) {
            if (p.months <= 0 || p.id == ref.id) continue
            val pct = Math.round((1 - (p.price / p.months) / refPerMonth) * 100).toInt()
            if (pct >= 1) out[p.id] = "$pct%"
        }
        return out
    }
}

/**
 * Which URLs a paywall may load or open: https anywhere, plain http only
 * from this machine. Same rule as the server and the other SDKs.
 */
object PaywallURL {
    @JvmStatic
    fun safe(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val uri = try { URI(raw) } catch (_: Exception) { return null }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return when (scheme) {
            "https" -> raw
            "http" -> if (host == "localhost" || host == "127.0.0.1") raw else null
            else -> null
        }
    }
}

/** Hex colours: "#RGB", "#RRGGBB" or "#RRGGBBAA" → ARGB. */
internal object PaywallColor {
    private val hex = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

    /** ARGB, or null for anything unreadable. */
    fun parse(s: String?): Long? {
        if (s == null || !hex.matches(s)) return null
        var h = s.substring(1)
        if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
        if (h.length == 6) h += "FF"
        val v = h.toLong(16)
        val rgb = v shr 8
        val a = v and 0xFF
        return (a shl 24) or rgb
    }

    /** RGB (0…1) → hue (0…1), saturation, brightness. */
    fun hsb(r: Double, g: Double, b: Double): Triple<Double, Double, Double> {
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val d = mx - mn
        var h = 0.0
        if (d > 0) {
            h = when (mx) {
                r -> ((g - b) / d) % 6
                g -> (b - r) / d + 2
                else -> (r - g) / d + 4
            }
            h /= 6
            if (h < 0) h += 1
        }
        return Triple(h, if (mx == 0.0) 0.0 else d / mx, mx)
    }

    /** A colour with its hue turned by [degrees] (HSB); null for anything unreadable. */
    fun hueShift(s: String?, degrees: Double): Long? {
        val argb = parse(s) ?: return null
        val a = (argb shr 24) and 0xFF
        val r = ((argb shr 16) and 0xFF) / 255.0
        val g = ((argb shr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        val (h0, sat, v) = hsb(r, g, b)
        var h = (h0 + degrees / 360) % 1
        if (h < 0) h += 1
        val (nr, ng, nb) = hsbToRgb(h, sat, v)
        return (a shl 24) or ((nr * 255).roundToInt().toLong() shl 16) or ((ng * 255).roundToInt().toLong() shl 8) or (nb * 255).roundToInt().toLong()
    }

    fun hsbToRgb(h: Double, s: Double, v: Double): Triple<Double, Double, Double> {
        if (s == 0.0) return Triple(v, v, v)
        val hh = (h * 6) % 6
        val i = floor(hh).toInt()
        val f = hh - i
        val p = v * (1 - s)
        val q = v * (1 - s * f)
        val t = v * (1 - s * (1 - f))
        return when (i) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
    }
}

/** The look's arithmetic, kept pure so it can be tested. */
internal object PaywallLookMath {
    val FONTS = listOf("system", "rounded", "serif", "mono")
    val WEIGHTS = mapOf("regular" to 400, "medium" to 500, "semibold" to 600, "bold" to 700, "heavy" to 800)

    /** The four corner radii: `corners` when it has four, else `radius` all round (clamped 0–200). */
    fun corners(look: PaywallDoc.Look): List<Double> {
        val c = look.corners
        if (c != null && c.size == 4) return c.map { it.coerceIn(0.0, 200.0) }
        return List(4) { (look.radius ?: 0.0).coerceIn(0.0, 200.0) }
    }

    sealed class Size {
        object Fit : Size()
        object Fill : Size()
        data class Fixed(val px: Double) : Size()
    }

    fun size(mode: String?, px: Double?): Size? = when (mode) {
        "fit" -> Size.Fit
        "fill" -> Size.Fill
        "fixed" -> if (px != null && px > 0) Size.Fixed(minOf(px, 1000.0)) else null
        else -> null
    }

    /** The type part of a look, as it flows down to a block's text. */
    data class Type(
        val font: String? = null,
        val weight: String? = null,
        val size: Double? = null,
        val color: String? = null,
        val italic: Boolean? = null,
    ) {
        /** This type with [look]'s type fields laid over it (unreadable ones ignored). */
        fun merged(look: PaywallDoc.Look?): Type {
            if (look == null) return this
            var t = this
            look.font?.let { if (it in FONTS) t = t.copy(font = it) }
            look.weight?.let { if (it in WEIGHTS) t = t.copy(weight = it) }
            look.fontSize?.let { if (it > 0) t = t.copy(size = it) }
            look.color?.let { if (PaywallColor.parse(it) != null) t = t.copy(color = it) }
            if (look.italic == true) t = t.copy(italic = true)
            return t
        }
    }

    data class Font(val size: Double, val weight: Int, val family: String, val italic: Boolean)

    /** A text's font: the block's design defaults with the look's type over them. */
    fun font(size: Double, weight: Int, family: String, type: Type): Font = Font(
        size = type.size?.coerceIn(8.0, 96.0) ?: size,
        weight = type.weight?.let { WEIGHTS[it] } ?: weight,
        family = type.font?.takeIf { it in FONTS } ?: family,
        italic = type.italic == true,
    )
}
