package ru.bysonic.chistytv.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.bysonic.chistytv.MainViewModel
import ru.bysonic.chistytv.Screen
import ru.bysonic.chistytv.ui.theme.C

/** Как включить отладку — коротко по docs/ADB.md */
private data class Guide(val tab: String, val steps: List<String>, val note: String? = null)

private val guides = listOf(
  Guide(
    "Android TV",
    listOf(
      "Настройки → Система → Об устройстве",
      "7 раз нажмите «Сборка» — появится «Вы стали разработчиком»",
      "Назад → «Для разработчиков» → включите «Отладка по USB» и «Отладка по сети», если есть",
      "Нажмите «Найти телевизор» и подтвердите на ТВ «Разрешить отладку?» — отметьте «Всегда»",
    ),
    "Android 11+ без «Отладки по сети»: включите «Беспроводная отладка» и выберите в приложении «Подключение по коду».",
  ),
  Guide(
    "Яндекс ТВ",
    listOf(
      "Настройки → Настройки устройства → Об устройстве → 7 раз «Сборка» → «Для разработчиков» → «Отладка по USB»",
      "Нет пункта? Родной пульт ТВ: зажмите «Меню» ~1 сек, затем быстро ← ↑ ← ↑ и «Назад»",
      "Other options → ADB → On (или DEBUG → ADB ENABLE). Не открылось — попробуйте «Меню» → 1147 или «Источник» → 2580",
      "Меню с пульта не открывается: поставьте с флешки FactoryMenu (4PDA, версия под ваш Android) → DEBUG → ADB ENABLE",
      "Окна «Разрешить отладку?» нет — выключите ТВ из розетки на 10 секунд и подключитесь снова",
    ),
    "В сервисном меню не трогайте ничего, кроме ADB: остальные пункты могут дать чёрный экран. Подробно — docs/ADB.md на GitHub.",
  ),
  Guide(
    "Салют ТВ",
    listOf(
      "На ТВ: Настройки → Об устройстве — запишите ID устройства",
      "Sber Studio под тем же Сбер ID: Настройки сервисов → SmartApp → Мои устройства → добавьте и включите ADB",
      "Перезагрузите телевизор и нажмите «Найти телевизор»",
    ),
    "Sber SBX на Android 13: «Номер сборки» ×7 → «Отладка по Wi-Fi»; порт закрывается после каждой перезагрузки.",
  ),
  Guide(
    "Samsung, LG",
    listOf(
      "Tizen, webOS и VIDAA — это не Android: с ними ЧистыйТВ не работает",
      "Для YouTube без тормозов проще всего недорогая приставка на Android TV или VPN на роутере",
    ),
  ),
)

@Composable
fun StartScreen(vm: MainViewModel) {
  var tab by remember { mutableIntStateOf(0) }
  Column(
    Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .statusBarsPadding()
      .navigationBarsPadding()
      .padding(horizontal = 20.dp),
  ) {
    Spacer(Modifier.height(28.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(C.Brand), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Tv, null, tint = Color.White, modifier = Modifier.size(34.dp))
        Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(16.dp).align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp))
      }
      Spacer(Modifier.width(16.dp))
      Column {
        Text("ЧистыйТВ", style = MaterialTheme.typography.displaySmall, color = C.Text)
        Text("без рекламы · с телефона", style = MaterialTheme.typography.bodyMedium, color = C.TextDim)
      }
    }
    Spacer(Modifier.height(22.dp))
    Text(
      "Уберёт рекламу и слежку с телевизора, поставит YouTube без тормозов и чистый главный экран. Ничего не удаляется — всё возвращается одной кнопкой.",
      style = MaterialTheme.typography.bodyLarge,
      color = C.TextDim,
    )

    vm.lastTv?.let { (address, name) ->
      Spacer(Modifier.height(20.dp))
      GlassCard(Modifier.fillMaxWidth(), onClick = { vm.reconnectLast() }, border = C.Brand) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconBadge(Icons.Rounded.History, listOf(C.Violet, C.Teal), 44.dp)
          Spacer(Modifier.width(14.dp))
          Column(Modifier.weight(1f)) {
            Text("Продолжить с $name", style = MaterialTheme.typography.titleMedium, color = C.Text)
            Text(address, style = MaterialTheme.typography.bodySmall, color = C.TextDim)
          }
        }
      }
    }

    Spacer(Modifier.height(24.dp))
    SectionTitle("Как подготовить телевизор")
    GlassCard(Modifier.fillMaxWidth()) {
      Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        guides.forEachIndexed { i, g ->
          val on = i == tab
          Text(
            g.tab,
            style = MaterialTheme.typography.labelLarge,
            color = if (on) Color.White else C.TextDim,
            modifier = Modifier
              .clip(RoundedCornerShape(50))
              .background(if (on) C.Brand else androidx.compose.ui.graphics.SolidColor(C.Stroke))
              .clickable { tab = i }
              .padding(horizontal = 14.dp, vertical = 8.dp),
          )
        }
      }
      Spacer(Modifier.height(16.dp))
      AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "guide") { t ->
        Column {
          guides[t].steps.forEachIndexed { i, s ->
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
              Box(Modifier.size(24.dp).clip(CircleShape).background(C.Violet.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = C.Text, fontWeight = FontWeight.Bold)
              }
              Spacer(Modifier.width(12.dp))
              Text(s, style = MaterialTheme.typography.bodyMedium, color = C.Text, modifier = Modifier.padding(top = 2.dp))
            }
          }
          guides[t].note?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = C.TextDim)
          }
        }
      }
    }

    if (vm.discovery.vpnActive()) {
      Spacer(Modifier.height(14.dp))
      Hint("На телефоне включён VPN. ЧистыйТВ попробует обойти его для домашней сети, но если телевизор не найдётся — выключите VPN на время настройки.", Icons.Rounded.VpnLock, C.Warn)
    }

    Spacer(Modifier.height(24.dp))
    GradientButton("Найти телевизор", { vm.go(Screen.Search); vm.startScan() }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Radar)
    Spacer(Modifier.height(14.dp))
    Text(
      "Телефон и телевизор — в одной Wi-Fi сети. Данные никуда не отправляются: приложение говорит только с вашим ТВ и скачивает программы с их официальных GitHub.",
      style = MaterialTheme.typography.bodySmall,
      color = C.TextMuted,
      textAlign = TextAlign.Center,
      modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(24.dp))
  }
}
