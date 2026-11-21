plugins {
    id("com.android.library")
}

extra["publishArtifactId"] = "admob-ads-nextgen"
apply(from = rootProject.file("gradle/publishing.gradle.kts"))

android {
    namespace = "com.kazmi.dev.adsnextgen"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api("com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.7.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
