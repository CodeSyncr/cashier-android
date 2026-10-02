package com.nimbus.cashier

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What a subscriber holds right now — the one object an app gates on. */
data class CustomerInfo(
    /** The app user id this state belongs to (after any alias). */
    val appUserId: String,
    val requestDate: Date?,
    /** Every known entitlement, active or not, keyed by identifier. */
    val entitlements: Map<String, EntitlementInfo>,
    val activeEntitlementIds: List<String>,
    val activeProductIds: List<String>,
    /** Furthest-out expiry across active entitlements; null when one never expires. */
    val latestExpirationDate: Date?,
) {
    /** The entitlements granting access now. */
    val activeEntitlements: Map<String, EntitlementInfo> get() = entitlements.filterValues { it.isActive }

    /** Whether an entitlement grants access now. */
    fun isEntitledTo(entitlementId: String): Boolean = entitlements[entitlementId]?.isActive == true

    companion object {
        fun fromJson(o: JSONObject): CustomerInfo {
            val ents = mutableMapOf<String, EntitlementInfo>()
            o.optJSONObject("entitlements")?.let { e -> e.keys().forEach { k -> ents[k] = EntitlementInfo.fromJson(e.getJSONObject(k)) } }
            return CustomerInfo(
                appUserId = o.getString("subject"),
                requestDate = CashierDates.parse(o.optStringOrNull("requested_at")),
                entitlements = ents,
                activeEntitlementIds = o.optJSONArray("active_entitlement_ids").strings(),
                activeProductIds = o.optJSONArray("active_product_ids").strings(),
                latestExpirationDate = CashierDates.parse(o.optStringOrNull("latest_expires_at")),
            )
        }
    }
}

/** One entitlement's state. */
data class EntitlementInfo(
    val identifier: String,
    val isActive: Boolean,
    /** False once auto-renew is off; access still lasts to expiry. */
    val willRenew: Boolean,
    /** trial | intro | normal | promotional | grace */
    val periodType: String,
    /** The Cashier product that granted it (null for a promotional grant). */
    val productIdentifier: String?,
    /** purchase | promotional */
    val source: String?,
    val expirationDate: Date?,
) {
    /** In the store's grace period after a failed renewal. */
    val isInGracePeriod: Boolean get() = periodType == "grace"

    companion object {
        fun fromJson(o: JSONObject) = EntitlementInfo(
            identifier = o.getString("id"),
            isActive = o.optBoolean("active"),
            willRenew = o.optBoolean("will_renew"),
            periodType = o.optStringOrNull("period_type") ?: "normal",
            productIdentifier = o.optStringOrNull("product_id"),
            source = o.optStringOrNull("source"),
            expirationDate = CashierDates.parse(o.optStringOrNull("expires_at")),
        )
    }
}

/** A product as configured in the Cashier console. */
data class CashierProduct(
    val identifier: String,
    val name: String,
    /** Console price in the currency's smallest unit (Play charges its own price). */
    val amount: Long,
    val currency: String,
    /** Billing period in months; 0 is a one-time purchase. */
    val periodMonths: Int,
    val trialDays: Int,
    val entitlements: List<String>,
    val appleProductId: String?,
    val googleProductId: String?,
) {
    val isSubscription: Boolean get() = periodMonths > 0

    companion object {
        fun fromJson(o: JSONObject) = CashierProduct(
            identifier = o.getString("id"),
            name = o.optStringOrNull("name") ?: o.getString("id"),
            amount = o.optLong("amount"),
            currency = o.optString("currency"),
            periodMonths = o.optInt("period_months"),
            trialDays = o.optInt("trial_days"),
            entitlements = o.optJSONArray("entitlements").strings(),
            appleProductId = o.optStringOrNull("apple_product_id"),
            googleProductId = o.optStringOrNull("google_product_id"),
        )
    }
}

internal data class WireOffering(
    val id: String,
    val description: String,
    val packages: List<Pair<String, CashierProduct>>,
    /** The published paywall; a document this SDK cannot read is null, never an error. */
    val paywall: PaywallDoc? = null,
    val paywallId: Long? = null,
)

internal data class WireOfferings(
    val current: String?,
    val offerings: List<WireOffering>,
    val appName: String? = null,
    val experiment: PaywallExperiment? = null,
) {
    companion object {
        fun fromJson(o: JSONObject): WireOfferings {
            val list = o.optJSONArray("offerings") ?: JSONArray()
            val offerings = (0 until list.length()).map { i ->
                val off = list.getJSONObject(i)
                val pk = off.optJSONArray("packages") ?: JSONArray()
                WireOffering(
                    id = off.getString("id"),
                    description = off.optString("description"),
                    packages = (0 until pk.length()).map { j ->
                        val p = pk.getJSONObject(j)
                        p.getString("id") to CashierProduct.fromJson(p.getJSONObject("product"))
                    },
                    paywall = (off.opt("paywall") as? JSONObject)?.let { runCatching { PaywallDoc.fromJson(it) }.getOrNull() },
                    paywallId = (off.opt("paywall_id") as? Number)?.toLong(),
                )
            }
            val experiment = (o.opt("experiment") as? JSONObject)?.let { e ->
                val id = (e.opt("id") as? Number)?.toLong() ?: return@let null
                PaywallExperiment(id, e.optString("variant"))
            }
            return WireOfferings(o.optStringOrNull("current_offering"), offerings, o.optStringOrNull("app_name"), experiment)
        }
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { getString(it) }

/** RFC 3339 with any number of fractional digits and any offset; Go's zero time is null. */
internal object CashierDates {
    private val fraction = Regex("""\.(\d+)""")

    fun parse(raw: String?): Date? {
        if (raw.isNullOrEmpty() || raw.startsWith("0001-01-01")) return null
        // SimpleDateFormat reads exactly milliseconds; trim or pad to 3 digits.
        val normalized = if (fraction.containsMatchIn(raw)) {
            fraction.replace(raw) { m -> "." + m.groupValues[1].take(3).padEnd(3, '0') }
        } else {
            raw.replaceFirst(Regex("""(T\d{2}:\d{2}:\d{2})"""), "$1.000")
        }
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(normalized)
        } catch (_: Exception) {
            null
        }
    }
}
