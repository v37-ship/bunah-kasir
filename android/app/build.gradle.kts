import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.bunah.kasir"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.bunah.kasir"
        minSdk = 24
        targetSdk = 34
        // Single version source: ../../shared-spec/VERSION (e.g. "1.9")
        val wanggaVersion = providers.fileContents(rootProject.layout.projectDirectory.file("../shared-spec/VERSION")).asText.get().trim()
        versionCode = wanggaVersion.replace(".", "").toInt()
        versionName = wanggaVersion
    }
    // Release signing comes from android/keystore.properties (local) or env vars (CI).
    // Every signed build — local or CI — uses the same key, so APKs upgrade in place.
    signingConfigs {
        create("release") {
            val props = Properties().apply {
                val f = rootProject.layout.projectDirectory.file("keystore.properties").asFile
                if (f.exists()) f.inputStream().use { load(it) }
            }
            // property key in keystore.properties -> environment variable used by CI
            val envKeys = mapOf(
                "storeFile" to "BUNAH_KEYSTORE_FILE",
                "storePassword" to "BUNAH_STORE_PASSWORD",
                "keyAlias" to "BUNAH_KEY_ALIAS",
                "keyPassword" to "BUNAH_KEY_PASSWORD",
            )
            fun p(k: String) =
                (envKeys[k]?.let { providers.environmentVariable(it).orNull } ?: props.getProperty(k))
            storeFile = p("storeFile")?.let { file(rootProject.layout.projectDirectory.file(it)) }
            storePassword = p("storePassword")
            keyAlias = p("keyAlias")
            keyPassword = p("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (signingConfigs.getByName("release").storeFile != null)
                signingConfigs.getByName("release") else null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // JVM unit tests (GoldenTest) read the shared fixtures directly from shared-spec/golden,
    // so they compile against the same inputs as the C# smoke tool and the Python verifier.
    sourceSets { getByName("test") { resources.srcDir("../../shared-spec/golden") } }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(bom); androidTestImplementation(bom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

