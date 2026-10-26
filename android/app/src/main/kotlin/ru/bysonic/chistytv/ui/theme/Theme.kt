package ru.bysonic.chistytv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val scheme = darkColorScheme(
  primary = C.Violet,
  onPrimary = C.Text,
  secondary = C.Teal,
  onSecondary = C.Bg,
  tertiary = C.Pink,
  background = C.Bg,
  onBackground = C.Text,
  surface = C.Surface,
  onSurface = C.Text,
  surfaceVariant = C.SurfaceHigh,
  onSurfaceVariant = C.TextDim,
  surfaceContainer = C.Surface,
  surfaceContainerHigh = C.SurfaceHigh,
  surfaceContainerHighest = C.SurfaceHigh,
  outline = C.StrokeStrong,
  outlineVariant = C.Stroke,
  error = C.Fail,
)

/** ЧистыйТВ всегда тёмный: телевизионная эстетика и фирменный градиент лучше всего смотрятся на тёмном */
@Composable
fun ChistyTVTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}
