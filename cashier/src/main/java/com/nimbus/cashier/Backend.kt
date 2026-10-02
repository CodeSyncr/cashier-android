package com.nimbus.cashier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * An error from Cashier. [code] is Cashier's machine-readable reason
 * ("invalid_receipt", "receipt_in_use", "store_not_configured", …), or
 * "network" when Cashier could not be reached.
 */
class CashierException(val status: Int, val code: String, message: String) : Exception(message) {
    /** True when retrying later may succeed. */
    val isTransient: Boolean get() = code == "network" || status >= 500 || status == 429
}

/** One Play purchase as the API takes it. */
internal data class Receipt(val token: String, val productId: String) {
    fun toJson(): JSONObject = JSONObject().put("platform", "google").put("token", token).put("product_id", productId)
}

/** A request the SDK sends; the default transport is HttpURLConnection, tests swap it. */
internal fun interface Transport {
    /** Returns (status, body). Throws IOException when the host cannot be reached. */
    fun send(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String>
}

internal object HttpTransport : Transport {
    override fun send(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return status to text
        } finally {
            conn.disconnect()
        }
    }
}

/** Thin client over /api/cashier/v1. */
internal class Backend(
    private val apiKey: String,
    baseUrl: String,
    private val transport: Transport = HttpTransport,
) {
    private val base = baseUrl.trimEnd('/') + "/api/cashier/v1/"

    suspend fun customerInfo(appUserId: String): CustomerInfo =
        CustomerInfo.fromJson(call("GET", "subscribers/${enc(appUserId)}"))

    suspend fun offerings(appUserId: String? = null): WireOfferings =
        WireOfferings.fromJson(call("GET", if (appUserId.isNullOrEmpty()) "offerings" else "offerings?app_user_id=${enc(appUserId)}"))

    /** The offerings of a paywall draft ("Preview in app"). */
    suspend fun paywallPreview(token: String): WireOfferings = WireOfferings.fromJson(call("GET", "paywalls/preview/${enc(token)}"))

    /** A paywall `view` or `close` (204). */
    suspend fun paywallEvent(appUserId: String, paywallId: Long, type: String, experiment: PaywallExperiment?) {
        val body = JSONObject().put("app_user_id", appUserId).put("paywall_id", paywallId).put("type", type)
        if (experiment != null) body.put("experiment_id", experiment.id).put("variant", experiment.variant)
        call("POST", "paywalls/events", body)
    }

    /** An answer given on a paywall's question screen (204). */
    suspend fun paywallResponse(appUserId: String, paywallId: Long, answer: PaywallAnswer) {
        val body = JSONObject().put("app_user_id", appUserId).put("paywall_id", paywallId)
            .put("screen_id", answer.screenId).put("question", answer.question).put("answer", answer.answer)
        call("POST", "paywalls/responses", body)
    }

    suspend fun postReceipt(appUserId: String, receipt: Receipt): CustomerInfo {
        val body = receipt.toJson().put("app_user_id", appUserId)
        return CustomerInfo.fromJson(call("POST", "receipts", body).getJSONObject("subscriber"))
    }

    suspend fun restore(appUserId: String, receipts: List<Receipt>): CustomerInfo {
        val body = JSONObject().put("receipts", JSONArray(receipts.map { it.toJson() }))
        return CustomerInfo.fromJson(call("POST", "subscribers/${enc(appUserId)}/restore", body).getJSONObject("subscriber"))
    }

    suspend fun logIn(from: String, to: String): CustomerInfo =
        CustomerInfo.fromJson(call("POST", "subscribers/${enc(from)}/alias", JSONObject().put("new_app_user_id", to)))

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private suspend fun call(method: String, path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val headers = mutableMapOf(
            "Authorization" to "Bearer $apiKey",
            "Accept" to "application/json",
            "User-Agent" to "NimbusCashier-Android/${Cashier.VERSION}",
        )
        if (body != null) headers["Content-Type"] = "application/json"
        val (status, text) = try {
            transport.send(method, base + path, headers, body?.toString())
        } catch (e: IOException) {
            throw CashierException(0, "network", "Cashier could not be reached: ${e.message}")
        }
        val json = try {
            JSONObject(text.ifEmpty { "{}" })
        } catch (e: Exception) {
            throw CashierException(status, "bad_response", "Cashier sent a response this SDK cannot read")
        }
        if (status !in 200..299) {
            throw CashierException(status, json.optString("error", "http_$status"), json.optString("message"))
        }
        json
    }
}
