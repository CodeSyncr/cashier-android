plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

// Published as com.github.CodeSyncr:cashier-android through JitPack (see
// jitpack.yml); VERSION comes from the git tag JitPack builds.
val sdkVersion: String = (findProperty("VERSION") as String?) ?: "1.0.0"

android {
    namespace = "com.nimbus.cashier"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "VERSION", "\"$sdkVersion\"")
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    // The Compose compiler that matches Kotlin 1.9.0.
    composeOptions { kotlinCompilerExtensionVersion = "1.5.1" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.CodeSyncr"
            artifactId = "cashier-android"
            version = sdkVersion
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Nimbus Cashier for Android")
                description.set("Google Play subscriptions verified by your Nimbus Cashier app, gated on entitlements.")
                url.set("https://github.com/CodeSyncr/cashier-android")
                licenses { license { name.set("MIT"); url.set("https://opensource.org/licenses/MIT") } }
            }
        }
    }
}

dependencies {
    api("com.android.billingclient:billing-ktx:7.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Paywalls: Compose UI + foundation only (no Material, no image or
    // video libraries — images load through a small built-in loader and
    // video plays through the platform MediaPlayer).
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    api(composeBom)
    api("androidx.compose.runtime:runtime")
    api("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.0")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub on the JVM; tests use the real one.
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
