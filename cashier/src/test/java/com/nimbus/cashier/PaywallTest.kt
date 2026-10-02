package com.nimbus.cashier

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Fixtures/paywalls.json is exported from the Cashier server's own templates
 * (plus a showcase using every block type, multi-step flows and a
 * translated flow) and shared with the iOS and web SDKs, so these tests
 * prove that what the web editor saves, this SDK can read — and that it
 * derives the same values iOS's PaywallTests check.
 */
class PaywallTest {
    companion object {
        val packages = listOf(
            PaywallPackageView("\$monthly", "Monthly", "\$4.99", "month", "", ""),
            PaywallPackageView("\$annual", "Annual", "\$39.99", "year", "\$3.33", "7-day"),
        )

        val raw: JSONObject by lazy {
            JSONObject(PaywallTest::class.java.getResourceAsStream("/paywalls.json")!!.bufferedReader().use { it.readText() })
        }

        val fixtures: Map<String, PaywallDoc> by lazy {
            raw.keys().asSequence().associateWith { PaywallDoc.fromJson(raw.getJSONObject(it))!! }
        }

        /** Every block type the server can save (doc.go's BlockTypes). */
        val blockTypes = setOf(
            "title", "text", "image", "video", "art", "icon", "stack", "footer", "header", "custom", "spacer",
            "packages", "cta", "sheet", "tabs", "switch", "button", "carousel",
            "countdown", "timeline", "social_proof", "testimonial", "features", "award",
        )

        /** The server's `paywall.Variants`, restated: a change there must be a change here. */
        val variants = mapOf(
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
        val effects = setOf("glow", "aurora", "orbs", "grid", "rays")
        val entrances = setOf("rise", "fade", "zoom")
        val arts = listOf("stars", "mountains", "waves", "blobs", "planet", "sunburst", "gift", "synthwave", "hills", "confetti")

        const val THEME = """"theme":{"background":"#FFFFFF","surface":"#EEEEEE","text":"#000000","muted":"#666666","accent":"#FF0000","accent_text":"#FFFFFF","radius":8,"font":"system"}"""

        fun doc(json: String) = PaywallDoc.parse(json)!!
        fun block(json: String) = PaywallDoc.Block.fromJson(JSONObject(json))
    }

    @Test fun everyServerDocumentDecodesAndIsSupported() {
        assertEquals("the gallery templates, plans, showcase, variants, three flows and the translated flow", 51, fixtures.size)
        for ((name, doc) in fixtures) {
            assertTrue(name, doc.isSupported)
            val all = doc.allBlocks
            assertTrue(name, all.count { it.type == "packages" } == 1 || all.any { !it.`package`.isNullOrEmpty() })
            assertEquals(name, 1, all.count { it.type == "cta" })
            assertTrue(name, doc.flow.any { it.id == doc.initialScreenId })
            for (screen in doc.flow) {
                assertTrue("$name: effect ${screen.effect}", (screen.effect ?: "glow") in effects)
                assertTrue("$name: entrance ${screen.entrance}", (screen.entrance ?: "rise") in entrances)
            }
            assertEquals("$name: block ids are unique", all.size, all.map { it.id }.toSet().size)
            for (b in all) {
                assertTrue("$name: no renderer for ${b.type}", b.type in blockTypes)
                for (icon in (b.items ?: emptyList()).map { it.icon } + listOfNotNull(b.icon)) {
                    assertNotNull("$name: no glyph for icon $icon", PaywallIcons.paths[icon])
                }
                if (b.type == "button") {
                    assertTrue("$name: button action", b.action in listOf("next", "back", "screen", "close", "restore", "url"))
                    assertTrue("$name: button style", (b.style ?: "primary") in listOf("primary", "secondary", "outline", "link"))
                }
                if (!b.variant.isNullOrEmpty()) assertEquals("$name: no ${b.type} variant ${b.variant}", b.variant, b.resolvedVariant)
                for (hex in listOfNotNull(b.background, b.border).filter { it.isNotEmpty() }) {
                    assertNotNull("$name: colour $hex", PaywallColor.parse(hex))
                }
                if (b.type == "tabs") {
                    assertTrue("$name: tabs are labelled stacks", (b.children ?: emptyList()).all { it.type == "stack" && !it.text.isNullOrEmpty() })
                }
                if (b.type == "art") assertTrue("$name: art ${b.art}", b.art in arts)
            }
            val t = doc.theme
            for (hex in listOf(t.background, t.surface, t.text, t.muted, t.accent, t.accentText)) assertNotNull("$name: $hex", PaywallColor.parse(hex))
        }
        val showcase = fixtures.getValue("showcase")
        assertEquals("the showcase covers every block type", blockTypes, showcase.allBlocks.map { it.type }.toSet())
        assertEquals(2, showcase.flow.size)
        assertTrue(showcase.flow.flatMap { it.blocks }.any { !it.children.isNullOrEmpty() })
        assertEquals(10, fixtures.getValue("steps_1").flow.size)
        assertEquals(10, fixtures.getValue("steps_2").flow.size)
        assertEquals(2, fixtures.getValue("steps_3").flow.size)
    }

    @Test fun variantsDocumentShowsEveryVariantEffectAndEntrance() {
        assertEquals(variants, PaywallVariants.table)
        val doc = fixtures.getValue("variants")
        assertEquals(10, doc.flow.size)
        assertEquals(effects, doc.flow.mapNotNull { it.effect }.toSet())
        assertEquals(entrances, doc.flow.mapNotNull { it.entrance }.toSet())
        assertTrue(doc.allBlocks.any { it.type == "button" && it.resolvedVariant == "option" && !it.emoji.isNullOrEmpty() })
        val shown = doc.allBlocks.map { "${it.type}/${it.resolvedVariant}" }.toSet()
        for ((type, list) in variants) for (v in list) assertTrue("lacks $type $v", "$type/$v" in shown)
    }

    @Test fun unknownVariantDrawsTheDefault() {
        val b = block("""{"id":"t","type":"timeline","variant":"sparkly","items":[{"icon":"lock","title":"Today"},{"icon":"star","title":"Day 7"}]}""")
        assertEquals("sparkly", b.variant)
        assertEquals("line", b.resolvedVariant)
        assertEquals("rounded", PaywallVariants.resolve("button", null))
        assertEquals("laurel", PaywallVariants.resolve("award", ""))
        assertEquals("types without variants have none", "", PaywallVariants.resolve("title", "large"))
    }

    @Test fun emojiIconDecodes() {
        val d = doc("""{"version":2,$THEME,"screens":[{"id":"s","name":"S","effect":"rays","entrance":"zoom","blocks":[{"id":"i","type":"icon","emoji":"🚀","variant":"gradient"},{"id":"o","type":"button","variant":"option","icon":"bolt","text":"Faster","action":"next"},{"id":"p","type":"packages"},{"id":"c","type":"cta","text":"Buy"}]}]}""")
        val icon = d.allBlocks.first()
        assertNull(icon.icon)
        assertEquals("🚀", icon.emoji)
        assertEquals("rays", d.flow[0].effect)
        assertEquals("zoom", d.flow[0].entrance)
        assertEquals("bolt", d.allBlocks[1].icon)
    }

    @Test fun hueShift() {
        assertEquals("green sits a third of the way round", 1.0 / 3, PaywallColor.hsb(0.0, 1.0, 0.0).first, 0.0001)
        assertEquals(0xFF00FF00L, PaywallColor.hueShift("#FF0000", 120.0))
        assertNull(PaywallColor.hueShift("red", 30.0))
    }

    @Test fun screensDrawWithTheirOwnTheme() {
        val variantsDoc = fixtures.getValue("variants")
        val own = variantsDoc.flow.first { it.id == "variants-1" }
        val plain = variantsDoc.flow.first { it.id == "variants-2" }
        assertEquals("#0B0B1F", variantsDoc.themeFor(own).background)
        assertEquals("#8B7CFF", variantsDoc.themeFor(own).accent)
        assertNull(plain.theme)
        assertEquals(variantsDoc.theme, variantsDoc.themeFor(plain))
        for (name in listOf("steps_1", "steps_2", "steps_3")) {
            val d = fixtures.getValue(name)
            for (s in d.flow) if (s.theme != null) assertEquals("$name/${s.id}", s.theme, d.themeFor(s))
        }
        assertTrue(fixtures.getValue("steps_1").flow.mapNotNull { it.theme?.accent }.toSet().size > 1)
    }

    @Test fun aBadScreenThemeFallsBack() {
        val d = doc("""{"version":2,$THEME,"screens":[{"id":"a","name":"A","theme":{"background":"#000000","surface":"nope","text":"#FFFFFF","muted":"#999999","accent":"#00FF00","accent_text":"#000000","radius":20,"font":"comic"},"blocks":[]},{"id":"b","name":"B","theme":{"background":42},"blocks":[]}]}""")
        val a = d.themeFor(d.flow[0])
        assertEquals("#000000", a.background)
        assertEquals("#00FF00", a.accent)
        assertEquals(20.0, a.radius, 0.0)
        assertEquals("an unreadable colour falls back to the document's", "#EEEEEE", a.surface)
        assertEquals("an unknown font falls back to the document's", "system", a.font)
        assertNull("an unreadable screen theme is dropped, not the document", d.flow[1].theme)
        assertEquals(d.theme, d.themeFor(d.flow[1]))
    }

    @Test fun looksDecode() {
        val looks = fixtures.getValue("variants").flow.first { it.id == "looks" }
        val blocks = looks.allBlocks
        val title = blocks.first { it.id == "title-10" }
        assertEquals("right", title.align)
        assertEquals("serif", title.look?.font)
        assertEquals("heavy", title.look?.weight)
        assertEquals(34.0, title.look?.fontSize)
        assertEquals(true, title.look?.italic)
        val card = blocks.first { it.id == "custom-2" }.look!!
        assertEquals("fixed", card.width)
        assertEquals(300.0, card.widthPx)
        assertEquals(listOf(0.0, 0.0, 12.0, 12.0), card.corners)
        assertEquals(listOf(0.0, 0.0, 12.0, 12.0), PaywallLookMath.corners(card))
        assertEquals("#EEF4FF", card.backgroundEnd)
        assertEquals(18.0, card.shadowBlur)
        assertEquals("center", blocks.first { it.id == "stack-18" }.cross)
        assertEquals("layers", blocks.first { it.id == "stack-19" }.axis)
        assertEquals(30.0, blocks.first { it.id == "icon-11" }.look?.opacity)

        // An unreadable look is ignored, never the document.
        val bad = block("""{"id":"t","type":"title","text":"Hi","look":{"width":7,"corners":"round"}}""")
        assertNull(bad.look)
        assertEquals("Hi", bad.text)
    }

    @Test fun lookArithmetic() {
        assertEquals(List(4) { 20.0 }, PaywallLookMath.corners(PaywallDoc.Look(radius = 20.0)))
        assertEquals("corners need all four", List(4) { 20.0 }, PaywallLookMath.corners(PaywallDoc.Look(radius = 20.0, corners = listOf(1.0, 2.0, 3.0))))
        assertEquals("clamped to 200", listOf(0.0, 200.0, 4.0, 5.0), PaywallLookMath.corners(PaywallDoc.Look(corners = listOf(0.0, 300.0, 4.0, 5.0))))
        assertEquals(PaywallLookMath.Size.Fit, PaywallLookMath.size("fit", null))
        assertEquals(PaywallLookMath.Size.Fill, PaywallLookMath.size("fill", 50.0))
        assertEquals(PaywallLookMath.Size.Fixed(300.0), PaywallLookMath.size("fixed", 300.0))
        assertNull("a fixed size needs its pixels", PaywallLookMath.size("fixed", 0.0))
        assertNull(PaywallLookMath.size(null, 300.0))

        val none = PaywallLookMath.font(30.0, 700, "system", PaywallLookMath.Type())
        assertEquals(30.0, none.size, 0.0)
        assertEquals(700, none.weight)
        assertFalse(none.italic)
        val titleLook = PaywallDoc.Look(font = "serif", weight = "heavy", fontSize = 34.0, italic = true)
        val styled = PaywallLookMath.font(30.0, 700, "system", PaywallLookMath.Type().merged(titleLook))
        assertEquals(34.0, styled.size, 0.0)
        assertEquals(800, styled.weight)
        assertEquals("serif", styled.family)
        assertTrue(styled.italic)
        val inherited = PaywallLookMath.Type().merged(titleLook).merged(PaywallDoc.Look(weight = "not a weight"))
        assertEquals("an unknown weight keeps the inherited one", "heavy", inherited.weight)
        assertEquals("serif", inherited.font)
    }

    @Test fun savings() {
        val s = PaywallPlans.savings(listOf(
            PaywallPlans.Plan("\$monthly", 4.99, 1.0), PaywallPlans.Plan("\$annual", 39.99, 12.0), PaywallPlans.Plan("\$weekly", 0.99, 0.25),
            PaywallPlans.Plan("\$lifetime", 99.99, 0.0), PaywallPlans.Plan("\$pricey_year", 79.99, 12.0),
        ))
        assertEquals("the reference saves nothing", "", s["\$monthly"])
        assertEquals("33%", s["\$annual"])
        assertEquals("21%", s["\$weekly"])
        assertEquals("one-time purchases have no savings", "", s["\$lifetime"])
        assertEquals("costing more is not a saving", "", s["\$pricey_year"])
        assertEquals(mapOf("\$y" to "17%", "\$q" to ""), PaywallPlans.savings(listOf(PaywallPlans.Plan("\$y", 60.0, 12.0), PaywallPlans.Plan("\$q", 18.0, 3.0))))
        assertEquals("no reference, no savings", mapOf("\$life" to ""), PaywallPlans.savings(listOf(PaywallPlans.Plan("\$life", 50.0, 0.0))))
        assertEquals("Save 33% with a year", PaywallText.resolve("Save {{savings}} with a year", "Acme",
            PaywallPackageView("a", "A", "\$39.99", "year", "\$3.33", "", "33%")))
        assertEquals("Save!", PaywallText.resolve("Save {{savings}}!", "Acme", packages[0]))
    }

    @Test fun samplePlansSave() {
        // The web editor's sample plans, priced as Play would be.
        val plans = listOf(
            PaywallPricing.Priced("\$weekly", "Weekly", "\$3.99", 3_990_000, "USD", PaywallPricing.Period(1, 'W'), null),
            PaywallPricing.Priced("\$monthly", "Monthly", "\$9.99", 9_990_000, "USD", PaywallPricing.Period(1, 'M'), null),
            PaywallPricing.Priced("\$six_month", "6 months", "\$39.99", 39_990_000, "USD", PaywallPricing.Period(6, 'M'), null),
            PaywallPricing.Priced("\$annual", "Yearly", "\$69.99", 69_990_000, "USD", PaywallPricing.Period(1, 'Y'), PaywallPricing.Period(7, 'D')),
            PaywallPricing.Priced("\$lifetime", "Lifetime", "\$119.99", 119_990_000, "USD", null, null),
            PaywallPricing.Priced("\$premium_monthly", "Premium monthly", "\$19.99", 19_990_000, "USD", PaywallPricing.Period(1, 'M'), null),
            PaywallPricing.Priced("\$premium_annual", "Premium yearly", "\$149.99", 149_990_000, "USD", PaywallPricing.Period(1, 'Y'), null),
        )
        val views = PaywallPricing.views(plans, Locale.US)
        assertEquals(mapOf(
            "\$weekly" to "", "\$monthly" to "", "\$six_month" to "33%", "\$annual" to "42%", "\$lifetime" to "",
            "\$premium_monthly" to "", "\$premium_annual" to "",
        ), views.associate { it.id to it.savings })
        val annual = views.first { it.id == "\$annual" }
        assertEquals("year", annual.period)
        assertEquals("\$5.83", annual.pricePerMonth)
        assertEquals("7-day", annual.trial)
        assertEquals("6 months", views.first { it.id == "\$six_month" }.period)
        assertEquals("", views.first { it.id == "\$monthly" }.pricePerMonth)
        assertEquals("", views.first { it.id == "\$lifetime" }.period)
        assertEquals("week", views.first { it.id == "\$weekly" }.period)
    }

    @Test fun billingPeriods() {
        assertEquals(PaywallPricing.Period(1, 'M'), PaywallPricing.parsePeriod("P1M"))
        assertEquals(PaywallPricing.Period(1, 'Y'), PaywallPricing.parsePeriod("P1Y"))
        assertEquals(PaywallPricing.Period(3, 'D'), PaywallPricing.parsePeriod("P3D"))
        assertEquals(PaywallPricing.Period(18, 'M'), PaywallPricing.parsePeriod("P1Y6M"))
        assertNull(PaywallPricing.parsePeriod("monthly"))
        assertEquals("1-week", PaywallPricing.trialWords(PaywallPricing.Period(1, 'W')))
        assertEquals(0.25, PaywallPricing.months(PaywallPricing.Period(1, 'W')), 0.0)
        assertEquals(0.0, PaywallPricing.months(PaywallPricing.Period(3, 'D')), 0.0)
    }

    @Test fun planCards() {
        val d = fixtures.getValue("plans")
        val top = d.flow.flatMap { it.blocks }
        assertEquals(listOf("\$monthly", "\$annual", "\$premium_monthly", "\$premium_annual"), PaywallPlans.cards(top))
        val ids = packages.map { it.id }
        assertEquals("\$annual", PaywallPlans.initialSelection("\$annual", null, listOf("\$monthly"), ids))
        assertEquals("\$annual", PaywallPlans.initialSelection("\$gone", "\$annual", listOf("\$monthly"), ids))
        assertEquals("\$annual", PaywallPlans.initialSelection(null, null, listOf("\$premium_monthly", "\$annual"), ids))
        assertEquals("\$monthly", PaywallPlans.initialSelection(null, null, emptyList(), ids))

        val cards = d.allBlocks.filter { it.`package` != null }
        assertEquals(listOf("\$monthly", "\$annual"), cards.filter { PaywallPlans.isShown(it, ids) }.map { it.`package` })
        assertTrue("other blocks always show", PaywallPlans.isShown(top[0], emptyList()))

        val annual = packages[1]
        assertEquals("\$monthly", PaywallPlans.scope("\$monthly", annual, packages)?.id)
        assertEquals("\$annual", PaywallPlans.scope(null, annual, packages)?.id)
        assertEquals("\$annual", PaywallPlans.scope("\$gone", annual, packages)?.id)

        val tabs = d.allBlocks.first { it.type == "tabs" }.children!!
        val all = ids + listOf("\$premium_monthly", "\$premium_annual")
        assertEquals("\$premium_monthly", PaywallPlans.tierSelection(tabs[1], "\$annual", all))
        assertNull(PaywallPlans.tierSelection(tabs[1], "\$premium_annual", all))
        assertNull("no plan of that tier on offer", PaywallPlans.tierSelection(tabs[1], "\$annual", ids))
        assertEquals("\$monthly", PaywallPlans.tierSelection(tabs[0], "\$premium_annual", all))
    }

    @Test fun lookSelectedOverLook() {
        val card = fixtures.getValue("plans").allBlocks.first { it.`package` == "\$monthly" }
        val merged = (card.look ?: PaywallDoc.Look()).overlaid(card.lookSelected)
        assertEquals("look's fields stay", "fill", merged.width)
        assertEquals("#FFF1EC", merged.background)
        assertEquals(2.0, merged.borderWidth)
        val base = PaywallDoc.Look(color = "#111111", weight = "regular")
        val over = base.overlaid(PaywallDoc.Look(weight = "bold"))
        assertEquals("look_selected wins", "bold", over.weight)
        assertEquals("#111111", over.color)
        assertEquals(base, base.overlaid(null))
        assertEquals("#F04E28", card.children!!.first { it.type == "text" }.lookSelected?.color)
    }

    @Test fun screenMediaDecodes() {
        val s = fixtures.getValue("plans").flow[0]
        assertEquals("https://example.com/bg.jpg", s.backgroundImage)
        assertEquals("#00000066", s.backgroundOverlay)
        assertNull(s.backgroundVideo)
        assertEquals("hero_fade", s.blocks.first().resolvedVariant)
        val video = PaywallDoc.Screen.fromJson(JSONObject("""{"id":"s","name":"S","background_video":"https://example.com/v.mp4","blocks":[]}"""))
        assertEquals("https://example.com/v.mp4", video.backgroundVideo)
    }

    @Test fun artColoursMatchTheWeb() {
        val palette = mapOf("A" to "#F04E28", "AT" to "#FFFFFF", "T" to "#111114", "B" to "#FFFFFF", "S" to "#F4F4F6", "M" to "#6B6B76")
        val expected = mapOf(
            "A" to "rgba(240, 78, 40, 1)",
            "A+40*0.8@0.5" to "rgba(209, 181, 15, 0.5)",
            "A-20*0.35" to "rgba(92, 6, 19, 1)",
            "#FFFFFF@0.61" to "rgba(255, 255, 255, 0.61)",
            "S*0.9" to "rgba(217, 217, 224, 1)",
            "M@0.3" to "rgba(107, 107, 118, 0.3)",
            "A+180" to "rgba(40, 202, 240, 1)",
            "T*2" to "rgba(34, 34, 40, 1)",
            "B-10*0.5@0.25" to "rgba(128, 128, 128, 0.25)",
            "AT@0.8" to "rgba(255, 255, 255, 0.8)",
            "#FFD76A+15*1.2" to "rgba(255, 254, 178, 1)",
        )
        for ((token, css) in expected) assertEquals(token, css, PaywallArt.color(token, palette)?.css)
        assertEquals("rgba(236, 28, 242, 0.5)", PaywallArt.color("A+40*0.8@0.5", mapOf("A" to "#8B5CF6"))?.css)
        assertNull("unreadable is transparent", PaywallArt.color("nope", palette))
    }

    @Test fun artPathParser() {
        val square = PaywallArt.bounds(PaywallArt.path("M0 0 L10 0 L10 10 L0 10 Z"))
        assertEquals(listOf(0.0, 0.0, 10.0, 10.0), square.toList())
        val tight = PaywallArt.bounds(PaywallArt.path("M-5-5 5,-5L5,5-5,5Z"))
        assertEquals(listOf(-5.0, -5.0, 5.0, 5.0), tight.toList())
        val curve = PaywallArt.bounds(PaywallArt.path("M0 100 C0 0 100 0 100 100 Z"))
        assertEquals(0.0, curve[0], 0.001)
        assertEquals(100.0, curve[2], 0.001)
        assertEquals("a cubic's top is at 3/4 of its control height", 25.0, curve[1], 0.5)
        val dots = PaywallArt.bounds(PaywallArt.path("M.5.5L1.5.5"))
        assertEquals(1.0, dots[2] - dots[0], 0.0001)
    }

    @Test fun everyArtDecodes() {
        assertEquals(arts.toSet(), PaywallArt.scenes.keys)
        for (name in arts) assertTrue(name, PaywallArt.scenes[name]!!.isNotEmpty())
        assertEquals(0.0, PaywallArt.swing(0.0, 2.0, 0.0), 0.0)
        assertEquals(1.0, PaywallArt.swing(2.0, 2.0, 0.0), 0.0001)
        assertEquals("alternates back", 0.0, PaywallArt.swing(4.0, 2.0, 0.0), 0.0001)
        assertEquals("rests until its delay", 0.0, PaywallArt.swing(1.0, 2.0, 3.0), 0.0)
    }

    @Test fun savingsTextHidesWithoutSavings() {
        val annual = PaywallPackageView("\$annual", "Annual", "\$39.99", "year", "", "", "33%")
        val monthly = packages[0]
        assertTrue(PaywallText.hidesForSavings("Save {{savings}} with a year", monthly))
        assertTrue(PaywallText.hidesForSavings("SAVE {{ savings }}", null))
        assertFalse(PaywallText.hidesForSavings("Save {{savings}} with a year", annual))
        assertFalse("only savings text hides", PaywallText.hidesForSavings("{{price}} / {{period}}", monthly))
        assertFalse(PaywallText.hidesForSavings(null, monthly))
    }

    @Test fun cardBadgesDecode() {
        val d = doc("""{"version":2,$THEME,"screens":[{"id":"s","name":"S","blocks":[{"id":"row","type":"stack","axis":"horizontal","children":[{"id":"m","type":"custom","package":"${'$'}monthly","badge":"SAVE {{savings}}","children":[{"id":"mt","type":"title","text":"Monthly","size":"s"}]},{"id":"a","type":"custom","package":"${'$'}annual","badge":"BEST · SAVE {{savings}}","children":[{"id":"at","type":"title","text":"Yearly","size":"s"}]}]},{"id":"c","type":"cta","text":"Continue"}]}]}""")
        assertEquals(listOf("SAVE {{savings}}", "BEST · SAVE {{savings}}"), d.allBlocks.filter { it.`package` != null }.map { it.badge })
    }

    @Test fun nestedBlocksDecode() {
        val b = block("""{"id":"s","type":"sheet","radius":24,"children":[{"id":"c","type":"custom","axis":"horizontal","justify":"space_between","gap":8,"padding":12,"background":"#FFF4E8","border":"#F04E28","children":[{"id":"t","type":"text","text":"Hi"}]},{"id":"sw","type":"switch","text":"Trial","package_on":"${'$'}annual","package_off":"${'$'}monthly"},{"id":"cd","type":"countdown","until":"2026-12-31T23:59:00Z","minutes":0},{"id":"sp","type":"social_proof","text":"Loved","images":["https://example.com/a.png"]},{"id":"v","type":"video","url":"https://example.com/v.mp4","poster":"https://example.com/p.png"},{"id":"h","type":"header","back":true},{"id":"car","type":"carousel","interval":3,"children":[{"id":"p1","type":"title","text":"One"}]}]}""")
        assertEquals(24.0, b.radius)
        assertEquals(7, b.children?.size)
        val custom = b.children!!.first()
        assertEquals("horizontal", custom.axis)
        assertEquals("space_between", custom.justify)
        assertEquals(8.0, custom.gap)
        assertEquals(12.0, custom.padding)
        assertEquals("#FFF4E8", custom.background)
        assertEquals("#F04E28", custom.border)
        assertEquals("Hi", custom.children?.first()?.text)
        assertEquals("\$annual", b.children!![1].packageOn)
        assertEquals("\$monthly", b.children!![1].packageOff)
        assertEquals("2026-12-31T23:59:00Z", b.children!![2].until)
        assertEquals(listOf("https://example.com/a.png"), b.children!![3].images)
        assertEquals("https://example.com/p.png", b.children!![4].poster)
        assertEquals(true, b.children!![5].back)
        assertEquals(3, b.children!![6].interval)
        assertEquals(listOf("s", "c", "t", "sw", "cd", "sp", "v", "h", "car", "p1"), b.withDescendants.map { it.id })
    }

    @Test fun countdownArithmetic() {
        val start = 1_000_000_000L
        val target = PaywallCountdown.target(null, 30, start)!!
        assertEquals(1_800_000L, target - start)
        assertEquals(Triple(0, 30, 0), PaywallCountdown.remaining(target, start))
        assertEquals(Triple(0, 28, 58), PaywallCountdown.remaining(target, start + 61_400))
        assertEquals("stops at zero", Triple(0, 0, 0), PaywallCountdown.remaining(target, start + 5_000_000))
        val until = PaywallCountdown.target("2026-12-31T23:59:00Z", 5, start)!!
        assertEquals(1_798_761_540_000L, until)
        val fractional = PaywallCountdown.target("2026-12-31T23:59:00.500+01:00", null, start)!!
        assertEquals(1_798_761_540_000L - 3_600_000 + 500, fractional)
        assertEquals(Triple(26, 5, 7), PaywallCountdown.remaining(until, until - (26 * 3600 + 5 * 60 + 7) * 1000L))
        assertNull(PaywallCountdown.target("tomorrow", null, start))
        assertNull(PaywallCountdown.target(null, 0, start))
        assertEquals(Triple(0, 0, 0), PaywallCountdown.remaining(null, start))
    }

    @Test fun switchPicksItsPackages() {
        assertTrue(PaywallSwitch.isOn("\$annual", "\$annual"))
        assertFalse(PaywallSwitch.isOn("\$monthly", "\$annual"))
        assertFalse(PaywallSwitch.isOn(null, null))
        assertEquals("\$annual", PaywallSwitch.pkg(true, "\$annual", "\$monthly"))
        assertEquals("\$monthly", PaywallSwitch.pkg(false, "\$annual", "\$monthly"))
    }

    @Test fun variablesResolveLikeTheWebSDK() {
        val annual = packages[1]
        assertEquals("\$39.99 / year", PaywallText.resolve("{{price}} / {{period}}", "Acme", annual))
        assertEquals("Start my trial", PaywallText.resolve("Start my {{trial}} trial", "Acme", packages[0]))
        assertEquals("Unlock Acme!", PaywallText.resolve("Unlock {{app_name}}{{ unknown }}!", "Acme", null))
        assertEquals("After: billed \$4.99.", PaywallText.resolve("After {{trial}}: billed {{price}}.", "Acme", packages[0]))
        assertEquals("Annual at \$3.33 a month", PaywallText.resolve("{{product_name}} at {{price_per_month}} a month", "Acme", annual))
    }

    @Test fun safeURLs() {
        assertNotNull(PaywallURL.safe("https://example.com/a.png"))
        assertNotNull(PaywallURL.safe("http://localhost:8080/media/a.png"))
        assertNotNull(PaywallURL.safe("http://127.0.0.1:3000/media/a.mp4"))
        assertNotNull(PaywallURL.safe("HTTP://LOCALHOST/media/a.png"))
        assertNull(PaywallURL.safe("http://example.com/a.png"))
        assertNull(PaywallURL.safe("javascript:alert(1)"))
        assertNull(PaywallURL.safe("http://localhost.evil.com/a.png"))
        assertNull(PaywallURL.safe("http://127.0.0.1.evil.com/a.png"))
        assertNull(PaywallURL.safe("https:///no-host"))
        assertNull(PaywallURL.safe("file:///etc/passwd"))
        assertNull(PaywallURL.safe(""))
        assertNull(PaywallURL.safe(null))
    }

    @Test fun hexColours() {
        assertEquals(0xFFFFFFFFL, PaywallColor.parse("#FFF"))
        assertEquals(0xFF1B1037L, PaywallColor.parse("#1B1037"))
        assertEquals(0x801B1037L, PaywallColor.parse("#1B103780"))
        assertNull(PaywallColor.parse("red"))
        assertNull(PaywallColor.parse("#12345"))
    }

    @Test fun versionOneReadsAsOneScreen() {
        val d = doc("""{"version":1,$THEME,"blocks":[{"id":"p","type":"packages"},{"id":"c","type":"cta","text":"Buy"}]}""")
        assertTrue(d.isSupported)
        assertEquals(listOf("paywall"), d.flow.map { it.id })
        assertEquals("paywall", d.initialScreenId)
        assertEquals(2, d.flow[0].blocks.size)
    }

    @Test fun newerSchemaIsNotGuessedAt() {
        assertFalse(doc("""{"version":3,$THEME,"blocks":[]}""").isSupported)
    }

    @Test fun missingIdsAreFilledAndThemeDefaultsApply() {
        val d = doc("""{"version":2,"theme":{"accent":"#123456"},"screens":[{"name":"","blocks":[{"type":"Title","text":"A"},{"id":"x","type":"text"},{"id":"x","type":"social_proof"}]}]}""")
        assertEquals("#123456", d.theme.accent)
        assertEquals("#FFFFFF", d.theme.background)
        assertEquals("screen-1", d.flow[0].id)
        assertEquals("Screen 1", d.flow[0].name)
        assertEquals(listOf("title-1", "x", "social-proof-1"), d.allBlocks.map { it.id })
        assertEquals("title", d.allBlocks[0].type)
        assertNull("no version, no document", PaywallDoc.parse("""{"theme":{}}"""))
        assertNull("no theme, no document", PaywallDoc.parse("""{"version":2}"""))
        assertNull(PaywallDoc.parse("""{"version":"not a number"}"""))
    }

    @Test fun offeringsCarryThePaywallAndSurviveABadOne() {
        val classic = raw.getJSONObject("classic").toString()
        val w = WireOfferings.fromJson(JSONObject("""
            {"current_offering":"a","app_name":"Acme","experiment":{"id":3,"variant":"b"},"offerings":[
              {"id":"a","packages":[],"paywall":$classic,"paywall_id":12},
              {"id":"b","packages":[],"paywall":{"version":"not a number"},"paywall_id":13},
              {"id":"c","packages":[]}]}
        """))
        assertEquals("Acme", w.appName)
        assertEquals(PaywallExperiment(3, "b"), w.experiment)
        assertNotNull(w.offerings[0].paywall)
        assertEquals(12L, w.offerings[0].paywallId)
        assertNull("an unreadable paywall leaves the offering usable", w.offerings[1].paywall)
        assertNull(w.offerings[2].paywall)
        assertNull(w.offerings[2].paywallId)
        assertNull(WireOfferings.fromJson(JSONObject("""{"offerings":[]}""")).experiment)
    }

    // ── Answers (spec §3) ───────────────────────────────────

    @Test fun questionScreensCarryAnswers() {
        val steps = fixtures.getValue("steps_1")
        val asked = steps.flow.filter { !it.question.isNullOrEmpty() }
        assertTrue(asked.isNotEmpty())
        for (s in asked) {
            val answers = s.allBlocks.filter { it.type == "button" && !it.answer.isNullOrEmpty() }
            assertTrue("${s.id} has answer buttons", answers.isNotEmpty())
            assertEquals(s.question, s.answerQuestion)
            answers.forEach { assertTrue(it.answer!!.length <= 120) }
        }
        assertEquals("falls back to the name", "Paywall", PaywallDoc.Screen(id = "p", name = "Paywall").answerQuestion)
        assertEquals("Paywall", PaywallDoc.Screen(id = "p", name = "Paywall", question = "").answerQuestion)
        val loc = fixtures.getValue("localized")
        assertEquals(listOf("friend", "social"), loc.allBlocks.filter { it.type == "button" }.map { it.answer })
    }

    // ── Localization (spec §4, the server's localize_test.go) ──

    @Test fun localizeMatchesLikeTheServer() {
        val d = fixtures.getValue("localized")
        assertEquals("en", PaywallLocalization.defaultLocale(d))
        val cases = mapOf("es" to "es", "es-MX" to "es", "ES_es" to "es", "pt-BR" to "pt-BR", "pt-PT" to "pt-BR", "pt" to "pt-BR",
            "pt_br" to "pt-BR", "en-GB" to null, "en" to null, "fr" to null, "" to null)
        for ((want, got) in cases) assertEquals("match($want)", got, PaywallLocalization.match(d, want))
        // Exact match wins even inside the default's language; otherwise that language stays default.
        val gb = d.copy(locales = listOf("en-GB", "es"))
        assertEquals("en-GB", PaywallLocalization.match(gb, "en_gb"))
        assertNull(PaywallLocalization.match(gb, "en-US"))
        assertEquals("an empty default means en", null, PaywallLocalization.match(d.copy(defaultLocale = ""), "en-AU"))
        assertEquals("es", PaywallLocalization.match(d.copy(defaultLocale = "fr"), "es-419"))
    }

    @Test fun localizeSwapsTextsIn() {
        val d = fixtures.getValue("localized")
        fun b(doc: PaywallDoc, id: String) = doc.allBlocks.first { it.id == id }
        val es = d.localize("es-MX")
        assertEquals("Hazte Pro", b(es, "t2").text)
        assertEquals("nested children", "Un amigo", b(es, "o1").text)
        assertEquals("missing keys keep the original", "Social media", b(es, "o2").text)
        assertEquals("friend", b(es, "o1").answer)
        assertEquals("Sin anuncios", b(es, "f1").items!![0].title)
        assertEquals("Ever.", b(es, "f1").items!![0].text)
        assertEquals("Everything", b(es, "f1").items!![1].title)
        assertEquals("Mejor precio", b(es, "p1").badges!!["annual"])
        assertEquals("Continuar por {{price}}", b(es, "c1").text)
        assertEquals("Cancel anytime", b(es, "c1").subtext)
        assertEquals("¿Cómo nos conociste?", es.flow[0].question)
        assertEquals("¿Cómo nos conociste?", es.flow[0].answerQuestion)
        // Variables in a translation resolve afterwards, as in the original.
        assertEquals("Continuar por \$4.99", PaywallText.resolve(b(es, "c1").text!!, "Acme", packages[0]))

        assertEquals("the original is untouched", "Go Pro", b(d, "t2").text)
        assertEquals("Best value", b(d, "p1").badges!!["annual"])
        assertEquals("the default language gets no translation", "Go Pro", b(d.localize("en-US"), "t2").text)
        assertEquals("Go Pro", b(d.localize("fr"), "t2").text)
        val pt = d.localize("pt")
        assertEquals("pt falls back to pt-BR", "Seja Pro", b(pt, "t2").text)
        assertEquals("How did you hear about us?", b(pt, "t1").text)
        assertEquals("How did you hear about us?", pt.flow[0].question)
    }

    @Test fun localizeOnlyReplacesTextsThatAreSetAndWalksEveryContainer() {
        val d = doc("""{"version":2,$THEME,"default_locale":"en","locales":["de"],"strings":{"de":{
            "tabs.text":"nope","tab1.text":"Monatlich","deep.text":"Tief","car.text":"leer","pg.text":"Seite","sh.author":"Autor",
            "q.question":"Frage","cd.badge":"Abzeichen","cd.answer":"Antwort","tl.items.1.text":"Zwei","tl.items.0.text":"kein Text","bg.badges.x":"Neu","empty.text":""}},
            "screens":[{"id":"q","name":"Q","blocks":[
              {"id":"tabs","type":"tabs","children":[{"id":"tab1","type":"stack","text":"Monthly","children":[{"id":"box","type":"custom","children":[{"id":"deep","type":"text","text":"Deep"}]}]}]},
              {"id":"car","type":"carousel","children":[{"id":"pg","type":"title","text":"Page"}]},
              {"id":"sheet","type":"sheet","children":[{"id":"sh","type":"testimonial","text":"Great","author":"Ann"},{"id":"cd","type":"custom","package":"p","badge":"Best","children":[{"id":"btn","type":"button","text":"Yes","answer":"yes"}]}]},
              {"id":"tl","type":"timeline","items":[{"icon":"star","title":"One"},{"icon":"star","title":"Two","text":"2"}]},
              {"id":"bg","type":"packages","badges":{"y":"Old"}},
              {"id":"empty","type":"text","text":"Keep"}]}]}""")
        val de = d.localize("de-AT")
        fun b(id: String) = de.allBlocks.first { it.id == id }
        assertEquals("Monatlich", b("tab1").text)
        assertEquals("Tief", b("deep").text)
        assertNull("only set texts are replaced", b("car").text)
        assertNull(b("tabs").text)
        assertEquals("Seite", b("pg").text)
        assertEquals("Autor", b("sh").author)
        assertEquals("Frage", de.flow[0].question)
        assertEquals("Abzeichen", b("cd").badge)
        assertNull("the card has no answer to replace", b("cd").answer)
        assertEquals("yes", b("btn").answer)
        assertNull(b("tl").items!![0].text)
        assertEquals("Zwei", b("tl").items!![1].text)
        assertEquals("badges only for packages it lists", mapOf("y" to "Old"), b("bg").badges)
        assertEquals("an empty translation keeps the original", "Keep", b("empty").text)
        assertEquals("a screen question is set even where the original has none (as the server)", "Frage", d.localize("de").flow[0].question)
    }

    // ── Backend and analytics (spec §1–3) ───────────────────

    @Test fun offeringsSendTheAppUserId() = runTest {
        var seen = ""
        val backend = Backend("k", "https://cashier.test", Transport { _, url, _, _ ->
            seen = url
            200 to """{"current_offering":null,"offerings":[]}"""
        })
        backend.offerings("\$anon:a b")
        assertEquals("https://cashier.test/api/cashier/v1/offerings?app_user_id=%24anon%3Aa%20b", seen)
        backend.offerings(null)
        assertEquals("https://cashier.test/api/cashier/v1/offerings", seen)
    }

    @Test fun paywallEventsAndAnswersAreSentAndNeverThrow() = runTest {
        val sent = java.util.Collections.synchronizedList(mutableListOf<Pair<String, JSONObject>>())
        var fail = false
        val backend = Backend("k", "https://cashier.test", Transport { method, url, _, body ->
            assertEquals("POST", method)
            sent += url.substringAfter("/v1/") to JSONObject(body!!)
            if (fail) 500 to """{"error":"boom"}""" else 204 to ""
        })
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val analytics = PaywallAnalytics(backend, scope) { "user_1" }
        val offering = Offering("a", "", emptyList(), paywall = null, paywallId = 12, experiment = PaywallExperiment(3, "a"))
        val jobs = listOfNotNull(
            analytics.event(offering, "view"),
            analytics.answer(offering, PaywallAnswer("s2", "How did you hear about us?", "A friend told me")),
            analytics.event(offering.copy(experiment = null), "close"),
        )
        assertNull("no paywall id, nothing sent", analytics.event(offering.copy(paywallId = null), "view"))
        assertNull(analytics.answer(offering.copy(paywallId = null), PaywallAnswer("s", "q", "a")))
        scope.advanceUntilIdle()
        jobs.forEach { it.join() }
        assertEquals(3, sent.size)
        // Sent concurrently: find each by what it is.
        val (url1, view) = sent.first { it.second.optString("type") == "view" }
        assertEquals("paywalls/events", url1)
        assertEquals("user_1", view.getString("app_user_id"))
        assertEquals(12, view.getInt("paywall_id"))
        assertEquals("view", view.getString("type"))
        assertEquals(3, view.getInt("experiment_id"))
        assertEquals("a", view.getString("variant"))
        val (url2, answer) = sent.first { it.second.has("answer") }
        assertEquals("paywalls/responses", url2)
        assertEquals("s2", answer.getString("screen_id"))
        assertEquals("How did you hear about us?", answer.getString("question"))
        assertEquals("A friend told me", answer.getString("answer"))
        assertEquals(12, answer.getInt("paywall_id"))
        val close = sent.first { it.second.optString("type") == "close" }.second
        assertEquals("close", close.getString("type"))
        assertFalse("experiment fields only with an experiment", close.has("experiment_id") || close.has("variant"))
        fail = true
        val failing = analytics.event(offering, "close")!!
        scope.advanceUntilIdle()
        failing.join()
        assertFalse(failing.isCancelled)
        assertEquals("errors are swallowed", 4, sent.size)
    }
}
