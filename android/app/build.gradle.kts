import java.util.Properties
import java.security.KeyStore
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Increment both values for every APK distributed to testers. Keep versionCode
// strictly increasing so Android always accepts the newer package as an update.
val appVersionCode = 31
val appVersionName = "0.1.30"

// Temporary shared debug identity used by every authorized Hermes Remote build host. The private
// keystore stays outside Git at ~/.android/debug.keystore; only its public certificate digest is
// safe to keep here. This prevents another agent/machine from silently shipping an incompatible APK.
val expectedDebugCertificateSha256 =
    "06C18DFC4A852330654C2DA040A578BCCAB13B71DDE4AC962BB9BC2271DD32C5"
val canonicalDebugKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")

// Release signing is driven by a gitignored keystore.properties at the repo root.
// When absent (e.g. a fresh clone or CI without secrets), release builds stay unsigned.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.hermes.client"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.hermes.remote"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "com.hermes.client.HiltTestRunner"
        // App name; the beta build type overrides this so both can be installed at once.
        manifestPlaceholders["appLabel"] = "Hermes Remote"
        buildConfigField("String", "UPDATE_INDEX_URL", "\"https://mrlgs.net/releases/index.json\"")
        buildConfigField("String", "EXPECTED_UPDATE_CERT_SHA256", "\"$expectedDebugCertificateSha256\"")
    }
    signingConfigs {
        // Do not rely on AGP's environment-dependent default debug keystore lookup. CI runners
        // may redirect ANDROID_USER_HOME and silently sign with a generated key even after the
        // canonical ~/.android/debug.keystore has been verified. Pin every debug APK to the exact
        // file whose certificate is checked below so all authorized build hosts stay compatible.
        getByName("debug") {
            storeFile = canonicalDebugKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            // Sign with the release config only when the keystore is present.
            signingConfig = signingConfigs.findByName("release")
        }
        // Beta channel: a separate applicationId + "Hermes Beta" label so testers can run
        // the beta alongside the production app. Cut from the `dev` branch as a GitHub
        // pre-release. Inherits the release signing config.
        create("beta") {
            initWith(getByName("release"))
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
            manifestPlaceholders["appLabel"] = "Hermes Remote Beta"
            signingConfig = signingConfigs.findByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }

    // Let stubbed android.* calls (e.g. android.util.Log) return defaults instead of throwing
    // in local JVM unit tests, so pure logic that mirrors to logcat stays unit-testable.
    testOptions { unitTests.isReturnDefaultValues = true }

    // Build daemon runs on JBR (JDK 21); emit JVM 17 bytecode for Android.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Keep Gradle's canonical app-debug.apk intact for tooling, and automatically
// stage the tester-facing APK under a stable, versioned filename after each build.
// Sync uses an isolated directory so an older version can never be handed off by mistake.
val stageDebugApk = tasks.register<Sync>("stageDebugApk") {
    group = "distribution"
    description = "Stages the debug APK with its version in the filename."
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(layout.buildDirectory.dir("outputs/apk/distribution/debug"))
    rename { "Hermes-Remote-$appVersionName-debug.apk" }
}

val verifyDebugSigningKey = tasks.register("verifyDebugSigningKey") {
    group = "verification"
    description = "Rejects builds that do not use the shared Hermes Remote debug certificate."
    doLast {
        check(canonicalDebugKeystore.isFile) {
            "Missing shared Hermes Remote debug keystore at ${canonicalDebugKeystore.path}. " +
                "Ask the project owner for secure provisioning; do not generate a replacement."
        }
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            canonicalDebugKeystore.inputStream().use { load(it, "android".toCharArray()) }
        }
        val certificate = checkNotNull(keyStore.getCertificate("androiddebugkey")) {
            "Shared debug keystore does not contain androiddebugkey."
        }
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { "%02X".format(it) }
        check(actual == expectedDebugCertificateSha256) {
            "Wrong Hermes Remote debug signing certificate: $actual. " +
                "Expected $expectedDebugCertificateSha256."
        }
    }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    dependsOn(verifyDebugSigningKey)
    finalizedBy(stageDebugApk)
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.material)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // Hilt 2.59.2 bundles kotlin-metadata-jvm capped at metadata 2.3.0, but Kotlin 2.3.10 emits
    // 2.4.0. Dagger 2.57+ unshades kotlin-metadata-jvm, so pin a matching version on the Hilt
    // processing classpaths (KSP + the plugin's javac aggregation) to read the newer metadata.
    val kotlinMetadataJvm = "org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlin.get()}"
    ksp(kotlinMetadataJvm)
    annotationProcessor(kotlinMetadataJvm)
    kspAndroidTest(kotlinMetadataJvm)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)
    implementation(libs.markdown.m3)
    implementation(libs.zxing.embedded)
    implementation(libs.glance.appwidget)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.hilt.testing)
    androidTestImplementation(libs.androidx.test.runner)
    kspAndroidTest(libs.hilt.compiler)
}
