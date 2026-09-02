package com.foobnix.webdav;

import com.foobnix.android.utils.TxtUtils;

/**
 * A configured WebDAV server. Persisted as one line in
 * {@code AppState.allWebDavLinks} with the format {@code url,title;}
 * (same shape as the OPDS catalog list, but stored in its own field).
 */
public class WebDavServer {

    public String url;
    public String title;
    public String appState;

    public WebDavServer(String url, String title) {
        this.url = url;
        this.title = title;
    }

    public static String buildLine(String url, String title) {
        return url + "," + TxtUtils.fixAppState(title) + ";";
    }
}
