plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.xgimi.translator"
    compileSdk = 35
    defaultConfig {
        applicationId = "fr.xgimi.translator"
        minSdk = 26
        targetSdk = 33
        versionCode = 5
        versionName = "0.4.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // vrai org.json pour les tests JVM (celui d'android.jar est un stub)
}
