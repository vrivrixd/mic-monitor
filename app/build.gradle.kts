plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vrivrixd.micmonitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vrivrixd.micmonitor"
        minSdk = 24
        targetSdk = 35
        // The internal number has to grow on every release, otherwise Android
        // refuses the update. The visible name is what the about screen shows.
        versionCode = 12
        versionName = "1.1"
    }

    // The signing key is not kept in the repository. It is placed at
    // app/micmonitor.p12 at build time, and the passwords come from the
    // environment. Without the file the build still runs, only unsigned.
    val keystore = file("micmonitor.p12")
    val signed = keystore.exists()

    signingConfigs {
        if (signed) {
            create("stable") {
                storeFile = keystore
                storeType = "PKCS12"
                storePassword = System.getenv("MIC_MONITOR_STORE_PASSWORD").orEmpty()
                keyAlias = System.getenv("MIC_MONITOR_KEY_ALIAS").orEmpty()
                keyPassword = System.getenv("MIC_MONITOR_KEY_PASSWORD").orEmpty()
            }
        }
    }

    buildTypes {
        debug {
            if (signed) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            if (signed) signingConfig = signingConfigs.getByName("stable")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
        viewBinding = true
    }

    // The built file carries the name and the version, instead of the
    // app-release.apk that Gradle would produce.
    applicationVariants.all {
        val mark = if (buildType.name == "release") "" else "-" + buildType.name
        val fileName = "MicMonitor-" + versionName + mark + ".apk"
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = fileName
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
}
