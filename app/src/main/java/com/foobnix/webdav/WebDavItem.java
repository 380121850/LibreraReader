package com.foobnix.webdav;

/**
 * One row of the WebDAV UI: a configured server (root view), a remote
 * directory or a remote file. Kept independent from the OPDS models.
 */
public class WebDavItem {
    public String href;
    public String name;
    public boolean isDir;
    public boolean isServer;
    public long size = -1;
    /** original persistence line (server rows only), used for remove/edit */
    public String appState;

    public WebDavItem() {
    }

    public WebDavItem(String href, String name, boolean isServer) {
        this.href = href;
        this.name = name;
        this.isDir = true;
        this.isServer = isServer;
    }
}
