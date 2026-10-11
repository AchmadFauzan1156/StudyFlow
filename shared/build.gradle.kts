import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// ---------- Konfigurasi publik aplikasi (bukan rahasia) ----------
// Dibaca dari environment variable atau local.properties (tidak di-commit). Nilai kosong = fitur akun nonaktif.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

fun configValue(propertyKey: String, envKey: String): String =
    providers.environmentVariable(envKey).orNull ?: localProperties.getProperty(propertyKey) ?: ""

val appConfigValues = mapOf(
    "supabaseUrl" to configValue("studyflow.supabase.url", "STUDYFLOW_SUPABASE_URL"),
    "supabasePublishableKey" to configValue("studyflow.supabase.publishableKey", "STUDYFLOW_SUPABASE_PUBLISHABLE_KEY"),
    "apiBaseUrl" to configValue("studyflow.api.baseUrl", "STUDYFLOW_API_BASE_URL"),
    "androidApiBaseUrl" to configValue("studyflow.api.baseUrl.android", "STUDYFLOW_API_BASE_URL_ANDROID"),
    "appVersion" to providers.gradleProperty("studyflow.version").get(),
)

val generateAppConfig = tasks.register("generateAppConfig") {
    val values = appConfigValues
    val outputDir = layout.buildDirectory.dir("generated/appconfig/kotlin")
    inputs.properties(values)
    outputs.dir(outputDir)
    doLast {
        fun literal(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
        val file = outputDir.get().file("id/studyflow/config/AppConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            buildString {
                appendLine("// Dibuat otomatis oleh :shared:generateAppConfig. Jangan diedit.")
                appendLine("package id.studyflow.config")
                appendLine()
                appendLine("object AppConfig {")
                values.forEach { (key, value) -> appendLine("    const val $key: String = ${literal(value)}") }
                appendLine("}")
            },
        )
    }
}

kotlin {
    compilerOptions {
        // Room memakai expect object untuk RoomDatabaseConstructor.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "id.studyflow.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        androidResources {
            enable = true
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.material.icons.core)
            implementation(libs.compose.ui.tooling.preview)

            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.navigation.compose)

            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)

            api(project.dependencies.platform(libs.koin.bom))
            api(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        commonMain {
            kotlin.srcDir(generateAppConfig)
        }
        androidMain.dependencies {
            implementation(libs.koin.android)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.jna.platform)
        }
        jvmTest.dependencies {
            // viewModelScope memakai Dispatchers.Main; di JVM disediakan coroutines-swing.
            implementation(libs.kotlinx.coroutines.swing)
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.ui.test)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspJvm", libs.androidx.room.compiler)
}

room {
    schemaDirectory("$projectDir/schemas")
}
