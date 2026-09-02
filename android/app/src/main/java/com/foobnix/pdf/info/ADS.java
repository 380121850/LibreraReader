package com.foobnix.pdf.info;

import android.app.Activity;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import com.foobnix.ads.AdsProvider;
import com.foobnix.ads.AdsProviderFactory;
import com.foobnix.ads.RewardListener;
import com.foobnix.android.utils.LOG;
import com.foobnix.model.AppSP;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

/**
 * Ad facade, SDK-free. The ad-display policy timers (persisted in AppSP) and
 * the test-device helpers stay here; every SDK operation is delegated to the
 * AdsProvider compiled into this variant (see src/admobAds / src/noAds in
 * app/build.gradle). Main code never references ad-SDK types, so GMS-free
 * builds (F-Droid, Pro) compile with zero ad-SDK code.
 */
public class ADS {
    //public static int FULL_SCREEN_TIMEOUT_SEC = 15;
    public static int ADS_LIVE_SEC = 60 * 60;//60 min
    public static int INTERSTITIAL_DELAY_SEC = 60 * 5;//4 min

    public static int REWARDS_HOURS_IN_SECONDS = 2 * 60 * 60;//2 hours

    private final static ADS instance = new ADS();

    private AdsProvider provider;

    public static synchronized ADS get() {
        return instance;
    }

    private ADS() {
    }

    /**
     * The provider is looked up lazily so the mere class load of ADS (e.g. by
     * AppsConfig while detecting test devices) does not touch the ad SDK.
     */
    private synchronized AdsProvider provider() {
        if (provider == null) {
            provider = AdsProviderFactory.get();
        }
        return provider;
    }

    public static long secondsRemain(long time) {
        return TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - time);
    }

    public static void hideAdsTemp(Activity a) {
    }

    /** Reward-window policy shared by all callers (button visibility, guards). */
    public boolean isRewardActivated() {
        try {
            boolean activated = secondsRemain(AppSP.get().rewardShowTime) < REWARDS_HOURS_IN_SECONDS;
            LOG.d("ADS1", "isRewardActivated", activated);
            return activated;
        } catch (Exception e) {
            LOG.e(e);
        }
        return true;
    }

    // -------- SDK delegation (implementation per variant) --------

    /** SDK bootstrap; only runs when the app is allowed to show ads. */
    public void initialize(Context context) {
        try {
            if (AppsConfig.isShowAdsInApp(context)) {
                provider().initialize(context);
            }
        } catch (Throwable e) {
            LOG.e(e);
        }
    }

    public void showBanner(Activity a) {
        try {
            provider().showBanner(a);
        } catch (Throwable e) {
            LOG.e(e);
        }
    }

    public void onPauseBanner() {
        try {
            provider().onPauseBanner();
        } catch (Throwable e) {
            LOG.e(e);
        }
    }

    public void onResumeBanner(Activity a) {
        try {
            provider().onResumeBanner(a);
        } catch (Throwable e) {
            LOG.e(e);
        }
    }

    public void onDestroyBanner() {
        try {
            provider().onDestroyBanner();
        } catch (Throwable e) {
            LOG.e(e);
        }
    }

    public void loadInterstitial(Activity a) {
        try {
            provider().loadInterstitial(a);
        } catch (Throwable e) {
            LOG.e(e);
        }
    }

    public void showInterstitial(Activity a) {
        try {
            provider().showInterstitial(a);
        } catch (Throwable e) {
            LOG.e(e);
        }
    }

    public void loadRewardedAd(Activity a, Runnable onRewardLoaded) {
        try {
            provider().loadRewardedAd(a, onRewardLoaded);
        } catch (Throwable e) {
            LOG.e(e);
        }
    }

    public boolean isRewardsLoaded() {
        try {
            return provider().isRewardsLoaded();
        } catch (Throwable e) {
            LOG.e(e);
            return false;
        }
    }

    public void showRewardedAd(Activity a, RewardListener listener) {
        try {
            provider().showRewardedAd(a, listener);
        } catch (Throwable e) {
            LOG.e(e);
        }
    }

    public void requestConsent(Activity a) {
        try {
            provider().requestConsent(a);
        } catch (Throwable e) {
            LOG.e(e);
        }
    }

    public boolean isPrivacyOptionsRequired() {
        try {
            return provider().isPrivacyOptionsRequired();
        } catch (Throwable e) {
            LOG.e(e);
            return false;
        }
    }

    public void showPrivacyOptions(Activity a) {
        try {
            provider().showPrivacyOptions(a);
        } catch (Throwable e) {
            LOG.e(e);
        }
    }

    public static String getByTestID(Context c) {
        String android_id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        String upperCase = md5_2(android_id).toUpperCase();
        Log.d("device_id", upperCase);
        return upperCase;
    }

    public static final String md5_2(final String s) {
        try {
            // Create MD5 Hash
            MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            digest.update(s.getBytes());
            byte messageDigest[] = digest.digest();

            // Create Hex String
            StringBuffer hexString = new StringBuffer();
            for (int i = 0; i < messageDigest.length; i++) {
                String h = Integer.toHexString(0xFF & messageDigest[i]);
                while (h.length() < 2) {
                    h = "0" + h;
                }
                hexString.append(h);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
        }
        return "";
    }
}
