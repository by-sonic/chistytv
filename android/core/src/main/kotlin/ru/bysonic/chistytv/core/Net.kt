package ru.bysonic.chistytv.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Сеть телефона: GitHub-релизы, APK и запасной импорт подписки Happ. В тестах — заглушка. */
interface Net {
  fun text(url: String): String

  fun download(url: String, dest: File)

  /** POST JSON; возвращает код ответа */
  fun postJson(url: String, body: String): Int
}

class HttpNet : Net {
  private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
    connectTimeout = 15_000
    readTimeout = 60_000
    instanceFollowRedirects = true
    setRequestProperty("User-Agent", "chistytv")
  }

  override fun text(url: String): String {
    val c = open(url)
    try {
      if (c.responseCode !in 200..299) throw IOException("$url: HTTP ${c.responseCode}")
      return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    } finally {
      c.disconnect()
    }
  }

  override fun download(url: String, dest: File) {
    val c = open(url)
    try {
      if (c.responseCode !in 200..299) throw IOException("$url: HTTP ${c.responseCode}")
      val tmp = File(dest.path + ".part")
      c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
      if (!tmp.renameTo(dest)) {
        tmp.copyTo(dest, overwrite = true)
        tmp.delete()
      }
    } finally {
      c.disconnect()
    }
  }

  override fun postJson(url: String, body: String): Int {
    val c = open(url)
    try {
      c.requestMethod = "POST"
      c.doOutput = true
      c.setRequestProperty("Content-Type", "application/json")
      c.outputStream.use { it.write(body.toByteArray()) }
      return c.responseCode
    } finally {
      c.disconnect()
    }
  }
}

/** fresh — имя файла не меняется от версии к версии (тег latest), поэтому качаем заново каждый раз */
data class ApkSource(val name: String, val url: String, val fresh: Boolean = false)

object ApkSources {
  /**
   * Основная сборка SmartTube для ТВ — armeabi-v7a; arm64 берём, только если 32-битных библиотек нет.
   * На x86 «универсальные» APK не ставятся (в них нет x86-библиотек), поэтому для x86 — своя сборка.
   */
  fun abi(info: TvInfo): String = when {
    info.abiList.contains("armeabi-v7a") -> "armeabi-v7a"
    info.abiList.contains("arm64-v8a") -> "arm64-v8a"
    info.abiList.contains("x86") -> "x86"
    else -> "universal"
  }

  /** Релиз с тегом latest обновляется на месте и хранит файлы с постоянными именами */
  fun smartTube(info: TvInfo): ApkSource {
    val abi = abi(info)
    val name = if (abi == "universal") "smarttube_stable.apk" else "smarttube_stable_$abi.apk"
    return ApkSource(name, "https://github.com/yuliskov/SmartTube/releases/download/latest/$name", fresh = true)
  }

  val happ = ApkSource("Happ.apk", "https://github.com/Happ-proxy/happ-android/releases/latest/download/Happ.apk", fresh = true)

  fun githubAsset(net: Net, repo: String, patterns: List<String>): ApkSource {
    val rel = Json.parseToJsonElement(net.text("https://api.github.com/repos/$repo/releases/latest")).jsonObject
    val assets = rel["assets"]?.jsonArray.orEmpty().map { it.jsonObject }
    for (pat in patterns) {
      val r = Regex(pat, RegexOption.IGNORE_CASE)
      val a = assets.firstOrNull { r.containsMatchIn(it["name"]?.jsonPrimitive?.content.orEmpty()) } ?: continue
      return ApkSource(a["name"]!!.jsonPrimitive.content, a["browser_download_url"]!!.jsonPrimitive.content)
    }
    throw IOException("В последнем релизе $repo нет подходящего файла.")
  }

  fun launcher(net: Net, info: TvInfo): ApkSource =
    githubAsset(net, "leanbitlab-org/LtvLauncher", listOf("${abi(info)}-release\\.apk$", "universal-release\\.apk$"))
}

/** Скачанные APK храним: без связи с GitHub возьмём сохранённый, как скрипт */
class ApkCache(private val dir: File, private val net: Net, private val pins: Map<String, String> = ApkSignature.PINS) {
  /** Подпись проверяем у любого файла, скачанного и сохранённого: перед установкой, каждый раз */
  fun get(key: String, out: Output, resolve: () -> ApkSource): File {
    val file = fetch(key, out, resolve)
    val pin = pins[key] ?: return file
    if (pin !in ApkSignature.certHashes(file)) {
      file.delete()
      throw IOException("${file.name}: подпись не совпадает с подписью издателя. Файл удалён и не установлен. Если издатель сменил ключ, обновите ЧистыйТВ.")
    }
    return file
  }

  private fun fetch(key: String, out: Output, resolve: () -> ApkSource): File {
    dir.mkdirs()
    val cached = dir.listFiles { f -> f.name.startsWith(key) && f.name.endsWith(".apk") }?.maxByOrNull { it.lastModified() }
    return try {
      val src = resolve()
      var file = File(dir, "$key-${src.name.replace(Regex("[^\\w.\\-]"), "_")}")
      if (!file.name.endsWith(".apk")) file = File(file.path + ".apk")
      if (!file.exists() || src.fresh) {
        out.info("Скачиваю ${src.name}…")
        net.download(src.url, file)
      }
      file
    } catch (e: Exception) {
      if (cached != null) {
        out.warn("Нет связи с GitHub, беру сохранённый ${cached.name}")
        cached
      } else {
        throw e
      }
    }
  }
}
