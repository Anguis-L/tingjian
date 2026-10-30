plugins {
    id("com.android.application")
}

android {
    namespace = "com.tingjian.relay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tingjian.relay"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
