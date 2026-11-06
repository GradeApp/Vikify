import java.io.File
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * Release signing lookup order (first match wins):
 *   1. Direct env vars: VIKIFY_RELEASE_STORE_FILE / _STORE_PASSWORD / _KEY_ALIAS / _KEY_PASSWORD.
 *   2. External props file via env var VIKIFY_KEYSTORE_PROPERTIES (absolute path outside repo).
 *   3. Repo-root keystore.properties (legacy; discouraged, gitignored).
 * Never commit keystore or passwords. See docs/user-guide/USER_GUIDE.en.md.
 */
fun loadReleaseSigningProperties(repoRoot: File): Properties? {
    val env = System.getenv()
    val storeFromEnv = env["VIKIFY_RELEASE_STORE_FILE"]
    if (!storeFromEnv.isNullOrBlank()) {
        return Properties().apply {
            setProperty("storeFile", storeFromEnv)
            setProperty("storePassword", env["VIKIFY_RELEASE_STORE_PASSWORD"].orEmpty())
            setProperty("keyAlias", env["VIKIFY_RELEASE_KEY_ALIAS"].orEmpty())
            setProperty("keyPassword", env["VIKIFY_RELEASE_KEY_PASSWORD"].orEmpty())
        }
    }
    val externalPath = env["VIKIFY_KEYSTORE_PROPERTIES"]
    if (!externalPath.isNullOrBlank()) {
        val ext = File(externalPath)
        if (ext.isFile) {
            return Properties().apply { ext.inputStream().use { load(it) } }
        }
    }
    val f = File(repoRoot, "keystore.properties")
    if (!f.exists()) return null
    return Properties().apply { f.inputStream().use { load(it) } }
}

val releaseSigningProps: Properties? = loadReleaseSigningProperties(rootProject.projectDir)
val emulatorBuild = providers.gradleProperty("emulator").orNull == "true"
val publicPolicyUrl = providers.gradleProperty("privacyPolicyUrl").getOrElse("https://94043.net/vikify/privacy.html")
if (publicPolicyUrl.isNotEmpty()) {
    val uri = URI(publicPolicyUrl)
    require(uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.userInfo == null &&
        uri.query == null && uri.fragment == null && uri.toASCIIString() == publicPolicyUrl &&
        publicPolicyUrl.none { it == '\'' || it == '"' }) {
        "privacyPolicyUrl must be a public HTTPS URL without credentials, query or fragment"
    }
}
val releaseSigningConfigured: Boolean =
    releaseSigningProps?.let { p ->
        !p.getProperty("storeFile").isNullOrBlank() &&
            !p.getProperty("storePassword").isNullOrBlank() &&
            !p.getProperty("keyAlias").isNullOrBlank() &&
            !p.getProperty("keyPassword").isNullOrBlank()
    } ?: false

android {
    namespace = "vikify.vpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "vikify.vpn"
        minSdk = 26
        targetSdk = 36
        // Bump for every private release distributed to users (Android requires strictly increasing).
        versionCode = 22
        versionName = "1.6.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "EMULATOR_TESTING", emulatorBuild.toString())
        buildConfigField("String", "PUBLIC_PRIVACY_URL", "\"" + publicPolicyUrl + "\"")
        ndk {
            abiFilters += if (emulatorBuild) listOf("x86_64") else listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (releaseSigningConfigured && releaseSigningProps != null) {
            create("release") {
                val p = releaseSigningProps
                val path = p.getProperty("storeFile")!!.trim()
                val keystoreFile = File(path).takeIf { it.isAbsolute } ?: rootProject.file(path)
                storeFile = keystoreFile
                require(keystoreFile.exists()) {
                    "Keystore not found at ${keystoreFile.absolutePath} (storeFile is relative to repo root unless absolute)"
                }
                storePassword = p.getProperty("storePassword")!!
                keyAlias = p.getProperty("keyAlias")!!
                keyPassword = p.getProperty("keyPassword")!!
            }
        }
    }

    buildTypes {
        debug {
            if (providers.gradleProperty("isolatedUiQa").orNull == "true") applicationIdSuffix = ".uiqa"
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            if (providers.gradleProperty("isolatedReleaseQa").orNull == "true") applicationIdSuffix = ".releaseqa"
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    if (emulatorBuild) {
        require(layout.buildDirectory.file("emulator-native/jniLibs/x86_64/libxray.so").get().asFile.isFile) {
            "Run scripts/prepare-emulator-native.ps1 before building with -Pemulator=true."
        }
        sourceSets.getByName("debug").jniLibs.srcDir(layout.buildDirectory.dir("emulator-native/jniLibs"))
        val emulatorManifest = layout.buildDirectory.file("emulator-native/AndroidManifest.xml").get().asFile
        if (emulatorManifest.isFile) {
            sourceSets.getByName("debug").manifest.srcFile(emulatorManifest)
            sourceSets.getByName("debug").res.srcDir(layout.buildDirectory.dir("emulator-native/res"))
            sourceSets.getByName("debug").assets.srcDir(layout.buildDirectory.dir("emulator-native/assets"))
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
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

gradle.taskGraph.whenReady {
    if (emulatorBuild && allTasks.any { it.name.contains("Release") }) {
        throw GradleException("Emulator native libraries are for debug testing only.")
    }
    val releasePackagingTasks = setOf("assembleRelease", "bundleRelease", "packageRelease", "signReleaseBundle")
    val needsSignedRelease = allTasks.any { it.project == project && it.name in releasePackagingTasks }
    if (needsSignedRelease && !releaseSigningConfigured) {
        throw org.gradle.api.GradleException(
            "Signed release requires signing credentials. Use either:\n" +
                "  • Repository root file keystore.properties (see keystore.properties.example), or\n" +
                "  • Environment variables VIKIFY_RELEASE_STORE_FILE, VIKIFY_RELEASE_STORE_PASSWORD, " +
                "VIKIFY_RELEASE_KEY_ALIAS, VIKIFY_RELEASE_KEY_PASSWORD.\n" +
                "See docs/RELEASE_SIGNING.md"
        )
    }
    if (needsSignedRelease) {
        val config = android.signingConfigs.getByName("release")
        val keys = KeyStore.getInstance(requireNotNull(config.storeFile), requireNotNull(config.storePassword).toCharArray())
        val certificate = requireNotNull(keys.getCertificate(config.keyAlias)) { "Release certificate is missing" }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
        require(fingerprint == "570b4ad5bbe8ab617b8164226b07be21e8011d977bbe7c4e7070a5b1581e37f0") {
            "Signing certificate differs from the historical Vikify production certificate. See docs/RELEASE_SIGNING.md"
        }
    }
}

dependencies {
    implementation(files("libs/tun2socks.aar"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
