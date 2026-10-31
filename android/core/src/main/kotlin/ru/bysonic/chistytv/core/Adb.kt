package ru.bysonic.chistytv.core

import java.io.File

/**
 * Соединение с телевизором. В приложении — по сети через libadb, в тестах — фейковый ТВ
 * (как test/fake-adb.ps1 у скрипта) или настоящий adbd эмулятора.
 */
interface Adb {
  /** Команда в shell телевизора; вывод stdout и stderr вместе, без пробелов по краям */
  fun shell(command: String): String

  /** Передать файл на телевизор; при ошибке — исключение */
  fun push(source: File, remotePath: String)
}

enum class Tone { INFO, OK, WARN, FAIL, HINT }

data class Line(val tone: Tone, val text: String)

/** Куда ядро пишет ход работы — в приложении это живой журнал на экране. */
fun interface Output {
  fun line(line: Line)

  fun info(text: String) = line(Line(Tone.INFO, text))
  fun ok(text: String) = line(Line(Tone.OK, text))
  fun warn(text: String) = line(Line(Tone.WARN, text))
  fun fail(text: String) = line(Line(Tone.FAIL, text))
  fun hint(text: String) = line(Line(Tone.HINT, text))

  companion object {
    val None = Output { }
  }
}

/** Защита от ошибок в базе: такие пакеты не трогаем никогда (тот же список, что в src/main.ps1). */
object Protect {
  val patterns = listOf(
    "^android$", "^com\\.android\\.systemui$", "^com\\.android\\.settings$", "^com\\.android\\.tv\\.settings$",
    "packageinstaller", "inputmethod", "keyboard", "leanbackime", "\\.ime$", "tvinput", "tvcenter", "tv\\.service",
    "bluetooth", "remote", "factory", "fac$", "\\.hkcfac", "^com\\.android\\.vending$", "^com\\.google\\.android\\.gms$",
    "^com\\.google\\.android\\.gsf$", "setupwraith", "setupwizard", "provision", "networkstack", "wifi", "webview",
  )

  // В PowerShell -match не различает регистр — здесь так же
  private val regexes = patterns.map { Regex(it, RegexOption.IGNORE_CASE) }

  fun isProtected(pkg: String): Boolean = regexes.any { it.containsMatchIn(pkg) }
}

class Planner(private val db: Db) {
  fun platforms(info: TvInfo): List<Platform> = db.platforms.filter { pl ->
    pl.detectPackages.any { it in info.installed } ||
      pl.detectManufacturer?.let { re ->
        val r = Regex(re, RegexOption.IGNORE_CASE)
        r.containsMatchIn(info.manufacturer) || r.containsMatchIn(info.brand)
      } == true
  }

  /** Что из базы стоит на этом ТВ, ещё не отключено и не под защитой */
  fun plan(info: TvInfo): List<PackageEntry> {
    val ids = setOf("any") + platforms(info).map { it.id }
    return db.packages.filter { e ->
      e.id in info.installed && e.platform in ids && e.action != "keep" && e.id !in info.disabled &&
        !Protect.isProtected(e.id)
    }
  }

  /**
   * Штатный лаунчер и его парные пакеты: на Google TV 14 без setupwraith система уходит
   * в цикл перезагрузок, поэтому отключаются только все вместе.
   */
  fun stockLaunchers(info: TvInfo): List<PackageEntry> {
    val stock = mutableListOf<PackageEntry>()
    for (e in db.packages.filter { it.launcher && it.id in info.installed && it.id !in info.disabled }) {
      stock += e
      for (req in e.requires) {
        if (req.isBlank() || req !in info.installed || req in info.disabled) continue
        stock += db.entry(req) ?: PackageEntry(id = req, title = req)
      }
    }
    return stock.distinctBy { it.id }
  }
}
