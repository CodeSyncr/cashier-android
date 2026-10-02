package com.nimbus.cashier

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Shows an offering's paywall — designed in the Cashier console — natively,
 * with Google Play prices, and buys through Cashier.
 *
 * ```kotlin
 * PaywallView(
 *     onPurchaseCompleted = { info -> if (info.isEntitledTo("premium")) close() },
 *     onDismiss = { close() },
 * )
 * ```
 *
 * With no offering given it loads the current one. If that offering has no
 * published paywall, [fallback] is shown instead (a plain list by default).
 * Must be hosted in an Activity (purchases need one). Sends the paywall's
 * `view` event once, `close` when dismissed without a purchase, and every
 * answer given on a question screen to Cashier.
 *
 * @param locale the locale to show the paywall's translations in (a BCP-47
 *   tag like "es" or "pt-BR"); null uses the device's.
 * @param onDismiss the close button (or a button with action "close", or
 *   Back); null shows no close button.
 * @param onAnswer an answer the user gave (after Cashier records it).
 */
@Composable
fun PaywallView(
    offering: Offering? = null,
    modifier: Modifier = Modifier,
    locale: String? = null,
    onPurchaseCompleted: (CustomerInfo) -> Unit = {},
    onRestoreCompleted: (CustomerInfo) -> Unit = {},
    onDismiss: (() -> Unit)? = null,
    onAnswer: (PaywallAnswer) -> Unit = {},
    fallback: @Composable (Offering?) -> Unit = { DefaultPaywallFallback(it, onDismiss) },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember(offering) { mutableStateOf(offering) }
    var views by remember(offering) { mutableStateOf<List<PaywallPackageView>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var purchasing by remember { mutableStateOf(false) }
    var viewSent by rememberSaveable { mutableStateOf(false) }
    var purchased by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(offering) {
        try {
            val o = offering ?: Cashier.shared.offerings().current
            views = PaywallPricing.views(o?.availablePackages ?: emptyList())
            loaded = o
        } catch (e: Exception) {
            failed = true
        }
    }

    val o = loaded
    val doc = o?.paywall
    val localized = remember(doc, locale) { doc?.localize(locale ?: PaywallLocalization.deviceLocale()) }
    val analytics = if (Cashier.isConfigured) Cashier.shared.paywallAnalytics else null

    LaunchedEffect(o, doc) {
        if (o != null && doc != null && !viewSent) {
            viewSent = true
            analytics?.event(o, "view")
        }
    }

    val close: (() -> Unit)? = onDismiss?.let { dismiss ->
        {
            if (o != null && doc != null && !purchased) analytics?.event(o, "close")
            dismiss()
        }
    }
    if (close != null) BackHandler { close() }

    fun alert(message: String) {
        runCatching { AlertDialog.Builder(context).setTitle("Purchase").setMessage(message).setPositiveButton("OK", null).show() }
    }

    when {
        o != null && localized != null && views != null -> PaywallContent(
            doc = localized,
            appName = o.appName,
            packages = views!!,
            modifier = modifier,
            isPurchasing = purchasing,
            onPurchase = { view ->
                val pkg = o.availablePackages.firstOrNull { it.identifier == view.id }
                val activity = context.findActivity()
                if (pkg != null && activity != null && !purchasing) {
                    purchasing = true
                    scope.launch {
                        try {
                            val result = Cashier.shared.purchase(activity, pkg)
                            val info = result.customerInfo
                            when {
                                result.isPending -> alert("Your purchase is waiting for approval. You'll get access as soon as it goes through.")
                                info != null && !result.userCancelled && result.purchase != null -> {
                                    purchased = true
                                    onPurchaseCompleted(info)
                                }
                            }
                        } catch (e: Exception) {
                            alert(e.message ?: "The purchase could not be completed.")
                        } finally {
                            purchasing = false
                        }
                    }
                }
            },
            onRestore = {
                if (!purchasing) {
                    purchasing = true
                    scope.launch {
                        try {
                            val info = Cashier.shared.restorePurchases()
                            onRestoreCompleted(info)
                            if (info.activeEntitlementIds.isEmpty()) alert("No purchases to restore were found for this Google account.")
                        } catch (e: Exception) {
                            alert(e.message ?: "Purchases could not be restored.")
                        } finally {
                            purchasing = false
                        }
                    }
                }
            },
            onClose = close,
            onAnswer = { answer ->
                analytics?.answer(o, answer)
                onAnswer(answer)
            },
        )
        (o != null && views != null) || failed -> fallback(o)
        else -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PaywallSpinner(Color.Gray, 32.dp) }
    }
}

/** Shown when an offering has no published paywall: its packages as a plain list. */
@Composable
fun DefaultPaywallFallback(offering: Offering?, onDismiss: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().background(Color.White).windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onDismiss != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                PaywallIcon("xmark", 20.dp, Color.Gray, Modifier.plainClickable(onClick = onDismiss))
            }
        }
        (offering?.availablePackages ?: emptyList()).forEach { pkg ->
            Row(Modifier.fillMaxWidth()) {
                PlainText(pkg.productDetails.name.ifEmpty { pkg.productDetails.title }, 16.0, "system", Modifier.weight(1f), color = Color.Black)
                PlainText(pkg.formattedPrice ?: "", 16.0, "system", color = Color.Gray)
            }
        }
    }
}

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * What a presented paywall reports. Every method is optional. Kept small
 * and stable for bridges (React Native, Flutter).
 */
interface PaywallListener {
    fun onPurchaseCompleted(customerInfo: CustomerInfo) {}
    fun onRestoreCompleted(customerInfo: CustomerInfo) {}
    fun onAnswer(answer: PaywallAnswer) {}
    /** The paywall closed, for whatever reason (purchase, restore, close, Back). */
    fun onDismiss() {}
    /** Loading failed before anything could be shown (the paywall then closes). */
    fun onError(error: Exception) {}
}

/** A presented paywall's arguments, kept in memory while its activity lives. */
internal class PaywallSession(
    val offeringId: String?,
    val offering: Offering?,
    val locale: String?,
    val requiredEntitlement: String?,
    val listener: PaywallListener,
)

internal object PaywallSessions {
    private val sessions = HashMap<String, PaywallSession>()
    fun put(s: PaywallSession): String = UUID.randomUUID().toString().also { synchronized(sessions) { sessions[it] = s } }
    fun get(id: String?): PaywallSession? = id?.let { synchronized(sessions) { sessions[it] } }
    fun remove(id: String?) { id?.let { synchronized(sessions) { sessions.remove(it) } } }
}

/**
 * The full-screen host for [Cashier.presentPaywall]. Declared in the
 * library's manifest; you never start it yourself.
 */
class PaywallActivity : ComponentActivity() {
    private var sessionId: String? = null
    private var dismissed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        sessionId = intent.getStringExtra(EXTRA_SESSION)
        val session = PaywallSessions.get(sessionId)
        if (session == null || !Cashier.isConfigured) {
            // The process was recreated and the callbacks are gone.
            finish()
            return
        }
        setContent {
            var offering by remember { mutableStateOf(session.offering) }
            var ready by remember { mutableStateOf(session.offering != null || session.offeringId == null) }
            LaunchedEffect(Unit) {
                if (!ready) {
                    try {
                        offering = Cashier.shared.offerings()[session.offeringId!!]
                            ?: throw CashierException(404, "not_found", "No offering ${session.offeringId}")
                        ready = true
                    } catch (e: Exception) {
                        session.listener.onError(e)
                        finishPaywall()
                    }
                }
            }
            if (ready) {
                val entitlement = session.requiredEntitlement
                PaywallView(
                    offering = offering,
                    locale = session.locale,
                    onPurchaseCompleted = { info ->
                        session.listener.onPurchaseCompleted(info)
                        if (entitlement == null || info.isEntitledTo(entitlement)) finishPaywall()
                    },
                    onRestoreCompleted = { info ->
                        session.listener.onRestoreCompleted(info)
                        if (entitlement != null && info.isEntitledTo(entitlement)) finishPaywall()
                    },
                    onDismiss = { finishPaywall() },
                    onAnswer = { session.listener.onAnswer(it) },
                )
            } else {
                Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) { PaywallSpinner(Color.Gray, 32.dp) }
            }
        }
    }

    private fun finishPaywall() {
        if (!dismissed) {
            dismissed = true
            PaywallSessions.get(sessionId)?.listener?.onDismiss()
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            if (!dismissed) {
                dismissed = true
                PaywallSessions.get(sessionId)?.listener?.onDismiss()
            }
            PaywallSessions.remove(sessionId)
        }
    }

    internal companion object {
        const val EXTRA_SESSION = "com.nimbus.cashier.paywall.session"
    }
}

/**
 * Presents a paywall full screen, and purchases through Google Play Billing.
 *
 * With [requiredEntitlement], it first checks the user's entitlements and
 * shows nothing (calling [PaywallListener.onDismiss] straight away) when
 * they already have it; after a purchase or restore that grants it the
 * paywall closes by itself.
 *
 * @param offeringId the offering to show; null is the current one.
 * @param offering an offering already loaded (e.g. from [Cashier.previewOffering]); wins over [offeringId].
 * @param locale the translations to show ("es", "pt-BR"); null is the device's.
 */
@JvmOverloads
fun Cashier.presentPaywall(
    activity: Activity,
    requiredEntitlement: String? = null,
    offeringId: String? = null,
    offering: Offering? = null,
    locale: String? = null,
    listener: PaywallListener = object : PaywallListener {},
) {
    val start = {
        val id = PaywallSessions.put(PaywallSession(offeringId, offering, locale, requiredEntitlement, listener))
        activity.startActivity(Intent(activity, PaywallActivity::class.java).putExtra(PaywallActivity.EXTRA_SESSION, id))
    }
    if (requiredEntitlement == null) {
        start()
        return
    }
    CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
        val entitled = runCatching { customerInfo().isEntitledTo(requiredEntitlement) }.getOrDefault(false)
        if (entitled) listener.onDismiss() else start()
    }
}
