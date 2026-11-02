package ru.bysonic.chistytv.core

import java.net.URLEncoder

/**
 * Отчёт о телевизоре для issue на GitHub — тот же текст, что у chistytv.ps1.
 * Только модель, сборка и системные пакеты: ни IP, ни MAC, ни серийного номера, ни сторонних приложений.
 * Отправляет сам человек, кнопкой на GitHub.
 */
class TvReport(val title: String, val full: String, val short: String) {
  /** Ссылка на новый issue. Весь текст в адрес не влезает? Тогда короче, а полный — в буфере обмена */
  fun url(max: Int = MAX_URL): String {
    val head = "$ISSUE_URL?title=${enc(title)}&body="
    return listOf(full, short, PASTE).map { head + enc(it) }.firstOrNull { it.length <= max } ?: (head + enc(PASTE))
  }

  companion object {
    const val ISSUE_URL = "https://github.com/by-sonic/chistytv/issues/new"
    // Без входа GitHub уводит на страницу входа, адрес там разбухает: с ~4500 символов ошибка 500
    const val MAX_URL = 3500
    const val PASTE = "Вставьте сюда отчёт: он уже в буфере обмена."
    private const val MAX_LINE = 200

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** В сообщениях об ошибках бывают адреса, ссылка подписки и пути: в отчёт они не идут */
    fun scrub(line: String): String {
      val t = line
        .replace(Regex("https?://\\S+"), "<ссылка>")
        .replace(Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?\\b"), "<ip>")
        .replace(Regex("\\b[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}\\b"), "<mac>")
        .replace(Regex("[A-Za-z]:\\\\[^)\\r\\n]*"), "<папка>")
      return if (t.length > MAX_LINE) t.take(MAX_LINE) + "…" else t
    }
  }
}
