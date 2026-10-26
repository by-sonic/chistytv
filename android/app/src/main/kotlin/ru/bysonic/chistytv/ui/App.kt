package ru.bysonic.chistytv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import ru.bysonic.chistytv.MainViewModel
import ru.bysonic.chistytv.Screen

private val depth = mapOf(
  Screen.Start to 0, Screen.Search to 1, Screen.Pair to 2, Screen.Connecting to 3, Screen.Home to 4,
  Screen.Clean to 5, Screen.YouTube to 5, Screen.Launcher to 5, Screen.Dns to 5, Screen.Restore to 5,
)

@Composable
fun ChistyApp(vm: MainViewModel, finish: () -> Unit) {
  BackHandler { if (!vm.back()) finish() }
  GlowBackground {
    AnimatedContent(
      targetState = vm.screen,
      transitionSpec = {
        val forward = (depth[targetState] ?: 0) >= (depth[initialState] ?: 0)
        val dir = if (forward) 1 else -1
        (slideInHorizontally(tween(320)) { it / 5 * dir } + fadeIn(tween(320))) togetherWith
          (slideOutHorizontally(tween(260)) { -it / 6 * dir } + fadeOut(tween(200)))
      },
      label = "screen",
    ) { s ->
      when (s) {
        Screen.Start -> StartScreen(vm)
        Screen.Search -> SearchScreen(vm)
        Screen.Pair -> PairScreen(vm)
        Screen.Connecting -> ConnectingScreen(vm)
        Screen.Home -> HomeScreen(vm)
        Screen.Clean -> CleanScreen(vm)
        Screen.YouTube -> YouTubeScreen(vm)
        Screen.Launcher -> LauncherScreen(vm)
        Screen.Dns -> DnsScreen(vm)
        Screen.Restore -> RestoreScreen(vm)
      }
    }
  }
}
