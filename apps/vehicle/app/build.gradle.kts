import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Keep credentials outside source/Gradle properties and never print their values.
val releaseSigningEnvironment = listOf(
    "CARPLAY_RELEASE_KEYSTORE", "CARPLAY_RELEASE_KEY_ALIAS",
    "CARPLAY_RELEASE_STORE_PASSWORD", "CARPLAY_RELEASE_KEY_PASSWORD",
    "CARPLAY_RELEASE_CERT_SHA256",
).associateWith { providers.environmentVariable(it).orNull }
val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    group = "verification"
    description = "Reject absent, development, expired or unexpected release signing credentials."
    doLast {
        val missing = releaseSigningEnvironment.filterValues { it.isNullOrBlank() }.keys
        check(missing.isEmpty()) { "Release signing is required. Missing environment variables: ${missing.joinToString()}. Demo uses its separate development signature." }
        val certificatePin = releaseSigningEnvironment.getValue("CARPLAY_RELEASE_CERT_SHA256")!!
            .replace(":", "").lowercase()
        check(certificatePin.matches(Regex("[0-9a-f]{64}"))) { "Release certificate SHA-256 must contain 64 hexadecimal digits." }
        val keystoreFile = file(releaseSigningEnvironment.getValue("CARPLAY_RELEASE_KEYSTORE")!!)
        check(keystoreFile.isFile) { "Release keystore file is unavailable." }
        try {
            val store = KeyStore.getInstance(keystoreFile,
                releaseSigningEnvironment.getValue("CARPLAY_RELEASE_STORE_PASSWORD")!!.toCharArray())
            val alias = releaseSigningEnvironment.getValue("CARPLAY_RELEASE_KEY_ALIAS")!!
            check(!alias.equals("androiddebugkey", ignoreCase = true))
            check(store.getKey(alias, releaseSigningEnvironment.getValue("CARPLAY_RELEASE_KEY_PASSWORD")!!.toCharArray()) is PrivateKey)
            val certificate = store.getCertificate(alias) as X509Certificate
            certificate.checkValidity()
            check(!certificate.subjectX500Principal.name.contains("Android Debug", ignoreCase = true))
            val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                .joinToString("") { "%02x".format(it) }
            check(actual == certificatePin)
        } catch (_: Exception) {
            // Provider exceptions may contain paths/aliases. Do not print credentials or their values.
            throw GradleException("Release signing validation failed: verify private key access, certificate validity and SHA-256 pin; development certificates are forbidden.")
        }
    }
}

val mediaProbeRequested = providers.gradleProperty("onuMediaProbe").orNull?.toBooleanStrict() ?: false

android {
    namespace = "dev.carplaymate.demo"
    compileSdk = 35
    useLibrary("android.car", false)
    testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")
    // Register the separate fixture runner only when testing the demo APK.
    // A build-type-named androidTest directory alone is not consumed by this AGP setup.
    if (testBuildType == "demo") {
        sourceSets.getByName("androidTest").manifest.srcFile("src/androidTestDemo/AndroidManifest.xml")
    }
    defaultConfig {
        applicationId = "dev.carplaymate.demo"
        minSdk = 28
        targetSdk = 35
        versionCode = 63
        versionName = "0.58.0"
        // Pleos task restoration conflicts with mixed launcher/media discovery in the probe.
        // Release and ordinary demo never activate that experimental surface.
        buildConfigField("boolean", "LETTER_MEDIA_BROWSER_ENABLED", "false")
        manifestPlaceholders["letterMediaBrowserEnabled"] = "false"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("marketRelease") {
            storeFile = releaseSigningEnvironment["CARPLAY_RELEASE_KEYSTORE"]?.let { file(it) }
            keyAlias = releaseSigningEnvironment["CARPLAY_RELEASE_KEY_ALIAS"]
            storePassword = releaseSigningEnvironment["CARPLAY_RELEASE_STORE_PASSWORD"]
            keyPassword = releaseSigningEnvironment["CARPLAY_RELEASE_KEY_PASSWORD"]
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "PLEOS_ENABLED", "false")
            // The upstream speech SDK logs recognition payloads unless R8 strips its logger.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro", "proguard-test-interfaces.pro")
        }
        release {
            // Keep the reviewed illustration bytes intact in packaged resources.
            isCrunchPngs = false
            isDebuggable = false
            isJniDebuggable = false
            signingConfig = signingConfigs.getByName("marketRelease")
            buildConfigField("boolean", "PLEOS_ENABLED", "true")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro", "proguard-pleos-release.pro")
        }
        create("demo") {
            initWith(getByName("release"))
            isShrinkResources = true
            applicationIdSuffix = ".demo"
            versionNameSuffix = if (mediaProbeRequested) "-media-probe" else "-demo"
            buildConfigField("boolean", "LETTER_MEDIA_BROWSER_ENABLED", mediaProbeRequested.toString())
            manifestPlaceholders["letterMediaBrowserEnabled"] = mediaProbeRequested.toString()
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            proguardFiles("proguard-demo-runtime.pro", "proguard-test-interfaces.pro")
        }
    }
    androidResources { noCompress += "wav" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Only release consumes this prerequisite; demo/debug remain usable without release keys.
tasks.matching { it.name == "preReleaseBuild" || it.name == "validateSigningRelease" }
    .configureEach { dependsOn(validateReleaseSigning) }

dependencies {
    // Tink (via SDK security dependency) references these annotations during R8 analysis.
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
