plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// One signing key for every published APK. Android installs an update only over an app signed
// with the same key; CI used to make a fresh debug key per run, so each release forced people to
// uninstall (losing local data). CI writes the key from the COMPANION_KEYSTORE_B64 secret;
// without it (local builds) the usual per-machine debug key is used.
val sharedKeystore = System.getenv("COMPANION_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "ru.keegoo.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.keegoo.companion"
        minSdk = 29
        targetSdk = 35
        versionCode = 13
        versionName = "0.7.1"
    }

    signingConfigs {
        if (sharedKeystore != null) {
            create("shared") {
                storeFile = sharedKeystore
                storePassword = System.getenv("COMPANION_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("COMPANION_KEY_ALIAS") ?: "companion"
                keyPassword = System.getenv("COMPANION_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (sharedKeystore != null) signingConfig = signingConfigs.getByName("shared")
            // 10.0.2.2 — localhost внутри Android-эмулятора
            buildConfigField("String", "API_BASE_URL", "\"https://api.94-183-236-169.sslip.io/\"")
            // TODO: вставить ключ из дашборда AppMetrica, когда будет получен
            buildConfigField("String", "APPMETRICA_KEY", "\"\"")
        }
        release {
            // R8 stays off: it renamed the fields of every Gson/Retrofit model (0.6.1–0.7.0 sent
            // {"a":…} to the server and parsed empty tokens → a registration storm, no forecasts).
            // The APK is small; if minify ever comes back, proguard-rules.pro keeps the models.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "API_BASE_URL", "\"https://api.94-183-236-169.sslip.io/\"")
            // TODO: вставить ключ из дашборда AppMetrica, когда будет получен
            buildConfigField("String", "APPMETRICA_KEY", "\"\"")
            if (sharedKeystore != null) signingConfig = signingConfigs.getByName("shared")
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
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.health.connect)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)
    implementation(libs.datastore.preferences)
    implementation(libs.appmetrica.sdk)

    debugImplementation(libs.androidx.ui.tooling)
}
