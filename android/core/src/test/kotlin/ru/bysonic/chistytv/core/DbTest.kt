package ru.bysonic.chistytv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Перенос test/check-db.ps1 плюс сверка со скриптом: приложение и chistytv.ps1 не должны разъезжаться. */
class DbTest {
  private val repo = File(System.getProperty("chistytv.repo"))
  private val db = Db.parse(File(repo, "data/packages.json").readText())

  @Test
  fun `настоящая база читается`() {
    assertTrue(db.platforms.size >= 7)
    assertTrue(db.packages.size >= 100)
  }

  @Test
  fun `в базе нет дубликатов`() {
    val dups = db.packages.groupBy { it.id }.filterValues { it.size > 1 }.keys
    assertTrue("дубликаты: $dups", dups.isEmpty())
  }

  @Test
  fun `действия и платформы известны`() {
    val platforms = db.platforms.map { it.id }.toSet() + "any"
    val bad = db.packages.filter { it.action !in setOf("disable", "ask", "keep") || it.platform !in platforms }
    assertTrue("непонятные записи: ${bad.map { it.id }}", bad.isEmpty())
  }

  @Test
  fun `ничего из отключаемого не попадает под защиту`() {
    val clash = db.packages.filter { it.action != "keep" && Protect.isProtected(it.id) }
    assertTrue("скрипт и приложение их не тронут, а база обещает: ${clash.map { it.id }}", clash.isEmpty())
  }

  @Test
  fun `защитные шаблоны те же, что в src_main_ps1`() {
    val script = File(repo, "src/main.ps1").readText()
    val block = Regex("\\\$Script:ProtectPatterns = @\\(([\\s\\S]*?)\\n\\)").find(script)!!.groupValues[1]
    val fromScript = Regex("'([^']+)'").findAll(block).map { it.groupValues[1] }.toList()
    assertEquals(fromScript, Protect.patterns)
  }

  @Test
  fun `пакеты приложений и DNS те же, что в скрипте`() {
    val script = File(repo, "src/main.ps1").readText()
    assertTrue(script.contains("\$Script:HappPackage = '${TvSession.HAPP_PACKAGE}'"))
    assertTrue(script.contains("\$Script:LauncherPackage = '${TvSession.LAUNCHER_PACKAGE}'"))
    assertTrue(script.contains(TvSession.ADGUARD_DNS))
    for (p in TvSession.SMARTTUBE_PACKAGES) assertTrue(p, script.contains("'$p'"))
  }
}
