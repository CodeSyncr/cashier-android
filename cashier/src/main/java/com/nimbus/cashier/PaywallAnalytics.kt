package com.nimbus.cashier

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Paywall `view` / `close` events and answers, sent fire-and-forget: they
 * never block the UI and their errors are ignored. Nothing is sent for a
 * paywall the server has no id for (a preview).
 */
internal class PaywallAnalytics(
    private val backend: Backend,
    private val scope: CoroutineScope,
    private val appUserId: () -> String,
) {
    fun event(offering: Offering, type: String): Job? {
        val id = offering.paywallId ?: return null
        val user = appUserId()
        return scope.launch { runCatching { backend.paywallEvent(user, id, type, offering.experiment) } }
    }

    fun answer(offering: Offering, answer: PaywallAnswer): Job? {
        val id = offering.paywallId ?: return null
        val user = appUserId()
        return scope.launch { runCatching { backend.paywallResponse(user, id, answer) } }
    }
}
