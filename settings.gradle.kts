pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 仅为 libsu（root 桥接，用户 2026-09-30 批准 core/service 6.0.0）开放 JitPack，其余依赖仍走上面两个源
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.topjohnwu.libsu") }
        }
    }
}

rootProject.name = "SmartAlarm"
include(":app")
include(":core")
include(":clockfixture")
project(":clockfixture").projectDir = file("tests/clockfixture")
include(":upgradeprobe")
project(":upgradeprobe").projectDir = file("tests/upgradeprobe")
