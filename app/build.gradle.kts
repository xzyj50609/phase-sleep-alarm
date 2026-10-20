plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val syncLicenseAssets by tasks.registering(Sync::class) {
    from(rootProject.file("LICENSE"), rootProject.file("THIRD-PARTY-NOTICES.md"))
    from(rootProject.file("licenses")) { include("*.txt") }
    into(layout.buildDirectory.dir("generated/licenseAssets/licenses"))
}

android {
    namespace = "com.example.smartalarm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.smartalarm"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "1.4.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testApplicationId = "com.example.smartalarm.instrumentation"
    }

    signingConfigs {
        create("official") {
            // 正式签名材料只通过 PHASE_SIGNING_DIR 提供；没有时 release 回退到 debug 签名，方便自行构建。
            val signingDir = providers.environmentVariable("PHASE_SIGNING_DIR").orNull?.let(::file)
            val passwordFile = signingDir?.resolve("store-password.txt")
            if (signingDir != null && passwordFile != null && passwordFile.exists()) {
                storeFile = signingDir.resolve("phase-release.p12")
                storePassword = passwordFile.readText().trim()
                keyAlias = "phase"
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        create("coexist") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
            matchingFallbacks += listOf("debug")
        }
        release {
            val official = signingConfigs.getByName("official")
            signingConfig = if (official.storeFile != null) official else signingConfigs.getByName("debug")
            testProguardFiles("proguard-test-rules.pro")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        aidl = true
    }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/licenseAssets"))
    // 共存包复用正式版直通实现，不带 files/fault.txt 控制的验收注入。
    sourceSets.getByName("coexist").java.srcDir("src/release/kotlin")
}

tasks.named("preBuild") { dependsOn(syncLicenseAssets) }

dependencies {
    implementation(project(":core"))

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Core
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.activity:activity-compose:1.10.1")

    // root 桥接：仅 core + service 两个模块，6.0.0（用户 2026-09-30 批准，其余 libsu 模块未批准）
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("com.github.topjohnwu.libsu:service:6.0.0")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")

}
