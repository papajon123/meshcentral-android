plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ru.papajon.meshcentralmobile"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "io.github.papajon123.meshcentral"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.5.1"
        // Keep dependency-only locales from taking precedence over a supported system fallback.
        resourceConfigurations.addAll(listOf(
            "en", "ru", "zh", "b+zh+Hans", "b+zh+Hant", "es", "fr", "pt", "de", "ar", "hi", "bn", "ur",
            "ja", "ko", "in", "tr", "vi", "th", "it", "fa", "ta", "te", "mr",
        ))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }

    val releaseKeystoreFile = System.getenv("ANDROID_RELEASE_KEYSTORE_FILE")
    val releasePassword = System.getenv("ANDROID_RELEASE_PASSWORD")
    if (!releaseKeystoreFile.isNullOrBlank() && !releasePassword.isNullOrBlank()) {
        signingConfigs {
            create("releaseLine") {
                storeFile = file(releaseKeystoreFile)
                storePassword = releasePassword
                keyAlias = System.getenv("ANDROID_RELEASE_KEY_ALIAS") ?: "meshcentral_release"
                keyPassword = releasePassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.findByName("releaseLine")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
