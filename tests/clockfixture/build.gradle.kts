plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.example.phasefixture"
    compileSdk = 35
    defaultConfig { minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1" }
    flavorDimensions += "clock"
    productFlavors {
        create("first") { dimension = "clock"; applicationId = "com.example.phasefixture.first"; manifestPlaceholders["clockLabel"] = "测试时钟 A" }
        create("second") { dimension = "clock"; applicationId = "com.example.phasefixture.second"; manifestPlaceholders["clockLabel"] = "测试时钟 B" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
