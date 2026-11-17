# Tune2AI Studio

Tune2AI Studio is a responsive developer console prototype packaged as a lightweight Android WebView app.

## Build the Android app

Open the project in Android Studio and let Gradle sync, then build the `app` module. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The app requires Android API 26 or later. Playground replies are currently simulated; no model backend is connected yet.

## Web preview

Serve the project root with any static file server and open `index.html`.
