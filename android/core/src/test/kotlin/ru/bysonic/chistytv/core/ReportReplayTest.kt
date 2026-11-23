package ru.bysonic.chistytv.core

import java.io.File
import java.net.URLDecoder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ReportReplayTest {
  private fun build(tv: FakeTv): TvSession {
    val repo = File(System.getProperty("chistytv.repo"))
    val dir = Files.createTempDirectory("issue-report").toFile()
    val net = StubNet()
    return TvSession(tv, Db.parse(File(repo, "data/packages.json").readText()), dir,
      ApkCache(File(dir, "apk"), net, emptyMap()), net, sleep = {})
  }

  @Test fun `26 Salute - distinguish journal from disabled and absent packages and preserve launcher`() {
    val owner = "ru.sberdevices.starlauncher"
    val active = listOf("ru.sberdevices.music", "ru.sberdevices.sberplay", "ru.sberdevices.huaweicast", "ru.sberdevices.startube")
    val off = listOf("ru.sberdevices.smarthome.promo", "ru.sberdevices.videos", "ru.sberdevices.gamepad.service")
    val tv = FakeTv(active + off + owner).apply {
      props["ro.product.manufacturer"] = "SaluteTV"
      props["ro.product.brand"] = "SaluteTV"
      props["ro.product.model"] = "SaluteTV"
      props["ro.build.version.release"] = "13"
      props["ro.build.version.sdk"] = "33"
      disabled += off
    }
    val session = build(tv)
    (off + active).forEach { session.store.record(it) }
    val report = session.report("1.0.8", emptyList()).full
    assertTrue(report, report.contains("Включились снова (не через ЧистыйТВ):** " + active.joinToString(", ")))
    tv.installed.removeAll(active)
    session.refresh()
    assertFalse(session.report("1.0.8", emptyList()).full.contains("Включились снова"))
    tv.disabled.clear()
    session.refresh()
    assertTrue("show the launcher hint after this cleanup",
      session.clean(session.plan().filter { it.action == "disable" }).homeHint)
    assertFalse(owner in tv.disabled)
    assertFalse(tv.commands.any { it.startsWith("pm disable") && it.endsWith(owner) })
    tv.disabled.clear()
    tv.installed += "com.leanbitlab.ltvL"
    session.refresh()
    val selected = session.plan().filter { it.action == "disable" }
    assertTrue("same cleanup with LTvLauncher present", selected.isNotEmpty())
    assertFalse("hint suppressed when LTvLauncher already installed", session.clean(selected).homeHint)
  }

  @Test fun `25 Haier repeated loss report preserves diagnostics without diagnosing the network`() {
    val tv = FakeTv(listOf("com.google.android.tvlauncher")).apply {
      props["ro.product.manufacturer"] = "Haier"
      props["ro.product.model"] = "Haier Android TV FF Pro"
      props["ro.build.version.release"] = "14"
      props["ro.build.version.sdk"] = "34"
    }
    val errors = List(5) { "Связь с телевизором потеряна" }
    val report = build(tv).report("приложение 1.0.8", errors).full
    assertEquals(5, report.lineSequence().count { it == "- " + errors.first() })
    assertFalse(report.contains("глубокий сон"))
  }

  @Test fun `17 SDMC DBOX02 Android 11 browser issue template contains recoverable diagnostics`() {
    val tv = FakeTv((1..300).map { "com.example.firmware.package$it" }).apply {
      props["ro.product.manufacturer"] = "SDMC"
      props["ro.product.model"] = "DBOX02"
    }
    val report = build(tv).report("приложение 1.0.8", List(15) { "Длинное сообщение ".repeat(25) })
    val url = report.url(2000)
    assertTrue(url.length <= 2000)
    val body = URLDecoder.decode(url.substringAfter("&body="), "UTF-8")
    assertTrue(body, body.contains("приложение 1.0.8") && body.contains("Android") && body.contains("SDK 30"))
    assertTrue(URLDecoder.decode(url.substringBefore("&body="), "UTF-8").contains("SDMC DBOX02"))
  }
}
