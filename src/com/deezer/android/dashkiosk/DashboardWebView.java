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
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import org.xwalk.core.ClientCertRequest;
import org.xwalk.core.JavascriptInterface;
import org.xwalk.core.XWalkJavascriptResult;
import org.xwalk.core.XWalkPreferences;
import org.xwalk.core.XWalkResourceClient;
import org.xwalk.core.XWalkUIClient;
import org.xwalk.core.XWalkView;

import com.deezer.android.dashkiosk.DashboardWaitscreen;

/**
 * Fullscreen web view that is setup for kiosk mode: no interaction
 * allowed.
 */
public class DashboardWebView extends XWalkView {

    private static final String TAG = "DashKiosk";
    private static final int ALIVE = 1;
    private static final int DEADLINE = 2;
    private Context mContext;
    private Handler mHandler = null;
    private DashboardWaitscreen mWaitscreen = null;

    public DashboardWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
    }

    @Override
    protected void onDetachedFromWindow() {
        this.hideWaitScreen();
        this.mHandler.removeMessages(ALIVE);
        this.mHandler.removeMessages(DEADLINE);
        this.mHandler = null;
        Log.d(TAG, "Webview paused");
    }

    @Override
    protected void onAttachedToWindow() {
        XWalkPreferences.setValue(XWalkPreferences.REMOTE_DEBUGGING, true);
        XWalkPreferences.setValue(XWalkPreferences.JAVASCRIPT_CAN_OPEN_WINDOW, false);
        getSettings().setMediaPlaybackRequiresUserGesture(false);
        clearSslPreferences();

        /* Don't show error dialogs */
        this.setResourceClient(new XWalkResourceClient(this) {
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
                    if (insecure &&
                        error.hasError(SslError.SSL_UNTRUSTED) &&
                        !error.hasError(SslError.SSL_DATE_INVALID) &&
                        !error.hasError(SslError.SSL_EXPIRED) &&
                        !error.hasError(SslError.SSL_IDMISMATCH) &&
                        !error.hasError(SslError.SSL_INVALID)) {
                        // The certificate is "only" untrusted and we allow this.
                        Log.d(TAG, "Accept untrusted certificate " + error.getCertificate());
                        callback.onReceiveValue(true);
                        return;
                    }
                    Log.w(TAG, "TLS error: " + error.toString());
                    callback.onReceiveValue(false);
                }

                private Boolean handleClientCertRequest(ClientCertRequest handler,
                                                        InputStream in,
                                                        String pass,
                                                        String type) {
                    KeyStore keystore = null;
                    char[] password = (pass.length() > 0)?pass.toCharArray():null;
                    try {
                        keystore = KeyStore.getInstance("BKS");
                        keystore.load(in, password);
                    } catch (Exception e) {
                        Log.e(TAG, "Unable to open " + type + "store", e);
                        return false;
                    }

                    /* Iterate over available certificates */
                    try {
                        Enumeration<String> aliases = keystore.aliases();
                        while (aliases.hasMoreElements()) {
                            String alias = aliases.nextElement();
                            if (!keystore.isKeyEntry(alias)) {
                                Log.d(TAG, "Entry `" + alias + "` is not a private key, skip");
                                continue;
                            }
                            KeyStore.PrivateKeyEntry entry = null;
                            try {
                                entry = (KeyStore.PrivateKeyEntry)keystore.getEntry(
                                    alias, new KeyStore.PasswordProtection(password));
                            } catch (Exception e) {
                                Log.e(TAG, "Unable to get entry `" + alias + "`", e);
                                continue;
                            }
                            Log.d(TAG, "Got a private key!");
                            ArrayList<X509Certificate> chain = new ArrayList<X509Certificate>();
                            for (Certificate c : entry.getCertificateChain()) {
                                chain.add((X509Certificate)c);
                            }
                            handler.proceed(entry.getPrivateKey(), chain);
                            return true;
                        }
                    } catch (KeyStoreException e) {
                        Log.e(TAG, "Error while querying keystore", e);
                        return false;
                    }

                    /* Nothing found */
                    return false;
                }

                @Override
                public void onReceivedClientCertRequest(XWalkView view,
                                                        ClientCertRequest handler) {
                    Log.d(TAG, "Client certificate requested for " + handler.getHost());
                    /* Unfortunately, not implemented yet in Crosswalk */
                    // Log.d(TAG, "Accepted key types: " + TextUtils.join(", ", handler.getKeyTypes()));
                    // Log.d(TAG, "Accepted principals: " + TextUtils.join(", ", handler.getPrincipals()));

                    SharedPreferences sharedPref = PreferenceManager
                        .getDefaultSharedPreferences(mContext);
                    String password = sharedPref.getString("pref_ssl_keystore_password", "");
                    Boolean external = sharedPref.getBoolean("pref_ssl_external_keystore", false);
                    String path = sharedPref.getString("pref_ssl_keystore_path", "");
                    Boolean embedded = sharedPref.getBoolean("pref_ssl_embedded_keystore", false);

                    if (external) {
                        Log.d(TAG, "Looking for external store `" + path + "`");
                        try {
                            try (FileInputStream in = new FileInputStream(path)) {
                                if (handleClientCertRequest(handler, in,
                                                            password, "external")) {
                                    return;
                                }
                            }
                        } catch (FileNotFoundException e) {
                            Log.e(TAG, "Keystore `" + path + "` was not found");
                        } catch (SecurityException e) {
                            Log.e(TAG, "Access to keystore `" + path + "` was denied");
                        } catch (IOException e) {
                            Log.e(TAG, "Cannot handle keystore `" + path + "`", e);
                        }
                    }
                    if (embedded) {
                        Log.d(TAG, "Looking for embedded store");
                        try {
                            try (InputStream in = getResources().openRawResource(R.raw.clientstore)) {
                                if (handleClientCertRequest(handler, in,
                                                            password, "embedded")) {
                                    return;
                                }
                            }
                        } catch (IOException e)  {
                            Log.e(TAG, "Cannot handle embedded keystore", e);
                        }
                    }

                    Log.i(TAG,
                          "Unable to find a matching client certificate for " +
                          handler.getHost() + " in keystore");
                    handler.cancel();
                }

            });

        /* Ignore most interactions */
        this.setUIClient(new XWalkUIClient(this) {
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
        this.addJavascriptInterface(new Object() {
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

        this.displayWaitScreen();
        this.heartbeat();
        this.loadReceiver();
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

    // Trigger a reload if we miss an heartbeat
    private void heartbeat() {
        this.mHandler = new Handler() {
                @Override
                public void handleMessage(Message input) {
                    switch (input.what) {
                    case ALIVE:
                        // Got a heartbeat, delay deadline
                        Log.d(TAG, "Received heartbeat");
                        hideWaitScreen();
                        this.removeMessages(DEADLINE);
                        this.sendMessageDelayed(this.obtainMessage(DEADLINE),
                                                getTimeout());
                        break;
                    case DEADLINE:
                        // We hit the deadline, trigger a reload
                        Log.i(TAG, "No activity from supervised URL. Trigger reload.");
                        displayWaitScreen();
                        stopLoading();
                        loadReceiver();
                        this.sendMessageDelayed(this.obtainMessage(DEADLINE),
                                                getTimeout());
                        break;
                    }
                }
            };

        mHandler.sendMessageDelayed(mHandler.obtainMessage(DEADLINE),
                                    getTimeout());
    }

    private void loadReceiver() {
        SharedPreferences sharedPref = PreferenceManager
            .getDefaultSharedPreferences(mContext);
        String pingURL = sharedPref.getString("pref_ping_url", null);
        String appVer = getResources().getString(R.string.app_versionName);
        String url = pingURL + "?v=" + appVer;
        Log.d(TAG, "Loading " + url);
        this.load(url, null);
    }

    private int getTimeout() {
        SharedPreferences sharedPref = PreferenceManager
            .getDefaultSharedPreferences(mContext);
        return Integer.valueOf(sharedPref.getString("pref_ping_timeout", null));
    }

}
