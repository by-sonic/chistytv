package ru.bysonic.chistytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ru.bysonic.chistytv.MainViewModel
import ru.bysonic.chistytv.Screen
import ru.bysonic.chistytv.core.TvSession
import ru.bysonic.chistytv.ui.theme.Accent
import ru.bysonic.chistytv.ui.theme.C

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(vm: MainViewModel) {
  val info = vm.info ?: return
  val session = vm.session
  Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
      Spacer(Modifier.height(20.dp))
      // Карточка телевизора
      GlassCard(Modifier.fillMaxWidth(), border = C.Brand, padding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(Modifier.size(58.dp).clip(RoundedCornerShape(18.dp)).background(C.Brand), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Tv, null, tint = Color.White, modifier = Modifier.size(32.dp))
          }
          Spacer(Modifier.width(16.dp))
          Column(Modifier.weight(1f)) {
            Text("Подключено", style = MaterialTheme.typography.labelMedium, color = C.Ok)
            Text(info.title, style = MaterialTheme.typography.titleLarge, color = C.Text)
          }
        }
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Chip("Android ${info.android}", C.Sky)
          vm.platformNames.forEach { Chip(it, C.Violet) }
          if (vm.platformNames.isEmpty()) Chip("общие правила Android TV", C.TextDim)
          Chip("приложений ${info.installed.size}", C.TextDim)
          if (info.disabled.isNotEmpty()) Chip("отключено ${info.disabled.size}", C.Teal)
        }
      }

      if (vm.lostConnection) {
        Spacer(Modifier.height(12.dp))
        GlassCard(Modifier.fillMaxWidth(), onClick = { vm.reconnectAfterLoss() }, border = androidx.compose.ui.graphics.SolidColor(C.Fail.copy(alpha = 0.5f))) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WarningAmber, null, tint = C.Fail)
            Spacer(Modifier.width(10.dp))
            Text("Связь с телевизором пропала — нажмите, чтобы переподключиться", color = C.Text, style = MaterialTheme.typography.bodyMedium)
          }
        }
      }

      Spacer(Modifier.height(24.dp))
      SectionTitle("Что делаем")
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val plan = session?.plan().orEmpty()
        ActionTile(
          Icons.Rounded.CleaningServices, Accent.Clean, "Убрать рекламу и мусор",
          planSummary(plan.count { it.action == "disable" }, plan.count { it.action == "ask" }),
          badge = "начните с этого", badgeColor = C.Violet,
        ) { vm.openClean() }
        ActionTile(
          Icons.Rounded.SmartDisplay, Accent.YouTube, "YouTube без тормозов",
          "SmartTube без рекламы + VPN Happ на телевизор",
          badge = if (session?.smartTubeCurrent == true) "SmartTube стоит" else null, badgeColor = C.Ok,
        ) { vm.go(Screen.YouTube) }
        ActionTile(
          Icons.Rounded.Dashboard, Accent.Home, "Новый главный экран",
          "LTvLauncher — без рекламных рядов и рекомендаций",
          badge = if (session?.launcherInstalled == true) "установлен" else null, badgeColor = C.Ok,
        ) { vm.go(Screen.Launcher) }
        ActionTile(Icons.Rounded.Shield, Accent.Dns, "Блокировка рекламы через DNS", "AdGuard DNS режет рекламу и трекеры в приложениях") { vm.openDns() }
        ActionTile(Icons.Rounded.Replay, Accent.Restore, "Вернуть как было", "Включить всё, что отключил ЧистыйТВ") { vm.openRestore() }
      }
      Spacer(Modifier.height(20.dp))
      Hint(
        "Когда закончите, выключите на ТВ «Отладку по сети» и «Отладку по USB»: пока они включены, порт ${if (vm.connectTarget.endsWith(":5555")) "5555" else "отладки"} открыт для всей домашней сети.",
        Icons.Rounded.Shield, C.Warn,
      )
      Spacer(Modifier.height(16.dp))
    }
    GhostButton("Отключиться", { vm.disconnect() }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), Icons.Rounded.LinkOff, C.TextDim)
  }
}

/** Русские окончания: 1 сервис, 2 сервиса, 5 сервисов */
fun plural(n: Int, one: String, few: String, many: String): String {
  val m10 = n % 10
  val m100 = n % 100
  return when {
    m10 == 1 && m100 != 11 -> one
    m10 in 2..4 && m100 !in 12..14 -> few
    else -> many
  }
}

fun planSummary(ads: Int, apps: Int): String {
  val parts = buildList {
    if (ads > 0) add("$ads ${plural(ads, "рекламный сервис", "рекламных сервиса", "рекламных сервисов")}")
    if (apps > 0) add("$apps ${plural(apps, "лишнее приложение", "лишних приложения", "лишних приложений")}")
  }
  return if (parts.isEmpty()) "Известного мусора не видно — или он уже отключён" else "Нашёл " + parts.joinToString(" и ")
}

/** Пояснения к результатам — общие для экранов */
fun dnsText(r: TvSession.DnsResult): String = when (r) {
  TvSession.DnsResult.ENABLED -> "AdGuard DNS работает"
  TvSession.DnsResult.ENABLED_UNCHECKED -> "Включено без проверки"
  TvSession.DnsResult.ROLLED_BACK -> "Сеть не пропускает DNS-over-TLS — вернул как было"
  TvSession.DnsResult.TOO_OLD -> "Нужен Android 9 и новее"
}
