# Add project specific ProGuard rules here.
# Keep JNI entry points reachable from native code.
-keepclasseswithmembers class * {
    native <methods>;
}
