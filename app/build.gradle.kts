plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

android {
    namespace = "com.projectreclaim.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.projectreclaim.app"
        minSdk = 29
        targetSdk = 37
        versionCode = 3
        versionName = "0.1.0-alpha01"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("androidTest").assets.directories.add(
            "$projectDir/schemas",
        )
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

val kotlinxSerializationVersion = libs.versions.kotlinxSerialization.get()
configurations.configureEach {
    if (name.endsWith("AndroidTestRuntimeClasspath")) {
        resolutionStrategy.force(
            "org.jetbrains.kotlinx:kotlinx-serialization-bom:$kotlinxSerializationVersion",
            "org.jetbrains.kotlinx:kotlinx-serialization-core:$kotlinxSerializationVersion",
            "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:$kotlinxSerializationVersion",
            "org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion",
            "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:$kotlinxSerializationVersion",
        )
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
