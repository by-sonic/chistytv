package ru.bysonic.chistytv.core

import java.net.URLEncoder

/**
 * Отчёт о телевизоре для issue на GitHub — тот же текст, что у chistytv.ps1.
 * Только модель, сборка и системные пакеты: ни IP, ни MAC, ни серийного номера, ни сторонних приложений.
 * Отправляет сам человек, кнопкой на GitHub.
 */
class TvReport(val title: String, val full: String, val short: String, private val summary: List<String> = emptyList()) {
  /** В ссылке помещается сколько возможно, полный отчёт остаётся в буфере обмена. */
  fun url(max: Int = MAX_URL): String {
    if (title.length > max - ISSUE_URL.length) return ISSUE_URL
    val head = "$ISSUE_URL?title=${enc(title)}&body="
    fun encode(body: String): String? {
      if (body.length > max - head.length) return null
      return (head + enc(body)).takeIf { it.length <= max }
    }
    for (body in listOf(full, short)) {
      encode(body)?.let { return it }
    }
    var body = "Краткий отчёт. Чтобы приложить полный, замените этот текст содержимым буфера обмена.\n"
    val ask = "\n\n**Что случилось:** (опишите своими словами)"
    var summaryUrl: String? = null
    for (line in summary) {
      val candidate = "$body\n$line"
      val url = encode(candidate + ask) ?: continue
      body = candidate
      summaryUrl = url
    }
    return summaryUrl ?: encode(PASTE) ?: ISSUE_URL
  }

  companion object {
    const val ISSUE_URL = "https://github.com/by-sonic/chistytv/issues/new"
    // Без входа GitHub уводит на страницу входа, адрес там разбухает: с ~4500 символов ошибка 500
    const val MAX_URL = 3500
    const val PASTE = "Вставьте сюда отчёт: он уже в буфере обмена."
    private const val MAX_LINE = 200
    private const val IPV6 = """(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f:.]*(?:%[\w.-]+)?"""
    private val macAddress = Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** В сообщениях об ошибках бывают адреса, ссылка подписки и пути: в отчёт они не идут */
    fun scrub(line: String): String {
      val t = line
        .replace(Regex("https?://\\S+"), "<ссылка>")
        .replace(Regex("""\[$IPV6\](?::\d+)?"""), "<ip>")
        .replace(Regex("""(^|[^\w:])($IPV6)(?![\w:])""")) { match ->
          val (prefix, address) = match.destructured
          prefix + if (macAddress.matches(address)) "<mac>" else "<ip>"
        }
        .replace(Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?\\b"), "<ip>")
        .replace(Regex("[A-Za-z]:\\\\[^)\\r\\n]*"), "<папка>")
      return if (t.length > MAX_LINE) t.take(MAX_LINE) + "…" else t
    }
  }
}
