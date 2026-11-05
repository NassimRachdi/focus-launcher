plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Valeurs injectées par la compilation GitHub (voir .github/workflows/build.yml)
val ciVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val updateUrl = (project.findProperty("updateUrl") as String?) ?: ""

android {
    namespace = "fr.nassim.focuslauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "fr.nassim.focuslauncher"
        minSdk = 21
        // Cible Android 5.1 : pas de permissions à l'exécution, file:// autorisé pour l'auto-mise à jour
        targetSdk = 22
        versionCode = ciVersionCode
        versionName = "1.0.$ciVersionCode"
        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("KEYSTORE_PATH")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val rel = signingConfigs.getByName("release")
            signingConfig = if (rel.storeFile != null) rel else signingConfigs.getByName("debug")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
