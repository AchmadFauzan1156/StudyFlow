import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// Tanda tangan release dibaca dari keystore.properties (tidak di-commit) atau environment variable CI.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

fun signingValue(key: String, env: String): String? = providers.environmentVariable(env).orNull ?: keystoreProps.getProperty(key)

android {
    namespace = "id.studyflow.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "id.studyflow"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Naikkan setiap rilis agar APK baru bisa dipasang menimpa versi lama.
        versionCode = 2
        versionName = providers.gradleProperty("studyflow.version").get()
    }

    val storeFilePath = signingValue("storeFile", "STUDYFLOW_KEYSTORE_FILE")
    signingConfigs {
        if (storeFilePath != null) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = signingValue("storePassword", "STUDYFLOW_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "STUDYFLOW_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "STUDYFLOW_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Tanpa keystore, APK release tetap dibuat tetapi tidak bertanda tangan (tidak bisa dipasang).
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(projects.shared)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
}
