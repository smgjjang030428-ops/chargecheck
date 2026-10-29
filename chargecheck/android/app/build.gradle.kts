import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// 배포 서버 주소. local.properties 의 chargecheck.serverUrl 로 바꿀 수 있다(배포 후 Render 주소).
val localProps = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load) }
val releaseServer = localProps.getProperty("chargecheck.serverUrl") ?: "https://chargecheck-ap2h.onrender.com"
// release 서명 키. 키 파일 위치와 비밀번호는 local.properties 에만 둔다. 없으면 서명되지 않은 release가 만들어진다.
val releaseKeystore = localProps.getProperty("chargecheck.keystore")?.let(::file)?.takeIf { it.exists() }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.chargecheck.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.chargecheck.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = localProps.getProperty("chargecheck.keystorePassword")
                keyAlias = localProps.getProperty("chargecheck.keyAlias")
                keyPassword = localProps.getProperty("chargecheck.keyPassword")
            }
        }
    }

    buildTypes {
        // debug = 에뮬레이터에서 개발 PC 서버(10.0.2.2 = PC의 127.0.0.1), release = 배포 서버(HTTPS)
        getByName("debug") { buildConfigField("String", "SERVER_URL", "\"http://10.0.2.2:8000\"") }
        getByName("release") {
            buildConfigField("String", "SERVER_URL", "\"$releaseServer\"")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
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
    buildFeatures { compose = true; buildConfig = true }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:port"))
    implementation(project(":core:config"))
    implementation(project(":feature:session"))
    implementation(project(":feature:warning"))
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
    implementation(libs.compose.foundation)
}