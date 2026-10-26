package ru.bysonic.chistytv.core

import java.io.File
import java.util.Base64

/**
 * Всё, что ЧистыйТВ делает с телевизором, — перенос src/main.ps1 один в один:
 * те же команды, те же проверки и защита, те же тексты. Вопросы скрипта («Д/н», номера)
 * здесь — параметры: их задаёт экран приложения.
 */
class TvSession(
  val adb: Adb,
  val db: Db,
  private val storeRoot: File,
  private val apks: ApkCache,
  private val net: Net,
  var out: Output = Output.None,
  private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
  val planner = Planner(db)

  var info: TvInfo = TvInfo.read(adb)
    private set

  val store: TvStore get() = TvStore(storeRoot, info, adb)

  fun refresh(): TvInfo = TvInfo.read(adb).also { info = it }

  val platforms: List<Platform> get() = planner.platforms(info)

  fun plan(): List<PackageEntry> = planner.plan(info)

  // ─── Чистка и откат ─────────────────────────────────────────────────

  data class DisableResult(val done: List<PackageEntry>, val failed: List<Pair<PackageEntry, String>>, val refused: Boolean, val notApplied: List<PackageEntry>)

  fun disable(entries: List<PackageEntry>): DisableResult {
    val done = mutableListOf<PackageEntry>()
    val failed = mutableListOf<Pair<PackageEntry, String>>()
    val store = store
    for (e in entries) {
      // По одному пакету на вызов: в цепочке команд pm может молча остановиться после первой
      val r = adb.shell("pm disable-user --user 0 ${e.id}")
      if (DISABLED.containsMatchIn(r)) {
        store.record(e.id)
        out.ok("${e.title} (${e.id})")
        done += e
      } else {
        out.warn("${e.id}: $r")
        failed += e to r
      }
    }
    val now = TvInfo.parsePackages(adb.shell("pm list packages -d"))
    val notApplied = entries.filter { it.id !in now }
    val refused = done.isEmpty() && entries.isNotEmpty()
    if (refused) {
      out.fail("Прошивка не даёт отключать пакеты. Тогда остаётся блокировка рекламы через DNS.")
    } else if (notApplied.isNotEmpty()) {
      out.warn("Не применилось: ${notApplied.joinToString(", ") { it.id }}")
    }
    return DisableResult(done, failed, refused, notApplied)
  }

  data class CleanResult(val backup: File?, val disable: DisableResult?, val homeHint: Boolean)

  /** Чистка: бэкап, отключение выбранного, подсказка про рекламные ряды штатного лаунчера */
  fun clean(selected: List<PackageEntry>): CleanResult {
    if (selected.isEmpty()) {
      out.ok("Известного мусора не нашёл — или он уже отключён.")
      return CleanResult(null, null, false)
    }
    val backup = store.backup(info)
    out.ok("Бэкап: ${backup.name}")
    val r = disable(selected)
    refresh()
    if (!r.refused) {
      out.ok("Готово. Перезагрузите телевизор и проверьте, что всё нужное работает.")
      out.hint("Что-то сломалось — откройте ЧистыйТВ и выберите «Вернуть как было».")
    }
    // Чистка убирает рекламные сервисы и слежку, но ряды «рекомендаций» на главном экране рисует сам
    // штатный лаунчер. Его не отключаем — иначе ТВ останется без главного экрана.
    val hint = LAUNCHER_PACKAGE !in info.installed
    if (hint) {
      out.warn(
        "Ряды с рекламой на главном экране рисует сам штатный лаунчер — чистка его не трогает, иначе ТВ " +
          "останется без главного экрана. Чтобы убрать и их, выберите «Новый главный экран без рекламных рядов».",
      )
    }
    return CleanResult(backup, r, hint)
  }

  data class RestoreResult(val restored: List<String>, val failed: List<String>, val hadJournal: Boolean)

  fun restore(): RestoreResult {
    val store = store
    val pkgs = store.recorded()
    if (pkgs.isEmpty()) {
      out.warn("Для этого телевизора нет журнала ЧистогоТВ — возвращать нечего.")
      return RestoreResult(emptyList(), emptyList(), false)
    }
    val ok = mutableListOf<String>()
    val bad = mutableListOf<String>()
    for (p in pkgs) {
      var r = adb.shell("pm enable $p")
      if (!r.contains("enabled", ignoreCase = true)) r = adb.shell("cmd package install-existing $p")
      if (Regex("enabled|installed", RegexOption.IGNORE_CASE).containsMatchIn(r)) {
        out.ok(p)
        ok += p
      } else {
        out.warn("$p: $r")
        bad += p
      }
    }
    store.archiveJournal()
    refresh()
    out.ok("Все отключённые ЧистымТВ приложения включены. Перезагрузите телевизор.")
    return RestoreResult(ok, bad, true)
  }

  // ─── Установка приложений ───────────────────────────────────────────

  fun installApk(file: File, label: String): Boolean {
    // push + pm install: на Android 14 «adb install» может зависнуть, ожидая подтверждения на экране
    val remote = "/data/local/tmp/chistytv.apk"
    try {
      adb.push(file, remote)
    } catch (e: Exception) {
      out.fail("Не удалось передать $label: ${e.message}")
      return false
    }
    val r = adb.shell("pm install -r $remote")
    adb.shell("rm -f $remote")
    if (r.contains("Success")) {
      out.ok("$label установлен")
      return true
    }
    out.fail("$label не установился: $r")
    if (r.contains("VERIFICATION_FAILURE")) out.hint("Прошивка запрещает установку сторонних приложений по ADB.")
    if (r.contains("NO_MATCHING_ABIS")) out.hint("У $label нет сборки под процессор этого телевизора (${info.abiList}).")
    return false
  }

  // ─── YouTube: SmartTube + Happ ──────────────────────────────────────

  /** Какая сборка SmartTube стоит: новая, старая (после смены ключа не обновляется) или никакой */
  val smartTubeInstalled: List<String> get() = SMARTTUBE_PACKAGES.filter { it in info.installed }

  val smartTubeCurrent: Boolean get() = smartTubeInstalled.any { it == "org.smarttube.stable" || it == "org.smarttube.beta" }

  val happInstalled: Boolean get() = HAPP_PACKAGE in info.installed

  fun installSmartTube(): Boolean {
    val old = smartTubeInstalled
    if (!smartTubeCurrent && old.isNotEmpty()) {
      out.warn("Стоит старая сборка SmartTube (${old.joinToString(", ")}): после смены ключа разработчика она не обновляется. Поставлю новую рядом, старую потом можно удалить.")
    }
    val apk = apks.get("smarttube", out) { ApkSources.smartTube(info) }
    return installApk(apk, "SmartTube").also { refresh() }
  }

  fun installHapp(): Boolean {
    val apk = apks.get("happ", out) { ApkSources.happ }
    return installApk(apk, "Happ").also { refresh() }
  }

  sealed interface HappResult {
    data object Sent : HappResult
    data object BadUrl : HappResult
    data class Rejected(val reason: String) : HappResult
  }

  fun sendHappSubscription(sub: String): HappResult {
    if (!isSubscriptionUrl(sub)) {
      out.fail("Это не похоже на ссылку подписки.")
      return HappResult.BadUrl
    }
    val r = adb.shell("am start -a android.intent.action.VIEW -p $HAPP_PACKAGE -d 'happ://add/$sub'")
    if (!Regex("Error|Exception|unable to resolve", RegexOption.IGNORE_CASE).containsMatchIn(r)) {
      out.ok("Подписка отправлена в Happ. На телевизоре подтвердите добавление и разрешите VPN-подключение.")
      out.hint("В Happ выберите сервер (лучше «Автовыбор») и включите VPN, затем откройте SmartTube.")
      return HappResult.Sent
    }
    val reason = r.lines().firstOrNull { Regex("Error|Exception", RegexOption.IGNORE_CASE).containsMatchIn(it) } ?: r
    out.warn("Happ не принял ссылку напрямую: $reason")
    return HappResult.Rejected(reason)
  }

  /** Официальный способ Happ для ТВ: «Web Import» на телевизоре показывает 5-значный код. Ссылка идёт через check.happ.su */
  fun happWebImport(code: String, sub: String): Boolean {
    if (!Regex("^\\d{5}$").matches(code)) {
      out.fail("Код — это 5 цифр.")
      return false
    }
    if (!isSubscriptionUrl(sub)) {
      out.fail("Это не похоже на ссылку подписки.")
      return false
    }
    val data = Base64.getEncoder().encodeToString(sub.toByteArray())
    return try {
      val status = net.postJson("https://check.happ.su/sendtv/$code", "{\"data\":\"$data\"}")
      if (status in 200..299) {
        out.ok("Отправлено. Подтвердите добавление подписки в Happ на телевизоре.")
        true
      } else {
        out.fail("Сервер Happ не принял код: HTTP $status")
        false
      }
    } catch (e: Exception) {
      out.fail("Сервер Happ не принял код: ${e.message}")
      false
    }
  }

  // ─── DNS ────────────────────────────────────────────────────────────

  data class DnsState(val mode: String, val spec: String) {
    val enabled get() = mode == "hostname"
  }

  fun dnsState(): DnsState = DnsState(
    adb.shell("settings get global private_dns_mode").trim(),
    adb.shell("settings get global private_dns_specifier").trim(),
  )

  /**
   * Работает ли на ТВ поиск адресов. Строку «PING host (ip)» ping печатает только после того,
   * как имя нашлось, поэтому проверка не зависит от того, отвечает ли сервер на ping.
   */
  fun tvDnsWorks(): Boolean = Regex("PING \\S+ \\(\\d", RegexOption.IGNORE_CASE)
    .containsMatchIn(adb.shell("ping -c 1 -W 2 connectivitycheck.gstatic.com 2>&1"))

  enum class DnsResult { TOO_OLD, ENABLED, ENABLED_UNCHECKED, ROLLED_BACK }

  fun enableDns(): DnsResult {
    if (info.sdk < 28) {
      out.warn("Нужен Android 9 и новее.")
      return DnsResult.TOO_OLD
    }
    val before = dnsState()
    val online = tvDnsWorks()
    if (!online) out.warn("У телевизора сейчас нет интернета — проверить DNS не получится.")
    adb.shell("settings put global private_dns_specifier $ADGUARD_DNS")
    adb.shell("settings put global private_dns_mode hostname")
    if (!online) {
      out.ok("Включено. Если после появления интернета ТВ напишет «нет связи» — выключите здесь же.")
      return DnsResult.ENABLED_UNCHECKED
    }
    // Частный DNS ходит по порту 853. Если его режет провайдер или роутер, ТВ остаётся без интернета —
    // проверяем и при неудаче сразу возвращаем как было
    out.info("Проверяю, что интернет на ТВ работает с новым DNS…")
    repeat(4) {
      sleep(2000)
      if (tvDnsWorks()) {
        out.ok("Включено, интернет работает. Отключить можно здесь же или в настройках сети ТВ.")
        return DnsResult.ENABLED
      }
    }
    val mode = if (before.mode in listOf("off", "opportunistic")) before.mode else "opportunistic"
    adb.shell("settings put global private_dns_mode $mode")
    if (before.spec.isNotBlank() && before.spec != "null") adb.shell("settings put global private_dns_specifier ${before.spec}")
    else adb.shell("settings delete global private_dns_specifier")
    out.warn("С AdGuard DNS телевизор остался без интернета: провайдер или роутер не пропускают DNS-over-TLS (порт 853).")
    out.ok("Вернул DNS как было — интернет на ТВ работает.")
    out.hint("Можно прописать AdGuard DNS в роутере (94.140.14.14 и 94.140.15.15) — тогда реклама режется у всех устройств дома.")
    return DnsResult.ROLLED_BACK
  }

  fun disableDns() {
    adb.shell("settings put global private_dns_mode opportunistic")
    out.ok("Частный DNS выключен")
  }

  // ─── Главный экран ──────────────────────────────────────────────────

  val launcherInstalled: Boolean get() = LAUNCHER_PACKAGE in info.installed

  fun installLauncher(): Boolean {
    val apk = apks.get("ltvlauncher", out) { ApkSources.launcher(net, info) }
    return installApk(apk, "LTvLauncher").also { refresh() }
  }

  fun focusedActivity(): String = adb.shell("dumpsys activity activities").lines()
    .firstOrNull { Regex("topResumedActivity|mResumedActivity|mFocusedApp").containsMatchIn(it) }.orEmpty()

  fun homeOpensLauncher(): Boolean {
    adb.shell("input keyevent KEYCODE_HOME")
    sleep(3000)
    return focusedActivity().contains(LAUNCHER_PACKAGE)
  }

  enum class HomeResult { OPENS, STUBBORN, NO_ACTIVITY }

  /** Назначить LTvLauncher главным экраном и проверить кнопкой «Домой» */
  fun makeLauncherHome(): HomeResult {
    val component = adb.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME -p $LAUNCHER_PACKAGE")
      .lines().map { it.trim() }.lastOrNull { Regex("^[\\w.]+/[\\w.$]+$").matches(it) }
    if (component == null) {
      out.fail("Не нашёл экран лаунчера — откройте LTvLauncher на телевизоре один раз и попробуйте снова.")
      return HomeResult.NO_ACTIVITY
    }
    val r = adb.shell("cmd package set-home-activity $component")
    if (!r.contains("Success")) out.warn("set-home-activity: $r")
    if (homeOpensLauncher()) {
      out.ok("Кнопка «Домой» открывает LTvLauncher.")
      return HomeResult.OPENS
    }
    out.warn("Прошивка всё равно открывает штатный главный экран (так делает Google TV и часть Яндекс ТВ).")
    out.hint("Мягкий способ: на ТВ откройте Настройки → Специальные возможности → «Home Button Fix» (LTvLauncher) и включите. Затем нажмите «Домой» на пульте.")
    return HomeResult.STUBBORN
  }

  fun stockLaunchers(): List<PackageEntry> = planner.stockLaunchers(info)

  enum class HardResult { UNKNOWN_STOCK, WIFI_OWNER, DONE, REVERTED }

  /**
   * Жёсткий способ — отключить штатный лаунчер вместе с парными пакетами.
   * Пакет-владелец сохранённой сети Wi-Fi при отключении удаляет её — ТВ отвалится от Wi-Fi и ADB,
   * поэтому при любой помехе отменяем всё.
   */
  fun disableStockLauncher(): HardResult {
    val stock = stockLaunchers()
    if (stock.isEmpty()) {
      out.warn("Штатный лаунчер этой прошивки не знаю — оставляю как есть.")
      return HardResult.UNKNOWN_STOCK
    }
    val wifiOwners = adb.shell("dumpsys wifi | grep -o 'cname=[^ ]*'")
    val owners = stock.filter { wifiOwners.contains(it.id) }
    if (owners.isNotEmpty()) {
      out.warn("${owners.joinToString(", ") { it.id }} владеет сохранённой сетью Wi-Fi: после отключения ТВ потеряет Wi-Fi.")
      out.hint("Настройки → Сеть → «Забыть сеть», подключитесь к ней заново и попробуйте ещё раз.")
      return HardResult.WIFI_OWNER
    }
    disable(stock)
    refresh()
    if (homeOpensLauncher()) {
      out.ok("Готово: «Домой» открывает LTvLauncher.")
      return HardResult.DONE
    }
    out.fail("Не помогло. Возвращаю штатный лаунчер.")
    for (e in stock) adb.shell("pm enable ${e.id}")
    refresh()
    return HardResult.REVERTED
  }

  companion object {
    private val DISABLED = Regex("disabled-user|new state: disabled", RegexOption.IGNORE_CASE)

    /** Пакеты SmartTube: новые (после смены ключа в конце 2025) и старые */
    val SMARTTUBE_PACKAGES = listOf("org.smarttube.stable", "org.smarttube.beta", "app.smarttube.fdroid", "com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta")
    const val HAPP_PACKAGE = "com.happproxy"
    const val LAUNCHER_PACKAGE = "com.leanbitlab.ltvL"
    const val ADGUARD_DNS = "dns.adguard-dns.com"

    /** Ссылка уходит в shell телевизора внутри одинарных кавычек: кавычки и пробелы недопустимы */
    fun isSubscriptionUrl(url: String): Boolean = Regex("^https?://[A-Za-z0-9\\-._~:/?#\\[\\]@!$()*+,;=%&]+$").matches(url)
  }
}
