# Project-specific R8 rules. **INERT** — minification is off (rules/ACTION.md Q2), so nothing here
# affects any build today. It stays wired via `proguardFiles` in `app/build.gradle.kts`, meaning
# these rules apply automatically the day `isMinifyEnabled` is switched on, with no edit here.
#
# Written when R8 was first enabled (2026-09-14), and kept through every enable/disable cycle since
# (see ACTION.md Q2 and its Change History for that history, including the most recent decision to
# leave R8 off specifically to protect the native bridge conservatively).
#
# --- What this file does and does not need to cover ------------------------------------------
# R8 rewrites Java/Kotlin bytecode only; it never sees machine code. So a keep rule is needed only
# where a symbol crosses the language boundary. For this project that boundary audit found exactly
# ONE, and this file covers it. The three neighbours that look like they should appear here, and
# why they do not, recorded so the next reader does not "fix" a non-problem by adding rules or, far
# worse, delete the JNA rules below as unused:
#
#   * C++ (rawalchemy grading, rawtherapee/RawTherapee) — reached from Rust through the `cxx`
#     crate's `#[cxx::bridge]`, i.e. compile-time-generated glue statically linked into
#     librawler_fotlab.so (librawalchemy_grading.a + generated C++ shims). Those symbols are linked
#     inside the .so and have no Java-side names, so there is nothing for R8 to rename. Their real
#     hazards are link-time (DT_NEEDED on libc++_shared.so / libomp.so and getting those .so files
#     packaged), handled by build.rs and CI — not by R8.
#   * Room — KSP + room-compiler, pure code generation, no reflection.
#   * DataStore — the Preferences API, not Proto; types resolved at compile time.
#   * Compose / Coil / ExifInterface — each ships consumer proguard rules inside its AAR, which
#     AGP merges automatically.

# JNI entry points reachable from native code.
#
# This project currently has NO JNI at all — no `jni::`, no `cxx::bridge` reaching Kotlin, no Kotlin
# `external fun` — so the rule matches nothing today. It is kept deliberately: it is the exact rule
# that would silently stop protecting things if someone later introduced a JNI entry point, and a
# project whose native bridge already depends on precise symbol names is the wrong place to remove
# a safety net that costs nothing.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# --- JNA -------------------------------------------------------------------------------------
# JNA is the runtime the UniFFI-generated bindings use to call librawler_fotlab.so. It resolves
# native symbols by reflecting over Java declarations, so its classes, its dynamic proxies and
# the member names involved must all survive minification. THIS is the load-bearing section.
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn com.sun.jna.**
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** {
    public *;
}

# --- UniFFI bindings + our facade --------------------------------------------------------------
# The generated `UniffiLib` interface declares one method per exported Rust function, and JNA maps
# the Java method NAME to the native symbol name. Obfuscating those names would break the bridge
# at runtime, so the whole binding package is kept (it is a handful of small classes).
-keep class io.github.fotlab.fotlab_rawler.** { *; }