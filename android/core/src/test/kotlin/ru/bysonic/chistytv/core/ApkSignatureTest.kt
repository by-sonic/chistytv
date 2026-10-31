package ru.bysonic.chistytv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest

class ApkSignatureTest {
  private fun u32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
  private fun u64(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
  private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

  /** Минимальный файл с блоком подписи v2 и одним сертификатом */
  private fun signedApk(cert: ByteArray): File {
    val certs = u32(cert.size) + cert
    val signed = u32(0) + u32(certs.size) + certs
    val signer = u32(signed.size) + signed
    val seq = u32(signer.size) + signer
    val value = u32(seq.size) + seq
    val pair = u64(4L + value.size) + u32(0x7109871a) + value
    val size = pair.size + 24L
    val block = u64(size) + pair + u64(size) + "APK Sig Block 42".toByteArray()
    val eocd = byteArrayOf(0x50, 0x4b, 5, 6) + ByteArray(12) + u32(block.size) + ByteArray(2)
    return File.createTempFile("signed", ".apk").apply { writeBytes(ByteArrayOutputStream().also { it.write(block); it.write(eocd) }.toByteArray()) }
  }

  @Test
  fun `сертификат читается из блока подписи`() {
    val cert = "test certificate".toByteArray()
    assertEquals(setOf(sha(cert)), ApkSignature.certHashes(signedApk(cert)))
  }

  @Test
  fun `мусор вместо APK — подписи нет`() {
    val f = File.createTempFile("junk", ".apk").apply { writeText("apk") }
    assertTrue(ApkSignature.certHashes(f).isEmpty())
  }

  private class OneFileNet(private val body: ByteArray) : Net {
    override fun text(url: String) = "{}"
    override fun download(url: String, dest: File) = dest.writeBytes(body)
    override fun postJson(url: String, body: String) = 200
  }

  @Test
  fun `чужая подпись — файл удалён, установки нет`() {
    val dir = Files.createTempDirectory("apk").toFile()
    val cache = ApkCache(dir, OneFileNet(signedApk("чужой".toByteArray()).readBytes()), mapOf("happ" to sha("издатель".toByteArray())))
    val e = assertThrows(IOException::class.java) { cache.get("happ", Output.None) { ApkSources.happ } }
    assertTrue(e.message, e.message!!.contains("подпись не совпадает"))
    assertTrue("подделка не осталась в кэше", dir.listFiles()!!.none { it.name.endsWith(".apk") })
  }

  @Test
  fun `подпись издателя — файл проходит`() {
    val cert = "издатель".toByteArray()
    val cache = ApkCache(Files.createTempDirectory("apk").toFile(), OneFileNet(signedApk(cert).readBytes()), mapOf("happ" to sha(cert)))
    assertTrue(cache.get("happ", Output.None) { ApkSources.happ }.exists())
  }

  @Test
  fun `подделка в кэше при отсутствии сети тоже не проходит`() {
    val dir = Files.createTempDirectory("apk").toFile()
    signedApk("чужой".toByteArray()).copyTo(File(dir, "happ-Happ.apk"))
    val cache = ApkCache(dir, StubNet(fail = true), mapOf("happ" to sha("издатель".toByteArray())))
    assertThrows(IOException::class.java) { cache.get("happ", Output.None) { ApkSources.happ } }
  }

  /** CHISTYTV_IT_APKS — папка с настоящими APK: smarttube*, happ*, ltv*, чужой app-release.apk */
  @Test
  fun `настоящие APK издателей`() {
    val dir = System.getenv("CHISTYTV_IT_APKS")?.let(::File)
    assumeTrue("CHISTYTV_IT_APKS не задан", dir != null)
    val files = dir!!.listFiles()!!.filter { it.name.endsWith(".apk") }
    for (f in files) {
      val h = ApkSignature.certHashes(f)
      val pin = when {
        f.name.startsWith("st-") -> ApkSignature.PINS["smarttube"]
        f.name.startsWith("happ") -> ApkSignature.PINS["happ"]
        f.name.startsWith("LTv") -> ApkSignature.PINS["ltvlauncher"]
        else -> null
      }
      println("${f.name}: $h")
      if (pin != null) assertTrue(f.name, pin in h) else assertFalse(f.name, h.any { it in ApkSignature.PINS.values })
    }
  }
}
