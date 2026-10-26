package ru.bysonic.chistytv.adb

import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import ru.bysonic.chistytv.BuildConfig
import ru.bysonic.chistytv.core.Adb
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit

/**
 * Соединение телефона с телевизором по ADB. Обычная «Отладка по сети» (порт 5555) — как `adb connect`;
 * беспроводная отладка Android 11+ — пара по шестизначному коду и TLS, как `adb pair`.
 */
class TvConnection(private val keys: AdbKeys, private val deviceName: String) : Adb, Closeable {
  private val manager = object : AbsAdbConnectionManager() {
    // Явно this@TvConnection: просто deviceName внутри наследника — это его же getDeviceName(), бесконечная рекурсия
    override fun getPrivateKey(): PrivateKey = this@TvConnection.keys.privateKey
    override fun getCertificate(): Certificate = this@TvConnection.keys.certificate
    override fun getDeviceName(): String = this@TvConnection.deviceName
  }.apply {
    // Пока человек дойдёт до телевизора и нажмёт «Разрешить», проходит время
    setTimeout(CONFIRM_SECONDS, TimeUnit.SECONDS)
  }

  var address: String? = null
    private set

  val connected: Boolean get() = manager.isConnected

  /**
   * Подключение. Если телевизор видит телефон впервые, он показывает окно «Разрешить отладку?» —
   * вызов ждёт ответа до [CONFIRM_SECONDS] секунд.
   */
  fun connect(host: String, port: Int, tls: Boolean = false): Boolean {
    runCatching { manager.disconnect() }
    // Для обычного порта — самый совместимый вариант протокола; для TLS-порта Android 11+ — современный
    manager.api = if (tls) 30 else 1
    val ok = manager.connect(host, port)
    if (ok) address = "$host:$port"
    return ok
  }

  /** Пара по коду из «Беспроводной отладки» (Android 11+) */
  fun pair(host: String, port: Int, code: String): Boolean = manager.pair(host, port, code)

  override fun shell(command: String): String {
    // Команды пишем в журнал только в отладочной сборке: в них бывает ссылка подписки
    if (BuildConfig.DEBUG) android.util.Log.d("ChistyTV", "shell> $command")
    val stream = manager.openStream("shell:$command")
    return stream.use { s -> readAll(s.openInputStream()).toString(Charsets.UTF_8).replace("\r\n", "\n").trim() }
  }

  /** Передача файла по протоколу sync — как `adb push` */
  override fun push(source: File, remotePath: String) {
    manager.openStream("sync:").use { s: AdbStream ->
      val out = s.openOutputStream()
      val input = s.openInputStream()
      val spec = "$remotePath,$MODE_FILE_0644".toByteArray()
      out.write(header("SEND", spec.size) + spec)
      source.inputStream().use { file ->
        val buf = ByteArray(SYNC_DATA_MAX)
        while (true) {
          val n = file.read(buf)
          if (n <= 0) break
          out.write(header("DATA", n) + buf.copyOf(n))
        }
      }
      out.write(header("DONE", (source.lastModified() / 1000).toInt()))
      out.flush()
      val reply = readExactly(input, 8)
      val id = String(reply, 0, 4, Charsets.US_ASCII)
      val len = ByteBuffer.wrap(reply, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
      if (id != "OKAY") {
        val msg = if (id == "FAIL" && len in 1..4096) String(readExactly(input, len)) else "ответ $id"
        throw IOException("телевизор не принял файл: $msg")
      }
      out.write(header("QUIT", 0))
      out.flush()
    }
  }

  override fun close() {
    runCatching { manager.disconnect() }
    address = null
  }

  private fun header(id: String, n: Int): ByteArray =
    ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put(id.toByteArray(Charsets.US_ASCII)).putInt(n).array()

  private fun readAll(input: InputStream): ByteArray {
    val buf = ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    while (true) {
      val n = try {
        input.read(chunk)
      } catch (e: IOException) {
        // libadb сообщает о закрытии потока телевизором исключением «Stream closed.» — это нормальный конец вывода,
        // в том числе у команд без вывода (settings put, input keyevent)
        if (e.message?.contains("closed", ignoreCase = true) == true) -1 else throw e
      }
      if (n < 0) break
      buf.write(chunk, 0, n)
    }
    return buf.toByteArray()
  }

  private fun readExactly(input: InputStream, n: Int): ByteArray {
    val out = ByteArray(n)
    var off = 0
    while (off < n) {
      val r = input.read(out, off, n - off)
      if (r < 0) throw IOException("соединение прервалось")
      off += r
    }
    return out
  }

  companion object {
    const val CONFIRM_SECONDS = 90L
    private const val SYNC_DATA_MAX = 64 * 1024
    /** S_IFREG | 0644 — как отправляет `adb push` */
    private const val MODE_FILE_0644 = 33188
  }
}
