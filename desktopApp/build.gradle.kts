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
            packageVersion = "0.1.0"
            // Tanpa modul ini aplikasi hasil paket langsung tertutup saat dibuka (hasil suggestRuntimeModules).
            modules("java.instrument", "java.management", "jdk.unsupported")
        }
    }
}

// Paket portabel (folder aplikasi + runtime Java bawaan) tanpa installer. MSI/EXE butuh WiX Toolset.
tasks.register<Zip>("packageZip") {
    group = "compose desktop"
    description = "Membuat StudyFlow-<versi>-windows.zip dari createDistributable."
    dependsOn("createDistributable")
    from(layout.buildDirectory.dir("compose/binaries/main/app"))
    archiveFileName.set("StudyFlow-0.1.0-${System.getProperty("os.name").substringBefore(' ').lowercase()}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
}
