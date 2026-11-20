# AdMob NextGen Ads Utility: Implementation Plan

Status: v5. Phases 0 to 7 done: all formats, Compose wrappers, README and GitHub Packages publishing, verified by a release build and a local publish. Remaining: on-device testing and unit tests.
Author: Kazmi Dev

## 1. Goal

A copy-and-use utility package for the Google Mobile Ads **Next-Gen SDK** (`ads-mobile-sdk`).
The user of the utility:

1. Copies the package into their app and adds the dependencies.
2. Calls `AdsManager.initialize(...)` once.
3. Calls every ad function on `AdsManager`. They never see the internal managers.

Two UI flavors: **XML (Views)** and **Jetpack Compose**, sharing one core.

## 2. Principles

| Principle | Meaning |
|---|---|
| One public door | Only `AdsManager`, config, events, errors and UI components are public. Everything else is `internal`. |
| One implementation per concept | Loading, caching, timeout, expiry, single-flight, event mapping are written once in a shared base, not per format. |
| Never lose a loaded ad | An ad that loaded but was not shown is stored in a cache and used later. |
| User never waits more than 8 s | Hard cap, enforced with a cancellable timeout. |
| One full-screen ad at a time | A central coordinator arbitrates between all full-screen formats. |
| Policy first | Every flow is designed against AdMob policies (section 9). |
| Minimal code | No dead code, no repeated reads, no inline comments. Only a "Created by Kazmi Dev" header per class. |

## 2a. Decisions locked

| Topic | Decision |
|---|---|
| Distribution | Published later as a **GitHub Packages library** (AAR, Maven coordinates). Source stays copy-friendly. |
| Package name | `com.kazmi.dev.adsnextgen` (real package, which is what a published library needs). |
| Ad unit ids | **Google test ids by default.** The user passes real ids at `initialize`, or only the app id and then an id with each call. |
| Per-call id | Every preload/show/banner/native call accepts an optional `adUnitId` so different screens can use different ids at runtime. |
| Internet | Checked **inside** the utility. Offline means no request is sent, so match rate is not hurt by doomed requests. |
| Consent (UMP) | Built in. Runs **before** `MobileAds.initialize`. If unavailable, not required, or it errors, it is skipped and ads initialize normally. |
| minSdk | **24** (the Next-Gen SDK's own minimum). Compose artifacts use Compose BOM 2026.11.01. |
| App Open | The user can exclude activities (splash etc.) through `AdsConfig.appOpenExcludedActivities` or `excludeFromAppOpen(...)`. Auto show on foreground uses a preloaded ad only, so the user never waits. Small internal cooldown. |
| Preloading | The SDK's own `*AdPreloader` classes (`start`, `pollAd`, buffer refill and expiry handled by Google). On-demand uses the suspend `load()`. One tiny late-ad slot per ad unit keeps the ad that arrives after an on-demand timeout. |
| SDK ready | Marked ready **immediately** after `MobileAds.initialize` is called, so loads and shows are never blocked while mediation adapters initialize in the background. `AdsConfig.waitForMediation` (default false) switches to waiting for the adapter callback. |
| Ads switch | `AdsManager.isAdsEnabled` (premium users). No per-call purchase flags. |
| SDK version | Latest stable **1.7.0** (your AdsNextGen library uses 1.4.0). |
| API names | Only names that exist in the SDK binary are used. Nothing invented. |

## 3. Package structure

```
admobNextGenAds/                          Android library module
├── build.gradle.kts
└── src/main/
    ├── AndroidManifest.xml
    └── java/com/kazmi/dev/adsnextgen/
        ├── AdsManager.kt                 public facade, initialize, ready state
        ├── AdsConfig.kt                  ids (test defaults) and options
        ├── AdError.kt, AdEvent.kt        shared error and event models
        ├── InterstitialAdManager.kt      interface InterstitialAds + internal manager
        ├── RewardedAdManager.kt
        ├── RewardedInterstitialAdManager.kt
        ├── AppOpenAdManager.kt           foreground observer + AppOpenAds
        ├── FullScreenController.kt       shared logic for all full-screen formats
        ├── FullScreenFormat.kt           tiny per-format adapter + late-ad slot
        ├── AdCoordinator.kt              one full-screen ad at a time, priority
        ├── ConsentManager.kt             UMP flow, runs before SDK init
        ├── ActivityTracker.kt            weak ref to current activity
        └── Connectivity.kt               network state, no request when offline
(Phase 5/6: banner/ and nativead/ packages, plus xml/ and compose/ UI.)
```

Dependencies: `ads-mobile-sdk:1.7.0`, `user-messaging-platform:4.0.0`, `kotlinx-coroutines-android`, `lifecycle-process`, Compose BOM `2026.11.01` (compose folder only).

## 4. Core abstractions

### 4.1 `AdsConfig`
```
AdsConfig(
  appId = TEST_APP_ID,
  interstitialId = TEST_INTERSTITIAL, rewardedId = TEST_REWARDED,
  rewardedInterstitialId = TEST_REWARDED_INTERSTITIAL, appOpenId = TEST_APP_OPEN,
  bannerId = TEST_BANNER, nativeId = TEST_NATIVE,
  requestConsent = true
)
```
Defaults are Google's published test ids, so a fresh integration works without real ids. For release the user passes real ids, or just `appId` and an id per call.
Every show/preload call takes `adUnitId: String? = null` (null means use the config id).

Test ids (verify against Google's docs when coding):
`ca-app-pub-3940256099942544~3347511713` (app), interstitial `/1033173712`, rewarded `/5224354917`, rewarded interstitial `/5354046379`, app open `/9257395921`, adaptive banner `/9214589741`, native `/2247696110`.

### 4.2 `AdsManager` (public facade)
Implements one small interface per format and delegates:
```
object AdsManager :
    InterstitialAds by InterstitialAdManager,
    RewardedAds by RewardedAdManager,
    RewardedInterstitialAds by RewardedInterstitialAdManager,
    AppOpenAds by AppOpenAdManager,
    NativeAds by NativeAdManager,
    BannerAds by BannerAdManager
```
Plus `initialize`, `isInitialized`, `awaitInitialized`, `isFullScreenAdShowing`.

`initialize` order: (1) UMP consent, (2) `MobileAds.initialize`, (3) mark ready. Consent needs an Activity, so `initialize` takes the Application and consent runs on the first resumed Activity (via `ActivityTracker`); if none appears within a short window, or consent is not required or fails, initialization continues without it.

### 4.3 `FullScreenAdManager<T>` (shared base)
All four full-screen formats differ only in: how to load, how to show, expiry time, and extra events. They plug into the base through a tiny adapter:
```
internal interface FullScreenFormat<T> {
    suspend fun load(adUnitId): T?
    fun show(ad: T, activity, events: EventSink)
    val expiryMs: Long
}
```
The base owns: cache, single-flight preload, on-demand wait with timeout, busy flag, coordinator calls, event delivery on the main thread. Each format manager becomes about 30 lines.

### 4.4 Preloading (SDK preloaders) and the late-ad slot
- `preload*` calls `XAdPreloader.start(adUnitId, PreloadConfiguration(request, bufferSize))`. Google keeps the buffer full, refills after every poll, and drops expired ads. `start` is idempotent.
- `showPreloaded*` calls `XAdPreloader.pollAd(adUnitId)`; a hit shows instantly.
- Preloads wanted while offline are remembered and started when the network returns.
- **Late-ad slot:** one entry per ad unit, with load time and format expiry (55 min, App Open 3 h 50 min). It holds only the ad that finished loading after an on-demand timeout, or an ad returned because the activity was closing. `showPreloaded*` checks it before the SDK preloader.

### 4.5 On-demand loading
- `request` is a `scope.async` on `Dispatchers.Main.immediate`, **not a child of the caller**, so cancelling the wait never cancels the network request.
- The wait is `withTimeoutOrNull(timeout)` (1 s to 8 s, default 8 s). A flag separates timeout from load failure.
- Waiting includes the time spent waiting for SDK initialization.

### 4.6 `AdEvent` and `AdError`
```
sealed interface AdEvent {
  Showed, Clicked, Impression, Paid(value),
  Dismissed, Failed(error),
  Rewarded(item)            // rewarded formats only
}
```
`Dismissed` and `Failed` are terminal: exactly one per show call.

`AdError`: `NO_INTERNET`, `PRODUCT_PURCHASED`, `NOT_INITIALIZED`, `AD_FAILED_TO_LOAD`, `AD_LOAD_TIMEOUT`, `AD_NOT_AVAILABLE`, `AD_FAILED_TO_SHOW`, `ANOTHER_AD_SHOWING`, `ACTIVITY_UNAVAILABLE`.

## 5. Loading, timeout and coroutines

The Next-Gen SDK exposes a **suspending** `load()`, so no callback bridge is needed.

| Concern | Design |
|---|---|
| Scope | `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`; state is only touched on main, so no locks. |
| On-demand wait | `withTimeoutOrNull(timeout) { request.await() }`. The timeout is cooperative cancellation of the wait only. |
| Late ad after timeout | A second coroutine awaits the same `Deferred` and stores the ad in the cache. |
| Timeout range | Caller value clamped to 1 s..8 s. Default 8 s. |
| Timeout vs failure | A flag set inside the timeout block separates `AD_LOAD_TIMEOUT` from `AD_FAILED_TO_LOAD`. |
| Preload | `preload(count)`: fills the cache silently up to `count`, no UI, never shows. |
| Show preloaded | Cache hit: show instantly. Miss: `loadOnDemandIfUnavailable = true` (default) falls back to on-demand; `false` returns `AD_NOT_AVAILABLE`. |
| Auto refill | After a preloaded ad is dismissed, one more is preloaded. On-demand never refills. |
| Callback threads | SDK callbacks are re-dispatched onto the main scope before reaching the user. |

## 6. Scenario handling

The coordinator holds one state: `Idle`, `LoadingOnDemand(format)`, `Showing(format)`.

**Priority:** Rewarded / Rewarded Interstitial / Interstitial **>** App Open.

| Scenario | Behavior |
|---|---|
| Two ads loaded, one is showing | The second stays in the cache; `show` returns `ANOTHER_AD_SHOWING` (ignored silently for double taps). |
| Interstitial on-demand load in progress and a second show call arrives | Ignored (single busy flag). |
| Interstitial loading, user goes to Recents, comes back; App Open resume fires | App Open is **skipped** because a higher-priority load or show is active. The App Open ad that loaded is **cached** for a later resume. |
| Interstitial finishes loading while app is in background | Not shown. Stored in the cache. Shown only when the activity is RESUMED. |
| Activity finishing/destroyed at show time | Ad returned to the front of the cache, `Failed(ACTIVITY_UNAVAILABLE)`. |
| Ad dismissed | Coordinator returns to `Idle`; App Open cooldown starts so it does not appear right after an interstitial. |
| Cached ad expired | Dropped on `poll()`, a fresh load starts. |
| Premium user | `isProductPurchased` or a global `AdsManager.adsEnabled = false` short-circuits every format with `PRODUCT_PURCHASED`. |
| No internet | Cache hit still shows; a miss returns `NO_INTERNET` without starting a request. |
| SDK not initialized yet | Loads wait for `awaitInitialized()`; show calls before init wait inside the same 8 s budget. |
| Configuration change / rotation | Nothing holds a strong Activity reference; only a `WeakReference` in `ActivityTracker`. |
| Process death | Caches are memory only and rebuild on next launch. |

## 7. Formats

### 7.1 Interstitial (first slice, written)
`preloadInterstitial`, `showInterstitialOnDemand`, `showPreloadedInterstitial`.

### 7.2 Rewarded and Rewarded Interstitial
Same three functions plus a `Rewarded(item)` event. The reward callback never replaces `Dismissed`. Rewarded interstitial shows only after the user has opted in or been shown a clear notice (policy).

### 7.3 App Open
- Observes `ProcessLifecycleOwner` ON_START plus `ActivityTracker`.
- Shows on foreground only if: enabled, not purchased, no higher-priority ad active, small internal cooldown passed, and the current activity is not the SDK's own `AdActivity`.
- A cached ad is used instantly; otherwise one load with the 8 s cap.
- Never shows on cold start before the app has real content (policy).
- Exposes `AppOpenAds.showAppOpenOnDemand` and `preloadAppOpen` for manual control.

### 7.4 Banner
Sizes: adaptive anchored, inline adaptive, collapsible, plus fixed (banner 320x50, large banner, medium rectangle 300x250, full banner, leaderboard).
- XML: `BannerAdView` custom view (attributes: size type, ad unit id).
- Compose: `BannerAd(size = ..., modifier)` composable.
- Lifecycle bound: pause/resume/destroy follow the host lifecycle. No manual refresh faster than policy allows (use the AdMob refresh setting).

### 7.5 Native
Templates: **Small**, **Medium**, **Full Screen**.
- `NativeAdManager`: preload queue with expiry; the ad is removed from the queue on use and destroyed with the view.
- XML: `NativeAdSmallView`, `NativeAdMediumView`, `NativeAdFullScreenView` with layouts.
- Compose: `NativeAdSmall`, `NativeAdMedium`, `NativeAdFullScreen`.
- All templates must render the required assets (headline, ad badge, media or icon, call to action, AdChoices).
- Loading state is a placeholder skeleton; on failure the slot collapses.

## 8. Public API sketch

```
AdsManager.initialize(application, AdsConfig(...)) { }

AdsManager.preloadInterstitial()
AdsManager.showInterstitialOnDemand(activity) { event -> }
AdsManager.showPreloadedInterstitial(activity) { event -> }

same trio for Rewarded, RewardedInterstitial, AppOpen

AdsManager.preloadNative(count)
// XML: <BannerAdView/>, <NativeAdMediumView/>
// Compose: BannerAd(...), NativeAdMedium(...)
```

## 9. Policy checklist (verify against current AdMob policy while building)

- Never auto-click, never encourage clicks, never place ads where accidental clicks are likely.
- Interstitials only at natural transition points; never on app launch before content, never on exit.
- Do not show an ad while another full-screen ad is showing.
- Do not show an expired ad; do not reuse a shown ad.
- Do not load or show ads while the app is in the background.
- Native ads: show the "Ad" attribution, AdChoices, and all required assets; no obscuring.
- Banners: no overlap with UI, no manipulation of size to hide content.
- Rewarded: user opt-in, clear reward description.
- Use test ad units in debug builds; never click your own live ads.
- Consent (UMP): request consent before loading ads where required; expose it as an optional step in `initialize`.

## 10. Build phases

| Phase | Deliverable | Status |
|---|---|---|
| 0 | Spike: exact Next-Gen SDK 1.7.0 API for every format | Done (signature dump checked) |
| 1 | Core: config, error and event models, coordinator, activity tracker, connectivity | Done, compiles |
| 2 | Shared full-screen controller, Interstitial | Done, compiles |
| 3 | Rewarded and Rewarded Interstitial | Done, compiles |
| 4 | App Open, coordinator priority, excluded activities | Done, compiles; scenarios need device testing |
| 5 | Banner (XML and Compose, all sizes, collapsible) | Done, compiles; collapsible needs device testing |
| 6 | Native (XML and Compose, three templates) | Done, compiles; visual check pending |
| 7 | Consent (UMP), README, publishing, wrapper | Done; release build and local publish verified |
| 8 | On-device verification of the scenario matrix in section 6 | Pending (needs a real device) |
| 9 | Unit tests for the shared controller and coordinator | Pending |

Each phase ends with a commit on the feature branch and a short review with you before the next.

## 11. Code standards

- Header comment on every class: `Created by Kazmi Dev` plus one line of purpose. No other comments unless essential.
- `internal` by default; public only what the user needs.
- No duplicated loading logic: formats only supply load/show/expiry.
- No Android resources referenced from core; UI folders own their layouts.
- Naming: `preloadX`, `showXOnDemand`, `showPreloadedX` for every format.

## 12. Resolved points

1. Package: `com.kazmi.dev.adsnextgen`.
2. Preloading: SDK preloaders.
3. SDK ready: immediately after initialize, option `waitForMediation`.
4. App Open: user-provided excluded activities.

## 13. SDK facts verified against ads-mobile-sdk 1.7.0 (real binary, minSdk 24)

Full signature dump: `reference/ads-mobile-sdk-1.7.0-api.txt` (kept locally, not committed).
Root package: `com.google.android.libraries.ads.mobile.sdk`.

| Area | Exact API |
|---|---|
| Init | `MobileAds.initialize(Context, InitializationConfig)` and `(…, OnAdapterInitializationCompleteListener)`; `MobileAds.isInitialized()`; `InitializationConfig.Builder(appId)` with `setRequestConfiguration`, `disableMediationAdapterInitialization`; `RequestConfiguration.Builder().setTestDeviceIds(...)` |
| Request | `AdRequest.Builder(adUnitId).build()` (full-screen formats) |
| Load result | `AdLoadResult.Success.ad` / `AdLoadResult.Failure.error` (`LoadAdError`, codes `NO_FILL`, `NETWORK_ERROR`, `TIMEOUT`, `CANCELLED`, ...) |
| Suspend load | `InterstitialAd.load(request)`, `RewardedAd.load`, `RewardedInterstitialAd.load`, `AppOpenAd.load`, `BannerAd.load(BannerAdRequest)`, `NativeAdLoader.load(NativeAdRequest)` |
| Callback load | `load(request, AdLoadCallback)` with `onAdLoaded` / `onAdFailedToLoad(LoadAdError)` |
| Full-screen events | `AdEventCallback`: `onAdShowedFullScreenContent`, `onAdDismissedFullScreenContent`, `onAdFailedToShowFullScreenContent(FullScreenContentError)`, `onAdImpression`, `onAdClicked`, `onAdPaid(AdValue)`. `FullScreenContentError.ErrorCode`: `INTERNAL_ERROR`, `AD_REUSED`, `APP_NOT_FOREGROUND`, `MEDIATION_SHOW_ERROR`, `H5_SHOW_AD_NOT_LOADED` |
| Show | `InterstitialAd.show(Activity)`, `AppOpenAd.show(Activity)`, `RewardedAd.show(Activity, OnUserEarnedRewardListener)`, `RewardedInterstitialAd.show(Activity, OnUserEarnedRewardListener)`; `RewardItem.getType()/getAmount()` |
| Preloaders | `XAdPreloader.start(preloadId, PreloadConfiguration(request, bufferSize), PreloadCallback)`, `pollAd`, `isAdAvailable`, `getNumAdsAvailable`, `destroy`, `destroyAll`; `PreloadCallback.onAdPreloaded / onAdsExhausted / onAdFailedToPreload` |
| Banner | `BannerAdRequest.Builder(adUnitId, AdSize)`; sizes `AdSize.BANNER`, `FULL_BANNER`, `LARGE_BANNER`, `LEADERBOARD`, `MEDIUM_RECTANGLE`, `FLUID`; adaptive: `getCurrentOrientationAnchoredAdaptiveBannerAdSize(Context, width)`, `getLargeAnchoredAdaptiveBannerAdSize`, `getCurrentOrientationInlineAdaptiveBannerAdSize`, `getInlineAdaptiveBannerAdSize(width, maxHeight)`; host view `AdView` with `registerBannerAd(BannerAd, Activity)` and `destroy()`; `BannerAdRefreshCallback`; `BannerAd.isCollapsible()` |
| Collapsible | No dedicated builder method. Collapsible is requested through `setGoogleExtrasBundle` (extra key `collapsible` = `bottom` or `top`); to be confirmed on a device in the spike |
| Native | `NativeAdRequest.Builder(adUnitId, listOf(NativeAd.NativeAdType.NATIVE))`, `setMediaAspectRatio`, `setAdChoicesPlacement`, `setVideoOptions`; result `NativeAdLoadResult.NativeAdSuccess.ad` / `.Failure.error`; view `NativeAdView` with `headlineView`, `bodyView`, `callToActionView`, `iconView`, `advertiserView`, `starRatingView`, `storeView`, `priceView`, `adChoicesView`, `registerNativeAd(NativeAd, MediaView)`; `MediaView`; `NativeAd.destroy()` |
| UMP 4.0.0 (minSdk 23) | `UserMessagingPlatform.getConsentInformation(ctx)`, `ConsentInformation.requestConsentInfoUpdate(activity, ConsentRequestParameters, onSuccess, onFailure)`, `UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity, listener)`, `ConsentInformation.canRequestAds()`, `showPrivacyOptionsForm` |

### Corrections this check forces on the current code
- `AppOpenAdManager` / `BannerAdManager` in the repo are old-style and will be rewritten on the new core.
- The interstitial slice already uses real names (`InterstitialAd.load(request)` suspend, `AdLoadResult.Success`, `InterstitialAdEventCallback`, `AdRequest.Builder`), so it needs only minor changes to join the shared base.
- The SDK itself reports `APP_NOT_FOREGROUND` and `AD_REUSED` show errors; the coordinator should map them to our `AdError` instead of ignoring them.
