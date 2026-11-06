plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "vikify.vpn.probe"
    compileSdk = 35
    defaultConfig {
        applicationId = "vikify.vpn.probe"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    val emulatorFiles = rootProject.file("app/build/emulator-native")
    if (emulatorFiles.resolve("AndroidManifest.xml").isFile) {
        sourceSets.getByName("debug").manifest.srcFile(emulatorFiles.resolve("AndroidManifest.xml"))
        sourceSets.getByName("debug").res.srcDir(emulatorFiles.resolve("res"))
    }
}
dependencies {
    androidTestImplementation("androidx.annotation:annotation:1.8.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
