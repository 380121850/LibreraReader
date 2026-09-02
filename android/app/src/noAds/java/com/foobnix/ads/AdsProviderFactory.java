package com.foobnix.ads;

/**
 * Compiled only into the GMS-free flavors (fdroid, pro) via
 * app/src/noAds in app/build.gradle — their APKs get the no-op provider
 * and therefore contain no ad-SDK code (F-Droid requirement).
 */
public class AdsProviderFactory {

    public static AdsProvider get() {
        return new NoAdsProvider();
    }
}
