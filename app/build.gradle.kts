plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNo = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "de.ofen.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.hdr.aduro"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNo
        versionName = "1.0.$buildNo"
    }

    // Fester Schlüssel, damit sich neue Versionen über die alte installieren lassen.
    // Der Schlüssel liegt passwortgeschützt im Repo, das Passwort nur als
    // GitHub-Secret OFEN_KEY_PASSWORD. Ohne Passwort entsteht nur eine unsignierte APK.
    val keyPw = System.getenv("OFEN_KEY_PASSWORD")
    signingConfigs {
        if (!keyPw.isNullOrBlank()) {
            create("ofen") {
                storeFile = file("release.keystore")
                storePassword = keyPw
                keyAlias = "ofen"
                keyPassword = keyPw
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (!keyPw.isNullOrBlank()) signingConfig = signingConfigs.getByName("ofen")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Gemeinsamer Kern mit dem Raspi (Ofen, Homematic, Steuerungs-Übergabe)
    sourceSets["main"].java.srcDir("../shared/src/main/kotlin")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Barcode-/QR-Scanner von Google Play-Diensten (eigene Kamera-Oberfläche, keine Kamera-Berechtigung nötig)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // Texterkennung (z. B. „Password: …“ auf dem Typenschild), Modell kommt über die Play-Dienste
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
}
