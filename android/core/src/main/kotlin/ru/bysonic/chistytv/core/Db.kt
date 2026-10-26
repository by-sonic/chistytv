package ru.bysonic.chistytv.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** База пакетов — тот же data/packages.json, что у chistytv.ps1. */
@Serializable
data class Db(
  val version: Int = 0,
  val updated: String = "",
  val platforms: List<Platform> = emptyList(),
  val packages: List<PackageEntry> = emptyList(),
) {
  fun entry(id: String): PackageEntry? = packages.firstOrNull { it.id == id }

  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): Db = json.decodeFromString(serializer(), text.removePrefix("﻿"))
  }
}

@Serializable
data class Platform(
  val id: String,
  val name: String,
  @SerialName("detect_packages") val detectPackages: List<String> = emptyList(),
  @SerialName("detect_manufacturer") val detectManufacturer: String? = null,
)

@Serializable
data class PackageEntry(
  val id: String,
  val platform: String = "any",
  val group: String = "",
  /** disable — отключаем сразу, ask — спрашиваем, keep — не трогаем никогда */
  val action: String = "ask",
  val title: String = "",
  val note: String = "",
  val launcher: Boolean = false,
  val requires: List<String> = emptyList(),
  @SerialName("override_protect") val overrideProtect: Boolean = false,
) {
  /** «Название — пояснение»; без пояснения — без висящего тире */
  val label: String get() = if (note.isNotBlank()) "$title — $note" else title
}

/** Сведения о телевизоре: getprop и списки пакетов. */
data class TvInfo(
  val manufacturer: String,
  val brand: String,
  val model: String,
  val android: String,
  val sdk: Int,
  val abi: String,
  val abiList: String,
  val installed: Set<String>,
  val disabled: Set<String>,
) {
  /**
   * Имя папки с бэкапами и журналом: «Xiaomi-MiTV-MSSP0», как у chistytv.ps1.
   * Буквы любого алфавита, как \w в .NET; флаг (?U) не годится — его не понимает регулярный движок Android.
   */
  val key: String get() = "$manufacturer-$model".replace(Regex("[^\\p{L}\\p{N}_\\-]+"), "_").trim('_').ifEmpty { "tv" }

  /** «Xiaomi MiTV-MSSP0»; «unknown» у эмуляторов и дешёвых плат не показываем */
  val title: String get() = listOf(manufacturer, model).filter { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
    .joinToString(" ").ifEmpty { "Телевизор" }

  companion object {
    val PROPS = listOf(
      "ro.product.manufacturer", "ro.product.brand", "ro.product.model", "ro.build.version.release",
      "ro.build.version.sdk", "ro.product.cpu.abi", "ro.product.cpu.abilist",
    )

    fun read(adb: Adb): TvInfo {
      val p = PROPS.associateWith { adb.shell("getprop $it").trim() }
      return TvInfo(
        manufacturer = p.getValue("ro.product.manufacturer"),
        brand = p.getValue("ro.product.brand"),
        model = p.getValue("ro.product.model"),
        android = p.getValue("ro.build.version.release"),
        sdk = p.getValue("ro.build.version.sdk").toIntOrNull() ?: 0,
        abi = p.getValue("ro.product.cpu.abi"),
        abiList = p.getValue("ro.product.cpu.abilist"),
        installed = parsePackages(adb.shell("pm list packages")),
        disabled = parsePackages(adb.shell("pm list packages -d")),
      )
    }

    /** «package:com.foo» по строке; сообщения об ошибках с пробелами пропускаем */
    fun parsePackages(out: String): Set<String> = out.lineSequence()
      .map { it.trim().removePrefix("package:").trim() }
      .filter { it.isNotEmpty() && !it.contains(' ') && !it.contains(':') }
      .toCollection(LinkedHashSet())
  }
}
