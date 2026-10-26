package ru.bysonic.chistytv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import ru.bysonic.chistytv.ui.ChistyApp
import ru.bysonic.chistytv.ui.theme.ChistyTVTheme

class MainActivity : ComponentActivity() {
  private val vm: MainViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Тёмные системные панели под тёмный интерфейс
    enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
    setContent { ChistyTVTheme { ChistyApp(vm, ::finish) } }
  }
}
