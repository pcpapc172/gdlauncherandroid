plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace  = "ir.pcpapc172.gdlauncher1"
    compileSdk = 34

    defaultConfig {
        applicationId   = "ir.pcpapc172.gdlauncher1"
        minSdk          = 24
        targetSdk       = 34
        versionCode     = 2
        versionName     = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = System.getenv("KEYSTORE_PATH")
            val storePass    = System.getenv("KEYSTORE_STORE_PASSWORD")
            val keyAlias     = System.getenv("KEYSTORE_KEY_ALIAS")
            val keyPass      = System.getenv("KEYSTORE_KEY_PASSWORD")

            if (keystoreFile != null && storePass != null && keyAlias != null && keyPass != null) {
                storeFile     = file(keystoreFile)
                storePassword = storePass
                this.keyAlias = keyAlias
                keyPassword   = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig   = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}
