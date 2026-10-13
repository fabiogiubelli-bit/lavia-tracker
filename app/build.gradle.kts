import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Legge un valore da: variabile d'ambiente, local.properties, oppure -P da riga di comando.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: (findProperty(name) as String?)?.takeIf { it.isNotBlank() }
        ?: ""

android {
    namespace = "cc.lavia.tracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "cc.lavia.tracker"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Token pubblico Mapbox (pk....) incorporato nell'APK. Se vuoto, l'app lo chiede al primo avvio.
        resValue("string", "lavia_mapbox_token", secret("MAPBOX_PUBLIC_TOKEN"))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        resValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.mapbox.maps:android-ndk27:11.32.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
}
