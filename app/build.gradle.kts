plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ir.pcpapc172.gdlauncher1"
    compileSdk = 34

    defaultConfig {
        applicationId = "ir.pcpapc172.gdlauncher1"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "2.1.0"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = System.getenv("KEYSTORE_PATH")
            val storePass = System.getenv("KEYSTORE_STORE_PASSWORD")
            val keyAlias = System.getenv("KEYSTORE_KEY_ALIAS")
            val keyPass = System.getenv("KEYSTORE_KEY_PASSWORD")

            val values = listOf(keystoreFile, storePass, keyAlias, keyPass)
            require(values.all { it == null } || values.all { !it.isNullOrBlank() }) {
                "Provide all release-signing environment variables together"
            }
            if (keystoreFile != null && storePass != null && keyAlias != null && keyPass != null) {
                storeFile = file(keystoreFile)
                storePassword = storePass
                this.keyAlias = keyAlias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    packaging {
        resources.excludes +=
            setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.android.tools.build:apksig:8.2.2")
    implementation("org.smali:dexlib2:2.5.2")
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
