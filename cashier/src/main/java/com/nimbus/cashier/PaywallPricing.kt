package com.nimbus.cashier

import com.android.billingclient.api.ProductDetails
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Google Play prices for the paywall's variables. */
internal object PaywallPricing {
    /** An ISO 8601 billing period ("P1M", "P1Y", "P1W", "P7D", "P3M") as count and unit. */
    data class Period(val value: Int, val unit: Char)

    fun parsePeriod(iso: String?): Period? {
        val m = Regex("""^P(?:(\d+)Y)?(?:(\d+)M)?(?:(\d+)W)?(?:(\d+)D)?$""").find(iso ?: return null) ?: return null
        val (y, mo, w, d) = m.destructured
        return when {
            y.isNotEmpty() && mo.isEmpty() && w.isEmpty() && d.isEmpty() -> Period(y.toInt(), 'Y')
            mo.isNotEmpty() && y.isEmpty() && w.isEmpty() && d.isEmpty() -> Period(mo.toInt(), 'M')
            w.isNotEmpty() && y.isEmpty() && mo.isEmpty() && d.isEmpty() -> Period(w.toInt(), 'W')
            d.isNotEmpty() && y.isEmpty() && mo.isEmpty() && w.isEmpty() -> Period(d.toInt(), 'D')
            // Mixed ("P1Y6M"): read as months, the only unit it can be written in.
            y.isNotEmpty() || mo.isNotEmpty() -> Period((y.toIntOrNull() ?: 0) * 12 + (mo.toIntOrNull() ?: 0), 'M')
            else -> null
        }
    }

    private fun unitWord(u: Char) = when (u) {
        'D' -> "day"; 'W' -> "week"; 'M' -> "month"; 'Y' -> "year"; else -> "period"
    }

    /** "month", "3 months", "year". */
    fun words(p: Period): String = if (p.value == 1) unitWord(p.unit) else "${p.value} ${unitWord(p.unit)}s"

    /** "7-day", "1-week", "1-month". */
    fun trialWords(p: Period): String = "${p.value}-${unitWord(p.unit)}"

    /** A period in months, for savings: weeks are a quarter, days not counted. */
    fun months(p: Period): Double = when (p.unit) {
        'W' -> p.value * 0.25; 'M' -> p.value.toDouble(); 'Y' -> p.value * 12.0; else -> 0.0
    }

    fun monthsIn(p: Period): Int = when (p.unit) {
        'M' -> p.value; 'Y' -> p.value * 12; else -> 0
    }

    /** What one package looks like before pricing is formatted: Play's numbers. */
    data class Priced(
        val id: String,
        val productName: String,
        val formattedPrice: String,
        val priceMicros: Long,
        val currency: String,
        /** null for a one-time purchase. */
        val period: Period?,
        /** The free trial's length, when the default offer starts with one. */
        val trial: Period?,
    )

    fun format(amount: Double, currency: String, locale: Locale = Locale.getDefault()): String = try {
        NumberFormat.getCurrencyInstance(locale).apply { this.currency = Currency.getInstance(currency) }.format(amount)
    } catch (_: Exception) {
        String.format(Locale.US, "%.2f %s", amount, currency)
    }

    /** The paywall's view of packages, {{savings}} included. */
    fun views(priced: List<Priced>, locale: Locale = Locale.getDefault()): List<PaywallPackageView> {
        val savings = PaywallPlans.savings(priced.map { PaywallPlans.Plan(it.id, it.priceMicros / 1_000_000.0, it.period?.let(::months) ?: 0.0) })
        return priced.map { p ->
            var perMonth = ""
            p.period?.let { per ->
                val m = monthsIn(per)
                if (m > 1) perMonth = format(p.priceMicros / 1_000_000.0 / m, p.currency, locale)
            }
            PaywallPackageView(
                id = p.id, productName = p.productName, price = p.formattedPrice,
                period = p.period?.let(::words) ?: "", pricePerMonth = perMonth,
                trial = p.trial?.let(::trialWords) ?: "", savings = savings[p.id] ?: "",
            )
        }
    }

    /** Play's product details for one package, read the way the paywall shows it. */
    fun priced(pkg: Package): Priced {
        val d: ProductDetails = pkg.productDetails
        val name = d.name.ifEmpty { d.title }
        d.oneTimePurchaseOfferDetails?.let {
            return Priced(pkg.identifier, name, it.formattedPrice, it.priceAmountMicros, it.priceCurrencyCode, null, null)
        }
        val offers = d.subscriptionOfferDetails.orEmpty()
        val offer = offers.firstOrNull { it.offerToken == pkg.defaultOfferToken } ?: offers.firstOrNull()
        val phases = offer?.pricingPhases?.pricingPhaseList.orEmpty()
        // The recurring (last) phase is the base price; a free first phase is the trial.
        val base = phases.lastOrNull()
        val first = phases.firstOrNull()
        val trial = if (first != null && phases.size > 1 && first.priceAmountMicros == 0L) parsePeriod(first.billingPeriod) else null
        return Priced(
            pkg.identifier, name, base?.formattedPrice ?: pkg.formattedPrice ?: "",
            base?.priceAmountMicros ?: 0L, base?.priceCurrencyCode ?: "USD",
            parsePeriod(base?.billingPeriod) ?: Period(pkg.cashierProduct.periodMonths.coerceAtLeast(1), 'M'), trial,
        )
    }

    fun views(packages: List<Package>): List<PaywallPackageView> = views(packages.map(::priced))
}
