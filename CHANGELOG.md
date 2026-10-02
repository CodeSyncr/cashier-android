# Changelog

## 1.1.0 (2026-10-02)

- Paywalls: the console's paywall documents drawn natively with Jetpack Compose — every block type and variant, multi-screen flows, screen themes, backgrounds (gradient, image, video), effects and entrances, plan cards, badges, countdowns, carousels, tabs, switches and sheets. `PaywallView` (Compose), `PaywallContent`, and `Cashier.presentPaywall(activity, requiredEntitlement, …)` with a `PaywallListener`.
- Offerings carry the paywall (`Offering.paywall`, `paywallId`, `appName`) and the experiment the user is in (`Offerings.experiment`); the request sends `app_user_id`.
- Paywall `view` / `close` events and answers from question screens (`POST /paywalls/events`, `/paywalls/responses`), fire-and-forget; `onAnswer` callback.
- Paywall translations (`PaywallDoc.localize`), resolved like the server.
- `Cashier.previewOffering(token)` for the editor's "Preview in app".
- New dependencies: Compose UI + foundation (BOM 2024.06.00) and activity-compose.

## 1.0.0

First release.

- Google Play Billing 7 purchases verified by your Nimbus Cashier app; entitlements, customer info (cached, as a Flow), log in / log out, restore.
- Offerings and packages from the console, with subscription offer selection.
