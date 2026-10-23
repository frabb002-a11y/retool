plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "com.unexpectedpanda.retool"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.unexpectedpanda.retool"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "2.4.9"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        buildPython("python3")
        android.defaultConfig.ndk {
            abiFilters.clear()
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        pip {
            install("lxml")
            install("strictyaml")
            install("validators")
        }
    }
}

val stageRetoolPython =
    tasks.register<Copy>("stageRetoolPython") {
        val dest = layout.projectDirectory.dir("src/main/python").asFile
        doFirst {
            dest.deleteRecursively()
            dest.mkdirs()
        }
        from(rootProject.file("../retool.py"))
        into(dest)
    }

tasks.register<Copy>("stageRetoolModules") {
    dependsOn(stageRetoolPython)
    from(rootProject.file("../modules")) {
        include("**/*.py")
        exclude("gui/**")
    }
    into(layout.projectDirectory.dir("src/main/python/modules"))
}

tasks.configureEach {
    if (
        name != "stageRetoolPython" &&
        name != "stageRetoolModules" &&
        (name.contains("Python", ignoreCase = true) || name.contains("chaquopy", ignoreCase = true))
    ) {
        dependsOn("stageRetoolModules")
    }
}

tasks.named("preBuild").configure {
    dependsOn("stageRetoolModules")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
