package ru.bysonic.chistytv.adb

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * Ключ ADB этого телефона — как ~/.android/adbkey у компьютера. Телевизор запоминает его,
 * когда вы отмечаете «Всегда разрешать», поэтому ключ создаётся один раз и хранится в личной папке приложения.
 * Сертификат нужен для подключения по коду (Android 11+, TLS).
 */
class AdbKeys(dir: File) {
  val privateKey: PrivateKey
  val certificate: X509Certificate

  init {
    dir.mkdirs()
    val keyFile = File(dir, "adbkey.pk8")
    val certFile = File(dir, "adbkey.crt")
    if (keyFile.exists() && certFile.exists()) {
      privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
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
      keyFile.writeBytes(privateKey.encoded)
      certFile.writeBytes(certificate.encoded)
    }
  }

  private companion object {
    const val DAY = 24L * 3600 * 1000
  }
}
