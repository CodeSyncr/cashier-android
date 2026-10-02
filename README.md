# Nimbus Cashier for Android

Google Play subscriptions and in-app purchases backed by [Nimbus Cashier](https://nimbusgo.space/docs/cashier-cloud). Your app buys through Play Billing; Cashier verifies every purchase with Google, keeps it current through renewals, refunds and account holds, and answers one question: *is this user entitled to premium?*

**Requirements:** minSdk 24, Java 17, Play Billing 7 and Jetpack Compose UI (included).

## Install

Add JitPack to your repositories (`settings.gradle.kts`):

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

and the dependency (`app/build.gradle.kts`):

```kotlin
dependencies {
    implementation("com.github.CodeSyncr:cashier-android:1.0.0")
}
```

## Use

```kotlin
import com.nimbus.cashier.Cashier

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // The PUBLIC key from your Cashier app's Settings & keys.
        Cashier.configure(this, apiKey = "cshr_pub_…")
    }
}

// After your own sign-in:
Cashier.shared.logIn(user.id)

// Gate a feature:
if (Cashier.shared.customerInfo().isEntitledTo("premium")) unlockPremium()

// Buy a package from the current offering (from an Activity):
val pkg = Cashier.shared.offerings().current!!.availablePackages.first()
val result = Cashier.shared.purchase(activity, pkg)

// Restore:
Cashier.shared.restorePurchases()

// React to changes:
Cashier.shared.customerInfoFlow.collect { info -> /* … */ }
```

## Paywalls

Design a paywall in the Cashier console (Paywalls → attach it to an offering) and the SDK draws it natively with Jetpack Compose — the same document the web SDK and the iOS SDK render — with Google Play prices, and buys through Play Billing and Cashier.

### Present it full screen

```kotlin
import com.nimbus.cashier.PaywallListener
import com.nimbus.cashier.presentPaywall

Cashier.shared.presentPaywall(
    activity,
    requiredEntitlement = "premium",   // shows nothing if the user already has it
    listener = object : PaywallListener {
        override fun onPurchaseCompleted(customerInfo: CustomerInfo) { /* unlock */ }
        override fun onDismiss() { /* back to the app */ }
    },
)
```

`presentPaywall(activity, requiredEntitlement = null, offeringId = null, offering = null, locale = null, listener)`:

| Parameter | |
|---|---|
| `activity` | Any `Activity`; the paywall opens in the SDK's own `PaywallActivity` (declared in the library manifest). |
| `requiredEntitlement` | When set: checked first (nothing is shown and `onDismiss` is called when the user has it), and the paywall closes itself once a purchase or restore grants it. |
| `offeringId` / `offering` | Which offering to show; default the current one. `offering` takes an already loaded one (e.g. `Cashier.shared.previewOffering(token)`). |
| `locale` | The translation to show (`"es"`, `"pt-BR"`); default the device's. |
| `listener` | `PaywallListener` — `onPurchaseCompleted(CustomerInfo)`, `onRestoreCompleted(CustomerInfo)`, `onAnswer(PaywallAnswer)`, `onDismiss()`, `onError(Exception)`; every method optional. |

This API is what the React Native bridge calls; it is kept stable.

### Or embed it in your own Compose UI

```kotlin
PaywallView(
    offering = null,                    // null: the current offering
    locale = null,                      // null: the device's
    onPurchaseCompleted = { info -> if (info.isEntitledTo("premium")) close() },
    onRestoreCompleted = { info -> },
    onDismiss = { close() },            // null: no close button
    onAnswer = { answer -> if (answer.answer == "yes") askForNotifications() },
)
```

`PaywallView` must sit in an Activity (purchases need one). An offering without a published paywall shows `fallback` (by default a plain list of packages). `PaywallContent(doc, appName, packages, onPurchase, …)` draws a document with your own purchase handling.

### What it sends

- `GET /offerings?app_user_id=…` — the id lets Cashier assign the user to a running paywall experiment (`Offerings.experiment`; `current` already reflects the variant).
- `POST /paywalls/events` — `view` once when shown, `close` when dismissed without a purchase (with the experiment and variant when enrolled).
- `POST /paywalls/responses` — each answer from a question screen (Feedback, Marketing consent), also handed to `onAnswer` as `PaywallAnswer(screenId, question, answer)`.

All three are fire-and-forget: they never block the paywall and their errors are ignored. Nothing is sent for a preview paywall.

### Translations

A paywall carries its own translations (`default_locale`, `locales`, `strings`). The SDK picks one the way the server does: an exact match of the requested tag, else the first translation in the same language (unless that is the paywall's own language), else the original texts; missing keys keep the original. `PaywallDoc.localize(tag)` applies it yourself.

### Rendering notes

- Every block type and variant of the schema is drawn: headers, titles, text, images, video, art, icons, stacks and custom boxes (plan cards with badges and `look` / `look_selected`), packages, CTA, buttons (next / back / screen / close / restore / url), footer, sheets, tabs, switch, carousels, countdowns, timelines, social proof, testimonials, features and awards; screen themes, gradient/image/video backgrounds, effects and entrances.
- No extra dependencies beyond Compose UI/foundation: icons are the web SDK's stroke icons, images load through a small built-in loader (https only; http only from localhost), and video plays muted and looping through the platform `MediaPlayer` (the poster shows until the first frame, or instead of a clip that cannot play).
- Sizes are in points as on iOS and the web: text does not scale with the system font size. Motion is off when the system's animator duration scale is 0.
- Differences from iOS: Android has no rounded system font ("rounded" draws the default); shadows cast by a block's content (a look shadow on a block with no fill) are not drawn, and shadows / art blur need Android 9+.

The full guide — console setup, Play Console, testing and going live — is at **https://nimbusgo.space/docs/cashier-android**.

## Develop

```sh
./gradlew :cashier:testDebugUnitTest
```

## Release

1. Update `CHANGELOG.md`.
2. Tag the commit (`git tag 1.0.1 && git push --tags`).
3. Open https://jitpack.io/#CodeSyncr/cashier-android and build the tag (or just request it — the first request builds it). The version is the tag.

Moving to Maven Central later means adding signing and a Sonatype repository to the `publishing` block in `cashier/build.gradle.kts`; the coordinates change to your own group id.

## License

MIT
