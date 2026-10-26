// libadb-android 3.1.1 (MuntashirAkon) с исправлениями гонок — подробности в README.md рядом.
// Лицензия: GPL-3.0-or-later OR Apache-2.0 (используем на условиях Apache-2.0), части — BSD-3-Clause.
plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "io.github.muntashirakon.adb"
  compileSdk = 36
  defaultConfig {
    minSdk = 24
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

dependencies {
  implementation("androidx.annotation:annotation:1.9.1")
  implementation("org.bouncycastle:bcprov-jdk15to18:1.81")
  implementation("com.github.MuntashirAkon.spake2-java:spake2-android:2.2.1")
}
