package ru.bysonic.chistytv

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.bysonic.chistytv.adb.AdbKeys
import ru.bysonic.chistytv.adb.Discovery
import ru.bysonic.chistytv.adb.FoundTv
import ru.bysonic.chistytv.adb.TvConnection
import ru.bysonic.chistytv.core.ApkCache
import ru.bysonic.chistytv.core.Db
import ru.bysonic.chistytv.core.HttpNet
import ru.bysonic.chistytv.core.Line
import ru.bysonic.chistytv.core.Output
import ru.bysonic.chistytv.core.PackageEntry
import ru.bysonic.chistytv.core.Tone
import ru.bysonic.chistytv.core.TvInfo
import ru.bysonic.chistytv.core.TvSession
import java.io.File
import java.io.IOException

private const val TAG = "ChistyTV"

enum class Screen { Start, Search, Pair, Connecting, Home, Clean, YouTube, Launcher, Dns, Restore }

class MainViewModel(app: Application) : AndroidViewModel(app) {
  private val ctx: Context get() = getApplication()
  private val prefs = app.getSharedPreferences("chistytv", Context.MODE_PRIVATE)
  private val main = Handler(Looper.getMainLooper())
  private val net = HttpNet()
  val discovery = Discovery(app)

  // ─── Навигация ──────────────────────────────────────────────────────

  var screen by mutableStateOf(Screen.Start)
    private set
  private val stack = ArrayDeque<Screen>()

  fun go(to: Screen) {
    if (to == screen) return
    stack.addLast(screen)
    screen = to
    if (to !in setOf(Screen.Connecting, Screen.Search, Screen.Pair)) resetOperation()
  }

  /** false — уходить некуда, пусть система закроет приложение */
  fun back(): Boolean {
    if (busy) return true
    if (screen == Screen.Connecting) cancelConnect()
    if (screen == Screen.Home) {
      disconnect()
      return true
    }
    val prev = stack.removeLastOrNull() ?: return false
    screen = if (prev == Screen.Connecting) Screen.Search else prev
    if (screen == Screen.Search) startScan()
    return true
  }

  private fun goRoot(to: Screen) {
    stack.clear()
    screen = to
  }

  // ─── База пакетов: из приложения, а свежая — с GitHub ──────────────

  private var db: Db = loadDb()

  private fun loadDb(): Db {
    val bundled = Db.parse(ctx.assets.open("packages.json").use { it.readBytes().toString(Charsets.UTF_8) })
    val cached = File(ctx.filesDir, "packages.json").takeIf { it.exists() }?.let { runCatching { Db.parse(it.readText()) }.getOrNull() }
    return listOfNotNull(bundled, cached).maxBy { it.updated + "#" + it.version.toString().padStart(6, '0') }
  }

  private fun refreshDb() {
    viewModelScope.launch(Dispatchers.IO) {
      val fresh = withTimeoutOrNull(8000) {
        runCatching { net.text("https://raw.githubusercontent.com/by-sonic/chistytv/main/data/packages.json") }.getOrNull()
      } ?: return@launch
      val parsed = runCatching { Db.parse(fresh) }.getOrNull() ?: return@launch
      if (parsed.updated > db.updated || (parsed.updated == db.updated && parsed.version > db.version)) {
        File(ctx.filesDir, "packages.json").writeText(fresh)
        db = parsed
      }
    }
  }

  init {
    refreshDb()
  }

  // ─── Поиск телевизора ───────────────────────────────────────────────

  var scanning by mutableStateOf(false)
    private set
  val found = mutableStateListOf<FoundTv>()
  var scannedNets by mutableStateOf<List<String>>(emptyList())
    private set
  var vpnActive by mutableStateOf(false)
    private set
  private var scanJob: Job? = null
  private var stopMdns: (() -> Unit)? = null

  val lastTv: Pair<String, String>?
    get() {
      val a = prefs.getString("last_address", null) ?: return null
      return a to (prefs.getString("last_name", null) ?: a)
    }

  fun startScan() {
    vpnActive = discovery.vpnActive()
    found.clear()
    scanJob?.cancel()
    stopMdns?.invoke()
    stopMdns = discovery.discoverWireless { tv -> main.post { addFound(tv) } }
    scanning = true
    scanJob = viewModelScope.launch {
      scannedNets = discovery.scanNetworkDebug { tv -> main.post { addFound(tv) } }
      scanning = false
    }
  }

  private fun addFound(tv: FoundTv) {
    if (found.none { it.host == tv.host && it.kind == tv.kind }) found += tv
  }

  fun stopScan() {
    scanJob?.cancel()
    scanning = false
    stopMdns?.invoke()
    stopMdns = null
  }

  // ─── Подключение ────────────────────────────────────────────────────

  private val keysDir get() = File(ctx.filesDir, "adb")
  private var loadedKeys: AdbKeys? = null
  private val keys: AdbKeys get() = loadedKeys ?: AdbKeys(keysDir).also { loadedKeys = it }

  /** Новый ключ: телевизоры, которым разрешён старый, снова спросят «Разрешить отладку?» */
  fun resetAdbKey() {
    loadedKeys = null
    AdbKeys.reset(keysDir)
  }
  private var connection: TvConnection? = null
  var session by mutableStateOf<TvSession?>(null)
    private set
  var info by mutableStateOf<TvInfo?>(null)
    private set
  var connectTarget by mutableStateOf("")
    private set
  var connectError by mutableStateOf<String?>(null)
    private set
  var pairError by mutableStateOf<String?>(null)
    private set
  var pairing by mutableStateOf(false)
    private set
  private var connectJob: Job? = null

  /** Имя телефона в окне «Разрешить отладку?» на телевизоре */
  private val deviceName get() = "ЧистыйТВ · ${Build.MANUFACTURER} ${Build.MODEL}".trim()

  fun connect(address: String, tls: Boolean = false) {
    val (host, port) = parseAddress(address) ?: run {
      connectError = "Не понял адрес. Пример: 192.168.1.50 или 192.168.1.50:5555"
      return
    }
    stopScan()
    connectTarget = "$host:$port"
    connectTls = tls
    go(Screen.Connecting)
    startConnect(host, port, tls)
  }

  private var connectTls = false

  /** «Ещё раз» на экране подключения — с теми же адресом и способом */
  fun retryConnect() {
    val (host, port) = parseAddress(connectTarget) ?: return
    startConnect(host, port, connectTls)
  }

  private fun startConnect(host: String, port: Int, tls: Boolean) {
    connectError = null
    connectJob?.cancel()
    connectJob = viewModelScope.launch {
      val result = withContext(Dispatchers.IO) {
        // VPN на телефоне не должен мешать: соединение с телевизором открываем в домашней сети
        val bound = discovery.bindToHome()
        Log.i(TAG, "подключение к $host:$port tls=$tls, домашняя сеть=$bound")
        try {
          val k = keys
          Log.i(TAG, "ключ готов")
          val c = TvConnection(k, deviceName)
          if (!c.connect(host, port, tls)) throw IOException("телевизор не ответил")
          Log.i(TAG, "подключено, читаю сведения")
          val s = TvSession(c, db, File(ctx.filesDir, "tv"), ApkCache(File(ctx.cacheDir, "apk"), net), net)
          Result.success(c to s)
        } catch (e: Throwable) {
          Log.w(TAG, "подключение не удалось", e)
          Result.failure(e)
        } finally {
          if (bound) discovery.unbind()
        }
      }
      result.onSuccess { (c, s) ->
        connection?.close()
        connection = c
        session = s
        info = s.info
        prefs.edit().putString("last_address", "$host:$port").putString("last_name", s.info.title).putBoolean("last_tls", tls).apply()
        goRoot(Screen.Home)
      }.onFailure { e ->
        if (screen != Screen.Connecting) return@onFailure
        connectError = explain(e, host, port)
      }
    }
  }

  fun reconnectLast() {
    val (address, _) = lastTv ?: return
    connect(address, prefs.getBoolean("last_tls", false))
  }

  fun cancelConnect() {
    connectJob?.cancel()
    connectJob = null
  }

  private fun explain(e: Throwable, host: String, port: Int): String {
    val m = e.message.orEmpty()
    return when {
      e is io.github.muntashirakon.adb.AdbPairingRequiredException ->
        "Телевизор просит подключение по коду: «Для разработчиков» → «Беспроводная отладка» → «Подключить по коду»."
      m.contains("ECONNREFUSED", true) || m.contains("refused", true) ->
        "$host:$port не принимает подключение. Проверьте, что на ТВ включена «Отладка по сети» (или «Отладка по USB») и телевизор не перезагружался."
      m.contains("timed out", true) || m.contains("timeout", true) || e is InterruptedException ->
        if (connectTarget.isNotEmpty()) "Телевизор не ответил. Если на экране ТВ было окно «Разрешить отладку?» — подтвердите его и попробуйте ещё раз." else m
      m.contains("EHOSTUNREACH", true) || m.contains("ENETUNREACH", true) || m.contains("No route", true) ->
        "Телевизор недоступен. Телефон и ТВ должны быть в одной Wi-Fi сети" + if (discovery.vpnActive()) "; VPN на телефоне может закрывать доступ к домашней сети." else "."
      else -> "Не подключился к $host:$port: ${m.ifBlank { e.javaClass.simpleName }}"
    }
  }

  /** Подключение по коду (Android 11+): пара, затем TLS-подключение к порту беспроводной отладки */
  fun pairAndConnect(pairAddress: String, code: String, connectAddress: String) {
    val pair = parseAddress(pairAddress, defaultPort = null)
    if (pair == null) {
      pairError = "Нужен адрес с портом из окна с кодом, например 192.168.1.50:37123"
      return
    }
    if (!Regex("^\\d{6}$").matches(code)) {
      pairError = "Код — это 6 цифр с экрана телевизора"
      return
    }
    pairError = null
    pairing = true
    viewModelScope.launch {
      val ok = withContext(Dispatchers.IO) {
        val bound = discovery.bindToHome()
        try {
          TvConnection(keys, deviceName).use { it.pair(pair.first, pair.second, code) }
        } catch (e: Throwable) {
          pairError = "Не удалось: ${e.message ?: e.javaClass.simpleName}"
          false
        } finally {
          if (bound) discovery.unbind()
        }
      }
      pairing = false
      if (!ok) {
        if (pairError == null) pairError = "Телевизор отклонил код. Проверьте цифры — код меняется при каждом открытии окна."
        return@launch
      }
      // Порт для подключения — с главного экрана «Беспроводной отладки», он не такой, как у окна с кодом
      val target = connectAddress.ifBlank { found.firstOrNull { it.kind == FoundTv.Kind.WIRELESS_DEBUG && it.host == pair.first }?.address.orEmpty() }
      if (target.isBlank()) {
        pairError = "Пара создана. Теперь введите IP-адрес и порт с главного экрана «Беспроводной отладки» (там другой порт)."
        return@launch
      }
      connect(target, tls = true)
    }
  }

  fun disconnect() {
    session = null
    info = null
    connection?.close()
    connection = null
    goRoot(Screen.Start)
  }

  private fun parseAddress(s: String, defaultPort: Int? = Discovery.ADB_PORT): Pair<String, Int>? {
    val t = s.trim()
    val m = Regex("^([A-Za-z0-9.\\-]+)(?::(\\d{1,5}))?$").find(t) ?: return null
    val port = m.groupValues[2].toIntOrNull() ?: defaultPort ?: return null
    if (port !in 1..65535) return null
    return m.groupValues[1] to port
  }

  // ─── Операции над телевизором ───────────────────────────────────────

  var busy by mutableStateOf(false)
    private set
  val log = mutableStateListOf<Line>()
  var lostConnection by mutableStateOf(false)
    private set

  var cleanResult by mutableStateOf<TvSession.CleanResult?>(null)
    private set
  var restoreResult by mutableStateOf<TvSession.RestoreResult?>(null)
    private set
  var dnsResult by mutableStateOf<TvSession.DnsResult?>(null)
    private set
  var dnsEnabled by mutableStateOf<Boolean?>(null)
    private set
  var homeResult by mutableStateOf<TvSession.HomeResult?>(null)
    private set
  var hardResult by mutableStateOf<TvSession.HardResult?>(null)
    private set
  var happResult by mutableStateOf<TvSession.HappResult?>(null)
    private set
  var webImportSent by mutableStateOf(false)
    private set
  var journal by mutableStateOf<List<String>>(emptyList())
    private set

  private fun resetOperation() {
    log.clear()
    cleanResult = null
    restoreResult = null
    dnsResult = null
    homeResult = null
    hardResult = null
    happResult = null
    webImportSent = false
  }

  private fun op(clearLog: Boolean = true, block: TvSession.() -> Unit) {
    val s = session ?: return
    if (busy) return
    busy = true
    if (clearLog) log.clear()
    viewModelScope.launch {
      withContext(Dispatchers.IO) {
        s.out = Output { line -> main.post { log += line } }
        try {
          s.block()
        } catch (e: Throwable) {
          val lost = e is IOException && !(connection?.connected ?: false)
          main.post {
            log += Line(Tone.FAIL, if (lost) "Связь с телевизором потеряна" else "Ошибка: ${e.message ?: e.javaClass.simpleName}")
            if (lost) lostConnection = true
          }
        }
      }
      info = s.info
      busy = false
    }
  }

  fun reconnectAfterLoss() {
    lostConnection = false
    val address = connection?.address ?: prefs.getString("last_address", null) ?: return
    connect(address, prefs.getBoolean("last_tls", false))
  }

  // Чистка: рекомендованное отмечено, ваши приложения — нет (как у скрипта с «Enter — оставить всё»)
  val selection = mutableStateMapOf<String, Boolean>()
  var plan by mutableStateOf<List<PackageEntry>>(emptyList())
    private set

  fun openClean() {
    val s = session ?: return
    plan = s.plan()
    selection.clear()
    plan.forEach { selection[it.id] = it.action == "disable" }
    go(Screen.Clean)
  }

  fun runClean() {
    val chosen = plan.filter { selection[it.id] == true }
    op { cleanResult = clean(chosen) }
  }

  fun openRestore() {
    go(Screen.Restore)
    op { journal = store.recorded() }
  }

  fun runRestore() = op { restoreResult = restore(); journal = store.recorded() }

  fun openDns() {
    go(Screen.Dns)
    op { dnsEnabled = dnsState().enabled }
  }

  fun enableDns() = op { dnsResult = enableDns(); dnsEnabled = dnsState().enabled }

  fun disableDns() = op { disableDns(); dnsEnabled = dnsState().enabled }

  fun installSmartTube() = op(clearLog = false) { installSmartTube() }

  fun installHapp() = op(clearLog = false) { installHapp() }

  fun sendSubscription(url: String) = op(clearLog = false) { happResult = sendHappSubscription(url.trim()) }

  fun webImport(code: String, url: String) = op(clearLog = false) { webImportSent = happWebImport(code.trim(), url.trim()) }

  fun makeHome() = op {
    if (!launcherInstalled && !installLauncher()) return@op
    homeResult = makeLauncherHome()
  }

  fun checkHomeAgain() = op(clearLog = false) {
    if (homeOpensLauncher()) {
      out.ok("Готово: «Домой» открывает LTvLauncher.")
      homeResult = TvSession.HomeResult.OPENS
    } else {
      out.warn("Пока открывается штатный главный экран.")
    }
  }

  fun disableStock() = op(clearLog = false) { hardResult = disableStockLauncher() }

  val stockLaunchers: List<PackageEntry> get() = session?.stockLaunchers().orEmpty()

  val platformNames: List<String> get() = session?.platforms?.map { it.name }.orEmpty()

  override fun onCleared() {
    stopScan()
    connection?.close()
  }
}
