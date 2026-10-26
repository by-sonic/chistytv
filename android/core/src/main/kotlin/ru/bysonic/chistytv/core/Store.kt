package ru.bysonic.chistytv.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Бэкапы и журнал отката одного телевизора — в том же формате, что у chistytv.ps1:
 * <папка>/<Производитель-Модель>/disabled-by-chistytv.txt, по пакету в строке.
 *
 * Журнал дублируется на сам телевизор (/data/local/tmp/chistytv): тогда «Вернуть как было»
 * сработает и с другого телефона, и после переустановки приложения.
 */
class TvStore(root: File, info: TvInfo, private val adb: Adb, private val now: () -> Date = { Date() }) {
  val dir = File(root, info.key)
  val journal = File(dir, "disabled-by-chistytv.txt")

  private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(now())

  fun record(pkg: String) {
    dir.mkdirs()
    journal.appendText("$pkg\n")
    // Имя пакета — только [\w.], в shell его можно подставлять как есть
    if (SAFE_PKG.matches(pkg)) adb.shell("mkdir -p $TV_DIR && echo $pkg >> $TV_JOURNAL")
  }

  /** Всё, что отключал ЧистыйТВ: журнал на телефоне плюс журнал на телевизоре */
  fun recorded(): List<String> {
    val local = if (journal.exists()) journal.readText().removePrefix("﻿").lines() else emptyList()
    val remote = adb.shell("cat $TV_JOURNAL 2>/dev/null").lines()
    return (local + remote).map { it.trim() }.filter { SAFE_PKG.matches(it) }.distinct()
  }

  /** После отката журнал не удаляем, а откладываем в сторону — как скрипт */
  fun archiveJournal() {
    val s = stamp()
    if (journal.exists()) journal.renameTo(File(dir, "disabled-by-chistytv.txt.restored-$s"))
    adb.shell("mv $TV_JOURNAL $TV_JOURNAL.restored-$s 2>/dev/null")
  }

  fun backup(info: TvInfo): File {
    val b = File(dir, stamp())
    b.mkdirs()
    File(b, "packages.txt").writeText(info.installed.joinToString("\n"))
    File(b, "disabled-before.txt").writeText(info.disabled.joinToString("\n"))
    File(b, "getprop.txt").writeText(adb.shell("getprop"))
    for (ns in listOf("global", "secure", "system")) File(b, "settings-$ns.txt").writeText(adb.shell("settings list $ns"))
    return b
  }

  companion object {
    const val TV_DIR = "/data/local/tmp/chistytv"
    const val TV_JOURNAL = "$TV_DIR/disabled-by-chistytv.txt"
    val SAFE_PKG = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")
  }
}
