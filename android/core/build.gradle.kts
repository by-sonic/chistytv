// Логика ЧистогоТВ без Android: переносится из src/main.ps1 и проверяется тестами на обычной JVM
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  // Интеграционный тест на настоящем adbd (эмулятор Android TV): обычный ADB-клиент для JVM
  testImplementation(libs.dadb)
}

tasks.test {
  // Корень репозитория: тесты берут ту же базу и фикстуры, что и тесты скрипта
  systemProperty("chistytv.repo", rootDir.parentFile.absolutePath)
  System.getenv("CHISTYTV_IT_ADB")?.let { environment("CHISTYTV_IT_ADB", it) }
  testLogging {
    events("passed", "skipped", "failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}
