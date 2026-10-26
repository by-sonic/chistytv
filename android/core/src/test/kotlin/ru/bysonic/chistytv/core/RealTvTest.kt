package ru.bysonic.chistytv.core

import dadb.AdbKeyPair
import dadb.Dadb
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Те же операции на настоящем adbd по сети — эмулятор Android TV 9 или живой телевизор:
 *   CHISTYTV_IT_ADB=127.0.0.1:15555 ./gradlew :core:test --tests '*RealTvTest*'
 * Без переменной тест пропускается. Нужен интернет: APK качаются с GitHub, как у пользователя.
 */
class RealTvTest {
  private class DadbAdb(private val dadb: Dadb) : Adb {
    override fun shell(command: String): String = dadb.shell(command).allOutput.trim()

    override fun push(source: File, remotePath: String) {
      // Права 0644 явно: на Windows у файлов нет unix-атрибутов, которые dadb читает по умолчанию
      dadb.push(source, remotePath, 420, source.lastModified())
    }
  }

  private val repo = File(System.getProperty("chistytv.repo"))
  private val db = Db.parse(File(repo, "data/packages.json").readText())
  private val lines = mutableListOf<Line>()
  private val text get() = lines.joinToString("\n") { it.text }

  @Before
  fun need() = assumeTrue("CHISTYTV_IT_ADB не задан — пропускаю тест на настоящем ТВ", target != null)

  private fun session(): TvSession {
    val net = HttpNet()
    return TvSession(DadbAdb(connection), db, workDir, ApkCache(File(workDir, "apk"), net), net, Output { lines += it; println("  ${it.tone} ${it.text}") })
  }

  @Test
  fun `сведения о телевизоре и платформа`() {
    val s = session()
    println("ТВ: ${s.info.title}, Android ${s.info.android} (SDK ${s.info.sdk}), ${s.info.abiList}, приложений ${s.info.installed.size}")
    assertTrue(s.info.sdk >= 21)
    assertTrue(s.info.installed.size > 20)
    assertTrue("Android TV распознан: ${s.platforms.map { it.id }}", s.platforms.any { it.id == "google" })
  }

  @Test
  fun `чистка по настоящей базе и откат`() {
    val s = session()
    val recommended = s.plan().filter { it.action == "disable" }
    println("Рекомендовано к отключению: ${recommended.map { it.id }}")
    assumeTrue("на этой прошивке нечего отключать", recommended.isNotEmpty())
    val r = s.clean(recommended)
    val disabledNow = TvInfo.parsePackages(s.adb.shell("pm list packages -d"))
    assertTrue(text, recommended.all { it.id in disabledNow })
    assertEquals(recommended.size, r.disable!!.done.size)
    assertTrue("журнал на ТВ", s.adb.shell("cat ${TvStore.TV_JOURNAL}").contains(recommended[0].id))

    s.restore()
    val after = TvInfo.parsePackages(s.adb.shell("pm list packages -d"))
    assertTrue(text, recommended.none { it.id in after })
  }

  @Test
  fun `DNS включается с проверкой и выключается`() {
    val s = session()
    val r = s.enableDns()
    println("DNS: $r")
    assertTrue(r in setOf(TvSession.DnsResult.ENABLED, TvSession.DnsResult.ENABLED_UNCHECKED, TvSession.DnsResult.ROLLED_BACK))
    if (r != TvSession.DnsResult.ROLLED_BACK) assertTrue(s.dnsState().enabled)
    s.disableDns()
    assertTrue(!s.dnsState().enabled)
  }

  @Test
  fun `LTvLauncher с GitHub ставится и становится главным экраном`() {
    val s = session()
    if (!s.launcherInstalled && ApkSources.abi(s.info) == "x86") {
      // У LTvLauncher нет x86-сборки: на x86-телевизоре честно объясняем, а не падаем молча
      assertTrue(!s.installLauncher())
      assertTrue(text, text.contains("нет сборки под процессор"))
      assumeTrue("LTvLauncher не ставится на x86 — главный экран проверяется на ARM-телевизоре", false)
    }
    if (!s.launcherInstalled) assertTrue(text, s.installLauncher())
    assertTrue(s.launcherInstalled)
    val home = s.makeLauncherHome()
    println("Главный экран: $home, фокус: ${s.focusedActivity()}")
    assertTrue(text, home != TvSession.HomeResult.NO_ACTIVITY)
  }

  @Test
  fun `SmartTube и Happ с GitHub, подписка уходит в Happ`() {
    val s = session()
    if (!s.smartTubeCurrent) assertTrue(text, s.installSmartTube())
    if (!s.happInstalled && ApkSources.abi(s.info) == "x86") {
      assertTrue(!s.installHapp())
      assertTrue(text, text.contains("нет сборки под процессор"))
      assumeTrue("У Happ нет x86-сборки — подписка проверяется на ARM-телевизоре", false)
    }
    if (!s.happInstalled) assertTrue(text, s.installHapp())
    assertTrue(s.smartTubeCurrent && s.happInstalled)
    assertEquals(text, TvSession.HappResult.Sent, s.sendHappSubscription("https://example.com/sub/chistytv-test"))
  }

  companion object {
    private val target: String? = System.getenv("CHISTYTV_IT_ADB")
    private val workDir: File by lazy { Files.createTempDirectory("chistytv-it").toFile() }

    private val connection: Dadb by lazy {
      val (host, port) = target!!.split(":").let { it[0] to (it.getOrNull(1)?.toInt() ?: 5555) }
      // Постоянный ключ, как ~/.android/adbkey у компьютера: «Всегда разрешать» на ТВ нажимается один раз
      val dir = File(System.getProperty("user.home"), ".chistytv").apply { mkdirs() }
      val priv = File(dir, "it-adbkey")
      val pub = File(dir, "it-adbkey.pub")
      if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
      Dadb.create(host, port, AdbKeyPair.read(priv, pub), connectTimeout = 10_000, socketTimeout = 120_000)
    }

    @JvmStatic
    @AfterClass
    fun close() {
      if (target != null) runCatching { connection.close() }
    }
  }
}
