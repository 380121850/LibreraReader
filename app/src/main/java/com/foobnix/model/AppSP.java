package com.foobnix.model;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.Objects;
import com.foobnix.pdf.info.Android6;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.Urls;

import java.io.File;

public class AppSP {

    private static AppSP instance = new AppSP();
    public String lastBookPath;

    public int lastBookPage = 0;
    public int lastBookPageCount = 0;
    public int tempBookPage = 0;
    public volatile int lastBookParagraph = 0;
    public String lastBookTitle;
    public int lastBookWidth = 0;
    public int lastBookHeight = 0;
    public int lastFontSize = 0;
    public String lastBookLang = "";
    public boolean isLocked = false;
    public boolean isFirstTimeVertical = true;
    public boolean isFirstTimeHorizontal = true;

    public int readingMode = AppState.READING_MODE_SCROLL;
    public long syncTime;
    public int syncTimeStatus;
    // Cumulative time (ms) spent in the reader, shown on the dashboard stats.
    public long readTimeMs = 0;
    // Reading stats: today's reading time ("yyyy-MM-dd" key) and cumulative
    // page flips, for the daily-time and reading-speed dashboard cards.
    public String readDayKey = "";
    public long readDayMs = 0;
    public long readPages = 0;
    // Reading time per calendar month, "{"yyyy-MM": ms}" — kept for the last
    // 13 months only. Filled by ReadingStats.onPause alongside readTimeMs.
    public String readMonthlyJson = "{}";
    // Reading time per calendar day, "{"yyyy-MM-dd": ms}" — kept for the last
    // 40 days (feeds the week/month views of the stats chart).
    public String readDailyJson = "{}";
    public String hypenLang = null;
    public boolean isCut = false;
    public boolean isDouble = false;
    public boolean isRTL = Urls.isRtl();
    public boolean isDoubleCoverAlone = false;
    public boolean isCrop = false;
    public boolean isCropSymetry = false;
    public boolean isSmartReflow = false;
    public boolean isEnableSync;
    public String syncRootID;

    public String currentProfile = "";
    public String rootPath1 = getRootDir();

    transient SharedPreferences sp;

    public long interstitialLoadAdTime = 0;
    public long interstitialAdShowTime = 0;

    public long rewardedAdLoadedTime = 0;
    public long rewardShowTime = 0;


    public static AppSP get() {
        return instance;
    }

    public void init(Context c) {
        sp = c.getSharedPreferences("AppTemp", Context.MODE_PRIVATE);
        load(c);
        getRootPath(c);
    }

    public String getTempDir(Context c){
        return new File(c.getExternalFilesDir(null), "Demo").toString();
    }
    public String getRootDir(){
        return new File(Environment.getExternalStorageDirectory(), "Librera").toString();
    }
    public File getTempDownloadBooks(Context c){
        return new File(c.getExternalFilesDir(null), "TempDownloads");
    }

    public String getRootPath(Context c){
        LOG.d("rootPath2","getRootPath-1",rootPath1, currentProfile);
        if(instance.currentProfile.isEmpty()) {
            if (!Android6.canWrite(c)) {
                instance.rootPath1 = getTempDir(c);
                instance.currentProfile = "Demo";
            } else {
                instance.rootPath1 =getRootDir();
                instance.currentProfile = AppsConfig.IS_LOG ? "BETA" : "Librera";
            }

        }
        LOG.d("rootPath2","getRootPath-2",rootPath1, currentProfile);
        return instance.rootPath1;
    }

    public void load(Context c) {
        Objects.loadFromSp(instance, sp);
    }

    public void save() {
        Objects.saveToSP(instance, sp);
        LOG.d("rootPath2","save-1",rootPath1, currentProfile);
        LOG.d("rootPath2","save-2",get().rootPath1, get().currentProfile);

    }

}
