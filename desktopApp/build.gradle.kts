import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    // Memakai JDK yang menjalankan Gradle; bytecode ditarget 17.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val appVersion = providers.gradleProperty("studyflow.version").get()

dependencies {
    implementation(projects.shared)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "id.studyflow.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "StudyFlow"
            packageVersion = appVersion
            // Tanpa modul ini aplikasi hasil paket langsung tertutup saat dibuka (hasil suggestRuntimeModules).
            modules("java.instrument", "java.management", "jdk.unsupported")
            description = "Catatan kuliah, tugas, dan belajar dengan AI"
            vendor = "StudyFlow"
            windows {
                menuGroup = "StudyFlow"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Tetap sama di setiap versi agar installer baru menggantikan versi lama.
                upgradeUuid = "41f47992-8c7c-47a4-885e-e3dd2dcc99db"
            }
        }
    }
}

// Paket portabel (folder aplikasi + runtime Java bawaan) tanpa installer. MSI/EXE butuh WiX Toolset.
tasks.register<Zip>("packageZip") {
    group = "compose desktop"
    description = "Membuat StudyFlow-<versi>-windows.zip dari createDistributable."
    dependsOn("createDistributable")
    from(layout.buildDirectory.dir("compose/binaries/main/app"))
    archiveFileName.set("StudyFlow-$appVersion-${System.getProperty("os.name").substringBefore(' ').lowercase()}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
}
