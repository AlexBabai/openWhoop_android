plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

import org.gradle.internal.os.OperatingSystem
import java.util.Properties

android {
    namespace = "dev.openwhoop.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.openwhoop.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(layout.buildDirectory.dir("generated/rustJniLibs"))
        }
    }
}

val rustCrateDir = layout.projectDirectory.dir("../rust/openwhoop-android-algos")
val cargoProfile = "release"
val androidTargets = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7-linux-androideabi",
    "x86" to "i686-linux-android",
    "x86_64" to "x86_64-linux-android",
)

fun androidLinker(target: String): String {
    val localPropertiesSdkDir = providers.provider {
        val localProperties = rootProject.file("local.properties")
        if (!localProperties.isFile) {
            null
        } else {
            Properties().apply {
                localProperties.inputStream().use(::load)
            }.getProperty("sdk.dir")
        }
    }
    val ndkHome = providers
        .environmentVariable("ANDROID_NDK_HOME")
        .orElse(providers.environmentVariable("ANDROID_NDK_ROOT"))
        .orElse(providers.environmentVariable("ANDROID_HOME").map { "$it/ndk/27.0.12077973" })
        .orElse(providers.environmentVariable("ANDROID_SDK_ROOT").map { "$it/ndk/27.0.12077973" })
        .orElse(localPropertiesSdkDir.map { "$it/ndk/27.0.12077973" })
        .get()
    val hostTag = when {
        OperatingSystem.current().isLinux -> "linux-x86_64"
        OperatingSystem.current().isMacOsX -> "darwin-x86_64"
        OperatingSystem.current().isWindows -> "windows-x86_64"
        else -> error("Unsupported host OS for Android NDK")
    }
    val toolchain = "$ndkHome/toolchains/llvm/prebuilt/$hostTag/bin"
    val executable = when (target) {
        "aarch64-linux-android" -> "aarch64-linux-android35-clang"
        "armv7-linux-androideabi" -> "armv7a-linux-androideabi35-clang"
        "i686-linux-android" -> "i686-linux-android35-clang"
        "x86_64-linux-android" -> "x86_64-linux-android35-clang"
        else -> error("Unsupported Android Rust target: $target")
    }
    return "$toolchain/$executable"
}

val buildRustAlgos = tasks.register("buildRustAlgos") {
    group = "build"
    description = "Builds the Rust openwhoop-algos JNI library for Android ABIs."
    inputs.dir(rustCrateDir.dir("src"))
    inputs.file(rustCrateDir.file("Cargo.toml"))
    inputs.file(rustCrateDir.file("Cargo.lock"))

    androidTargets.forEach { (abi, target) ->
        val outputDir = layout.buildDirectory.dir("generated/rustJniLibs/$abi")
        outputs.file(outputDir.map { it.file("libopenwhoop_android_algos.so") })
        doLast {
            exec {
                workingDir = rustCrateDir.asFile
                environment("CC_${target.replace('-', '_')}", androidLinker(target))
                environment("CARGO_TARGET_${target.uppercase().replace('-', '_')}_LINKER", androidLinker(target))
                commandLine("cargo", "build", "--target", target, "--profile", cargoProfile)
            }
            copy {
                from(rustCrateDir.file("target/$target/$cargoProfile/libopenwhoop_android_algos.so"))
                into(outputDir.get())
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(buildRustAlgos)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.health.connect.client)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
