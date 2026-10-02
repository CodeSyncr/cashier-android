package com.nimbus.cashier

import java.util.Locale

/**
 * A paywall's own translations (the server's localize.go, ported exactly).
 *
 * The block texts are written in `default_locale`; `strings` holds, per
 * extra locale, replacements keyed by where the text lives:
 *
 *     <block>.text  <block>.subtext  <block>.author  <block>.badge  <block>.answer
 *     <block>.items.<i>.title  <block>.items.<i>.text  <block>.badges.<package>
 *     <screen>.question
 *
 * A key left out keeps the original, so a half-translated paywall still
 * reads whole. Variables such as {{price}} inside a translation are filled
 * in afterwards exactly as in the original.
 */
object PaywallLocalization {
    private fun language(tag: String): String {
        val t = tag.replace('_', '-').lowercase(Locale.ROOT)
        val i = t.indexOf('-')
        return if (i > 0) t.substring(0, i) else t
    }

    /** The locale the block texts are written in. */
    @JvmStatic
    fun defaultLocale(doc: PaywallDoc): String = doc.defaultLocale?.takeIf { it.isNotEmpty() } ?: "en"

    /**
     * Which of the paywall's translations a device asking for [requested]
     * sees: an exact (case-insensitive, `_` read as `-`) match, else the
     * first with the same language — unless that language is the
     * default's — else null (the originals).
     */
    @JvmStatic
    fun match(doc: PaywallDoc, requested: String): String? {
        val want = requested.trim().replace('_', '-').lowercase(Locale.ROOT)
        if (want.isEmpty()) return null
        doc.locales.firstOrNull { it.lowercase(Locale.ROOT) == want }?.let { return it }
        if (language(want) == language(defaultLocale(doc))) return null
        return doc.locales.firstOrNull { language(it) == language(want) }
    }

    /** A copy of [doc] with the texts for [requested] swapped in. */
    @JvmStatic
    fun localize(doc: PaywallDoc, requested: String): PaywallDoc {
        val loc = match(doc, requested) ?: return doc
        val tr = doc.strings[loc]
            ?: doc.strings.entries.firstOrNull { it.key.equals(loc, ignoreCase = true) }?.value
            ?: return doc
        fun t(key: String, original: String?): String? {
            if (original.isNullOrEmpty()) return original
            return tr[key]?.takeIf { it.isNotEmpty() } ?: original
        }
        fun block(b: PaywallDoc.Block): PaywallDoc.Block {
            val id = b.id
            return b.copy(
                text = t("$id.text", b.text),
                subtext = t("$id.subtext", b.subtext),
                author = t("$id.author", b.author),
                badge = t("$id.badge", b.badge),
                answer = t("$id.answer", b.answer),
                items = b.items?.mapIndexed { i, it ->
                    it.copy(title = t("$id.items.$i.title", it.title) ?: "", text = t("$id.items.$i.text", it.text))
                },
                badges = b.badges?.mapValuesTo(linkedMapOf()) { (pkg, v) -> tr["$id.badges.$pkg"]?.takeIf { it.isNotEmpty() } ?: v },
                children = b.children?.map { block(it) },
            )
        }
        return doc.copy(
            screens = doc.screens?.map { s ->
                // As the server: a screen question is replaced even when the original is empty.
                s.copy(question = tr["${s.id}.question"]?.takeIf { it.isNotEmpty() } ?: s.question, blocks = s.blocks.map { block(it) })
            },
            blocks = doc.blocks?.map { block(it) },
        )
    }

    /** The device's preferred locale as a BCP-47 tag. */
    @JvmStatic
    fun deviceLocale(): String = try {
        androidx.core.os.LocaleListCompat.getAdjustedDefault()[0]?.toLanguageTag() ?: Locale.getDefault().toLanguageTag()
    } catch (_: Throwable) {
        Locale.getDefault().toLanguageTag()
    }
}
