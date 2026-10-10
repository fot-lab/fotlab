plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Version identity is owned by the repository root files, not by this script.
// See rules/VERSION.md — never edit them from here.
val appVersionName = rootProject.file("VERSION_NAME").readText().trim()
val appVersionCode = rootProject.file("VERSION_CODE").readText().trim().toInt()

// Release signing. keystore.properties is written by CI — see the
// "Get or create release keystore" step in .github/workflows/build_gradle.yaml;
// the keystore itself lives in the dedicated `keystore` repo (branch `keystore`).
// When the file is absent (e.g. a local build) the release build stays unsigned
// instead of failing.
val keystorePropsFile = rootProject.file("keystore.properties")
// Parsed into a Map instead of java.util.Properties: inside this script `java`
// is not the root package (the Java/Android plugin contributes a `java`
// accessor), so `java.util.*` cannot be referenced by its qualified name.
val keystoreProps: Map<String, String> = if (keystorePropsFile.exists()) {
    keystorePropsFile.readLines()
        .filter { it.contains('=') && !it.trim().startsWith("#") }
        .associate { line ->
            val (key, value) = line.split("=", limit = 2)
            key.trim() to value.trim()
        }
} else {
    emptyMap()
}
val hasReleaseKeystore = keystoreProps.containsKey("RELEASE_STORE_FILE")

android {
    namespace = "io.github.fotlab.fotlab"
    compileSdk = 36

    defaultConfig {
        applicationId = providers.gradleProperty("APP_ID").get()
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // CI smoke sharding (.github/workflows/smoke_emulator.yaml): each parallel shard passes a
        // comma-separated AndroidJUnitRunner `-e class` list (`Class` or `Class#method` — NOT
        // `Class#method1+method2`: a token the runner cannot parse selects nothing, and the shard
        // still ends in BUILD SUCCESSFUL) via -PsmokeTestFilter=..., which
        // connectedDebugAndroidTest forwards to am instrument.
        // Absent locally / in a full run, so every instrumented test still runs by default.
        if (project.hasProperty("smokeTestFilter")) {
            testInstrumentationRunnerArguments["class"] =
                project.property("smokeTestFilter").toString()
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProps.getValue("RELEASE_STORE_FILE"))
                storePassword = keystoreProps.getValue("RELEASE_STORE_PASSWORD")
                keyAlias = keystoreProps.getValue("RELEASE_KEY_ALIAS")
                keyPassword = keystoreProps.getValue("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 minification + resource shrinking stay OFF (rules/ACTION.md Q2), deliberately and
            // with the reason recorded rather than left to the AGP default. The deciding reason is
            // to **protect the native bridge conservatively**: the JNA/UniFFI bridge resolves native
            // symbols by reflecting over Java declarations and maps each Java method NAME to a
            // native symbol, so an obfuscation mistake breaks it at runtime only — no compile
            // error, and no CI signal either, because R8 applies to release alone while the
            // emulator smoke job builds the debug variant. The first shipping release would be the
            // first build to actually execute these rules, and it would do so on a user's device.
            // R8 is a size/performance optimisation, not a correctness feature, so the trade is
            // not worth taking blind. Enable it deliberately, once a release build runs in CI.
            //
            // `proguard-rules.pro` stays wired via `proguardFiles`, so it is inert now and applies
            // automatically the day minification is switched on.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            // Hand-written first-party Kotlin that lives outside src/main/kotlin: the native
            // binding facade under app/src/binding/kotlin (FOTLAB-STUDIO-000001).
            kotlin.srcDir("src/binding/kotlin")
            // UniFFI bindings for rawler_fotlab are GENERATED by CI into the build directory,
            // so generated code never enters src/ and needs no .gitignore entry
            // (FOTLAB-STRUCT-000002 R1). Its package comes from uniffi.toml `package_name`.
            kotlin.srcDir("build/generated/uniffi/main/kotlin")
            // The prebuilt librawler_fotlab.so files CI downloads live in the build directory
            // too, for the same reason — no build artifact is placed under src/ (R1).
            jniLibs.srcDir("build/generated/jniLibs")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Per-ABI APK splits — Google's "Build per-ABI APKs" best practice
    // (https://developer.android.com/build/configure-apk-splits). Instead of one
    // fat universal APK carrying all four ABIs' native libs (librawler_fotlab.so +
    // the JNA/OpenMP stubs), emit one APK per ABI plus an optional universal
    // fallback. Each per-ABI APK ships only its own ABI's .so files, so the
    // download is ~1/4 the size of the universal. The split is purely a *packaging*
    // split: the .so files are placed under build/generated/jniLibs by CI (all four
    // ABIs, matching build_rust.yaml's `cargo ndk` targets), and AGP filters them
    // per output APK automatically — no ndk.abiFilters needed.
    //
    // ABI set mirrors build_rust.yaml so every emitted APK has its native lib.
    // `universalApk = true` keeps a single all-ABI APK as a fallback for users who
    // don't know their device ABI; GitHub Releases has no ABI filtering, so the
    // universal remains the "grab one file" path while the per-ABI APKs are the
    // recommended download. (All four share the same versionCode — fine for direct
    // GitHub distribution; a Play Store multi-APK upload would instead need
    // distinct versionCodes, which would conflict with the +1 rule in rules/VERSION.md.)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
}

// Kotlin compiler options. The KGP `compilerOptions` DSL supersedes the deprecated
// `android { kotlinOptions { } }` block (Kotlin 2.x).
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Shared Room infrastructure (data package). KSP + room-compiler run on the
    // library's @Entity/@Database (`FOTLAB-DATABS-000001` R7).
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // User preferences (library display mode) kept out of the fs_node Room database.
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)

    // Image + video thumbnails and full-size loading for the library (content:// URIs,
    // automatic downsampling). Coil owns the memory + disk cache, so we never hand-manage one.
    // coil-video registers VideoFrameDecoder, letting AsyncImage pull a frame for video nodes.
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    // EXIF metadata readout for the viewer's detail panel.
    implementation(libs.androidx.exifinterface)

    // Coroutines are used directly by the media / studio pipeline (parallel sniffers, decode off
    // main thread). Declared explicitly rather than relied on transitively.
    implementation(libs.kotlinx.coroutines.android)

    // Runtime for the UniFFI-generated rawler_fotlab bindings. On Android JNA must come from the
    // `@aar` artefact — the plain jar ships desktop natives only — and a version catalog cannot
    // express that artifact type, so the coordinate is spelled out here with its version from the
    // catalog (`gradle/libs.versions.toml`). See FOTLAB-STUDIO-000001.
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)

    // Instrumented smoke tests, run on an emulator by `connectedDebugAndroidTest` in
    // .github/workflows/smoke_emulator.yaml. The cases live in the AGP-default instrumented
    // source set `app/src/androidTest/kotlin` — no extra `kotlin.srcDir` is needed because
    // `src/<source-set>/kotlin` is registered out of the box.
    // `runner` is what `testInstrumentationRunner` above names; `core` supplies
    // ActivityScenario and `ext-junit` the AndroidJUnit4 bridge.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // Intents.intending(): stub the system DocumentsUI answer of the grade bar's
    // OpenDocument contract (the SAF picker lives outside the app process).
    androidTestImplementation(libs.androidx.test.espresso.intents)
    // Compose UI tests (ZoomableGestureTest): the rule injects real multi-pointer events through
    // `performTouchInput`; `ui-test-manifest` supplies the empty activity the rule launches.
    // Both are BOM-managed, hence the platform() line on the androidTest configuration too.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.manifest)
}
