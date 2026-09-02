package com.foobnix.ads;

import android.app.Activity;
import android.content.Context;

/**
 * Ad-network abstraction. Main code (the {@link com.foobnix.pdf.info.ADS}
 * facade) only talks to this interface, so no ad-SDK type leaks into main.
 *
 * Exactly one implementation is compiled into each variant via
 * {@link AdsProviderFactory}:
 *  - src/admobAds  -> AdMobAdsProvider (real AdMob; ad-enabled flavors)
 *  - src/noAds     -> NoAdsProvider    (GMS-free flavors: F-Droid, Pro)
 * That way F-Droid APKs contain no ad-SDK code at all.
 */
public interface AdsProvider {

    /** SDK bootstrap (AdMob init / request configuration). Called once at app start. */
    void initialize(Context context);

    // -------- banner --------

    void showBanner(Activity a);

    void onPauseBanner();

    void onResumeBanner(Activity a);

    void onDestroyBanner();

    // -------- interstitial --------

    /** Starts an async load; caches the ad internally when it arrives. */
    void loadInterstitial(Activity a);

    /** Shows the cached ad (if any). All policy timers live in the ADS facade. */
    void showInterstitial(Activity a);

    // -------- rewarded --------

    void loadRewardedAd(Activity a, Runnable onRewardLoaded);

    boolean isRewardsLoaded();

    void showRewardedAd(Activity a, RewardListener listener);

    // -------- consent (UMP in the AdMob implementation; no-op elsewhere) --------

    /** Consent info update + consent form, if the network requires it. */
    void requestConsent(Activity a);

    /** Whether the privacy-options ("ad settings") entry must be shown. */
    boolean isPrivacyOptionsRequired();

    /** Opens the privacy-options form (activity-scoped). */
    void showPrivacyOptions(Activity a);
}
