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
        // O numero interno precisa subir a cada envio, senao o Android recusa a
        // atualizacao. O nome visivel continua em 1.0 ate o projeto ficar pronto.
        versionCode = 8
        versionName = "1.0"
    }

    // Chave fixa guardada no projeto. Sem ela o servidor de compilacao criaria uma
    // chave diferente a cada execucao e o aparelho recusaria a instalacao por cima.
    signingConfigs {
        create("stable") {
            storeFile = file("micmonitor.p12")
            storeType = "PKCS12"
            storePassword = "micmonitor"
            keyAlias = "micmonitor"
            keyPassword = "micmonitor"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
}
