plugins { id("com.android.application") }

android {
    namespace = "org.necmobile"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "org.necmobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.2.4-alpha"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild { cmake { path = file("../native/CMakeLists.txt") } }
    sourceSets.getByName("main").jniLibs.srcDir("../third_party/np2kai-binary-packaged")
    packaging { jniLibs.useLegacyPackaging = true }
}

val buildPc98Core by tasks.registering(Exec::class) {
    workingDir("..")
    commandLine("python", "-X", "utf8", "scripts/build_np2kai.py")
}
val stagePc98Core by tasks.registering(Copy::class) {
    dependsOn(buildPc98Core)
    from("../work/np2kai-build/libs/arm64-v8a/libretro.so")
    into("../third_party/np2kai-binary-packaged/arm64-v8a")
    rename { "libnp2kai.so" }
}
tasks.named("preBuild") { dependsOn(stagePc98Core) }
