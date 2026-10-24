pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Pleos Connect SDK (Vehicle, NaviHelper)
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
    }
}

rootProject.name = "chargecheck"

// 순수 Kotlin(JVM) 모듈: Android SDK 없이도 빌드·테스트된다.
include(
    ":core:model",
    ":core:port",
    ":core:config",
    ":feature:session",
)

// Android 모듈: Android SDK가 있는 환경(Android Studio, ANDROID_HOME 설정)에서만 포함한다.
val androidSdkAvailable: Boolean =
    System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }

if (androidSdkAvailable) {
    include(
        ":app",
        ":ui",
        ":core:data",
        ":feature:warning",
        ":feature:review",
        ":feature:complete",
        ":adapter:pleos",
        ":adapter:android",
        ":adapter:remote",
        ":adapter:fake",
    )
}
