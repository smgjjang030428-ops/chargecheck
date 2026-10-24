import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.chargecheck.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.chargecheck.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // real = Pleos·Android 어댑터, demo = Fake 어댑터 + 시나리오 재생(시연 영상용)
    flavorDimensions += "mode"
    productFlavors {
        create("real") { dimension = "mode" }
        create("demo") { dimension = "mode"; applicationIdSuffix = ".demo" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:port"))
    implementation(project(":core:config"))
    implementation(project(":core:data"))
    implementation(project(":feature:session"))
    implementation(project(":feature:warning"))
    implementation(project(":feature:review"))
    implementation(project(":feature:complete"))
    implementation(project(":ui"))
    implementation(project(":adapter:remote"))
    "realImplementation"(project(":adapter:pleos"))
    "realImplementation"(project(":adapter:android"))
    "demoImplementation"(project(":adapter:fake"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
}