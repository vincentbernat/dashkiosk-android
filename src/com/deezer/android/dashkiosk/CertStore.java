/*
 * Copyright (c) 2016 Vincent Bernat <vbe@deezer.com>
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

import android.content.SharedPreferences;
import android.content.res.Resources;
import android.util.Log;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.Principal;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;

/**
 * Certificate store handling (both certificates and private keys).
 */
public class CertStore {
    private static final String TAG = "DashKiosk";

    /**
     * Get a matching client certificate for the given host and
     * requested principals.
     */
    public static PrivateKeyEntry getClientCertificate(SharedPreferences prefs,
                                                       Resources resources,
                                                       String host, Principal[] principals) {
        String password = prefs.getString("pref_ssl_keystore_password", "");
        Boolean external = prefs.getBoolean("pref_ssl_external_keystore", false);
        String path = prefs.getString("pref_ssl_keystore_path", "");
        Boolean embedded = prefs.getBoolean("pref_ssl_embedded_keystore", false);
        PrivateKeyEntry keyEntry = null;

        /* Try with external store */
        if (keyEntry == null && external) {
            Log.d(TAG, "Looking for external store `" + path + "`");
            try {
                FileInputStream in = new FileInputStream(path);
                try {
                    keyEntry = getClientCertificateFrom(in, host, password,
                                                        principals, "external");
                } finally {
                    in.close();
                }
            } catch (FileNotFoundException e) {
                Log.e(TAG, "Keystore `" + path + "` was not found");
            } catch (SecurityException e) {
                Log.e(TAG, "Access to keystore `" + path + "` was denied");
            } catch (IOException e) {
                Log.e(TAG, "Cannot handle keystore `" + path + "`", e);
            }
        }

        /* Try with embedded store */
        if (keyEntry == null && embedded) {
            Log.d(TAG, "Looking for embedded store");
            try {
                InputStream in = resources.openRawResource(R.raw.clientstore);
                try {
                    keyEntry = getClientCertificateFrom(in, host, password,
                                                        principals, "embedded");
                } finally {
                    in.close();
                }
            } catch (IOException e)  {
                Log.e(TAG, "Cannot handle embedded keystore", e);
            }
        }

        if (keyEntry == null) {
            Log.i(TAG, "Unable to find a matching client certificate for " + host);
        }
        return keyEntry;
    }

    private static PrivateKeyEntry getClientCertificateFrom(InputStream in,
                                                            String host,
                                                            String pass,
                                                            Principal[] principals,
                                                            String type) {
        KeyStore keystore = null;
        char[] password = (pass.length() > 0)?pass.toCharArray():null;
        try {
            keystore = KeyStore.getInstance("BKS");
            keystore.load(in, password);
        } catch (Exception e) {
            Log.e(TAG, "Unable to open " + type + "store", e);
            return null;
        }

        /* Iterate over available certificates */
        try {
            KeyStore.PasswordProtection pp = new KeyStore.PasswordProtection(password);
            KeyStore.PrivateKeyEntry entry = null;
            Enumeration<String> aliases = keystore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (!keystore.isKeyEntry(alias)) {
                    Log.d(TAG, "Entry `" + alias + "` is not a private key, skip");
                    continue;
                }

                // Extract private key
                try {
                    entry = (KeyStore.PrivateKeyEntry)keystore.getEntry(alias, pp);
                } catch (Exception e) {
                    Log.e(TAG, "Unable to get entry `" + alias + "`", e);
                    continue;
                }

                // Check the type
                if (entry.getCertificate().getType() != "X.509") {
                    Log.d(TAG, "Entry `" + alias + "` doesn't have the right type (" +
                          entry.getCertificate().getType() + ")");
                    continue;
                }

                // TODO: We should match the private key class (RSAPrivateKey,
                //       DSAPrivateKey, ECPrivateKey) with handler.getKeyTypes().

                // Check the principal
                X509Certificate cert = (X509Certificate)entry.getCertificate();
                String issuerName = cert.getIssuerX500Principal().getName();
                if (!Arrays.asList(principals).contains(cert.getIssuerX500Principal())) {
                    Log.d(TAG, "Entry `" + alias + "` doesn't have the right issuer (" +
                          issuerName + ")");
                    continue;
                }

                Log.i(TAG, "Got certificate for " + host + " in " +
                      type + " store (alias: " + alias + ")");
                return entry;
            }
        } catch (KeyStoreException e) {
            Log.e(TAG, "Error while querying keystore", e);
            return null;
        }

        return null;
    }
}
