package ru.bysonic.chistytv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Те же сценарии, что test/run-tests.ps1 у скрипта, на том же фейковом телевизоре и той же тестовой базе. */
class TvSessionTest {
  private val repo = File(System.getProperty("chistytv.repo"))
  private val db = Db.parse(File(repo, "test/fixtures/packages.json").readText())

  // Как в run-tests.ps1: что взять из базы для сценариев
  private val safe = db.packages.filter { it.action == "disable" && it.platform == "xiaomi" }.take(2).map { it.id }
  private val ask = db.packages.filter { it.action == "ask" && it.platform == "any" }.take(1).map { it.id }
  private val keep = db.packages.filter { it.action == "keep" }.take(1).map { it.id }
  private val detect = db.platforms.first { it.id == "xiaomi" }.detectPackages.take(1)
  private val installed = listOf("com.android.settings", "com.android.systemui") + safe + ask + keep + detect + listOf("com.unknown.app")

  private class Run(val session: TvSession, val lines: MutableList<Line>, val root: File) {
    val text: String get() = lines.joinToString("\n") { it.text }
  }

  private fun run(tv: FakeTv, net: Net = StubNet()): Run {
    val lines = mutableListOf<Line>()
    val root = Files.createTempDirectory("chistytv-test").toFile()
    val s = TvSession(tv, db, root, ApkCache(File(root, "apk"), net), net, Output { lines += it }, sleep = {})
    return Run(s, lines, root)
  }

  /** «-Yes» у скрипта: рекомендованное отключаем, необязательное не трогаем */
  private fun TvSession.cleanDefaults() = clean(plan().filter { it.action == "disable" })

  @Test
  fun `в базе есть данные для теста`() {
    assertTrue(safe.isNotEmpty() && ask.isNotEmpty() && keep.isNotEmpty())
  }

  @Test
  fun `1 чистка отключает только рекомендованное и пишет журнал и бэкап`() {
    val tv = FakeTv(installed)
    val r = run(tv)
    val result = r.session.cleanDefaults()
    assertTrue("рекомендованные отключены", tv.disabled.containsAll(safe))
    assertTrue("необязательные не тронуты без вопроса", ask.none { it in tv.disabled })
    assertTrue("keep и неизвестные не тронуты", keep[0] !in tv.disabled && "com.unknown.app" !in tv.disabled && "com.android.settings" !in tv.disabled)
    assertTrue("журнал отката записан", r.session.store.journal.readText().lines().contains(safe[0]))
    assertTrue("журнал продублирован на телевизор", tv.files[TvStore.TV_JOURNAL].orEmpty().contains(safe[0]))
    assertTrue("бэкап сделан", File(result.backup, "packages.txt").exists())
    assertTrue("подсказали про рекламные ряды штатного лаунчера", r.text.contains("рисует сам штатный лаунчер"))
  }

  @Test
  fun `2 откат включает всё обратно`() {
    val tv = FakeTv(installed)
    val r = run(tv)
    r.session.cleanDefaults()
    val restored = r.session.restore()
    assertTrue(r.text, tv.disabled.isEmpty())
    assertEquals(safe.toSet(), restored.restored.toSet())
    assertTrue("журнал отложен в сторону", r.session.store.dir.listFiles()!!.any { it.name.startsWith("disabled-by-chistytv.txt.restored-") })
  }

  @Test
  fun `2б откат по журналу на телевизоре — с другого телефона`() {
    val tv = FakeTv(installed)
    run(tv).session.cleanDefaults()
    // Новый телефон: своего журнала нет, но на ТВ он остался
    val other = run(tv)
    other.session.restore()
    assertTrue(other.text, tv.disabled.isEmpty())
  }

  @Test
  fun `3 прошивка запрещает отключение — понятное сообщение`() {
    val tv = FakeTv(installed, refuse = true)
    val r = run(tv)
    val result = r.session.cleanDefaults()
    assertTrue(r.text, r.text.contains("Прошивка не даёт отключать"))
    assertTrue(result.disable!!.refused)
  }

  @Test
  fun `4 подписка уходит в Happ через happ add`() {
    val tv = FakeTv(installed + listOf("com.happproxy", "org.smarttube.stable"))
    val r = run(tv)
    assertTrue(r.session.happInstalled && r.session.smartTubeCurrent)
    assertEquals(TvSession.HappResult.Sent, r.session.sendHappSubscription("https://example.com/sub/abc123"))
    assertEquals(1, tv.started.count { it.contains("happ://add/https://example.com/sub/abc123") })
    r.session.sendHappSubscription("https://example.com/sub?id=1&lang=ru")
    assertEquals("ссылка с & в параметрах проходит", 1, tv.started.count { it.contains("-p com.happproxy -d 'happ://add/https://example.com/sub?id=1&lang=ru'") })
  }

  @Test
  fun `5 подозрительная ссылка отвергается`() {
    val tv = FakeTv(installed + "com.happproxy")
    val r = run(tv)
    assertEquals(TvSession.HappResult.BadUrl, r.session.sendHappSubscription("https://x.com/'; reboot"))
    assertTrue(r.text.contains("не похоже на ссылку"))
    assertTrue(tv.started.isEmpty())
  }

  @Test
  fun `6 DNS включается`() {
    val tv = FakeTv(installed)
    assertEquals(TvSession.DnsResult.ENABLED, run(tv).session.enableDns())
    assertEquals("hostname", tv.dnsMode)
    assertEquals("dns.adguard-dns.com", tv.dnsSpec)
  }

  @Test
  fun `6б провайдер режет DNS-over-TLS — откат сам`() {
    val tv = FakeTv(installed, dotBlocked = true)
    val r = run(tv)
    assertEquals(TvSession.DnsResult.ROLLED_BACK, r.session.enableDns())
    assertEquals("opportunistic", tv.dnsMode)
    assertEquals("null", tv.dnsSpec)
    assertTrue("объяснили причину", r.text.contains("порт 853"))
  }

  @Test
  fun `6в нет интернета — включаем без проверки и предупреждаем`() {
    val tv = FakeTv(installed, offline = true)
    val r = run(tv)
    assertEquals(TvSession.DnsResult.ENABLED_UNCHECKED, r.session.enableDns())
    assertTrue(r.text.contains("нет интернета"))
  }

  @Test
  fun `6г DNS на Android 8 не включаем`() {
    val tv = FakeTv(installed)
    tv.props["ro.build.version.sdk"] = "26"
    assertEquals(TvSession.DnsResult.TOO_OLD, run(tv).session.enableDns())
    assertEquals("opportunistic", tv.dnsMode)
  }

  @Test
  fun `7 главный экран — прошивка слушается set-home-activity`() {
    val tv = FakeTv(installed + "com.leanbitlab.ltvL", homeWorks = true)
    val r = run(tv)
    assertEquals(TvSession.HomeResult.OPENS, r.session.makeLauncherHome())
    assertEquals("com.leanbitlab.ltvL/com.leanbitlab.ltvL.MainActivity", tv.home)
    assertTrue(r.text.contains("открывает LTvLauncher"))
  }

  @Test
  fun `8 главный экран — прошивка упрямится, штатный лаунчер без согласия цел`() {
    val tv = FakeTv(installed + listOf("com.leanbitlab.ltvL", "com.test.launcher"))
    val r = run(tv)
    assertEquals(TvSession.HomeResult.STUBBORN, r.session.makeLauncherHome())
    assertTrue(r.text.contains("Home Button Fix"))
    assertTrue(tv.disabled.isEmpty())
  }

  @Test
  fun `9 жёсткий режим — парный пакет владеет Wi-Fi, ничего не отключаем`() {
    val tv = FakeTv(installed + listOf("com.leanbitlab.ltvL", "com.test.stock", "com.test.wraith"), wifiOwner = "cname=com.test.wraith")
    val r = run(tv)
    r.session.makeLauncherHome()
    assertEquals(TvSession.HardResult.WIFI_OWNER, r.session.disableStockLauncher())
    assertTrue(r.text.contains("потеряет Wi-Fi"))
    assertTrue(tv.disabled.isEmpty())
  }

  @Test
  fun `10 жёсткий режим — лаунчер отключается вместе с парным пакетом`() {
    val tv = FakeTv(installed + listOf("com.leanbitlab.ltvL", "com.test.stock", "com.test.wraith"))
    val r = run(tv)
    assertEquals(listOf("com.test.stock", "com.test.wraith"), r.session.stockLaunchers().map { it.id })
    r.session.makeLauncherHome()
    assertEquals(TvSession.HardResult.DONE, r.session.disableStockLauncher())
    assertTrue(tv.disabled.containsAll(listOf("com.test.stock", "com.test.wraith")))
    assertTrue(r.text.contains("Готово"))
  }

  @Test
  fun `12 пустое пояснение — без висящего тире`() {
    val tv = FakeTv(installed)
    val r = run(tv)
    r.session.cleanDefaults()
    assertFalse(r.text, Regex(" — ?$", RegexOption.MULTILINE).containsMatchIn(r.text))
    assertFalse(db.packages.any { Regex(" — ?$").containsMatchIn(it.label) })
  }

  // ─── То, чего не было в скрипте ─────────────────────────────────────

  @Test
  fun `платформа по производителю и пакетам`() {
    val tv = FakeTv(listOf("com.test.yandex.launcher"))
    val pls = run(tv).session.platforms.map { it.id }
    assertEquals(listOf("xiaomi", "yandex"), pls)
  }

  @Test
  fun `имя папки ТВ как у скрипта — и с кириллицей`() {
    fun info(m: String, model: String) = TvInfo(m, m, model, "9", 28, "", "", emptySet(), emptySet())
    assertEquals("Xiaomi-MiTV-MSSP0", info("Xiaomi", "MiTV-MSSP0").key)
    assertEquals("Hartens-HTY-24HDR06B-S2", info("Hartens", "HTY-24HDR06B-S2").key)
    assertEquals("Яндекс-ТВ_43", info("Яндекс", "ТВ 43").key)
    assertEquals("sdk_google_atv_x86", info("unknown", "sdk_google_atv_x86").title)
  }

  /** Регулярки ядра должны работать и на Android (ICU): там нет, например, флага (?U) */
  @Test
  fun `регулярки ядра без конструкций, которых нет на Android`() {
    val src = File(repo, "android/core/src/main/kotlin").walk().filter { it.extension == "kt" }
      .joinToString("\n") { f -> f.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n") }
    for (bad in listOf("(?U)", "(?u)", "(?<")) assertFalse("в ядре есть $bad", src.contains(bad))
  }

  @Test
  fun `мусор в выводе pm не становится пакетом`() {
    val p = TvInfo.parsePackages("package:com.a.b\nWARNING: linker: something\n\npackage:com.c.d \nError: oops")
    assertEquals(setOf("com.a.b", "com.c.d"), p)
  }

  @Test
  fun `установка Happ — передача файла и pm install`() {
    val tv = FakeTv(installed)
    val r = run(tv)
    assertTrue(r.session.installHapp())
    assertEquals(listOf("/data/local/tmp/chistytv.apk"), tv.pushed)
    assertTrue(tv.commands.contains("pm install -r /data/local/tmp/chistytv.apk"))
    assertTrue(tv.commands.contains("rm -f /data/local/tmp/chistytv.apk"))
    assertTrue(r.text.contains("Happ установлен"))
  }

  // 12б из run-tests.ps1: мало памяти на ТВ
  private fun apk1Mb(r: Run) = File(r.root, "test.apk").apply { writeBytes(ByteArray(1024 * 1024)) } // нужно около 3×1 + 50 = 53 МБ

  @Test
  fun `мало памяти заранее — кэш очищен и установлено`() {
    val tv = FakeTv(installed).apply { freeMb = 40; cacheMb = 500; needMb = 100 }
    val r = run(tv)
    assertTrue(r.session.installApk(apk1Mb(r), "SmartTube"))
    assertTrue(tv.trimmed)
    assertTrue(r.text, r.text.contains("мало памяти") && r.text.contains("SmartTube установлен"))
  }

  @Test
  fun `не хватило при установке — кэш очищен, вторая попытка удалась`() {
    val tv = FakeTv(installed).apply { freeMb = 60; cacheMb = 500; needMb = 100 }
    val r = run(tv)
    assertTrue(r.session.installApk(apk1Mb(r), "SmartTube"))
    assertTrue(tv.trimmed)
    assertTrue(r.text, r.text.contains("пробую ещё раз") && r.text.contains("SmartTube установлен"))
  }

  @Test
  fun `места нет совсем — подсказка, без стектрейса`() {
    val tv = FakeTv(installed).apply { freeMb = 40; cacheMb = 5; needMb = 100 }
    val r = run(tv)
    assertFalse(r.session.installApk(apk1Mb(r), "SmartTube"))
    assertTrue(r.text, r.text.contains("not enough space") && r.text.contains("Удалите ненужные приложения") && r.text.contains("свободно 45 МБ"))
    assertFalse(r.text, r.text.contains("ExceptionUtils"))
    assertEquals("кэш уже очищен заранее — повторно не ставим", 1, tv.commands.count { it.startsWith("pm install") })
  }

  @Test
  fun `ошибка pm — коротко, без стектрейса`() {
    assertEquals("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]", TvSession.shortError("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]"))
    // Как печатает Android 9 на эмуляторе: пустая строка, заголовок, исключение и стектрейс
    val pm9 = "\nException occurred while executing:\nandroid.os.ParcelableException: java.io.IOException: Requested internal only, but not enough space\n" +
      "\tat android.util.ExceptionUtils.wrap(ExceptionUtils.java:34)\nCaused by: java.io.IOException: Requested internal only, but not enough space"
    assertEquals("Requested internal only, but not enough space", TvSession.shortError(pm9))
    assertEquals("Error: что-то ещё", TvSession.shortError("Error: что-то ещё\n\tat x.y(Z.java:1)"))
  }

  @Test
  fun `обычная установка не трогает кэш`() {
    val tv = FakeTv(installed)
    val r = run(tv)
    assertTrue(r.session.installApk(apk1Mb(r), "SmartTube"))
    assertFalse(tv.trimmed)
    assertFalse(r.text.contains("Кэш"))
  }

  @Test
  fun `LTvLauncher — сборка под процессор телевизора`() {
    val net = StubNet(
      api = """{"tag_name":"v1.4","assets":[{"name":"LtvLauncher-v1.4-arm64-v8a-release.apk","browser_download_url":"https://x/arm64.apk"},
        {"name":"LtvLauncher-v1.4-armeabi-v7a-release.apk","browser_download_url":"https://x/v7a.apk"},
        {"name":"LtvLauncher-v1.4-universal-release.apk","browser_download_url":"https://x/u.apk"}]}""",
    )
    val tv = FakeTv(installed)
    assertTrue(run(tv, net).session.installLauncher())
    assertEquals(listOf("https://x/v7a.apk"), net.downloaded)
  }

  @Test
  fun `нет связи с GitHub — берём сохранённый APK`() {
    val tv = FakeTv(installed)
    val net = StubNet(fail = true)
    val r = run(tv, net)
    File(r.root, "apk").mkdirs()
    File(r.root, "apk/happ-Happ.apk").writeText("старый apk")
    assertTrue(r.session.installHapp())
    assertTrue(r.text.contains("Нет связи с GitHub"))
  }

  @Test
  fun `запасной импорт Happ — 5 цифр и ссылка в base64`() {
    val net = StubNet()
    val r = run(FakeTv(installed), net)
    assertFalse(r.session.happWebImport("1234", "https://example.com/s"))
    assertTrue(r.session.happWebImport("12345", "https://example.com/s"))
    assertEquals("https://check.happ.su/sendtv/12345", net.posted.single().first)
    assertEquals("""{"data":"aHR0cHM6Ly9leGFtcGxlLmNvbS9z"}""", net.posted.single().second)
  }

  @Test
  fun `откат без журнала — честно говорим`() {
    val r = run(FakeTv(installed))
    assertFalse(r.session.restore().hadJournal)
    assertTrue(r.text.contains("нет журнала"))
  }
}

/** Сеть для тестов: качает «APK» без интернета и запоминает запросы */
class StubNet(private val api: String = "{}", private val fail: Boolean = false) : Net {
  val downloaded = mutableListOf<String>()
  val posted = mutableListOf<Pair<String, String>>()

  override fun text(url: String): String {
    if (fail) throw IOException("нет сети")
    return api
  }

  override fun download(url: String, dest: File) {
    if (fail) throw IOException("нет сети")
    downloaded += url
    dest.writeText("apk")
  }

  override fun postJson(url: String, body: String): Int {
    posted += url to body
    return 200
  }
}
