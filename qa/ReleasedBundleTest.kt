package ru.bysonic.chistytv.core

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

class ReleasedBundleTest {
  private fun adb(vararg args: String): String {
    val p = ProcessBuilder(listOf("adb", "-s", "emulator-5554") + args).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    check(p.waitFor() == 0) { out }
    return out
  }
  private fun shell(cmd: String) = adb("shell", "-x", cmd)

  @Test fun releaseSourceInstallsPublishedNestedAssetAndHandlesHomeOnRealTv() {
    assertEquals("github-hosted", System.getenv("RUNNER_ENVIRONMENT"))
    assertEquals("1", shell("getprop ro.kernel.qemu"))
    assertEquals("30", shell("getprop ro.build.version.sdk"))
    val original = shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").lines().last()
    assertTrue(original, original.startsWith("com.google.android.tvlauncher/"))
    val stock = original.substringBefore("/")
    val bridge = object : Adb {
      override fun shell(command: String): String = this@ReleasedBundleTest.shell(command)
      override fun push(source: File, remotePath: String) { adb("push", source.absolutePath, remotePath) }
    }
    val root = File(System.getProperty("chistytv.repo"))
    val db = Db.parse(File(root,"data/packages.json").readText())
    val temp = Files.createTempDirectory("chistytv-offline").toFile()
    // All calls from the release code to network transports fail in this test. The target Android shell is real.
    val offline = StubNet(fail = true)
    ZipFile(System.getenv("CHISTYTV_PACKAGE")).use { zip ->
      val cache = ApkCache(File(temp, "apks"), offline, openAsset = { name -> zip.getInputStream(zip.getEntry("assets/" + name)) })
      val session = TvSession(bridge, db, temp, cache, offline, Output { println(it.text) })
      assertFalse(session.launcherInstalled)
      assertTrue(session.installLauncher())
      assertTrue(session.launcherInstalled)
      val homeResult = session.makeLauncherHome()
      println("Release makeLauncherHome: " + homeResult)
      assertTrue("stock retained after ordinary install", stock !in session.info.disabled)
      if (homeResult == TvSession.HomeResult.STUBBORN) {
        val hard = session.disableStockLauncher()
        println("Release disableStockLauncher result after explicit consent: " + hard)
        assertEquals(TvSession.HardResult.DONE, hard)
        assertTrue(stock in session.info.disabled)
      } else {
        assertEquals(TvSession.HomeResult.OPENS, homeResult)
      }
      try {
        assertTrue(session.homeOpensLauncher())
        val restored = session.restore()
        assertTrue(restored.toString(), restored.failed.isEmpty())
        assertTrue("stock is enabled after restore", "package:" + stock in shell("pm list packages -e").lines())
      } finally {
        shell("pm enable " + stock)
        shell("cmd package set-home-activity --user 0 " + original)
      }
    }
    shell("input keyevent KEYCODE_HOME")
    Thread.sleep(3000)
    assertTrue(shell("dumpsys activity activities").lines().any { it.contains("mResumedActivity:") && it.contains(stock + "/") })
    assertTrue(offline.downloaded.isEmpty())
  }
}
