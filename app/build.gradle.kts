import org.gradle.api.tasks.Exec

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val stableStore = rootProject.file("webresearch.keystore")

val materializeV79 = tasks.register<Exec>("materializeV79") {
    val patch = rootProject.file("scripts/apply_v79.py")
    onlyIf { patch.exists() }
    workingDir(rootProject.projectDir)
    commandLine("python3", patch.absolutePath)
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(materializeV79)
}

android {
    namespace = "ru.evrasia.research"
    compileSdk = 35
    defaultConfig {
        applicationId = "ru.evrasia.research"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = System.getenv("WEB_RESEARCH_VERSION") ?: "dev"
    }
    signingConfigs {
        if (stableStore.exists()) {
            create("stable") {
                storeFile = stableStore
                storePassword = "webresearch"
                keyAlias = "webresearch"
                keyPassword = "webresearch"
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (stableStore.exists()) signingConfig = signingConfigs.getByName("stable")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
