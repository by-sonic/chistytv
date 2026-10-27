import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// Ключ подписи релиза лежит вне репозитория: ~/.chistytv/signing.properties
val signing = Properties().apply {
  val f = File(System.getProperty("user.home"), ".chistytv/signing.properties")
  if (f.exists()) f.inputStream().use { load(it) }
}

android {
  namespace = "ru.bysonic.chistytv"
  compileSdk = 36
  defaultConfig {
    applicationId = "ru.bysonic.chistytv"
    minSdk = 24
    targetSdk = 36
    versionCode = 2
    versionName = "1.0.1"
  }

  signingConfigs {
    if (signing.isNotEmpty()) {
      create("release") {
        storeFile = File(signing.getProperty("storeFile"))
        storePassword = signing.getProperty("storePassword")
        keyAlias = signing.getProperty("keyAlias")
        keyPassword = signing.getProperty("keyPassword")
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (signing.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = true
    shaders = false
  }

  sourceSets {
    // База пакетов — та же data/packages.json, что у скрипта: одна правда на двоих
    getByName("main").assets.directories.add(rootDir.parentFile.resolve("data").path)
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
      excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(project(":core"))

  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  debugImplementation(libs.androidx.compose.ui.tooling)

  // ADB по сети с телефона: обычная отладка (порт 5555) и подключение по коду Android 11+ (TLS)
  implementation(project(":libadb"))
  implementation(libs.bouncycastle.pkix)
  implementation(libs.conscrypt.android)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
}
