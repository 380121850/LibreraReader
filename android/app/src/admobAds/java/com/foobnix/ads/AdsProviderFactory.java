package com.foobnix.ads;

/**
 * Compiled only into the ad-enabled flavors via app/src/admobAds in
 * app/build.gradle (those flavors pull play-services-ads through libDepFree).
 */
public class AdsProviderFactory {

    public static AdsProvider get() {
        return new AdMobAdsProvider();
    }
}
