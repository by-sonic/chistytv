package ru.bysonic.chistytv.adb

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Ключ ADB этого телефона — как ~/.android/adbkey у компьютера. Телевизор запоминает его,
 * когда вы отмечаете «Всегда разрешать», поэтому ключ создаётся один раз и хранится в личной папке приложения.
 * Сертификат нужен для подключения по коду (Android 11+, TLS).
 *
 * На диске ключ лежит зашифрованным: AES-ключ живёт в Android Keystore и из него не извлекается, так что
 * скопированный файл бесполезен. Сам RSA-ключ в Keystore положить нельзя: libadb подписывает им напрямую.
 */
class AdbKeys(dir: File) {
  val privateKey: PrivateKey
  val certificate: X509Certificate

  init {
    dir.mkdirs()
    val plain = File(dir, PLAIN)
    val sealed = File(dir, SEALED)
    val certFile = File(dir, CERT)
    val stored = when {
      !certFile.exists() -> null
      sealed.exists() -> runCatching { open(sealed.readBytes()) }.getOrNull() // Keystore сброшен — ключ придётся создать заново
      plain.exists() -> plain.readBytes().also { store(it, sealed, plain) } // файл от версий до 1.0.3
      else -> null
    }
    if (stored != null) {
      privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(stored))
      certificate = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) } as X509Certificate
    } else {
      val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
      val name = X500Name("CN=ChistyTV")
      val now = System.currentTimeMillis()
      val holder = JcaX509v3CertificateBuilder(
        name, BigInteger.valueOf(now), Date(now - DAY), Date(now + 30 * 365 * DAY), name, pair.public,
      ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
      privateKey = pair.private
      certificate = JcaX509CertificateConverter().getCertificate(holder)
      store(privateKey.encoded, sealed, plain)
      certFile.writeBytes(certificate.encoded)
    }
  }

  /** Зашифровать; если Keystore на этом телефоне не работает — оставить файл в личной папке, как раньше */
  private fun store(key: ByteArray, sealed: File, plain: File) {
    val box = runCatching { seal(key) }.getOrNull()
    if (box != null) {
      sealed.writeBytes(box)
      plain.delete()
    } else {
      plain.writeBytes(key)
    }
  }

  private fun seal(data: ByteArray): ByteArray {
    val c = Cipher.getInstance(AES)
    c.init(Cipher.ENCRYPT_MODE, wrapKey())
    return byteArrayOf(c.iv.size.toByte()) + c.iv + c.doFinal(data)
  }

  private fun open(box: ByteArray): ByteArray {
    val ivSize = box[0].toInt()
    val c = Cipher.getInstance(AES)
    c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, box, 1, ivSize))
    return c.doFinal(box, 1 + ivSize, box.size - 1 - ivSize)
  }

  private fun wrapKey(): SecretKey {
    val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    (ks.getKey(ALIAS, null) as SecretKey?)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
      init(
        KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .build(),
      )
    }.generateKey()
  }

  companion object {
    private const val DAY = 24L * 3600 * 1000
    private const val PLAIN = "adbkey.pk8"
    private const val SEALED = "adbkey.enc"
    private const val CERT = "adbkey.crt"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "chistytv-adbkey"
    private const val AES = "AES/GCM/NoPadding"

    /** Забыть ключ: следующий запуск создаст новый */
    fun reset(dir: File) {
      listOf(PLAIN, SEALED, CERT).forEach { File(dir, it).delete() }
      runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
    }
  }
}
