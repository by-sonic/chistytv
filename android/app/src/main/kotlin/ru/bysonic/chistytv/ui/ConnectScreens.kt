package ru.bysonic.chistytv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.bysonic.chistytv.MainViewModel
import ru.bysonic.chistytv.Screen
import ru.bysonic.chistytv.adb.FoundTv
import ru.bysonic.chistytv.adb.TvConnection
import ru.bysonic.chistytv.ui.theme.C

@Composable
fun SearchScreen(vm: MainViewModel) {
  var manual by remember { mutableStateOf(false) }
  Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
    TopBar("Поиск телевизора", onBack = { vm.stopScan(); vm.back() })
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
      Radar(vm.scanning, Modifier.fillMaxWidth().aspectRatio(1.25f)) {
        Icon(Icons.Rounded.Tv, null, tint = Color.White, modifier = Modifier.size(44.dp))
      }
      Text(
        when {
          vm.scanning -> "Ищу телевизор в вашей Wi-Fi сети…"
          vm.found.isEmpty() -> "Телевизор не нашёлся"
          else -> "Нашёл — выберите телевизор"
        },
        style = MaterialTheme.typography.headlineSmall,
        color = C.Text,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
      )
      if (vm.scannedNets.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Centered("Сеть ${vm.scannedNets.joinToString()}")
      }
      Spacer(Modifier.height(18.dp))

      val pairing = vm.found.filter { it.kind == FoundTv.Kind.PAIRING }
      vm.found.filter { it.kind != FoundTv.Kind.PAIRING }.forEach { tv ->
        AnimatedVisibility(true, enter = fadeIn() + slideInVertically { it / 2 }) {
          FoundTvCard(tv) {
            if (tv.kind == FoundTv.Kind.WIRELESS_DEBUG) vm.connect(tv.address, tls = true) else vm.connect(tv.address)
          }
        }
        Spacer(Modifier.height(10.dp))
      }
      if (pairing.isNotEmpty()) {
        GlassCard(Modifier.fillMaxWidth(), onClick = { vm.go(Screen.Pair) }, border = C.Brand) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Key, listOf(C.Pink, C.Violet), 44.dp)
            Spacer(Modifier.width(14.dp))
            Column {
              Text("Телевизор ждёт код", style = MaterialTheme.typography.titleMedium, color = C.Text)
              Text("На экране ТВ открыто «Подключить по коду» — введите шесть цифр", style = MaterialTheme.typography.bodySmall, color = C.TextDim)
            }
          }
        }
        Spacer(Modifier.height(10.dp))
      }

      if (!vm.scanning && vm.found.isEmpty()) {
        GlassCard(Modifier.fillMaxWidth()) {
          Text("Проверьте:", style = MaterialTheme.typography.titleSmall, color = C.Text)
          Spacer(Modifier.height(8.dp))
          listOf(
            "телевизор включён и в той же Wi-Fi сети, что телефон",
            "на ТВ включена «Отладка по USB» и «Отладка по сети» (или ADB Mode в сервисном меню)",
            "в роутере нет «изоляции клиентов» и это не гостевая сеть",
            "Android 11+ без «Отладки по сети» — подключитесь по коду",
          ).forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium, color = C.TextDim, modifier = Modifier.padding(vertical = 3.dp)) }
        }
        Spacer(Modifier.height(10.dp))
      }
      if (vm.vpnActive) {
        Hint("На телефоне включён VPN. Если телевизор не находится, выключите VPN на время настройки.", Icons.Rounded.VpnLock, C.Warn)
        Spacer(Modifier.height(10.dp))
      }
    }
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GhostButton("Ввести IP", { manual = true }, Modifier.weight(1f), Icons.Rounded.Edit)
        GhostButton("По коду", { vm.go(Screen.Pair) }, Modifier.weight(1f), Icons.Rounded.Key)
      }
      if (!vm.scanning) GradientButton("Искать снова", { vm.startScan() }, Modifier.fillMaxWidth(), Icons.Rounded.Refresh)
    }
  }
  if (manual) ManualIpDialog(onDismiss = { manual = false }) { manual = false; vm.connect(it) }
}

@Composable
private fun FoundTvCard(tv: FoundTv, onClick: () -> Unit) {
  GlassCard(Modifier.fillMaxWidth(), onClick = onClick, border = androidx.compose.ui.graphics.Brush.linearGradient(listOf(C.Teal.copy(alpha = 0.6f), C.Violet.copy(alpha = 0.4f)))) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      IconBadge(Icons.Rounded.Tv, listOf(C.Teal, C.Sky), 48.dp)
      Spacer(Modifier.width(14.dp))
      Column(Modifier.weight(1f)) {
        // mDNS-имя беспроводной отладки — «adb-<серийный номер>-<случайный хвост>»: показываем только серийник
        val wireless = tv.kind == FoundTv.Kind.WIRELESS_DEBUG
        val serial = if (wireless) tv.name.removePrefix("adb-").substringBeforeLast('-') else ""
        Text(
          when {
            wireless -> "Беспроводная отладка (Android 11+)"
            tv.name.isNotBlank() -> tv.name
            else -> "Телевизор с отладкой по сети"
          },
          style = MaterialTheme.typography.titleMedium, color = C.Text,
        )
        Text(tv.address + if (serial.isNotBlank()) " · $serial" else "", style = MaterialTheme.typography.bodySmall, color = C.TextDim)
      }
      Chip("Подключить", C.Teal, filled = true)
    }
  }
}

@Composable
private fun ManualIpDialog(onDismiss: () -> Unit, onConnect: (String) -> Unit) {
  var ip by rememberSaveable { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = C.SurfaceHigh,
    title = { Text("IP-адрес телевизора", color = C.Text) },
    text = {
      Column {
        Text("На ТВ: Настройки → Сеть → ваша сеть. Порт можно не писать — по умолчанию 5555.", style = MaterialTheme.typography.bodyMedium, color = C.TextDim)
        Spacer(Modifier.height(12.dp))
        AppTextField(ip, { ip = it }, "192.168.1.50", KeyboardType.Uri)
      }
    },
    confirmButton = { TextButton({ if (ip.isNotBlank()) onConnect(ip) }) { Text("Подключить", color = C.Teal) } },
    dismissButton = { TextButton(onDismiss) { Text("Отмена", color = C.TextDim) } },
  )
}

@Composable
fun AppTextField(value: String, onChange: (String) -> Unit, placeholder: String, keyboard: KeyboardType = KeyboardType.Text, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
  OutlinedTextField(
    value = value,
    onValueChange = onChange,
    placeholder = { Text(placeholder, color = C.TextMuted) },
    singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
    shape = RoundedCornerShape(16.dp),
    trailingIcon = trailing,
    colors = OutlinedTextFieldDefaults.colors(
      focusedBorderColor = C.Teal,
      unfocusedBorderColor = C.StrokeStrong,
      focusedTextColor = C.Text,
      unfocusedTextColor = C.Text,
      cursorColor = C.Teal,
      focusedContainerColor = C.Bg.copy(alpha = 0.4f),
      unfocusedContainerColor = C.Bg.copy(alpha = 0.4f),
    ),
    modifier = modifier.fillMaxWidth(),
  )
}

/** Шесть клеток для кода — как в банковских приложениях */
@Composable
fun CodeInput(code: String, onChange: (String) -> Unit, length: Int = 6) {
  BasicTextField(
    value = code,
    onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(length)) },
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    singleLine = true,
    decorationBox = {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        repeat(length) { i ->
          val ch = code.getOrNull(i)?.toString().orEmpty()
          val active = i == code.length
          Box(
            Modifier
              .weight(1f)
              .aspectRatio(0.82f)
              .clip(RoundedCornerShape(14.dp))
              .background(C.Bg.copy(alpha = 0.5f))
              .border(if (active) 2.dp else 1.dp, if (active) C.Teal else C.StrokeStrong, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
          ) {
            Text(ch, style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, color = C.Text))
          }
        }
      }
    },
  )
}

@Composable
fun PairScreen(vm: MainViewModel) {
  val found = vm.found
  val pairSvc = found.firstOrNull { it.kind == FoundTv.Kind.PAIRING }
  val connectSvc = found.firstOrNull { it.kind == FoundTv.Kind.WIRELESS_DEBUG }
  var pairAddr by rememberSaveable { mutableStateOf(pairSvc?.address.orEmpty()) }
  var connectAddr by rememberSaveable { mutableStateOf(connectSvc?.address.orEmpty()) }
  var code by rememberSaveable { mutableStateOf("") }
  LaunchedEffect(pairSvc, connectSvc) {
    if (pairAddr.isBlank() && pairSvc != null) pairAddr = pairSvc.address
    if (connectAddr.isBlank() && connectSvc != null) connectAddr = connectSvc.address
  }
  Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
    TopBar("Подключение по коду", onBack = { vm.back() })
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
      Hint("Для Android 11 и новее без «Отладки по сети». На ТВ: «Для разработчиков» → «Беспроводная отладка» → включить → «Подключить по коду».", Icons.Rounded.WifiFind, C.Sky)
      Spacer(Modifier.height(18.dp))
      SectionTitle("Код с экрана телевизора")
      CodeInput(code, { code = it })
      Spacer(Modifier.height(18.dp))
      SectionTitle("IP-адрес и порт из окна с кодом")
      AppTextField(pairAddr, { pairAddr = it }, "192.168.1.50:37123", KeyboardType.Uri)
      if (pairSvc != null) Text("Нашёл автоматически", style = MaterialTheme.typography.bodySmall, color = C.Ok, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
      Spacer(Modifier.height(14.dp))
      SectionTitle("IP-адрес и порт с главного экрана отладки")
      AppTextField(connectAddr, { connectAddr = it }, "192.168.1.50:41235", KeyboardType.Uri)
      Text(
        if (connectSvc != null) "Нашёл автоматически" else "Там другой порт, не как у окна с кодом. Можно оставить пустым — найду сам.",
        style = MaterialTheme.typography.bodySmall,
        color = if (connectSvc != null) C.Ok else C.TextMuted,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
      )
      vm.pairError?.let {
        Spacer(Modifier.height(14.dp))
        Hint(it, color = C.Fail)
      }
    }
    GradientButton(
      "Подключить",
      { vm.pairAndConnect(pairAddr, code, connectAddr) },
      Modifier.fillMaxWidth().padding(20.dp),
      icon = Icons.Rounded.Link,
      enabled = code.length == 6 && pairAddr.isNotBlank(),
      loading = vm.pairing,
    )
  }
}

@Composable
fun ConnectingScreen(vm: MainViewModel) {
  val error = vm.connectError
  Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
    TopBar("Подключение", onBack = { vm.back() })
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      TvConfirmIllustration(Modifier.fillMaxWidth().aspectRatio(1.35f))
      if (error == null) {
        Text("Подтвердите на телевизоре", style = MaterialTheme.typography.headlineSmall, color = C.Text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
          "Появится окно «Разрешить отладку?». Отметьте «Всегда разрешать» и нажмите «ОК» пультом — тогда в следующий раз спрашивать не будет.",
          style = MaterialTheme.typography.bodyLarge,
          color = C.TextDim,
          textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          CircularProgressIndicator(Modifier.size(22.dp), color = C.Teal, strokeWidth = 2.5.dp)
          Spacer(Modifier.width(12.dp))
          Text("Подключаюсь к ${vm.connectTarget}", style = MaterialTheme.typography.bodyMedium, color = C.TextDim)
        }
        Spacer(Modifier.height(8.dp))
        Text("Жду подтверждения до ${TvConnection.CONFIRM_SECONDS} секунд", style = MaterialTheme.typography.bodySmall, color = C.TextMuted)
      } else {
        Text("Не получилось", style = MaterialTheme.typography.headlineSmall, color = C.Text)
        Spacer(Modifier.height(12.dp))
        Hint(error, color = C.Fail)
      }
    }
    if (error != null) {
      Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GhostButton("Назад", { vm.back() }, Modifier.weight(1f))
        GradientButton("Ещё раз", { vm.retryConnect() }, Modifier.weight(1f), Icons.Rounded.Refresh)
      }
    } else {
      GhostButton("Отмена", { vm.back() }, Modifier.fillMaxWidth().padding(20.dp))
    }
  }
}
