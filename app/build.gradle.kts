plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.linuxsuren.message"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.linuxsuren.message"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        release {
            // Minify is intentionally off: keep the client debuggable and free
            // of proguard surprises; nothing here relies on reflection.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Same zero-heavy-dependency policy as the reference project: HTTP is a
    // plain java.net.HttpURLConnection wrapper, JSON is org.json from the
    // platform, UI is plain android.app.Activity + XML layouts via ViewBinding.
    // The only additions: coroutines, and RecyclerView — the required message
    // list widget, which ships only as an androidx artifact.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
