// Нативная часть конвертации Dolby Vision Profile 7 -> 8.1: JNI-мост dovi_bridge + статическая libdovi.
// Модуль подключается только с -Pdovi=true (см. settings.gradle.kts) и собирает AAR в app/libs:
//   ./gradlew -Pdovi=true :dovi:assembleRelease
// Обычная сборка приложения и CI его не трогают: AAR лежит в app/libs готовым. Инструкция - docs/ru/native-libs.md.

plugins {
    alias(libs.plugins.android.library)
}

// Официальный тег libdovi (quietvoid/dovi_tool). Заголовок src/main/cpp/include/libdovi/rpu_parser.h
// должен соответствовать этой версии
val libdoviTag = "libdovi-3.3.2"

// ABI через запятую: -PdoviAbis=arm64-v8a,armeabi-v7a,x86_64. По умолчанию - ARM (ТВ-приставки и телефоны)
val doviAbis: List<String> = (findProperty("doviAbis") as String? ?: "arm64-v8a,armeabi-v7a")
    .split(',').map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "top.rootu.dddplayer.dovi"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 23
        ndk { abiFilters += doviAbis }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

val buildLibdovi by tasks.registering(Exec::class) {
    description = "Builds static libdovi from quietvoid/dovi_tool for the selected ABIs"
    inputs.property("tag", libdoviTag)
    inputs.property("abis", doviAbis)
    outputs.files(doviAbis.map { file("src/main/cpp/libdovi/$it/libdovi.a") })
    commandLine(listOf("bash", file("build_libdovi.sh").path, libdoviTag) + doviAbis)
}
tasks.named("preBuild") { dependsOn(buildLibdovi) }

// Готовый AAR кладется в app/libs под постоянным именем
val copyAarToApp by tasks.registering(Copy::class) {
    from(layout.buildDirectory.file("outputs/aar/dovi-release.aar"))
    into(rootProject.file("app/libs"))
    rename { "lib-dovi-release.aar" }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(copyAarToApp) }
