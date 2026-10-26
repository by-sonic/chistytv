package ru.bysonic.chistytv.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.bysonic.chistytv.MainViewModel
import ru.bysonic.chistytv.Screen
import ru.bysonic.chistytv.core.PackageEntry
import ru.bysonic.chistytv.core.TvSession
import ru.bysonic.chistytv.ui.theme.Accent
import ru.bysonic.chistytv.ui.theme.C

/** Общий каркас экрана действия: шапка, прокручиваемое содержимое, кнопка снизу */
@Composable
private fun ActionScaffold(title: String, vm: MainViewModel, bottom: (@Composable ColumnScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
  Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
    TopBar(title, onBack = { vm.back() })
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), content = content)
    if (bottom != null) Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = bottom)
  }
}

@Composable
private fun Header(icon: androidx.compose.ui.graphics.vector.ImageVector, colors: List<Color>, title: String, text: String) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    IconBadge(icon, colors, 56.dp)
    Spacer(Modifier.width(16.dp))
    Column {
      Text(title, style = MaterialTheme.typography.headlineSmall, color = C.Text)
    }
  }
  Spacer(Modifier.height(12.dp))
  Text(text, style = MaterialTheme.typography.bodyLarge, color = C.TextDim)
  Spacer(Modifier.height(20.dp))
}

// ─── Чистка ─────────────────────────────────────────────────────────────

@Composable
fun CleanScreen(vm: MainViewModel) {
  val done = vm.cleanResult
  val recommended = vm.plan.filter { it.action == "disable" }
  val optional = vm.plan.filter { it.action == "ask" }
  val chosen = vm.plan.count { vm.selection[it.id] == true }
  ActionScaffold("Чистка", vm, bottom = {
    when {
      done != null -> {
        if (done.homeHint) GhostButton("Новый главный экран", { vm.go(Screen.Launcher) }, Modifier.fillMaxWidth(), Icons.Rounded.Dashboard)
        GradientButton("Готово", { vm.back() }, Modifier.fillMaxWidth(), Icons.Rounded.CheckCircle)
      }
      vm.plan.isEmpty() -> GradientButton("Назад", { vm.back() }, Modifier.fillMaxWidth())
      else -> GradientButton(if (chosen > 0) "Отключить $chosen" else "Ничего не выбрано", { vm.runClean() }, Modifier.fillMaxWidth(), Icons.Rounded.CleaningServices, enabled = chosen > 0 && !vm.busy, loading = vm.busy)
    }
  }) {
    Header(
      Icons.Rounded.CleaningServices, Accent.Clean, "Реклама и мусор",
      "Приложения не удаляются, а отключаются — всё возвращается кнопкой «Вернуть как было». Перед изменениями сохраню бэкап списка приложений и настроек.",
    )
    if (vm.plan.isEmpty()) {
      Hint("Известного мусора не нашёл — или он уже отключён. Для редкой модели можно пройтись глубже с ИИ-ассистентом: PROMPT.md в репозитории.")
      return@ActionScaffold
    }
    if (done == null && !vm.busy) {
      if (recommended.isNotEmpty()) {
        SectionTitle("Реклама, слежка и фоновый мусор — безопасно (${recommended.size})")
        PackageList(recommended, vm)
        Spacer(Modifier.height(20.dp))
      }
      if (optional.isNotEmpty()) {
        SectionTitle("Ваши приложения — отметьте ненужные (${optional.size})")
        PackageList(optional, vm)
        Spacer(Modifier.height(20.dp))
      }
    }
    // Подсказку про лаунчер показываем карточкой ниже, в журнале она бы повторялась
    LogPanel(if (done?.homeHint == true) vm.log.filterNot { it.text.startsWith("Ряды с рекламой") } else vm.log, vm.busy)
    if (done?.homeHint == true) {
      Spacer(Modifier.height(12.dp))
      Hint("Ряды с рекламой на главном экране рисует сам штатный лаунчер — его не трогаем, иначе ТВ останется без главного экрана. Уберёт их «Новый главный экран».", Icons.Rounded.Dashboard, C.Teal)
    }
    Spacer(Modifier.height(12.dp))
  }
}

@Composable
private fun PackageList(items: List<PackageEntry>, vm: MainViewModel) {
  GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 6.dp)) {
    items.forEachIndexed { i, e ->
      val checked = vm.selection[e.id] == true
      Row(
        Modifier
          .fillMaxWidth()
          .clickable { vm.selection[e.id] = !checked }
          .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Checkbox(checked, { vm.selection[e.id] = it }, colors = CheckboxDefaults.colors(checkedColor = C.Violet, uncheckedColor = C.TextMuted, checkmarkColor = Color.White))
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
          Text(e.title, style = MaterialTheme.typography.titleSmall, color = C.Text)
          if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodySmall, color = C.TextDim)
          Text(e.id, style = MaterialTheme.typography.labelSmall, color = C.TextMuted, fontFamily = FontFamily.Monospace)
        }
      }
      if (i < items.lastIndex) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(C.Stroke))
    }
  }
}

// ─── YouTube ────────────────────────────────────────────────────────────

@Composable
fun YouTubeScreen(vm: MainViewModel) {
  val s = vm.session ?: return
  @Suppress("UNUSED_VARIABLE") val tick = vm.info // перерисовать, когда обновятся сведения о ТВ
  val context = LocalContext.current
  var sub by rememberSaveable { mutableStateOf("") }
  var code by rememberSaveable { mutableStateOf("") }
  ActionScaffold("YouTube без тормозов", vm) {
    Header(Icons.Rounded.SmartDisplay, Accent.YouTube, "YouTube без тормозов", "SmartTube — открытый клиент YouTube для ТВ: без рекламы, со SponsorBlock и 4K. Без VPN YouTube в России тормозит — поставим Happ и добавим вашу подписку.")

    StepCard(
      1, "SmartTube", if (s.smartTubeCurrent) "Установлен" else if (s.smartTubeInstalled.isNotEmpty()) "Старая сборка — не обновляется" else "Не установлен", s.smartTubeCurrent,
      if (s.smartTubeCurrent) null else { { GradientButton("Установить с GitHub", { vm.installSmartTube() }, Modifier.fillMaxWidth(), Icons.Rounded.Download, enabled = !vm.busy, loading = vm.busy) } },
    )
    Spacer(Modifier.height(12.dp))
    StepCard(
      2, "VPN-клиент Happ", if (s.happInstalled) "Установлен" else "Не установлен", s.happInstalled,
      if (s.happInstalled) null else { { GradientButton("Установить с GitHub", { vm.installHapp() }, Modifier.fillMaxWidth(), Icons.Rounded.Download, enabled = !vm.busy, loading = vm.busy) } },
    )
    Spacer(Modifier.height(12.dp))
    StepCard(3, "Подписка VPN", if (vm.happResult == TvSession.HappResult.Sent || vm.webImportSent) "Отправлена в Happ" else "Ссылка из вашего VPN-бота", vm.happResult == TvSession.HappResult.Sent || vm.webImportSent) {
      AppTextField(sub, { sub = it }, "https://…", KeyboardType.Uri, trailing = {
        IconButton({
          val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
          clip.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { sub = it.trim() }
        }) { Icon(Icons.Rounded.ContentPaste, "Вставить", tint = C.TextDim) }
      })
      Spacer(Modifier.height(10.dp))
      Row(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable {
          context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(VPN_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(Icons.Rounded.VpnKey, null, tint = C.Warn, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Нет VPN? $VPN_NAME — бесплатный пробный период", style = MaterialTheme.typography.bodySmall, color = C.Warn)
      }
      Spacer(Modifier.height(12.dp))
      GradientButton("Отправить в Happ", { vm.sendSubscription(sub) }, Modifier.fillMaxWidth(), Icons.Rounded.Send, enabled = sub.isNotBlank() && s.happInstalled && !vm.busy)
      if (!s.happInstalled) Text("Сначала установите Happ", style = MaterialTheme.typography.bodySmall, color = C.TextMuted, modifier = Modifier.padding(top = 6.dp))
    }

    AnimatedVisibility(vm.happResult is TvSession.HappResult.Rejected && !vm.webImportSent) {
      Column {
        Spacer(Modifier.height(12.dp))
        GlassCard(Modifier.fillMaxWidth(), border = SolidColor(C.Warn.copy(alpha = 0.4f))) {
          Text("Запасной способ Happ для ТВ", style = MaterialTheme.typography.titleMedium, color = C.Text)
          Spacer(Modifier.height(6.dp))
          Text("В Happ на телевизоре выберите «Web Import» — появится 5-значный код. Ссылка подписки при этом пройдёт через сервер Happ (check.happ.su).", style = MaterialTheme.typography.bodyMedium, color = C.TextDim)
          Spacer(Modifier.height(12.dp))
          CodeInput(code, { code = it }, length = 5)
          Spacer(Modifier.height(12.dp))
          GradientButton("Отправить через сервер Happ", { vm.webImport(code, sub) }, Modifier.fillMaxWidth(), enabled = code.length == 5 && !vm.busy)
        }
      }
    }
    Spacer(Modifier.height(16.dp))
    LogPanel(vm.log, vm.busy)
    Spacer(Modifier.height(16.dp))
  }
}

@Composable
private fun StepCard(n: Int, title: String, status: String, done: Boolean, content: (@Composable ColumnScope.() -> Unit)? = null) {
  GlassCard(Modifier.fillMaxWidth(), border = if (done) SolidColor(C.Ok.copy(alpha = 0.45f)) else null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(if (done) C.Ok else C.Violet.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
        if (done) Icon(Icons.Rounded.CheckCircle, null, tint = Color.White, modifier = Modifier.size(20.dp))
        else Text("$n", style = MaterialTheme.typography.titleSmall, color = C.Text)
      }
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = C.Text)
        Text(status, style = MaterialTheme.typography.bodySmall, color = if (done) C.Ok else C.TextDim)
      }
    }
    if (content != null) {
      Spacer(Modifier.height(12.dp))
      content()
    }
  }
}

// ─── Главный экран ──────────────────────────────────────────────────────

@Composable
fun LauncherScreen(vm: MainViewModel) {
  val s = vm.session ?: return
  var confirm by remember { mutableStateOf(false) }
  val home = vm.homeResult
  ActionScaffold("Новый главный экран", vm, bottom = {
    when {
      home == TvSession.HomeResult.OPENS || vm.hardResult == TvSession.HardResult.DONE -> GradientButton("Готово", { vm.back() }, Modifier.fillMaxWidth(), Icons.Rounded.CheckCircle)
      home == TvSession.HomeResult.STUBBORN -> GhostButton("Включил «Home Button Fix» — проверить", { vm.checkHomeAgain() }, Modifier.fillMaxWidth(), enabled = !vm.busy)
      else -> GradientButton(if (s.launcherInstalled) "Сделать главным экраном" else "Установить и сделать главным", { vm.makeHome() }, Modifier.fillMaxWidth(), Icons.Rounded.Dashboard, enabled = !vm.busy, loading = vm.busy)
    }
  }) {
    Header(Icons.Rounded.Dashboard, Accent.Home, "Чистый главный экран", "LTvLauncher — открытый лаунчер (GPL-3.0) без рекламных рядов и рекомендаций. Штатный главный экран не удаляется и не отключается, пока вы сами не попросите.")
    LogPanel(vm.log, vm.busy)
    if (home == TvSession.HomeResult.STUBBORN && vm.hardResult == null) {
      Spacer(Modifier.height(14.dp))
      Hint("Мягкий способ: на ТВ откройте Настройки → Специальные возможности → «Home Button Fix» (LTvLauncher) и включите. Затем нажмите «Домой» на пульте.", Icons.Rounded.Dashboard, C.Sky)
      val stock = vm.stockLaunchers
      if (stock.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        GlassCard(Modifier.fillMaxWidth(), border = SolidColor(C.Warn.copy(alpha = 0.4f))) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WarningAmber, null, tint = C.Warn)
            Spacer(Modifier.width(10.dp))
            Text("Жёсткий способ", style = MaterialTheme.typography.titleMedium, color = C.Text)
          }
          Spacer(Modifier.height(8.dp))
          Text("Отключить штатный лаунчер. Обратимо через «Вернуть как было». Если «Домой» всё равно не откроет LTvLauncher, верну штатный сам.", style = MaterialTheme.typography.bodyMedium, color = C.TextDim)
          Spacer(Modifier.height(8.dp))
          stock.forEach { Text("•  ${it.title} (${it.id})", style = MaterialTheme.typography.bodySmall, color = C.TextDim) }
          Spacer(Modifier.height(12.dp))
          GhostButton("Отключить штатный лаунчер", { confirm = true }, Modifier.fillMaxWidth(), color = C.Warn, enabled = !vm.busy)
        }
      }
    }
    Spacer(Modifier.height(16.dp))
  }
  if (confirm) {
    AlertDialog(
      onDismissRequest = { confirm = false },
      containerColor = C.SurfaceHigh,
      title = { Text("Отключить штатный лаунчер?", color = C.Text) },
      text = { Text("Лаунчер и его парные пакеты отключатся вместе. Если пакет владеет сохранённой сетью Wi-Fi, ничего не трону — иначе ТВ потеряет Wi-Fi.", color = C.TextDim) },
      confirmButton = { TextButton({ confirm = false; vm.disableStock() }) { Text("Отключить", color = C.Warn) } },
      dismissButton = { TextButton({ confirm = false }) { Text("Отмена", color = C.TextDim) } },
    )
  }
}

// ─── DNS ────────────────────────────────────────────────────────────────

@Composable
fun DnsScreen(vm: MainViewModel) {
  val enabled = vm.dnsEnabled
  ActionScaffold("Блокировка через DNS", vm, bottom = {
    when (enabled) {
      true -> GhostButton("Выключить AdGuard DNS", { vm.disableDns() }, Modifier.fillMaxWidth(), enabled = !vm.busy)
      false -> GradientButton("Включить AdGuard DNS", { vm.enableDns() }, Modifier.fillMaxWidth(), Icons.Rounded.Shield, enabled = !vm.busy, loading = vm.busy)
      null -> {}
    }
  }) {
    Header(Icons.Rounded.Shield, Accent.Dns, "AdGuard DNS", "Режет рекламу и трекеры во всех приложениях телевизора. Рекламу внутри YouTube не убирает — для неё SmartTube. После включения проверю, что интернет на ТВ работает, и если нет — сразу верну как было.")
    GlassCard(Modifier.fillMaxWidth()) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(50)).background(if (enabled == true) C.Ok else C.TextMuted))
        Spacer(Modifier.width(12.dp))
        Text(
          when (enabled) {
            true -> "Частный DNS включён"
            false -> "Частный DNS выключен"
            null -> "Проверяю…"
          },
          style = MaterialTheme.typography.titleMedium, color = C.Text,
        )
      }
      vm.dnsResult?.let {
        Spacer(Modifier.height(6.dp))
        Text(dnsText(it), style = MaterialTheme.typography.bodySmall, color = if (it == TvSession.DnsResult.ENABLED) C.Ok else C.Warn)
      }
    }
    Spacer(Modifier.height(14.dp))
    LogPanel(vm.log.filter { it.text.isNotBlank() }, vm.busy)
    Spacer(Modifier.height(16.dp))
  }
}

// ─── Откат ──────────────────────────────────────────────────────────────

@Composable
fun RestoreScreen(vm: MainViewModel) {
  val r = vm.restoreResult
  ActionScaffold("Вернуть как было", vm, bottom = {
    if (r != null) GradientButton("Готово", { vm.back() }, Modifier.fillMaxWidth(), Icons.Rounded.CheckCircle)
    else GradientButton("Включить всё обратно", { vm.runRestore() }, Modifier.fillMaxWidth(), Icons.Rounded.Replay, enabled = vm.journal.isNotEmpty() && !vm.busy, loading = vm.busy)
  }) {
    Header(Icons.Rounded.Replay, Accent.Restore, "Вернуть как было", "Включу всё, что отключал ЧистыйТВ — с этого телефона, с другого или скриптом с компьютера: журнал хранится и на самом телевизоре.")
    if (r == null && !vm.busy) {
      if (vm.journal.isEmpty()) Hint("ЧистыйТВ на этом телевизоре ещё ничего не отключал.")
      else {
        SectionTitle("Отключено ЧистымТВ (${vm.journal.size})")
        GlassCard(Modifier.fillMaxWidth()) {
          vm.journal.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = C.TextDim, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 3.dp)) }
        }
      }
      Spacer(Modifier.height(14.dp))
    }
    LogPanel(if (r == null && !vm.busy) emptyList() else vm.log, vm.busy)
    Spacer(Modifier.height(16.dp))
  }
}

const val VPN_NAME = "RoseVPN"
const val VPN_URL = "https://t.me/rosevpnru_bot"
