package ru.bysonic.chistytv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate as rotateDraw
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.bysonic.chistytv.core.Line
import ru.bysonic.chistytv.core.Tone
import ru.bysonic.chistytv.ui.theme.C

/** Фон всех экранов: тёмный с мягким свечением фирменных цветов по углам */
@Composable
fun GlowBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
  Box(
    modifier
      .fillMaxSize()
      .background(C.Bg)
      .drawBehind {
        drawCircle(
          Brush.radialGradient(listOf(C.Violet.copy(alpha = 0.28f), Color.Transparent), center = Offset(size.width * 0.1f, size.height * 0.04f), radius = size.width * 0.9f),
          radius = size.width * 0.9f, center = Offset(size.width * 0.1f, size.height * 0.04f),
        )
        drawCircle(
          Brush.radialGradient(listOf(C.Teal.copy(alpha = 0.16f), Color.Transparent), center = Offset(size.width * 0.95f, size.height * 0.98f), radius = size.width * 0.85f),
          radius = size.width * 0.85f, center = Offset(size.width * 0.95f, size.height * 0.98f),
        )
      },
    content = content,
  )
}

@Composable
fun GradientButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  enabled: Boolean = true,
  loading: Boolean = false,
  brush: Brush = C.Brand,
) {
  val shape = RoundedCornerShape(18.dp)
  Box(
    modifier
      .heightIn(min = 56.dp)
      .shadow(if (enabled) 18.dp else 0.dp, shape, ambientColor = C.Violet, spotColor = C.Violet)
      .clip(shape)
      .background(if (enabled) brush else Brush.linearGradient(listOf(C.SurfaceHigh, C.SurfaceHigh)))
      .clickable(enabled = enabled && !loading, onClick = onClick)
      .padding(horizontal = 22.dp),
    contentAlignment = Alignment.Center,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
      if (loading) {
        CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.5.dp)
        Spacer(Modifier.width(12.dp))
      } else if (icon != null) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
      }
      Text(text, color = if (enabled) Color.White else C.TextMuted, style = MaterialTheme.typography.labelLarge, fontSize = 16.sp)
    }
  }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, color: Color = C.Text, enabled: Boolean = true) {
  val shape = RoundedCornerShape(16.dp)
  Row(
    modifier
      .heightIn(min = 50.dp)
      .clip(shape)
      .border(1.dp, if (color == C.Text) C.StrokeStrong else color.copy(alpha = 0.5f), shape)
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = 18.dp)
      .alpha(if (enabled) 1f else 0.4f),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Center,
  ) {
    if (icon != null) {
      Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
      Spacer(Modifier.width(8.dp))
    }
    Text(text, color = color, style = MaterialTheme.typography.labelLarge)
  }
}

@Composable
fun GlassCard(
  modifier: Modifier = Modifier,
  border: Brush? = null,
  padding: PaddingValues = PaddingValues(18.dp),
  onClick: (() -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  val shape = RoundedCornerShape(24.dp)
  Column(
    modifier
      .clip(shape)
      .background(Brush.verticalGradient(listOf(C.SurfaceHigh.copy(alpha = 0.92f), C.Surface.copy(alpha = 0.92f))))
      .border(1.dp, border ?: Brush.linearGradient(listOf(C.StrokeStrong, C.Stroke)), shape)
      .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
      .padding(padding),
    content = content,
  )
}

/** Круглый значок с градиентом — у каждого раздела свой */
@Composable
fun IconBadge(icon: ImageVector, colors: List<Color>, size: Dp = 48.dp) {
  Box(
    Modifier
      .size(size)
      .clip(RoundedCornerShape(size * 0.32f))
      .background(Brush.linearGradient(colors)),
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.52f))
  }
}

@Composable
fun Chip(text: String, color: Color = C.TextDim, filled: Boolean = false) {
  val shape = RoundedCornerShape(50)
  Text(
    text,
    color = if (filled) Color.White else color,
    style = MaterialTheme.typography.labelMedium,
    modifier = Modifier
      .clip(shape)
      .background(if (filled) color.copy(alpha = 0.9f) else color.copy(alpha = 0.12f))
      .padding(horizontal = 10.dp, vertical = 5.dp),
  )
}

@Composable
fun ActionTile(icon: ImageVector, colors: List<Color>, title: String, subtitle: String, badge: String? = null, badgeColor: Color = C.Teal, onClick: () -> Unit) {
  GlassCard(Modifier.fillMaxWidth(), onClick = onClick, padding = PaddingValues(16.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      IconBadge(icon, colors)
      Spacer(Modifier.width(14.dp))
      Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(title, style = MaterialTheme.typography.titleMedium, color = C.Text, modifier = Modifier.weight(1f, fill = false))
          if (badge != null) {
            Spacer(Modifier.width(8.dp))
            Chip(badge, badgeColor)
          }
        }
        Spacer(Modifier.height(3.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = C.TextDim)
      }
      Spacer(Modifier.width(8.dp))
      Icon(Icons.AutoMirrored.Rounded.ArrowForwardIos, null, tint = C.TextMuted, modifier = Modifier.size(16.dp))
    }
  }
}

/** Шапка экрана с кнопкой «назад» */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
  Row(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    if (onBack != null) {
      IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = C.Text) }
    } else {
      Spacer(Modifier.width(16.dp))
    }
    Text(title, style = MaterialTheme.typography.titleLarge, color = C.Text)
  }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
  Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = C.TextMuted, modifier = modifier.padding(start = 4.dp, bottom = 8.dp))
}

private fun toneIcon(t: Tone): Pair<ImageVector, Color> = when (t) {
  Tone.OK -> Icons.Rounded.CheckCircle to C.Ok
  Tone.WARN -> Icons.Rounded.WarningAmber to C.Warn
  Tone.FAIL -> Icons.Rounded.Close to C.Fail
  Tone.HINT -> Icons.Rounded.Lightbulb to C.Sky
  Tone.INFO -> Icons.Rounded.Info to C.TextDim
}

/** Живой журнал операции: строки появляются по мере работы, как в окне скрипта */
@Composable
fun LogPanel(lines: List<Line>, busy: Boolean, modifier: Modifier = Modifier) {
  AnimatedVisibility(lines.isNotEmpty() || busy, enter = fadeIn() + expandVertically()) {
    GlassCard(modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
      lines.forEachIndexed { i, l ->
        val (icon, color) = toneIcon(l.tone)
        Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
          Icon(icon, null, tint = color, modifier = Modifier.size(18.dp).padding(top = 1.dp))
          Spacer(Modifier.width(10.dp))
          Text(
            l.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (l.tone == Tone.INFO) C.TextDim else C.Text,
            fontWeight = if (l.tone == Tone.FAIL || (l.tone == Tone.OK && i == lines.lastIndex)) FontWeight.SemiBold else FontWeight.Normal,
          )
        }
      }
      if (busy) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
          CircularProgressIndicator(Modifier.size(16.dp), color = C.Teal, strokeWidth = 2.dp)
          Spacer(Modifier.width(10.dp))
          Text("Работаю с телевизором…", style = MaterialTheme.typography.bodyMedium, color = C.TextDim)
        }
      }
    }
  }
}

/** Радар поиска: расходящиеся волны и вращающийся луч вокруг значка телевизора */
@Composable
fun Radar(active: Boolean, modifier: Modifier = Modifier, center: @Composable BoxScope.() -> Unit) {
  val t = rememberInfiniteTransition(label = "radar")
  val wave by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "wave")
  val sweep by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "sweep")
  val pulse by t.animateFloat(0.92f, 1.04f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "pulse")
  Box(modifier, contentAlignment = Alignment.Center) {
    Canvas(Modifier.fillMaxSize()) {
      val r = size.minDimension / 2
      for (k in 1..3) drawCircle(C.StrokeStrong, radius = r * k / 3f, style = Stroke(1.dp.toPx()))
      if (active) {
        for (k in 0..2) {
          val p = (wave + k / 3f) % 1f
          drawCircle(C.Teal.copy(alpha = (1f - p) * 0.45f), radius = r * (0.25f + p * 0.75f), style = Stroke(2.dp.toPx()))
        }
        rotateDraw(sweep) {
          drawArc(
            Brush.sweepGradient(listOf(Color.Transparent, C.Violet.copy(alpha = 0.0f), C.Violet.copy(alpha = 0.45f)), center = this.center),
            startAngle = -60f, sweepAngle = 60f, useCenter = true,
          )
        }
      }
    }
    Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
      Box(
        Modifier.size(92.dp * (if (active) pulse else 1f)).clip(CircleShape).background(C.Brand),
        contentAlignment = Alignment.Center,
        content = center,
      )
    }
  }
}

/** Рисунок телевизора с окном «Разрешить отладку?» — чтобы было понятно, что искать на экране ТВ */
@Composable
fun TvConfirmIllustration(modifier: Modifier = Modifier) {
  val t = rememberInfiniteTransition(label = "tv")
  val glow by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "glow")
  Box(modifier, contentAlignment = Alignment.Center) {
    Canvas(Modifier.fillMaxSize()) {
      val w = size.width
      val h = size.height
      val tvW = w * 0.92f
      val tvH = tvW * 0.58f
      val left = (w - tvW) / 2
      val top = (h - tvH) / 2 - h * 0.04f
      drawRoundRect(C.SurfaceHigh, Offset(left, top), Size(tvW, tvH), CornerRadius(22f))
      drawRoundRect(C.StrokeStrong, Offset(left, top), Size(tvW, tvH), CornerRadius(22f), style = Stroke(3f))
      drawRoundRect(Color(0xFF0D1324), Offset(left + 14f, top + 14f), Size(tvW - 28f, tvH - 28f), CornerRadius(12f))
      // подставка
      drawRoundRect(C.StrokeStrong, Offset(w / 2 - tvW * 0.16f, top + tvH + 10f), Size(tvW * 0.32f, 8f), CornerRadius(4f))
      // окно-диалог
      val dW = tvW * 0.62f
      val dH = tvH * 0.56f
      val dl = (w - dW) / 2
      val dt = top + (tvH - dH) / 2
      drawRoundRect(Color(0xFF1E2740), Offset(dl, dt), Size(dW, dH), CornerRadius(14f))
      drawRoundRect(C.Text.copy(alpha = 0.85f), Offset(dl + dW * 0.08f, dt + dH * 0.16f), Size(dW * 0.6f, dH * 0.08f), CornerRadius(6f))
      drawRoundRect(C.TextMuted, Offset(dl + dW * 0.08f, dt + dH * 0.32f), Size(dW * 0.8f, dH * 0.05f), CornerRadius(4f))
      drawRoundRect(C.TextMuted, Offset(dl + dW * 0.08f, dt + dH * 0.42f), Size(dW * 0.55f, dH * 0.05f), CornerRadius(4f))
      // чекбокс «Всегда»
      drawRoundRect(C.Teal, Offset(dl + dW * 0.08f, dt + dH * 0.56f), Size(dH * 0.11f, dH * 0.11f), CornerRadius(4f))
      drawRoundRect(C.TextDim, Offset(dl + dW * 0.08f + dH * 0.16f, dt + dH * 0.585f), Size(dW * 0.42f, dH * 0.05f), CornerRadius(4f))
      // кнопка «ОК» пульсирует
      drawRoundRect(C.Teal.copy(alpha = glow), Offset(dl + dW * 0.62f, dt + dH * 0.76f), Size(dW * 0.3f, dH * 0.15f), CornerRadius(10f))
      drawRoundRect(C.TextMuted.copy(alpha = 0.5f), Offset(dl + dW * 0.28f, dt + dH * 0.76f), Size(dW * 0.3f, dH * 0.15f), CornerRadius(10f))
    }
  }
}

@Composable
fun Hint(text: String, icon: ImageVector = Icons.Rounded.Lightbulb, color: Color = C.Sky, modifier: Modifier = Modifier) {
  Row(
    modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(18.dp))
      .background(color.copy(alpha = 0.1f))
      .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
      .padding(14.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(10.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium, color = C.Text)
  }
}

@Composable
fun Centered(text: String, modifier: Modifier = Modifier) {
  Text(text, modifier = modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = C.TextDim)
}
