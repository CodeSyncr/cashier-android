package com.nimbus.cashier

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * Nimbus Cashier for Android: in-app purchases through Google Play Billing,
 * verified and tracked by your Cashier app, gated on entitlements.
 *
 * ```kotlin
 * Cashier.configure(context, apiKey = "cshr_pub_…", appUserId = user?.id)
 *
 * val offerings = Cashier.shared.offerings()
 * offerings.current?.annual?.let { pkg ->
 *     val result = Cashier.shared.purchase(activity, pkg)
 *     if (result.customerInfo?.isEntitledTo("premium") == true) unlock()
 * }
 * ```
 *
 * The SDK never acknowledges or consumes a purchase itself: Cashier verifies
 * the purchase token with Google and acknowledges it server-side, so a
 * purchase Cashier never saw is refunded by Google after three days instead
 * of being granted blindly.
 */
class Cashier internal constructor(
    context: Context,
    apiKey: String,
    appUserId: String?,
    baseUrl: String,
    transport: Transport,
    private val billingFactory: ((PurchasesUpdatedListener) -> BillingClient)?,
) {
    companion object {
        const val VERSION = "1.0.0"

        @Volatile private var instance: Cashier? = null

        /** The configured instance. Call [configure] first. */
        @JvmStatic
        val shared: Cashier
            get() = instance ?: error("NimbusCashier: call Cashier.configure(context, apiKey) before Cashier.shared")

        @JvmStatic
        val isConfigured: Boolean get() = instance != null

        /**
         * Configures the shared instance once, early (Application.onCreate).
         *
         * @param apiKey your app's **public** key (`cshr_pub_…`). Never ship the secret key.
         * @param appUserId your own user id when known; null uses the last
         *   identified user, else a persisted anonymous id.
         */
        @JvmStatic
        @JvmOverloads
        fun configure(
            context: Context,
            apiKey: String,
            appUserId: String? = null,
            baseUrl: String = "https://nimbusgo.space",
        ): Cashier {
            val c = Cashier(context.applicationContext, apiKey, appUserId, baseUrl, HttpTransport, null)
            synchronized(this) {
                instance?.close()
                instance = c
            }
            c.connectAndSync()
            return c
        }
    }

    init {
        require(!apiKey.startsWith("cshr_live_")) {
            "NimbusCashier: that is your SECRET key. Use the public key (cshr_pub_…) in an app."
        }
    }

    private val backend = Backend(apiKey, baseUrl, transport)
    private val prefs: SharedPreferences = context.getSharedPreferences("com.nimbus.cashier", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectMutex = Mutex()

    @Volatile private var currentUser: String = appUserId?.trim()?.takeIf { it.isNotEmpty() }
        ?.also { prefs.edit().putString(Identity.IDENTIFIED_KEY, it).apply() }
        ?: prefs.getString(Identity.IDENTIFIED_KEY, null)?.takeIf { it.isNotEmpty() }
        ?: Identity.anonymousId(prefs)

    private val _customerInfo = MutableStateFlow<CustomerInfo?>(null)
    @Volatile private var fetchedAt = 0L

    /** Every CustomerInfo the SDK learns about, as it happens. */
    val customerInfoFlow: StateFlow<CustomerInfo?> = _customerInfo.asStateFlow()

    /** How long a cached CustomerInfo is served without refetching, in ms. */
    var cacheLifetimeMillis: Long = 5 * 60 * 1000

    /** The app user id purchases and reads are attributed to. */
    val appUserId: String get() = currentUser

    /** Whether the current user is an anonymous one the SDK generated. */
    val isAnonymous: Boolean get() = Identity.isAnonymous(currentUser)

    // ── Billing client ───────────────────────────────────────

    private var inFlight: CompletableDeferred<Pair<BillingResult, List<Purchase>?>>? = null

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        val waiting = inFlight
        if (waiting != null && !waiting.isCompleted) {
            waiting.complete(result to purchases)
        } else if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            // Outside a purchase call: a pending purchase completing, or one
            // bought from the Play Store app.
            scope.launch { purchases.forEach { runCatching { deliver(it) } } }
        }
    }

    private val billing: BillingClient by lazy {
        billingFactory?.invoke(purchasesListener) ?: BillingClient.newBuilder(context)
            .setListener(purchasesListener)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
    }

    private suspend fun ensureConnected() = connectMutex.withLock {
        if (billing.isReady) return@withLock
        val result = suspendCancellableCoroutine { cont ->
            billing.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(r: BillingResult) {
                    if (cont.isActive) cont.resume(r)
                }
                override fun onBillingServiceDisconnected() {
                    // The next call reconnects.
                }
            })
        }
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            throw CashierException(0, "billing_unavailable", "Google Play Billing is unavailable: ${result.debugMessage}")
        }
    }

    internal fun connectAndSync() {
        scope.launch {
            runCatching {
                ensureConnected()
                // Anything bought but never acknowledged reached Cashier only
                // if this post succeeds; retry it on every launch.
                ownedPurchases().filter { !it.isAcknowledged }.forEach { runCatching { deliver(it) } }
            }
        }
    }

    private fun close() {
        runCatching { if (billing.isReady) billing.endConnection() }
    }

    // ── Customer info ────────────────────────────────────────

    /** What the current user holds; cached for [cacheLifetimeMillis] unless [forceRefresh]. */
    suspend fun customerInfo(forceRefresh: Boolean = false): CustomerInfo {
        val user = currentUser
        val cached = _customerInfo.value
        if (!forceRefresh && cached != null && cached.appUserId == user &&
            System.currentTimeMillis() - fetchedAt < cacheLifetimeMillis
        ) return cached
        return backend.customerInfo(user).also { publish(it, user) }
    }

    /** The last CustomerInfo fetched for the current user, without a request. */
    val cachedCustomerInfo: CustomerInfo? get() = _customerInfo.value?.takeIf { it.appUserId == currentUser }

    private fun publish(info: CustomerInfo, user: String) {
        // A response for a user who has since logged out must not land.
        if (user != currentUser && info.appUserId != currentUser) return
        fetchedAt = System.currentTimeMillis()
        _customerInfo.value = info
    }

    fun invalidateCustomerInfoCache() {
        fetchedAt = 0
    }

    // ── Offerings ────────────────────────────────────────────

    /**
     * The console's offerings with each package's Play product loaded. A
     * package whose product has no Google Play id, or that Play does not
     * return (not active, wrong package), is left out.
     */
    suspend fun offerings(): Offerings = buildOfferings(backend.offerings(currentUser))

    /**
     * The offering of a paywall *draft*, from the editor's "Preview in app"
     * (the token in its QR code / link). For development builds only;
     * customers always get the published paywall. Show it with
     * `PaywallView(offering = …)`.
     */
    suspend fun previewOffering(token: String): Offering {
        val offerings = buildOfferings(backend.paywallPreview(token))
        return offerings.current ?: offerings.all.values.firstOrNull()
            ?: throw CashierException(404, "not_found", "No paywall preview for that token.")
    }

    private suspend fun buildOfferings(wire: WireOfferings): Offerings {
        ensureConnected()
        val products = wire.offerings.flatMap { it.packages.map { p -> p.second } }.filter { it.googleProductId != null }
        val details = mutableMapOf<String, com.android.billingclient.api.ProductDetails>()
        for ((type, group) in products.groupBy { if (it.isSubscription) BillingClient.ProductType.SUBS else BillingClient.ProductType.INAPP }) {
            val params = QueryProductDetailsParams.newBuilder().setProductList(
                group.map { it.googleProductId!! }.distinct().map {
                    QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(type).build()
                },
            ).build()
            val result = billing.queryProductDetails(params)
            if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                throw CashierException(0, "billing_error", "Google Play could not load products: ${result.billingResult.debugMessage}")
            }
            result.productDetailsList?.forEach { details[it.productId] = it }
        }
        val all = wire.offerings.associate { o ->
            o.id to Offering(
                identifier = o.id,
                serverDescription = o.description,
                availablePackages = o.packages.mapNotNull { (id, product) ->
                    details[product.googleProductId]?.let { Package(id, o.id, product, it) }
                },
                paywall = o.paywall,
                paywallId = o.paywallId?.takeIf { o.paywall != null },
                appName = wire.appName ?: "",
                experiment = wire.experiment,
            )
        }
        return Offerings(current = wire.current?.let { all[it] }, all = all, experiment = wire.experiment)
    }

    // ── Paywall analytics (fire-and-forget) ──────────────────

    internal val paywallAnalytics = PaywallAnalytics(backend, scope) { currentUser }

    // ── Purchasing ───────────────────────────────────────────

    /** The outcome of a purchase attempt. */
    data class PurchaseResult(
        val customerInfo: CustomerInfo?,
        val purchase: Purchase?,
        /** The buyer backed out. */
        val userCancelled: Boolean,
        /** Waiting on payment (cash, bank transfer); [customerInfoFlow] updates when it clears. */
        val isPending: Boolean,
    )

    /**
     * Buys a package. For a subscription, [offerToken] picks the base plan or
     * offer; the default is [Package.defaultOfferToken].
     */
    suspend fun purchase(activity: Activity, pkg: Package, offerToken: String? = null): PurchaseResult {
        ensureConnected()
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(pkg.productDetails)
        if (pkg.productDetails.productType == BillingClient.ProductType.SUBS) {
            val token = offerToken ?: pkg.defaultOfferToken
                ?: throw CashierException(0, "no_offer", "No eligible base plan or offer for ${pkg.productDetails.productId}")
            productParams.setOfferToken(token)
        }
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams.build()))
            .setObfuscatedAccountId(Identity.obfuscatedAccountId(currentUser))
            .build()

        val waiting = CompletableDeferred<Pair<BillingResult, List<Purchase>?>>()
        inFlight = waiting
        try {
            val launch = billing.launchBillingFlow(activity, flow)
            if (launch.responseCode != BillingClient.BillingResponseCode.OK) {
                return failure(launch)
            }
            val (result, purchases) = waiting.await()
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return failure(result)
            val bought = purchases.orEmpty().firstOrNull { pkg.productDetails.productId in it.products }
                ?: purchases.orEmpty().firstOrNull()
                ?: return PurchaseResult(cachedCustomerInfo, null, userCancelled = false, isPending = false)
            if (bought.purchaseState == Purchase.PurchaseState.PENDING) {
                return PurchaseResult(cachedCustomerInfo, bought, userCancelled = false, isPending = true)
            }
            return PurchaseResult(deliver(bought), bought, userCancelled = false, isPending = false)
        } finally {
            inFlight = null
        }
    }

    private fun failure(result: BillingResult): PurchaseResult = when (result.responseCode) {
        BillingClient.BillingResponseCode.USER_CANCELED ->
            PurchaseResult(cachedCustomerInfo, null, userCancelled = true, isPending = false)
        else -> throw CashierException(0, "billing_${result.responseCode}", "Google Play: ${result.debugMessage}")
    }

    /** Posts one Play purchase to Cashier (which verifies and acknowledges it). */
    private suspend fun deliver(purchase: Purchase): CustomerInfo? {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return null
        val user = currentUser
        var info: CustomerInfo? = null
        for (product in purchase.products) {
            info = backend.postReceipt(user, Receipt(purchase.purchaseToken, product))
        }
        info?.let { publish(it, user) }
        return info
    }

    // ── Restore ──────────────────────────────────────────────

    /**
     * Sends every subscription and one-time product Play says this Google
     * account owns to Cashier, and returns the merged state. Use it for a
     * "Restore purchases" button and when migrating existing subscribers.
     */
    suspend fun restorePurchases(): CustomerInfo {
        ensureConnected()
        val receipts = ownedPurchases()
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            .flatMap { p -> p.products.map { Receipt(p.purchaseToken, it) } }
        val user = currentUser
        if (receipts.isEmpty()) return customerInfo(forceRefresh = true)
        return backend.restore(user, receipts).also { publish(it, user) }
    }

    private suspend fun ownedPurchases(): List<Purchase> {
        val out = mutableListOf<Purchase>()
        for (type in listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP)) {
            val r = billing.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build())
            if (r.billingResult.responseCode == BillingClient.BillingResponseCode.OK) out += r.purchasesList
        }
        return out
    }

    // ── Identity ─────────────────────────────────────────────

    /**
     * Identifies the current user as [newAppUserId]. Anything bought while
     * anonymous moves to that account; switching between two signed-in users
     * merges nothing.
     */
    suspend fun logIn(newAppUserId: String): CustomerInfo {
        val target = newAppUserId.trim()
        if (target.isEmpty() || Identity.isAnonymous(target)) {
            throw CashierException(0, "invalid_app_user_id", "logIn needs your own, non-empty user id")
        }
        val current = currentUser
        if (current == target) return customerInfo()
        val info = backend.logIn(current, target)
        currentUser = target
        prefs.edit().putString(Identity.IDENTIFIED_KEY, target).apply()
        if (Identity.isAnonymous(current)) Identity.forgetAnonymousId(prefs)
        publish(info, target)
        return info
    }

    /** Signs out: the device becomes a fresh anonymous subscriber. */
    suspend fun logOut(): CustomerInfo {
        if (isAnonymous) throw CashierException(0, "already_anonymous", "logOut called while already anonymous")
        prefs.edit().remove(Identity.IDENTIFIED_KEY).apply()
        Identity.forgetAnonymousId(prefs)
        currentUser = Identity.anonymousId(prefs)
        _customerInfo.value = null
        return customerInfo(forceRefresh = true)
    }
}
