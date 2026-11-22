# Conscrypt 2.5.3 includes adapters for Android <= 4.4 that reference
# legacy platform-internal SSL types. The app requires Android 11+ (minSdk 30),
# where these adapters are never selected. These types are intentionally not
# part of the modern Android SDK stubs. Silence *only* these two references;
# retain R8 shrinking/optimization and Conscrypt's dependency keep rules.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
