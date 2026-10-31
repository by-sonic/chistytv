package ru.bysonic.chistytv.core

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Кем подписан APK. Сверяем сертификат с закреплённым, сам расчёт подписи проверяет Android при установке:
 * подделка с чужим сертификатом не пройдёт здесь, а с подставленным нашим — на телевизоре.
 */
object ApkSignature {
  /** SHA-256 сертификатов издателей — те же, что в src/main.ps1 */
  val PINS = mapOf(
    "smarttube" to "109735aa24fe85e2e1623ebb3b1ecc4bf4263f4c6374e4411d646bfa2eda482a",
    "happ" to "397e98abed03c5f263ffaf4e8a1043b2a59f538b99e75cf152fa05a7e8007d8d",
    "ltvlauncher" to "df819e5940a514f3d2d5d19d852c18e4cbe2d1d636cb92011b8c101dac72aa9a",
  )

  private const val V2 = 0x7109871a
  private const val V3 = 0xf05368c0.toInt()

  /** Сертификаты из блока подписи APK (схемы v2 и v3); не разобрали — пусто */
  fun certHashes(file: File): Set<String> = try {
    RandomAccessFile(file, "r").use { read(it) }
  } catch (e: RuntimeException) {
    emptySet()
  } catch (e: IOException) {
    emptySet()
  }

  private fun bytes(f: RandomAccessFile, from: Long, n: Int): ByteBuffer {
    val a = ByteArray(n)
    f.seek(from)
    f.readFully(a)
    return ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
  }

  // Читаем только хвост файла и сам блок подписи: Happ весит 67 МБ
  private fun read(f: RandomAccessFile): Set<String> {
    val tailSize = minOf(f.length(), 65557L).toInt()
    val tail = bytes(f, f.length() - tailSize, tailSize)
    val eocd = (tailSize - 22 downTo 0).firstOrNull { tail.getInt(it) == 0x06054b50 } ?: return emptySet()
    val cd = tail.getInt(eocd + 16).toLong() and 0xffffffffL
    val footer = bytes(f, cd - 24, 24)
    if (String(footer.array(), 8, 16, Charsets.US_ASCII) != "APK Sig Block 42") return emptySet()
    val blockSize = footer.getLong(0).toInt()
    val b = bytes(f, cd - blockSize - 8, blockSize + 8)
    val out = linkedSetOf<String>()
    var pos = 8
    while (pos < blockSize + 8 - 24) {
      val len = b.getLong(pos).toInt()
      val id = b.getInt(pos + 8)
      if (id == V2 || id == V3) signers(b, pos + 12, out)
      pos += 8 + len
    }
    return out
  }

  private fun signers(b: ByteBuffer, value: Int, out: MutableSet<String>) {
    val seqEnd = value + 4 + u32(b, value)
    var s = value + 4
    while (s < seqEnd) {
      val signed = s + 8
      val certs = signed + 4 + u32(b, signed)
      val certsEnd = certs + 4 + u32(b, certs)
      var c = certs + 4
      while (c < certsEnd) {
        val n = u32(b, c)
        val der = b.array().copyOfRange(c + 4, c + 4 + n)
        out += MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }
        c += 4 + n
      }
      s += 4 + u32(b, s)
    }
  }

  private fun u32(b: ByteBuffer, at: Int): Int = b.getInt(at)
}
