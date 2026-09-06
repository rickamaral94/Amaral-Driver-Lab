plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.amaral.driverlab.bench"
    compileSdk = 35

    defaultConfig { minSdk = 30 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(project(":core-driver"))
    api(project(":core-stats"))
    api(project(":core-telemetry"))
    api(project(":core-vk"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
