package com.nimbus.cashier

import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.UUID

/** App user ids, and the account id Play attaches to each purchase. */
internal object Identity {
    const val ANONYMOUS_PREFIX = "\$anon:"
    private const val ANON_KEY = "anonymous_app_user_id"
    const val IDENTIFIED_KEY = "app_user_id"

    fun isAnonymous(id: String) = id.startsWith(ANONYMOUS_PREFIX)

    fun anonymousId(prefs: SharedPreferences): String {
        prefs.getString(ANON_KEY, null)?.takeIf { isAnonymous(it) }?.let { return it }
        val id = ANONYMOUS_PREFIX + UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString(ANON_KEY, id).apply()
        return id
    }

    fun forgetAnonymousId(prefs: SharedPreferences) = prefs.edit().remove(ANON_KEY).apply()

    /**
     * Play's obfuscatedAccountId: at most 64 characters and not personal
     * data, so the app user id is hashed (SHA-256, hex = exactly 64).
     */
    fun obfuscatedAccountId(appUserId: String): String =
        MessageDigest.getInstance("SHA-256").digest(appUserId.toByteArray()).joinToString("") { "%02x".format(it) }
}
