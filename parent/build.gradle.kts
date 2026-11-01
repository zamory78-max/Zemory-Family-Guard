import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing key comes from GitHub Secrets (see README). Without it the debug key is used.
val signingFile = System.getenv("SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }?.let { file(it) }?.takeIf { it.exists() }

val appVersionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.familyguard.parent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.familyguard.parent"
        minSdk = 24
        targetSdk = 35
        versionCode = appVersionCode
        versionName = "1.0.$appVersionCode"
    }

    signingConfigs {
        if (signingFile != null) {
            create("release") {
                storeFile = signingFile
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "zemory"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (signingFile != null) "release" else "debug")
        }
    }

    // The web dashboard (../web) is bundled as the app's assets.
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets", "../web")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
}
