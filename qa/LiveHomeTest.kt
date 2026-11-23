package ru.bysonic.chistytv.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Run exclusively against the ephemeral Android TV 11 emulator in the QA workflow. */
class LiveHomeTest {
  private val serial = "emulator-5554"
  private fun adb(vararg args: String): String {
    val process = ProcessBuilder(listOf("adb", "-s", serial) + args).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    check(process.waitFor() == 0) { "adb ${args.joinToString(" ")}: $output" }
    return output
  }

  @Test fun `Android TV 11 - recover original HOME after user disabled it outside ChistyTV`() {
    assertEquals("github-hosted", System.getenv("RUNNER_ENVIRONMENT"))
    assertEquals("1", adb("shell", "getprop ro.kernel.qemu"))
    assertEquals("30", adb("shell", "getprop ro.build.version.sdk"))
    assertTrue(adb("shell", "pm list features").contains("android.software.leanback"))
    println("Image: " + adb("shell", "getprop ro.build.fingerprint"))
    val intent = "-a android.intent.action.MAIN -c android.intent.category.HOME"
    fun resolve() = adb("shell", "cmd package resolve-activity --brief $intent").lines().last()
    val original = resolve()
    val home = original.substringBefore("/")
    println("Resolved HOME before: $original")
    assertTrue(original, original.contains("/") && home.matches(Regex("[A-Za-z0-9_.]+")))
    assertTrue("image has an actual launcher: $original", "launcher" in home)
    val bridge = object : Adb {
      // App Adb.shell returns command output even when a remote utility exits 1,
      // for example reading the not-yet-created optional journal on a clean image.
      override fun shell(command: String) = adb("shell", "-x", command)
      override fun push(source: File, remotePath: String) { adb("push", source.absolutePath, remotePath) }
    }
    val root = File(System.getProperty("chistytv.repo"))
    val db = Db.parse(File(root, "data/packages.json").readText())
    val dir = Files.createTempDirectory("chistytv-api30").toFile()
    val net = StubNet()
    val session = TvSession(bridge, db, dir, ApkCache(File(dir, "apk"), net, emptyMap()), net, Output { println(it.text) })
    assertTrue("clean TV", session.store.recorded().isEmpty())
    try {
      assertTrue(adb("shell", "pm disable-user --user 0 $home").contains("disabled-user"))
      assertTrue("package disabled", adb("shell", "pm list packages -d").lines().contains("package:$home"))
      val disabledHome = resolve()
      println("Resolved HOME while stock launcher disabled: $disabledHome")
      assertNotEquals("HOME no longer assigned", original, disabledHome)
      val result = session.restore()
      assertTrue(result.toString(), home in result.restored && result.failed.isEmpty())
      assertTrue(adb("shell", "pm list packages -e").lines().contains("package:$home"))
      val recovered = resolve()
      println("Resolved HOME after release restore: $recovered")
      assertEquals("restored default HOME component", original, recovered)
      val launch = adb("shell", "am start -W $intent")
      println(launch)
      assertTrue(launch, launch.contains("Status: ok"))
      // Resolve and launch use the unqualified HOME intent, as does the Home key.
      assertTrue(launch, launch.contains(home))
    } finally {
      adb("shell", "pm enable $home")
    }
  }
}
