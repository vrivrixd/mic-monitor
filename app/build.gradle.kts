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
        versionCode = 11
        versionName = "1.1"
    }

    // A chave de assinatura nao fica no repositorio. Ela e colocada em
    // app/micmonitor.p12 na hora de compilar, e as senhas vem do ambiente.
    // Sem o arquivo, a compilacao acontece do mesmo jeito, so que sem assinatura.
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
}
