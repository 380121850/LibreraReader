package com.foobnix.ads;

/**
 * Own reward callback, kept free of ad-SDK types so the ADS facade can be
 * compiled against every variant (ad-enabled and GMS-free alike).
 */
public interface RewardListener {

    /** The user watched the rewarded ad and earned the reward. */
    void onRewardEarned();
}
