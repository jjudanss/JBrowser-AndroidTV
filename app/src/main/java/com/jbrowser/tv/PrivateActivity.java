package com.jbrowser.tv;

import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;

/** Separate WebView data directory for private browsing on Android 9 and newer. */
public class PrivateActivity extends MainActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WebView.setDataDirectorySuffix("jbrowser_private");
        }
        super.onCreate(savedInstanceState);
    }
}
