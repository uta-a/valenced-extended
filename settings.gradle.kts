pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://api.xposed.info/")
    }
}

rootProject.name = "Valenced"
include(":android")
include(":android:api")
include(":bundle")
include(":core")

val ANDROID_PATCHES_BUILD_NAME = "android-patches"

val wantsPatches = gradle.startParameter.taskNames.any { it.startsWith(":$ANDROID_PATCHES_BUILD_NAME") }
val hasReseam = System.getenv("RESEAM_BIN")?.isNotBlank() == true ||
    System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, "reseam").canExecute() }

if (wantsPatches || hasReseam) includeBuild("android/patches") { name = ANDROID_PATCHES_BUILD_NAME }

// cursed system for ios on linux
if (!System.getProperty("os.name").equals("Mac OS X", ignoreCase = true)) {
    val wantsIos = gradle.startParameter.taskNames.any { task ->
        if (task.startsWith("-")) return@any false
        task.contains("ios", ignoreCase = true) ||
            task.contains("konan", ignoreCase = true) ||
            task.contains("tweak", ignoreCase = true) ||
            !task.contains(':')
    }
    if (wantsIos) {
        gradle.beforeProject {
            extensions.extraProperties.set("valenced.appleTargets", "true")
        }
    }
}
