This repository contains the Android application to be used with
[Dashkiosk](https://github.com/vincentbernat/dashkiosk). Its
documentation is on [ReadTheDocs][].

[ReadTheDocs]: http://dashkiosk.readthedocs.org/en/latest/android.html

# Compilation

This project uses Gradle. Here are the steps to compile it:

 1. Download the [Android SDK][]
 2. Unpack it, put `bin/android` somewhere in your PATH and ensure you
    install Android SDK Build tools 21.1.2 as well as the SDK Platform
    21.
 3. Build with `./gradlew assemble`.

[Gradle]: http://www.gradle.org/
[Android SDK]: http://developer.android.com/sdk/index.html#Other

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
    fullscreen webview.

 4. A _Chromecast custom receiver_ which will run the regular receiver
    if you want to display dashboards using Google Chromecast devices.
