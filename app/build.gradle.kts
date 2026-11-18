import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val nativeOutput = layout.buildDirectory.dir("generated/jniLibs")
val buildNative by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    val sdk = rootProject.file(providers.gradleProperty("nec.sdk").getOrElse(".cache/android-sdk"))
    val cmake = providers.gradleProperty("nec.cmake").getOrElse("cmake")
    commandLine("python", "-X", "utf8", "scripts/build_native.py", "--sdk", sdk.absolutePath,
        "--cmake", cmake, "--output", nativeOutput.get().asFile.absolutePath)
    inputs.dir(rootProject.file("native"))
    inputs.dir(rootProject.file("third_party/m88/src"))
    inputs.dir(rootProject.file("third_party/np2kai-source"))
    inputs.dir(rootProject.file("third_party/tinysoundfont"))
    inputs.dir(rootProject.file("third_party/fatfs"))
    inputs.dir(rootProject.file("scripts"))
    outputs.dir(nativeOutput)
}

android {
    namespace = "org.necmobile"
    compileSdk = 36
    compileSdkExtension = 19
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "org.necmobile"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersion.getProperty("versionCode").toInt()
        versionName = releaseVersion.getProperty("versionName")
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    signingConfigs {
        create("existing") {
            storeFile = rootProject.file(".cache/signing/development.jks")
            storePassword = "android"
            keyAlias = "necmobile"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("existing")
            isMinifyEnabled = false
        }
        getByName("debug") { signingConfig = signingConfigs.getByName("existing") }
    }
    sourceSets.getByName("main").jniLibs.srcDir(nativeOutput.get().asFile)
    providers.gradleProperty("nec.dosAssets").orNull?.let {
        sourceSets.getByName("main").assets.srcDir(rootProject.file(it))
    }
    packaging { jniLibs.useLegacyPackaging = true }
}

tasks.named("preBuild") { dependsOn(buildNative) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
}
