plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.example.phaseupgradeprobe"
    compileSdk = 35
    defaultConfig { applicationId = "com.example.phaseupgradeprobe"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1" }
    signingConfigs {
        create("official") {
            val dir = providers.environmentVariable("PHASE_SIGNING_DIR").orNull?.let(::file)
            if (dir != null && dir.resolve("store-password.txt").exists()) {
                storeFile = dir.resolve("phase-release.p12")
                storePassword = dir.resolve("store-password.txt").readText().trim()
                keyAlias = "phase"; keyPassword = storePassword
            }
        }
    }
    buildTypes { debug { signingConfigs.getByName("official").takeIf { it.storeFile != null }?.let { signingConfig = it } } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.test:runner:1.6.2")
    implementation("androidx.test.ext:junit:1.2.1")
    // The independent probe must share binary-compatible public runtime classes with both targets.
    implementation("androidx.lifecycle:lifecycle-common:2.9.0")
    implementation("androidx.tracing:tracing:1.2.0")
}
