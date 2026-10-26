package ru.bysonic.chistytv.core

import java.io.File

/**
 * Фейковый телевизор — перенос test/fake-adb.ps1: понимает те же команды и так же отвечает.
 * Состояние меняется, как на настоящем ТВ, а все команды записываются для проверок.
 */
class FakeTv(
  installed: List<String>,
  var refuse: Boolean = false,
  var homeWorks: Boolean = false,
  var wifiOwner: String = "",
  var dotBlocked: Boolean = false,
  var offline: Boolean = false,
  val props: MutableMap<String, String> = mutableMapOf(
    "ro.product.manufacturer" to "Xiaomi", "ro.product.brand" to "Xiaomi", "ro.product.model" to "MiTV-TEST",
    "ro.build.version.release" to "11", "ro.build.version.sdk" to "30", "ro.product.cpu.abi" to "armeabi-v7a",
    "ro.product.cpu.abilist" to "armeabi-v7a,armeabi",
  ),
) : Adb {
  val installed = installed.toMutableList()
  val disabled = mutableListOf<String>()
  val started = mutableListOf<String>()
  val commands = mutableListOf<String>()
  val pushed = mutableListOf<String>()
  var home = ""
  var dnsMode = "opportunistic"
  var dnsSpec = "null"
  /** Файлы в /data/local/tmp — для журнала ЧистогоТВ на самом телевизоре */
  val files = mutableMapOf<String, String>()

  override fun push(source: File, remotePath: String) {
    pushed += remotePath
  }

  override fun shell(command: String): String {
    commands += command
    val c = command.trim()
    Regex("^getprop (\\S+)$").find(c)?.let { return props[it.groupValues[1]].orEmpty() }
    if (c == "getprop") return props.entries.joinToString("\n") { "[${it.key}]: [${it.value}]" }
    if (c == "pm list packages") return installed.joinToString("\n") { "package:$it" }
    if (c == "pm list packages -d") return disabled.joinToString("\n") { "package:$it" }
    Regex("^pm disable-user --user 0 (\\S+)$").find(c)?.let {
      val p = it.groupValues[1]
      if (refuse) return "java.lang.SecurityException: Shell cannot change component state for $p"
      if (p !in installed) return "Error: Unknown package: $p"
      if (p !in disabled) disabled += p
      return "Package $p new state: disabled-user"
    }
    Regex("^pm enable (\\S+)$").find(c)?.let {
      val p = it.groupValues[1]
      disabled.remove(p)
      return "Package $p new state: enabled"
    }
    if (c.startsWith("pm install -r ")) {
      if ("com.installed.by.test" !in installed) installed += "com.installed.by.test"
      return "Success"
    }
    Regex("^cmd package resolve-activity .*-p (\\S+)").find(c)?.let {
      val p = it.groupValues[1]
      return if (p in installed) "priority=0 preferredOrder=0\n$p/$p.MainActivity" else "No activity found"
    }
    Regex("^cmd package set-home-activity (\\S+)").find(c)?.let {
      home = it.groupValues[1]
      return "Success"
    }
    if (c.startsWith("input keyevent")) return ""
    if (c == "dumpsys activity activities") {
      var top = "com.google.android.apps.tv.launcherx/.StockActivity"
      if (homeWorks && home.isNotEmpty()) top = home
      if (home.isNotEmpty() && "com.test.stock" in disabled) top = home
      return "  topResumedActivity=ActivityRecord{1a2b u0 $top t12}"
    }
    if (c.startsWith("dumpsys wifi")) return wifiOwner
    if (c.startsWith("am start ")) {
      started += c
      return "Starting: Intent { act=android.intent.action.VIEW }"
    }
    if (c.startsWith("settings list")) return "a=1"
    if (c.startsWith("settings get global private_dns_mode")) return dnsMode
    if (c.startsWith("settings get global private_dns_specifier")) return dnsSpec
    Regex("^settings put global private_dns_mode (\\S+)").find(c)?.let { dnsMode = it.groupValues[1]; return "" }
    Regex("^settings put global private_dns_specifier (\\S+)").find(c)?.let { dnsSpec = it.groupValues[1]; return "" }
    if (c.startsWith("settings delete global private_dns_specifier")) {
      dnsSpec = "null"
      return ""
    }
    if (c.startsWith("ping ")) {
      // dotBlocked: провайдер режет порт 853 — с частным DNS имена перестают находиться
      if (offline || (dotBlocked && dnsMode == "hostname")) return "ping: unknown host connectivitycheck.gstatic.com"
      return "PING connectivitycheck.gstatic.com (142.250.74.35) 56(84) bytes of data.\n64 bytes from 142.250.74.35: icmp_seq=1 ttl=117 time=12 ms"
    }
    if (c.startsWith("rm ")) return ""
    Regex("^mkdir -p (\\S+) && echo (\\S+) >> (\\S+)$").find(c)?.let {
      val (_, pkg, file) = it.destructured
      files[file] = files[file].orEmpty() + "$pkg\n"
      return ""
    }
    Regex("^cat (\\S+) 2>/dev/null$").find(c)?.let { return files[it.groupValues[1]].orEmpty().trim() }
    Regex("^mv (\\S+) (\\S+) 2>/dev/null$").find(c)?.let {
      val (from, to) = it.destructured
      files.remove(from)?.let { v -> files[to] = v }
      return ""
    }
    return "unknown shell command: $c"
  }
}
