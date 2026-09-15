# Project-specific R8 rules. Applied to release builds only (rules/ACTION.md Q2).

# JNI entry points reachable from native code.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# --- JNA ---------------------------------------------------------------------------------
# JNA is the runtime the UniFFI-generated bindings use to call librawler_fotlab.so. It resolves
# native symbols by reflecting over Java declarations, so its classes, its dynamic proxies and
# the member names involved must all survive minification.
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn com.sun.jna.**
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** {
    public *;
}

# --- UniFFI bindings + our facade --------------------------------------------------------
# The generated `UniffiLib` interface declares one method per exported Rust function, and JNA maps
# the Java method NAME to the native symbol name. Obfuscating those names would break the bridge
# at runtime, so the whole binding package is kept (it is a handful of small classes).
-keep class io.github.fotlab.fotlab_rawler.** { *; }
