# Admob Ads NextGen Implementation

A ready-made utility for the **Google Mobile Ads Next-Gen SDK** (`ads-mobile-sdk`). Add the dependency, call `AdsManager.initialize(...)` once, and call every ad function on `AdsManager`. It works from XML (Views) and from Jetpack Compose.

Created by Kazmi Dev.

## Contents
- [What it gives you](#what-it-gives-you)
- [Install](#install)
- [Quick start](#quick-start)
- [Initialization and configuration](#initialization-and-configuration)
- [Full-screen ads](#full-screen-ads)
- [App Open](#app-open)
- [Banner](#banner)
- [Native](#native)
- [Events and errors](#events-and-errors)
- [How loading works](#how-loading-works)
- [Consent (UMP)](#consent-ump)
- [Premium users](#premium-users)
- [Project layout](#project-layout)
- [Status](#status)

## What it gives you

| Format | Functions on `AdsManager` / UI |
|---|---|
| Interstitial | `preloadInterstitial`, `showInterstitialOnDemand`, `showPreloadedInterstitial` |
| Rewarded | `preloadRewarded`, `showRewardedOnDemand`, `showPreloadedRewarded` |
| Rewarded Interstitial | `preloadRewardedInterstitial`, `showRewardedInterstitialOnDemand`, `showPreloadedRewardedInterstitial` |
| App Open | `preloadAppOpen`, `showAppOpenOnDemand`, `showPreloadedAppOpen`, automatic on foreground |
| Banner | `AdsBannerView` (XML) / `BannerAd` (Compose): adaptive, inline adaptive, collapsible, fixed sizes |
| Native | `AdsNativeView` (XML) / `NativeAdSmall`, `NativeAdMedium`, `NativeAdFullScreen` (Compose), `preloadNative` |

Built in: UMP consent, offline check (no request is sent without internet), one full-screen ad at a time, an 8 second load cap, Google test ids by default, and no ad lost when a load finishes late.

Requirements: minSdk 24, Ads SDK `1.7.0`.

## Install

Published through [JitPack](https://jitpack.io). No token or account is needed.

`settings.gradle.kts`:
```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

`app/build.gradle.kts`:
```kotlin
dependencies {
    implementation("com.github.kazmi-dev.Admob-Ads-NextGen-Implementation:admob-ads-nextgen:2.0.0")
    implementation("com.github.kazmi-dev.Admob-Ads-NextGen-Implementation:admob-ads-nextgen-compose:2.0.0") // Compose only
}
```
The Compose artifact already includes the core one. The version is the git tag of this repository.

## Quick start

Application class:
```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AdsManager.initialize(
            application = this,
            config = AdsConfig(
                appId = "ca-app-pub-xxx~yyy",
                interstitialId = "ca-app-pub-xxx/zzz",
                appOpenExcludedActivities = setOf(SplashActivity::class.java)
            )
        ) { AdsManager.preloadInterstitial() }
    }
}
```
Show an ad and continue when it ends:
```kotlin
AdsManager.showPreloadedInterstitial(activity) { event ->
    if (event is AdEvent.Dismissed || event is AdEvent.Failed) navigateNext()
}
```

## Initialization and configuration

`AdsManager.initialize(application, config, onInitialized)` runs consent (when enabled), then starts the SDK. Call it once; later calls only wait for readiness.

| `AdsConfig` field | Default | Meaning |
|---|---|---|
| `appId` | Google test app id | AdMob app id |
| `interstitialId`, `rewardedId`, `rewardedInterstitialId`, `appOpenId`, `bannerId`, `nativeId` | Google test ids | Default ad unit per format |
| `requestConsent` | `true` | Run the UMP flow before starting the SDK |
| `waitForMediation` | `false` | `false`: ready right after `initialize` is called, ads are never blocked while mediation adapters start. `true`: wait for the adapter callback |
| `testDeviceIds` | empty | Test device ids |
| `appOpenEnabled` | `true` | Automatic App Open on foreground |
| `appOpenExcludedActivities` | empty | Activities where automatic App Open never shows |

Ad unit ids are optional. You can pass only the app id and give an id on every call, so different screens use different ids at runtime:
```kotlin
AdsManager.showInterstitialOnDemand(activity, adUnitId = "ca-app-pub-xxx/screen2") { }
```
Replace the test ids with real ids for release builds.

## Full-screen ads

Interstitial, Rewarded, Rewarded Interstitial and App Open share the same three functions (`X` is the format name):

| Function | What it does |
|---|---|
| `preloadX(count = 1, adUnitId = null)` | Loads silently in the background. No loading UI, nothing is shown. Uses the SDK preloaders, which refill the buffer automatically. |
| `showXOnDemand(activity, adUnitId = null, timeoutMillis = 8000) { event -> }` | Loads an ad right now and shows it when ready. Waits at most 8 seconds. |
| `showPreloadedX(activity, adUnitId = null, loadOnDemandIfUnavailable = true, timeoutMillis = 8000) { event -> }` | Shows a preloaded ad instantly. With none available it falls back to on-demand loading, or reports `AdError.AD_NOT_AVAILABLE` when `loadOnDemandIfUnavailable = false`. |

Rewarded formats also send `AdEvent.Rewarded(item)` when the user earns the reward:
```kotlin
AdsManager.showPreloadedRewarded(activity) { event ->
    when (event) {
        is AdEvent.Rewarded -> grantReward(event.item.amount)
        is AdEvent.Dismissed, is AdEvent.Failed -> continueFlow()
        else -> Unit
    }
}
```
Show a rewarded interstitial only after telling the user about the reward.

## App Open

After initialization a preloaded App Open ad is shown automatically when the app returns to the foreground, unless:
- the current activity is excluded,
- another full-screen ad is loading or showing (Interstitial and Rewarded always have priority),
- the cooldown (15 seconds) has not passed,
- no ad is preloaded. Automatic App Open never makes the user wait.

```kotlin
AdsManager.excludeFromAppOpen(SplashActivity::class.java, OnboardingActivity::class.java)
AdsManager.isAppOpenEnabled = false
```
For a cold start, call `showAppOpenOnDemand` from your splash screen yourself.

## Banner

XML:
```xml
<com.kazmi.dev.adsnextgen.AdsBannerView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:adsBannerSize="anchoredAdaptive"
    app:adsAdUnitId="ca-app-pub-xxx/banner" />
```
Attributes: `adsBannerSize` (`anchoredAdaptive`, `inlineAdaptive`, `banner`, `fullBanner`, `largeBanner`, `leaderboard`, `mediumRectangle`, `fluid`), `adsCollapsible` (`none`, `top`, `bottom`), `adsAdUnitId`, `adsAutoLoad`. In code use `size`, `collapse`, `adUnitId`, `onEvent` and `load()`.

Compose:
```kotlin
BannerAd(size = BannerSize.INLINE_ADAPTIVE, collapse = BannerCollapse.BOTTOM) { event -> }
```
The banner loads once laid out, sends no request while offline, hides itself when no ad is available, and is destroyed with the host lifecycle.

## Native

Three templates: Small, Medium and Full Screen, each with the required "Ad" badge and AdChoices.

XML:
```xml
<com.kazmi.dev.adsnextgen.AdsNativeView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:adsNativeTemplate="medium" />
```
Compose: `NativeAdSmall()`, `NativeAdMedium()`, `NativeAdFullScreen()`.

Call `AdsManager.preloadNative()` early so views can show instantly. A native view takes a preloaded ad when one exists and loads otherwise. It is invisible while loading (so layouts do not jump) and hidden when no ad is available. Override the look by redefining the `ads_native_cta`, `ads_native_cta_text`, `ads_native_badge`, `ads_native_badge_text` and `ads_native_stroke` colors.

## Events and errors

`AdEvent` (full-screen): `Showed`, `Clicked`, `Impression`, `Paid(value)`, `Rewarded(item)`, `Dismissed`, `Failed(error)`. Exactly one of `Dismissed` or `Failed` ends each show call, so navigate onward on either.

`BannerEvent` and `NativeEvent`: `Loaded`, `Failed(error)`, `Clicked`, `Impression`, `Paid(value)`.

`AdError`: `NO_INTERNET`, `PRODUCT_PURCHASED`, `INVALID_AD_UNIT_ID`, `AD_FAILED_TO_LOAD`, `AD_LOAD_TIMEOUT`, `AD_NOT_AVAILABLE`, `AD_FAILED_TO_SHOW`, `ANOTHER_AD_SHOWING`, `ACTIVITY_UNAVAILABLE`.

## How loading works

- **Preloading** uses the SDK preloaders (`*AdPreloader`): Google keeps the buffer full and drops expired ads. Preloads requested while offline start when the network returns.
- **On-demand** loads with a suspend call. The user waits at most 8 seconds (`withTimeoutOrNull`); only the waiting is cancelled, never the network request.
- **No ad is lost.** An ad that finishes loading after the timeout, or one returned because the activity was closing, is kept in a small late-ad slot and used by the next `showPreloaded`.
- **One full-screen ad at a time.** A second show call while one is in progress is ignored (double taps) or reports `ANOTHER_AD_SHOWING`. If you leave the app while an interstitial loads, the App Open ad is skipped and cached.
- **Offline.** No request is sent without internet, so your match rate is not hurt by doomed requests.

## Consent (UMP)

Runs before the SDK starts, on the first visible activity. If consent is not required, unavailable or fails, it is skipped and ads initialize normally. If consent is required and the user does not grant it, ads stay off. Offer a privacy options entry in your settings screen:
```kotlin
if (AdsManager.isPrivacyOptionsRequired(context)) AdsManager.showPrivacyOptions(activity)
```

## Premium users
```kotlin
AdsManager.isAdsEnabled = false
```
Every ad call then ends immediately with `AdError.PRODUCT_PURCHASED`.

## Project layout
```
admobNextGenAds/          core library (all ads, XML views, consent)
admobNextGenAdsCompose/   Compose wrappers
jitpack.yml               JitPack build settings
gradle/publishing.gradle.kts   Maven publication settings, shared by both modules
docs/IMPLEMENTATION_PLAN.md    design and decisions
legacy/                   previous single-file version, kept for reference
```
To release: set `libraryVersion` in `gradle.properties`, merge to `main`, then create a GitHub release with the tag `X.Y.Z`. JitPack builds it the first time someone requests that version (or when you open the version on jitpack.io).

## Status

Compiles against Ads SDK 1.7.0 and builds with the JitPack command. It has not been tested on a device yet. Still to verify on a real device: the consent flow, App Open timing and priority, collapsible banners, and how the native templates look.

Follow Google's AdMob policies: do not click your own ads, show interstitials at natural transition points, and use test ids while developing.
