package com.nimbus.cashier

import com.android.billingclient.api.ProductDetails

/** Every offering the console defines, with Play products attached. */
data class Offerings(
    /** The offering your paywall should show now. */
    val current: Offering?,
    /** Every offering, keyed by identifier. */
    val all: Map<String, Offering>,
    /** The paywall experiment the current user is enrolled in, if any ([current] already reflects its variant). */
    val experiment: PaywallExperiment? = null,
) {
    operator fun get(identifier: String): Offering? = all[identifier]
}

/** A named set of packages a paywall presents. */
data class Offering(
    val identifier: String,
    val serverDescription: String,
    /** Packages whose product Google Play returned, in console order. */
    val availablePackages: List<Package>,
    /**
     * The paywall designed for this offering in the Cashier console, when
     * one is published. Show it with [PaywallView] or [Cashier.presentPaywall].
     */
    val paywall: PaywallDoc? = null,
    /** The server's id for [paywall] (analytics); null for a preview. */
    val paywallId: Long? = null,
    /** Your Cashier app's name, for {{app_name}} in paywall text. */
    val appName: String = "",
    /** The experiment this offering was chosen by, if any. */
    val experiment: PaywallExperiment? = null,
) {
    fun getPackage(identifier: String): Package? = availablePackages.firstOrNull { it.identifier == identifier }
    val monthly get() = getPackage("\$monthly")
    val annual get() = getPackage("\$annual")
    val sixMonth get() = getPackage("\$six_month")
    val threeMonth get() = getPackage("\$three_month")
    val twoMonth get() = getPackage("\$two_month")
    val weekly get() = getPackage("\$weekly")
    val lifetime get() = getPackage("\$lifetime")
}

/** One slot of an offering: a Cashier product and the Play product that sells it. */
data class Package(
    val identifier: String,
    val offeringIdentifier: String,
    val cashierProduct: CashierProduct,
    /** Play's product: localized title and prices come from here. */
    val productDetails: ProductDetails,
) {
    /**
     * For a subscription, the offer bought by default: the eligible offer
     * whose first phase is cheapest (a free trial wins), else the base plan.
     * Pass another token to [Cashier.purchase] to sell a specific offer.
     */
    val defaultOfferToken: String?
        get() = productDetails.subscriptionOfferDetails
            ?.minByOrNull { o -> o.pricingPhases.pricingPhaseList.firstOrNull()?.priceAmountMicros ?: Long.MAX_VALUE }
            ?.offerToken

    /** The price to show: the recurring base price for a subscription, else the one-time price. */
    val formattedPrice: String?
        get() = productDetails.oneTimePurchaseOfferDetails?.formattedPrice
            ?: productDetails.subscriptionOfferDetails
                ?.flatMap { it.pricingPhases.pricingPhaseList }
                ?.maxByOrNull { it.priceAmountMicros }
                ?.formattedPrice
}

/** A running paywall experiment the user is enrolled in. */
data class PaywallExperiment(
    val id: Long,
    /** "a" or "b". */
    val variant: String,
)
