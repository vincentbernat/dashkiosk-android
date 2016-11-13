/*
 * Copyright (c) 2013 Vincent Bernat <vbe@deezer.com>
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package com.deezer.android.dashkiosk;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import java.lang.ref.WeakReference;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import org.xwalk.core.ClientCertRequest;
import org.xwalk.core.JavascriptInterface;
import org.xwalk.core.XWalkJavascriptResult;
import org.xwalk.core.XWalkPreferences;
import org.xwalk.core.XWalkResourceClient;
import org.xwalk.core.XWalkUIClient;
import org.xwalk.core.XWalkView;

import com.deezer.android.dashkiosk.DashboardWaitscreen;
import com.deezer.android.dashkiosk.CertStore;

/**
 * Fullscreen web view that is setup for kiosk mode: no interaction
 * allowed.
 */
public class DashboardWebView extends XWalkView {

    private static final String TAG = "DashKiosk";
    private static final int ALIVE = 1;
    private static final int DEADLINE = 2;
    private Context mContext;
    private final Handler mHandler = new HeartbeatHandler(this);
    private DashboardWaitscreen mWaitscreen = null;

    public DashboardWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
    }

    @Override
    protected void onDetachedFromWindow() {
        hideWaitScreen();
        mHandler.removeMessages(ALIVE);
        mHandler.removeMessages(DEADLINE);
        super.onDetachedFromWindow();
        Log.d(TAG, "Webview paused");
    }

    @Override
    protected void onAttachedToWindow() {
        XWalkPreferences.setValue(XWalkPreferences.REMOTE_DEBUGGING, true);
        XWalkPreferences.setValue(XWalkPreferences.JAVASCRIPT_CAN_OPEN_WINDOW, false);
        getSettings().setMediaPlaybackRequiresUserGesture(false);
        clearSslPreferences();

        /* Don't show error dialogs */
        setResourceClient(new XWalkResourceClient(this) {
                @Override
                public void onReceivedLoadError(XWalkView view,
                                                int errorCode,
                                                String description,
                                                String failingUrl) {
                    Log.w(TAG, "Load failed for " + failingUrl + ": " + description);
                }

                @Override
                public void onReceivedSslError(XWalkView view,
                                               ValueCallback<Boolean> callback,
                                               SslError error) {
                    SharedPreferences sharedPref = PreferenceManager
                        .getDefaultSharedPreferences(mContext);
                    Boolean insecure = sharedPref.getBoolean("pref_insecure_ssl", false);
                    if (insecure) {
                        Log.d(TAG, "Accept invalid certificate " + error.getCertificate());
                        callback.onReceiveValue(true);
                        return;
                    }
                    Log.w(TAG, "TLS error: " + error.toString());
                    callback.onReceiveValue(false);
                }

                @Override
                public void onReceivedClientCertRequest(XWalkView view,
                                                        ClientCertRequest handler) {
                    Log.d(TAG, "Client certificate requested for " + handler.getHost());
                    if (handler.getKeyTypes() == null || handler.getPrincipals() == null) {
                        Log.w(TAG, "No key can be accepted");
                    }
                    Log.d(TAG, "Accepted key types: " +
                          TextUtils.join(", ", handler.getKeyTypes()) + " (ignored)");
                    Log.d(TAG, "Accepted principals: " +
                          TextUtils.join(", ", handler.getPrincipals()));

                    PrivateKeyEntry keyEntry = CertStore.getClientCertificate(
                        PreferenceManager.getDefaultSharedPreferences(mContext),
                        getResources(),
                        handler.getHost(),
                        handler.getPrincipals());

                    if (keyEntry == null) {
                        handler.cancel();
                    } else {
                        ArrayList<X509Certificate> chain = new ArrayList<X509Certificate>();
                        for (Certificate c : keyEntry.getCertificateChain()) {
                            chain.add((X509Certificate)c);
                        }
                        handler.proceed(keyEntry.getPrivateKey(), chain);
                    }
                }
            });

        /* Ignore most interactions */
        setUIClient(new XWalkUIClient(this) {
                @Override
                public void onFullscreenToggled(XWalkView view, boolean enterFullscreen) {
                    Log.d(TAG, "Ignore fullscreen request");
                }

                @Override
                public boolean onJavascriptModalDialog(XWalkView view,
                                                       XWalkUIClient.JavascriptMessageType type,
                                                       String url,
                                                       String message,
                                                       String defaultValue,
                                                       XWalkJavascriptResult result) {
                    Log.d(TAG, "Ignore JS modal dialog (type: " + type + ", message: " + message + ")");
                    return false;
                }

                @Override
                public void onJavascriptCloseWindow(XWalkView view) {
                    Log.d(TAG, "Ignore request to close window");
                }

                @Override
                public void openFileChooser(XWalkView view,
                                            ValueCallback<Uri> uploadFile,
                                            String acceptType,
                                            String capture) {
                    Log.d(TAG, "Ignore request to open a file chooser");
                }
            });

        /* Provide an interface for readiness */
        addJavascriptInterface(new Object() {
                @JavascriptInterface
                public void ready() {
                    mHandler.sendMessage(mHandler.obtainMessage(ALIVE));
                }

                @JavascriptInterface
                public int timeout() {
                    return getTimeout();
                }

                @JavascriptInterface
                public void log(String message) {
                    Log.d(TAG, "Javascript log: " + message);
                }
            }, "JSInterface");

        displayWaitScreen();
        loadReceiver();
        mHandler.sendMessageDelayed(mHandler.obtainMessage(DEADLINE),
                                    getTimeout());
        super.onAttachedToWindow();
        Log.d(TAG, "Webview started");
    }

    private void displayWaitScreen() {
        if (mWaitscreen != null && mWaitscreen.isShowing()) {
            return;
        }
        mWaitscreen = new DashboardWaitscreen(mContext);
        mWaitscreen.show();
    }

    private void hideWaitScreen() {
        if (mWaitscreen != null && mWaitscreen.isShowing()) {
            mWaitscreen.dismiss();
        }
        mWaitscreen = null;
    }

    private static class HeartbeatHandler extends Handler {
        private final WeakReference<DashboardWebView> mParent;

        public HeartbeatHandler(DashboardWebView parent) {
            mParent = new WeakReference<DashboardWebView>(parent);
        }

        @Override
        public void handleMessage(Message input) {
            DashboardWebView parent = mParent.get();
            if (parent == null) {
                return;
            }
            switch (input.what) {
            case ALIVE:
                // Got a heartbeat, delay deadline
                Log.d(TAG, "Received heartbeat");
                parent.hideWaitScreen();
                removeMessages(DEADLINE);
                sendMessageDelayed(obtainMessage(DEADLINE),
                                   parent.getTimeout());
                break;
            case DEADLINE:
                // We hit the deadline, trigger a reload
                Log.i(TAG, "No activity from supervised URL. Trigger reload.");
                parent.displayWaitScreen();
                parent.stopLoading();
                parent.loadReceiver();
                sendMessageDelayed(obtainMessage(DEADLINE),
                                   parent.getTimeout());
                break;
            }
        }
    }

    private void loadReceiver() {
        SharedPreferences sharedPref = PreferenceManager
            .getDefaultSharedPreferences(mContext);
        String pingURL = sharedPref.getString("pref_ping_url", null);
        String appVer = getResources().getString(R.string.app_versionName);
        String url = pingURL + "?v=" + appVer;
        Log.d(TAG, "Loading " + url);
        load(url, null);
    }

    private int getTimeout() {
        SharedPreferences sharedPref = PreferenceManager
            .getDefaultSharedPreferences(mContext);
        return Integer.valueOf(sharedPref.getString("pref_ping_timeout", null));
    }

}
