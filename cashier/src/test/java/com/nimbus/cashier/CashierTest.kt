package com.nimbus.cashier

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class CashierTest {
    private fun subscriber(id: String, premium: Boolean) = """
        {"subject":"$id","requested_at":"2026-09-27T09:57:08.80715+05:30",
         "entitlements":{"premium":{"id":"premium","active":$premium,"will_renew":true,"period_type":"grace",
           "product_id":"pro_monthly","source":"purchase","expires_at":"2026-10-27T04:27:08.296621Z"}},
         "active_entitlement_ids":${if (premium) "[\"premium\"]" else "[]"},"active_product_ids":[],
         "latest_expires_at":"0001-01-01T00:00:00Z"}
    """.trimIndent()

    @Test fun decodesCustomerInfo() {
        val info = CustomerInfo.fromJson(JSONObject(subscriber("u1", true)))
        assertEquals("u1", info.appUserId)
        assertTrue(info.isEntitledTo("premium"))
        assertFalse(info.isEntitledTo("gold"))
        val prem = info.entitlements.getValue("premium")
        assertTrue(prem.isInGracePeriod)
        assertEquals(1793075228L, prem.expirationDate!!.time / 1000)
        assertNotNull(info.requestDate)
        assertNull("Go's zero time is 'never'", info.latestExpirationDate)
    }

    @Test fun parsesDates() {
        assertEquals(CashierDates.parse("2026-10-04T09:57:08Z")!!.time, CashierDates.parse("2026-10-04T15:27:08+05:30")!!.time)
        assertEquals(CashierDates.parse("2026-10-04T09:57:08.123Z")!!.time, CashierDates.parse("2026-10-04T09:57:08.123456789Z")!!.time)
        assertNull(CashierDates.parse(""))
        assertNull(CashierDates.parse(null))
    }

    @Test fun decodesOfferings() {
        val w = WireOfferings.fromJson(JSONObject("""
            {"current_offering":"launch","offerings":[{"id":"launch","description":"Launch","packages":[
              {"id":"${'$'}annual","product":{"id":"pro_annual","period_months":12,"entitlements":["premium"],"google_product_id":"pro_annual_play"}}]}]}
        """))
        assertEquals("launch", w.current)
        val (id, product) = w.offerings.single().packages.single()
        assertEquals("\$annual", id)
        assertTrue(product.isSubscription)
        assertEquals("pro_annual_play", product.googleProductId)
        assertNull(product.appleProductId)
    }

    @Test fun obfuscatedAccountIdFitsPlay() {
        val a = Identity.obfuscatedAccountId("user_42")
        assertEquals(64, a.length)
        assertEquals(a, Identity.obfuscatedAccountId("user_42"))
        assertFalse(a.contains("user"))
    }

    @Test fun backendPostsReceiptsAndMapsErrors() = runTest {
        val sent = mutableListOf<Triple<String, String, String?>>()
        var answer = 200 to """{"subscriber":${subscriber("user_1", true)}}"""
        val transport = Transport { method, url, headers, body ->
            assertEquals("Bearer cshr_pub_test", headers["Authorization"])
            sent += Triple(method, url, body)
            answer
        }
        val backend = Backend("cshr_pub_test", "https://cashier.test/", transport)
        val info = backend.postReceipt("user_1", Receipt("ptoken", "pro_monthly_play"))
        assertTrue(info.isEntitledTo("premium"))
        val (method, url, body) = sent.single()
        assertEquals("POST", method)
        assertEquals("https://cashier.test/api/cashier/v1/receipts", url)
        val json = JSONObject(body!!)
        assertEquals("google", json.getString("platform"))
        assertEquals("ptoken", json.getString("token"))
        assertEquals("pro_monthly_play", json.getString("product_id"))
        assertEquals("user_1", json.getString("app_user_id"))

        answer = 409 to """{"error":"receipt_in_use","message":"belongs to another user"}"""
        try {
            backend.postReceipt("user_1", Receipt("ptoken", "pro_monthly_play"))
            fail("expected an error")
        } catch (e: CashierException) {
            assertEquals("receipt_in_use", e.code)
            assertFalse(e.isTransient)
        }

        val offline = Backend("cshr_pub_test", "https://cashier.test", Transport { _, _, _, _ -> throw IOException("down") })
        try {
            offline.customerInfo("\$anon:1")
            fail("expected an error")
        } catch (e: CashierException) {
            assertTrue(e.isTransient)
        }
    }

    @Test fun aliasEncodesTheAnonymousId() = runTest {
        var seen = ""
        val backend = Backend("k", "https://cashier.test", Transport { _, url, _, body ->
            seen = url
            assertEquals("user_9", JSONObject(body!!).getString("new_app_user_id"))
            200 to subscriber("user_9", false)
        })
        assertEquals("user_9", backend.logIn("\$anon:abc", "user_9").appUserId)
        assertEquals("https://cashier.test/api/cashier/v1/subscribers/%24anon%3Aabc/alias", seen)
    }
}
