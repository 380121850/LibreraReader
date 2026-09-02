package com.foobnix.ads;

import android.app.Activity;
import android.content.Context;

/**
 * No-op provider for variants that must ship without any ad SDK
 * (F-Droid policy, the paid Pro build). Compiling this instead of a real
 * SDK implementation keeps those APKs free of ad-network code entirely.
 */
public class NoAdsProvider implements AdsProvider {

    @Override
    public void initialize(Context context) {
    }

    @Override
    public void showBanner(Activity a) {
    }

    @Override
    public void onPauseBanner() {
    }

    @Override
    public void onResumeBanner(Activity a) {
    }

    @Override
    public void onDestroyBanner() {
    }

    @Override
    public void loadInterstitial(Activity a) {
    }

    @Override
    public void showInterstitial(Activity a) {
    }

    @Override
    public void loadRewardedAd(Activity a, Runnable onRewardLoaded) {
    }

    @Override
    public boolean isRewardsLoaded() {
        return false;
    }

    @Override
    public void showRewardedAd(Activity a, RewardListener listener) {
    }

    @Override
    public void requestConsent(Activity a) {
    }

    @Override
    public boolean isPrivacyOptionsRequired() {
        return false;
    }

    @Override
    public void showPrivacyOptions(Activity a) {
    }
}
