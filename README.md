This repository contains the Android application to be used with
[Dashkiosk](https://github.com/vincentbernat/dashkiosk). Its
documentation is on [ReadTheDocs][].

[![Build Status](https://secure.travis-ci.org/vincentbernat/dashkiosk-android.png?branch=master)](https://travis-ci.org/vincentbernat/dashkiosk-android)

[ReadTheDocs]: https://dashkiosk.readthedocs.org/en/latest/android.html

# Compilation

As a prerequisite, download the [Android SDK][], unpack it and ensure
`bin/android` is somewhere on your `PATH`. Then, to compile, just use:

    ./gradlew assemble

To just build a debug APK, use:

    ./gradlew --daemon assembleDebug

If the build process complains about licensing, you need
to [manually accept the licenses][] with the SDK manager GUI tool.

[Android SDK]: https://developer.android.com/studio/index.html#downloads
[manually accept the licenses]: https://developer.android.com/studio/intro/update.html#download-with-gradle

The embedded certificates are just here as an exemple. Only the client
certificate (along with the key) will be bundled in the
application. Since the CA shouldn't be used anywhere, it shoudn't be a
security risk. But you can remove the symbolic link in `res/raw`. Have
a look at `certificates/generate` script to understand how those
certificates were generated.

Currently, ensure that you store only one keypair in the keystore. The
Android application will always use the first certificate.

# About Dashkiosk

Dashkiosk is a solution to manage dashboards on multiple screens. It
comes as four components:

 1. A _receiver_ runs in a browser attached to each screen and will
    display the requested dashboards. The receiver is quite dumb, it
    contacts the server and wait for it to tell which URL to display.

 2. A _server_ which will manage the screens by sending them what they
    should display. An administration interface allows the user to
    manage those screens individually or in a group.
    
 3. An _Android app_ that will run the receiver. This is mainly a
    fullscreen webview built on top of [Crosswalk][] to get access to
    an up-to-date browser engine.

 4. A _Chromecast custom receiver_ which will run the regular receiver
    if you want to display dashboards using Google Chromecast devices.

[Crosswalk]: https://crosswalk-project.org/

# Limitations and non-limitations

The Dashkiosk application for Android has a few perks, notably the
support of WebGL and decent performance, thanks to the accelerated
webview.

However, here are a few limitations:

 - inability to login through a web form
 - inability to preset a cookie (this could be a workaround for the
   limitation above and this should not be too complicated to do, open
   an issue if needed)
 - inability to use several client certificates, see
   [XWALK-6025](https://crosswalk-project.org/jira/browse/XWALK-6025))
