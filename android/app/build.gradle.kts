plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.alexecgo.registroxau"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.alexecgo.registroxau"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        // Solo procesadores de 64 bits ARM (todos los móviles actuales, incluido el POCO X7 Pro).
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        getByName("debug") {
            // Clave fija guardada en el repo para que cada APK nueva se instale encima de la anterior.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Abre el registro web a pantalla completa dentro de la app (Trusted Web Activity).
    implementation("com.google.androidbrowserhelper:androidbrowserhelper:2.5.0")
    // Inicio de sesión de Google nativo para el registro dentro de la app.
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // Leer capturas de MT5 en el propio móvil (sin enviar la imagen a ningún sitio).
    // Versión de Google Play Services: el modelo no va dentro de la APK (la APK pesa ~40 MB menos).
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
}
