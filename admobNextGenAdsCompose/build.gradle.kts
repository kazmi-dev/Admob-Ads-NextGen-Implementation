plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

extra["publishArtifactId"] = "admob-ads-nextgen-compose"
apply(from = rootProject.file("gradle/publishing.gradle.kts"))

android {
    namespace = "com.kazmi.dev.adsnextgen.compose"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
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
    api(project(":admobNextGenAds"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation-layout")
}
